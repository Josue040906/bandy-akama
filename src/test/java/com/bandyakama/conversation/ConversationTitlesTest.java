package com.bandyakama.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTitlesTest {

    @Test
    void laPremiereQuestionDevientLeTitreSansSaPonctuationFinale() {
        assertEquals("Combien y a-t-il d’agents contractuels",
                ConversationTitles.depuisPremierMessage("  combien y a-t-il d’agents contractuels ?  "));
    }

    @Test
    void seuleLaPremiereLigneCompteEtLesEspacesSontResserres() {
        assertEquals("Prépare une demande de congé",
                ConversationTitles.depuisPremierMessage("Prépare   une demande\tde congé.\nDu 2 au 4 février."));
    }

    @Test
    void uneLongueQuestionEstCoupeeAUnMotEntier() {
        String titre = ConversationTitles.depuisPremierMessage(
                "Prépare une demande de mutation pour Lalaina RAZAFINDRATSIMA vers le Service du Personnel au poste de gestionnaire");
        assertTrue(titre.length() <= ConversationTitles.LONGUEUR_MAX, titre);
        assertTrue(titre.endsWith("…"), titre);
        assertEquals("Prépare une demande de mutation pour Lalaina…", titre);
    }

    @Test
    void laMentionDePieceJointeNEstPasDansLeTitre() {
        assertEquals("Enregistre ce diplôme",
                ConversationTitles.depuisPremierMessage("Enregistre ce diplôme.\n[Pièce jointe : diplome.jpg, 134 Ko]"));
    }

    @Test
    void unePieceJointeSeuleDonneSonNom() {
        assertEquals("Pièce jointe : cin-rakoto.png",
                ConversationTitles.depuisPremierMessage("[Pièce jointe : cin-rakoto.png, 42 Ko]"));
    }

    @Test
    void unMessageVideGardeLeTitreParDefaut() {
        assertEquals(ConversationTitles.PAR_DEFAUT, ConversationTitles.depuisPremierMessage("   "));
        assertEquals(ConversationTitles.PAR_DEFAUT, ConversationTitles.depuisPremierMessage(null));
    }
}
