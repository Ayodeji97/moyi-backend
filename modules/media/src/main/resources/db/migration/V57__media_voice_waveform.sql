-- A voice note's waveform (issue #165, ADR-0061): 64 levels, 0 to 255, measured by
-- media.process from the Opus it keeps, one byte per level.
--
-- bytea of exactly 64 bytes, not smallint[]: a byte is exactly the range 0 to 255, so
-- no element can be out of it and no CHECK has to say so; 64 bytes (plus a one-byte
-- header) against a smallint[]'s 128 plus its array header; and nothing to check about
-- an array's dimensions or nulls inside it. The service reads it as unsigned.
--
-- Null for every note processed before this migration (no backfill: ADR-0061), for a
-- note not yet transcoded, and for one whose waveform could not be measured; the client
-- draws its stand-in then. Only a voice note has one.
ALTER TABLE media_assets
    ADD COLUMN waveform bytea,
    ADD CONSTRAINT media_assets_waveform_check CHECK (
        waveform IS NULL OR (kind = 'VOICE' AND octet_length(waveform) = 64)
    );

-- A deleted asset keeps no waveform. The loudness of someone's voice over a minute is
-- theirs, and goes with the note: an erasure, the reaper, the owner's delete, account
-- deletion (ADR-0058 decision 9) and the operator's delete (V36's media_quarantine_delete)
-- each set status = 'DELETED', and this trigger clears the waveform on every one of them,
-- and on any added later, rather than each statement having to remember it. The CHECK
-- beside it is the trigger's backstop: should the trigger ever be dropped, a deletion
-- that leaves a waveform fails instead of keeping it.
CREATE FUNCTION media_assets_waveform_goes_with_deletion() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'DELETED' THEN
        NEW.waveform := NULL;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER media_assets_waveform_goes_with_deletion
    BEFORE INSERT OR UPDATE ON media_assets
    FOR EACH ROW EXECUTE FUNCTION media_assets_waveform_goes_with_deletion();

ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_deleted_waveform_check CHECK (status <> 'DELETED' OR waveform IS NULL);
