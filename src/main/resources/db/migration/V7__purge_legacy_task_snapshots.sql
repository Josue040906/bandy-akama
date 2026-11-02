-- Existing schema-v1 snapshots were written before strict argument allowlisting.
-- They are disposable cache entries; remove them instead of carrying forward
-- potentially sensitive arguments into the sanitized schema-v2 contract.
DELETE FROM bandy_conversation_task_state;
