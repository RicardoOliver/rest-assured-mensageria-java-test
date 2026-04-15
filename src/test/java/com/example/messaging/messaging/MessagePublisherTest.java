package com.example.messaging.messaging;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessagePublisherTest {
    @Test
    void publish_deveEnviarMensagemComHeadersEContentTypeEsperados() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        MessagePublisher publisher = new MessagePublisher(rabbitTemplate);

        String messageId = "msg-123";
        String payload = "{\"type\":\"ORDER_CREATED\",\"data\":{\"orderId\":\"123\"}}";

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        publisher.publish(messageId, payload);

        verify(rabbitTemplate).send(
                eq(RabbitTopologyConfig.EVENTS_EXCHANGE),
                eq(RabbitTopologyConfig.EVENTS_ROUTING_KEY),
                messageCaptor.capture()
        );

        Message sent = messageCaptor.getValue();

        Assertions.assertEquals(payload, new String(sent.getBody(), StandardCharsets.UTF_8));
        Assertions.assertEquals(MessageProperties.CONTENT_TYPE_JSON, sent.getMessageProperties().getContentType());
        Assertions.assertEquals(StandardCharsets.UTF_8.name(), sent.getMessageProperties().getContentEncoding());
        Assertions.assertEquals(messageId, String.valueOf(sent.getMessageProperties().getHeaders().get("messageId")));

        Object producedAt = sent.getMessageProperties().getHeaders().get("producedAt");
        Assertions.assertNotNull(producedAt);
        Assertions.assertDoesNotThrow(() -> OffsetDateTime.parse(String.valueOf(producedAt)));
    }
}
