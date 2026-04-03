package com.example.messaging.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    Optional<MessageEntity> findByMessageId(String messageId);
}

