package com.example.messaging.messaging;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class MessagePublisher {
    private final RabbitTemplate rabbitTemplate;

    public MessagePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(String messageId, String payload) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setHeader("messageId", messageId);
        props.setHeader("producedAt", OffsetDateTime.now().toString());

        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE, RabbitTopologyConfig.EVENTS_ROUTING_KEY, message);
    }
}
