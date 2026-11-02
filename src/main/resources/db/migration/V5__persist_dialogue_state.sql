-- Some installations were baselined at V2 before the persistent message
-- and memory tables had actually been created. Reconcile those legacy
-- installations here as well as adding dialogue_state to existing memories.

CREATE TABLE IF NOT EXISTS bandy_conversation_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL
        REFERENCES bandy_conversation(id) ON DELETE CASCADE,
    sequence_number BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'TOOL')),
    content TEXT NOT NULL,
    message_type VARCHAR(40) NOT NULL,
    metadata JSONB NOT NULL,
    request_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (conversation_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_bandy_message_conversation_order
    ON bandy_conversation_message (conversation_id, sequence_number);

CREATE INDEX IF NOT EXISTS idx_bandy_message_request
    ON bandy_conversation_message (conversation_id, request_id);

CREATE TABLE IF NOT EXISTS bandy_conversation_memory (
    conversation_id UUID PRIMARY KEY
        REFERENCES bandy_conversation(id) ON DELETE CASCADE,
    summary TEXT NOT NULL DEFAULT '',
    active_state JSONB NOT NULL DEFAULT '{}'::JSONB,
    result_references JSONB NOT NULL DEFAULT '[]'::JSONB,
    summarized_through_sequence BIGINT NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 1,
    dialogue_state JSONB NOT NULL
        DEFAULT '{"schemaVersion":1,"activeObjectiveId":null,"objectives":[],"pendingClarification":null}'::JSONB,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE bandy_conversation_memory
    ADD COLUMN IF NOT EXISTS dialogue_state JSONB NOT NULL
        DEFAULT '{"schemaVersion":1,"activeObjectiveId":null,"objectives":[],"pendingClarification":null}'::JSONB;
