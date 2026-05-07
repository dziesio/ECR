package com.ecrharv.harvester.repository;

import com.ecrharv.harvester.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Set;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    boolean existsByLibrusMessageId(String librusMessageId);

    @Query("SELECT m.librusMessageId FROM Message m")
    Set<String> findAllLibrusMessageIds();
}
