-- A member's report holds an entry's photo and voice note back from the
-- partner until a person has decided (FR-100, ADR-0053; the owner's ruling Q16:
-- "a report path to human review and takedown"). V36 gave the operator a hold
-- for this (`media_quarantine_hold`, reason REPORTED); this lets the report
-- route take it, and lets a dismissed report give it back.
--
-- Media is a leaf: it knows a report only as an id, never what was reported,
-- by whom or why. Those are `gratitude`'s (`entry_reports`, V39).

-- Which report holds which asset. An asset stays held while any report holds
-- it: two reports of one entry are two rows, and dismissing one lets nothing
-- go while the other is open. `report_id` has no foreign key: the report is
-- another module's row. Nor has `media_id`, as `media_moderation_log.media_id`
-- has none: nothing removes an asset's row in production, and every function
-- below joins the asset it names, so a hold on a row that is gone holds nothing.
CREATE TABLE media_report_holds (
    media_id  uuid NOT NULL,
    report_id uuid NOT NULL,
    PRIMARY KEY (media_id, report_id)
);

CREATE INDEX media_report_holds_report_idx ON media_report_holds (report_id);

-- V36's hold, now remembering what it held the asset back from, so that a
-- report found groundless can put the asset back exactly as it was: its
-- verdict, the verdict's time and what moderation found (`heldFrom`). Held
-- from no verdict at all (moderation had not reached it, or none is
-- configured) is `"verdict": null`: given back, it is handed to moderation
-- again rather than passed unseen. Otherwise V36's function, unchanged.
CREATE OR REPLACE FUNCTION media_quarantine_hold(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE media_assets
    SET status = 'QUARANTINED', moderation_verdict = 'QUARANTINED', moderated_at = coalesce(moderated_at, now()),
        moderation_scores = coalesce(moderation_scores, '{}'::jsonb) || jsonb_build_object(
            'reason', 'REPORTED',
            'heldFrom', jsonb_build_object(
                'verdict', moderation_verdict, 'moderatedAt', moderated_at, 'scores', moderation_scores))
    WHERE id = p_media AND status = 'READY';
    IF NOT FOUND THEN
        RETURN 'NOT_READY';
    END IF;
    INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
    VALUES (uuidv7(), p_media, 'QUARANTINED', p_actor, p_note, now());
    RETURN 'HELD';
END;
$$;

-- The report route's hold (`MediaReports.holdFor`): every photo and voice note
-- attached to the entry, as `media` records it, held for the report. A READY
-- one is held back (V36's hold, reason REPORTED, logged under `p_actor`); one
-- already held back (by moderation, by the operator or by another report) or
-- blocked is only recorded as held by this report too, so that nothing a
-- later decision on another report or verdict does lets it go while this one
-- is open. A deleted one is gone and is not held. Each asset's row is locked
-- first, so that this and `media_report_release` take them one at a time.
-- Returns how many this report holds.
CREATE FUNCTION media_report_hold(p_entry uuid, p_report uuid, p_actor text) RETURNS integer
    LANGUAGE plpgsql AS $$
DECLARE
    r record;
    v_held integer := 0;
BEGIN
    FOR r IN
        SELECT id, status FROM media_assets
        WHERE attached_entry_id = p_entry AND status IN ('READY', 'QUARANTINED')
        ORDER BY id
        FOR UPDATE
    LOOP
        IF r.status = 'READY' THEN
            PERFORM media_quarantine_hold(r.id, p_actor, 'report ' || p_report);
        END IF;
        INSERT INTO media_report_holds (media_id, report_id) VALUES (r.id, p_report) ON CONFLICT DO NOTHING;
        v_held := v_held + 1;
    END LOOP;
    RETURN v_held;
END;
$$;

-- What a report holds, for the operator's `moyi report show`.
CREATE FUNCTION media_report_holding(p_report uuid)
    RETURNS TABLE (media_id uuid, kind text, status text, verdict text)
    LANGUAGE sql STABLE AS $$
    SELECT a.id, a.kind, a.status, a.moderation_verdict
    FROM media_report_holds h JOIN media_assets a ON a.id = h.media_id
    WHERE h.report_id = p_report
    ORDER BY a.kind, a.id;
$$;

-- A report dismissed (`entry_report_dismiss`, V39): this report holds nothing
-- any more, and each asset it held that **nothing else holds** is given back
-- as it was before the report: PASSED and READY, shown to the partner again;
-- or, if it had no verdict, READY with none and handed to moderation again (a
-- `MediaReady`, the outbox's two inserts as `media_quarantine_delete` makes
-- them). Only an asset the report's hold held back (reason REPORTED, with its
-- `heldFrom`): one moderation quarantined or blocked stays as it is, and so
-- does one the operator released or deleted meanwhile. Returns how many it
-- gave back.
CREATE FUNCTION media_report_release(p_report uuid, p_actor text, p_note text) RETURNS integer
    LANGUAGE plpgsql AS $$
DECLARE
    r record;
    v_from jsonb;
    v_event uuid;
    v_now timestamptz := date_trunc('milliseconds', now());
    v_released integer := 0;
BEGIN
    IF current_setting('transaction_isolation') NOT IN ('read committed', 'read uncommitted') THEN
        RAISE EXCEPTION 'media_report_release publishes to the outbox and runs only at READ COMMITTED';
    END IF;
    FOR r IN
        SELECT a.id, a.status, a.moderation_verdict, a.moderation_scores
        FROM media_assets a JOIN media_report_holds h ON h.media_id = a.id
        WHERE h.report_id = p_report
        ORDER BY a.id
        FOR UPDATE OF a
    LOOP
        DELETE FROM media_report_holds WHERE media_id = r.id AND report_id = p_report;
        -- Another open report still holds it: a statement of its own, so it sees a hold committed while this waited.
        CONTINUE WHEN EXISTS (SELECT 1 FROM media_report_holds WHERE media_id = r.id);
        CONTINUE WHEN r.status <> 'QUARANTINED' OR r.moderation_verdict IS DISTINCT FROM 'QUARANTINED'
            OR r.moderation_scores ->> 'reason' IS DISTINCT FROM 'REPORTED' OR NOT (r.moderation_scores ? 'heldFrom');
        v_from := r.moderation_scores -> 'heldFrom';
        IF v_from ->> 'verdict' = 'PASSED' THEN
            UPDATE media_assets
            SET status = 'READY', moderation_verdict = 'PASSED', moderated_at = (v_from ->> 'moderatedAt')::timestamptz,
                moderation_scores = nullif(v_from -> 'scores', 'null'::jsonb)
            WHERE id = r.id;
        ELSIF v_from ->> 'verdict' IS NULL THEN
            UPDATE media_assets
            SET status = 'READY', moderation_verdict = NULL, moderated_at = NULL,
                moderation_scores = nullif(v_from -> 'scores', 'null'::jsonb)
            WHERE id = r.id;
            v_event := uuidv7();
            INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
            VALUES (v_event, 'Media', r.id, 'MediaReady', jsonb_build_object('mediaId', r.id), v_now);
            INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at)
            SELECT v_event, consumer_id, v_now FROM outbox_subscriptions WHERE event_type = 'MediaReady';
        ELSE
            -- A hold takes only a READY asset, whose verdict is PASSED or none: nothing else is given back.
            CONTINUE;
        END IF;
        INSERT INTO media_moderation_log (id, media_id, action, actor, note, at)
        VALUES (uuidv7(), r.id, 'RELEASED', p_actor, p_note, v_now);
        v_released := v_released + 1;
    END LOOP;
    RETURN v_released;
END;
$$;

-- A report upheld (`entry_report_uphold`, V39): each asset the report holds is
-- taken down by V36's own functions, which log themselves.
--   DELETE: deleted, its objects purged (`media_quarantine_delete`). A READY
--     one (released meanwhile) is held first, so the delete takes it. A
--     BLOCKED one is left exactly as it is (`BLOCKED_KEPT`): its evidence goes
--     only by the takedown runbook's §2, after the report to the authority.
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
        IF p_action = 'BLOCK' THEN
            v_outcome := media_quarantine_block(r.id, p_actor, p_note);
        ELSIF r.moderation_verdict = 'BLOCKED' THEN
            v_outcome := 'BLOCKED_KEPT';
        ELSIF r.status = 'DELETED' THEN
            v_outcome := 'GONE';
        ELSE
            IF r.status = 'READY' THEN
                PERFORM media_quarantine_hold(r.id, p_actor, p_note);
            END IF;
            v_outcome := media_quarantine_delete(r.id, p_actor, p_note);
        END IF;
        DELETE FROM media_report_holds h WHERE h.media_id = r.id AND h.report_id = p_report;
        media_id := r.id;
        outcome := v_outcome;
        RETURN NEXT;
    END LOOP;
END;
$$;
