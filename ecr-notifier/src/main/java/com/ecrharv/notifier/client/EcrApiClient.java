package com.ecrharv.notifier.client;

import com.ecrharv.notifier.dto.AnnouncementDto;
import com.ecrharv.notifier.dto.MessageDto;
import com.ecrharv.notifier.dto.StudentDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Component
public class EcrApiClient {

    private final RestClient restClient;

    public EcrApiClient(@Qualifier("ecrApiRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public List<StudentDto> getStudents() {
        List<StudentDto> result = restClient.get()
                .uri("/api/students")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : List.of();
    }

    public List<MessageDto> getMessages(UUID studentId) {
        List<MessageDto> result = restClient.get()
                .uri("/api/students/{id}/messages", studentId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : List.of();
    }

    public List<AnnouncementDto> getAnnouncements(UUID studentId) {
        List<AnnouncementDto> result = restClient.get()
                .uri("/api/students/{id}/announcements", studentId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : List.of();
    }
}
