-- FR-100: a member reports their partner's entry, its photo and voice note
-- with it, to a person (ADR-0053; the owner's ruling Q16: "a report path to
-- human review and takedown"). Doc 07 sketches `reports(id, reporter_user_id,
-- target_type, target_id, reason, detail, status, resolved_by, resolved_at,
-- resolution)`; this is that table for the one target built, an entry.
--
-- The report row is the report. What it holds back is `media`'s
-- (`media_report_holds`, V38), keyed by this row's id; who decided what, and
-- when, is `entry_report_log` below.

-- `entry_id` and `bond_id` carry no foreign key. `bond_id` is another
-- module's id, as everywhere here. `entry_id` is this module's, and still has
-- none, on purpose: a report is the record of what a member said happened,
-- and it outlives the entry's row and its bond-day if those are ever removed
-- (today they are not: an erasure keeps a tombstone). The service inserts
-- only for an entry the reporter was shown.
--
-- `reporter_member_id` is the reporter's bond_members id in the entry's bond,
-- as `reactions.member_id` is: never a user id, and never told to the author
-- (ADR-0053 decision 5).
--
-- `detail` is the reporter's own words to the person who reviews: optional,
-- at most 500 characters (code points; `ReportDetail` is the rule, this is
-- its second line). Never a word of the entry: the entry is named by its id.
-- It is never logged, and it is emptied when the report is decided (decision
-- 7): what was decided, and why, is the operator's note in the log.
--
-- `reason` is one of five (decision 3). MINOR_SEXUAL_CONTENT is reviewed
-- first and never viewed (`entry_report_list` marks it), and is never blocked
-- by the route: a person decides.
CREATE TABLE entry_reports (
    id                 uuid        PRIMARY KEY,
    entry_id           uuid        NOT NULL,
    bond_id            uuid        NOT NULL,
    reporter_member_id uuid        NOT NULL,
    reason             text        NOT NULL,
    detail             text,
    status             text        NOT NULL,
    created_at         timestamptz NOT NULL,
    resolved_at        timestamptz,

    CONSTRAINT entry_reports_reason_check CHECK (
        reason IN ('NON_CONSENSUAL', 'MINOR_SEXUAL_CONTENT', 'VIOLENCE_OR_SELF_HARM', 'HARASSMENT', 'OTHER')
    ),
    CONSTRAINT entry_reports_detail_check CHECK (detail IS NULL OR char_length(detail) BETWEEN 1 AND 500),
    CONSTRAINT entry_reports_status_check CHECK (status IN ('OPEN', 'DISMISSED', 'UPHELD')),
    -- Decided, or not: a time exactly when it is, and no detail kept after it.
    CONSTRAINT entry_reports_resolved_check CHECK ((status = 'OPEN') = (resolved_at IS NULL)),
    CONSTRAINT entry_reports_detail_kept_check CHECK (status = 'OPEN' OR detail IS NULL)
);

-- The queue: what is open, oldest first.
CREATE INDEX entry_reports_open_idx ON entry_reports (created_at) WHERE status = 'OPEN';
-- A reporter's replay reads their own report back by id; the entry's reports, for the operator.
CREATE INDEX entry_reports_entry_idx ON entry_reports (entry_id);

-- Who looked at the queue or a report, and what was decided, by whom: the
-- operator's name (`MOYI_OPERATOR`) and note. Never the detail, never an
-- entry's words, never a key or a URL. `report_id` is null on a LISTED row,
-- a look at the whole queue, as `media_moderation_log.media_id` is.
CREATE TABLE entry_report_log (
    id        uuid        PRIMARY KEY,
    report_id uuid        REFERENCES entry_reports (id),
    action    text        NOT NULL,
    actor     text        NOT NULL,
    note      text,
    at        timestamptz NOT NULL,

    CONSTRAINT entry_report_log_action_check CHECK (action IN ('LISTED', 'READ', 'RECLASSIFIED', 'DISMISSED', 'UPHELD')),
    CONSTRAINT entry_report_log_actor_check CHECK (char_length(actor) BETWEEN 1 AND 100),
    CONSTRAINT entry_report_log_note_check CHECK (note IS NULL OR char_length(note) <= 500),
    CONSTRAINT entry_report_log_report_check CHECK ((report_id IS NULL) = (action = 'LISTED'))
);

CREATE INDEX entry_report_log_report_idx ON entry_report_log (report_id, at) WHERE report_id IS NOT NULL;

-- The operator's commands, as functions, so that the rules are here once and
-- `scripts/moyi report` only names which to run (ADR-0051 decision 6's
-- shape). Each writes its own log row in the same transaction as what it
-- does. Each calls `media`'s own functions for what becomes of the media
-- (V36, V38): gratitude depends on media, never the reverse, here as in the
-- build.

-- The open reports, the priority ones first (sexual content involving a
-- minor: never viewed, runbook §2), then oldest first. Ids, the reason,
-- whether there is a detail to read, and how many photos and voice notes the
-- report holds back; never the detail itself (`entry_report_read` shows it,
-- and logs that it did).
CREATE FUNCTION entry_report_list(p_actor text)
    RETURNS TABLE (priority boolean, report_id uuid, reason text, entry_id uuid, held integer, detail boolean,
                   received_at timestamptz)
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO entry_report_log (id, report_id, action, actor, note, at)
    VALUES (uuidv7(), NULL, 'LISTED', p_actor, NULL, now());
    RETURN QUERY
        SELECT r.reason = 'MINOR_SEXUAL_CONTENT', r.id, r.reason, r.entry_id,
               (SELECT count(*)::integer FROM media_report_holding(r.id)), r.detail IS NOT NULL, r.created_at
        FROM entry_reports r
        WHERE r.status = 'OPEN'
        ORDER BY r.reason = 'MINOR_SEXUAL_CONTENT' DESC, r.created_at, r.id;
END;
$$;

-- One report, for the person deciding it: its reason, entry, bond, status,
-- when, the reporter's detail (logged as READ, under the operator's name),
-- and the media it holds back, one row each (none: one row with no media).
CREATE FUNCTION entry_report_read(p_report uuid, p_actor text)
    RETURNS TABLE (report_id uuid, reason text, status text, entry_id uuid, bond_id uuid, received_at timestamptz,
                   detail text, media_id uuid, media_kind text, media_status text, media_verdict text)
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM entry_reports r WHERE r.id = p_report) THEN
        RETURN;
    END IF;
    INSERT INTO entry_report_log (id, report_id, action, actor, note, at)
    VALUES (uuidv7(), p_report, 'READ', p_actor, NULL, now());
    RETURN QUERY
        SELECT r.id, r.reason, r.status, r.entry_id, r.bond_id, r.created_at, r.detail,
               m.media_id, m.kind, m.status, m.verdict
        FROM entry_reports r LEFT JOIN LATERAL media_report_holding(r.id) m ON true
        WHERE r.id = p_report;
END;
$$;

-- Not what was reported: closed, its detail emptied, and every photo and voice
-- note it held back that nothing else holds is given back as it was
-- (`media_report_release`). Returns DISMISSED or NOT_OPEN.
CREATE FUNCTION entry_report_dismiss(p_report uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE entry_reports SET status = 'DISMISSED', resolved_at = now(), detail = NULL
    WHERE id = p_report AND status = 'OPEN';
    IF NOT FOUND THEN
        RETURN 'NOT_OPEN';
    END IF;
    INSERT INTO entry_report_log (id, report_id, action, actor, note, at)
    VALUES (uuidv7(), p_report, 'DISMISSED', p_actor, p_note, now());
    PERFORM media_report_release(p_report, p_actor, left('report ' || p_report || ' dismissed: ' || p_note, 500));
    RETURN 'DISMISSED';
END;
$$;

-- An open report's reason, changed by the operator, with the old and new
-- reasons and why in the log (RECLASSIFIED). The one way a report of sexual
-- content involving a minor can be upheld by anything but a block (review of
-- #118, P2-c): the reporter chose the wrong reason, and their detail says so.
-- Returns RECLASSIFIED, SAME_REASON or NOT_OPEN.
CREATE FUNCTION entry_report_reclassify(p_report uuid, p_reason text, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    v_old text;
BEGIN
    SELECT reason INTO v_old FROM entry_reports WHERE id = p_report AND status = 'OPEN' FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'NOT_OPEN';
    END IF;
    IF v_old = p_reason THEN
        RETURN 'SAME_REASON';
    END IF;
    -- The CHECK on `reason` refuses a name that is not one of the five.
    UPDATE entry_reports SET reason = p_reason WHERE id = p_report;
    INSERT INTO entry_report_log (id, report_id, action, actor, note, at)
    VALUES (uuidv7(), p_report, 'RECLASSIFIED', p_actor, left(v_old || ' -> ' || p_reason || ': ' || p_note, 500), now());
    RETURN 'RECLASSIFIED';
END;
$$;

-- What was reported: closed, its detail emptied, and what it held back taken
-- down (`media_report_takedown`): DELETE, or BLOCK for child sexual abuse
-- material (held as evidence, then runbook §2). A report of words alone holds
-- nothing, and its upholding takes nothing down (ADR-0053 decision 8). Returns
-- each asset with what became of it; a report that is not open, nothing at
-- all, and nothing is written.
--
-- **A MINOR_SEXUAL_CONTENT report is never upheld by DELETE** (review of
-- #118, P2-c): deleting would destroy what may be evidence the law requires
-- to be kept and reported (runbook §2). It is blocked, or first reclassified
-- (above, logged) when the reporter's own detail shows the reason was wrong.
-- Refused with the outcome RECLASSIFY_FIRST, and nothing is written.
CREATE FUNCTION entry_report_uphold(p_report uuid, p_action text, p_actor text, p_note text)
    RETURNS TABLE (media_id uuid, outcome text)
    LANGUAGE plpgsql AS $$
DECLARE
    v_reason text;
BEGIN
    IF p_action NOT IN ('DELETE', 'BLOCK') THEN
        RAISE EXCEPTION 'a report is upheld by DELETE or BLOCK, not %', p_action;
    END IF;
    SELECT r.reason INTO v_reason FROM entry_reports r WHERE r.id = p_report AND r.status = 'OPEN' FOR UPDATE;
    IF NOT FOUND THEN
        media_id := NULL;
        outcome := 'NOT_OPEN';
        RETURN NEXT;
        RETURN;
    END IF;
    IF v_reason = 'MINOR_SEXUAL_CONTENT' AND p_action = 'DELETE' THEN
        media_id := NULL;
        outcome := 'RECLASSIFY_FIRST';
        RETURN NEXT;
        RETURN;
    END IF;
    UPDATE entry_reports SET status = 'UPHELD', resolved_at = now(), detail = NULL WHERE id = p_report;
    INSERT INTO entry_report_log (id, report_id, action, actor, note, at)
    VALUES (uuidv7(), p_report, 'UPHELD', p_actor, left(p_action || ': ' || p_note, 500), now());
    RETURN QUERY SELECT t.media_id, t.outcome FROM media_report_takedown(p_report, p_action, p_actor, p_note) t;
END;
$$;
