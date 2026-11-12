-- A deleted asset is blocked as evidence only while a member's report holds it
-- (ADR-0063, Owed; A2 spec §9.3).
--
-- V36's `media_quarantine_block` blocked any DELETED asset whose objects were
-- not yet purged. After a removal (the console's or `moyi media quarantine
-- delete`), that window is the seconds until `media.purge` runs, and a removal
-- may already be listed in its author's data export: a block after it would
-- tell them what was found. The console refused this in its port; the script
-- did not (the runbook forbade it by hand, takedown.md §1 step 3).
--
-- What still needs to block a deleted asset is a report: its author erased the
-- entry while the report held it, and the report is upheld as child sexual
-- abuse material (takedown.md §3, "even if its author deleted it meanwhile").
-- So the rule is the report's hold, not who deleted it. `media_report_takedown`
-- removed its own hold before it blocked; it now blocks first and then removes
-- the hold, so that the block sees the report.
--
-- Answers are unchanged: BLOCKED, ALREADY_BLOCKED, GONE (nothing left to hold,
-- or deleted and no report holds it).

CREATE OR REPLACE FUNCTION media_quarantine_block(p_media uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    v_verdict text;
    v_status text;
BEGIN
    SELECT moderation_verdict, status INTO v_verdict, v_status FROM media_assets
    WHERE id = p_media AND (status IN ('READY', 'QUARANTINED') OR (status = 'DELETED' AND purged_at IS NULL)) FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'GONE';
    END IF;
    IF v_verdict = 'BLOCKED' THEN
        RETURN 'ALREADY_BLOCKED';
    END IF;
    -- A statement of its own, after the row lock, so it sees a hold committed while this waited.
    IF v_status = 'DELETED' AND NOT EXISTS (SELECT 1 FROM media_report_holds WHERE media_id = p_media) THEN
        RETURN 'GONE';
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

-- As V38's, except that a BLOCK is taken while this report's hold is still
-- there (above), and the hold is removed after it.
CREATE OR REPLACE FUNCTION media_report_takedown(p_report uuid, p_action text, p_actor text, p_note text)
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
            DELETE FROM media_report_holds h WHERE h.media_id = r.id AND h.report_id = p_report;
        ELSE
            DELETE FROM media_report_holds h WHERE h.media_id = r.id AND h.report_id = p_report;
            IF r.moderation_verdict = 'BLOCKED' THEN
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
        END IF;
        media_id := r.id;
        outcome := v_outcome;
        RETURN NEXT;
    END LOOP;
END;
$$;
