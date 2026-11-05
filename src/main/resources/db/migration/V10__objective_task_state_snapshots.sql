-- Per-objective operational snapshots. Sensitive profile payloads are intentionally
-- excluded by the persistence adapter; this table stores only reusable task state
-- and non-sensitive result references.
CREATE TABLE IF NOT EXISTS bandy_conversation_task_state (
    conversation_id UUID NOT NULL
        REFERENCES bandy_conversation(id) ON DELETE CASCADE,
    objective_id VARCHAR(120) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    operational_state JSONB NOT NULL DEFAULT '{}'::JSONB,
    result_references JSONB NOT NULL DEFAULT '[]'::JSONB,
    freshness JSONB NOT NULL DEFAULT '[]'::JSONB,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, objective_id)
);

CREATE INDEX IF NOT EXISTS idx_bandy_task_state_updated
    ON bandy_conversation_task_state (conversation_id, updated_at DESC);
