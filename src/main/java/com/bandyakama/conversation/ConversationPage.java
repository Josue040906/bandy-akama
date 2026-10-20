package com.bandyakama.conversation;

import java.util.List;

public record ConversationPage(
        List<Conversation> items,
        int page,
        int size,
        long total
) {
}
