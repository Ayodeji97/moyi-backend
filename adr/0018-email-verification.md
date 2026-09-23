# ADR-0018 — Email verification: hashed tokens, a landing page, and a listener that is not the outbox

**Status:** Accepted · **Date:** 2026-09-23

## Context

FR-002 is one sentence: *registration MUST send a verification email; unverified accounts may sign in but MUST NOT create or join a Bond; token TTL 24h, single-use.* Doc 06 §3.1 gives the two endpoints — `POST /auth/verify-email {token} → 200` and `POST /auth/resend-verification → 202 always` — and doc 07 §2 gives the table: `verification_tokens(id, user_id, purpose, token_hash unique, expires_at, consumed_at)`. That is everything the corpus says. Building it raised six questions the corpus does not answer, and the course's implementation (Chirp §7) answers four of them in ways doc 25 D5 would not accept.

## Decision

**1. Only the SHA-256 of the secret is stored, unsalted.** Doc 07 already says `token_hash`; this records *why unsalted is correct here and would be wrong for a password.* The secret is 256 bits of CSPRNG output (base64url, 43 characters), so there is no dictionary to precompute and nothing a salt would defend against; the hash exists so a database read — a backup, a leaked dump — yields nothing presentable. A password gets Argon2id precisely because its input is guessable. Chirp stores the plaintext and indexes it.

**2. The emailed link lands on the marketing site, and the app `POST`s the token.** Never a `GET` on this API that verifies directly. Corporate mail gateways and link-scanning clients fetch every link in an email before the person sees it; a single-use token consumed by a scanner is a link that says "expired" the moment a human clicks it. The landing page is the same arrangement as `/i/{code}` for invites (doc 02 J1); its base URL is `moyi.identity.verification.link-base-url`, required, with no default. Chirp's `GET /verify?token=` has this failure mode.

**3. Consumption is a conditional `UPDATE`, not a read-then-write.** `UPDATE … SET consumed_at = :now WHERE token_hash = :h AND consumed_at IS NULL AND expires_at > :now` returns the row count, and only the caller that gets `1` has verified anything. Two concurrent presentations of one token both read it as live; the database serialises the two updates and the second finds the predicate false. The token is read first so the *reason* it cannot be used can be told apart — unknown versus spent — and the update decides the winner. Chirp loads, checks, saves. Both the persistence test and the endpoint test run the race.

**4. Two error codes, on two statuses.** `422 VERIFICATION_TOKEN_INVALID` when the hash matches nothing — the body parsed, the value in it was never issued, which is doc 06 §2's definition of 422. `410 VERIFICATION_TOKEN_EXPIRED` when it was real and is spent, whether by time or by use; doc 06 already uses 410 for a spent invite, and `states.md` §1 draws one recovery state for both because the person's next step is the same. The 410's `detail` is that state's copy verbatim. Success is `200` with no body: there is nothing to say that the status does not.

**5. The email is sent by an `AFTER_COMMIT` listener, asynchronously, and this is explicitly not the outbox.** `RegisterUser` and `ResendVerification` publish a `VerificationRequested` event *inside* their transaction; `SendVerificationEmail` is a `@TransactionalEventListener(AFTER_COMMIT)` marked `@Async`, so a rollback sends nothing, a commit sends once, and the response does not wait for the provider. ADR-0008 names this arrangement as acceptable for non-critical events and defines the gap — a process death between commit and listener loses the email with no retry. The verification email is non-critical under that definition because the recovery path is designed in: screen 3's "send it again". A reveal notification has no such button, which is why *that* goes through the outbox; when the outbox lands, the event write replaces this listener and nothing upstream changes.

The `@Async` is also a security control, not only a latency one. The duplicate-registration path publishes no event; a synchronous send would make a new registration slower than a duplicate by one provider round-trip, reopening the T-18 timing oracle that ADR-0015's identical response and the always-hash rule close.

**6. Resend issues a new token and leaves the old ones live; a successful verification retires the rest.** Chirp invalidates every outstanding token on each resend. Here they stay valid until their own 24 hours, because the common reason for pressing "send again" is that the first email is *late*, and a late link that says "expired" on arrival reads as broken. Each is 256 bits; several live at once cost nothing. `VerifyEmail` deletes the person's other live tokens when any one is used (doc 07 §7: kept until "consumption or expiry", and those were neither). `resend-verification` returns `202` with an empty body whether the address is unknown, verified or pending; only pending does anything.

## Consequences

**Positive.** A leaked table verifies nobody. A mail scanner cannot spend a link. Two clicks cannot verify twice, and the test proves it rather than the comment. The registration response time is independent of the email provider and identical on the duplicate path. The client switches on two codes and shows `states.md`'s copy without parsing English.

**Negative.** The lost-email window between commit and listener is real and is accepted, not solved; the outbox is the fix and is a later slice. The resend path's timing differs from the do-nothing path by one `INSERT` — sub-millisecond against network jitter, and bounded by the per-IP rate limit when that lands (FR-012), but not identical. There is no per-email resend cooldown yet; `states.md`'s "you can ask for another in a minute" is a promise the rate-limiting slice has to keep. Consumed and expired rows accumulate until the `scheduling` module's reaper exists.

**Neutral.** The email is English only; the user's locale travels in the event so localisation (doc 16 §3, Phase 1) changes the composer and nothing else. The email has no design surface in the Figma file — recorded in `states.md` as a gap owed before Phase 6.

## Alternatives considered

- **`GET /auth/verify?token=` on the API**, as Chirp does. Rejected: spends tokens on scanner prefetch, and a `GET` with a side effect. No future condition makes this right.
- **Sending the email synchronously from the service.** Rejected: sends for a transaction that may roll back, blocks the response on the provider, and reopens the T-18 oracle.
- **Building the outbox now.** Rejected for scope, not on merit: it is its own slice with its own poller, table and failure modes, and the verification email has a recovery path the outbox exists to provide for events that do not. Becomes right the moment the first event *without* a recovery path exists — the reveal notification, Phase 3.
- **Invalidating old tokens on resend.** Rejected above. Would become right if tokens were short or guessable, which they are not.
- **One error code for every failure.** Rejected: the client would show "expired, we can send a new one" for a mistyped token, and "send a new one" is not the fix for a typo.
- **Emailing the existing account holder on a duplicate registration** ("someone tried to sign up with your address"), which OWASP recommends. Deferred, not rejected: it is a good notification and it would also equalise the two paths' work. It is a new outbound message with its own abuse surface, and belongs with the rate-limiting slice that bounds that surface.

## Revisit when

The outbox lands (replace the listener with an event write). Rate limiting lands (add the per-email cooldown, and reconsider the duplicate-registration notice). FR-004 and FR-005 arrive (widen the `purpose` CHECK; the token mechanics are shared). Or a real user reports a link that "expired" on first click — that is the scanner problem appearing on the landing-page side, and would mean the page itself is fetching.
