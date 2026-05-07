package com.ecrharv.frontend.dto;

import java.time.LocalDate;
import java.util.UUID;

public record AttendanceDto(UUID id, LocalDate date, int lessonNumber, String status, String subject) {}
