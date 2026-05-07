package com.ecrharv.harvester.service;

import com.ecrharv.harvester.config.SeleniumConfig;
import com.ecrharv.harvester.scraping.ScrapedData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openqa.selenium.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibrusScraperServiceTest {

    @Mock private SeleniumConfig seleniumConfig;

    @InjectMocks private LibrusScraperService service;

    private WebDriver driver;

    @BeforeEach
    void setUp() {
        driver = mock(WebDriver.class, withSettings().extraInterfaces(JavascriptExecutor.class));
        when(seleniumConfig.createDriver()).thenReturn(driver);

        // Inject @Value fields — no Spring context needed
        ReflectionTestUtils.setField(service, "username",             "testuser");
        ReflectionTestUtils.setField(service, "password",             "testpass");
        ReflectionTestUtils.setField(service, "urlLogin",             "https://portal.librus.pl/login");
        ReflectionTestUtils.setField(service, "urlGrades",            "https://synergia.librus.pl/grades");
        ReflectionTestUtils.setField(service, "urlMessages",          "https://synergia.librus.pl/messages");
        ReflectionTestUtils.setField(service, "urlAttendance",        "https://synergia.librus.pl/attendance");
        ReflectionTestUtils.setField(service, "urlAnnouncements",     "https://synergia.librus.pl/announcements");
        // Zero-out all delays so tests run in milliseconds
        ReflectionTestUtils.setField(service, "pageJitterMinMs",      0L);
        ReflectionTestUtils.setField(service, "pageJitterMaxMs",      1L);
        ReflectionTestUtils.setField(service, "humanTypeDelayMinMs",  0L);
        ReflectionTestUtils.setField(service, "humanTypeDelayMaxMs",  1L);
    }

    // ── Driver lifecycle ──────────────────────────────────────────────────────

    @Test
    void scrapeAll_closesDriverOnSuccess() {
        setupSuccessfulLogin();
        when(driver.findElements(any(By.class))).thenReturn(List.of());

        service.scrapeAll(Set.of(), Set.of());

        verify(driver).quit();
    }

    @Test
    void scrapeAll_closesDriverEvenWhenExceptionIsThrown() {
        doThrow(new WebDriverException("Network error")).when(driver).get(anyString());

        assertThatThrownBy(() -> service.scrapeAll(Set.of(), Set.of()))
                .isInstanceOf(RuntimeException.class);

        verify(driver).quit();
    }

    @Test
    void scrapeAll_wrapsExceptionsInRuntimeException() {
        doThrow(new WebDriverException("Crashed")).when(driver).get(anyString());

        assertThatThrownBy(() -> service.scrapeAll(Set.of(), Set.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Scraping session failed");
    }

    @Test
    void scrapeAll_createsExactlyOneDriver() {
        setupSuccessfulLogin();
        when(driver.findElements(any(By.class))).thenReturn(List.of());

        service.scrapeAll(Set.of(), Set.of());

        verify(seleniumConfig, times(1)).createDriver();
    }

    // ── Result structure ──────────────────────────────────────────────────────

    @Test
    void scrapeAll_returnsEmptyListsWhenTablesNotFound() {
        setupSuccessfulLogin();
        when(driver.findElements(any(By.class))).thenReturn(List.of());

        ScrapedData.ScrapeResult result = service.scrapeAll(Set.of(), Set.of());

        assertThat(result.grades()).isEmpty();
        assertThat(result.messages()).isEmpty();
        assertThat(result.attendance()).isEmpty();
    }

    @Test
    void scrapeAll_navigatesToAllThreeTargetUrls() {
        setupSuccessfulLogin();
        when(driver.findElements(any(By.class))).thenReturn(List.of());

        service.scrapeAll(Set.of(), Set.of());

        verify(driver, atLeast(1)).get("https://synergia.librus.pl/grades");
        verify(driver, atLeast(1)).get("https://synergia.librus.pl/messages/5");
        verify(driver, atLeast(1)).get("https://synergia.librus.pl/messages/6");
        verify(driver, atLeast(1)).get("https://synergia.librus.pl/attendance");
        verify(driver, atLeast(1)).get("https://synergia.librus.pl/announcements");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Configures the mock WebDriver so that the login sequence succeeds:
     * - any findElement() returns a clickable, displayable mock element
     * - getCurrentUrl() returns a synergia.librus.pl URL (satisfies urlContains check)
     */
    private void setupSuccessfulLogin() {
        WebElement mockElement = mock(WebElement.class);
        when(mockElement.isDisplayed()).thenReturn(true);
        when(mockElement.isEnabled()).thenReturn(true);
        when(driver.findElement(any(By.class))).thenReturn(mockElement);
        when(driver.getCurrentUrl()).thenReturn("https://synergia.librus.pl/dashboard");
    }
}
