-- Generic tool execution and memory policies.
-- V6 is reserved for the existing centralized approval migration in the
-- legacy tool-policy branch. This migration remains safe if that branch lands
-- first because both columns use IF NOT EXISTS.
ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS approved_for_gemini BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS retain_result_in_state BOOLEAN NOT NULL DEFAULT FALSE;

-- Preserve current RH integration behavior while requiring explicit opt-in
-- for new tools. Sensitive results remain excluded from durable history.
UPDATE bandy_tool
SET approved_for_gemini = TRUE
WHERE name IN (
    'get_agent_profile',
    'search_agents',
    'get_organization_info'
);

UPDATE bandy_tool
SET sensitive = TRUE
WHERE name IN ('get_agent_profile', 'search_agents');

-- Retain only the agent profile required for follow-up questions, explicitly.
UPDATE bandy_tool
SET retain_result_in_state = TRUE
WHERE name = 'get_agent_profile';
