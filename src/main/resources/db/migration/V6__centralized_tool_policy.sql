ALTER TABLE bandy_tool
    ADD COLUMN IF NOT EXISTS approved_for_gemini BOOLEAN NOT NULL DEFAULT FALSE;

-- Keep the existing RH integration working while requiring explicit approval
-- for every other tool. Sensitivity remains an independent persistence policy.
UPDATE bandy_tool
SET approved_for_gemini = TRUE,
    sensitive = CASE
        WHEN name IN ('get_agent_profile', 'search_agents') THEN TRUE
        ELSE sensitive
    END
WHERE name IN (
    'get_agent_profile',
    'search_agents',
    'get_organization_info'
);
