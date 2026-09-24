-- Password reset shares verification_tokens' hashed-secret lifecycle but has
-- its own purpose and one-hour expiry.
ALTER TABLE verification_tokens DROP CONSTRAINT verification_tokens_purpose_check;
ALTER TABLE verification_tokens
    ADD CONSTRAINT verification_tokens_purpose_check
    CHECK (purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET'));
