package com.ecrharv.frontend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record StudentDto(UUID id, String librusUsername, String fullName, String className, LocalDateTime createdAt) {}
