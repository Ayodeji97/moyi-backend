-- `POST /support/messages` takes an optional Idempotency-Key (FR-102,
-- ADR-0057), so an idempotency record may now name a support ticket.
-- `ResultKind` gains SUPPORT_TICKET in the same change, as it gained REPORT with V37.
--
-- In common:web's own sequence, for V33's reason: the table is common:web's.
-- V47, not beside V45 (support_tickets): the coordinator's ruling of 2026-11-08
-- was no decimal versions, and claimed 47 for this (ADR-0057, Consequences).
-- Every kind on main is listed. A parallel change that adds a kind (#127, a
-- member's report) must list SUPPORT_TICKET too, whichever lands second.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_result_kind_check;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_result_kind_check
        CHECK (result_kind IS NULL OR result_kind IN ('ENTRY', 'MEDIA', 'REPORT', 'SUPPORT_TICKET'));
