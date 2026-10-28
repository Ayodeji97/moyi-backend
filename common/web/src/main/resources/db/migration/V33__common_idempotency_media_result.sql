-- `POST /media/{id}/complete` requires an Idempotency-Key (doc 06:16), so an
-- idempotency record may now name a media asset (Phase 4 slice D1, ADR-0047).
-- V11's CHECK allowed only ENTRY; `ResultKind` gains MEDIA in the same change.
--
-- In common:web's own sequence rather than in media's V32, because the table is
-- common:web's: a statement on it in another module's migration would hide the
-- change from the module that owns the table (ADR-0044 decision 3's reasoning).
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_result_kind_check;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_result_kind_check CHECK (result_kind IS NULL OR result_kind IN ('ENTRY', 'MEDIA'));
