-- Push notifications, the transport half (Phase 4 slice N1, ADR-0044).
--
-- `notification` is a leaf module: it may read nothing of identity's, bond's
-- or gratitude's (spec §2.1). So it keeps its own copy of the one fact it needs
-- about a device, the token to send to, keyed by identity's device id with no
-- foreign key (no foreign key crosses a module boundary). `devices.push_token`
-- (V8) was never written and is now dead; ADR-0044 says why it is left until a
-- later migration drops it.

-- One push token per device: the device is identity's row, made at sign-in
-- (ADR-0025). `unique (token)` is the fold ADR-0025 waited for: a token names
-- one installation of the app, so a second device row that registers it takes
-- it from the first.
--
-- The token is a secret (doc 11:25): whoever holds it can address that phone.
-- It is never logged and its type redacts its `toString`.
CREATE TABLE push_tokens (
    device_id  uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL,
    platform   text        NOT NULL,
    token      text        NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT push_tokens_platform_check CHECK (platform IN ('ANDROID', 'IOS', 'WEAR')),
    -- FCM documents no maximum. Tokens seen today are about 160 characters;
    -- the bound is generous and keeps the unique index's entries well inside
    -- a btree page.
    CONSTRAINT push_tokens_token_length_check CHECK (char_length(token) BETWEEN 1 AND 1024),
    CONSTRAINT push_tokens_token_key UNIQUE (token)
);

-- "Every phone of this person", which is what a push is sent to.
CREATE INDEX push_tokens_user_id_idx ON push_tokens (user_id);

-- The notification ledger (doc 07:271-272, spec D-17): one row per push a
-- member is owed, made by `notification.api.Notifications.request` in the
-- transaction of whatever decided it was owed, and settled by the
-- `notification.push` consumer.
--
-- `dedupe_key` is unique, which makes a second *request* for the same push
-- impossible. It does not make a second *send* impossible: the consumer is
-- at least once, and a commit lost after FCM accepted a message sends it again
-- on the retry (spec §2.2 point 3, ADR-0044).
--
-- Nothing here is content: a category, ids and instants. The words a phone
-- shows are localisation keys chosen by category (spec §5 Q6).
CREATE TABLE notifications (
    id                  uuid        PRIMARY KEY,
    user_id             uuid        NOT NULL,
    bond_id             uuid        NOT NULL,
    category            text        NOT NULL,
    dedupe_key          text        NOT NULL,
    -- Whether this push took its Bond's reserved reveal-class slot (FR-082).
    -- The budget is N2's; until it is built nothing reserves, and this is false.
    reserved            boolean     NOT NULL DEFAULT false,
    -- Not to be sent before: quiet hours hold a push to their end (N2, Q7).
    not_before          timestamptz NOT NULL,
    -- Not to be sent after: past this the push is EXPIRED, never sent late.
    -- No CHECK against not_before: a request may arrive already stale (an
    -- event delivered after an outage), and it is recorded and then expired
    -- by the consumer like any other, so the ledger shows it.
    stale_after         timestamptz NOT NULL,
    status              text        NOT NULL,
    provider_message_id text,
    -- Why a push ended without being sent: a word of ours, never a provider's
    -- message, which can quote what was sent.
    error_class         text,
    created_at          timestamptz NOT NULL,
    sent_at             timestamptz,

    CONSTRAINT notifications_category_check CHECK (
        category IN ('REMINDER', 'PARTNER_JOINED', 'REVEAL', 'SOLO_UNLOCK', 'MILESTONE', 'ON_THIS_DAY', 'PROPOSAL_OPENED')
    ),
    CONSTRAINT notifications_status_check CHECK (
        status IN ('REQUESTED', 'SENT', 'SUPPRESSED', 'DROPPED_BUDGET', 'EXPIRED', 'FAILED_PERMANENT')
    ),
    CONSTRAINT notifications_dedupe_key_length_check CHECK (char_length(dedupe_key) BETWEEN 1 AND 200),
    -- A sent push says when, and only a sent push does.
    CONSTRAINT notifications_sent_at_check CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT notifications_dedupe_key_key UNIQUE (dedupe_key)
);
