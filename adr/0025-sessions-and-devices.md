# ADR-0025 — Sessions are refresh-token families; devices are built; the access token names its session

**Status:** Accepted · **Date:** 2026-09-24

## Context

FR-007: "a user MUST be able to see all active sessions/devices and revoke any of them individually or all at once." Doc 06 §3.1 lists `GET /auth/sessions` and `DELETE /auth/sessions/{id}` and, in §2, that a resource that is not yours is `404`, "not found or not permitted to know it exists". Doc 07 §2 specifies `devices(id, user_id, platform, push_token, app_version, os_version, last_seen_at, revoked_at)` with `unique (user_id, push_token)`, and doc 04 fixes `platform` as `ANDROID | IOS | WEAR`. ADR-0020 stored the client's description of itself as free text on the refresh token "for now" and named FR-007 as the moment `devices` is built and the string moves. Doc 09 §3 fixes the access token's claims as `sub jti iat exp iss aud scope`. "All at once" already exists as `logout-all`.

What none of them say: what a session *is* in this system, how the list knows which entry is the caller's own, what a device row is when nothing yet identifies a physical device, whether removing `device_info` is an expand/contract migration, and what the login request sends instead of a string.

## Decision

**1. A session is a live refresh-token family.** Login starts a family (ADR-0021); rotation extends it; reuse or logout revokes it. There is exactly one live token per live family, so the caller's live tokens *are* the sessions list, with no new table and no state to drift: `lastSeenAt` is the live token's `issuedAt` (every rotation mints a new one), `createdAt` is the family's first token, and the session id is the family id — opaque, and what `DELETE` names.

**2. The access token carries `sid`, the family it was minted for.** It is the only way the list can mark the current session without a second round-trip, and it is another opaque UUID, no more personal than `jti`. Doc 09's claim list is amended in the corpus; a token minted outside a session — none exist today — simply lacks it, and `CurrentUser.sessionId` is nullable to say so.

**3. `devices` is built as doc 07 specifies, one row per sign-in, for now.** The client sends `device { platform, appVersion, osVersion }` at login, validated at the edge with `platform` as a pattern so an unknown value is a 422 naming `device.platform`; `null` is allowed, and that session has no device. Nothing yet identifies a physical device — `push_token` is Phase 3's — so two sign-ins from one phone are two rows, honestly, rather than one row by a fingerprint nobody asked for. When push tokens arrive, the unique `(user_id, push_token)` doc 07 already specifies is what folds them. `last_seen_at` is touched on every rotation, so it is ready for notifications to read.

**4. `refresh_tokens.device_info` is dropped in the same migration**, replaced by a nullable `device_id` with `ON DELETE SET NULL` — a device row going away must not silently end a family; revocation is explicit. Doc 07 §6's expand/contract rule is for destructive changes in a deployed system; nothing is deployed and nothing carries across, and this sentence is where that is recorded rather than a dead column.

**5. Revocation is one `UPDATE` whose `WHERE` is the authorisation.** `UPDATE refresh_tokens SET revoked_at = now WHERE family_id = ? AND user_id = ? AND revoked_at IS NULL AND EXISTS (a live token in the family)`, under the per-user sessions lock every session mutation takes. The `EXISTS` is what makes "ended" include *expired*: without it a family whose last token had run out still had unrevoked rows to match, and a stale id from a client's cache got a 204 for a session the list no longer showed (Codex's review of #35). Zero rows — somebody else's family, a family that never was, or one already ended or expired — is one `404 NOT_FOUND` whose detail does not repeat the id; `instance` carries the path the caller typed, which is not disclosure. A value that is not a UUID is the same 404, not a 400 about its shape. Revoking the current session is a logout: the refresh token dies and the access token lives out its fifteen minutes seeing an empty list, which is doc 09 §3's stated trade-off, unchanged.

**6. The login request changes shape, and the PR is labelled `breaking-api-change` — although oasdiff did not call it breaking.** `deviceInfo: string` is gone; `device: object` replaces it. By oasdiff's rules a *removed optional request property* is not a break (a client that still sends it is ignored, not refused) and an added optional one is not either, so the diff job passed on its own. The label was kept because a generated client's *type* changes and a reviewer should read it as a contract change; the gate's first refusal is still owed to a future PR that removes a response field or adds a required request one. Recorded so nobody reads "labelled" as "the gate fired".

## Consequences

**Positive.** FR-007 complete with `logout-all`. No new source of truth: the list is derived from tokens that already had to be right. `devices` exists for Phase 3 with the columns and the uniqueness doc 07 wants. The sessions endpoints inherit the bearer, the per-user rate bucket, and the 404-not-403 rule without new code for any of them.

**Negative.** Two sign-ins from one phone show as two sessions until push tokens fold them; the Account screen that lists them (a recorded Figma gap) should expect that. One more claim in the token. `familyStartsOf` is a `GROUP BY` over the user's tokens on every list — a handful of rows per person, and the reaper (doc 07 §7) keeps it so.

**Neutral.** `DeviceResponse` echoes what the client sent about itself, nothing more. Tags and the OpenAPI document regenerate.

## Alternatives considered

- **A `sessions` table.** Rejected: it would duplicate the family's lifecycle and drift from it; the family already is the session.
- **Marking the current session by the refresh token in the request.** Rejected: `GET` with a credential in the body, and a second secret on a read path.
- **A client-generated installation id to fold devices.** Deferred: it is a fingerprint the product has not asked for, and the push token does the job when it exists.
- **Keeping `deviceInfo` beside `device` for compatibility.** Rejected: there is no client to be compatible with, and the breaking-change gate exists to be used.
- **`403` for another user's session.** Rejected by doc 06 §2 and T-02: it confirms the id exists.

## Revisit when

Push tokens land (fold devices on `(user_id, push_token)`, fill `revoked_at`). The Account screen is designed (the list's ordering and copy). A "sign out everywhere else" affordance is wanted (revoke all families but `sid`, which is one predicate away).
