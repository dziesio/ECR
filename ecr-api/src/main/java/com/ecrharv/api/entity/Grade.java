package com.ecrharv.api.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "grades")
@Getter
public class Grade {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(name = "subject_name")
    private String subjectName;

    @Column(name = "category")
    private String category;

    @Column(name = "grade_value")
    private String gradeValue;

    @Column(name = "weight")
    private int weight;

    @Column(name = "date_issued")
    private LocalDate dateIssued;

    @Column(name = "teacher")
    private String teacher;
}
