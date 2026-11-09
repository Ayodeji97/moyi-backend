# ADR-0057 — In-app support messages: a ticket for a person, an acknowledgement that never repeats it, and the operator's queue in the database

**Status:** Accepted · **Date:** 2026-11-08 · **Deciders:** Daniel, for FR-102 and doc 06; decisions
1, 3, 6 and 7 were made without him and are his to revisit (issue #129 left them to the backend).

## Context

FR-102 (MUST): "An in-app support contact route MUST exist: `POST /support/messages` creates a ticket
visible in the admin console and sends an acknowledgement email. Rate-limited to 5/day/user." Doc 06
§3.7 sketches `{subject, body}` and §4 names the bucket `support:create`, per user, five a day. The
admin console that would read the tickets is Phase 5 (doc 15); until it exists, the report queue
(ADR-0053) shows the shape: rows written by the application and never read back by it, read by the
operator through database functions that record each look, from `scripts/moyi`.

The moyi-app client asked for the route in issue #129 and left four things open: who may reach it,
the lengths, `201` or `202`, and the key. Two rules shape the answers: what a person writes must not
leak (CLAUDE.md, the entry-text rule), and an address registration has not confirmed may not be the
account holder's (ADR-0015).

## Decision

**1. Signed-in accounts only.** `POST /api/v1/support/messages` is behind the bearer, as everything
outside the public auth endpoints is. FR-102 says "in-app" and "5/day/user", and a per-user limit
needs a user. A signed-out person (locked out, or who cannot sign in) is **out of scope**: they write
to the published support address, which the client already opens as a `mailto:` (issue #129). A
signed-out route would need its own per-IP and per-address buckets, a CAPTCHA-shaped answer to abuse,
and an address the sender typed and nobody confirmed; none of that is FR-102's.

**2. `201 Created` with `{id, createdAt}`.** The ticket exists when the answer is sent, so `201`, not
`202`: what is deferred is the email, which the client is not waiting for. Nothing of what was
written is returned.

**3. `{subject, body}`: subject 1 to 120 characters, body 1 to 5000, characters being code points.**
One rule for both fields (`support.domain.SupportText`'s `supportText`), which the edge asks
(`ValidSupportSubject`, `ValidSupportBody`) and V45's `CHECK`s back: not blank (Kotlin's `isNotBlank`),
no U+0000, no unpaired surrogate, at most the limit in code points, the unit `char_length` counts, so
the two lines agree on every string. **Code points, not the entry's graphemes** (ADR-0031 ruling 1):
graphemes are counted for the text a member sees beside a counter and their partner reads; this is
read by an operator, and a database `CHECK` can state code points and cannot state graphemes. This is
ADR-0053 decision 3's choice for the report's detail, for the same reason. Kept exactly as sent,
nothing trimmed. A refusal is `422 VALIDATION_FAILED` naming the field, and never repeats the value.

**4. `Idempotency-Key` optional** (`@Idempotent(required = false)`, as `PATCH /entries/{id}`). A
client that sends one is not filed twice for a retry after a lost answer: the key, the ticket and the
event commit together (`IdempotentExecution.once`), and a replay is `201` with
`Idempotency-Replayed: true` and the same id and time, re-read, authorised as the sender's, with no
second email. **A replay spends one of the five a day too** (decision 9): the bucket is taken before
the handler, and so before the key is looked up. A retried fifth message can therefore be `429`
rather than its replayed `201`; the client waits for `Retry-After` and sends the same key again,
which returns the same ticket and files nothing new. A client that sends none still files: a duplicate support message costs an operator a
second read, and requiring the key would turn a client that forgot it into a person who cannot reach
support. `ResultKind.SUPPORT_TICKET`, with V47 widening `idempotency_keys`' `CHECK`.

**5. The acknowledgement is sent after the commit and never repeats the message.** The filing
transaction publishes `SupportTicketFiled` (ids only); `AcknowledgeSupportTicket` hears it
`AFTER_COMMIT` and `@Async`, as identity's `SendSecurityNotice` does, with a wider accepted gap: **the
acknowledgement is best effort, sent once and never retried.** A process death between the commit and
the send loses it, and so does a provider that refuses it or cannot be reached (a 5xx, a 429, a
timeout: `AccountMailOutcome.FAILED`, logged by identity and by ticket id here, not queued again).
The ticket, which is what matters, is kept either way, and the client is never promised an email. The email says the message arrived and a person will reply to this address, gives the ticket
id as a reference, and says why the words are not in it. **Neither the subject nor the body is in
it**: an email passes through a provider, a mailbox and whoever else reads that mailbox. It is a
transactional email (one message, about something the person just did, nothing to unsubscribe from),
keyed `support-ack-<ticketId>` so a provider retry cannot deliver it twice.

**6. Identity writes the email; the support module never learns the address.** `identity.api`
gains `AccountMail.sendToAccountHolder(userId, AccountMessage)`: the caller names the account, identity
reads the address and sends through `notification.api.EmailSender`. `UserDirectory` says "no email"
on purpose (ADR-0026), and this keeps it true. **Only a confirmed address is written to**
(`AccountMailOutcome.NOT_VERIFIED` otherwise): registration does not confirm an address (ADR-0015),
so an unconfirmed one may be a stranger's, and a message anyone can make this system send to it is the
abuse ADR-0015 guards against. An unconfirmed account's message is still filed and still `201`; it
gets no email. The operator sees whether the address was ever confirmed before answering (decision 7).

**7. A small `support` module owns `support_tickets` (V45), and the operator reads it through the
database.** Not identity (a ticket is not an account), not notification (a leaf that knows no user's
address and should stay one), not `admin` (#131's, and switched off). Its layers are the usual ones;
it depends on `identity.api` alone. The table: `id`, `user_id` (no foreign key: it crosses a module),
`subject`, `body`, `status` (`OPEN`, `CLOSED`), `created_at`, `closed_at`. The words are written by
the application and never read back by it: no response, log line or exception can carry them. The
operator's three functions, each writing a row in `support_ticket_log` (who looked, who closed, their
note; never the words): `support_ticket_list` (ids, sender, how long each field is, when; never the
words), `support_ticket_read` (the words, and the address to answer at, read from identity's `users`
at that moment rather than copied into the ticket, with whether it was ever confirmed) and
`support_ticket_close` (empties the subject and body, keeps the record). `scripts/moyi support
list|show|close` names which to run, under `MOYI_OPERATOR`. **The words do not outlive the answer in
the live database**: closing empties them (V45's `support_tickets_text_kept_check`), as a decided
report's detail is (ADR-0053 decision 7), with the same caveat: dead tuples until autovacuum, and the
nightly backups' 30 days (ADR-0043).

**8. What is owed when an account is deleted.** Account deletion is not built. When it is, an erased
account's tickets must be emptied (subject and body to null, closed) and its `user_id` kept or
anonymised as the deletion's ADR decides; `support_tickets_user_idx` is there for that sweep. Until
then a ticket names its sender by id only, and `support_ticket_read` shows no address for an account
that is gone.

**9. `support:create:user`, five a day per account** (doc 06 §4; `RateLimitBucket.SUPPORT_CREATE_USER`,
greedy, so one back every 4.8 hours). Spent before the body is read, as every bucket here is, so a
`422` costs one too; the sixth is `429 RATE_LIMITED` with `Retry-After` and the `X-RateLimit-*`
headers. Each message is a row in a person's queue and an email in the sender's mailbox, and five
bounds both.

**10. The words are never logged.** `SupportSubject` and `SupportBody` print their length only;
`SupportMessageRequest.toString` redacts both; `AccountMessage.toString` prints the subject (always
ours) and key, never the text; the services log ids and outcomes. `SecretsNeverLoggedTest` sends a
support message (keyed, and a refused one) with every logger at TRACE and the local email sender
printing the acknowledgement in full, and finds neither field in any line; `SupportMessagesTest`
captures the output of a filing and of every `422`.

## Consequences

**Positive.** FR-102 is met: a member reaches a person from the app, is told it arrived, and what they
wrote is read by one person, recorded as read, and deleted once answered. The client's generated code
gets one additive operation.

**Negative.** A signed-out person cannot use the route (decision 1). An unconfirmed account gets no
acknowledgement (decision 6), and the client is told `201` either way. The queue is SQL functions
until the Phase 5 console. `support_ticket_read` reads identity's `users` table: a database function
crossing a module, as V39's call media's functions, accepted for the operator's tool and not the
application's. **Two migrations, V45 and V47**: the `idempotency_keys` `CHECK` is common:web's table (V33's and
V37's reason for being there), so its widening is a migration in common:web's sequence, V47, claimed
for it (a decimal V45.1 was tried first and ruled out by the coordinator on 2026-11-08). A parallel
change that widens the same `CHECK` (#127) must list `SUPPORT_TICKET` too, whichever lands second.

**Neutral.** A new module (`modules/support`), wired in `app` and listed in `ArchitectureTest`. One
route, one bucket, one `ResultKind`, one `identity.api` port. No error code is added.

## Alternatives considered

- **A signed-out route with an email field and a per-IP limit.** Reaches the locked-out person, at
  the cost of an unconfirmed address to answer and a new abuse surface; the `mailto:` already reaches
  them. Right when support load shows the `mailto:` is not enough.
- **`202 Accepted`.** Would say the ticket may not exist yet; it does.
- **Echoing the subject in the acknowledgement.** Friendlier, and puts the sender's words in a
  provider's logs and a mailbox; the reference id serves the same purpose.
- **Graphemes for the limits.** Matches the entry; cannot be backed by a `CHECK`, and nobody is shown
  a counter of support text beside a partner's.
- **Required `Idempotency-Key`.** The safer write, and a client that forgot it could not reach support.
- **Copying the address into the ticket.** Easier to answer; a second copy of an address to erase,
  and stale the moment it changes.
- **Owning the table in identity.** Saves a module and a port; makes the account module the home of
  everything a person ever sends.

## Revisit when

The Phase 5 console is built (it reads tickets through a `support.api` port, and the functions may
go); account deletion is built (decision 8); support load or a client asks for signed-out support; or
the owner rules otherwise on decisions 1, 3, 6 or 7.
