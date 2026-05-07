package com.ecrharv.api.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "students")
@Getter
public class Student {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "librus_username")
    private String librusUsername;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "class_name")
    private String className;

    @Column(name = "bc_id")
    private String bcId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
