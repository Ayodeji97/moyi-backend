-- A member's report holds an entry's photo and voice note back from the
-- partner, and keeps their objects, until a person has decided (FR-100,
-- ADR-0053; the owner's ruling Q16: "a report path to human review and
-- takedown"). V36 gave the operator a hold for this (`media_quarantine_hold`,
-- reason REPORTED); this lets the report route take it, lets a dismissed
-- report give it back exactly, and keeps what a report holds from being
-- purged before anyone has looked (review of #118, P1).
--
-- Media is a leaf: it knows a report only as an id, never what was reported,
-- by whom or why. Those are `gratitude`'s (`entry_reports`, V39).

-- Which report holds which asset. An asset stays held while any report holds
-- it: two reports of one entry are two rows, and dismissing one lets nothing
-- go while the other is open. A row exists only while its report is open:
-- dismissing or upholding the report removes it. While one exists the asset's
-- objects are kept, whoever deletes the asset (its author's erasure, the
-- owner's DELETE, the reaper): `media.purge` and every best-effort delete
-- spare it, as they spare `evidence_held`, and the decision hands the objects
-- back to `media.purge` (ADR-0053 decision 8: a legal hold, bounded by the
-- review alarms).
--
-- `report_id` has no foreign key: the report is another module's row. Nor has
-- `media_id`, as `media_moderation_log.media_id` has none: nothing removes an
-- asset's row in production, every function below joins the asset it names,
-- and every test that empties `media_assets` would have to name this table.
CREATE TABLE media_report_holds (
    media_id  uuid NOT NULL,
    report_id uuid NOT NULL,
    PRIMARY KEY (media_id, report_id)
);

CREATE INDEX media_report_holds_report_idx ON media_report_holds (report_id);

-- The entry an asset was last attached to. `attached_entry_id` is cleared when
-- an entry lets an asset go (an erasure, a withdrawal, an edit that replaces
-- it), and a report of that entry must still find what it showed (review of
-- #118, P1: a report after a withdrawal held nothing, and the operator could
-- not find the photo). Set by a trigger whenever an asset is attached, so no
-- statement that attaches can forget it, and left alone when it is detached:
-- never cleared. Assets attached before this migration are filled in here.
ALTER TABLE media_assets ADD COLUMN last_entry_id uuid;
UPDATE media_assets SET last_entry_id = attached_entry_id WHERE attached_entry_id IS NOT NULL;

CREATE FUNCTION media_assets_remember_entry() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.attached_entry_id IS NOT NULL THEN
        NEW.last_entry_id := NEW.attached_entry_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER media_assets_remember_entry
    BEFORE INSERT OR UPDATE OF attached_entry_id ON media_assets
    FOR EACH ROW EXECUTE FUNCTION media_assets_remember_entry();

CREATE INDEX media_assets_last_entry_idx ON media_assets (last_entry_id) WHERE last_entry_id IS NOT NULL;

-- One event for one asset, by the outbox's own two inserts, at the READ
-- COMMITTED psql runs at, as `media_quarantine_delete` makes them (V36): the
-- event, and one delivery for each consumer subscribed to its type.
CREATE FUNCTION media_publish(p_media uuid, p_type text) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_event uuid := uuidv7();
    v_now timestamptz := date_trunc('milliseconds', now());
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed', 'read uncommitted') THEN
        RAISE EXCEPTION 'media_publish writes to the outbox and runs only at READ COMMITTED';
    END IF;
    INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
    VALUES (v_event, 'Media', p_media, p_type, jsonb_build_object('mediaId', p_media), v_now);
    INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at)
    SELECT v_event, consumer_id, v_now FROM outbox_subscriptions WHERE event_type = p_type;
END;
$$;

-- The hold itself, once: a READY asset held back (QUARANTINED, reason
-- REPORTED), remembering what it was held from, so that it can be given back
-- exactly: its verdict, the verdict's time, what moderation found and **which
-- report took it** (`heldFrom`; null for the operator's own hold, which no
-- dismissal ever gives back: review of #118, P2-a). Held from no verdict at all
-- is `"verdict": null`: given back, it goes to moderation again, never passed
-- unseen.
CREATE FUNCTION media_hold_back(p_media uuid, p_actor text, p_note text, p_report uuid) RETURNS text
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE media_assets
    SET status = 'QUARANTINED', moderation_verdict = 'QUARANTINED', moderated_at = coalesce(moderated_at, now()),
        moderation_scores = coalesce(moderation_scores, '{}'::jsonb) || jsonb_build_object(
            'reason', 'REPORTED',
            'heldFrom', jsonb_build_object(
                'verdict', moderation_verdict, 'moderatedAt', moderated_at, 'scores', moderation_scores,
                'report', p_report))
    WHERE id = p_media AND status = 'READY';
    IF NOT FOUND THEN
        RETURN 'NOT_READY';
    END IF;
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), p_media, 'QUARANTINED', p_actor, p_note, now());
    RETURN 'HELD';
