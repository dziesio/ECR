package com.ecrharv.harvester.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScrapingScheduler {

    private final TaskScheduler               taskScheduler;
    private final LibrusScraperService        scraperService;
    private final BritishCouncilScraperService bcScraperService;
    private final DataPersistenceService      persistenceService;

    @Value("${librus.username}")
    private String username;

    @Value("${librus.full-name:Unknown Student}")
    private String fullName;

    @Value("${scraper.interval-minutes:10}")
    private int intervalMinutes;

    @Value("${scraper.jitter-minutes:3}")
    private int jitterMinutes;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("ScrapingScheduler online — running initial scrape immediately, then every {} min ± {} min",
                intervalMinutes, jitterMinutes);
        taskScheduler.schedule(this::runAndReschedule, Instant.now());
    }

    private void scheduleNext() {
        long jitterSeconds = ThreadLocalRandom.current().nextLong(
                -jitterMinutes * 60L,
                 jitterMinutes * 60L
        );
        long delaySeconds = Math.max(60L, intervalMinutes * 60L + jitterSeconds);

        Instant fireAt = Instant.now().plus(Duration.ofSeconds(delaySeconds));
        log.info("Next scrape in {} s ({} min)", delaySeconds, delaySeconds / 60);

        taskScheduler.schedule(this::runAndReschedule, fireAt);
    }

    private void runAndReschedule() {
        log.info("Scraping session starting");
        try {
            var knownMessageIds      = persistenceService.getExistingMessageIds();
            var knownAnnouncementIds = persistenceService.getExistingAnnouncementIds();
            var result = scraperService.scrapeAll(knownMessageIds, knownAnnouncementIds);

            String scrapedName  = result.studentInfo() != null ? result.studentInfo().fullName()  : fullName;
            String scrapedClass = result.studentInfo() != null ? result.studentInfo().className() : null;
            var student = persistenceService.findOrCreateStudent(username, scrapedName, scrapedClass);

            persistenceService.saveGrades(student, result.grades());

            var bcResult = bcScraperService.scrapeMessages(knownMessageIds);
            persistenceService.linkStudentBcId(bcResult.bcId(), bcResult.bcFullName());

            var allMessages = new ArrayList<>(result.messages());
            allMessages.addAll(bcResult.messages());
            persistenceService.saveMessages(student, allMessages);

            persistenceService.saveAttendance(student, result.attendance());
            persistenceService.saveAnnouncements(student, result.announcements());

            log.info("Scraping session completed");
        } catch (Exception e) {
            log.error("Scraping session failed: {}", e.getMessage(), e);
        } finally {
            scheduleNext();
        }
    }
}
