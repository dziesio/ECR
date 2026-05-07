package com.ecrharv.frontend.service;

import com.ecrharv.frontend.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EcrApiClient {

    private final RestClient restClient;

    public List<StudentDto> getStudents() {
        List<StudentDto> result = restClient.get()
                .uri("/api/students")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        return result != null ? result : List.of();
    }

    public StudentDto getStudent(UUID id) {
        return restClient.get()
                .uri("/api/students/{id}", id)
                .retrieve()
                .body(StudentDto.class);
    }

    public List<GradeDto> getGrades(UUID studentId) {
        List<GradeDto> result = restClient.get()
                .uri("/api/students/{id}/grades", studentId)
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

    public List<AttendanceDto> getAttendance(UUID studentId) {
        List<AttendanceDto> result = restClient.get()
                .uri("/api/students/{id}/attendance", studentId)
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
