# Phase 2 — Bonds & Invites: design

**Date:** 2026-09-24 · **Status:** approved by Daniel 2026-09-24 ("Yes let move on") ·
**Scope:** doc 15 §3 Phase 2, doc 03 Epic B (FR-020 – FR-030), milestone M2.

This is the design the slices are built from. Where the planning corpus already
decides something, this document cites it and does not re-argue it. Where the
corpus is silent or contradicts itself, this document decides, says so, and the
slice's ADR records the reasoning. Anything here that turns out wrong is fixed
by amending this file in the same PR as the code, not by drifting from it.

---

## 1. Goal and non-goals

**Goal.** Two verified accounts can pair into a Bond through a shareable code,
and no third account can see that the Bond exists (M2). Around that core:
the Bond's settings, the two-party changes (anchor timezone, deletion), and
ending a Bond decently (leave, block).

**Non-goals, deliberately, with where they go:**

| Not in Phase 2 | Why | Where |
|---|---|---|
| Bond-days, entries, streak, `today` | The core loop is Phase 3. `GET /bonds` returns bonds and members now; streak and today's status are added to the same response later, additively | Phase 3 |
| Pause semantics when a member is suspended or in the deletion window (doc 04 §8.1/8.2) | Needs identity to publish events across the module boundary — the transactional outbox (ADR-0008), not yet built. Nothing in Phase 2 suspends or deletes an account either | Phase 5, with the outbox |
| The 90-day reaper for Bonds nobody joined, the 60-day warning email (OQ-08) | A scheduled job with ShedLock and an email; the state it reaps exists from B1 | Scheduling module, Phase 4/5 |
| Executing a deletion after the 30-day cooling-off | A job; there is no content to delete until Phase 3 | Phase 5 |
| FR-029a withdraw-my-entries on leave and block | There are no entries. Arrives with Phase 3 as an optional field on `leave` and `block`, default `true` on block, default `false` on leave — additive | Phase 3 |
| `Idempotency-Key` (doc 06 §1) | Required on entry creation and media completion; neither exists yet | Phase 3 |
| `notification_prefs`, reminder delivery | Column exists with its `'{}'` default; nothing reads it | Phase 4 |

---

## 2. The module

`modules/bond`, packages `com.moyi.bond.{api,domain,service,infra,web}`, under
the Konsist rules in `app/src/test/.../ArchitectureTest.kt`. The module owns
its Flyway migrations (ADR-0014): **V9** in B1, **V10** in B5.

Dependencies: `common:core` (ids, clock), `common:web` (problem details,
`NotFoundException`), `common:security` (`CurrentUser`, `ClientContext`,
rate-limit buckets), `modules:identity` (its `api` package only).

**The first cross-module port.** Identity grows `com.moyi.identity.api`:

```kotlin
interface UserDirectory {
    fun find(id: UUID): UserSummary?
    fun findAll(ids: Collection<UUID>): Map<UUID, UserSummary>
}
data class UserSummary(val id: UUID, val displayName: String, val emailVerified: Boolean)
```

Implemented in `identity.service` over the existing `AccountStore`. Bond never
sees a `User`, an email, or a status. This is the same shape as
`notification.api.EmailSender`, and it is the pattern for every later
inter-module call: an interface and DTOs in `api`, nothing else public. No
foreign key crosses the boundary: `bond_members.user_id`, `bonds.created_by`,
`blocks.*_user_id` are bare UUIDs.

---

## 3. Domain model

### 3.1 The aggregate

`Bond` is the aggregate root (ADR-0003). An immutable Kotlin `data class`
holding its `members`; every state change is a method returning a new `Bond`
or throwing a domain exception. Invariants it enforces:

- **I-1** `activeMembers.size <= maxMembers` — `maxMembers` is read from the
  row, and its initial value from `BondType.maxMembers` (2 for every type in
  v1; FR-021 wants it on the type, FR-030 wants the type addable without a
  migration, so both exist).
