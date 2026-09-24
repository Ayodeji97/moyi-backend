# ADR-0021 — Refresh rotation, reuse detection, and what a family revocation tells the person

**Status:** Accepted · **Date:** 2026-09-23

## Context

Doc 09 §3 specifies the refresh token in five lines: 256 bits of CSPRNG output, stored hashed, thirty days, rotated on every use, and "presenting an already-rotated token means it was stolen (either by the attacker or the victim). The entire token *family* is revoked immediately, and the user is emailed." Doc 06 §3.1 gives the endpoint and the code: `POST /auth/refresh` returns a new pair; reuse returns `401 TOKEN_REUSE_DETECTED`. Doc 13 tells the client what to do on that code: wipe local credentials, clear the cache, route to sign-in with an explanation. Doc 25 D5 calls this "the mechanism that makes stateless auth safe, and the single most probed backend topic in interviews."

The first implementation had the rotation and the family revocation and missed the two halves that face outward: every refresh failure was `UNAUTHENTICATED`, so the client could not tell a stolen session from an expired one, and nobody was emailed. It also revoked the family of any account whose status was not `ACTIVE`, which signed unverified users out on their first refresh.

## Decision

**1. Rotation is a conditional `UPDATE`, and the successor is inserted in the same transaction.** `UPDATE refresh_tokens SET rotated_at = :now, replaced_by = :successor WHERE id = :id AND rotated_at IS NULL AND revoked_at IS NULL AND expires_at > :now`. Row count `1` means this request rotated it; `0` means somebody else did a moment ago. The same compare-and-set shape as verification consumption (ADR-0018) and for the same reason: two concurrent presentations must have exactly one winner, and only the database can arbitrate.

**1a. Every mutation of a user's sessions first takes a per-user advisory lock.** `pg_advisory_xact_lock(1, hashtext(user_id))`, transaction-scoped, taken by rotation, `logout`, `logout-all`, password reset and login's family issue, before the state they act on is read. The compare-and-set in (1) is necessary and not sufficient: a family revocation is a single `UPDATE` over the rows in its snapshot under READ COMMITTED, and a rotation committing concurrently can insert a successor that snapshot never saw — a "revoked" family with one live token in it, held by whoever was refreshing, which after a reuse may be the thief. The Codex review of the first version found this; a two-transaction test now reproduces it and fails when the lock is removed. Per user rather than per family so there is one lock and no ordering to get wrong; a user has a handful of concurrent sessions, so the contention is nil.

**2. Three outcomes, three codes.** A token whose `rotated_at` is set — or whose `UPDATE` in (1) returned `0` — is a **reuse**: the family is revoked, a WARN names the user and family, the person is emailed, and the response is `401 TOKEN_REUSE_DETECTED`. A token that is unknown, expired, or in a family already revoked is **invalid**: `401 REFRESH_TOKEN_INVALID`, nothing revoked, nobody emailed — a second presentation of a stale token after the family is gone is not a second theft. A live token is **rotated**. The two 401 codes exist because the client's correct responses differ: sign in again, versus sign in again *and* explain that the session may have been stolen.

**3. Strict, with no grace window.** Two legitimate concurrent refreshes of one token — two tabs, a retry after a timeout — will trip reuse detection and end the family. That is the trade doc 09 makes and doc 13 pays for with a client-side mutex so the app never sends two. The alternative, a short window in which the immediate successor is accepted from the same family, weakens the guarantee exactly where it matters (a thief who acts within the window is indistinguishable from a retry) and is recorded here so nobody re-derives it as an improvement.

**4. The email goes out after the commit, through the same listener shape as verification.** A `SecurityNotice.SessionReuseDetected` event is published inside the revoking transaction and delivered by an `AFTER_COMMIT` `@Async` listener to `notification`'s `EmailSender` (ADR-0017), keyed by family id so a retry cannot send twice. The message is written for both readers doc 09 names: the victim, who needs to know to change their password, and the person holding the stale copy, who needs to know why they were signed out. **It is honest about scope**: that sign-in ended; other devices and outstanding access tokens did not (the first draft said "every device has been signed out", which was false and would have let a victim believe the account was secured). Changing the password is what signs everything out, and the email says so. The password-changed notice is keyed by user *and* change time, so a second reset is a second email and not a "retry" the provider swallows. Under ADR-0008 this is a non-critical event: the *protection* is the revocation, which has already committed; the notice is the part a process death could lose.

**5. FR-002 applies to refresh as it does to login.** An account that can sign in (`ACTIVE`, `PENDING_VERIFICATION`) can refresh. One that no longer can has its family revoked on the next refresh — as invalid, not as a reuse, because nobody did anything wrong with the token.

**6. `logout` revokes the presented token's family and is idempotent; `logout-all` revokes every family and bumps `tokens_invalid_before`.** `logout` needs no bearer token — the refresh token is the credential — and returns `204` whether or not the token existed, so it cannot be used to test tokens. `logout-all` needs a bearer token and, through ADR-0019's revocation validator, kills outstanding access tokens too.

## Consequences

**Positive.** A stolen refresh token is worth at most one rotation before the theft is detected, and detection is total: the thief's chain and the victim's are one family, and the lock means "revoked" means revoked. The person finds out. The client can distinguish the case that needs an explanation from the one that does not.

**Negative.** Legitimate concurrent refreshes end sessions; the client mutex is load-bearing. The advisory lock serialises a user's session mutations, so a user's own devices refreshing at the same instant queue behind each other for a few milliseconds. Every family revocation on reuse sends an email, which is also an abuse surface — someone holding a stolen token can make the victim receive one email per family they compromise, which is the least of that victim's problems. The `iat` one-second resolution from ADR-0019 means an access token issued in the same second as `logout-all` survives it.

**Neutral.** `used_at` from doc 07 is not populated: `rotated_at` is the moment a token is used, and one column saying so is enough. `replaced_by` is populated and is what makes a family readable as a chain.

## Alternatives considered

- **One `UNAUTHENTICATED` for every refresh failure** (the first implementation). Rejected: doc 06 names `TOKEN_REUSE_DETECTED`, and doc 13's client behaviour depends on it.
- **No email on reuse.** Rejected: doc 09 requires it, and it is the only way the victim learns.
- **A reuse grace window.** Rejected in (3).
- **Delete-and-reissue instead of mark-rotated** (Chirp's model). Rejected: a deleted token cannot be recognised as *reused*, so reuse detection is impossible — the stale token is simply "not found", the same answer as garbage.
- **Refresh tokens as JWTs** (Chirp). Rejected: an opaque secret stored as a hash can be revoked; a signed token cannot be un-signed.

## Revisit when

The client team reports legitimate reuse trips in the field (measure before adding a window). Doc 07 §5's Redis denylist for single-session access-token revocation is wanted (it would make `logout` kill the access token too). FR-007's session list lands (`revoked_reason`, currently unused, is where "revoked by user from settings" goes).
