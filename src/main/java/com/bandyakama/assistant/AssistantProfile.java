package com.bandyakama.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssistantProfile(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 500) String role,
        @NotBlank @Size(max = 12000) String instructions
) {
}
