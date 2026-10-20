package com.bandyakama.conversation;

import java.util.List;
import java.util.UUID;

public record ConversationMessagePage(
        UUID conversationId,
        List<ConversationMessage> items,
        int page,
        int size,
        long total
) {
}
