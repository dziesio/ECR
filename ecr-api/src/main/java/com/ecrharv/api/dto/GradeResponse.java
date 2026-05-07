package com.ecrharv.api.dto;

import com.ecrharv.api.entity.Grade;

import java.time.LocalDate;
import java.util.UUID;

public record GradeResponse(
        UUID id,
        String subjectName,
        String category,
        String gradeValue,
        int weight,
        LocalDate dateIssued,
        String teacher
) {
    public static GradeResponse from(Grade g) {
        return new GradeResponse(
                g.getId(), g.getSubjectName(), g.getCategory(),
                g.getGradeValue(), g.getWeight(), g.getDateIssued(), g.getTeacher()
        );
    }
}
