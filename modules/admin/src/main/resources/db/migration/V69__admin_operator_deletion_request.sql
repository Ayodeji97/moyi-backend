-- Account deletion asked for by email (ADR-0058, amended 2026-11-16).
--
-- getmoyi.app/delete-account promises a way that needs no app: "Email hello@getmoyi.app from the
-- address on your account, with the subject Delete my account. We reply to that address to confirm
-- it is you, then delete the account within 30 days." Google Play requires one. This is the
-- operator's half: once the person has confirmed from the account's address
-- (ops/admin/deletion-by-email.md), `moyi account delete` calls account_deletion_request below, and
-- `moyi account cancel-deletion` calls account_deletion_cancel when they change their mind by email
-- (a suspended account cannot sign in to cancel in the app).
--
-- It opens the SAME request the app's `POST /me/deletion-request` opens, in the same table, so
-- everything after it is ADR-0058's and nothing here is a second copy: the 14-day window in which
-- the person can still cancel in the app, then ExecuteAccountDeletions and every module's consumer
-- of AccountDeleted. No security email is sent: the operator's reply is how the person hears of it.
--
-- The window is `interval '336 hours'`, not '14 days': on a timestamptz Postgres adds calendar days
-- in the session's time zone, which across a clock change is 13 days 23 hours or 14 days 1 hour.
-- DeletionRequest.CANCELLATION_WINDOW is Duration.ofDays(14), exactly 336 hours. The job takes a request
-- when execute_after <= now (DeletionRequestStore.dueBy); the app's cancel asks requested_at + the window
-- (DeletionRequest.canBeCancelledAt). The two must agree to the microsecond, or an instant would be both
-- cancellable and due, or neither: V69MigrationTest checks the stored gap, DeletionRequestTest the 336.
--
-- A deliberate exception to "a module writes only its own tables" (ADR-0058's amendment): the operator's
-- function is admin's, the rows are identity's. It writes exactly what identity's own request and cancel
-- write, under the same lock, and nothing else.

-- audit_log's action CHECK, widened by the two actions below and nothing else (V66's pattern).
-- The list mirrors the Kotlin enum AuditAction; AuditActionCheckTest holds the two to each other.
ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;

ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (
    'ADMIN_INVITED', 'ADMIN_DISABLED', 'ADMIN_ENROLLED', 'ADMIN_SIGN_IN', 'ADMIN_SIGN_OUT', 'USER_LOOKED_UP',
    'REPORT_QUEUE_VIEWED', 'REPORT_READ', 'REPORT_DISMISSED', 'REPORT_RECLASSIFIED', 'REPORT_UPHELD',
    'QUARANTINE_QUEUE_VIEWED', 'MEDIA_VIEWED', 'MEDIA_RELEASED', 'MEDIA_BLOCKED', 'MEDIA_DELETED',
    'ADMIN_STEP_UP', 'ENTRY_TEXT_READ', 'ACCOUNT_SUSPENDED', 'ACCOUNT_UNSUSPENDED',
    'JOB_RUN', 'STREAK_RECALCULATED', 'FEATURE_FLAG_CHANGED',
    'SUPPORT_INBOX_VIEWED', 'SUPPORT_TICKET_READ', 'SUPPORT_TICKET_CLOSED',
    'ACCOUNT_DELETION_REQUESTED', 'ACCOUNT_DELETION_CANCELLED'
));

-- Both functions check the operator's name and note the same way, and answer with the account as
-- stored (its address and status), so the operator sees which account was meant before replying.
CREATE FUNCTION account_deletion_check_operator(p_operator text, p_note text) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    IF p_operator IS NULL OR p_operator !~ '^[A-Za-z0-9._@-]{1,64}$' THEN
        RAISE EXCEPTION 'an operator name is required' USING ERRCODE = 'check_violation';
    END IF;
    IF p_note IS NULL OR char_length(btrim(p_note)) = 0 OR char_length(p_note) > 400 THEN
        RAISE EXCEPTION 'a note of 1 to 400 characters is required' USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

