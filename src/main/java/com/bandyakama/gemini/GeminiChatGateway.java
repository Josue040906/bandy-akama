package com.bandyakama.gemini;

import com.google.genai.Chat;
import com.google.genai.types.GenerateContentConfig;

/** Small seam around the SDK chat creation API for deterministic orchestration tests. */
@FunctionalInterface
public interface GeminiChatGateway {
    Chat createChat(String model, GenerateContentConfig config);
}
