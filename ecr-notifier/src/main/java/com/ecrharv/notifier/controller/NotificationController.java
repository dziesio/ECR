package com.ecrharv.notifier.controller;

import com.ecrharv.notifier.client.EcrApiClient;
import com.ecrharv.notifier.dto.AnnouncementDto;
import com.ecrharv.notifier.dto.MessageDto;
import com.ecrharv.notifier.dto.StudentDto;
import com.ecrharv.notifier.service.BarkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final String TYPE_MESSAGE      = "MESSAGE";
    private static final String TYPE_ANNOUNCEMENT = "ANNOUNCEMENT";

    private final EcrApiClient apiClient;
    private final BarkService  barkService;

    public NotificationController(EcrApiClient apiClient, BarkService barkService) {
        this.apiClient   = apiClient;
        this.barkService = barkService;
    }

    @PostMapping("/resend-recent")
    public List<NotificationResponse> resendRecent() {
        List<StudentDto> students = apiClient.getStudents();
        List<NotificationResponse> sent = new ArrayList<>();

        for (StudentDto student : students) {
            apiClient.getMessages(student.id()).stream()
                    .filter(msg -> "INBOX".equalsIgnoreCase(msg.messageType()))
                    .limit(3)
                    .forEach(msg -> {
                        String title = buildMessageTitle(msg);
                        String body  = msg.subject() != null ? msg.subject() : "(no subject)";
                        barkService.send(title, body);
                        log.info("Re-sent message id={} title='{}'", msg.id(), title);
                        sent.add(new NotificationResponse(msg.id(), TYPE_MESSAGE, title, body, LocalDateTime.now()));
                    });

            apiClient.getAnnouncements(student.id()).stream()
                    .limit(3)
                    .forEach(ann -> {
                        String title = buildAnnouncementTitle(ann);
                        String body  = ann.title() != null ? ann.title() : "(no title)";
                        barkService.send(title, body);
                        log.info("Re-sent announcement id={} title='{}'", ann.id(), title);
                        sent.add(new NotificationResponse(ann.id(), TYPE_ANNOUNCEMENT, title, body, LocalDateTime.now()));
                    });
        }
        return sent;
    }

    private String buildMessageTitle(MessageDto msg) {
        String source = toDisplaySource(msg.messageSource());
        String role   = msg.senderRole() != null && !msg.senderRole().isBlank() ? " [" + msg.senderRole() + "]" : "";
        return source + ": New message - " + msg.sender() + role;
    }

    private String buildAnnouncementTitle(AnnouncementDto ann) {
        String source = toDisplaySource(ann.source());
        String author = ann.author() != null && !ann.author().isBlank() ? ann.author() : "unknown";
        String role   = ann.authorRole() != null && !ann.authorRole().isBlank() ? " [" + ann.authorRole() + "]" : "";
        return source + ": Announcement - " + author + role;
    }

    private String toDisplaySource(String source) {
        if ("BRITISH_COUNCIL".equalsIgnoreCase(source)) return "British Council";
        return "Librus";
    }

    public record NotificationResponse(
            UUID id,
            String entityType,
            String title,
            String body,
            LocalDateTime sentAt
    ) {}
}
