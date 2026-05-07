package com.ecrharv.harvester.repository;

import com.ecrharv.harvester.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    boolean existsByLibrusAnnouncementId(String librusAnnouncementId);

    @Query("SELECT a.librusAnnouncementId FROM Announcement a")
    Set<String> findAllLibrusAnnouncementIds();
}
