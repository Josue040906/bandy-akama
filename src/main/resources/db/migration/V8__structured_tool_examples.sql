ALTER TABLE bandy_tool_example
    ADD COLUMN pattern_text VARCHAR(500),
    ADD COLUMN expected_arguments TEXT NOT NULL DEFAULT '{}';

UPDATE bandy_tool_example
SET pattern_text = example_text
WHERE pattern_text IS NULL;

ALTER TABLE bandy_tool_example
    ALTER COLUMN pattern_text SET NOT NULL;

ALTER TABLE bandy_tool_example
    ADD CONSTRAINT chk_bandy_tool_example_pattern
        CHECK (length(trim(pattern_text)) > 0);
