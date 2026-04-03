package com.example.messaging.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MessageRequest(
        @NotBlank String messageId,
        @NotNull JsonNode payload
) {
}

