package com.ecrharv.harvester.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    @Value("${scraper.peak-interval-minutes:15}")
    private int peakIntervalMinutes;

    @Value("${scraper.offpeak-interval-minutes:60}")
    private int offPeakIntervalMinutes;

    @Value("${scraper.jitter-minutes:3}")
    private int jitterMinutes;

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("ScrapingScheduler online — peak {}±{} min (Mon–Fri 06–17 Warsaw), off-peak {}±{} min",
                peakIntervalMinutes, jitterMinutes, offPeakIntervalMinutes, jitterMinutes);
        taskScheduler.schedule(this::runAndReschedule, Instant.now());
    }

    private boolean isPeakTime() {
        ZonedDateTime now = ZonedDateTime.now(WARSAW);
        DayOfWeek day = now.getDayOfWeek();
        int hour = now.getHour();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && hour >= 6 && hour < 17;
    }

    private void scheduleNext() {
        boolean peak = isPeakTime();
        int baseMinutes = peak ? peakIntervalMinutes : offPeakIntervalMinutes;
        long jitterSeconds = ThreadLocalRandom.current().nextLong(
                -jitterMinutes * 60L,
                 jitterMinutes * 60L
        );
        long delaySeconds = Math.max(60L, baseMinutes * 60L + jitterSeconds);

        Instant fireAt = Instant.now().plus(Duration.ofSeconds(delaySeconds));
        log.info("Next scrape in {} s ({} min) [{}]", delaySeconds, delaySeconds / 60,
                peak ? "peak" : "off-peak");

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
