-- The person's error reference (ADR-0057 decision 11, issue #168): what the
-- app showed them beside an error, usually that error's traceId, sent along
-- when they opened support from it, so the operator can find the failing
-- request. Optional: null when the message was not sent from an error.
--
-- 1 to 64 visible ASCII characters, 0x21 to 0x7E (`support.domain.
-- SupportReference` is the rule; this CHECK is its second line). The class is
-- spelled as the two bytes, not as a range of letters, so no collation can
-- widen it.
ALTER TABLE support_tickets ADD COLUMN reference text;

ALTER TABLE support_tickets ADD CONSTRAINT support_tickets_reference_check
    CHECK (reference IS NULL OR reference ~ '^[\x21-\x7E]{1,64}$');

-- A closed ticket keeps none of what was sent with it: the reference goes
-- with the words, on the operator's close and on an account's deletion
-- (decision 7, ADR-0058 decision 10). An open one may carry it or not.
ALTER TABLE support_tickets DROP CONSTRAINT support_tickets_text_kept_check;
ALTER TABLE support_tickets ADD CONSTRAINT support_tickets_text_kept_check CHECK (
    (status = 'OPEN' AND subject IS NOT NULL AND body IS NOT NULL)
    OR (status = 'CLOSED' AND subject IS NULL AND body IS NULL AND reference IS NULL)
);

-- The operator's read shows the reference beside the words. Its result gains
-- a column, which CREATE OR REPLACE cannot do, so it is dropped and made again.
DROP FUNCTION support_ticket_read(uuid, text);
CREATE FUNCTION support_ticket_read(p_ticket uuid, p_actor text)
    RETURNS TABLE (ticket_id uuid, status text, user_id uuid, reply_to text, reply_to_confirmed boolean,
                   received_at timestamptz, subject text, body text, reference text)
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM support_tickets t WHERE t.id = p_ticket) THEN
        RETURN;
    END IF;
    INSERT INTO support_ticket_log (id, ticket_id, action, actor, note, at)
    VALUES (uuidv7(), p_ticket, 'READ', p_actor, NULL, now());
    RETURN QUERY
        SELECT t.id, t.status, t.user_id, u.email::text, u.email_verified_at IS NOT NULL, t.created_at, t.subject, t.body,
               t.reference
        FROM support_tickets t LEFT JOIN users u ON u.id = t.user_id AND u.deleted_at IS NULL
        WHERE t.id = p_ticket;
END;
$$;

-- The operator's close empties the reference with the words.
CREATE OR REPLACE FUNCTION support_ticket_close(p_ticket uuid, p_actor text, p_note text) RETURNS text
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE support_tickets SET status = 'CLOSED', closed_at = now(), subject = NULL, body = NULL, reference = NULL
    WHERE id = p_ticket AND status = 'OPEN';
    IF NOT FOUND THEN
        RETURN 'NOT_OPEN';
    END IF;
    INSERT INTO support_ticket_log (id, ticket_id, action, actor, note, at)
    VALUES (uuidv7(), p_ticket, 'CLOSED', p_actor, p_note, now());
    RETURN 'CLOSED';
END;
$$;
