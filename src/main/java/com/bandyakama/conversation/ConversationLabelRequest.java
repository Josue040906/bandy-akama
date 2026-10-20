package com.bandyakama.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConversationLabelRequest(
        @NotBlank @Size(max = 200) String label
) {
}
