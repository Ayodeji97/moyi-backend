-- Moderation and quarantine (Phase 4 slice D4, ADR-0051; the owner's rulings
-- Q1, Q10 and Q16).
--
-- Numbered V36, the next after main's V35. The spec's "V28 if a quarantine
-- audit table is needed before Phase 5's audit_log" is this: there is no
-- audit_log yet, and the operator's decisions must be kept from the first one.

-- What moderation found, beside the verdict V32 already had a column for.
--
-- `sensitive`: adult nudity between members is allowed (Q16) and comes back
-- PASSED with this set, so the partner's client blurs it behind "tap to view".
-- Kept whatever the verdict, so that a photo the operator releases from a
-- quarantine is still blurred if it was also nude.
ALTER TABLE media_assets ADD COLUMN sensitive boolean NOT NULL DEFAULT false;
-- When the verdict was given (by moderation; a release by the operator keeps it).
ALTER TABLE media_assets ADD COLUMN moderated_at timestamptz;
-- Child sexual abuse material is evidence (Q16: "reported as the law requires,
-- through the takedown runbook"). Set with a BLOCKED verdict; while it is set,
-- `media.purge` and every best-effort delete leave the asset's objects in the
-- storage, whoever deletes the asset (its owner, an erasure, the reaper: to
-- them it is gone, DELETED, as any deleted asset is). Only the operator's
-- `media_quarantine_delete`, after the report, clears it and hands the objects
-- to `media.purge`.
ALTER TABLE media_assets ADD COLUMN evidence_held boolean NOT NULL DEFAULT false;

ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_verdict_check CHECK (moderation_verdict IN ('PASSED', 'QUARANTINED', 'BLOCKED'));
-- A verdict has a time, and only a verdict has one.
ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_moderated_check CHECK ((moderation_verdict IS NULL) = (moderated_at IS NULL));
-- QUARANTINED is the status of an asset kept from everyone but its owner: a
-- verdict of QUARANTINED (extreme violence, or nothing could moderate it) or
-- BLOCKED (child sexual abuse material). Nothing else is ever QUARANTINED.
ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_evidence_check CHECK (NOT evidence_held OR moderation_verdict = 'BLOCKED');
ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_quarantined_check
        CHECK (status <> 'QUARANTINED' OR moderation_verdict IN ('QUARANTINED', 'BLOCKED'));

-- BLOCKED is final, and what it holds stays held (Q16: "never released through
-- the operator tool"; reported as the law requires). Not only the tool's
-- refusal: whoever writes the row, the application or a person at psql, by
-- update, insert or delete (review of #113, the four psql attacks):
--
-- 1. A verdict that becomes BLOCKED holds the evidence, and the asset is held
--    back (QUARANTINED) unless it is already deleted to its owner, which it may
--    be when a report comes after an erasure: then it stays DELETED, held.
-- 2. A blocked verdict is never taken off, and a blocked asset is never made
--    anything but QUARANTINED or DELETED; once DELETED it is never brought
--    back (no resurrection to QUARANTINED, or to READY by a later statement).
-- 3. The evidence hold is never cleared but by `media_quarantine_delete`, the
--    operator's delete after the report, which says so in a transaction-local
--    setting (`moyi.evidence_release`) that it sets for its own statement and
--    turns off again. A person who sets it by hand at psql can still clear a
--    hold, as one who can drop this trigger can: this stops a mistake or a
--    script, not the database's owner.
-- 4. A held row is never removed (the BEFORE DELETE trigger below).
--
-- A CHECK sees only the new row; these see both.
CREATE FUNCTION media_assets_blocked_is_final() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.moderation_verdict = 'BLOCKED'
       AND (TG_OP = 'INSERT' OR OLD.moderation_verdict IS DISTINCT FROM 'BLOCKED') THEN
        NEW.evidence_held := true;
        IF NEW.status <> 'DELETED' THEN
            NEW.status := 'QUARANTINED';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.moderation_verdict = 'BLOCKED' THEN
        IF NEW.moderation_verdict IS DISTINCT FROM 'BLOCKED'
           OR NEW.status NOT IN ('QUARANTINED', 'DELETED')
           OR (OLD.status = 'DELETED' AND NEW.status <> 'DELETED') THEN
            RAISE EXCEPTION 'media asset % is blocked: its verdict is final, and it is never released', OLD.id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.evidence_held AND NOT NEW.evidence_held
       AND coalesce(current_setting('moyi.evidence_release', true), '') <> 'on' THEN
        RAISE EXCEPTION 'media asset % is held as evidence: only the operator''s delete after the report lets it go', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER media_assets_blocked_is_final
    BEFORE INSERT OR UPDATE ON media_assets
    FOR EACH ROW EXECUTE FUNCTION media_assets_blocked_is_final();

CREATE FUNCTION media_assets_evidence_is_kept() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.evidence_held THEN
        RAISE EXCEPTION 'media asset % is held as evidence: its row is never removed', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER media_assets_evidence_is_kept
    BEFORE DELETE ON media_assets
    FOR EACH ROW EXECUTE FUNCTION media_assets_evidence_is_kept();

-- What was decided about each asset's moderation, by whom: every verdict
-- moderation gives, and every operator's look at the queue and decision on it.
-- The Phase 5 console reads this; until then `scripts/moyi media quarantine`
-- writes it. Never a key, a URL, a byte or a word of an entry: the asset's id,
-- what was done and the operator's note.
--
-- `media_id` has no foreign key, as nothing in this module's tables points at
-- a row that may one day be removed; it is null on a LISTED row, which is a
-- look at the whole queue.
CREATE TABLE media_moderation_log (
    id         uuid        PRIMARY KEY,
    media_id   uuid,
    action     text        NOT NULL,
    -- `moderation:<provider>` for a verdict; the operator's name for the rest.
    actor      text        NOT NULL,
    -- The reason a verdict was given (an ADR-0051 reason, never a vendor's
    -- label), or the operator's note: what they decided and why, a report's
    -- reference after a takedown.
    note       text,
    at         timestamptz NOT NULL,

    CONSTRAINT media_moderation_log_action_check CHECK (
        action IN ('PASSED', 'QUARANTINED', 'BLOCKED', 'LISTED', 'RELEASED', 'RELEASE_REFUSED', 'DELETED')
    ),
    CONSTRAINT media_moderation_log_actor_check CHECK (char_length(actor) BETWEEN 1 AND 100),
    CONSTRAINT media_moderation_log_note_check CHECK (note IS NULL OR char_length(note) <= 500),
    CONSTRAINT media_moderation_log_media_check CHECK ((media_id IS NULL) = (action = 'LISTED'))
);

CREATE INDEX media_moderation_log_media_idx ON media_moderation_log (media_id, at) WHERE media_id IS NOT NULL;

-- The quarantine queue, small by construction: what moderation kept back, and
-- what is held as evidence.
CREATE INDEX media_assets_quarantined_idx ON media_assets (moderated_at) WHERE status = 'QUARANTINED' OR evidence_held;

-- The operator's commands (spec §4 D4: "list|release|delete against the
-- database, audit-logged", and the takedown runbook's two for a member's
-- report: hold and block), as functions, so that the rules are here once and
-- `scripts/moyi media quarantine` only names which to run. Each writes its own
-- audit row in the same transaction as what it does.

-- What is held back, and what is held as evidence (deleted to its owner, its
-- objects kept for the report), oldest verdict first. Ids and categories only;
-- the verdict first, because what an operator may look at depends on it
-- (a BLOCKED one never: ops/moderation/takedown.md §2).
CREATE FUNCTION media_quarantine_list(p_actor text)
    RETURNS TABLE (verdict text, media_id uuid, kind text, status text, reason text, attached boolean, moderated_at timestamptz)
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), NULL, 'LISTED', p_actor, NULL, now());
    RETURN QUERY
        SELECT a.moderation_verdict, a.id, a.kind, a.status, a.moderation_scores ->> 'reason', a.attached_entry_id IS NOT NULL,
               a.moderated_at
        FROM media_assets a
        WHERE a.status = 'QUARANTINED' OR a.evidence_held
        ORDER BY a.moderated_at, a.id;
