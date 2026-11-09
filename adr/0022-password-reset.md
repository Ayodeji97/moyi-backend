# ADR-0022 — Password reset: a verification token with a different purpose, and a different page

**Status:** Accepted · **Date:** 2026-09-23 · **Amended:** 2026-11-08 (reset by a six-digit code, #135)

## Context

FR-004: "Password reset MUST be available via emailed single-use token, TTL 1h. Resetting MUST revoke all refresh-token families." Doc 06 §3.1: `POST /auth/forgot-password` is `202 always`; `POST /auth/reset-password {token, newPassword}` revokes all families **and** sets `tokens_invalid_before`. T-17 adds: "single-use, 1-hour TTL, invalidated on use; reset revokes all sessions; notification email to the old address." Doc 07 built `verification_tokens` with a `purpose` column for exactly this.

This slice arrived a phase early — it was Phase 1's last item and was built alongside login — and its first version had two defects the tests could not see: the reset link pointed at the *verification* landing page, so every reset link would have been dead on arrival, and the person was not told afterwards. Both were invisible because the test inserted a reset token by SQL instead of reading the link out of the email.

## Decision

**1. A reset token is a `verification_tokens` row with `purpose = PASSWORD_RESET` and a one-hour TTL.** The TTL is a property of the purpose (`VerificationPurpose.ttl`), as ADR-0018 arranged for. V7 widens the `CHECK`. Every lookup is by hash **and purpose** — a reset token presented to `/verify-email` is "not recognised", and vice versa — which the purpose-scoped lookup added in #27's review already guaranteed and this slice now tests from the outside.

**2. The reset link has its own landing page.** `moyi.identity.verification.reset-link-base-url`, required and absolute like the verification one, because the app has to `POST` the token to a different endpoint. The composer chooses the page by purpose. The test reads the link out of the recorded email and follows it; that is the test that would have caught the first version.

**3. `forgot-password` is `202` with an empty body for every address, and issues a token only for an account that could sign in.** Unknown, suspended and deleted addresses get the same nothing (`states.md` §1b: "identical whether or not the account exists"). FR-002's unverified account *can* reset — a person who forgets the password before verifying is otherwise locked out of their own address.

**4. `reset-password` hashes outside the transaction and consumes inside it.** The new password is checked at the edge like a registration password — shape and breach corpus (ADR-0012), so a reset cannot install `password` — and the Argon2id hash is computed before the transaction opens, as `RegisterUser` does. Inside: the conditional consume (one winner, ADR-0018's shape), the credential update with `failed_attempts` and `locked_until` cleared, every refresh family revoked, `tokens_invalid_before` bumped, and a `SecurityNotice.PasswordChanged` event published. A refused new password does not spend the token.

**5. The old address is emailed after the commit.** T-17's notification, through the same `AFTER_COMMIT` listener as the reuse notice (ADR-0021). If the reset was not the owner's doing, this is how they find out, and the email says what to do.

## Consequences

**Positive.** One token mechanism for two purposes, with the purpose enforced at every read. A reset signs every device out, including the one that reset it — and the screen says so before the action (`states.md` §1b). A person whose account was reset by someone else is told within seconds.

**Negative.** A reset is the one flow that must work for a locked-out person, and it depends on the email arriving; the resend affordance does not exist for reset links, so a lost email means asking again after an hour, or immediately with a new token — earlier reset tokens stay live until their own hour, as verification tokens do. Rate limiting of `forgot-password` (FR-012: 3 per hour per email) is the rate-limiting slice's.

**Neutral.** The reset email's copy is English only, like the verification email, and shares its localisation deferral.

## Alternatives considered

- **A separate `password_reset_tokens` table** (Chirp). Rejected: doc 07 designed the shared table with a purpose column, and the lifecycle — issue, present once, expire — is identical.
- **One landing page for both link kinds, dispatching on a query parameter.** Rejected: the pages ask the person for different things (nothing, versus a new password), and a token's purpose is enforced server-side regardless.
- **Skipping the notification email.** Rejected by T-17.

## Revisit when

FR-005 (email change) arrives — a third purpose, a third page, and the first case where the *new* address and the *old* address both need an email. FR-012 lands — bound `forgot-password` per email.

## Amendment — 2026-11-08: reset by a six-digit code (#135)

The reset email carries the same six-digit code as the verification email, on the same row as its link, by ADR-0018's amendment of the same date; everything there applies, with `PASSWORD_RESET` for the purpose and `422 PASSWORD_RESET_TOKEN_INVALID` for every refusal of a code (never 410).

**1. `POST /auth/reset-password` takes `{token, password}` or `{email, code, password}`.** The new password is judged at the edge first (shape and breach corpus, decision 4), so a refused password neither spends the code nor counts against it.

**2. Three steps, where the link has two.** The code is checked, and a wrong one counted and committed, in a short transaction under the row lock; the Argon2id hash is computed outside any transaction, as before; `ApplyPasswordReset` then spends the row by id with the conditional `UPDATE`, and does what decision 4 lists. Between the check and the spend the row is not held, because holding a row lock across an Argon2id hash would hold a connection for it: a link presented in that gap spends the row first, and the code path answers its one 422. `ApplyPasswordReset.apply` now takes the token it was handed and returns `false` when it lost that race, so each path answers in its own terms (410 for the link, 422 for the code).

**3. A reset takes back the code of any later reset email**, since the password it would reset has just changed. Earlier reset *links* still stay live until their hour, as decision 3's consequence said; that is unchanged.

**4. A forgot-password email is a new code.** It retires the previous reset code; three an hour per address (FR-012) bounds how often, and ADR-0023's `auth:code:email` bounds how many codes may be tried.

