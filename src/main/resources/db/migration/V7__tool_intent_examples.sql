CREATE TABLE IF NOT EXISTS bandy_tool_example (
    tool_name VARCHAR(64) NOT NULL
        REFERENCES bandy_tool(name) ON DELETE CASCADE,
    example_text VARCHAR(500) NOT NULL,
    position INTEGER NOT NULL CHECK (position >= 0),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tool_name, example_text),
    UNIQUE (tool_name, position),
    CHECK (length(trim(example_text)) > 0)
);
