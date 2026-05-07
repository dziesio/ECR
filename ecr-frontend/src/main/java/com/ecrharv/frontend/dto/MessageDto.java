package com.ecrharv.frontend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record MessageDto(UUID id, String messageType, String messageSource, String sender, String senderRole, String subject, String content, LocalDateTime sentAt) {}
