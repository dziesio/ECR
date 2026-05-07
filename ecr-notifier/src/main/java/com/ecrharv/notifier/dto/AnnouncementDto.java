package com.ecrharv.notifier.dto;

import java.time.LocalDate;
import java.util.UUID;

public record AnnouncementDto(UUID id, String source, String title, String content, String author, String authorRole, LocalDate publishedAt) {}
