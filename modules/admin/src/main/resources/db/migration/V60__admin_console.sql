-- The admin console, slice A1 (spec 2026-11-06-admin-console-a1-design.md §5, §8).
--
-- Admins are not users: their own tables, never `users` (FR-110). Accounts are
-- created only by the operator's SQL function below, in the shape of ADR-0051's
-- media_quarantine_* functions, because a "create admin" page would be a way to
-- become admin without already holding the server.

-- `gen_random_bytes` for the invitation token. V1 installs this too; stated here
-- so that this migration does not lean on another module's.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE admin_accounts (
    id                     uuid        PRIMARY KEY,
    email                  citext      NOT NULL,
    role                   text        NOT NULL,
    password_hash          text,
    -- Set on enrolment, re-invitation and disabling: a session older than this
    -- is signed out (spec §6).
    credentials_changed_at timestamptz NOT NULL,
    -- AES-GCM, base64(nonce || ciphertext || tag). Never the secret itself.
    totp_secret_enc        text,
    -- The last accepted TOTP time step; a code at or before it is a replay.
    totp_last_step         bigint,
    status                 text        NOT NULL,
    failed_attempts        int         NOT NULL DEFAULT 0,
    locked_until           timestamptz,
    created_at             timestamptz NOT NULL DEFAULT now(),
    activated_at           timestamptz,
    disabled_at            timestamptz,

    CONSTRAINT admin_accounts_email_key UNIQUE (email),
    CONSTRAINT admin_accounts_role_check CHECK (role IN ('SUPPORT', 'MODERATOR', 'ADMIN')),
    CONSTRAINT admin_accounts_status_check CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED')),
    CONSTRAINT admin_accounts_failed_attempts_check CHECK (failed_attempts >= 0)
);

-- Only the SHA-256 of the one-time token is stored; the token itself never is.
CREATE TABLE admin_invitations (
    id          uuid        PRIMARY KEY,
    admin_id    uuid        NOT NULL REFERENCES admin_accounts (id) ON DELETE CASCADE,
    token_hash  bytea       NOT NULL,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,

    CONSTRAINT admin_invitations_token_hash_key UNIQUE (token_hash)
);

CREATE INDEX admin_invitations_admin_idx ON admin_invitations (admin_id);

CREATE TABLE audit_log (
    id            uuid        PRIMARY KEY,
    occurred_at   timestamptz NOT NULL DEFAULT now(),
    actor_type    text        NOT NULL,
    actor_id      uuid,
    action        text        NOT NULL,
    target_type   text,
    target_id     text,
    justification text,
    outcome       text        NOT NULL,
    -- Never an email, a password, a code, a token, a session id or a search text.
    metadata      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- The keyed hash of the caller's address (PersonalDataHasher), never the address.
    ip_hash       bytea,

    CONSTRAINT audit_log_actor_type_check CHECK (actor_type IN ('ADMIN', 'OPERATOR', 'SYSTEM')),
    -- The one list that mirrors a Kotlin enum (AuditAction); AuditActionCheckTest
    -- holds this to it. Adding an action is a new migration that replaces this check.
    CONSTRAINT audit_log_action_check CHECK (action IN (
        'ADMIN_INVITED', 'ADMIN_DISABLED', 'ADMIN_ENROLLED', 'ADMIN_SIGN_IN', 'ADMIN_SIGN_OUT', 'USER_LOOKED_UP'
    )),
    CONSTRAINT audit_log_outcome_check CHECK (outcome IN ('SUCCEEDED', 'REFUSED', 'FAILED'))
);

-- The FR-112 disclosure reads "everything done to this target", oldest first.
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id, occurred_at);

-- Append-only, in the database: it holds against the application and against
-- ordinary psql, the reasoning of ADR-0051's evidence trigger. Retention (two
-- years, doc 07) will need one sanctioned delete; that slice adds a function
-- that sets a transaction-local setting this trigger checks, in ADR-0051's
-- pattern. Until then nothing deletes audit rows. A person who can drop the
-- trigger can still alter the log, as with every trigger: this stops a mistake
-- or a script, not the database's owner.
CREATE FUNCTION audit_log_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only' USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_append_only();

-- TRUNCATE fires no row trigger, so it needs its own, on the statement.
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_append_only();

-- The operator's commands, as functions (the runbook runs them with
-- `docker exec -i moyi-postgres psql`). Each writes its own audit row in the
-- same transaction as what it does, with actor_type OPERATOR and no actor id.

-- Creates the account INVITED, or resets an existing one to INVITED: password,
-- TOTP secret and lockout cleared, and credentials_changed_at moved forward so
-- any live session of it ends. Returns the one-time token: 32 random bytes,
-- base64url without padding (43 characters); only its SHA-256 is stored, and
-- it is never written to the audit row. An invitation not yet used is
-- withdrawn, so only the newest token works. Expires in 24 hours.
CREATE FUNCTION admin_invite(p_email citext, p_role text) RETURNS text
    LANGUAGE plpgsql AS $$
DECLARE
    v_now   timestamptz := now();
    v_id    uuid;
    v_token bytea := gen_random_bytes(32);
BEGIN
    INSERT INTO admin_accounts (id, email, role, status, credentials_changed_at, created_at)
    VALUES (uuidv7(), p_email, p_role, 'INVITED', v_now, v_now)
    ON CONFLICT (email) DO UPDATE
        SET role = EXCLUDED.role,
            status = 'INVITED',
            password_hash = NULL,
            totp_secret_enc = NULL,
            totp_last_step = NULL,
            failed_attempts = 0,
            locked_until = NULL,
            credentials_changed_at = v_now,
            activated_at = NULL,
            disabled_at = NULL
    RETURNING id INTO v_id;

    DELETE FROM admin_invitations WHERE admin_id = v_id AND consumed_at IS NULL;
    INSERT INTO admin_invitations (id, admin_id, token_hash, expires_at)
    VALUES (uuidv7(), v_id, sha256(v_token), v_now + interval '24 hours');

    INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, target_type, target_id, outcome, metadata)
    VALUES (uuidv7(), v_now, 'OPERATOR', NULL, 'ADMIN_INVITED', 'ADMIN_ACCOUNT', v_id::text, 'SUCCEEDED',
            jsonb_build_object('role', p_role));

    RETURN translate(encode(v_token, 'base64'), '+/=', '-_');
END;
$$;

-- Disables an account and ends its sessions (credentials_changed_at). The only
-- way an account is disabled in A1. Raises for an email nobody has.
CREATE FUNCTION admin_disable(p_email citext) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_now timestamptz := now();
    v_id  uuid;
BEGIN
    UPDATE admin_accounts
    SET status = 'DISABLED', disabled_at = v_now, credentials_changed_at = v_now
    WHERE email = p_email
    RETURNING id INTO v_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'no such admin';
    END IF;

    DELETE FROM admin_invitations WHERE admin_id = v_id AND consumed_at IS NULL;

    INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, target_type, target_id, outcome)
    VALUES (uuidv7(), v_now, 'OPERATOR', NULL, 'ADMIN_DISABLED', 'ADMIN_ACCOUNT', v_id::text, 'SUCCEEDED');
END;
$$;
