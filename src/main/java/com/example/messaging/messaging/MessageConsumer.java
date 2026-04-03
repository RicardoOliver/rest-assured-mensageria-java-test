package com.example.messaging.messaging;

import com.example.messaging.persistence.MessageEntity;
import com.example.messaging.persistence.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MessageConsumer {
    private final ObjectMapper objectMapper;
    private final MessageRepository messageRepository;

    public MessageConsumer(ObjectMapper objectMapper, MessageRepository messageRepository) {
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
    }

    @Transactional
    @RabbitListener(queues = RabbitTopologyConfig.EVENTS_QUEUE)
    public void consume(Message amqpMessage) {
        String messageId = headerAsString(amqpMessage, "messageId");
        if (messageId == null || messageId.isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Missing messageId header");
        }

        if (messageRepository.findByMessageId(messageId).isPresent()) {
            return;
        }

        String payload = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
        ensureBusinessValid(payload);

        try {
            messageRepository.save(new MessageEntity(messageId, payload, OffsetDateTime.now()));
        } catch (DataIntegrityViolationException duplicate) {
            return;
        }
    }

    private void ensureBusinessValid(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode type = root.get("type");
            if (type == null || type.asText().isBlank()) {
                throw new AmqpRejectAndDontRequeueException("Missing required field: type");
            }
        } catch (AmqpRejectAndDontRequeueException e) {
            throw e;
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("Invalid JSON payload", e);
        }
    }

    private String headerAsString(Message message, String header) {
        Object value = message.getMessageProperties().getHeaders().get(header);
        return value == null ? null : String.valueOf(value);
    }
}

