-- Niveau d'action de chaque outil ; les outils existants sont des consultations.
ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS action_level VARCHAR(16) NOT NULL DEFAULT 'READ';

ALTER TABLE bandy_tool
    ADD CONSTRAINT bandy_tool_action_level_check
    CHECK (action_level IN ('READ', 'NAVIGATE', 'PREPARE', 'CONFIRM'));