-- Opens a deletion request for the account at p_email, or reports the one already pending.
-- outcome: REQUESTED (opened now), ALREADY_PENDING (nothing done; its dates returned), UNVERIFIED
-- (nothing done: the address was never confirmed, so a reply from it proves less than the runbook
-- needs; the person confirms it first), or NO_ACCOUNT (no such address, or an account already
-- deleted, whose address was scrubbed). A suspended account is accepted: deletion is the person's
-- right whatever the account's standing, and they cancel by email (account_deletion_cancel).
-- Only REQUESTED writes an audit row: OPERATOR, no actor id (V60's operator functions), the
-- operator's name and the request's id in metadata, the note as the justification. Never the
-- email: the row is about the account, by id.
CREATE FUNCTION account_deletion_request(p_email citext, p_operator text, p_note text)
    RETURNS TABLE (outcome text, user_id uuid, email text, status text,
                   requested_at timestamptz, execute_after timestamptz)
    LANGUAGE plpgsql AS $$
DECLARE
    v_now      timestamptz := now();
    v_user     uuid;
    v_email    text;
    v_status   text;
    v_verified boolean;
    v_request  uuid;
BEGIN
    PERFORM account_deletion_check_operator(p_operator, p_note);

    -- The user's row, locked: what serialises this with the app's request and cancel and with the
    -- job (DeletionRequestStore.lockUser takes the same lock).
    SELECT u.id, u.email::text, u.status, u.email_verified_at IS NOT NULL
      INTO v_user, v_email, v_status, v_verified
      FROM users u WHERE u.email = p_email FOR UPDATE;
    IF NOT FOUND OR v_status = 'DELETED' THEN
        RETURN QUERY SELECT 'NO_ACCOUNT'::text, NULL::uuid, NULL::text, NULL::text, NULL::timestamptz, NULL::timestamptz;
        RETURN;
    END IF;

    RETURN QUERY
        SELECT 'ALREADY_PENDING'::text, d.user_id, v_email, v_status, d.requested_at, d.execute_after
        FROM deletion_requests d
        WHERE d.user_id = v_user AND d.cancelled_at IS NULL AND d.executed_at IS NULL;
    IF FOUND THEN
        RETURN;
    END IF;

    IF NOT v_verified THEN
        RETURN QUERY SELECT 'UNVERIFIED'::text, v_user, v_email, v_status, NULL::timestamptz, NULL::timestamptz;
        RETURN;
    END IF;

    v_request := uuidv7();
    INSERT INTO deletion_requests (id, user_id, requested_at, execute_after)
    VALUES (v_request, v_user, v_now, v_now + interval '336 hours');

    INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, target_type, target_id,
                           justification, outcome, metadata)
    VALUES (uuidv7(), v_now, 'OPERATOR', NULL, 'ACCOUNT_DELETION_REQUESTED', 'USER', v_user::text,
            p_note, 'SUCCEEDED', jsonb_build_object('operator', p_operator, 'request', v_request));

    RETURN QUERY SELECT 'REQUESTED'::text, v_user, v_email, v_status, v_now, v_now + interval '336 hours';
END;
$$;

-- Calls off the pending request for the account at p_email, as the app's cancel does
-- (AccountDeletion.cancel): only while the window is open, now < execute_after, the exact
-- complement of the job's execute_after <= now. outcome: CANCELLED, NOTHING_PENDING (none pending,
-- or the window has closed: one answer, as the app gives one 404), or NO_ACCOUNT.
-- Only CANCELLED writes an audit row, shaped as account_deletion_request's.
CREATE FUNCTION account_deletion_cancel(p_email citext, p_operator text, p_note text)
    RETURNS TABLE (outcome text, user_id uuid, email text, status text,
                   requested_at timestamptz, execute_after timestamptz)
    LANGUAGE plpgsql AS $$
DECLARE
    v_now       timestamptz := now();
    v_user      uuid;
    v_email     text;
    v_status    text;
    v_request   uuid;
    v_requested timestamptz;
    v_after     timestamptz;
BEGIN
    PERFORM account_deletion_check_operator(p_operator, p_note);

    SELECT u.id, u.email::text, u.status INTO v_user, v_email, v_status
      FROM users u WHERE u.email = p_email FOR UPDATE;
    IF NOT FOUND OR v_status = 'DELETED' THEN
        RETURN QUERY SELECT 'NO_ACCOUNT'::text, NULL::uuid, NULL::text, NULL::text, NULL::timestamptz, NULL::timestamptz;
        RETURN;
    END IF;

    SELECT d.id, d.requested_at, d.execute_after INTO v_request, v_requested, v_after
      FROM deletion_requests d
     WHERE d.user_id = v_user AND d.cancelled_at IS NULL AND d.executed_at IS NULL AND v_now < d.execute_after;
    IF NOT FOUND THEN
        RETURN QUERY SELECT 'NOTHING_PENDING'::text, v_user, v_email, v_status, NULL::timestamptz, NULL::timestamptz;
        RETURN;
    END IF;

    UPDATE deletion_requests SET cancelled_at = v_now WHERE id = v_request;

    INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, target_type, target_id,
                           justification, outcome, metadata)
    VALUES (uuidv7(), v_now, 'OPERATOR', NULL, 'ACCOUNT_DELETION_CANCELLED', 'USER', v_user::text,
            p_note, 'SUCCEEDED', jsonb_build_object('operator', p_operator, 'request', v_request));

    RETURN QUERY SELECT 'CANCELLED'::text, v_user, v_email, v_status, v_requested, v_after;
END;
$$;
