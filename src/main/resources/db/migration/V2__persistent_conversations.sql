CREATE TABLE IF NOT EXISTS bandy_conversation (
    id UUID PRIMARY KEY,
    application_id VARCHAR(120) NOT NULL,
    owner_id VARCHAR(200) NOT NULL,
    label VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    archived_at TIMESTAMP WITH TIME ZONE,
    active_request_id UUID,
    processing_until TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_bandy_conversation_owner_activity
    ON bandy_conversation (application_id, owner_id, last_activity_at DESC);

CREATE TABLE IF NOT EXISTS bandy_conversation_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES bandy_conversation(id) ON DELETE CASCADE,
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
    active_state JSONB NOT NULL,
    result_references JSONB NOT NULL,
    summarized_through_sequence BIGINT NOT NULL DEFAULT 0,
    schema_version INTEGER NOT NULL DEFAULT 1,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bandy_conversation_request (
    conversation_id UUID NOT NULL REFERENCES bandy_conversation(id) ON DELETE CASCADE,
    idempotency_key UUID NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    response_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, idempotency_key)
);
