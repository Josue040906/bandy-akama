ALTER TABLE bandy_conversation
    ADD COLUMN IF NOT EXISTS active_claim_id UUID;

ALTER TABLE bandy_conversation_request
    ADD COLUMN IF NOT EXISTS active_claim_id UUID;

ALTER TABLE bandy_conversation_request
    ADD COLUMN IF NOT EXISTS request_hash CHAR(64);
