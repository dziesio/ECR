package com.ecrharv.api.dto;

import com.ecrharv.api.entity.Attendance;
import com.ecrharv.api.enums.AttendanceStatus;

import java.time.LocalDate;
import java.util.UUID;

public record AttendanceResponse(
        UUID id,
        LocalDate date,
        int lessonNumber,
        AttendanceStatus status,
        String subject
) {
    public static AttendanceResponse from(Attendance a) {
        return new AttendanceResponse(a.getId(), a.getDate(), a.getLessonNumber(), a.getStatus(), a.getSubject());
    }
}
