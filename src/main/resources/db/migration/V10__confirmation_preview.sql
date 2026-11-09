-- Une tâche à confirmer peut d'abord être vérifiée par l'API, sans rien exécuter :
-- preview_query est le paramètre d'adresse qui demande cet aperçu (par exemple apercu=true).
ALTER TABLE bandy_tool ADD COLUMN IF NOT EXISTS preview_query VARCHAR(100);
