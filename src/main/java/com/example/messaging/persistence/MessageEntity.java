package com.example.messaging.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "messages")
public class MessageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long internalId;

    @Column(name = "message_id", nullable = false, unique = true, length = 128)
    private String messageId;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected MessageEntity() {
    }

    public MessageEntity(String messageId, String payload, OffsetDateTime createdAt) {
        this.messageId = messageId;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long getInternalId() {
        return internalId;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getPayload() {
        return payload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

