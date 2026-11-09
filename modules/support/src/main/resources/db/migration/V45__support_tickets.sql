-- FR-102: "An in-app support contact route MUST exist: POST /support/messages
-- creates a ticket visible in the admin console and sends an acknowledgement
-- email." (ADR-0057). Doc 07 has no table for it; this is the ticket, and the
-- record of who looked at it, until the Phase 5 console reads it.
--
-- `user_id` is identity's id and carries no foreign key, as every id that
-- crosses a module here does (CLAUDE.md). It is the sender: a ticket is filed
-- only by a signed-in account (decision 1).
--
-- `subject` and `body` are the sender's own words to a person: at most 120 and
-- 5000 characters (code points; `SupportSubject` and `SupportBody` are the
-- rule, these CHECKs its second line). They are never logged, never returned
-- by the API, and emptied when the ticket is closed (decision 7): what was
-- done, and why, is the operator's note in the log.
CREATE TABLE support_tickets (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL,
    subject    text,
    body       text,
    status     text        NOT NULL,
    created_at timestamptz NOT NULL,
    closed_at  timestamptz,

    CONSTRAINT support_tickets_subject_check CHECK (subject IS NULL OR char_length(subject) BETWEEN 1 AND 120),
    CONSTRAINT support_tickets_body_check CHECK (body IS NULL OR char_length(body) BETWEEN 1 AND 5000),
    CONSTRAINT support_tickets_status_check CHECK (status IN ('OPEN', 'CLOSED')),
    -- Closed, or not: a time exactly when it is.
    CONSTRAINT support_tickets_closed_check CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    -- An open ticket says something; a closed one keeps none of it.
    CONSTRAINT support_tickets_text_kept_check CHECK (
        (status = 'OPEN' AND subject IS NOT NULL AND body IS NOT NULL)
        OR (status = 'CLOSED' AND subject IS NULL AND body IS NULL)
    )
);

-- The queue: what is open, oldest first.
CREATE INDEX support_tickets_open_idx ON support_tickets (created_at) WHERE status = 'OPEN';
-- One account's tickets: for the erasure owed when an account is deleted (decision 8).
CREATE INDEX support_tickets_user_idx ON support_tickets (user_id);

-- Who looked at the queue or a ticket, and who closed it, with their note.
-- Never the subject, never the body. `ticket_id` is null on a LISTED row, a
-- look at the whole queue, as `entry_report_log.report_id` is (V39).
CREATE TABLE support_ticket_log (
    id        uuid        PRIMARY KEY,
    ticket_id uuid        REFERENCES support_tickets (id),
    action    text        NOT NULL,
    actor     text        NOT NULL,
    note      text,
    at        timestamptz NOT NULL,

    CONSTRAINT support_ticket_log_action_check CHECK (action IN ('LISTED', 'READ', 'CLOSED')),
    CONSTRAINT support_ticket_log_actor_check CHECK (char_length(actor) BETWEEN 1 AND 100),
    CONSTRAINT support_ticket_log_note_check CHECK (note IS NULL OR char_length(note) <= 500),
    CONSTRAINT support_ticket_log_ticket_check CHECK ((ticket_id IS NULL) = (action = 'LISTED'))
);

CREATE INDEX support_ticket_log_ticket_idx ON support_ticket_log (ticket_id, at) WHERE ticket_id IS NOT NULL;

-- The operator's commands, as functions, so that `scripts/moyi support` only
-- names which to run (V39's shape). Each writes its own log row in the same
-- transaction as what it does.

-- The open tickets, oldest first: ids, how long each message is, when. Never
-- the words (`support_ticket_read` shows them, and logs that it did).
CREATE FUNCTION support_ticket_list(p_actor text)
    RETURNS TABLE (ticket_id uuid, user_id uuid, subject_length integer, body_length integer, received_at timestamptz)
    LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO support_ticket_log (id, ticket_id, action, actor, note, at)
    VALUES (uuidv7(), NULL, 'LISTED', p_actor, NULL, now());
    RETURN QUERY
        SELECT t.id, t.user_id, char_length(t.subject), char_length(t.body), t.created_at
        FROM support_tickets t
        WHERE t.status = 'OPEN'
        ORDER BY t.created_at, t.id;
END;
$$;

-- One ticket, for the person answering it: its words (logged as READ, under
-- the operator's name) and where to answer. The address is read here, from
-- identity's `users`, at the moment of answering, not copied into the ticket:
-- a second copy of an address is a second thing to erase (decision 8). Whether
-- it was ever confirmed is shown beside it, since an unconfirmed address may
-- not be the sender's (ADR-0015).
CREATE FUNCTION support_ticket_read(p_ticket uuid, p_actor text)
    RETURNS TABLE (ticket_id uuid, status text, user_id uuid, reply_to text, reply_to_confirmed boolean,
                   received_at timestamptz, subject text, body text)
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM support_tickets t WHERE t.id = p_ticket) THEN
        RETURN;
    END IF;
    INSERT INTO support_ticket_log (id, ticket_id, action, actor, note, at)
    VALUES (uuidv7(), p_ticket, 'READ', p_actor, NULL, now());
    RETURN QUERY
        SELECT t.id, t.status, t.user_id, u.email::text, u.email_verified_at IS NOT NULL, t.created_at, t.subject, t.body
        FROM support_tickets t LEFT JOIN users u ON u.id = t.user_id AND u.deleted_at IS NULL
        WHERE t.id = p_ticket;
END;
$$;

-- Answered, or nothing to answer: closed, its words emptied, and the
-- operator's note of what was done in the log. Returns CLOSED or NOT_OPEN.
CREATE FUNCTION support_ticket_close(p_ticket uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE support_tickets SET status = 'CLOSED', closed_at = now(), subject = NULL, body = NULL
    WHERE id = p_ticket AND status = 'OPEN';
    IF NOT FOUND THEN
        RETURN 'NOT_OPEN';
    END IF;
    INSERT INTO support_ticket_log (id, ticket_id, action, actor, note, at)
    VALUES (uuidv7(), p_ticket, 'CLOSED', p_actor, p_note, now());
    RETURN 'CLOSED';
END;
$$;
