package com.bandyakama.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ChatRequest(
        @NotBlank @Size(max = 10000) String message,
        UUID conversationId
) {
}
