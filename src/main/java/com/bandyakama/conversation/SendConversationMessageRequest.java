package com.bandyakama.conversation;

import com.bandyakama.chat.UserAttachment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * Le message de l'utilisateur et, au besoin, une pièce jointe (photo ou PDF
 * en base64). Un message peut être vide s'il porte une pièce jointe.
 */
public record SendConversationMessageRequest(
        @Size(max = 10000) String message,
        @Valid Attachment attachment
) {
    public SendConversationMessageRequest(String message) {
        this(message, null);
    }

    public record Attachment(
            @Size(max = 200) String name,
            @Size(max = 100) String mimeType,
            // 4 Mo en base64 font environ 5,6 millions de caractères.
            @Size(max = 5_600_000) String data
    ) {
    }

    /** Le texte du message (jamais nul) ; refusé s'il est vide sans pièce jointe. */
    public String text() {
        String text = message == null ? "" : message.strip();
        if (text.isEmpty() && attachment == null) {
            throw new IllegalArgumentException("Le message est vide.");
        }
        return text;
    }

    /** La pièce jointe décodée et vérifiée, ou null. */
    public UserAttachment decodedAttachment() {
        return attachment == null
                ? null
                : UserAttachment.decode(attachment.name(), attachment.mimeType(), attachment.data());
    }
}
