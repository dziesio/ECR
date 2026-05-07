package com.ecrharv.harvester.repository;

import com.ecrharv.harvester.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SourceRepository extends JpaRepository<Source, Short> {
    Optional<Source> findByCode(String code);
}
