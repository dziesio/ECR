package com.ecrharv.api.repository;

import com.ecrharv.api.entity.Announcement;
import com.ecrharv.api.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    List<Announcement> findByStudentOrderByPublishedAtDesc(Student student);
}
