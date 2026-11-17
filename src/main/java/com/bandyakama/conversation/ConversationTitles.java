package com.bandyakama.conversation;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le titre d'une conversation, tiré de sa première question : la première
 * ligne, sans la mention de pièce jointe, raccourcie à un mot entier. Une
 * question faite d'une pièce jointe seule prend le nom de la pièce.
 */
public final class ConversationTitles {

    public static final String PAR_DEFAUT = "Nouvelle conversation";
    static final int LONGUEUR_MAX = 60;

    private static final Pattern MENTION = Pattern.compile("\\[Pièce jointe : ([^,\\]]+)(?:, [^\\]]*)?]");

    private ConversationTitles() {
    }

    public static String depuisPremierMessage(String message) {
        if (message == null) {
            return PAR_DEFAUT;
        }
        Matcher mention = MENTION.matcher(message);
        String piece = mention.find() ? mention.group(1).strip() : null;
        String texte = MENTION.matcher(message).replaceAll(" ").strip();
        String premiereLigne = texte.isEmpty() ? "" : texte.lines().map(String::strip)
                .filter(ligne -> !ligne.isEmpty()).findFirst().orElse("");
        String titre = premiereLigne.replaceAll("\\s+", " ").replaceAll("[\\s?.!:;,]+$", "");
        if (titre.isEmpty()) {
            return piece == null ? PAR_DEFAUT : raccourcir("Pièce jointe : " + piece);
        }
        titre = titre.substring(0, 1).toUpperCase(Locale.FRENCH) + titre.substring(1);
        return raccourcir(titre);
    }

    private static String raccourcir(String titre) {
        if (titre.length() <= LONGUEUR_MAX) {
            return titre;
        }
        String coupe = titre.substring(0, LONGUEUR_MAX - 1);
        int espace = coupe.lastIndexOf(' ');
        if (espace > LONGUEUR_MAX / 2) {
            coupe = coupe.substring(0, espace);
        }
        return coupe.replaceAll("[\\s,;:]+$", "") + "…";
    }
}
