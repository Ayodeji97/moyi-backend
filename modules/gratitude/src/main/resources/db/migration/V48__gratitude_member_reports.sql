-- FR-100's member half (ADR-0053, amended for #127): a member reports the
-- other member of their bond to a person. Doc 07's `reports` table had a
-- `target_type`; V39 built it for the one target that existed, an entry. This
-- gives it the second, in the same table, so that both are one queue, one log
-- (`entry_report_log`), one priority order, one review alarm
-- (`ops/monitoring/media-check.sh` counts every open row) and the same
-- operator's commands (`scripts/moyi reports`). The name stays `entry_reports`:
-- renaming it would mean recreating every function that reads it, for a word.
--
-- `target` is ENTRY or MEMBER. An entry report names its entry and no member;
-- a member report names the reported member (their `bond_members` id in
-- `bond_id`, as `reporter_member_id` is the reporter's: never a user id) and
-- no entry. Existing rows are all entry reports.
--
-- **A member report holds nothing.** `media_report_holds` is keyed by the
-- report and filled only by the entry route (V38, ADR-0053 decision 4); a
-- member report has no media, so `media_report_holding` answers nothing for it,
-- a dismissal gives nothing back and an upholding takes nothing down, as for a
-- report of words alone (decision 8).
ALTER TABLE entry_reports ADD COLUMN target text NOT NULL DEFAULT 'ENTRY';
ALTER TABLE entry_reports ALTER COLUMN target DROP DEFAULT;
ALTER TABLE entry_reports ADD COLUMN reported_member_id uuid;
ALTER TABLE entry_reports ALTER COLUMN entry_id DROP NOT NULL;
ALTER TABLE entry_reports ADD CONSTRAINT entry_reports_target_check CHECK (
    (target = 'ENTRY' AND entry_id IS NOT NULL AND reported_member_id IS NULL) OR
    (target = 'MEMBER' AND entry_id IS NULL AND reported_member_id IS NOT NULL)
);

-- The two reads gain the target and the member; their result types change, so
-- they are dropped and made again. Everything else they do is V39's, unchanged.
DROP FUNCTION entry_report_list(text);
DROP FUNCTION entry_report_read(uuid, text);

CREATE FUNCTION entry_report_list(p_actor text)
    RETURNS TABLE (priority boolean, report_id uuid, reason text, target text, entry_id uuid, reported_member_id uuid,
                   held integer, detail boolean, received_at timestamptz)
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO entry_report_log (id, report_id, action, actor, note, at)
    VALUES (uuidv7(), NULL, 'LISTED', p_actor, NULL, now());
    RETURN QUERY
        SELECT r.reason = 'MINOR_SEXUAL_CONTENT', r.id, r.reason, r.target, r.entry_id, r.reported_member_id,
               (SELECT count(*)::integer FROM media_report_holding(r.id)), r.detail IS NOT NULL, r.created_at
        FROM entry_reports r
        WHERE r.status = 'OPEN'
        ORDER BY r.reason = 'MINOR_SEXUAL_CONTENT' DESC, r.created_at, r.id;
END;
$$;

CREATE FUNCTION entry_report_read(p_report uuid, p_actor text)
    RETURNS TABLE (report_id uuid, reason text, status text, target text, entry_id uuid, reported_member_id uuid,
                   bond_id uuid, received_at timestamptz, detail text, media_id uuid, media_kind text, media_status text,
                   media_verdict text)
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM entry_reports r WHERE r.id = p_report) THEN
        RETURN;
    END IF;
    INSERT INTO entry_report_log (id, report_id, action, actor, note, at)
    VALUES (uuidv7(), p_report, 'READ', p_actor, NULL, now());
    RETURN QUERY
        SELECT r.id, r.reason, r.status, r.target, r.entry_id, r.reported_member_id, r.bond_id, r.created_at, r.detail,
               m.media_id, m.kind, m.status, m.verdict
        FROM entry_reports r LEFT JOIN LATERAL media_report_holding(r.id) m ON true
        WHERE r.id = p_report;
END;
$$;
