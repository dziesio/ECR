package com.ecrharv.harvester.service;

import com.ecrharv.harvester.config.SeleniumConfig;
import com.ecrharv.harvester.enums.MessageSource;
import com.ecrharv.harvester.enums.MessageType;
import com.ecrharv.harvester.scraping.BritishCouncilKeys;
import com.ecrharv.harvester.scraping.ScrapedData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class BritishCouncilScraperService {

    private final SeleniumConfig seleniumConfig;

    @Value("${bc.username:}")
    private String username;

    @Value("${bc.password:}")
    private String password;

    @Value("${bc.enabled:false}")
    private boolean enabled;

    @Value("${scraper.page-jitter-min-ms:1000}")
    private long pageJitterMinMs;

    @Value("${scraper.page-jitter-max-ms:4000}")
    private long pageJitterMaxMs;

    public ScrapedData.BcScrapeResult scrapeMessages(Set<String> knownMessageIds) {
        if (!enabled) {
            log.info("British Council scraper disabled — skipping");
            return ScrapedData.BcScrapeResult.empty();
        }
        if (username.isBlank() || password.isBlank()) {
            log.warn("British Council credentials not configured — skipping");
            return ScrapedData.BcScrapeResult.empty();
        }

        WebDriver driver = seleniumConfig.createDriver();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(20));

        try {
            login(driver, wait);
            String[] userInfo = extractUserInfo(driver);
            String bcId       = userInfo[0];
            String bcFullName = userInfo[1];
            List<ScrapedData.MessageRecord> messages = scrapeNewsFeed(driver, wait, knownMessageIds);
            return new ScrapedData.BcScrapeResult(bcId, bcFullName, messages);
        } catch (Exception e) {
            log.error("British Council scraping failed: {}", e.getMessage(), e);
            return ScrapedData.BcScrapeResult.empty();
        } finally {
            driver.quit();
            log.debug("British Council WebDriver closed");
        }
    }

    // ── Login ────────────────────────────────────────────────────────────────

    private void login(WebDriver driver, WebDriverWait wait) {
        log.info("Navigating to British Council Learning Hub login");
        driver.get(BritishCouncilKeys.URL_LOGIN);
        jitter();

        try {
            WebElement userField = wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector(BritishCouncilKeys.SEL_USERNAME)));
            jsSetValue(driver, userField, username);
            jitter();

            WebElement passField = wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector(BritishCouncilKeys.SEL_PASSWORD)));
            jsSetValue(driver, passField, password);
            jitter();

            wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector(BritishCouncilKeys.SEL_SUBMIT))).click();

            wait.until(ExpectedConditions.urlContains(BritishCouncilKeys.URL_HOME_FRAGMENT));
            jitter(); // let My Courses widget finish its API calls before we look for cards
            log.info("British Council login successful");
        } catch (TimeoutException e) {
            throw new RuntimeException(
                    "British Council login failed — check credentials or verify selectors against the live portal", e);
        }
    }

    // ── User info ─────────────────────────────────────────────────────────────

    private String[] extractUserInfo(WebDriver driver) {
        try {
            jitter(); // let the nav bar render
            String json = (String) ((JavascriptExecutor) driver)
                    .executeScript(BritishCouncilKeys.JS_EXTRACT_USER_INFO);
            if (json == null) return new String[]{"", ""};
            Map<String, String> info = new ObjectMapper().readValue(json, new TypeReference<>() {});
            String bcId   = coalesce(info.get("id"),   "");
            String bcName = coalesce(info.get("name"), "");
            log.info("BC user identified (ou={})", bcId.isBlank() ? "unknown" : bcId);
            return new String[]{bcId, bcName};
        } catch (Exception e) {
            log.warn("Could not extract BC user info from navbar: {}", e.getMessage());
            return new String[]{"", ""};
        }
    }

    // ── News feed ────────────────────────────────────────────────────────────

    private List<ScrapedData.MessageRecord> scrapeNewsFeed(WebDriver driver, WebDriverWait wait,
                                                            Set<String> knownMessageIds) {
        List<ScrapedData.MessageRecord> messages = new ArrayList<>();

        String courseUrl = navigateToFirstCourse(driver, wait);
        if (courseUrl == null) return messages;

        // Wait for the news widget element to appear so the page is ready
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector(BritishCouncilKeys.SEL_NEWS_WIDGET)));
        } catch (TimeoutException e) {
            log.warn("No news widget found on course page {} — BC structure may have changed", courseUrl);
            return messages;
        }

        // Strategy 1: D2L native News REST API (fastest, most reliable)
        messages = fetchNewsViaApi(driver, knownMessageIds);
        if (!messages.isEmpty()) {
            log.info("Scraped {} new BC news item(s) via REST API", messages.size());
            return messages;
        }

        // Strategy 2: Activity Stream shadow DOM piercing (posts may load async)
        log.info("News API returned 0 items — polling Activity Stream for up to 15 s");
        try {
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(20));
            String waitResult = (String) ((JavascriptExecutor) driver)
                    .executeAsyncScript(BritishCouncilKeys.JS_WAIT_FOR_ACTIVITIES_ASYNC);
            log.info("Activity Stream poll result: {}", waitResult);
        } catch (Exception e) {
            log.debug("Activity Stream poll error (will still try piercing): {}", e.getMessage());
        }

        String json;
        try {
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(20));
            json = (String) ((JavascriptExecutor) driver)
                    .executeAsyncScript(BritishCouncilKeys.JS_PIERCE_POSTS_ASYNC);
        } catch (Exception e) {
            log.warn("Shadow DOM piercing failed: {}", e.getMessage());
            return messages;
        }

        if (json == null || json.equals("[]") || json.isBlank()) {
            log.warn("No BC posts found via shadow DOM piercing at {} (activity stream may be empty)", courseUrl);
            return messages;
        }
        try {
            List<Map<String, String>> posts = new ObjectMapper().readValue(json, new TypeReference<>() {});
            log.info("Found {} BC activity post(s) from shadow DOM", posts.size());
            for (Map<String, String> post : posts) {
                String actId   = coalesce(post.get("id"),      "");
                String content = coalesce(post.get("content"), "");
                String author  = coalesce(post.get("author"),  "British Council");
                String dateStr = coalesce(post.get("date"),    "");
                String title   = summarizeAsTitle(content);
                String msgId   = actId.isBlank()
                        ? "bc_" + buildStableId(title, dateStr)
                        : "bc_" + actId;
                if (knownMessageIds.contains(msgId)) {
                    log.debug("Skipping already-stored BC post: '{}'", title);
                    continue;
                }
                messages.add(new ScrapedData.MessageRecord(msgId, MessageType.INBOX,
                        MessageSource.BRITISH_COUNCIL, author, "", title, content, parseDateTime(dateStr)));
                log.debug("Scraped BC post '{}' by '{}'", title, author);
            }
        } catch (Exception e) {
            log.error("Failed to parse BC posts JSON: {}", e.getMessage());
        }

        log.info("Scraped {} new British Council post(s)", messages.size());
        return messages;
    }

    @SuppressWarnings("unchecked")
    private List<ScrapedData.MessageRecord> fetchNewsViaApi(WebDriver driver, Set<String> knownMessageIds) {
        List<ScrapedData.MessageRecord> messages = new ArrayList<>();
        try {
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(15));
            String raw = (String) ((JavascriptExecutor) driver)
                    .executeAsyncScript(BritishCouncilKeys.JS_FETCH_NEWS_ASYNC);
            log.debug("BC News API raw response: {}",
                    raw != null && raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);

            if (raw == null || raw.startsWith("ERR:") || raw.startsWith("HTTP_")) {
                log.warn("BC News API failed: {}", raw);
                return messages;
            }

            List<Map<String, Object>> items = new ObjectMapper().readValue(raw, new TypeReference<>() {});
            log.info("BC News API returned {} item(s)", items.size());
            if (!items.isEmpty()) {
                String firstItem = new ObjectMapper().writeValueAsString(items.get(0));
                log.info("BC News API first item: {}", firstItem);
            }

            for (Map<String, Object> item : items) {
                String newsId  = coalesce(strVal(item, "NewsItemId"), strVal(item, "Id"));
                String title   = coalesce(strVal(item, "Title"), coalesce(strVal(item, "Headline"), "(no title)"));
                String author  = buildAuthorName(item);
                String dateStr = coalesce(strVal(item, "StartDate"), strVal(item, "CreatedDate"));
                Map<String, Object> body = (Map<String, Object>) item.get("Body");
                String content = body != null
                        ? coalesce(strVal(body, "Html"), strVal(body, "Text"))
                        : "";

                String msgId = "bc_news_" + newsId;
                if (knownMessageIds.contains(msgId)) {
                    log.debug("Skipping already-stored BC news: '{}'", title);
                    continue;
                }
                messages.add(new ScrapedData.MessageRecord(msgId, MessageType.INBOX,
                        MessageSource.BRITISH_COUNCIL, author, "", title, content, parseDateTime(dateStr)));
                log.debug("Scraped BC news '{}' by '{}'", title, author);
            }
        } catch (Exception e) {
            log.warn("BC News API fetch/parse failed: {}", e.getMessage());
        }
        return messages;
    }

    @SuppressWarnings("unchecked")
    private String buildAuthorName(Map<String, Object> item) {
        // Pre-extracted by JS from whatever nested structure the API returned
        String precomputed = strVal(item, "_author");
        if (!precomputed.isBlank()) return precomputed;

        // Java-side fallback: try nested objects in case the item was constructed differently
        for (String key : new String[]{"CreatedBy", "Author", "User", "Instructor", "Creator"}) {
            Object nested = item.get(key);
            if (nested instanceof Map<?, ?> raw) {
                Map<String, Object> m = (Map<String, Object>) raw;
                String nFirst = strVal(m, "FirstName");
                String nLast  = strVal(m, "LastName");
                if (!nFirst.isBlank() || !nLast.isBlank()) return (nFirst + " " + nLast).trim();
                String display = strVal(m, "DisplayName");
                if (!display.isBlank()) return display;
            }
        }
        return "British Council";
    }

    private String strVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return "";
        String s = String.valueOf(v).trim();
        return "null".equals(s) ? "" : s;
    }

    private String navigateToFirstCourse(WebDriver driver, WebDriverWait wait) {
        log.info("Looking for first enrolled course on BC home page");
        jitter(); // let page settle after login redirect

        // Scroll down and back to trigger IntersectionObserver on the lazy-loaded My Courses widget
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("window.scrollTo(0, document.body.scrollHeight);");
        jitter();
        js.executeScript("window.scrollTo(0, 0);");
        jitter();

        // Strategy 1: shadow DOM link search (open shadow roots only)
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(15));
            String id = shortWait.until(d ->
                    (String) ((JavascriptExecutor) d).executeScript(BritishCouncilKeys.JS_FIND_COURSE_ID));
            return gotoCoourse(driver, id, "shadow DOM");
        } catch (TimeoutException ignored) {
            log.debug("Shadow DOM course search found nothing — trying enrollments API");
        }

        // Strategy 2: D2L enrollments REST API (works even with closed shadow roots)
        try {
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(20));
            String result = (String) ((JavascriptExecutor) driver)
                    .executeAsyncScript(BritishCouncilKeys.JS_FIND_COURSE_ID_ASYNC);
            if (result != null && !result.startsWith("NO_COURSE") && !result.startsWith("ERR") && !result.startsWith("HTTP_")) {
                return gotoCoourse(driver, result, "enrollments API");
            }
            log.warn("Enrollments API found no course offering");
            return null;
        } catch (Exception e) {
            log.warn("Could not find any course — both strategies failed. URL: {}, title: {}",
                    driver.getCurrentUrl(), driver.getTitle());
            return null;
        }
    }

    private String gotoCoourse(WebDriver driver, String orgUnitId, String via) {
        if (orgUnitId == null || orgUnitId.isBlank()) return null;
        String courseUrl = BritishCouncilKeys.URL_COURSE_BASE + orgUnitId;
        log.info("Navigating to course {} (via {})", courseUrl, via);
        driver.get(courseUrl);
        jitter();
        log.info("Course page loaded (orgUnit={})", orgUnitId);
        return courseUrl;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String summarizeAsTitle(String htmlContent) {
        if (htmlContent == null || htmlContent.isBlank()) return "(no title)";
        String plain = htmlContent
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
        // Find the first sentence boundary
        int end = -1;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if ((c == '.' || c == '!' || c == '?') &&
                    (i + 1 >= plain.length() || plain.charAt(i + 1) == ' ')) {
                end = i + 1;
                break;
            }
        }
        String sentence = (end > 0 ? plain.substring(0, end) : plain).trim();
        if (sentence.length() > 120) {
            int cut = sentence.lastIndexOf(' ', 120);
            sentence = sentence.substring(0, cut > 0 ? cut : 120) + "…";
        }
        return sentence.isBlank() ? "(no title)" : sentence;
    }

    private String buildStableId(String title, String date) {
        String key = (title + "|" + date).toLowerCase().replaceAll("\\s+", "");
        return String.valueOf(Math.abs(key.hashCode()));
    }

    private String coalesce(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value.trim() : fallback;
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return LocalDateTime.now();
        String cleaned = raw.trim();
        // Try ZonedDateTime first (handles ISO-8601 with Z or offset, e.g. "2026-04-18T10:06:45.215Z")
        try {
            return java.time.ZonedDateTime.parse(cleaned).toLocalDateTime();
        } catch (DateTimeParseException ignored) {}
        for (var fmt : BritishCouncilKeys.DATE_FORMATS) {
            try {
                return LocalDateTime.parse(cleaned, fmt);
            } catch (DateTimeParseException ignored) {}
        }
        log.debug("Could not parse BC date '{}' — using current time", raw);
        return LocalDateTime.now();
    }

    private void jsSetValue(WebDriver driver, WebElement element, String value) {
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = arguments[1];" +
                "arguments[0].dispatchEvent(new Event('input', { bubbles: true }));" +
                "arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
                element, value);
    }

    private void jitter() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(pageJitterMinMs, pageJitterMaxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
