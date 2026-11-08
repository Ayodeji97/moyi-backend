-- `POST /support/messages` takes an optional Idempotency-Key (FR-102,
-- ADR-0057), so an idempotency record may now name a support ticket.
-- `ResultKind` gains SUPPORT_TICKET in the same change, as it gained REPORT with V37.
--
-- In common:web's own sequence, for V33's reason: the table is common:web's.
-- Numbered 45.1, beside V45 (support_tickets), because the one version this
-- change was given is 45 (ADR-0057, Consequences).
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_result_kind_check;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_result_kind_check
        CHECK (result_kind IS NULL OR result_kind IN ('ENTRY', 'MEDIA', 'REPORT', 'SUPPORT_TICKET'));
