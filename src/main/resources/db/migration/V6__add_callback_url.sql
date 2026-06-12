-- Day 13: Webhook callbacks
-- Store the caller's callback URL on each notification log row so the
-- dispatch service can POST the final delivery result back to the caller.
ALTER TABLE notification_logs
    ADD COLUMN callback_url VARCHAR(2048) NULL AFTER failure_reason;
