package com.ecrharv.harvester.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "persons", uniqueConstraints =
        @UniqueConstraint(name = "uq_persons", columnNames = {"source_id", "full_name", "role"}))
@Getter
@Setter
@NoArgsConstructor
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "source_id", nullable = false)
    private Source source;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "role", nullable = false)
    private String role;

    public Person(Source source, String fullName, String role) {
        this.source   = source;
        this.fullName = fullName;
        this.role     = role;
    }
}
