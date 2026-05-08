package com.ecrharv.harvester.service;

import com.ecrharv.harvester.config.LibrusHttpClient;
import com.ecrharv.harvester.config.LibrusSession;
import com.ecrharv.harvester.scraping.ScrapedData;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibrusScraperServiceTest {

    @Mock private LibrusHttpClient librusHttpClient;
    @Mock private LibrusSession session;

    @InjectMocks private LibrusScraperService service;

    @BeforeEach
    void setUp() throws IOException {
        when(librusHttpClient.openSession()).thenReturn(session);
        when(session.getPage(anyString()))
                .thenReturn(Jsoup.parse("<html><body></body></html>"));

        ReflectionTestUtils.setField(service, "urlGrades",        "https://synergia.librus.pl/grades");
        ReflectionTestUtils.setField(service, "urlMessages",      "https://synergia.librus.pl/messages");
        ReflectionTestUtils.setField(service, "urlAttendance",    "https://synergia.librus.pl/attendance");
        ReflectionTestUtils.setField(service, "urlAnnouncements", "https://synergia.librus.pl/announcements");
        ReflectionTestUtils.setField(service, "pageJitterMinMs",  0L);
        ReflectionTestUtils.setField(service, "pageJitterMaxMs",  1L);
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    @Test
    void scrapeAll_closesSessionOnSuccess() throws IOException {
        service.scrapeAll(Set.of(), Set.of());
        verify(session).close();
    }

    @Test
    void scrapeAll_closesSessionEvenWhenExceptionIsThrown() throws IOException {
        when(session.getPage(anyString())).thenThrow(new IOException("Network error"));

        assertThatThrownBy(() -> service.scrapeAll(Set.of(), Set.of()))
                .isInstanceOf(RuntimeException.class);

        verify(session).close();
    }

    @Test
    void scrapeAll_wrapsExceptionsInRuntimeException() throws IOException {
        when(session.getPage(anyString())).thenThrow(new IOException("Timeout"));

        assertThatThrownBy(() -> service.scrapeAll(Set.of(), Set.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Scraping session failed");
    }

    @Test
    void scrapeAll_createsExactlyOneSession() throws IOException {
        service.scrapeAll(Set.of(), Set.of());
        verify(librusHttpClient, times(1)).openSession();
    }

    // ── Result structure ──────────────────────────────────────────────────────

    @Test
    void scrapeAll_returnsEmptyListsWhenTablesNotFound() {
        ScrapedData.ScrapeResult result = service.scrapeAll(Set.of(), Set.of());

        assertThat(result.grades()).isEmpty();
        assertThat(result.messages()).isEmpty();
        assertThat(result.attendance()).isEmpty();
        assertThat(result.announcements()).isEmpty();
    }

    @Test
    void scrapeAll_navigatesToAllTargetUrls() throws IOException {
        service.scrapeAll(Set.of(), Set.of());

        verify(session, atLeast(1)).getPage("https://synergia.librus.pl/grades");
        verify(session, atLeast(1)).getPage("https://synergia.librus.pl/messages/5");
        verify(session, atLeast(1)).getPage("https://synergia.librus.pl/messages/6");
        verify(session, atLeast(1)).getPage("https://synergia.librus.pl/attendance");
        verify(session, atLeast(1)).getPage("https://synergia.librus.pl/announcements");
    }
}