- **I-5 / BR-9** an `ARCHIVED` (or `PENDING_DELETION`) Bond refuses every
  mutation except `block`, `requestDeletion` and `cancelDeletion`.
- **Status machine**, doc 04 §4.3, exactly:
  `PENDING_MEMBER → ACTIVE` on join; `ACTIVE → ARCHIVED` on leave or block;
  `ACTIVE | ARCHIVED | PENDING_MEMBER → PENDING_DELETION` on confirmed
  deletion; `PENDING_DELETION → (ACTIVE | ARCHIVED)` on cancel, restored by
  whether any member has `leftAt`; `PENDING_DELETION → DELETED` by the Phase 5
  job. `PENDING_MEMBER → ARCHIVED` when the creator leaves before anyone
  joins.

Value types, all `@JvmInline value class` like `UserId`: `BondId`, `MemberId`,
`InviteId`, `InviteCode`. Enums stored as text with CHECK constraints:
`BondType {COUPLE, FRIENDS, FAMILY, PARENT_CHILD}`, `BondStatus`,
`MemberRole {OWNER, MEMBER}`.

`Member`: `id · bondId · userId · role · joinedAt · leftAt? · reminderTimeLocal
· reminderTimezone · quietHoursStart? · quietHoursEnd? · nicknameForOther?`.
`nicknameForOther`, not `nicknameForPartner` (ADR-0003 amendment; doc 07 §2
still says `partner` and is amended).

`Invite`: `id · bondId · code · createdByMemberId · expiresAt · usedAt? ·
usedByUserId? · revokedAt?`. `isLive(now)` = not used, not revoked, not
expired.

`Block`: `blockerUserId · blockedUserId · bondId · createdAt`.

`Proposal` (B5): `id · bondId · kind {TIMEZONE_CHANGE, DELETION} · payload? ·
proposedByMemberId · proposedAt · expiresAt · confirmedByMemberId? ·
confirmedAt? · cancelledAt?`. One live proposal per kind per Bond.

### 3.2 Persistence

Flat JPA entities per table in `infra.database`, mapped to and from the
domain by hand (the identity precedent: `UserEntity` ↔ `User`). One
`BondStore` loads a Bond and its members in one query and hands back the
aggregate; `InviteStore`, `BlockStore`, `ProposalStore` are separate. No
`@OneToMany`: a Kotlin entity graph with lazy collections is the trap doc 25
§5 warns about, and the aggregate is small enough to assemble by hand.

Every store method that reads bond-scoped data takes a `BondId` **and** the
caller's `MemberId` or `UserId` (doc 05 §5.5 layer 3). There is no
`findById(bondId)` on `BondStore` that a service can call without saying who
is asking; the only exception is the guard's own lookup.

### 3.3 Invite codes

Alphabet `23456789ABCDEFGHJKMNPQRSTVWXYZ` — 30 symbols, `states.md` §2's
decision of 2026-09-03, which supersedes doc 09 T-06's "32-symbol" (amended
in B2's ADR). Six characters from `SecureRandom`, 30⁶ ≈ 7.3 × 10⁸, ≈ 29.4
bits. Stored and compared uppercase. Input is uppercased; a character outside
the alphabet is a `422 VALIDATION_FAILED` with field `code`, which leaks
nothing because it is about the shape of what was typed, not about any Bond.

Uniqueness is a `UNIQUE` constraint on `bond_invites.code`; on the
one-in-a-billion collision the insert is retried once with a fresh code.

**One live invite per Bond.** Creating a new one revokes the outstanding one
in the same transaction (doc 06 §3.3, `states.md` §2 "Replaced").

The link is `<moyi.bond.invite.link-base-url>/<code>`, defaulting to
`https://moyi.com/i`, configured like the verification link. The page it
lands on performs no lookup (`states.md` §2).

---

## 4. Authorisation — the guard

Three layers, doc 05 §5.5, doc 09 §4.

