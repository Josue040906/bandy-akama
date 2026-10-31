package com.bandyakama.service;

/** Signals that the user must clarify an ambiguous or unresolved reference before execution. */
final class ConversationClarificationRequiredException extends RuntimeException {
    private final String question;

    ConversationClarificationRequiredException(String question) {
        super(question);
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("La question de clarification est obligatoire.");
        }
        this.question = question;
    }

    String question() {
        return question;
    }
}
