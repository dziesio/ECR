package com.ecrharv.frontend.dto;

import java.time.LocalDate;
import java.util.UUID;

public record GradeDto(UUID id, String subjectName, String category, String gradeValue,
                       int weight, LocalDate dateIssued, String teacher) {}
