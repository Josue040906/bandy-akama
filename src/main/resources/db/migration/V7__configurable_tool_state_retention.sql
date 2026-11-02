ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS retain_result_in_state BOOLEAN NOT NULL DEFAULT FALSE;

-- Preserve the existing same-conversation profile follow-up behavior explicitly.
-- New tools never retain results unless an administrator opts in.
UPDATE bandy_tool
SET retain_result_in_state = TRUE
WHERE name = 'get_agent_profile';
