-- Account deletion asked for by email (ADR-0058, amended 2026-11-16).
--
-- getmoyi.app/delete-account promises a way that needs no app: "Email hello@getmoyi.app from the
-- address on your account, with the subject Delete my account. We reply to that address to confirm
-- it is you, then delete the account within 30 days." Google Play requires one. This is the
-- operator's half: once the person has confirmed from the account's address
-- (ops/admin/deletion-by-email.md), `moyi account delete` calls the function below.
--
-- It opens the SAME request the app's `POST /me/deletion-request` opens, in the same table, so
-- everything after it is ADR-0058's and nothing here is a second copy: the 14-day window in which
-- the person can still cancel in the app, then ExecuteAccountDeletions and every module's consumer
-- of AccountDeleted. No security email is sent: the operator's reply is how the person hears of it.
--
-- The window is `interval '336 hours'`, not '14 days': on a timestamptz Postgres adds calendar days
-- in the session's time zone, which across a clock change is 13 days 23 hours or 14 days 1 hour.
-- DeletionRequest.CANCELLATION_WINDOW is Duration.ofDays(14), exactly 336 hours, and the job reads
-- the window from requested_at, not from execute_after: the two must agree to the microsecond.

-- audit_log's action CHECK, widened by ACCOUNT_DELETION_REQUESTED and nothing else (V66's pattern).
-- The list mirrors the Kotlin enum AuditAction; AuditActionCheckTest holds the two to each other.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;

ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_INVITED', 'ADMIN_DISABLED', 'ADMIN_ENROLLED', 'ADMIN_SIGN_IN', 'ADMIN_SIGN_OUT', 'USER_LOOKED_UP',
    'REPORT_QUEUE_VIEWED', 'REPORT_READ', 'REPORT_DISMISSED', 'REPORT_RECLASSIFIED', 'REPORT_UPHELD',
    'QUARANTINE_QUEUE_VIEWED', 'MEDIA_VIEWED', 'MEDIA_RELEASED', 'MEDIA_BLOCKED', 'MEDIA_DELETED',
    'ADMIN_STEP_UP', 'ENTRY_TEXT_READ', 'ACCOUNT_SUSPENDED', 'ACCOUNT_UNSUSPENDED',
    'JOB_RUN', 'STREAK_RECALCULATED', 'FEATURE_FLAG_CHANGED',
    'SUPPORT_INBOX_VIEWED', 'SUPPORT_TICKET_READ', 'SUPPORT_TICKET_CLOSED',
    'ACCOUNT_DELETION_REQUESTED'
));

-- Opens a deletion request for the account at p_email, or reports the one already pending.
-- outcome: REQUESTED (opened now), ALREADY_PENDING (nothing done; its dates returned), or
-- NO_ACCOUNT (no such address, or an account already deleted, whose address was scrubbed).
-- Only REQUESTED writes an audit row: OPERATOR, no actor id (V60's operator functions), the
-- operator's name and the request's id in metadata, the note as the justification. Never the
-- email: the row is about the account, by id.
CREATE FUNCTION account_deletion_request(p_email citext, p_operator text, p_note text)
    RETURNS TABLE (outcome text, user_id uuid, requested_at timestamptz, execute_after timestamptz)
    LANGUAGE plpgsql AS $$
DECLARE
    v_now     timestamptz := now();
    v_user    uuid;
    v_status  text;
    v_request uuid;
BEGIN
    IF p_operator IS NULL OR p_operator !~ '^[A-Za-z0-9._@-]{1,64}$' THEN
        RAISE EXCEPTION 'an operator name is required' USING ERRCODE = 'check_violation';
    END IF;
    IF p_note IS NULL OR char_length(btrim(p_note)) = 0 OR char_length(p_note) > 400 THEN
        RAISE EXCEPTION 'a note of 1 to 400 characters is required' USING ERRCODE = 'check_violation';
    END IF;

    -- The user's row, locked: what serialises this with the app's request and cancel and with the
    -- job (DeletionRequestStore.lockUser takes the same lock).
    SELECT u.id, u.status INTO v_user, v_status FROM users u WHERE u.email = p_email FOR UPDATE;
    IF NOT FOUND OR v_status = 'DELETED' THEN
        RETURN QUERY SELECT 'NO_ACCOUNT'::text, NULL::uuid, NULL::timestamptz, NULL::timestamptz;
        RETURN;
    END IF;

    RETURN QUERY
        SELECT 'ALREADY_PENDING'::text, d.user_id, d.requested_at, d.execute_after
        FROM deletion_requests d
        WHERE d.user_id = v_user AND d.cancelled_at IS NULL AND d.executed_at IS NULL;
    IF FOUND THEN
        RETURN;
    END IF;

    v_request := uuidv7();
    INSERT INTO deletion_requests (id, user_id, requested_at, execute_after)
    VALUES (v_request, v_user, v_now, v_now + interval '336 hours');

    INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, target_type, target_id,
                           justification, outcome, metadata)
    VALUES (uuidv7(), v_now, 'OPERATOR', NULL, 'ACCOUNT_DELETION_REQUESTED', 'USER', v_user::text,
            p_note, 'SUCCEEDED', jsonb_build_object('operator', p_operator, 'request', v_request));

    RETURN QUERY SELECT 'REQUESTED'::text, v_user, v_now, v_now + interval '336 hours';
END;
$$;