END;
$$;

-- V36's operator hold, now the shared one above with no report.
CREATE OR REPLACE FUNCTION media_quarantine_hold(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
BEGIN
    RETURN media_hold_back(p_media, p_actor, p_note, NULL);
END;
$$;

-- Gives a held-back asset back as it was before the hold (the caller has the
-- row locked and has decided it may): PASSED and READY with its verdict's
-- time, or, held from no verdict, READY with none and a new `MediaReady`, so
-- moderation looks at it. Returns RELEASED or REMODERATED; NOT_HELD_BACK for
-- anything a hold did not hold back.
CREATE FUNCTION media_give_back(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    v_scores jsonb;
    v_from jsonb;
BEGIN
    SELECT moderation_scores INTO v_scores FROM media_assets
    WHERE id = p_media AND status = 'QUARANTINED' AND moderation_verdict = 'QUARANTINED';
    IF NOT FOUND OR v_scores ->> 'reason' IS DISTINCT FROM 'REPORTED' OR NOT (v_scores ? 'heldFrom') THEN
        RETURN 'NOT_HELD_BACK';
    END IF;
    v_from := v_scores -> 'heldFrom';
    IF v_from ->> 'verdict' = 'PASSED' THEN
        UPDATE media_assets
        SET status = 'READY', moderation_verdict = 'PASSED', moderated_at = (v_from ->> 'moderatedAt')::timestamptz,
            moderation_scores = nullif(v_from -> 'scores', 'null'::jsonb)
        WHERE id = p_media;
        INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
        VALUES (uuidv7(), p_media, 'RELEASED', p_actor, p_note, now());
        RETURN 'RELEASED';
    ELSIF v_from ->> 'verdict' IS NULL THEN
        UPDATE media_assets
        SET status = 'READY', moderation_verdict = NULL, moderated_at = NULL,
            moderation_scores = nullif(v_from -> 'scores', 'null'::jsonb)
        WHERE id = p_media;
        PERFORM media_publish(p_media, 'MediaReady');
        INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
        VALUES (uuidv7(), p_media, 'RELEASED', p_actor, left('to moderation: ' || coalesce(p_note, ''), 500), now());
        RETURN 'REMODERATED';
    END IF;
    -- A hold takes only a READY asset, whose verdict is PASSED or none.
    RETURN 'NOT_HELD_BACK';
END;
$$;

-- V36's release, with two refusals it lacked (review of #118, P2-b):
-- - **Never while a report holds it.** Refused, and the refusal recorded
--   (RELEASE_REFUSED): the report is decided with `moyi reports dismiss`,
--   which gives it back once nothing else holds it. Returns REPORT_HELD.
-- - **Never PASSED unseen.** An asset an operator held before moderation
--   reached it goes back to moderation (REMODERATED), as a dismissal sends it.
-- Otherwise V36's: the operator's look is the verdict, PASSED and READY.
CREATE OR REPLACE FUNCTION media_quarantine_release(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    v_verdict text;
    v_scores jsonb;
BEGIN
    SELECT moderation_verdict, moderation_scores INTO v_verdict, v_scores FROM media_assets
    WHERE id = p_media AND status = 'QUARANTINED' FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_QUARANTINED';
    END IF;
    IF v_verdict = 'BLOCKED' THEN
        INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
        VALUES (uuidv7(), p_media, 'RELEASE_REFUSED', p_actor, p_note, now());
        RETURN 'NEVER_RELEASED';
    END IF;
    IF EXISTS (SELECT 1 FROM media_report_holds WHERE media_id = p_media) THEN
        INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
        VALUES (uuidv7(), p_media, 'RELEASE_REFUSED', p_actor, p_note, now());
        RETURN 'REPORT_HELD';
    END IF;
    IF v_scores ? 'heldFrom' AND v_scores -> 'heldFrom' ->> 'verdict' IS NULL THEN
        RETURN media_give_back(p_media, p_actor, p_note);
    END IF;
    UPDATE media_assets SET status = 'READY', moderation_verdict = 'PASSED' WHERE id = p_media;
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), p_media, 'RELEASED', p_actor, p_note, now());
    RETURN 'RELEASED';
END;
$$;

-- V36's queue, with an eighth column: how many open reports hold the asset.
-- Such a row is decided in the reports queue (runbook §1, §3), not here.
DROP FUNCTION media_quarantine_list(text);
CREATE FUNCTION media_quarantine_list(p_actor text)
    RETURNS TABLE (verdict text, media_id uuid, kind text, status text, reason text, attached boolean,
                   moderated_at timestamptz, reports integer)
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), NULL, 'LISTED', p_actor, NULL, now());
    RETURN QUERY
        SELECT a.moderation_verdict, a.id, a.kind, a.status, a.moderation_scores ->> 'reason', a.attached_entry_id IS NOT NULL,
               a.moderated_at, (SELECT count(*)::integer FROM media_report_holds h WHERE h.media_id = a.id)
        FROM media_assets a
        WHERE a.status = 'QUARANTINED' OR a.evidence_held
        ORDER BY a.moderated_at, a.id;
