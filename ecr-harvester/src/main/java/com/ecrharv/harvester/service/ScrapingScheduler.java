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
import com.ecrharv.harvester.scraping.ScrapedData;
import java.util.ArrayList;
import java.util.List;
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

    // BC: Saturday 08–14 → 15 min, all other times → 120 min
    @Value("${bc.scraper.peak-interval-minutes:15}")
    private int bcPeakIntervalMinutes;

    @Value("${bc.scraper.offpeak-interval-minutes:120}")
    private int bcOffpeakIntervalMinutes;

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("ScrapingScheduler online — Librus peak {}±{} min (Mon–Fri 06–17), off-peak {}±{} min",
                peakIntervalMinutes, jitterMinutes, offPeakIntervalMinutes, jitterMinutes);
        log.info("BC schedule — Saturday 08–14: {} min, other: {} min",
                bcPeakIntervalMinutes, bcOffpeakIntervalMinutes);
        taskScheduler.schedule(this::runAndReschedule,   Instant.now());
        taskScheduler.schedule(this::runBcAndReschedule, Instant.now().plusSeconds(30));
    }

    // ── Librus schedule ───────────────────────────────────────────────────────

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
        log.info("Next Librus scrape in {} s ({} min) [{}]", delaySeconds, delaySeconds / 60,
                peak ? "peak" : "off-peak");
        taskScheduler.schedule(this::runAndReschedule, Instant.now().plus(Duration.ofSeconds(delaySeconds)));
    }

    private void runAndReschedule() {
        log.info("Librus scraping session starting");
        try {
            var knownMessageIds      = persistenceService.getExistingMessageIds();
            var knownAnnouncementIds = persistenceService.getExistingAnnouncementIds();

            String studentName  = fullName;
            String studentClass = null;
            List<ScrapedData.MessageRecord> librusMsgs = List.of();
            try {
                var result = scraperService.scrapeAll(knownMessageIds, knownAnnouncementIds);
                if (result.studentInfo() != null) {
                    studentName  = result.studentInfo().fullName();
                    studentClass = result.studentInfo().className();
                }
                var student = persistenceService.findOrCreateStudent(username, studentName, studentClass);
                persistenceService.saveGrades(student, result.grades());
                persistenceService.saveAttendance(student, result.attendance());
                persistenceService.saveAnnouncements(student, result.announcements());
                librusMsgs = result.messages();
            } catch (Exception e) {
                log.error("Librus scraping failed: {}", e.getMessage(), e);
            }

            var student = persistenceService.getOrCreateStudent(username, studentName);
            persistenceService.saveMessages(student, librusMsgs);

            log.info("Librus scraping session completed");
        } catch (Throwable e) {
            log.error("Librus scraping session failed: {}", e.getMessage(), e);
        } finally {
            scheduleNext();
        }
    }

    // ── BC schedule ───────────────────────────────────────────────────────────

    private boolean bcIsPeakTime() {
        ZonedDateTime now = ZonedDateTime.now(WARSAW);
        return now.getDayOfWeek() == DayOfWeek.SATURDAY
                && now.getHour() >= 8
                && now.getHour() < 14;
    }

    private void scheduleBcNext() {
        boolean peak = bcIsPeakTime();
        long delaySeconds = (peak ? bcPeakIntervalMinutes : bcOffpeakIntervalMinutes) * 60L;
        log.info("Next BC scrape in {} min [{}]", delaySeconds / 60,
                peak ? "Saturday peak" : "off-peak");
        taskScheduler.schedule(this::runBcAndReschedule, Instant.now().plus(Duration.ofSeconds(delaySeconds)));
    }

    private void runBcAndReschedule() {
        log.info("BC scraping session starting");
        try {
            var knownMessageIds = persistenceService.getExistingMessageIds();
            var bcResult = bcScraperService.scrapeMessages(knownMessageIds);
            persistenceService.linkStudentBcId(bcResult.bcId(), bcResult.bcFullName());
            var student = persistenceService.getOrCreateStudent(username, fullName);
            persistenceService.saveMessages(student, bcResult.messages());
            log.info("BC scraping session completed");
        } catch (Throwable e) {
            log.error("BC scraping session failed: {}", e.getMessage(), e);
        } finally {
            scheduleBcNext();
        }
    }
}
