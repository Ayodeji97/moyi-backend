# ADR-0020 — Login: one refusal, one cost, a silent backoff

**Status:** Accepted · **Date:** 2026-09-23

## Context

FR-003 wants a short-lived access token and a rotating refresh token from sign-in. Doc 06 §2 fixes the security rule — "authentication failures return an identical response and take an identical amount of time whether the email exists or not" — and doc 09 §3 names the mechanism for the timing half: a dummy Argon2id verification on the unknown-address path. T-03 asks for "account lockout with exponential backoff"; T-18 warns that a per-account control that only a real account can trigger is itself an enumeration oracle. FR-002 says an unverified account may sign in.

The first implementation of this slice (PR #32 as opened) got the dummy verify right and four things wrong, and each was wrong in an instructive direction: unverified accounts could not sign in; the lockout was a fixed fifteen minutes with the counter reset when a lock expired, which is a lockout but not a backoff; the refusal code was the resource server's `UNAUTHENTICATED`; and the constant-time property was tested only by asserting a 401, not by counting verifications. Doc 12 §3.3 is explicit that a timing comparison is not a test.

## Decision

**1. Every refusal is `401 INVALID_CREDENTIALS` with an identical body and no `WWW-Authenticate`.** Wrong password, unknown address, malformed address, locked account, suspended or deleted account. The web layer never learns which. `INVALID_CREDENTIALS` rather than `UNAUTHENTICATED` because no bearer credential was presented — this is a form, not a resource — and because a client's response to "your password is wrong" (an inline error) differs from its response to "your session is gone" (a redirect). No `WWW-Authenticate` for the same reason.

**2. Every refusal costs one Argon2id verification.** A known address is verified against its hash; an unknown or malformed one against a fixed dummy hash computed once at startup; a locked account is verified and the result discarded. The verify runs outside every transaction, under the same permit bound as registration's hash (NFR-005a). **The test is a counter on the hasher**, wrapped not mocked: one `matchesDummy` on the unknown path, one `matches` on the known one, zero of the other. A stopwatch test would be satisfied by noise.

**3. FR-002 is honoured: `ACTIVE` and `PENDING_VERIFICATION` sign in.** The restriction on an unverified account is that it may not create or join a Bond, and that belongs to the Bond module. Screen 3's "Check again" depends on this: the app signs in after registering and reads `GET /me`. Every other status is refused as in (1); a suspended person with the right password is told their credentials do not match, which is wrong for them and right for everyone else until a suspended-account screen exists — recorded as a gap.

**4. Lockout is silent and exponential.** Five consecutive failures lock the account for one minute; each further failure after a lock expires doubles the next lock, to a one-hour cap; attempts made during a lock are refused without being counted; only a successful sign-in resets the counter. The rule lives once in Kotlin (`LockoutPolicy`) and once in a single native `UPDATE` that increments and locks atomically, and two tests pin the two to the same numbers. Silent, per T-18: the locked response is (1). The visible "wait N minutes" that `states.md` draws is FR-012's per-IP `429 Retry-After`, which is the rate-limiting slice's.

**5. A device is a string, for now.** `deviceInfo` is the client's free-text description of itself, stored on the refresh token. Doc 07's `devices` table, with `push_token` and the `device_id` binding doc 09 mentions, arrives with FR-007's session list and the push-token registration that needs it; a migration then moves this column. A table with one populated column and no reader was judged worse than a documented deferral.

## Consequences

**Positive.** Nothing about the response or its timing answers "does this address have an account". A slow attacker who waits out each lock meets a longer one every time. An unverified person can reach the app and see their own state. The two statements of the lockout rule cannot drift unnoticed.

**Negative.** The count-a-failure write happens only on the known-account path, so the two paths differ by one `UPDATE` — a millisecond against a 150 ms verify and the network's jitter, and bounded by the per-IP limit when it lands. A locked account's owner is not told they are locked. The `devices` deferral means `refresh_tokens.device_info` is a column doc 07 does not have, and doc 07 is amended to say so.

**Neutral.** Spring Security's `AuthenticationManager` / `DaoAuthenticationProvider` / `formLogin` machinery is not used: it is built around a session and a redirect, its failure handling does not know about a dummy verify or a silent lock, and the response body is ours. The resource-server half (ADR-0019) is Spring Security's; the login form is not. The deprecated OAuth2 password grant is the alternative nobody should pick and is not considered further.

## Alternatives considered

- **A fixed 15-minute lockout, counter reset on expiry** (the first implementation). Rejected: it is a lockout, not a backoff, and T-03 asks for the latter by name. Becomes right for nobody.
- **A visible lockout response** (`423 Locked`, or a `Retry-After`). Rejected by T-18: only a real account can be locked, so the response is an oracle.
- **Refusing unverified sign-in** (the first implementation, and Chirp's behaviour). Rejected by FR-002's text and by screen 3's design.
- **Spring Security's form login.** Rejected above. Would become right if the API grew a browser-session admin console — which is Phase 7's admin module, and would be a separate filter chain.
- **Building `devices` now.** Deferred, with the migration path named.

## Revisit when

FR-007 (session list) or push-token registration lands — build `devices`, move `device_info`. FR-012 lands — the per-IP bucket is what makes the visible "wait" honest. A suspended-account screen is designed — refuse suspended accounts with a code of their own only if the product decides they should know.