END;
$$;

-- The report route's hold (`MediaReports.holdFor`): every photo and voice note
-- the entry has, or had (`last_entry_id`: let go by an erasure, a withdrawal
-- or an edit), held for the report.
-- - READY: held back from the partner (the hold above, recording the report).
-- - Already held back (by moderation, the operator or another report), or
--   blocked: recorded as held by this report too.
-- - DELETED with its objects not yet purged: recorded as held, so the objects
--   stay until the report is decided (P1).
-- - Purged: nothing is left to hold.
-- Each asset's row is locked first, so that this and the release take them
-- one at a time. Returns how many this report holds.
CREATE FUNCTION media_report_hold(p_entry uuid, p_report uuid, p_actor text) RETURNS integer
    LANGUAGE plpgsql AS $$
DECLARE
    r record;
    v_held integer := 0;
BEGIN
    FOR r IN
        SELECT id, status FROM media_assets
        WHERE coalesce(attached_entry_id, last_entry_id) = p_entry
          AND (status IN ('READY', 'QUARANTINED') OR (status = 'DELETED' AND purged_at IS NULL))
        ORDER BY id
        FOR UPDATE
    LOOP
        IF r.status = 'READY' THEN
            PERFORM media_hold_back(r.id, p_actor, 'report ' || p_report, p_report);
        END IF;
        INSERT INTO media_report_holds (media_id, report_id) VALUES (r.id, p_report) ON CONFLICT DO NOTHING;
        v_held := v_held + 1;
    END LOOP;
    RETURN v_held;
END;
$$;

-- What a report holds, for the operator's `moyi reports show`.
CREATE FUNCTION media_report_holding(p_report uuid)
    RETURNS TABLE (media_id uuid, kind text, status text, verdict text)
    LANGUAGE sql STABLE AS $$
    SELECT a.id, a.kind, a.status, a.moderation_verdict
    FROM media_report_holds h JOIN media_assets a ON a.id = h.media_id
    WHERE h.report_id = p_report
    ORDER BY a.kind, a.id;
$$;

