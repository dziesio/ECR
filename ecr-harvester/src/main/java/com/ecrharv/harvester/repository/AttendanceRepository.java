package com.ecrharv.harvester.repository;

import com.ecrharv.harvester.entity.Attendance;
import com.ecrharv.harvester.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    boolean existsByStudentAndDateAndLessonNumber(Student student, LocalDate date, int lessonNumber);
}
