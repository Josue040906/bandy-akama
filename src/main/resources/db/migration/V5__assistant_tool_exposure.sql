ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS assistant_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Preserve only the currently supported assistant tools during the transition.
-- Other registered tools remain stored but are not exposed to Gemini.
UPDATE bandy_tool
SET assistant_enabled = TRUE
WHERE name IN (
    'search_agents',
    'get_agent_profile',
    'get_organization_info'
);

CREATE INDEX IF NOT EXISTS idx_bandy_tool_assistant_enabled
    ON bandy_tool (assistant_enabled, enabled);
