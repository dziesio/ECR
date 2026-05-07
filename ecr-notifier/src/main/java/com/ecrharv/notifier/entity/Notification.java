package com.ecrharv.notifier.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "notifications", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"entity_id", "entity_type"})
})
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "entity_type", nullable = false, length = 20)
    private String entityType;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt;

    public Notification(UUID entityId, String entityType, String title, String body) {
        this.entityId = entityId;
        this.entityType = entityType;
        this.title = title;
        this.body = body;
        this.notifiedAt = LocalDateTime.now();
    }
}
