package com.ecrharv.api.dto;

import com.ecrharv.api.entity.Student;

import java.time.LocalDateTime;
import java.util.UUID;

public record StudentResponse(
        UUID id,
        String librusUsername,
        String bcId,
        String fullName,
        String className,
        LocalDateTime createdAt
) {
    public static StudentResponse from(Student s) {
        return new StudentResponse(s.getId(), s.getLibrusUsername(), s.getBcId(),
                s.getFullName(), s.getClassName(), s.getCreatedAt());
    }
}
