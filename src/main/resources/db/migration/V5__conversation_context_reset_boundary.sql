ALTER TABLE bandy_conversation_message
    ADD COLUMN IF NOT EXISTS context_reset BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_bandy_message_context_reset
    ON bandy_conversation_message (
        conversation_id, context_reset, sequence_number
    );
