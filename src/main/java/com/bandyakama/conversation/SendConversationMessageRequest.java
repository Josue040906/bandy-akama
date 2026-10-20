package com.bandyakama.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendConversationMessageRequest(
        @NotBlank @Size(max = 10000) String message
) {
}
