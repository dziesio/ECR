package com.ecrharv.api.repository;

import com.ecrharv.api.entity.Attendance;
import com.ecrharv.api.entity.Student;
import com.ecrharv.api.enums.AttendanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {

    List<Attendance> findByStudentOrderByDateDescLessonNumberAsc(Student student);

    List<Attendance> findByStudentAndStatusOrderByDateDesc(Student student, AttendanceStatus status);
}
