package com.ecrharv.harvester.service;

import com.ecrharv.harvester.config.BritishCouncilHttpClient;
import com.ecrharv.harvester.enums.MessageSource;
import com.ecrharv.harvester.enums.MessageType;
import com.ecrharv.harvester.scraping.ScrapedData;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class BritishCouncilScraperService {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX",     Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss",      Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy h:mm a",         Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy h:mm a",        Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a",         Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm",           Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm",           Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy",                Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy",               Locale.ENGLISH)
    );

    private final BritishCouncilHttpClient bcHttpClient;

    @Value("${bc.username:}")
    private String username;

    @Value("${bc.password:}")
    private String password;

    @Value("${bc.enabled:false}")
    private boolean enabled;

    public ScrapedData.BcScrapeResult scrapeMessages(Set<String> knownMessageIds) {
        if (!enabled) {
            log.info("British Council scraper disabled — skipping");
            return ScrapedData.BcScrapeResult.empty();
        }
        if (username.isBlank() || password.isBlank()) {
            log.warn("British Council credentials not configured — skipping");
            return ScrapedData.BcScrapeResult.empty();
        }

        try (BritishCouncilHttpClient.BcSession session = bcHttpClient.openSession()) {
            List<String> orgIds = session.findCourseOrgIds();
            if (orgIds.isEmpty()) {
                log.warn("No BC course/group found — skipping news scrape");
                return ScrapedData.BcScrapeResult.empty();
            }

            List<ScrapedData.MessageRecord> messages = new ArrayList<>();
            for (String orgId : orgIds) {
                processNewsItems(session.fetchNewsItems(orgId), knownMessageIds, messages, session);
            }

            log.info("Scraped {} new BC news item(s)", messages.size());
            return new ScrapedData.BcScrapeResult("", username, messages);

        } catch (Exception e) {
            log.error("British Council scraping failed: {}", e.getMessage(), e);
            return ScrapedData.BcScrapeResult.empty();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void processNewsItems(List<JsonNode> items, Set<String> knownMessageIds,
                                  List<ScrapedData.MessageRecord> out,
                                  BritishCouncilHttpClient.BcSession session) {
        for (JsonNode item : items) {
            JsonNode obj = item.path("object");

            // Prefer article's own id (object.id) — stable across scrapes.
            // Fall back to top-level activity id only when no object is present.
            String rawId = obj.isMissingNode() ? "" : obj.path("id").asText("").trim();
            if (rawId.isBlank()) rawId = text(item, "NewsItemId", "Id", "id");

            // Extract last path segment from ActivityStreams URL (…/article/{uuid})
            String newsId = rawId;
            if (newsId.contains("/")) newsId = newsId.substring(newsId.lastIndexOf('/') + 1);
            else if (newsId.contains(":")) newsId = newsId.substring(newsId.lastIndexOf(':') + 1);

            String title = text(item, "Title", "Headline");
            if (title.isBlank() && !obj.isMissingNode()) title = obj.path("name").asText("").trim();
            String content = extractBody(item);
            if (title.isBlank()) title = generateTitle(content);

            String author  = extractAuthor(item, session);
            String dateStr = text(item, "published", "StartDate", "CreatedDate", "PublishDate");
            if (dateStr.isBlank() && !obj.isMissingNode())
                dateStr = text(obj, "published", "StartDate", "updated");

            String msgId = "bc_news_" + newsId;
            if (knownMessageIds.contains(msgId)) continue;
            out.add(new ScrapedData.MessageRecord(msgId, MessageType.INBOX, MessageSource.BRITISH_COUNCIL,
                    author, "", title, content, parseDateTime(dateStr)));
        }
    }

    private static final java.util.regex.Pattern GREETING =
            java.util.regex.Pattern.compile(
                    "^Dear [^,\\n]+,\\s*", java.util.regex.Pattern.CASE_INSENSITIVE);

    // Derives a display title from HTML body content:
    // 1. Heading element (h1/h2/h3)
    // 2. Paragraph whose sole child is <strong> or <b>
    // 3. Plain text after stripping "Dear X," greeting, clipped at 80 chars
    private String generateTitle(String html) {
        if (html == null || html.isBlank()) return "(no title)";
        org.jsoup.nodes.Document doc = Jsoup.parse(html);

        for (String sel : new String[]{"h1", "h2", "h3"}) {
            org.jsoup.nodes.Element el = doc.selectFirst(sel);
            if (el != null && !el.text().isBlank()) return capitalize(el.text().trim());
        }
        for (String sel : new String[]{"p > strong:only-child", "p > b:only-child"}) {
            org.jsoup.nodes.Element el = doc.selectFirst(sel);
            if (el != null && !el.text().isBlank()) return capitalize(el.text().trim());
        }

        String plain = doc.text().trim();
        if (plain.isBlank()) return "(no title)";

        // Strip leading "Dear Parents," / "Dear Students," — it's a greeting, not a title
        java.util.regex.Matcher m = GREETING.matcher(plain);
        if (m.find() && m.end() < plain.length()) plain = plain.substring(m.end()).trim();
        if (plain.isBlank()) return "(no title)";

        String title = plain.length() <= 80 ? plain
                : plain.substring(0, plain.lastIndexOf(' ', 80) > 20
                        ? plain.lastIndexOf(' ', 80) : 80).trim() + "…";
        return capitalize(title);
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String text(JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText("").trim();
            if (!v.isBlank() && !"null".equals(v)) return v;
        }
        return "";
    }

    private String extractAuthor(JsonNode item, BritishCouncilHttpClient.BcSession session) {
        for (String key : new String[]{"actor", "CreatedBy", "Author", "User", "Instructor", "Creator"}) {
            JsonNode node = item.path(key);
            if (node.isMissingNode()) continue;
            if (node.isObject()) {
                String first = node.path("FirstName").asText("").trim();
                String last  = node.path("LastName").asText("").trim();
                if (!first.isBlank() || !last.isBlank()) return (first + " " + last).trim();
                String display = node.path("DisplayName").asText(
                        node.path("Name").asText(node.path("name").asText(""))).trim();
                if (!display.isBlank()) return display;
            } else if (node.isTextual() && session != null) {
                // ActivityStreams actor is a URL — resolve to a name via the session
                String resolved = session.resolveActorName(node.asText().trim());
                if (resolved != null && !resolved.isBlank()) return resolved;
            }
        }
        String first = item.path("AuthorFirstName").asText("").trim();
        String last  = item.path("AuthorLastName").asText("").trim();
        if (!first.isBlank() || !last.isBlank()) return (first + " " + last).trim();
        return "British Council";
    }

    private String extractBody(JsonNode item) {
        JsonNode body = item.path("Body");
        if (!body.isMissingNode()) {
            String html  = body.path("Html").asText("").trim();
            String plain = body.path("Text").asText("").trim();
            return !html.isBlank() ? html : plain;
        }
        // Activity Streams 2.0: object.content
        JsonNode obj = item.path("object");
        if (!obj.isMissingNode() && obj.isObject()) {
            String content = obj.path("content").asText("").trim();
            if (!content.isBlank()) return content;
        }
        return item.path("Content").asText("").trim();
    }

    private LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return LocalDateTime.now();
        String cleaned = raw.trim();
        try {
            return java.time.ZonedDateTime.parse(cleaned).toLocalDateTime();
        } catch (DateTimeParseException ignored) {}
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDateTime.parse(cleaned, fmt); } catch (DateTimeParseException ignored) {}
        }
        log.debug("Could not parse BC date '{}' — using current time", raw);
        return LocalDateTime.now();
    }
}
