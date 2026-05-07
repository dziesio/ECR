package com.ecrharv.api.dto;

import com.ecrharv.api.entity.Announcement;
import com.ecrharv.api.entity.Person;

import java.time.LocalDate;
import java.util.UUID;

public record AnnouncementResponse(
        UUID id,
        String source,
        String title,
        String content,
        String author,
        String authorRole,
        LocalDate publishedAt
) {
    public static AnnouncementResponse from(Announcement a) {
        Person p = a.getAuthor();
        return new AnnouncementResponse(
                a.getId(),
                p != null ? p.getSource().getCode() : null,
                a.getTitle(),
                a.getContent(),
                p != null ? p.getFullName() : null,
                p != null && !p.getRole().isBlank() ? p.getRole() : null,
                a.getPublishedAt()
        );
    }
}
