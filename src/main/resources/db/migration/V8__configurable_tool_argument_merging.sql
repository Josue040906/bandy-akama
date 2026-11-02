-- Per-parameter continuation semantics. Existing parameters keep REPLACE
-- so the migration does not silently change established tool behavior.
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
