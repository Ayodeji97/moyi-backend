# ADR-0044 — Push: a leaf module that carries and records, tokens kept by it, and a consumer with its own deadline

**Status:** Accepted · **Date:** 2026-10-27 · **Deciders:** Daniel

## Context

Phase 4 slice N1 (spec `2026-10-27-phase-4-media-and-notifications-design.md` §2, §4 N1) is the
transport half of push notifications: where a device's push token lives, the ledger of pushes
owed, FCM, and how a push travels from the fact that earned it to a phone. Nothing requests a
push until N3; quiet hours, the budget and category toggles are N2.

Four things made it non-obvious.

**The module graph forbids the obvious design** (spec §2.1). `identity → notification` exists
already (ADR-0017: `EmailSender`), and `bond → identity`, `gratitude → bond, identity`. Doc 05:44
gives `notification` "push + email dispatch, preferences, quiet hours, dedupe", and doc 07:52 puts
`push_token` on identity's `devices`. Together they need a cycle: `notification` would have to
read devices, members and quiet hours, all held by modules that depend on it.

**Token ownership (spec D-1).** Doc 07:52 and ADR-0025 put the token on `devices.push_token`
(V8), never written. ADR-0025 also waited for push tokens to fold duplicate device rows on
`(user_id, push_token)` and to fill `revoked_at`.

**The outbox's contract does not fit a push as it stands** (spec §2.2). `EventConsumer.handle`
runs inside the delivery's transaction with a 60 s limit, and retries never stop (ADR-0035
decision 5). An external call made in `handle` holds a pooled connection and the delivery's row
lock; "no last attempt" would deliver a reveal push the next morning; and at-least-once means a
push can be sent twice.

**The spec's one [inferred] item for N1**: that a consumer publishing to the outbox from inside a
delivery transaction meets ADR-0035's rule that publishers run at READ COMMITTED.

## Decision

**1. `notification` is a leaf: it carries and records; it decides nothing about who is told
what.** It owns the transport and the ledger: `push_tokens` (keyed by identity's device id, no
foreign key), `notifications` with `unique (dedupe_key)`, the `PushSender` port and its FCM
adapter, and the `notification.push` consumer. The decision of who is told what lives with the
facts (N3: consumers in `gratitude` and `bond`), which call
`notification.api.Notifications.request(PushRequest)` with every value already resolved: user,
Bond, category, dedupe key, deadline. `ArchitectureTest` fails if any file under
`modules/notification` imports a domain module or its build file names one; Gradle alone would
refuse a cycle with identity, bond or gratitude, but not a dependency on media, scheduling,
admin or analytics.

**2. Tokens are `notification`'s; the route is `identity`'s.** `PUT` and
`DELETE /api/v1/me/devices/current/push-token`: the device is the one the caller's session began
from, found through the access token's `sid` (ADR-0025). There is no device id in the path for a
caller to swap. This replaces doc 06:82-83's `POST /me/devices` and `DELETE /me/devices/{id}`,
because devices are made at sign-in. Identity calls `notification.api.PushTokens` in its own
transaction, under the per-user sessions lock (ADR-0021), so that a registration cannot race a
logout into keeping a token for an ended session (`PushTokenEndpointTest` holds the lock and
watches the `PUT` wait, then answer `404`).

- `404 NOT_FOUND` when the session has ended (an access token outlives its session by up to
  fifteen minutes) or began without describing a device. No new error code.
