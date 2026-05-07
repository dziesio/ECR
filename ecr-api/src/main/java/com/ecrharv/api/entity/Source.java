package com.ecrharv.api.entity;

import jakarta.persistence.*;
import lombok.Getter;

@Entity
@Table(name = "sources")
@Getter
public class Source {

    @Id
    private Short id;

    @Column(name = "code", unique = true, nullable = false)
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;
}