END;
$$;

-- Lets a quarantined asset reach the partner after all: PASSED, READY. Never a
-- BLOCKED one (the refusal is recorded too, and the trigger above would refuse
-- the change anyway). Returns RELEASED, NEVER_RELEASED (blocked) or
-- NOT_QUARANTINED.
CREATE FUNCTION media_quarantine_release(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    v_verdict text;
BEGIN
    SELECT moderation_verdict INTO v_verdict FROM media_assets WHERE id = p_media AND status = 'QUARANTINED' FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_QUARANTINED';
    END IF;
    IF v_verdict = 'BLOCKED' THEN
        INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
        VALUES (uuidv7(), p_media, 'RELEASE_REFUSED', p_actor, p_note, now());
        RETURN 'NEVER_RELEASED';
    END IF;
    UPDATE media_assets SET status = 'READY', moderation_verdict = 'PASSED' WHERE id = p_media;
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), p_media, 'RELEASED', p_actor, p_note, now());
    RETURN 'RELEASED';
END;
$$;

-- A member's report (ops/moderation/takedown.md §3): holds a READY asset back
-- from the partner, QUARANTINED, for the operator to delete or release. Only a
-- READY one: what is already held back needs no hold, and a deleted one is
-- gone. Its verdict time is kept if it had one. Returns HELD or NOT_READY.
CREATE FUNCTION media_quarantine_hold(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE media_assets
    SET status = 'QUARANTINED', moderation_verdict = 'QUARANTINED', moderated_at = coalesce(moderated_at, now()),
        moderation_scores = coalesce(moderation_scores, '{}'::jsonb) || jsonb_build_object('reason', 'REPORTED')
    WHERE id = p_media AND status = 'READY';
    IF NOT FOUND THEN
        RETURN 'NOT_READY';
    END IF;
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), p_media, 'QUARANTINED', p_actor, p_note, now());
    RETURN 'HELD';