- **Every way a session ends forgets its token in the same commit**: logout, logout-all, ending
  a session by id, a refresh-token reuse (the phone may be the thief's), a refresh by an account
  that can no longer sign in, and a password reset.
- **The fold** (ADR-0025, "Revisit when"). `unique (token)`: a token names one installation of
  the app, so it is held by one device row. A registration takes the token from whichever other
  row held it. If that row is the same person's, it was an earlier sign-in from the same phone;
  `PushTokens.register` returns its id and identity sets its `devices.revoked_at`. The session it
  began is not ended (ADR-0025 decision 4: revocation is explicit). If the row is somebody
  else's, the phone changed hands without a sign-out, and the token just moves: the earlier
  owner's pushes must not reach the new owner's lock screen.
- **Registrations of one token are serialised** by a transaction-scoped advisory lock on the
  token's hash (namespace `2`; `1` is the sessions lock), taken before the take-and-give.
  *(Corrected 2026-10-28, ADR-0046: this said namespace `2` was free. Bond's "bonds of a user"
  lock (FR-025) uses it too. Harmless, as ADR-0045 records: a hash collision would only make
  two unrelated requests wait for each other.)* Without
  it, two registrations at once both found nobody holding the token and the second insert failed
  on `unique (token)`: a 500 whose message, logged at ERROR, quoted the token (review of #96).
  With it, the later registrant runs after the earlier commits and holds the token.

**3. `devices.push_token` is dead, unmapped now, and dropped by a later forward migration.**
`DeviceEntity` and `Device` stop mapping it in this change. The drop is not in V29 for two
reasons. V29 is `notification`'s migration, and a statement on identity's table there would hide
it from the module that owns the table. More importantly, production is deployed (ADR-0042), so
ADR-0025 decision 4's "nothing is deployed" no longer holds and doc 07 §6's expand/contract does:
during a Kamal deploy the previous build runs beside the new one after V29 has run, and that
build maps `push_token` in every `INSERT INTO devices`, so a drop in the same release would fail
every sign-in on the old container for the length of the cutover. The drop belongs to the first
identity migration after this change is deployed (Owed).

**4. One queue, the outbox's.** `Notifications.request` joins the caller's transaction
(`MANDATORY`), inserts the row with `ON CONFLICT (dedupe_key) DO NOTHING`, and publishes
`PushRequested {notificationId}` on aggregate `Notification` only if the row is new. A request
that is already past its `staleAfter` is still recorded and published, and the consumer expires
it, so every final status has one path and the ledger shows what was asked for. Two transactions
asking at once make one row: the second insert waits on the first's uncommitted row and then does
nothing (`SendPushTest` watches it wait in `pg_stat_activity`).

**5. The consumer `notification.push` sends inside the delivery's transaction, with a deadline of
its own.** `StartFrom.NOW` (ADR-0035 Owed: no backfill scan of the outbox by type). In order:

1. A push that is gone, or no longer `REQUESTED`, is nothing to do: the same event twice.
2. Past `stale_after` → `EXPIRED`, never sent. The outbox's rule (retry for ever) stands; the
   push carries its own deadline instead (spec §2.2 point 2).
3. **The N2 seam**: category toggle → `SUPPRESSED`, quiet hours → held to `not_before`, FR-082's
   budget → `DROPPED_BUDGET` or the reserved slot. Not built. Until N2, every push is treated as
   wanted, in hours and in budget; this is safe only because nothing requests one before N3, and
   N3 comes after N2. `not_before` is set to the request's instant and `reserved` is false.
4. Every token of the member is sent the push. `TokenInvalid` deletes that token (FR-083), only
   if the device still holds that same token.
5. Settled by `PushSettlement`, a pure rule: **sent if any phone took it** (provider id
   recorded); otherwise **retried if any phone may yet take it** (throw, the outbox backs off,
   FR-086; the pruned tokens roll back with it and are pruned again on the retry); otherwise
   `FAILED_PERMANENT` with a word of ours in `error_class`: `REJECTED`, `NO_VALID_TOKEN` or
   `NO_DEVICE`. The spec's status list has no "no device" status; `FAILED_PERMANENT` with
   `NO_DEVICE` is the nearest and is not a failure of anything but reach.

The HTTP call inside the transaction holds a pooled connection and the delivery's row lock for
one round trip per phone, bounded by a 2 s connect and a 5 s read timeout (spec §2.2 point 1,
the spec's 5 s). A member has a handful of phones; at the ADR-0035 limit of 60 s a delivery
would need eight unresponsive phones to run out. Chosen over a second queue because the outbox
already has backoff, a dead-letter gauge and a time limit.

**6. At least once, said plainly.** If the commit fails after FCM took a message, the delivery is
retried and the phone gets the push again. The same happens if a delivery overruns its time limit
after a send. `unique (dedupe_key)` makes a second *request* impossible, not a second *send*; doc
07:272's "makes double-sending structurally impossible" is too strong, and the tests prove only
the part that is true: one request per key, and one send per request when the delivery is handed
over twice. A push is a hint and the client is correct without it (doc 25:69), so a rare double
is accepted over the cost of exactly-once. When one phone takes a push and another is
unavailable, the push is `SENT` and the second phone is let go, rather than retrying and sending
the first one a second copy.

**7. The publish-from-a-delivery [inferred] item: checked by running, and it holds.** The
dispatcher's `TransactionTemplate` sets no isolation level, so the delivery runs at the
database's default, READ COMMITTED; `JdbcEventPublisher` asks Postgres in its insert and refuses
any stricter level. `SendPushTest` runs a consumer that requests a push from inside its own
delivery, records `current_setting('transaction_isolation')` (`read committed`), and sees the
`PushRequested` it published delivered and sent on the next run.

**8. FCM HTTP v1, the vendor in one `internal` class.** `FcmPushSender` signs a service-account
assertion with Nimbus (RS256, already on the classpath through Spring Security's JOSE module; no
hand-written crypto, doc 25 D5), trades it at Google's token endpoint for a bearer kept until
five minutes before expiry, and sends through `RestClient`. Classification: FCM's error code
`UNREGISTERED` or `SENDER_ID_MISMATCH`, or a 400 that names the token → `TokenInvalid`. A bare 404
without FCM's error code is `Rejected`: a wrong base URL or a proxy answers one for every token,
and pruning on it would silence every phone (review of #96). 429, 5xx, timeouts, a bearer FCM
refuses (dropped and refetched), and a 429 or 5xx from Google's token endpoint → `Unavailable`;
any other refusal, and the token endpoint refusing the service account → `Rejected`. **[inferred]** FCM names a malformed token
either with a `BadRequest` field violation on `message.token` or only in its prose ("registration
token"); both are read, neither is logged. If FCM words it otherwise, the push is
`FAILED_PERMANENT REJECTED` and the token stays: the safe direction, since no good token is
deleted.

**Bodies go to the client as bytes.** Spring's `DefaultRestClient` prints every object body it
serialises at DEBUG ("Writing [...]"), which here would be the device token and the signed
assertion. Bytes written through `StreamingHttpOutputMessage.Body` pass no converter and are
never printed. `FcmPushSenderTest` raises `org.springframework` to TRACE and fails if the token,
the bearer or the assertion appears in any line; handing the client the map instead turns it red.

**9. Payload: keys and opaque ids only** (spec §5 Q6, FR-084, T-14). `title_loc_key`/
`body_loc_key` (Android) and `title-loc-key`/`loc-key` (iOS) set to `push_<category>_title` and
`push_<category>_body`; `data: {bondId, kind}`; collapse key `<CATEGORY>:<bondId>` on both
platforms, so a held push replaces its predecessor. The deadline travels too: Android `ttl` is
the remaining lifetime at send time (never negative; FCM's default is four weeks) and APNs
`apns-expiration` the deadline itself, so a phone offline when FCM accepted the push is not shown
it after `stale_after` (review of #96). The owner writes the words in the apps'
string tables.

**10. The provider posture is ADR-0017's.** `moyi.notification.push.provider` defaults to `FCM`,
which refuses to start without `fcm.project-id`, `fcm.client-email` and `fcm.private-key-pem`
(a key file's escaped `\n` accepted). `LOG` is asked for by name: the `local` profile, the test
contexts, and **production's `config/deploy.yml`, until a Firebase project exists**, in a line
that says so and how to switch. `LoggingPushSender` prints the category and the Bond, never the
token, and reports `Accepted("not-sent:log")` so a row it settled cannot pass for a delivery.
`PushProperties.Fcm` redacts the private key in its `toString`.

## Consequences

**Positive.** No cycle, and the module meant to be extracted first stays the one with no inbound
knowledge. A logout silences the phone in the same commit. The ledger answers "was this member
told, and if not why" for every push. A provider outage costs retries, never a request's
transaction, and never a push the day after. The token and the assertion are kept out of logs
by construction, not by a pin.

**Negative.** Every N3 consumer must resolve recipients, settings and dedupe keys itself; the
rules for those live in two modules (`gratitude`, `bond`), not one. A send holds a database
connection for up to seven seconds per unresponsive phone. A rare duplicate push is possible and
accepted. Production runs with pushes logged, not sent, until the owner creates the Firebase
project and changes one line, and nothing alerts on that. The `devices.push_token` column and its
constraints survive one more release.

**Neutral.** `notification` gains `common:events`, `common:core`, JDBC and Spring Security's JOSE
module. `ArchitectureTest`'s layer table is unchanged: `PushTokens` and `Notifications` have rules
behind them and are implemented in `service`, which ADR-0017's "Revisit when" foresaw; only
`PushSender`'s adapters are `infra` implementing `api`.

## Alternatives considered

- **Move `EmailSender`'s port into `common` so `notification` may depend on `bond` and
  `identity`** (spec §2.1). Rejected: reverses ADR-0017 decision 1 and makes the module meant to
  be extracted first (doc 05:24, ADR-0001) the one that knows the most. Right if notification
  ever needs to read a member's settings at send time that cannot be passed as values.
- **Keep the token on `devices.push_token`.** Rejected: `notification` cannot read it, and every
  send would need identity to pass the tokens, which means identity in the send path of every
  consumer.
- **Send outside the delivery's transaction** (claim, commit, send, record). Rejected for N1: it
  needs a second state machine for "sending" and a recovery for a crash between send and record,
  for a saving of one held connection per send at this scale. Right if sends become slow or many.
- **Retry a push until a phone that was unavailable takes it.** Rejected: the phone that already
  has it gets it again.
- **Google's auth library for the bearer.** Rejected: several dependencies for one signed JWT and
  one POST; Nimbus is already here.
- **Drop `devices.push_token` in V29.** Rejected in decision 3.

## Owed

- **The migration that drops `devices.push_token`**, its length CHECK and
  `devices_user_push_token_key`, in the first identity change after this one is deployed.
- **Metrics and the span** (doc 11:57, 11:66): `gratitude_push_sent_total{category,status}` and a
  span around the send. Not built in N1; N3 is when anything is sent.
- **Retention**: the ledger's 90 days (doc 07:353) is a job, not built.
- **A session that simply expires keeps its token.** Only an ended session forgets it. The
  refresh-token reaper (doc 07 §7), when built, should forget the tokens of families it removes.
- **The switch to FCM in production**: a Firebase project, a service account, three values in
  Bitwarden and `config/deploy.yml`, and one manual send to a device (M4).

## Revisit when

N2 lands (the seam in decision 5). FCM's error vocabulary is seen to differ from decision 8's
[inferred] reading. A send is seen to take seconds routinely, or the delivery limit is reached.
A second push provider is wanted (APNs direct, Web Push).