-- What becomes of an asset once one report no longer holds it (the caller
-- has the row locked and has removed that report's row). If another open
-- report still holds it, nothing (STILL_HELD). Otherwise:
-- - deleted meanwhile (erased, withdrawn, reaped, its owner's DELETE) and not
--   evidence: its objects are handed to `media.purge` now (PURGED: a
--   `MediaReleased`), the purge the hold had kept waiting;
-- - held back by a report's hold: given back (RELEASED, REMODERATED);
-- - anything else, as moderation or the operator left it (UNCHANGED): an
--   operator's own hold is never given back by a report.
CREATE FUNCTION media_report_let_go(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    a record;
BEGIN
    -- A statement of its own, so it sees a hold committed while the caller waited for the row.
    IF EXISTS (SELECT 1 FROM media_report_holds WHERE media_id = p_media) THEN
        RETURN 'STILL_HELD';
    END IF;
    SELECT status, evidence_held, purged_at, moderation_scores INTO a FROM media_assets WHERE id = p_media;
    IF a.status = 'DELETED' THEN
        IF a.evidence_held OR a.purged_at IS NOT NULL THEN
            RETURN 'UNCHANGED';
        END IF;
        PERFORM media_publish(p_media, 'MediaReleased');
        INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
        VALUES (uuidv7(), p_media, 'DELETED', p_actor, left('deleted while held; objects to media.purge: ' || coalesce(p_note, ''), 500), now());
        RETURN 'PURGED';
    END IF;
    IF a.moderation_scores -> 'heldFrom' ->> 'report' IS NULL THEN
        RETURN 'UNCHANGED';
    END IF;
    RETURN media_give_back(p_media, p_actor, p_note);
END;
$$;

-- A report dismissed (`entry_report_dismiss`, V39): this report holds nothing
-- any more, and each asset it held is let go (above). Returns how many were
-- given back or handed to the purge.
CREATE FUNCTION media_report_release(p_report uuid, p_actor text, p_note text) RETURNS integer
    LANGUAGE plpgsql AS $$
DECLARE
    r record;
    v_released integer := 0;
BEGIN
    FOR r IN
        SELECT a.id
        FROM media_assets a JOIN media_report_holds h ON h.media_id = a.id
        WHERE h.report_id = p_report
        ORDER BY a.id
        FOR UPDATE OF a
    LOOP
        DELETE FROM media_report_holds WHERE media_id = r.id AND report_id = p_report;
        IF media_report_let_go(r.id, p_actor, p_note) IN ('RELEASED', 'REMODERATED', 'PURGED') THEN
            v_released := v_released + 1;
        END IF;
    END LOOP;
    RETURN v_released;
END;
$$;

-- A report upheld (`entry_report_uphold`, V39): each asset the report holds is
-- taken down by V36's own functions, which log themselves.
--   DELETE: deleted, its objects purged (`media_quarantine_delete`). A READY
--     one is held first, so the delete takes it. One already deleted (by its
--     author, while held) is let go, so the purge the hold kept waiting runs
--     (DELETED). A BLOCKED one is left exactly as it is (BLOCKED_KEPT): its
--     evidence goes only by the takedown runbook's §2, after the report to the
--     authority. While another open report holds it, the objects wait for that
--     one too.
--   BLOCK: blocked and held as evidence (`media_quarantine_block`): READY,
--     held back, or deleted with its objects not yet purged.
-- The report then holds nothing. Returns each asset with what became of it.
CREATE FUNCTION media_report_takedown(p_report uuid, p_action text, p_actor text, p_note text)
    RETURNS TABLE (media_id uuid, outcome text)
    LANGUAGE plpgsql AS $$
DECLARE
    r record;
    v_outcome text;
BEGIN
    IF p_action NOT IN ('DELETE', 'BLOCK') THEN
        RAISE EXCEPTION 'a takedown is DELETE or BLOCK, not %', p_action;
    END IF;
    FOR r IN
        SELECT a.id, a.status, a.moderation_verdict
        FROM media_assets a JOIN media_report_holds h ON h.media_id = a.id
        WHERE h.report_id = p_report
        ORDER BY a.id
        FOR UPDATE OF a
    LOOP
        DELETE FROM media_report_holds h WHERE h.media_id = r.id AND h.report_id = p_report;
        IF p_action = 'BLOCK' THEN
            v_outcome := media_quarantine_block(r.id, p_actor, p_note);
        ELSIF r.moderation_verdict = 'BLOCKED' THEN
            v_outcome := 'BLOCKED_KEPT';
        ELSIF r.status = 'DELETED' THEN
            PERFORM media_report_let_go(r.id, p_actor, p_note);
            v_outcome := 'DELETED';
        ELSE
            IF r.status = 'READY' THEN
                PERFORM media_hold_back(r.id, p_actor, p_note, p_report);
            END IF;
            v_outcome := media_quarantine_delete(r.id, p_actor, p_note);
        END IF;
        media_id := r.id;
        outcome := v_outcome;
        RETURN NEXT;
    END LOOP;
END;
$$;
