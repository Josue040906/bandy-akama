-- Contexte de l'utilisateur connecté (par exemple son prénom) : le catalogue
-- désigne l'outil à lire et les seuls champs à garder ; la conversation
-- conserve ce contexte pour ne le demander qu'une fois.
ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS user_context_fields VARCHAR(200);

ALTER TABLE bandy_conversation_memory
    ADD COLUMN IF NOT EXISTS user_context TEXT;
