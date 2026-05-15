package com.ecrharv.harvester.service;

import com.ecrharv.harvester.config.BritishCouncilHttpClient;
import com.ecrharv.harvester.enums.MessageSource;
import com.ecrharv.harvester.enums.MessageType;
import com.ecrharv.harvester.scraping.ScrapedData;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            String orgId = session.findFirstCourseOrgId();
            if (orgId == null) {
                log.warn("No BC course found — skipping news scrape");
                return ScrapedData.BcScrapeResult.empty();
            }

            List<JsonNode> newsItems = session.fetchNewsItems(orgId);
            List<ScrapedData.MessageRecord> messages = new ArrayList<>();

            for (JsonNode item : newsItems) {
                String newsId = text(item, "NewsItemId", "Id", "id");
                String title  = text(item, "Title", "Headline");
                if (title.isBlank()) title = "(no title)";
                String author  = extractAuthor(item);
                String dateStr = text(item, "StartDate", "CreatedDate", "PublishDate");
                String content = extractBody(item);

                String msgId = "bc_news_" + newsId;
                if (knownMessageIds.contains(msgId)) {
                    log.debug("Skipping already-stored BC news: '{}'", title);
                    continue;
                }

                messages.add(new ScrapedData.MessageRecord(
                        msgId, MessageType.INBOX, MessageSource.BRITISH_COUNCIL,
                        author, "", title, content, parseDateTime(dateStr)));
                log.debug("Scraped BC news '{}' by '{}'", title, author);
            }

            log.info("Scraped {} new BC news item(s)", messages.size());
            return new ScrapedData.BcScrapeResult("", username, messages);

        } catch (Exception e) {
            log.error("British Council scraping failed: {}", e.getMessage(), e);
            return ScrapedData.BcScrapeResult.empty();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String text(JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText("").trim();
            if (!v.isBlank() && !"null".equals(v)) return v;
        }
        return "";
    }

    private String extractAuthor(JsonNode item) {
        for (String key : new String[]{"CreatedBy", "Author", "User", "Instructor", "Creator"}) {
            JsonNode nested = item.path(key);
            if (!nested.isMissingNode() && nested.isObject()) {
                String first = nested.path("FirstName").asText("").trim();
                String last  = nested.path("LastName").asText("").trim();
                if (!first.isBlank() || !last.isBlank()) return (first + " " + last).trim();
                String display = nested.path("DisplayName").asText(nested.path("Name").asText("")).trim();
                if (!display.isBlank()) return display;
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
