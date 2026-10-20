package com.bandyakama.conversation;

import java.time.Instant;
import java.util.UUID;

public record Conversation(
        UUID id,
        String label,
        String status,
        Instant createdAt,
        Instant lastActivityAt
) {
}
