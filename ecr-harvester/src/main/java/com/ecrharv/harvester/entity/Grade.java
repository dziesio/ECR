package com.ecrharv.harvester.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "grades")
@Getter
@Setter
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "subject_name", nullable = false)
    private String subjectName;

    @Column(name = "category")
    private String category;

    @Column(name = "grade_value", nullable = false)
    private String gradeValue;

    @Column(name = "weight", nullable = false)
    private int weight = 1;

    @Column(name = "date_issued")
    private LocalDate dateIssued;

    @Column(name = "teacher")
    private String teacher;
}