1. **Filter chain.** Every bond endpoint needs the bearer. None is added to
   `OpenApiConfiguration`'s public list.
2. **`BondAccessGuard`** in `bond.service`. `fun membershipOf(caller: UserId,
   bondId: BondId): Membership` — returns the caller's membership in that Bond
   or throws `BondNotFoundException : NotFoundException` (404 `NOT_FOUND`,
   detail "That bond was not found."). Non-member, wrong id, malformed id,
   a Bond the caller left: all the same answer, except that a member who
   **left** an `ARCHIVED` Bond still gets a `Membership` for **reads** (OQ-09,
   `states.md` §9 "retained access"), flagged `left = true`, and every write
   path refuses it.
3. **`Membership` can only come from the guard.** Its constructor is private
   to the guard's file. Every service function that acts on a Bond takes a
   `Membership`, not a `BondId`, so a controller physically cannot reach a
   Bond without the guard having said yes. **Konsist rule (B1):** in
   `com.moyi.bond.service`, every function with a `BondId` parameter also has
   a `Membership` parameter, except inside `BondAccessGuard` itself and the
   functions that create or list Bonds for a `UserId`.

**The cross-tenant suite (B1, grows every slice).** A MockMvc test in the
bond module enumerates every route from `RequestMappingHandlerMapping` whose
pattern contains `{bondId}`. For each it looks up a fixture (a valid body and
headers that would succeed for a member), calls it as a **non-member** with
a real Bond's id, and asserts `404 NOT_FOUND` with a body that is only the
problem-detail fields. A route with no fixture fails the test — that is doc
12 §3.3's "a new endpoint that is not covered fails the build". The guard runs
before `If-Match` is examined and before any state check, so a non-member
never sees a 412, 428 or 409.

Path ids are taken as `String` and parsed in the controller; a non-UUID is
the same 404 (the `SessionsController` precedent).

---

## 5. The API

Base `/api/v1`. All bearer-authenticated. Bodies `camelCase`. Timestamps
RFC 3339 UTC. Times of day `HH:mm`. Errors RFC 9457 with `code`.

### 5.1 Response shapes

```jsonc
// BondResponse — the one shape every bond endpoint returns
{
  "id": "019…", "type": "COUPLE", "name": "Us",
  "anchorTimezone": "Africa/Lagos", "revealTimeLocal": "21:00" | null,
  "strictMode": false, "status": "PENDING_MEMBER", "maxMembers": 2,
  "createdAt": "…", "archivedAt": null, "deletionScheduledFor": null,
  "members": [
    { "id": "019…", "displayName": "Amara", "role": "OWNER", "joinedAt": "…", "leftAt": null }
  ],
  "me": { "memberId": "019…", "role": "OWNER" },
  "invite": { "id": "019…", "code": "7KQ4MZ", "link": "https://moyi.com/i/7KQ4MZ", "expiresAt": "…" } | null,
  "pendingTimezoneChange": { "proposedTimezone": "Europe/London", "proposedByMemberId": "…", "proposedAt": "…", "expiresAt": "…" } | null,
  "pendingDeletionRequest": { "requestedByMemberId": "…", "requestedAt": "…", "expiresAt": "…" } | null
}
```

