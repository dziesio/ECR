package com.ecrharv.notifier.service;

import com.ecrharv.notifier.client.EcrApiClient;
import com.ecrharv.notifier.dto.AnnouncementDto;
import com.ecrharv.notifier.dto.MessageDto;
import com.ecrharv.notifier.dto.StudentDto;
import com.ecrharv.notifier.entity.Notification;
import com.ecrharv.notifier.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String TYPE_MESSAGE      = "MESSAGE";
    private static final String TYPE_ANNOUNCEMENT = "ANNOUNCEMENT";

    private final EcrApiClient           apiClient;
    private final BarkService            barkService;
    private final NotificationRepository notificationRepository;

    @Scheduled(fixedDelayString = "${notifier.poll-interval-ms:300000}")
    public void pollAndNotify() {
        log.info("Polling ecr-api for new messages and announcements");
        try {
            List<StudentDto> students = apiClient.getStudents();
            for (StudentDto student : students) {
                processMessages(student);
                processAnnouncements(student);
            }
        } catch (Exception e) {
            log.warn("Polling cycle failed: {}", e.getMessage());
        }
    }

    private void processMessages(StudentDto student) {
        List<MessageDto> messages = apiClient.getMessages(student.id());
        for (MessageDto msg : messages) {
            if (!"INBOX".equalsIgnoreCase(msg.messageType())) continue;
            if (notificationRepository.existsByEntityIdAndEntityType(msg.id(), TYPE_MESSAGE)) {
                continue;
            }
            String source = toDisplaySource(msg.messageSource());
            String role   = msg.senderRole() != null && !msg.senderRole().isBlank() ? " [" + msg.senderRole() + "]" : "";
            String title  = source + ": New message - " + msg.sender() + role;
            String body   = msg.subject() != null ? msg.subject() : "(no subject)";
            barkService.send(title, body);
            notificationRepository.save(new Notification(msg.id(), TYPE_MESSAGE, title, body));
        }
    }

    private void processAnnouncements(StudentDto student) {
        List<AnnouncementDto> announcements = apiClient.getAnnouncements(student.id());
        for (AnnouncementDto ann : announcements) {
            if (notificationRepository.existsByEntityIdAndEntityType(ann.id(), TYPE_ANNOUNCEMENT)) {
                continue;
            }
            String source = toDisplaySource(ann.source());
            String author = ann.author() != null && !ann.author().isBlank() ? ann.author() : "unknown";
            String role   = ann.authorRole() != null && !ann.authorRole().isBlank() ? " [" + ann.authorRole() + "]" : "";
            String title  = source + ": Announcement - " + author + role;
            String body   = ann.title() != null ? ann.title() : "(no title)";
            barkService.send(title, body);
            notificationRepository.save(new Notification(ann.id(), TYPE_ANNOUNCEMENT, title, body));
        }
    }

    private String toDisplaySource(String messageSource) {
        if ("BRITISH_COUNCIL".equalsIgnoreCase(messageSource)) return "British Council";
        return "Librus";
    }
}
