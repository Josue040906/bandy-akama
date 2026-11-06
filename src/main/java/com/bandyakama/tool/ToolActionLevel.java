package com.bandyakama.tool;

/**
 * Ce que Bandy fait d'un appel à l'outil. C'est une donnée du catalogue : le
 * code n'associe jamais un niveau à un outil précis. Une action « interdite »
 * n'a pas de niveau : elle n'est tout simplement pas enregistrée comme outil.
 */
public enum ToolActionLevel {
    /** Consultation : Bandy appelle l'API et Gemini explique le résultat. */
    READ,
    /** Navigation : Bandy propose au front d'ouvrir une page autorisée. */
    NAVIGATE,
    /** Tâche préparée : Bandy préremplit un formulaire, l'utilisateur valide. */
    PREPARE,
    /** Tâche confirmée : exécutée seulement après un « Confirmer » structuré. */
    CONFIRM
}