Members carry no `userId`, no email, no reminder settings (`states.md` §8:
never show the partner's settings). `invite` is present only while live.
Display names come from `UserDirectory` at read time; nothing is denormalised.

`ETag: "<version>"` on `GET /bonds/{bondId}` and on every response carrying a
`BondResponse`.

### 5.2 Endpoints

| # | Method · path | Slice | Success | Errors beyond 401/404 |
|---|---|---|---|---|
| 1 | `POST /bonds` `{name, type, anchorTimezone, revealTimeLocal?, reminderTimezone?}` | B1 | `201` BondResponse with `invite` | `403 EMAIL_NOT_VERIFIED` · `409 BOND_LIMIT_REACHED` · `422` |
| 2 | `GET /bonds` | B1 | `200 {bonds: [BondResponse]}` — every Bond the caller has a membership row in, left or not, newest first | — |
| 3 | `GET /bonds/{bondId}` | B1 | `200` BondResponse + `ETag` | — |
| 4 | `POST /bonds/{bondId}/invites` | B2 | `201 {id, code, link, expiresAt}`; revokes the previous live invite | `409 BOND_FULL` · `409 BOND_ARCHIVED` · `429 invite:create` |
| 5 | `DELETE /bonds/{bondId}/invites/{inviteId}` | B2 | `204` | `404` if not this Bond's live invite |
| 6 | `GET /invites/{code}` | B2 | `200 {bondName, bondType, inviterDisplayName}` | `404 INVITE_NOT_USABLE` · `422` · `429` |
| 7 | `POST /invites/{code}/accept` | B2 | `200` BondResponse | `403 EMAIL_NOT_VERIFIED` · `409 ALREADY_MEMBER` · `409 BOND_LIMIT_REACHED` · `404 INVITE_NOT_USABLE` · `422` · `429` |
| 8 | `POST /bonds/{bondId}/leave` | B3 | `204` | `409 BOND_ARCHIVED` |
| 9 | `POST /bonds/{bondId}/block` | B3 | `204` | — (allowed on ACTIVE and ARCHIVED) |
| 10 | `PATCH /bonds/{bondId}` `{name?, type?, revealTimeLocal?, strictMode?}` + `If-Match` | B4 | `200` BondResponse + `ETag` | `428 PRECONDITION_REQUIRED` · `412 PRECONDITION_FAILED` · `409 BOND_ARCHIVED` · `422` |
| 11 | `GET /bonds/{bondId}/members/me/settings` | B4 | `200 {nicknameForOther, reminderTimeLocal, reminderTimezone, quietHoursStart, quietHoursEnd}` | — |
| 12 | `PUT /bonds/{bondId}/members/me/settings` (full replacement) | B4 | `200` same shape | `409 BOND_ARCHIVED` · `422` |
| 13 | `PATCH /bonds/{bondId}/timezone` `{anchorTimezone}` | B5 | `200` BondResponse — applied at once while `PENDING_MEMBER`; otherwise a proposal | `409 PROPOSAL_PENDING` · `409 TIMEZONE_CHANGE_TOO_SOON` · `409 BOND_ARCHIVED` · `422` |
| 14 | `POST /bonds/{bondId}/timezone/confirm` | B5 | `200` BondResponse | `404` no live proposal · `409 PROPOSAL_NEEDS_OTHER_MEMBER` · `409 TIMEZONE_CHANGE_TOO_SOON` |
| 15 | `DELETE /bonds/{bondId}/timezone` — cancel the proposal, either member | B5 | `204` | `404` no live proposal |
| 16 | `POST /bonds/{bondId}/deletion-request` | B5 | `202` BondResponse | `409 BOND_ARCHIVED` only when the Bond ended by block (see §6.4) |
| 17 | `DELETE /bonds/{bondId}/deletion-request` — cancels the request or the pending deletion, either member | B5 | `204` | `404` nothing to cancel |

Bean Validation on every body: `name` 1–60 chars (a number chosen here, like
the 80-character display name; flagged for Daniel), `nicknameForOther` ≤ 40,
`type` one of the enum, `anchorTimezone` and `reminderTimezone` a region IANA
id (§6.1), `revealTimeLocal` `HH:mm`, `code` six alphabet characters after
uppercasing. Validation failures are `422 VALIDATION_FAILED` with the
constraint-derived field codes the global handler already produces.

### 5.3 New error codes (`common:web` `ErrorCode`)

`EMAIL_NOT_VERIFIED` (403) · `BOND_LIMIT_REACHED` (409) · `BOND_FULL` (409) ·
`BOND_ARCHIVED` (409) · `ALREADY_MEMBER` (409) · `INVITE_NOT_USABLE` (404) ·
`PRECONDITION_REQUIRED` (428) · `PRECONDITION_FAILED` (412) ·
`PROPOSAL_PENDING` (409) · `PROPOSAL_NEEDS_OTHER_MEMBER` (409) ·
`TIMEZONE_CHANGE_TOO_SOON` (409). Each added by the slice that returns it.

### 5.4 Rate limiting (`RateLimitBucket`, doc 06 §4)

| Bucket | Subject | Limit | Applied to |
|---|---|---|---|
| `invite:create:user` | USER | 10 / day | #4 |
| `invite:lookup:user` | USER | 10 / hour | #6 |
| `invite:code:ip` | IP | 20 / hour, shared | #6 and #7 — the T-06 brute-force bound |

`@RateLimited` today accepts only IP buckets; B2 extends the interceptor to
USER buckets (the user id is known before the handler runs). The
`authenticated:user` 120/min bucket applies to everything as it already does.

---

## 6. Rules, stated so they can be tested

### 6.1 Creating (FR-020, FR-021, FR-025, ADR-0004, ADR-0013)

- Caller must be email-verified (`UserDirectory`), else `403 EMAIL_NOT_VERIFIED`.
- Caller must have fewer than 3 Bonds in `PENDING_MEMBER` or `ACTIVE` where
  their membership has no `leftAt`, else `409 BOND_LIMIT_REACHED`. Archived
  and pending-deletion Bonds do not count.
- `anchorTimezone` must be in `ZoneId.getAvailableZoneIds()`, contain `/`,
  and not start with `Etc/` or `SystemV/`: region ids only, no fixed offsets.
  Deprecated aliases (`Asia/Calcutta`) are accepted and stored as given.
- The creator becomes the `OWNER` member; `reminderTimezone` is the request's
  value or, absent, the anchor zone; `reminderTimeLocal` defaults to 20:00.
- A live invite is created in the same transaction, 7-day expiry, and
  returned in the `201`.
- The Bond is `PENDING_MEMBER`; `maxMembers` copied from the type.

### 6.2 Joining (FR-022 – FR-025, T-06, doc 26 §2.1)

Checks in this order, on `POST /invites/{code}/accept`:

1. Caller verified, else `403 EMAIL_NOT_VERIFIED` (their own state; leaks nothing).
2. Code shape, else `422`.
3. Resolve a **live** invite by code, lock its Bond row (`SELECT … FOR
   UPDATE`); none → `404 INVITE_NOT_USABLE`.
4. Caller already an active member of that Bond → `409 ALREADY_MEMBER` (they
   know their own Bonds; the creator scanning their own code is the case).
5. Caller at the 3-Bond limit → `409 BOND_LIMIT_REACHED`.
6. Bond not `PENDING_MEMBER`, or active members ≥ `maxMembers` → `404
   INVITE_NOT_USABLE`.
7. A `blocks` row in **either direction** between the caller and any user who
   has ever been a member of this Bond → `404 INVITE_NOT_USABLE`.
8. Mark the invite used (`used_at`, `used_by_user_id`) with a compare-and-set
   on `used_at IS NULL AND revoked_at IS NULL`; a lost race is `404
   INVITE_NOT_USABLE`. Insert the `MEMBER` membership; the Bond becomes
   `ACTIVE`.

Steps 3, 6, 7 and 8 are one answer on purpose (FR-024, `states.md` §2 "one
screen, one string, four causes"). Doc 06 §3.3's `410` and `409 full` on
accept, and its `valid` field on lookup, are superseded: a usable invite is a
`200`, anything else is that `404`. `GET /invites/{code}` applies steps 2, 3
and 6 only (not the caller's own state — the confirm screen shows who you
would join before anything is spent).

### 6.3 Ending (FR-026, FR-029, T-09, T-20, doc 26)

- **Leave.** Active member of an `ACTIVE` or `PENDING_MEMBER` Bond. Sets
  `leftAt`, the Bond becomes `ARCHIVED` with `archivedAt`, any live invite is
  revoked, any live proposal is cancelled. `204`. Nobody is notified
  (T-09 "discreet exit"). On an already-archived Bond: `409 BOND_ARCHIVED`.
- **Block.** Everything leave does, plus one `blocks` row per other member
  (current or left) with `bond_id`. Permitted on an `ARCHIVED` Bond too —
  blocking someone who left first is the point of FR-029. On a Bond with no
  other member it behaves exactly as leave. The `204` is identical to
  leave's, the archived Bond looks identical from the other side, and no
  response anywhere carries the word "block" (doc 26 §2.1 is the acceptance
  test; the cross-tenant suite adds the blocked party's view as a case).
- **Archived means read-only for both.** Both members, including the one who
  left, keep `GET /bonds/{bondId}` and appear in each other's `GET /bonds`.
  Every other bond-scoped write is `409 BOND_ARCHIVED`.

### 6.4 Two-party consent (FR-027, FR-028, BR-6, T-09, doc 04 §8.5)

One mechanism, `bond_proposals`, one live proposal per `kind` per Bond,
7-day expiry, confirmable only by a member other than the proposer,
cancellable by either, lapsed proposals ignored (never reaped; `expires_at`
in the predicate).

**Timezone.**
- `PENDING_MEMBER`: the creator changes it directly (there is nobody to
  consent; ADR-0004 expects onboarding mistakes). Still subject to the
  30-day rule.
- `ACTIVE`: `PATCH …/timezone` records a proposal; `…/timezone/confirm` by
  the other member applies it. Applying sets `anchor_timezone` and
  `timezone_changed_at = now`.
- **30-day rule:** a change (direct or confirmed) when `timezone_changed_at`
  is less than 30 days ago is `409 TIMEZONE_CHANGE_TOO_SOON`, detail naming
  the date it becomes allowed. Checked at proposal *and* at confirmation.
  Doc 06 §3.3 said `429`; a month is not a rate limit a client should show
  as "please wait", and the code is amended in B5's ADR.
- **"Effective from the next Bond-day, never retroactively."** In Phase 2
  there are no Bond-days, so the column simply changes. Phase 3's day
  opener reads the zone when it opens a day and a `bond_days` row is never
  recomputed (BR-6); doc 04 §8.5's skipped-date-is-`FROZEN` rule lands
  there. Written here so Phase 3 cannot forget it.

**Deletion.**
- First `POST …/deletion-request` records a `DELETION` proposal, `202`.
  Repeating it as the same member is idempotent `202`.
- The other member's `POST` confirms: `status = PENDING_DELETION`,
  `deletion_requested_at = now`, `deletionScheduledFor = now + 30 days` in
  the response. `202`.
- `PENDING_MEMBER` Bond (one member): the first request confirms itself.
- `DELETE …/deletion-request` by either member cancels the proposal or the
  pending deletion; status returns to `ARCHIVED` if any member has `leftAt`,
  else `ACTIVE`. `204`.
- A Bond that ended by **block** refuses deletion requests with `409
  BOND_ARCHIVED` (doc 26 §4: "Close the box must be absent" — a request would
  be a channel from the blocked party back to the blocker). The refusal is
  the same code an archived Bond gives every other write, so it discloses no
  block.

### 6.5 Settings (FR-027 name, ADR-0013 §8 type, doc 06 §1 concurrency)

- `PATCH /bonds/{bondId}` requires `If-Match`; missing → `428`, not equal to
  the current `version` → `412`. Each successful write increments `version`
  (`@Version`). `type` is patchable unilaterally (ADR-0013 §8, not in doc 06's
  list; amended). Refused on `ARCHIVED` / `PENDING_DELETION` with `409`.
- Member settings are per-row, no `If-Match`; `PUT` replaces all five fields.
  Refused on archived Bonds.

---

## 7. Data — V9 (B1) and V10 (B5)

Doc 07 §2 "bond", with the deltas below, each stated in the migration's
comments:

```sql
bonds(id uuid pk, type text, name text, anchor_timezone text, timezone_changed_at timestamptz,
      reveal_time_local time, strict_mode bool default false, status text, max_members smallint,
      created_by uuid, created_at, archived_at, deletion_requested_at, version int default 0)
  CHECK type IN (...), status IN (...), char_length(name) BETWEEN 1 AND 60, max_members >= 2
bond_members(id uuid pk, bond_id fk, user_id uuid, role text, joined_at, left_at,
      reminder_time_local time default '20:00', reminder_timezone text, quiet_hours_start time,
      quiet_hours_end time, notification_prefs jsonb default '{}', nickname_for_other text)
  UNIQUE (bond_id, user_id) WHERE left_at IS NULL      -- one active membership
  INDEX (user_id) WHERE left_at IS NULL                -- "my bonds"
  INDEX (bond_id)
bond_invites(id uuid pk, bond_id fk, code text UNIQUE, created_by_member_id fk bond_members,
      expires_at, used_at, used_by_user_id uuid, revoked_at)
  INDEX (code) WHERE used_at IS NULL AND revoked_at IS NULL
  INDEX (bond_id)
blocks(id uuid pk, blocker_user_id uuid, blocked_user_id uuid, bond_id fk, created_at)
  UNIQUE (blocker_user_id, blocked_user_id, bond_id)
  INDEX (blocked_user_id), INDEX (blocker_user_id)     -- the accept check, both directions
-- V10
bond_proposals(id uuid pk, bond_id fk, kind text, payload text, proposed_by_member_id fk,
      proposed_at, expires_at, confirmed_by_member_id, confirmed_at, cancelled_at)
  CHECK kind IN ('TIMEZONE_CHANGE','DELETION')
  UNIQUE (bond_id, kind) WHERE confirmed_at IS NULL AND cancelled_at IS NULL
```

Deltas from doc 07: `nickname_for_other` (ADR-0003); `created_by_member_id`
(doc 04's `createdByMemberId`, doc 07 said `created_by`); `bond_proposals` is
new; `version` is the ETag. Bond ids are UUID v7 (`IdGenerator.timeOrdered`),
as are member, invite and proposal ids — none of them is a secret.

---

## 8. Testing

Per slice, in this order (TDD):

- **Domain unit tests** — the aggregate's invariants and status machine, the
  code alphabet, the timezone rule, the 30-day rule, with `MutableClock`.
- **Persistence tests** — the module's own Spring context on Testcontainers
  Postgres (`BondTestApplication`, as identity does), `UserDirectory` faked.
- **Endpoint tests** — MockMvc through the real chain, tokens minted by the
  test issuer. Every table row in §5.2 has a test per listed status.
- **The cross-tenant suite** — §4, reflection-driven, grows with every slice.
- **The one-answer test** — the six causes in §6.2 produce byte-identical
  problem bodies (modulo `instance` and `traceId`).
- **The discreet-exit test** — after a leave and after a block, the other
  member's `GET /bonds/{id}` and `GET /bonds` responses are identical.
- **Race tests** — two accepts on one code; two proposals; two joins on a
  Bond with one seat. Two-transaction tests as `RefreshTokenPersistenceTest`
  does.
- **Mutation checks** before each PR: break the guard, break the block check,
  break the CAS; watch the right test fail; revert.
- **Contract** — `OpenApiContractTest` keeps `contracts/openapi.json` current;
  every path-parameter operation documents 404 (ADR-0024).
- **Smoke** — `scripts/smoke.sh` gains the M2 story: A creates, B joins by
  code, C gets 404 on everything, A leaves, B still reads.

Coverage gate 80% stays.

---

## 9. Slices

| Slice | Branch | Contents | ADR |
|---|---|---|---|
| **B1** the Bond and its guard | `feat/bond-core` | module, domain, V9, `identity.api.UserDirectory`, #1–#3, `BondAccessGuard`, `Membership`, Konsist rule, cross-tenant suite, contract, smoke | 0026 — the bond module, the guard, and the first cross-module port |
| **B2** invites (M2) | `feat/bond-invites` | #4–#7, codes, three buckets, USER-subject `@RateLimited`, block check at accept, the limit, the one-answer rule | 0027 — invite codes and the one answer |
| **B3** ending | `feat/bond-ending` | #8–#9, archived read-only, `blocks` written, discreet-exit test | 0028 — leave, block, and the discreet exit |
| **B4** settings | `feat/bond-settings` | #10–#12, `If-Match`/`ETag`, `@Version` | 0029 — optimistic concurrency over HTTP |
| **B5** consent | `feat/bond-consent` | V10, #13–#17, proposals, the 30-day rule, deletion cooling-off | 0030 — two-party consent as one mechanism |

Each PR: the template's ten questions, the concept brief, the Figma
alignment table (§10), the DoD, a learning-log entry, HANDOVER.md updated.
Corpus amendments go to the Gratitude repo on `docs/bonds-phase-2`: ADR
copies; doc 06 §3.3 (paths, codes, `valid` dropped, `type` in PATCH, `DELETE
…/timezone`, `409` not `429`); doc 07 §2 (deltas in §7); doc 09 T-06 (30
symbols); doc 00's amendment log; `states.md` §2 (the UNRESOLVED note
closed).

---

## 10. Figma alignment (states.md)

| Endpoint | Screen | Status |
|---|---|---|
| #1 create | Onboarding 5 Create Bond, 4 relationship type | exists |
| #1 response `invite` | Onboarding 6 Invite handoff | exists |
| #2 list | none — the product assumes one Bond; FR-025 allows three | **gap**, record in states.md |
| #3 detail | Today (partner not joined / joined), Bond settings root | exists |
| #4, #5 invites | Bond settings › Invite (active, revoked) | exists |
| #6 lookup | Join › Confirm; Join › Cannot be used | exists |
| #7 accept | Join › Confirm → Day 1 | exists |
| #8 leave | Bond settings › Leave (confirm) | exists |
| #9 block | Bond settings › Block confirm (doc 26) | exists |
| #10 patch | Bond name editor; Relationship type; Reveal time picker; Rest days toggle | exists |
| #11, #12 member settings | Your reminder (OS picker); nickname | reminder exists; **nickname gap** |
| #13–#15 timezone | Shared time zone, three-step flow; pending state | exists (cancel: adapt the pending state) |
| #16, #17 deletion | Close the box confirm; cooling-off | exists |

---

## 11. Decisions made here, for the ADRs to carry

1. 30-symbol alphabet; doc 09 T-06 amended.
2. One `404 INVITE_NOT_USABLE` for every unusable code; doc 06's `410`/`409
   full`/`valid` superseded.
3. `type` is patchable on `PATCH /bonds/{id}`.
4. `409 TIMEZONE_CHANGE_TOO_SOON`, not `429`.
5. `DELETE /bonds/{id}/timezone` cancels a proposal (new).
6. Timezone changes directly while `PENDING_MEMBER`; deletion self-confirms
   while `PENDING_MEMBER`.
7. Deletion requests lapse after 7 days like timezone proposals (one rule).
8. Block is allowed on an archived Bond; leave is not.
9. Archived Bonds stay readable for the member who left (OQ-09 confirmed by
   building it).
10. Bond name ≤ 60, nickname ≤ 40 — numbers chosen here, flagged for Daniel.
11. `reminderTimezone` defaults to the anchor zone until the client sets it.
12. Flat entities plus hand mappers, not a JPA object graph.
13. `Membership` only from the guard; Konsist enforces the signature rule.

**Open for Daniel, not blocking:** doc 26 §5.1 — whether withdrawing entries
on block destroys the author's own copy. Phase 3.
