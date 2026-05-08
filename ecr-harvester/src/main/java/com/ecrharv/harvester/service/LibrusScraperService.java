package com.ecrharv.harvester.service;

import com.ecrharv.harvester.config.LibrusHttpClient;
import com.ecrharv.harvester.config.LibrusSession;
import com.ecrharv.harvester.enums.AttendanceStatus;
import com.ecrharv.harvester.enums.MessageSource;
import com.ecrharv.harvester.enums.MessageType;
import com.ecrharv.harvester.scraping.LibrusKeys;
import com.ecrharv.harvester.scraping.ScrapedData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private final LibrusHttpClient librusHttpClient;

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

    private static final DateTimeFormatter DATE_FMT     = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ── Public API ───────────────────────────────────────────────────────────

    public ScrapedData.ScrapeResult scrapeAll(Set<String> knownMessageIds, Set<String> knownAnnouncementIds) {
        try (LibrusSession session = librusHttpClient.openSession()) {
            Document gradesDoc = session.getPage(urlGrades);
            log.info("Grades page title: '{}'", gradesDoc.title());

            ScrapedData.StudentInfo studentInfo = scrapeStudentInfo(gradesDoc);
            List<ScrapedData.GradeRecord> grades = scrapeGrades(gradesDoc);
            jitter();

            List<ScrapedData.MessageRecord> messages = scrapeMessages(session, knownMessageIds);
            jitter();

            List<ScrapedData.AttendanceRecord> attendance = scrapeAttendance(session);
            jitter();

            List<ScrapedData.AnnouncementRecord> announcements = scrapeAnnouncements(session, knownAnnouncementIds);

            return new ScrapedData.ScrapeResult(studentInfo, grades, messages, attendance, announcements);

        } catch (IOException e) {
            log.error("Scraping session failed: {}", e.getMessage(), e);
            throw new RuntimeException("Scraping session failed", e);
        }
    }

    // ── Student info ─────────────────────────────────────────────────────────

    private ScrapedData.StudentInfo scrapeStudentInfo(Document doc) {
        log.info("Scraping student info");
        try {
            Element p = doc.selectFirst(LibrusKeys.SEL_STUDENT_INFO);
            if (p == null) {
                log.warn("Student info element not found");
                return null;
            }
            String text = p.text();
            String rawName   = extractBetweenLabels(text, "Uczeń:", "Klasa:");
            String fullName  = reverseNameOrder(rawName);
            String className = extractStudentField(text, "Klasa:");
            log.info("Student info scraped — name: '{}', class: '{}'", fullName, className);
            return new ScrapedData.StudentInfo(fullName, className);
        } catch (Exception e) {
            log.warn("Could not scrape student info: {}", e.getMessage());
            return null;
        }
    }

    /** Extracts text between two labels in a flat string (JSoup .text() collapses whitespace). */
    private String extractBetweenLabels(String text, String startLabel, String endLabel) {
        int start = text.indexOf(startLabel);
        if (start < 0) return "";
        String after = text.substring(start + startLabel.length()).strip();
        int end = after.indexOf(endLabel);
        return end >= 0 ? after.substring(0, end).strip() : after;
    }

    /** Librus stores names as "Lastname Firstname" — reverse to "Firstname Lastname". */
    private String reverseNameOrder(String name) {
        if (name == null || name.isBlank()) return name;
        String[] parts = name.trim().split("\\s+");
        if (parts.length < 2) return name.trim();
        // Move first token (last name) to end
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (i > 1) sb.append(' ');
            sb.append(parts[i]);
        }
        sb.append(' ').append(parts[0]);
        return sb.toString();
    }

    private String extractStudentField(String text, String label) {
        int idx = text.indexOf(label);
        if (idx < 0) return "";
        String after = text.substring(idx + label.length());
        int newline = after.indexOf('\n');
        if (newline >= 0) after = after.substring(0, newline);
        // Stop at next label (e.g. "Uczeń:" appearing after "Klasa:")
        for (String next : List.of("Uczeń:", "Klasa:", "Rok szkolny:")) {
            int pos = after.indexOf(next);
            if (pos >= 0) after = after.substring(0, pos);
        }
        return after.strip();
    }

    // ── Grades ───────────────────────────────────────────────────────────────

    private List<ScrapedData.GradeRecord> scrapeGrades(Document doc) {
        log.info("Scraping grades");
        List<ScrapedData.GradeRecord> grades = new ArrayList<>();

        if (doc.selectFirst(LibrusKeys.SEL_GRADES_TABLE) == null) {
            log.warn("Grades table not found — page layout may have changed");
            return grades;
        }

        for (Element row : doc.select(LibrusKeys.SEL_GRADES_ROWS)) {
            try {
                Elements cells = row.select("td");
                if (cells.size() < 2) continue;

                String subjectName = cells.get(0).text().trim();
                if (subjectName.isBlank()) continue;

                for (Element span : cells.get(1).select(LibrusKeys.SEL_GRADE_SPAN)) {
                    String value = span.text().trim();
                    if (value.isBlank()) continue;

                    GradeDetails details = parseGradeTitle(span.attr("title"));

                    grades.add(new ScrapedData.GradeRecord(
                            subjectName,
                            details.category(),
                            value,
                            details.weight(),
                            details.dateIssued(),
                            details.teacher()
                    ));
                }
            } catch (Exception e) {
                log.warn("Could not parse grade row: {}", e.getMessage());
            }
        }

        log.info("Scraped {} grade entries", grades.size());
        return grades;
    }

    // ── Messages ─────────────────────────────────────────────────────────────

    private List<ScrapedData.MessageRecord> scrapeMessages(LibrusSession session,
                                                            Set<String> knownMessageIds) throws IOException {
        log.info("Scraping messages");
        List<ScrapedData.MessageRecord> messages = new ArrayList<>();
        messages.addAll(scrapeMessageFolder(session, urlMessages + LibrusKeys.MSG_INBOX_SUFFIX,
                MessageType.INBOX, knownMessageIds));
        jitter();
        messages.addAll(scrapeMessageFolder(session, urlMessages + LibrusKeys.MSG_SENT_SUFFIX,
                MessageType.SENT, knownMessageIds));
        return messages;
    }

    private List<ScrapedData.MessageRecord> scrapeMessageFolder(LibrusSession session,
                                                                  String url, MessageType type,
                                                                  Set<String> knownMessageIds) throws IOException {
        log.info("Scraping {} messages from {}", type, url);
        List<ScrapedData.MessageRecord> messages = new ArrayList<>();

        Document doc = session.getPage(url);

        if (doc.selectFirst(LibrusKeys.SEL_MSG_TABLE) == null) {
            log.warn("{} messages list not found — page layout may have changed", type);
            return messages;
        }

        List<String> hrefs   = new ArrayList<>();
        List<String> msgIds  = new ArrayList<>();
        List<LocalDateTime> sentAts = new ArrayList<>();

        for (Element row : doc.select(LibrusKeys.SEL_MSG_ROWS)) {
            Elements cells = row.select("td");
            if (cells.size() < 5) continue;

            Element link = row.selectFirst(LibrusKeys.SEL_MSG_LINK);
            if (link == null) continue;
            String href = link.attr("abs:href");
            if (!href.contains(LibrusKeys.MSG_HREF_FRAGMENT)) continue;

            String numericId = href.replaceAll(LibrusKeys.MSG_ID_PATTERN, "$1");
            hrefs.add(href);
            msgIds.add(type.name().toLowerCase() + "_" + numericId);
            sentAts.add(parseDateTime(cells.get(4).text().trim()));
        }

        int skipped = 0;
        for (int i = 0; i < hrefs.size(); i++) {
            String msgId = msgIds.get(i);
            if (knownMessageIds.contains(msgId)) {
                skipped++;
                continue;
            }
            try {
                Document detail = session.getPage(hrefs.get(i));
                jitter();

                String rawSender  = tableValue(detail, LibrusKeys.MSG_LABEL_SENDER);
                String sender     = rawSender.replaceAll("\\s*\\([^)]+\\)", "")
                                             .replaceAll("\\s*\\[[^]]*]", "").trim();
                java.util.regex.Matcher roleM = java.util.regex.Pattern
                        .compile("\\[([^]]+)]").matcher(rawSender);
                String senderRole = roleM.find() ? roleM.group(1).trim() : "";
                String subject    = tableValue(detail, LibrusKeys.MSG_LABEL_SUBJECT);

                Element contentEl = detail.selectFirst(LibrusKeys.SEL_MSG_DETAIL_CONTENT);
                String content    = contentEl != null ? extractText(contentEl) : "";

                messages.add(new ScrapedData.MessageRecord(
                        msgId, type, MessageSource.LIBRUS,
                        sender, senderRole, subject, content, sentAts.get(i)));
            } catch (Exception e) {
                log.warn("Failed to scrape {} message id={}: {}", type, msgId, e.getMessage());
            }
        }
        if (skipped > 0) log.info("Skipped {} already-stored {} messages", skipped, type);

        log.info("Scraped {} {} messages", messages.size(), type);
        return messages;
    }

    // ── Attendance ───────────────────────────────────────────────────────────

    private List<ScrapedData.AttendanceRecord> scrapeAttendance(LibrusSession session) throws IOException {
        log.info("Scraping attendance");
        List<ScrapedData.AttendanceRecord> records = new ArrayList<>();

        Document doc = session.getPage(urlAttendance);

        if (doc.selectFirst(LibrusKeys.SEL_ATTENDANCE_TABLE) == null) {
            log.warn("Attendance table not found — page layout may have changed");
            return records;
        }

        for (Element row : doc.select(LibrusKeys.SEL_ATTENDANCE_ROWS)) {
            try {
                Elements cells = row.select("td");
                if (cells.size() < 4) continue;

                String dateStr      = cells.get(0).text().trim();
                String lessonNumStr = cells.get(1).text().trim();
                String subject      = cells.get(2).text().trim();
                String statusRaw    = cells.get(3).text().trim();

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

        log.info("Scraped {} attendance records", records.size());
        return records;
    }

    // ── Announcements ────────────────────────────────────────────────────────

    private List<ScrapedData.AnnouncementRecord> scrapeAnnouncements(LibrusSession session,
                                                                       Set<String> knownIds) throws IOException {
        log.info("Scraping announcements");
        List<ScrapedData.AnnouncementRecord> records = new ArrayList<>();

        Document doc = session.getPage(urlAnnouncements);

        if (doc.selectFirst(LibrusKeys.SEL_ANN_TABLE) == null) {
            log.warn("Announcements table not found — page layout may have changed");
            return records;
        }

        Elements tables = doc.select(LibrusKeys.SEL_ANN_TABLE);
        log.info("Announcements page — found {} table(s)", tables.size());

        int skipped = 0;
        for (Element table : tables) {
            try {
                Element theadTd = table.selectFirst("thead td");
                if (theadTd == null) continue;
                String title = theadTd.text().trim();
                if (title.isBlank()) continue;

                String author     = "";
                String authorRole = "";
                String dateStr    = "";
                String content    = "";

                for (Element row : table.select("tbody tr")) {
                    Elements ths = row.select("th");
                    Elements tds = row.select("td");
                    if (ths.isEmpty() || tds.isEmpty()) continue;
                    String label = ths.first().text().trim();
                    String value = tds.first().text().trim();
                    if      (LibrusKeys.ANN_LABEL_AUTHOR.equals(label))  author     = value;
                    else if (LibrusKeys.ANN_LABEL_ROLE.equals(label))    authorRole = value;
                    else if (LibrusKeys.ANN_LABEL_DATE.equals(label))    dateStr    = value;
                    else if (LibrusKeys.ANN_LABEL_CONTENT.equals(label)) content    = extractText(tds.first());
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

        log.info("Scraped {} announcement(s)", records.size());
        return records;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractText(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Node node : el.childNodes()) {
            if (node instanceof TextNode tn) {
                sb.append(tn.text());
            } else if (node instanceof Element child) {
                if ("br".equals(child.tagName())) {
                    sb.append('\n');
                } else {
                    sb.append(extractText(child));
                }
            }
        }
        return sb.toString().strip();
    }

    private String tableValue(Document doc, String label) {
        for (Element td : doc.select("td")) {
            if (label.equals(td.text().trim())) {
                Element sibling = td.nextElementSibling();
                return sibling != null ? sibling.text().trim() : "";
            }
        }
        return "";
    }

    private void jitter() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(pageJitterMinMs, pageJitterMaxMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
