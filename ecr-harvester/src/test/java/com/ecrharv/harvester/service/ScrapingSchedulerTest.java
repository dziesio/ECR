package com.ecrharv.harvester.service;

import com.ecrharv.harvester.entity.Student;
import com.ecrharv.harvester.scraping.ScrapedData;
import com.ecrharv.harvester.scraping.ScrapedData.BcScrapeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScrapingSchedulerTest {

    @Mock private TaskScheduler               taskScheduler;
    @Mock private LibrusScraperService        scraperService;
    @Mock private BritishCouncilScraperService bcScraperService;
    @Mock private DataPersistenceService      persistenceService;

    @InjectMocks private ScrapingScheduler scheduler;

    private static final int INTERVAL_MINUTES = 10;
    private static final int JITTER_MINUTES   = 3;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "username",        "testuser");
        ReflectionTestUtils.setField(scheduler, "fullName",        "Test User");
        ReflectionTestUtils.setField(scheduler, "intervalMinutes", INTERVAL_MINUTES);
        ReflectionTestUtils.setField(scheduler, "jitterMinutes",   JITTER_MINUTES);
        lenient().when(persistenceService.getExistingMessageIds()).thenReturn(Set.of());
        lenient().when(persistenceService.getExistingAnnouncementIds()).thenReturn(Set.of());
        lenient().when(bcScraperService.scrapeMessages(any())).thenReturn(BcScrapeResult.empty());
    }

    // ── start() ───────────────────────────────────────────────────────────────

    @Test
    void start_schedulesExactlyOneRun() {
        scheduler.start();
        verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void start_schedulesImmediately() {
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        Instant before = Instant.now();

        scheduler.start();

        verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
        assertThat(captor.getValue()).isBetween(before.minusSeconds(1), before.plusSeconds(1));
    }

    // ── successful run ────────────────────────────────────────────────────────

    @Test
    void run_callsAllPersistenceMethods() {
        Student student = new Student();
        var result = new ScrapedData.ScrapeResult(null, List.of(), List.of(), List.of(), List.of());

        when(scraperService.scrapeAll(any(), any())).thenReturn(result);
        when(persistenceService.findOrCreateStudent(any(), any(), any())).thenReturn(student);

        invokeScheduledRunnable();

        verify(persistenceService).findOrCreateStudent("testuser", "Test User", null);
        verify(persistenceService).linkStudentBcId("", "");
        verify(persistenceService).saveGrades(student, List.of());
        verify(persistenceService).saveMessages(student, List.of());
        verify(persistenceService).saveAttendance(student, List.of());
        verify(persistenceService).saveAnnouncements(student, List.of());
    }

    @Test
    void run_reschedulesAfterSuccess() {
        when(scraperService.scrapeAll(any(), any()))
                .thenReturn(new ScrapedData.ScrapeResult(null, List.of(), List.of(), List.of(), List.of()));
        when(persistenceService.findOrCreateStudent(any(), any(), any())).thenReturn(new Student());

        invokeScheduledRunnable();

        // start() + reschedule inside the finally block = 2 total schedule() calls
        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    // ── failure handling ──────────────────────────────────────────────────────

    @Test
    void run_doesNotThrowOnScrapingException() {
        when(scraperService.scrapeAll(any(), any())).thenThrow(new RuntimeException("Browser crashed"));

        assertThatCode(this::invokeScheduledRunnable).doesNotThrowAnyException();
    }

    @Test
    void run_reschedulesEvenAfterException() {
        when(scraperService.scrapeAll(any(), any())).thenThrow(new RuntimeException("Browser crashed"));

        invokeScheduledRunnable();

        verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void run_doesNotCallPersistenceWhenScrapingFails() {
        when(scraperService.scrapeAll(any(), any())).thenThrow(new RuntimeException("Browser crashed"));

        invokeScheduledRunnable();

        verify(persistenceService, never()).findOrCreateStudent(any(), any(), any());
        verify(persistenceService, never()).saveGrades(any(), any());
        verify(persistenceService, never()).saveMessages(any(), any());
        verify(persistenceService, never()).saveAttendance(any(), any());
        verify(persistenceService, never()).saveAnnouncements(any(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void invokeScheduledRunnable() {
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        scheduler.start();
        verify(taskScheduler, atLeastOnce()).schedule(captor.capture(), any(Instant.class));
        captor.getAllValues().get(0).run();
    }
}
