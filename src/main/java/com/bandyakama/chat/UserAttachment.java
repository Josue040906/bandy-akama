package com.bandyakama.chat;

import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Une pièce jointe au message de l'utilisateur (photo ou PDF), transmise
 * telle quelle au modèle pour ce tour seulement. Bandy ne l'enregistre
 * jamais : l'historique ne garde que sa mention (nom et taille).
 */
public record UserAttachment(String name, String mimeType, byte[] data) {

    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    /** Contenu reçu en base64 ; refusé s'il est d'un autre type, vide, illisible ou trop lourd. */
    public static UserAttachment decode(String name, String mimeType, String base64) {
        String type = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Pièce jointe refusée : seuls les images JPEG, PNG, WebP et les PDF sont acceptés."
            );
        }
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("La pièce jointe est vide.");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException unreadable) {
            throw new IllegalArgumentException("La pièce jointe est illisible.");
        }
        if (data.length == 0 || data.length > MAX_BYTES) {
            throw new IllegalArgumentException("La pièce jointe doit faire au plus 4 Mo.");
        }
        String cleanName = name == null || name.isBlank()
                ? "piece-jointe"
                : name.strip().replaceAll("[\\p{Cntrl}\\\\/]", "_");
        return new UserAttachment(cleanName.length() > 120 ? cleanName.substring(0, 120) : cleanName, type, data);
    }

    /** Ce que l'historique garde de la pièce : son nom et sa taille, jamais son contenu. */
    public String mention() {
        long kilobytes = Math.max(1, Math.round(data.length / 1024.0));
        return "[Pièce jointe : " + name + ", " + kilobytes + " Ko]";
    }
}
