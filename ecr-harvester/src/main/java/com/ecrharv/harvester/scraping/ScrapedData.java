package com.ecrharv.harvester.scraping;

import com.ecrharv.harvester.enums.AttendanceStatus;
import com.ecrharv.harvester.enums.MessageSource;
import com.ecrharv.harvester.enums.MessageType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Immutable DTOs that carry raw scraped values from the browser to the
 * persistence layer — no JPA context needed here.
 */
public final class ScrapedData {

    private ScrapedData() {}

    public record GradeRecord(
            String subjectName,
            String category,
            String gradeValue,
            int weight,
            LocalDate dateIssued,
            String teacher
    ) {}

    public record StudentInfo(
            String fullName,
            String className
    ) {}

    public record MessageRecord(
            String librusMessageId,
            MessageType messageType,
            MessageSource messageSource,
            String sender,
            String senderRole,
            String subject,
            String content,
            LocalDateTime sentAt
    ) {}

    public record AttendanceRecord(
            LocalDate date,
            int lessonNumber,
            AttendanceStatus status,
            String subject
    ) {}

    public record AnnouncementRecord(
            String librusAnnouncementId,
            String source,
            String title,
            String content,
            String author,
            String authorRole,
            LocalDate publishedAt
    ) {}

    public record ScrapeResult(
            StudentInfo studentInfo,
            List<GradeRecord> grades,
            List<MessageRecord> messages,
            List<AttendanceRecord> attendance,
            List<AnnouncementRecord> announcements
    ) {}

    public record BcScrapeResult(
            String bcId,
            String bcFullName,
            List<MessageRecord> messages
    ) {
        public static BcScrapeResult empty() {
            return new BcScrapeResult("", "", List.of());
        }
    }
}
