package com.ecrharv.harvester.service;

import com.ecrharv.harvester.config.SeleniumConfig;
import com.ecrharv.harvester.enums.AttendanceStatus;
import com.ecrharv.harvester.enums.MessageSource;
import com.ecrharv.harvester.enums.MessageType;
import com.ecrharv.harvester.scraping.LibrusKeys;
import com.ecrharv.harvester.scraping.ScrapedData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class LibrusScraperService {

    private final SeleniumConfig seleniumConfig;

    @Value("${librus.username}")
    private String username;

    @Value("${librus.password}")
    private String password;

    @Value("${librus.url.login}")
    private String urlLogin;

    @Value("${librus.url.grades}")
    private String urlGrades;

    @Value("${librus.url.messages}")
    private String urlMessages;

    @Value("${librus.url.attendance}")
    private String urlAttendance;

    @Value("${librus.url.announcements}")
    private String urlAnnouncements;

    @Value("${scraper.page-jitter-min-ms:1000}")
    private long pageJitterMinMs;

    @Value("${scraper.page-jitter-max-ms:4000}")
    private long pageJitterMaxMs;

    @Value("${scraper.human-type-delay-min-ms:60}")
    private long humanTypeDelayMinMs;

    @Value("${scraper.human-type-delay-max-ms:180}")
    private long humanTypeDelayMaxMs;

    private static final DateTimeFormatter DATE_FMT     = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── Public API ───────────────────────────────────────────────────────────

    public ScrapedData.ScrapeResult scrapeAll(Set<String> knownMessageIds, Set<String> knownAnnouncementIds) {
        WebDriver driver = seleniumConfig.createDriver();
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

        try {
            login(driver, wait);

            ScrapedData.StudentInfo studentInfo = scrapeStudentInfo(driver, wait);

            List<ScrapedData.GradeRecord> grades = scrapeGrades(driver, wait);
            jitter();

            List<ScrapedData.MessageRecord> messages = scrapeMessages(driver, wait, knownMessageIds);
            jitter();

            List<ScrapedData.AttendanceRecord> attendance = scrapeAttendance(driver, wait);
            jitter();

            List<ScrapedData.AnnouncementRecord> announcements = scrapeAnnouncements(driver, wait, knownAnnouncementIds);

            return new ScrapedData.ScrapeResult(studentInfo, grades, messages, attendance, announcements);

        } catch (Exception e) {
            log.error("Scraping session failed: {}", e.getMessage(), e);
            throw new RuntimeException("Scraping session failed", e);
        } finally {
            driver.quit();
            log.debug("WebDriver closed");
        }
    }

    // ── Login ────────────────────────────────────────────────────────────────

    private void login(WebDriver driver, WebDriverWait wait) {
        log.info("Navigating to Librus portal");
        driver.get(urlLogin);
        jitter();

        // Step 1 — dismiss cookie/GDPR consent if it appears
        dismissCookieConsent(driver);

        // Step 2 — open the Synergia dropdown and click "Zaloguj"
        try {
            log.info("Opening Synergia login dropdown");
            wait.until(ExpectedConditions.elementToBeClickable(
                    By.cssSelector(LibrusKeys.SEL_SYNERGIA_BTN))).click();

            wait.until(ExpectedConditions.elementToBeClickable(
                    By.xpath(LibrusKeys.XPATH_ZALOGUJ_LINK))).click();

            jitter();
        } catch (TimeoutException e) {
            throw new RuntimeException(
                "Could not find Synergia login dropdown — portal layout may have changed", e
            );
        }

        // Step 3 — fill credentials on the login form

        // The Synergia login form may be embedded in an iframe — switch into it if present
        try {
            List<WebElement> frames = driver.findElements(By.tagName("iframe"));
            if (!frames.isEmpty()) {
                log.info("Found {} iframe(s) — switching into first one", frames.size());
                driver.switchTo().frame(frames.get(0));
            } else {
                log.info("No iframes found — searching in main document");
            }
        } catch (Exception e) {
            log.warn("iframe switch attempt failed: {}", e.getMessage());
        }

        try {
            WebElement loginField = wait.until(
                    ExpectedConditions.elementToBeClickable(By.id(LibrusKeys.LOGIN_FIELD_ID))
            );
            WebElement passField = driver.findElement(By.id(LibrusKeys.PASS_FIELD_ID));

            jsSetValue(driver, loginField, username);
            jitter();
            jsSetValue(driver, passField, password);
            jitter();

            driver.findElement(By.id(LibrusKeys.SUBMIT_BTN_ID)).click();

            wait.until(ExpectedConditions.urlContains(LibrusKeys.DOMAIN_SYNERGIA));
            log.info("Login successful");

        } catch (TimeoutException e) {
            throw new RuntimeException(
                "Login failed — check credentials or verify selectors against the live portal", e
            );
        }
    }

    /** Best-effort: clicks the cookie/GDPR consent button if one appears within 5 s. */
    private void dismissCookieConsent(WebDriver driver) {
        try {
            WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(5));
            WebElement btn = shortWait.until(
                    ExpectedConditions.elementToBeClickable(By.xpath(LibrusKeys.XPATH_COOKIE_CONSENT))
            );
            btn.click();
            log.info("Cookie consent dismissed");
            jitter();
        } catch (TimeoutException e) {
            log.debug("No cookie consent banner found — continuing");
        }
    }

    // ── Student info ─────────────────────────────────────────────────────────

    private ScrapedData.StudentInfo scrapeStudentInfo(WebDriver driver, WebDriverWait wait) {
        log.info("Scraping student info");
        driver.get(urlGrades);
        jitter();
        try {
            WebElement p = wait.until(
                    ExpectedConditions.presenceOfElementLocated(By.cssSelector(LibrusKeys.SEL_STUDENT_INFO))
            );
            String text = p.getText();
            String fullName  = extractStudentField(text, "Uczeń:");
            String className = extractStudentField(text, "Klasa:");
            log.info("Student info scraped — class: '{}'", className);
            return new ScrapedData.StudentInfo(fullName, className);
        } catch (Exception e) {
            log.warn("Could not scrape student info: {}", e.getMessage());
            return null;
        }
    }

    private String extractStudentField(String text, String label) {
        int idx = text.indexOf(label);
        if (idx < 0) return "";
        String after = text.substring(idx + label.length());
        int newline = after.indexOf('\n');
        if (newline >= 0) after = after.substring(0, newline);
        return after.replace(' ', ' ').strip();
    }

    // ── Grades ───────────────────────────────────────────────────────────────

    private List<ScrapedData.GradeRecord> scrapeGrades(WebDriver driver, WebDriverWait wait) {
        log.info("Scraping grades");
        List<ScrapedData.GradeRecord> grades = new ArrayList<>();

        driver.get(urlGrades);
        jitter();

        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(LibrusKeys.SEL_GRADES_TABLE)));
            List<WebElement> rows = driver.findElements(By.cssSelector(LibrusKeys.SEL_GRADES_ROWS));

            for (WebElement row : rows) {
                try {
                    List<WebElement> cells = row.findElements(By.tagName("td"));
                    if (cells.size() < 2) continue;

                    String subjectName = cells.get(0).getText().trim();
                    if (subjectName.isBlank()) continue;

                    List<WebElement> gradeSpans = cells.get(1).findElements(
                            By.cssSelector(LibrusKeys.SEL_GRADE_SPAN)
                    );

                    for (WebElement span : gradeSpans) {
                        String value = span.getText().trim();
                        if (value.isBlank()) continue;

                        GradeDetails details = parseGradeTitle(span.getAttribute("title"));

                        grades.add(new ScrapedData.GradeRecord(
                                subjectName,
                                details.category(),
                                value,
                                details.weight(),
                                details.dateIssued(),
                                details.teacher()
                        ));
                    }
                } catch (StaleElementReferenceException e) {
                    log.warn("Stale element in grades table — row skipped");
                }
            }
        } catch (TimeoutException e) {
            log.warn("Grades table not found at {} — page layout may have changed", urlGrades);
        }

        log.info("Scraped {} grade entries", grades.size());
        return grades;
    }

    // ── Messages ─────────────────────────────────────────────────────────────

    private List<ScrapedData.MessageRecord> scrapeMessages(WebDriver driver, WebDriverWait wait,
                                                            Set<String> knownMessageIds) {
        log.info("Scraping messages");
        List<ScrapedData.MessageRecord> messages = new ArrayList<>();
        messages.addAll(scrapeMessageFolder(driver, wait, urlMessages + LibrusKeys.MSG_INBOX_SUFFIX, MessageType.INBOX, knownMessageIds));
        jitter();
        messages.addAll(scrapeMessageFolder(driver, wait, urlMessages + LibrusKeys.MSG_SENT_SUFFIX, MessageType.SENT, knownMessageIds));
        return messages;
    }

    private List<ScrapedData.MessageRecord> scrapeMessageFolder(WebDriver driver, WebDriverWait wait,
                                                                 String url, MessageType type,
                                                                 Set<String> knownMessageIds) {
        log.info("Scraping {} messages from {}", type, url);
        List<ScrapedData.MessageRecord> messages = new ArrayList<>();

        driver.get(url);
        jitter();

        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(LibrusKeys.SEL_MSG_TABLE)));

            List<String> hrefs   = new ArrayList<>();
            List<String> msgIds  = new ArrayList<>();
            List<LocalDateTime> sentAts = new ArrayList<>();

            for (WebElement row : driver.findElements(By.cssSelector(LibrusKeys.SEL_MSG_ROWS))) {
                try {
                    List<WebElement> cells = row.findElements(By.tagName("td"));
                    if (cells.size() < 5) continue;

                    String href = row.findElement(By.cssSelector(LibrusKeys.SEL_MSG_LINK)).getAttribute("href");
                    if (href != null && href.contains(LibrusKeys.MSG_HREF_FRAGMENT)) {
                        String numericId = href.replaceAll(LibrusKeys.MSG_ID_PATTERN, "$1");
                        hrefs.add(href);
                        msgIds.add(type.name().toLowerCase() + "_" + numericId);
                        sentAts.add(parseDateTime(cells.get(4).getText().trim()));
                    }
                } catch (NoSuchElementException ignored) {}
            }

            int skipped = 0;
            for (int i = 0; i < hrefs.size(); i++) {
                String msgId = msgIds.get(i);
                if (knownMessageIds.contains(msgId)) {
                    skipped++;
                    continue;
                }
                try {
                    driver.get(hrefs.get(i));
                    jitter();

                    wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector(LibrusKeys.SEL_MSG_DETAIL_CONTAINER)));

                    String rawSender   = tableValue(driver, LibrusKeys.MSG_LABEL_SENDER);
                    String sender      = rawSender.replaceAll("\\s*\\([^)]+\\)", "")
                                                  .replaceAll("\\s*\\[[^]]*]", "").trim();
                    java.util.regex.Matcher roleM = java.util.regex.Pattern
                            .compile("\\[([^]]+)]").matcher(rawSender);
                    String senderRole  = roleM.find() ? roleM.group(1).trim() : "";
                    String subject = tableValue(driver, LibrusKeys.MSG_LABEL_SUBJECT);
                    String content = textSafe(driver, LibrusKeys.SEL_MSG_DETAIL_CONTENT);

                    messages.add(new ScrapedData.MessageRecord(msgId, type, MessageSource.LIBRUS, sender, senderRole, subject, content, sentAts.get(i)));
                    jitter();
                } catch (Exception e) {
                    log.warn("Failed to scrape {} message id={}: {}", type, msgId, e.getMessage());
                }
            }
            if (skipped > 0) log.info("Skipped {} already-stored {} messages", skipped, type);
        } catch (TimeoutException e) {
            log.warn("{} messages list not found at {} — page layout may have changed", type, url);
        }

        log.info("Scraped {} {} messages", messages.size(), type);
        return messages;
    }

    // ── Attendance ───────────────────────────────────────────────────────────

    private List<ScrapedData.AttendanceRecord> scrapeAttendance(WebDriver driver, WebDriverWait wait) {
        log.info("Scraping attendance");
        List<ScrapedData.AttendanceRecord> records = new ArrayList<>();

        driver.get(urlAttendance);
        jitter();

        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(LibrusKeys.SEL_ATTENDANCE_TABLE)));

            List<WebElement> rows = driver.findElements(By.cssSelector(LibrusKeys.SEL_ATTENDANCE_ROWS));

            for (WebElement row : rows) {
                try {
                    List<WebElement> cells = row.findElements(By.tagName("td"));
                    if (cells.size() < 4) continue;

                    String dateStr      = cells.get(0).getText().trim();
                    String lessonNumStr = cells.get(1).getText().trim();
                    String subject      = cells.get(2).getText().trim();
                    String statusRaw    = cells.get(3).getText().trim();

                    if (dateStr.isBlank() || lessonNumStr.isBlank()) continue;

                    records.add(new ScrapedData.AttendanceRecord(
                            parseDate(dateStr),
                            parseInt(lessonNumStr, 0),
                            parseAttendanceStatus(statusRaw),
                            subject
                    ));
                } catch (Exception e) {
                    log.warn("Could not parse attendance row: {}", e.getMessage());
                }
            }
        } catch (TimeoutException e) {
            log.warn("Attendance table not found at {} — page layout may have changed", urlAttendance);
        }

        log.info("Scraped {} attendance records", records.size());
        return records;
    }

    // ── Announcements ─────────────────────────────────────────────────────────

    private List<ScrapedData.AnnouncementRecord> scrapeAnnouncements(WebDriver driver, WebDriverWait wait,
                                                                       Set<String> knownIds) {
        log.info("Scraping announcements");
        List<ScrapedData.AnnouncementRecord> records = new ArrayList<>();

        driver.get(urlAnnouncements);
        jitter();

        // Each announcement is a self-contained table on the list page — no navigation to detail pages.
        // Structure: <thead><tr><td colspan="2">Title</td></tr></thead>
        //            <tbody><tr><th>Dodał</th><td>Author</td></tr>
        //                   <tr><th>Data publikacji</th><td>date</td></tr>
        //                   <tr><th>Treść</th><td>content</td></tr></tbody>
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(LibrusKeys.SEL_ANN_TABLE)));

            List<WebElement> tables = driver.findElements(By.cssSelector(LibrusKeys.SEL_ANN_TABLE));
            log.info("Announcements page — found {} table(s)", tables.size());

            int skipped = 0;
            for (WebElement table : tables) {
                try {
                    String title = table.findElement(By.cssSelector("thead td")).getText().trim();
                    if (title.isBlank()) continue;

                    String author     = "";
                    String authorRole = "";
                    String dateStr    = "";
                    String content    = "";

                    for (WebElement row : table.findElements(By.cssSelector("tbody tr"))) {
                        List<WebElement> ths = row.findElements(By.tagName("th"));
                        List<WebElement> tds = row.findElements(By.tagName("td"));
                        if (ths.isEmpty() || tds.isEmpty()) continue;
                        String label = ths.get(0).getText().trim();
                        if      (LibrusKeys.ANN_LABEL_AUTHOR.equals(label))  author     = tds.get(0).getText().trim();
                        else if (LibrusKeys.ANN_LABEL_ROLE.equals(label))    authorRole = tds.get(0).getText().trim();
                        else if (LibrusKeys.ANN_LABEL_DATE.equals(label))    dateStr    = tds.get(0).getText().trim();
                        else if (LibrusKeys.ANN_LABEL_CONTENT.equals(label)) content    = tds.get(0).getText().trim();
                    }

                    String annId = generateAnnId(title, dateStr, author);
                    if (knownIds.contains(annId)) { skipped++; continue; }

                    records.add(new ScrapedData.AnnouncementRecord(
                            annId, "LIBRUS", title, content, author, authorRole, parseDate(dateStr)));
                } catch (Exception e) {
                    log.warn("Failed to parse announcement table: {}", e.getMessage());
                }
            }
            if (skipped > 0) log.info("Skipped {} already-stored announcement(s)", skipped);
        } catch (TimeoutException e) {
            log.warn("Announcements table not found at {} — page layout may have changed", urlAnnouncements);
        }

        log.info("Scraped {} announcement(s)", records.size());
        return records;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void jsSetValue(WebDriver driver, WebElement element, String value) {
        ((JavascriptExecutor) driver).executeScript(
            "arguments[0].value = arguments[1];" +
            "arguments[0].dispatchEvent(new Event('input', { bubbles: true }));" +
            "arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
            element, value
        );
    }

    private void humanType(WebElement element, String text) {
        element.click();
        element.clear();
        for (char ch : text.toCharArray()) {
            element.sendKeys(String.valueOf(ch));
            sleep(ThreadLocalRandom.current().nextLong(humanTypeDelayMinMs, humanTypeDelayMaxMs + 1));
        }
    }

    private void jitter() {
        sleep(ThreadLocalRandom.current().nextLong(pageJitterMinMs, pageJitterMaxMs + 1));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String tableValue(WebDriver driver, String label) {
        try {
            return driver.findElement(
                    By.xpath("//td[normalize-space()='" + label + "']/following-sibling::td[1]")
            ).getText().trim();
        } catch (NoSuchElementException e) {
            return "";
        }
    }

    private String textSafe(WebDriver driver, String cssSelector) {
        try {
            return driver.findElement(By.cssSelector(cssSelector)).getText().trim();
        } catch (NoSuchElementException e) {
            return "";
        }
    }

    private GradeDetails parseGradeTitle(String title) {
        if (title == null || title.isBlank()) {
            return new GradeDetails(LibrusKeys.DEFAULT_CATEGORY, 1, LocalDate.now(), LibrusKeys.DEFAULT_UNKNOWN);
        }
        return new GradeDetails(
                extractLine(title, LibrusKeys.GRADE_KEY_CATEGORY),
                parseInt(extractLine(title, LibrusKeys.GRADE_KEY_WEIGHT), 1),
                parseDate(extractLine(title, LibrusKeys.GRADE_KEY_DATE)),
                extractLine(title, LibrusKeys.GRADE_KEY_TEACHER)
        );
    }

    private String extractLine(String block, String key) {
        for (String line : block.split("[\n\r]+")) {
            if (line.contains(key)) {
                String value = line.substring(line.indexOf(key) + key.length()).trim();
                return value.isBlank() ? LibrusKeys.DEFAULT_UNKNOWN : value;
            }
        }
        return LibrusKeys.DEFAULT_UNKNOWN;
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return LocalDate.now();
        String cleaned = raw.trim();
        for (DateTimeFormatter fmt : List.of(DATE_FMT,
                DateTimeFormatter.ofPattern("dd.MM.yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"))) {
            try { return LocalDate.parse(cleaned, fmt); } catch (DateTimeParseException ignored) {}
        }
        return LocalDate.now();
    }

    private LocalDateTime parseDateTime(String raw) {
        try {
            return LocalDateTime.parse(raw.trim(), DATETIME_FMT);
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private AttendanceStatus parseAttendanceStatus(String raw) {
        return switch (raw.toLowerCase().trim()) {
            case LibrusKeys.ATTENDANCE_PRESENT_SHORT,
                 LibrusKeys.ATTENDANCE_PRESENT_FULL  -> AttendanceStatus.PRESENT;
            case LibrusKeys.ATTENDANCE_LATE_SHORT,
                 LibrusKeys.ATTENDANCE_LATE_FULL     -> AttendanceStatus.LATE;
            case LibrusKeys.ATTENDANCE_EXCUSED_SHORT,
                 LibrusKeys.ATTENDANCE_EXCUSED_FULL  -> AttendanceStatus.EXCUSED;
            default                                   -> AttendanceStatus.ABSENT;
        };
    }

    private String generateAnnId(String title, String date, String author) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((title + "|" + date + "|" + author).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < 20; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString((title + date).hashCode());
        }
    }

    private record GradeDetails(String category, int weight, LocalDate dateIssued, String teacher) {}
}
