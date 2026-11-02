-- Per-parameter continuation semantics. Existing parameters keep REPLACE
-- so the migration does not silently change established tool behavior.
-- Some legacy deployments were baselined before the tool catalog migration.
-- Ensure the parameter table exists before adding its continuation metadata.
CREATE TABLE IF NOT EXISTS bandy_tool_parameter (
    tool_name VARCHAR(64) NOT NULL REFERENCES bandy_tool(name) ON DELETE CASCADE,
    name VARCHAR(64) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    location VARCHAR(10),
    required BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL,
    PRIMARY KEY (tool_name, name),
    UNIQUE (tool_name, position)
);

ALTER TABLE bandy_tool_parameter
    ADD COLUMN IF NOT EXISTS merge_strategy VARCHAR(20) NOT NULL DEFAULT 'REPLACE';

ALTER TABLE bandy_tool_parameter
    ADD COLUMN IF NOT EXISTS merge_key VARCHAR(64);

ALTER TABLE bandy_tool_parameter
    ADD COLUMN IF NOT EXISTS remove_marker VARCHAR(64) NOT NULL DEFAULT 'remove';

-- The RH search tool opts into the same generic keyed-JSON merge contract
-- available to any other tool. No RH field names are embedded in Java logic.
UPDATE bandy_tool_parameter
SET merge_strategy = 'MERGE_JSON',
    merge_key = 'field',
    remove_marker = 'remove'
WHERE tool_name = 'search_agents'
  AND name = 'criteriaJson';
