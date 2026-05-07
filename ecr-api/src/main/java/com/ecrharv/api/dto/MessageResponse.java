package com.ecrharv.api.dto;

import com.ecrharv.api.entity.Message;
import com.ecrharv.api.entity.Person;
import com.ecrharv.api.enums.MessageType;

import java.time.LocalDateTime;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        MessageType messageType,
        String messageSource,
        String sender,
        String senderRole,
        String subject,
        String content,
        LocalDateTime sentAt
) {
    public static MessageResponse from(Message m) {
        Person p = m.getSender();
        return new MessageResponse(
                m.getId(),
                m.getMessageType(),
                p != null ? p.getSource().getCode() : null,
                p != null ? p.getFullName() : null,
                p != null && !p.getRole().isBlank() ? p.getRole() : null,
                m.getSubject(),
                m.getContent(),
                m.getSentAt()
        );
    }
}
