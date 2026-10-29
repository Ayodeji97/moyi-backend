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

-- BLOCKED is final (Q16: "never released through the operator tool"). Not only
-- the tool's refusal: no statement, the application's or a person's at psql,
-- can take the verdict off, or make a blocked asset anything but QUARANTINED
-- (held as evidence) or DELETED (by the operator, after the report the
-- takedown runbook requires). A CHECK sees only the new row; this sees both.
CREATE FUNCTION media_assets_blocked_is_final() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.moderation_verdict = 'BLOCKED'
       AND (NEW.moderation_verdict IS DISTINCT FROM 'BLOCKED' OR NEW.status NOT IN ('QUARANTINED', 'DELETED')) THEN
        RAISE EXCEPTION 'media asset % is blocked: its verdict is final, and it is never released', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER media_assets_blocked_is_final
    BEFORE UPDATE ON media_assets
    FOR EACH ROW EXECUTE FUNCTION media_assets_blocked_is_final();

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

-- The operator's three commands (spec §4 D4: "list|release|delete against the
-- database, audit-logged"), as functions, so that the rules are here once and
-- `scripts/moyi media quarantine` only names which to run. Each writes its own
-- audit row in the same transaction as what it does.

-- What is held back, and what is held as evidence (deleted to its owner, its
-- objects kept for the report), oldest verdict first. Ids and categories only.
CREATE FUNCTION media_quarantine_list(p_actor text)
    RETURNS TABLE (media_id uuid, kind text, status text, verdict text, reason text, attached boolean, moderated_at timestamptz)
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), NULL, 'LISTED', p_actor, NULL, now());
    RETURN QUERY
        SELECT a.id, a.kind, a.status, a.moderation_verdict, a.moderation_scores ->> 'reason', a.attached_entry_id IS NOT NULL,
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

-- Deletes a quarantined asset, attached or not, and hands its objects to
-- `media.purge` as every other deletion does (ADR-0050): a `MediaReleased` is
-- published in this transaction. A BLOCKED asset is deleted this way once the
-- takedown runbook's report is made (its note says so), whether or not it is
-- already deleted to its owner: this is what lets its evidence go. Returns
-- DELETED or NOT_QUARANTINED.
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
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed', 'read uncommitted') THEN
        RAISE EXCEPTION 'media_quarantine_delete publishes to the outbox and runs only at READ COMMITTED';
    END IF;
    UPDATE media_assets
    SET status = 'DELETED', deleted_at = coalesce(deleted_at, v_now), attached_entry_id = NULL, evidence_held = false
    WHERE id = p_media AND (status = 'QUARANTINED' OR evidence_held);
    IF NOT FOUND THEN
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
