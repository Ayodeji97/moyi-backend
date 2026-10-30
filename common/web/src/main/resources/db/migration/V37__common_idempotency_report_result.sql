-- `POST /entries/{entryId}/report` requires an Idempotency-Key (doc 06:16), so an
-- idempotency record may now name a member's report (FR-100, ADR-0053).
-- `ResultKind` gains REPORT in the same change, as it gained MEDIA with V33.
--
-- In common:web's own sequence, for V33's reason: the table is common:web's.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_result_kind_check;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_result_kind_check
        CHECK (result_kind IS NULL OR result_kind IN ('ENTRY', 'MEDIA', 'REPORT'));
