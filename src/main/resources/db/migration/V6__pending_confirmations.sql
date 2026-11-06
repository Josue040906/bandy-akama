-- Tâches confirmées : une demande attend le clic « Confirmer » de l'utilisateur.
-- Elle n'est exécutée qu'une fois (PENDING -> EXECUTING -> CONFIRMED ou FAILED).
CREATE TABLE IF NOT EXISTS bandy_pending_confirmation (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES bandy_conversation(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    arguments_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'EXECUTING', 'CONFIRMED', 'FAILED', 'CANCELLED', 'EXPIRED')),
    result_json TEXT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bandy_pending_confirmation_conversation
    ON bandy_pending_confirmation (conversation_id);
