package com.ecrharv.api.repository;

import com.ecrharv.api.entity.Message;
import com.ecrharv.api.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByStudentOrderBySentAtDesc(Student student);
}
