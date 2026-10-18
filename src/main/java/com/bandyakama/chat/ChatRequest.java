package com.bandyakama.chat;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String message) {
}
