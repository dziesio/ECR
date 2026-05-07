package com.ecrharv.harvester.repository;

import com.ecrharv.harvester.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StudentRepository extends JpaRepository<Student, UUID> {

    Optional<Student> findByLibrusUsername(String librusUsername);

    Optional<Student> findByBcId(String bcId);
}
