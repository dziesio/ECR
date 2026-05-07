package com.ecrharv.api.repository;

import com.ecrharv.api.entity.Grade;
import com.ecrharv.api.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GradeRepository extends JpaRepository<Grade, UUID> {

    List<Grade> findByStudent(Student student);

    List<Grade> findByStudentAndSubjectName(Student student, String subjectName);
}
