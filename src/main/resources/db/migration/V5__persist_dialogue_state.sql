ALTER TABLE bandy_conversation_memory
    ADD COLUMN dialogue_state JSONB NOT NULL
    DEFAULT '{"schemaVersion":1,"activeObjectiveId":null,"objectives":[],"pendingClarification":null}'::JSONB;