END;
$$;

-- A member's report of child sexual abuse material (takedown.md §3): BLOCKED,
-- held as evidence (the trigger sets the hold and the status), whatever it was:
-- READY, held back, or already deleted to its owner while its objects may
-- still be in the storage (not yet purged). Returns BLOCKED, ALREADY_BLOCKED
-- or GONE (purged: nothing is left to hold).
CREATE FUNCTION media_quarantine_block(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    v_verdict text;
BEGIN
    SELECT moderation_verdict INTO v_verdict FROM media_assets
    WHERE id = p_media AND (status IN ('READY', 'QUARANTINED') OR (status = 'DELETED' AND purged_at IS NULL)) FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'GONE';
    END IF;
    IF v_verdict = 'BLOCKED' THEN
        RETURN 'ALREADY_BLOCKED';
    END IF;
    UPDATE media_assets
    SET moderation_verdict = 'BLOCKED', moderated_at = coalesce(moderated_at, now()),
        moderation_scores = coalesce(moderation_scores, '{}'::jsonb) || jsonb_build_object('reason', 'REPORTED')
    WHERE id = p_media;
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), p_media, 'BLOCKED', p_actor, p_note, now());
    RETURN 'BLOCKED';
END;
$$;

-- Deletes a quarantined asset, attached or not, and hands its objects to
-- `media.purge` as every other deletion does (ADR-0050): a `MediaReleased` is
-- published in this transaction. A BLOCKED asset is deleted this way once the
-- takedown runbook's report is made (its note says so), whether or not it is
-- already deleted to its owner: this is what lets its evidence go, and the one
-- place that may (`moyi.evidence_release`, on for this statement only).
-- Returns DELETED or NOT_QUARANTINED.
--
-- The publication is the outbox's own two inserts (JdbcEventPublisher), at the
-- READ COMMITTED psql runs at: the event, and one delivery for each consumer
-- subscribed to its type. Its aggregate, type and reference are media's
-- (`CompleteUpload.AGGREGATE`, `MediaReleases.MEDIA_RELEASED`,
-- `CompleteUpload.MEDIA_ID`); `MediaQuarantineCommandsTest` runs this and the
-- application's own `media.purge` against it, so a change on either side that
-- breaks the other fails there.
CREATE FUNCTION media_quarantine_delete(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    v_event uuid := uuidv7();
    v_now timestamptz := date_trunc('milliseconds', now());
    v_found boolean;
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed', 'read uncommitted') THEN
        RAISE EXCEPTION 'media_quarantine_delete publishes to the outbox and runs only at READ COMMITTED';
    END IF;
    PERFORM set_config('moyi.evidence_release', 'on', true);
    UPDATE media_assets
    SET status = 'DELETED', deleted_at = coalesce(deleted_at, v_now), attached_entry_id = NULL, evidence_held = false
    WHERE id = p_media AND (status = 'QUARANTINED' OR evidence_held);
    v_found := FOUND;
    PERFORM set_config('moyi.evidence_release', 'off', true);
    IF NOT v_found THEN
        RETURN 'NOT_QUARANTINED';
    END IF;
    INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
    VALUES (v_event, 'Media', p_media, 'MediaReleased', jsonb_build_object('mediaId', p_media), v_now);
    INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at)
    SELECT v_event, consumer_id, v_now FROM outbox_subscriptions WHERE event_type = 'MediaReleased';
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), p_media, 'DELETED', p_actor, p_note, v_now);
    RETURN 'DELETED';
END;
$$;

-- BEGIN backfill (MediaReadyBackfillTest runs this block again)
-- An asset made READY before this migration has no `MediaReady`: nothing
-- published one before D4. Without one it would never be moderated, and so
-- never shown to a partner. One event each, so that the moderation consumers,
-- which subscribe from the beginning, are handed them as they are any other.
-- Production has none (its storage has always been `unavailable`); this makes
-- it correct where some exist. Asked again, it adds nothing.
INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
SELECT uuidv7(), 'Media', a.id, 'MediaReady', jsonb_build_object('mediaId', a.id), coalesce(a.ready_at, a.created_at)
FROM media_assets a
WHERE a.status = 'READY' AND a.moderation_verdict IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM outbox_events e WHERE e.aggregate_id = a.id AND e.event_type = 'MediaReady'
  );
-- END backfill
