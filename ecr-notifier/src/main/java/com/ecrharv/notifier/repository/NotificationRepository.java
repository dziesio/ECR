package com.ecrharv.notifier.repository;

import com.ecrharv.notifier.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    boolean existsByEntityIdAndEntityType(UUID entityId, String entityType);
    List<Notification> findTop3ByEntityTypeOrderByNotifiedAtDesc(String entityType);
}
