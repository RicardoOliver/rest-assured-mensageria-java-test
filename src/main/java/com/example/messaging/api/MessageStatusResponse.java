package com.example.messaging.api;

public record MessageStatusResponse(
        String messageId,
        String status
) {
}

