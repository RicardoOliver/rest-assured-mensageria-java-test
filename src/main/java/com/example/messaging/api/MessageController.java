package com.example.messaging.api;

import com.example.messaging.messaging.MessagePublisher;
import com.example.messaging.persistence.MessageRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/messages")
public class MessageController {
    private final MessagePublisher messagePublisher;
    private final MessageRepository messageRepository;

    public MessageController(MessagePublisher messagePublisher, MessageRepository messageRepository) {
        this.messagePublisher = messagePublisher;
        this.messageRepository = messageRepository;
    }

    @PostMapping
    public ResponseEntity<Void> post(@Valid @RequestBody MessageRequest request) {
        messagePublisher.publish(request.messageId(), request.payload().toString());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<MessageStatusResponse> status(@PathVariable String messageId) {
        boolean persisted = messageRepository.findByMessageId(messageId).isPresent();
        if (!persisted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new MessageStatusResponse(messageId, "PERSISTED"));
    }
}
