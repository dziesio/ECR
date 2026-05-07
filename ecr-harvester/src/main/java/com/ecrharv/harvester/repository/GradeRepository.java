package com.ecrharv.harvester.repository;

import com.ecrharv.harvester.entity.Grade;
import com.ecrharv.harvester.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface GradeRepository extends JpaRepository<Grade, UUID> {

    boolean existsByStudentAndSubjectNameAndGradeValueAndDateIssued(
            Student student,
            String subjectName,
            String gradeValue,
            LocalDate dateIssued
    );
}
