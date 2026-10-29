ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS approved_for_gemini BOOLEAN NOT NULL DEFAULT FALSE;

-- Preserve the currently supported production tools while making approval explicit
-- for every newly registered tool.
UPDATE bandy_tool
SET approved_for_gemini = TRUE
WHERE name IN (
    'get_agent_profile',
    'search_agents',
    'get_organization_info'
);
