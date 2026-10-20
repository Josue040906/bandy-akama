package com.bandyakama.conversation;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record ConversationMessage(
        UUID id,
        long sequenceNumber,
        String role,
        String content,
        String messageType,
        JsonNode metadata,
        Instant createdAt
) {
}
