ALTER TABLE bandy_conversation_memory
    ADD COLUMN IF NOT EXISTS dialogue_state JSONB NOT NULL
    DEFAULT CAST(
        '{"schemaVersion":1,"activeObjectiveId":null,"objectives":[],"pendingClarifications":[]}'
        AS JSONB
    );
