package com.ecrharv.harvester.repository;

import com.ecrharv.harvester.entity.Person;
import com.ecrharv.harvester.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PersonRepository extends JpaRepository<Person, UUID> {
    Optional<Person> findBySourceAndFullNameAndRole(Source source, String fullName, String role);
}
