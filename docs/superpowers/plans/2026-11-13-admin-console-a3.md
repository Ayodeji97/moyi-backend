# Admin console A3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task is sized for one implementer pass and one independent review pass, and ends green.

**Goal:** An `ADMIN` suspends an account, or lifts a suspension, from the A1 lookup result, with a typed justification and a code proved in the same request; the member is signed out everywhere and cannot get back in; every bond they are in pauses for the partner (`paused: true`, `409 BOND_PAUSED` on new entries, edits and reactions); no day of the pause is missed.

**Architecture:** `identity` owns everything stored: V65 `account_suspensions` (one open span per user), an admin-only write port `AccountSuspension` (locks, status, token bump, refresh families, push tokens, span, in the console's transaction) and a bond-only read port `AccountStanding`. `bond` derives `paused` from open spans of members who have not left, on every membership resolution, bond view, invite and reminder roster, and exposes span overlap to the close job through `BondClosingView`. `gratitude` refuses the three writes and closes overlapping days `SUSPENDED` (withholding a reveal that falls due inside a span). `admin` adds `SuspensionConsole` (service, `ADMIN`) and `SuspensionController` (non-transactional, note before code, `303` + flash), V66 and the export's withholding.

**Tech Stack:** as A5: Kotlin 2.4, Spring Boot 4.1.2, Spring Security 7.1.2, Thymeleaf, Postgres 18, Flyway, JUnit 6 + kotest + MockMvc + Testcontainers, Konsist. No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-13-admin-console-a3-design.md` (commit ce1181d). Read it in full before Task 1, and A5's plan (`2026-11-13-admin-console-a5.md`) for the console's conventions. Where this plan and the spec disagree, the spec wins: stop and report. The plan's own rulings on spec gaps are marked **(plan ruling)** and listed at the end.

## Global Constraints

- Migrations: **V65** `modules/identity/src/main/resources/db/migration/V65__identity_account_suspensions.sql`, **V66** `modules/admin/src/main/resources/db/migration/V66__admin_account_suspension.sql`. Re-check `main` before Task 1 (`git fetch && git log origin/main -3`); if a number has landed, take the next free and say so in the PR.
- Lock order of suspend and lift: sessions advisory lock `(1, user)` (`RefreshTokenStore.lockSessionsOf`) → `users` row `FOR UPDATE` → refresh tokens → push tokens → `account_suspensions` → `audit_log`. **No bond, day or entry lock.** Every `bond`/`gratitude` path reads spans without a lock.
- `now` for suspend/lift is read from the clock **after** both locks are held, truncated to microseconds; `tokens_invalid_before = now.truncatedTo(SECONDS).plusSeconds(1)` (as `AccountErasure`). Lifting does not touch `tokens_invalid_before`.
- Ports: `AccountSuspension`'s two methods `@Transactional(propagation = MANDATORY)`; `AccountStanding` read-only, no lock, annotated as `AccountDirectory` is (`@Transactional(readOnly = true)`).
- Console service: `@PreAuthorize("hasRole('ADMIN')")`, `@Transactional`; the audit row through `AuditTrail.record` (`MANDATORY`). Controller: class-level `@PreAuthorize("hasRole('ADMIN')")`, **not** `@Transactional`; order role → id → note → code → act.
- Justification: `ModerationNote.require` (1–400 code points after trim, no NUL), never restated. The note goes only to `audit_log.justification`: never a log line, flash, metric tag, URL, exception message or `toString`.
- `BOND_PAUSED` copy (exact): "This bond is paused. Everything in it can still be read; new entries wait until it resumes." `409`. Thrown immediately after the existing `BondArchivedException` check of each refused path, so an ended bond answers `BOND_ARCHIVED`.
- `paused` names no cause, no date, no member, anywhere a member can see it.
- Contract: `contracts/openapi.json` is regenerated, never hand-edited: run `./gradlew :app:test --tests "com.moyi.app.OpenApiContractTest"`, then `cp app/build/openapi/openapi.json contracts/openapi.json` and read the diff. The PR carries `breaking-api-change` and its title is `feat(admin)!: …`. No admin route enters the contract.
- Concurrency tests synchronise on `pg_locks`/`pg_stat_activity` or a latch inside the code under test, with a deadline that **fails** the test; never a sleep.
- Every test that signs in after a suspension advances the `MutableClock` at least one second past the suspension first: the bump is the next whole second.
- Commits: Conventional Commits, subject ≤ 88 characters; `git config core.hooksPath .githooks` in the worktree before the first commit.
- Environment: another worktree may serve a phone on 8080/5432/6379/9000. Never `./gradlew --stop`, never kill java/gradle, never touch the default compose project. Smoke runs in its own compose project and ports (`MOYI_DB`, the 55432 workaround).

## Review Focus

1. **A conditional `GET` hiding the pause.** `BondResponse` carries an `ETag` from `BondView.entityTag()`; if `paused` is not in the digest a client holding the old tag gets `304` and never learns the bond paused (or resumed). Pinned in Task 3.
2. **The on-this-day push silenced with the reminder.** `ReminderRosterAdapter.dueBetween` is shared by the daily reminder and the on-this-day push; the spec stops only the reminder. Filtering inside `dueBetween` would silence both. Pinned in Task 3.
3. **The suspended member's lone entry reaching the partner by any read** (anti-goal 4): a paused `PARTIAL` day closes `SUSPENDED`, so the entry is never revealed; the partner's `GET /bonds/{id}/days/{date}`, `/today`, the archive, search and the partner's export must show no word of it, before and after the lift. Pinned in Task 5.
4. **A day stretched by a westward zone change.** Overlap must be judged on the day's bounds after `extendedTo(window)` (the `current` day in `CloseDay`), not the stored row; a span starting in the extra hour must still make the day `SUSPENDED`. Pinned in Task 5.
5. **A replayed write.** A submit accepted before the pause and replayed with the same `Idempotency-Key` after it must answer its original `201` (replay), not `409 BOND_PAUSED`; a new key is `409`. Pinned in Task 4.

---

### Task 1: V65, the suspension and the standing ports (identity)

**Files:**
- Create: `modules/identity/src/main/resources/db/migration/V65__identity_account_suspensions.sql` (spec §7 exactly: table, `CHECK (lifted_at IS NULL OR lifted_at >= suspended_at)`, partial `UNIQUE (user_id) WHERE lifted_at IS NULL`, `INDEX (user_id, suspended_at)`; a header comment citing the spec and that no actor or reason is kept here)
- Create: `modules/identity/src/main/kotlin/com/moyi/identity/api/AccountSuspension.kt` (port, `SuspensionResult`, `SuspensionOutcome`, `AccountStatus`)
- Create: `modules/identity/src/main/kotlin/com/moyi/identity/api/AccountStanding.kt` (port, `SuspensionSpan`)
- Create: `modules/identity/src/main/kotlin/com/moyi/identity/infra/database/AccountSuspensionStore.kt` (JDBC, internal, not transactional)
- Create: `modules/identity/src/main/kotlin/com/moyi/identity/service/SuspendAccount.kt` (implements `AccountSuspension`), `.../service/AccountStandingService.kt` (implements `AccountStanding`)
- Modify: `modules/identity/src/main/kotlin/com/moyi/identity/api/UserAdminLookup.kt` (`UserAdminView` gains `status: AccountStatus`, `suspendedSince: Instant?`, `deletionDueAt: Instant?`), `.../service/UserAdminLookupService.kt`
- Modify: `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt` (`declaredIn` gains `AccountSuspension`, `SuspensionResult`, `SuspensionOutcome`, `AccountStatus`; new rule: `AccountStanding`/`SuspensionSpan` imported in `src/main` only by `identity` and `bond`)
- Test: `modules/identity/src/test/kotlin/com/moyi/identity/infra/database/V65MigrationTest.kt`, `.../service/AccountSuspensionTest.kt`, `.../service/AccountSuspensionRaceTest.kt`, `.../service/UserAdminLookupServiceTest.kt` (extend)

**Interfaces (produces):**
- `enum class AccountStatus { PENDING_VERIFICATION, ACTIVE, SUSPENDED, PENDING_DELETION, DELETED }` (identity.api; mapped from `UserStatus` by an exhaustive `when`).
- `enum class SuspensionOutcome { SUSPENDED, ALREADY_SUSPENDED, LIFTED, NOT_SUSPENDED, GONE, NO_SUCH_ACCOUNT }`.
- `class SuspensionResult(val outcome: SuspensionOutcome, val statusBefore: AccountStatus?, val deletionDueAt: Instant?)` — `statusBefore` null only for `NO_SUCH_ACCOUNT`; `deletionDueAt` = the pending `DeletionRequest.cancellableUntil` (the `execute_after` the job runs at), null when none is pending **(plan ruling 1)**. `toString` ids/outcome only.
- `interface AccountSuspension { fun suspend(userId: UUID): SuspensionResult; fun lift(userId: UUID): SuspensionResult }`, both `MANDATORY`.
- `interface AccountStanding { fun suspendedAmong(userIds: Collection<UUID>): Set<UUID>; fun suspensionsOf(userIds: Collection<UUID>): List<SuspensionSpan> }`; `data class SuspensionSpan(val userId: UUID, val from: Instant, val to: Instant?)`. Empty input → empty answer with no query.
- `AccountSuspensionStore`: `lockStanding(userId: UserId): Standing?` (`SELECT status, email_verified_at FROM users WHERE id = ? FOR UPDATE`; `Standing(status: UserStatus, emailVerified: Boolean)`), `markSuspended(userId, at, invalidBefore)`, `markLifted(userId, status: UserStatus, at)`, `openSpan(id: UUID, userId, at)`, `closeOpenSpan(userId, at): Int`, `openSpanSince(userId): Instant?`, `openAmong(ids): Set<UUID>`, `spansOf(ids): List<SuspensionSpan>`.

**The rule** (spec rulings 4–6, 15): `suspend` — locks; `ACTIVE`/`PENDING_VERIFICATION` → status `SUSPENDED`, bump, `RefreshTokenStore.revokeAllForUser`, `PushTokens.forgetAllOf`, `openSpan`, outcome `SUSPENDED`; `SUSPENDED` → `ALREADY_SUSPENDED`; `DELETED`/`PENDING_DELETION` → `GONE`; no row → `NO_SUCH_ACCOUNT`. `lift` — same locks; `SUSPENDED` → status `ACTIVE` if `email_verified_at` is set else `PENDING_VERIFICATION`, `closeOpenSpan` (`check(== 1)`), outcome `LIFTED`; `ACTIVE`/`PENDING_VERIFICATION` → `NOT_SUSPENDED`; deleted → `GONE`; none → `NO_SUCH_ACCOUNT`. Nothing is written for any outcome but `SUSPENDED`/`LIFTED`. The pending deletion is read with `DeletionRequestStore.findPending` under the same locks. Erasure (`ExecuteAccountDeletions`) is **not** changed: a suspended account erased keeps its open span **(plan ruling 2)**.

- [ ] **Step 1: Write the failing tests.**
  - `V65MigrationTest` (as `V61MigrationTest`): a span inserts; a second open span for the same user fails the unique index; a closed and an open span coexist; `lifted_at < suspended_at` fails the check; a row for a non-existent user fails the FK.
  - `AccountSuspensionTest` (real database, each call inside `TransactionTemplate`; accounts made through `RegisterUser`/`VerifyEmail`, not inserted rows):
    - `suspending an active account`: outcome `SUSPENDED`, `statusBefore = ACTIVE`; `users.status = 'SUSPENDED'`; `tokens_invalid_before` = the act's second + 1 s exactly; every refresh token of the user revoked; push tokens registered beforehand are gone; one open span with `suspended_at` = the act's instant.
    - `a pending-verification account` → `SUSPENDED`, `statusBefore = PENDING_VERIFICATION`.
    - `suspending twice` → second is `ALREADY_SUSPENDED`; one span; `tokens_invalid_before` unchanged by the second.
    - `lifting a verified account` → `LIFTED`, `ACTIVE`, span closed at the act; refresh tokens still revoked (no session restored); `tokens_invalid_before` unchanged.
    - `lifting an unverified one` → `PENDING_VERIFICATION`; `verified while suspended, then lifted` (`VerifyEmail` in between leaves `SUSPENDED`) → `ACTIVE`.
    - `lift on active` → `NOT_SUSPENDED`, nothing written. `deleted` (through `ExecuteAccountDeletions`) → `GONE` both ways. `PENDING_DELETION` (set by SQL: no code path writes it, ADR-0058 decision 2) → `GONE`. Random id → `NO_SUCH_ACCOUNT`, `statusBefore` null.
    - `with a pending deletion request` → `SUSPENDED`, `deletionDueAt` = the request's `cancellableUntil`; and the job then carries it out on time (`ExecuteAccountDeletions.executeDue` → 1, status `DELETED`, the span still there).
    - `the invariant`: after every call above, `status = 'SUSPENDED'` ⇔ an open span exists, for every live (non-`DELETED`) account.
    - `AccountStanding`: `suspendedAmong` returns exactly the open-span ids of the input; `suspensionsOf` returns closed and open spans with `to` null for the open one.
    - Called outside a transaction → `IllegalTransactionStateException`.
  - `AccountSuspensionRaceTest` (two threads, `pg_locks` deadline): (1) two suspensions at once → one `SUSPENDED`, one `ALREADY_SUSPENDED`; suspend vs lift on a suspended account → the second sees the first's result. (2) The deletion job holding the user's locks (an `ExecuteAccountDeletions` run gated after `lockUser`, or a test transaction holding `pg_advisory_xact_lock(1, hashtext(id))` and `FOR UPDATE` as `AccountDeletionEndpointTest`'s race does) → the suspension waits, then answers `GONE`. (3) The suspension holding the locks → the deletion run waits, then carries the deletion out (ruling 15).
  - `UserAdminLookupServiceTest`: `status`, `suspendedSince` (null when active), `deletionDueAt`.
  - `ArchitectureTest`: the new rule with positive controls (an exact import from `gratitude/src/main` is a violation; one from `bond/src/main` and one from a test source are not).
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:identity:test --tests "*V65Migration*" --tests "*AccountSuspension*" --tests "*UserAdminLookupService*"` → compile errors / FAIL.
- [ ] **Step 3: Implement V65, the store, the two services and the view fields.**
- [ ] **Step 4: Run them and see them pass.** Same command → PASS; `./gradlew :app:test --tests "com.moyi.app.ArchitectureTest"` → PASS.
- [ ] **Step 5: Mutation checks** (revert each): skip the bump → the `tokens_invalid_before` assertion fails; skip `forgetAllOf` → the push-token assertion fails; read `now` before the locks and set the bump from it → no test is required to catch this here (Task 2's race does); lift to a stored `ACTIVE` regardless of verification → the unverified-lift test fails.
- [ ] **Step 6: Commit.** `feat(identity): suspend and lift an account, with spans kept (V65)`

### Task 2: Sign-in re-checks under the sessions lock, and the member is locked out

**Files:**
- Modify: `modules/identity/src/main/kotlin/com/moyi/identity/service/LoginUser.kt` (ruling 7; KDoc says why)
- Test: `modules/identity/src/test/kotlin/com/moyi/identity/web/SuspendedMemberTest.kt`, `.../web/LoginSuspensionRaceTest.kt`

**Interfaces:** consumes `AccountSuspension` (Task 1) in tests only. `LoginUser.login`'s signature is unchanged.

**The change:** inside the existing `transactions.execute`, after `sessions.lockSessionsOf(id)`: re-read `accounts.findById(id)` and `accounts.findCredentials(id)`; if the user is null, `!canAuthenticate`, or `credentials.passwordHash != <the hash just verified>`, throw `InvalidCredentialsException` (the transaction rolls back; nothing counted, the same `log.info("Login refused for user …")` line). Then `clearFailedLogins`, `newFamily`, **and `sessions.accessTokenFor(...)` inside the same transaction**; the result is returned from it.

- [ ] **Step 1: Write the failing tests.**
  - `SuspendedMemberTest` (MockMvc over the real identity endpoints; suspension through the port in a `TransactionTemplate`): after `suspend` — the access token minted before is `401` on `GET /me` with `WWW-Authenticate`; refresh with the old refresh token is `401 REFRESH_TOKEN_INVALID`; sign-in with the right password is `401` whose body equals a wrong password's byte for byte, `instance` aside (`no_instance` as the smoke does); the failed-login counter (`credentials.failed_attempts`) unchanged by it; `POST` password-reset request sends no mail (the test mail sink is empty); `GET /auth/sessions` with the old token is `401`. After `lift` (clock + 1 s): sign-in `200`; the families revoked by the suspension are still revoked; no session came back.
  - `LoginSuspensionRaceTest` (a `@TestConfiguration` with two `@Primary` decorators, each with a latch the test arms: a `PasswordHasher` that blocks in `matches`, as `LoginEndpointTest`'s `RecordingPasswordHasher` wraps the real one; an `AccessTokenIssuer` that blocks in `issue`):
    - `a suspension that commits while the password is being checked wins`: login blocked in `matches`; suspension commits; release → `401`, and no `refresh_tokens` row for the user was created after the suspension's instant.
    - `a sign-in that holds the sessions lock finishes first and is then undone`: login blocked in `issue` (inside its transaction, holding the advisory lock); start the suspension; wait until `pg_locks` shows an advisory lock not granted; release → login `200`; the suspension commits; that login's family is revoked and its access token is `401`.
    - `a password reset committed during the check also wins` (ruling 7's second half): login blocked in `matches`; reset the password through `ResetPassword`; release → `401`.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:identity:test --tests "*SuspendedMember*" --tests "*LoginSuspensionRace*"` → the race tests FAIL (the first two); `SuspendedMemberTest` may already pass in part: that is expected, it pins existing behaviour.
- [ ] **Step 3: Change `LoginUser`.**
- [ ] **Step 4: Run them, and `./gradlew :modules:identity:test`, and see them pass.**
- [ ] **Step 5: Mutation checks** (each must turn a test red; revert each): remove the re-read → race 1 fails; mint the access token after the commit → race 2 fails (the suspension is never seen waiting on the advisory lock); drop the hash comparison → the reset race fails; in `SuspendAccount`, read `now` before the locks → race 2's token is accepted.
- [ ] **Step 6: Commit.** `fix(identity): sign-in re-checks the account under the sessions lock`

### Task 3: The derived pause in `bond` (membership, views, invites, roster, admin view)

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/domain/Bond.kt` (`fun isPausedFor(suspended: Set<UserId>): Boolean` = `isOpen && members.any { it.isActive && it.userId in suspended }` — the one rule)
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/api/BondAccess.kt` (`BondMembership.paused: Boolean`, KDoc: checked after `takesWrites`, never folded into it (spec §14 q2); `BondClosingView.wasSuspendedDuring(startsAt: Instant, endsAt: Instant): Boolean` and `wasSuspendedAt(instant: Instant): Boolean`; `BondPast` gains `suspendedDuring: (Instant, Instant) -> Boolean` and `suspendedAt: (Instant) -> Boolean`)
- Modify: `.../service/BondAccessAdapter.kt` (`assemble` sets `paused` from `AccountStanding.suspendedAmong(active member user ids)`, read after the lock under `lockMembershipOf`), `.../service/BondPasts.kt` (spans of **every** member row, left or not, via `suspensionsOf`)
- Modify: `.../service/BondViews.kt` (`BondView.paused`; one `suspendedAmong` for all bonds' members), `.../service/BondEntityTag.kt` (`paused` in the digest), `.../web/BondResponse.kt` (`paused: Boolean`, required, KDoc: no cause, date or member)
- Modify: `.../service/ResolveInvite.kt`, `.../service/AcceptInvite.kt` (after `!bond.hasRoom`: `if (bond.isPausedFor(…)) throw InviteNotUsableException()`; in `AcceptInvite` under the bond lock it already holds)
- Modify: `.../service/ReminderRosterAdapter.kt` (`remindersDueBetween` only: candidates are every active member of every open bond, so a bond is paused when any candidate's user is in one `suspendedAmong(candidates' user ids)`; due reminders of those bonds are dropped. `dueBetween` is **not** changed)
- Modify: `.../api/BondAdminLookup.kt` (`BondAdminView.paused`), `.../service/BondAdminLookupService.kt`
- Modify: `modules/bond/src/test/kotlin/com/moyi/bond/infra/BondTestApplication.kt` and `modules/gratitude/src/test/kotlin/com/moyi/gratitude/infra/GratitudeTestApplication.kt` (a `FakeAccountStanding` bean each, beside `FakeUserDirectory`: in-memory spans, `suspend(userId, at)`, `lift(userId, at)`, `reset()`)
- Modify: `contracts/openapi.json` (regenerated), `contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt` (`getBond`'s description names `paused`: "`paused` is true while the bond takes no new entries, edits or reactions; everything in it can still be read. It says nothing of why.")
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/service/PausedBondTest.kt`, `.../web/PausedBondEndpointTest.kt`

**Interfaces:**
- Consumes: `AccountStanding.suspendedAmong`, `suspensionsOf`, `SuspensionSpan` (Task 1).
- Produces: `BondMembership.paused`; `BondClosingView.wasSuspendedDuring(startsAt, endsAt)` = some span with `from < endsAt && (to == null || startsAt < to)`; `BondClosingView.wasSuspendedAt(instant)` = some span with `from <= instant && (to == null || instant < to)`; `BondResponse.paused`; `BondAdminView.paused`.

- [ ] **Step 1: Write the failing tests** (`FakeAccountStanding` drives every case).
  - `PausedBondTest`: `membershipOf` and `lockMembershipOf` say `paused` for both members while one is suspended, false after lift; false for an ended bond whatever the spans; false when the suspended member has left; **both members suspended, one lifted → still paused**; a member in two bonds pauses both. `wasSuspendedDuring` on the boundaries: span ending exactly at `startsAt` → false; starting exactly at `endsAt` → false; open span starting inside → true; a left member's past span → true. `wasSuspendedAt`: the span's `from` → true; its `to` → false. Reminder roster: a member due in a paused bond is not returned, the same member's on-this-day candidate through `dueBetween` still is (Review Focus 2); a member due in an unpaused bond is. `BondAdminLookupService` sets `paused`.
  - `PausedBondEndpointTest` (MockMvc): `GET /bonds/{id}` and `GET /bonds` carry `"paused":true` then `false`; **the `ETag` differs between the two, and an `If-None-Match` with the old tag is `200`, not `304`** (Review Focus 1); invite resolve and accept into a bond whose creator is suspended equal a revoked invite's response byte for byte (`instance` aside), and both work after lift; leaving and blocking a paused bond still work.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:bond:test --tests "*PausedBond*"` → FAIL.
- [ ] **Step 3: Implement**, then regenerate the contract (Global Constraints) and read its diff: `paused` added to `BondResponse` as required, the `getBond` description.
- [ ] **Step 4: Run them and see them pass.** Same command; then `./gradlew :modules:bond:test :modules:gratitude:test` (the gratitude context now needs the fake) and `./gradlew :app:test --tests "com.moyi.app.OpenApiContractTest"` → PASS.
- [ ] **Step 5: Mutation checks** (revert each): drop the roster filter → the reminder test fails; move the filter into `dueBetween` → the on-this-day test fails; leave `paused` out of `entityTag` → the `304` test fails; judge overlap with `endsAt` only (`from < endsAt`) → the boundary test fails.
- [ ] **Step 6: Commit.** `feat(bond): a bond is paused while a member who has not left is suspended`

### Task 4: The partner's writes refused, the prompt and today (`gratitude`, `common/web`)

**Files:**
- Modify: `common/web/src/main/kotlin/com/moyi/common/web/ErrorCode.kt` (`BOND_PAUSED`, after `BOND_ARCHIVED`, KDoc from spec §9)
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GratitudeErrors.kt` (`BondPausedException`, `409`, the exact copy)
- Modify: `.../service/SubmitEntry.kt` (after line 353's check, inside `execution.once`: `if (membership.paused) throw BondPausedException()`), `.../service/ChangeEntry.kt` (after line 139's, `replacement != null` only), `.../service/ReactToEntry.kt` (`react` only: in the `Reacting.ALLOWED` branch, after `!membership.takesWrites`; `unreact` unchanged)
- Modify: `.../service/DailyPrompt.kt` (`if (!membership.takesWrites || membership.paused) return null`; no new import or constructor parameter, so the ArchitectureTest allowlist holds)
- Modify: `.../service/GetToday.kt` (`TodayView.paused = membership.paused`, from the membership already read), `.../web/TodayResponse.kt` (`paused: Boolean`, required)
- Modify: `contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt` (descriptions of `submitEntry`, `patchEntry`, `reactToEntry` name `409 BOND_PAUSED`: "`409 BOND_PAUSED` while the bond is paused: nothing is written, and the same request succeeds once it resumes."; `today` describes `paused` and that `prompt` is null while it is true), `contracts/openapi.json` (regenerated)
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/PausedBondWritesTest.kt`

**Interfaces:** consumes `BondMembership.paused` (Task 3), `FakeAccountStanding`. Produces `ErrorCode.BOND_PAUSED`, `TodayResponse.paused`.

- [ ] **Step 1: Write the failing tests** (`PausedBondWritesTest`, MockMvc, the partner acting while the other member is suspended in the fake):
  - `POST …/entries` (with and without `intendedAt`, i.e. a late entry for yesterday inside the grace), `PATCH …/entries/{id}` (text, and text with `imageMediaId`), `PUT …/entries/{id}/reaction` → `409 BOND_PAUSED`, the exact `detail`, and no row written (entry count, `updated_at`, reaction rows unchanged).
  - On an ended bond with a suspended member → `409 BOND_ARCHIVED` for each (precedence).
  - Allowed while paused, each with its usual status: `DELETE …/entries/{id}` (own), `DELETE …/reaction`, bookmark and unbookmark, report an entry, `GET` days/today/search/archive, the archive export, `PATCH /bonds/{id}` (name), member settings `PUT`, leave.
  - **Replay** (Review Focus 5): a submit accepted before the pause, sent again with the same `Idempotency-Key` after it → its original `201` body; a new key → `409`.
  - `GET /today`: `"paused":true` and `"prompt":null` while paused; `false` and a prompt after lift.
  - After lift: the same `POST` → `201`.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:gratitude:test --tests "*PausedBondWrites*"` → FAIL.
- [ ] **Step 3: Implement**, regenerate the contract, and read its diff: `BOND_PAUSED` in the problem `code` enum (the breaking change), `TodayResponse.paused`, four descriptions.
- [ ] **Step 4: Run them and see them pass.** Same command; `./gradlew :app:test --tests "com.moyi.app.OpenApiContractTest" --tests "com.moyi.app.ArchitectureTest"` → PASS.
- [ ] **Step 5: Mutation checks** (revert each): remove the `paused` check in each of the three services in turn → its test fails; put `SubmitEntry`'s check before `execution.once` → the replay test fails; drop the `DailyPrompt` clause → the prompt test fails.
- [ ] **Step 6: Commit.** `feat(gratitude)!: 409 BOND_PAUSED on new entries, edits and reactions in a paused bond`

### Task 5: Days in a pause are `SUSPENDED`, a reveal due in one is withheld

**Files:**
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/BondDay.kt` (`close(now: Instant, withheld: Boolean = false, paused: Boolean = false)`: when `paused`, `OPEN` and `PARTIAL` close `SUSPENDED`; `PENDING_REVEAL`/`REVEALED` unchanged by it; `isWithheldBy(separatedAt: Instant?, revealTime: LocalTime?, suspendedAt: (Instant) -> Boolean = { false })` adds `|| suspendedAt(revealFallsDueAt(revealTime))` under the same `awaitsReadingTogether` guard)
- Modify: `.../service/CloseDay.kt` (`withheld = prepared.isWithheldBy(view.separatedAt, view.revealTimeLocal, view::wasSuspendedAt)`; `close(...)` passes `paused = view.wasSuspendedDuring(current.startsAt, current.endsAt)`, the bounds **after** `extendedTo`; the class KDoc's step list names both)
- Modify: `.../service/CreateMissingDays.kt` (`SUSPENDED` when `view.wasPausedAt(window.endsAt) || view.wasSuspendedDuring(window.startsAt, window.endsAt)`)
- Modify: `.../infra/database/StreakStore.kt` (`DayToEvaluate.startsAt`, selected), `.../service/EvaluateStreaks.kt` (`outcomeOf`: a `MISSED` day for which `view.wasSuspendedDuring(day.startsAt, day.endsAt)` → `DayOutcome.SUSPENDED`, after the `AFTER_THE_END` branch)
- Modify: `.../service/ReconcileJoiningDay.kt` (`underBondLock`: `membership.paused || resumed.isWithheldBy(...)` → `days.update(resumed.withholdReveal())`)
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/CloseTransitionTest.kt` (extend), `.../service/SuspendedDaysTest.kt`, `.../service/StreakAcrossSuspensionTest.kt` (as `StreakAfterCancelledDeletionTest`)

**Interfaces:** consumes `BondClosingView.wasSuspendedDuring`, `wasSuspendedAt` (Task 3).

- [ ] **Step 1: Write the failing tests.** Every day produced through `SubmitEntry` and the close job (`CloseElapsedDays`/`DayCloser`), never inserted outcomes; spans through `FakeAccountStanding`; time through `MutableClock`.
  - `CloseTransitionTest`: `close(paused = true)` maps `OPEN → SUSPENDED`, `PARTIAL → SUSPENDED`, leaves `REVEALED` `REVEALED` and `PENDING_REVEAL` to the withheld rule; `isWithheldBy` with a `suspendedAt` true at the due instant → true, true only after it → false.
  - `SuspendedDaysTest`:
    - an overlapping `OPEN` day closes `SUSPENDED`, no `DayClosed` in the outbox;
    - `PARTIAL` with the suspended member's lone entry → `SUSPENDED`; the entry is never revealed, and the partner's `GET /days/{date}`, `/today` (that day), archive page, search for a word of it, and the partner's archive export show no word of it, before and after the lift (Review Focus 3);
    - `PARTIAL` with the partner's lone entry → `SUSPENDED`, not `SOLO`, nothing unlocked;
    - both wrote and revealed before the pause → `REVEALED`, `COMPLETE`;
    - `PENDING_REVEAL` whose due instant falls inside the span → closes `PENDING_REVEAL`, nothing stamped, no `DayRevealed`, `COMPLETE`; a job run **after the lift** but before the day's end still withholds it; due one microsecond before the span → revealed;
    - missing days inside a span → written `SUSPENDED`, no `DayClosed`;
    - a day lengthened by a confirmed westward zone change, the span beginning in its extra hour → `SUSPENDED` (Review Focus 4);
    - the lift day with one entry → `SUSPENDED`; the day after the lift with one entry → `SOLO`;
    - **stability**: a day closed while its span is still open and the same day closed (on a twin bond) after the span was lifted later → the same status.
    - `ReconcileJoiningDay`: a bond paired while paused whose joining day both wrote on is not revealed by a read.
  - `StreakAcrossSuspensionTest`: a 5-day run with one freeze banked; pause three days, lift, both write → run 6, freeze kept, `streak_days.evaluated_as = SUSPENDED` for the three; a `MISSED`-by-status day overlapping a span evaluates `SUSPENDED`.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:gratitude:test --tests "*CloseTransition*" --tests "*SuspendedDays*" --tests "*StreakAcrossSuspension*"` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, and `./gradlew :modules:gratitude:test`, and see them pass.**
- [ ] **Step 5: Mutation checks** (revert each): let a paused `PARTIAL` close `SOLO` → the lone-entry tests fail; drop the `suspendedAt` term in `isWithheldBy` → the withheld-reveal test fails; pass the stored row's bounds instead of `current`'s → the westward test fails; drop the `EvaluateStreaks` branch and write the gap `EMPTY` in `CreateMissingDays` → the streak test fails.
- [ ] **Step 6: Commit.** `feat(gratitude): days of a suspension are SUSPENDED, and reveals due in one are withheld`

### Task 6: V66, `SuspensionConsole`, and the export keeps the rows withheld

**Files:**
- Create: `modules/admin/src/main/resources/db/migration/V66__admin_account_suspension.sql` (V64's pattern: drop and re-add `audit_log_action_check` with every V64 action plus `ACCOUNT_SUSPENDED`, `ACCOUNT_UNSUSPENDED`)
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/domain/AuditAction.kt`, `.../domain/Disclosure.kt` (first branch: `action == ACCOUNT_SUSPENDED || action == ACCOUNT_UNSUSPENDED -> null`, KDoc citing spec §10)
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/SuspensionConsole.kt`
- Modify: `modules/admin/src/test/kotlin/com/moyi/admin/infra/AdminTestApplication.kt` (a scripted `AccountSuspension` fake bean that records calls and answers what the test sets)
- Modify: `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt` (rule: within `modules/admin/src/main`, only `service/SuspensionConsole.kt` imports `com.moyi.identity.api.AccountSuspension`; positive controls as A5's rule)
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/infra/V66MigrationTest.kt`, `AuditActionCheckTest` (unchanged, must pass), `.../domain/DisclosureTest.kt`, `.../service/SuspensionConsoleServiceTest.kt`, `app/src/test/kotlin/com/moyi/app/admin/AdminAccessDisclosureTest.kt` (extend)

**Interfaces:**
- Consumes: `AccountSuspension`, `SuspensionResult`, `SuspensionOutcome` (Task 1), `FreshStepUp`, `AuditTrail.record`, `ModerationNote`.
- Produces: `SuspensionConsole.suspend(userId: UUID, justification: String, stepUp: FreshStepUp, adminId: UUID, from: ClientAddress?): SuspensionResult` and `lift(...)` with the same parameters; `@ConditionalOnAdminConsole`, `@PreAuthorize("hasRole('ADMIN')")`, `@Transactional`. `ModerationNote.require` first (throws `InvalidModerationNote` before the port); `require(stepUp.adminId == adminId)`; the port; the row: `action = ACCOUNT_SUSPENDED | ACCOUNT_UNSUSPENDED`, outcome `SUCCEEDED` for `SUSPENDED`/`LIFTED` else `REFUSED`, `target_type = "USER"`/`target_id = userId` (both null for `NO_SUCH_ACCOUNT`), `justification` = the trimmed note, `metadata = {"result", "statusBefore" (omitted when null), "deletionPending": deletionDueAt != null}`. Metric `moyi.admin.suspension` tagged `action` (`suspend`/`lift`) and `result`; one INFO line `suspension admin={} user={} result={}`.

- [ ] **Step 1: Write the failing tests.**
  - `V66MigrationTest`: both new actions insert; every V64 action still does; `'ACCOUNT_SUSPEND'` fails.
  - `DisclosureTest`: both actions withheld for every outcome × target type × purpose × `reportDriven` the exhaustive table walks.
  - `SuspensionConsoleServiceTest` (A5's `ContentAccessServiceTest` setup, the scripted fake): each of the six outcomes for each act → one row with exactly the fields above; invalid note (empty, 401 code points, NUL) → `InvalidModerationNote`, port not called, no row; `MODERATOR`/`SUPPORT` → `AccessDeniedException`, port not called, no row; a step-up of another admin → `IllegalArgumentException`, no row; a forced failure after `record` (a throwing `AuditTrail` decorator) → no row committed, while an `ADMIN_STEP_UP` row from a `proveNow` before the call is; the note is in no log line (captured), metric tag or `metadata`.
  - `AdminAccessDisclosureTest`: the suspended member's export, built after a suspension and a lift, equals byte for byte one built before (real ports, app context).
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:admin:test --tests "*V66Migration*" --tests "*Disclosure*" --tests "*SuspensionConsole*"` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, `AuditActionCheckTest`, `./gradlew :app:test --tests "*AdminAccessDisclosureTest*" --tests "com.moyi.app.ArchitectureTest"`, and see them pass.**
- [ ] **Step 5: Mutation checks** (revert each): delete the `Disclosure` branch → `DisclosureTest` fails; remove `@PreAuthorize` → the `MODERATOR` test fails; validate the note after calling the port → the invalid-note test fails (port called).
- [ ] **Step 6: Commit.** `feat(admin): the suspension console and its audit rows, withheld from the export (V66)`

### Task 7: The lookup page, the two POSTs, and the whole path end to end

**Files:**
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/web/SuspensionController.kt`
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/web/UserLookupController.kt` (inject `SecondFactor` for `isAdmin`, display only; the result model gains `suspendForm`/`liftForm` as `DecisionForm(gated = true, askCode = true, back = "/admin/users?q=<id>")` for an `ADMIN`, `onlyAdmin = true` otherwise)
- Modify: `modules/admin/src/main/resources/templates/admin/users.html` (`layout :: result` flash; **Status** row for every role with "since <instant>" when suspended; "paused" on a bond row; the deletion line; the forms through `admin/fragments/decision :: form`)
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/web/SuspensionControllerTest.kt`, `app/src/test/kotlin/com/moyi/app/admin/SuspensionTest.kt`

**Interfaces:** consumes `SuspensionConsole` (Task 6), `SecondFactor.proveNow`, `ModerationPages.uuidOrNull`/`notFound`/`adminId`, `DecisionForm`, `UserAdminView.status/suspendedSince/deletionDueAt`, `BondAdminView.paused`.

**The POSTs** `/admin/users/{userId}/suspend` and `/unsuspend`, params `justification`, `code`: malformed id → `notFound()`; `InvalidModerationNote` → `admin/decide` with the form again (note kept), `422`; `Missing` → `admin/decide`, `200`, error `CODE_NEEDED_FOR_ACT`; `Refused` → `admin/decide`, `401`, `AdminAuthController.WRONG_CODE`; `NO_SUCH_ACCOUNT` → `notFound()`; every other outcome → `ModelAndView(RedirectView("/admin/users?q=<userId>").apply { setStatusCode(HttpStatus.SEE_OTHER) })` with the flash attribute `Decisions.RESULT` (a plain `redirect:` is a 302, not the spec's 303).

Copy (exact): button "Suspend this account" / "Lift the suspension"; others: "Only an ADMIN can suspend an account."; deletion line: "A deletion this person asked for runs on <deletionDueAt>, suspended or not." (instant formatted as the page's other instants); `CODE_NEEDED_FOR_ACT` = "Enter a code from your authenticator app. Each suspension, and each lift, needs a new one." **(plan ruling 3)**; flashes per spec §5's table, the `SUSPENDED` flash followed by the deletion line when `deletionDueAt != null`.

- [ ] **Step 1: Write the failing tests.**
  - `SuspensionControllerTest` (MockMvc + the admin chain + CSRF, scripted fake): each outcome row → its status, view or `303` `Location`, and flash text; note refused → `422` and **no code spent** (the same code then proves `Accepted` through `AdminSignIn.stepUp`); blank code with a fresh `secondFactorAt` → the code form, port not called; a replayed code → `401`, port not called; `MODERATOR`, `SUPPORT` and a hand-made POST → the 403 page, port not called, no row, code not spent; malformed id → 404, no row. The flash, the `Location` and every rendered page contain no note marker. The lookup page shows the forms to an `ADMIN` only, and Status/"paused" to all.
  - `SuspensionTest` (app, real ports; `AdminConsoleDriver` for the console and the API's MockMvc for the members; `ModerationRig`'s couple): the admin suspends A through the page → the flash; A's old access token `401`, refresh `401`, sign-in `401` equal to a wrong password's; B's submit `409 BOND_PAUSED`, `GET` bond and today `paused: true`; no reminder pushed to B at B's reminder time (`DailyRemindersTest`'s capture); the admin lifts (clock past the next TOTP step) → A signs in `200`, B's submit `201`; `audit_log` holds the two rows as Task 6 states. Races: (1) the suspension's transaction held open after the port call (a latch in a test `AuditTrail` decorator) while B submits → `201` (the write lands); after its commit B's next submit → `409`. (2) Two admins suspending at once → one `SUSPENDED` row, one `ALREADY_SUSPENDED` row.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:admin:test --tests "*SuspensionController*"` and `./gradlew :app:test --tests "*SuspensionTest*"` → FAIL.
- [ ] **Step 3: Implement the controller, the lookup model and the template** (`th:text` only; `TemplateSafetyTest` must pass).
- [ ] **Step 4: Run them, `./gradlew :modules:admin:test`, and see them pass.**
- [ ] **Step 5: Mutation checks** (revert each): prove the code before the note → the no-code-spent test fails; remove the class-level `@PreAuthorize` and the service's → the role test fails; return `redirect:` (302) → the `303` assertion fails.
- [ ] **Step 6: Commit.** `feat(admin): suspend and lift an account from the lookup page`

### Task 8: State map, smoke, runbook, ADR-0066, and the whole branch

**Files:**
- Modify: `docs/state-map/data/account.json` (`SUSPENDED` state: from `SIGNED_IN`, `ACTIVE_SIGNED_OUT`, `PENDING_VERIFICATION` by the console; sign-in, refresh, access, reset, verify from it; lift back), `bond.json` (paused rows: the three `BOND_PAUSED` writes, the allowed writes, `paused` on reads, invites), `day.json` (overlap close, withheld reveal), `admin.json` (one row per §5 outcome); every row's `evidenceRef` names a test from Tasks 1–7; then `scripts/state-map-generate`
- Modify: `scripts/smoke.sh` (an "admin A3" section after A2's, spec §12's probes, the refused-address probe to `/admin/users/{id}/suspend` equal to an unknown path's 401; the header comment updated with the run's counts)
- Modify: `ops/admin/README.md` ("What exists" gains A3; a section *Suspending an account* per spec §12; the metric; "What this does not do" loses suspension)
- Create: `adr/0066-the-admin-console-a3-suspension.md` (rulings 1–17, the ✱ departures, the amendments to ADR-0058, ADR-0055, ADR-0034; plan rulings 1–4 below)
- Modify: `docs/learning-log.md` only if Tasks 1–7 went wrong in a way worth keeping

- [ ] **Step 1:** State map; `PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests` and `scripts/state-map-check --strict` → PASS.
- [ ] **Step 2:** ADR and runbook. Confirm `scripts/moyi`'s `write` prints a `409 BOND_PAUSED` as any refusal (read and run once against a paused bond); no command is added (ruling 14).
- [ ] **Step 3:** `./gradlew build` → all green; record test counts.
- [ ] **Step 4:** `scripts/smoke.sh` in an isolated compose project → 0 failed; record passed/failed/skipped and the A3 check count in the header comment.
- [ ] **Step 5:** Commit. `docs(admin): ADR-0066, runbook, smoke and state map for A3 suspension`
- [ ] **Step 6:** Open the PR, title `feat(admin)!: admin console A3, suspending an account and the paused bond`, label `breaking-api-change`; the body lists the corpus amendments owed (doc 04 §8.1, doc 06, doc 07) and moyi-app's owed paused state. Merge only under the 2026-11-11 merge authority after independent review + build + smoke + CI, or by the owner.

---

## Plan rulings on the spec (each to be recorded in ADR-0066)

1. **The deletion date shown and returned is `cancellableUntil`, not `deleteBy`.** The spec names `deleteBy` (§3 ruling 4, §5, §6), but `ExecuteAccountDeletions` carries a request out from `execute_after` = `cancellableUntil` (day 14); `deleteBy` (day 30) is only the outer bound. "Runs on <deleteBy>" would be up to 16 days wrong. Field `deletionDueAt` on `SuspensionResult` and `UserAdminView`.
2. **Erasure leaves a suspended account's span open.** Ruling 15 keeps the span; closing it at erasure would unpause the partner's bond between the erasure's commit and the outbox consumer that ends it. The invariant tested is therefore "`SUSPENDED` ⇔ open span" over live (non-`DELETED`) accounts.
3. **The code prompt's copy** is new (`CODE_NEEDED_FOR_ACT`): `DecisionForm.CODE_NEEDED` says "This cannot be undone", which is false for a suspension.
4. **`BOND_PAUSED` sits immediately after `BOND_ARCHIVED` where that check already is** (spec §6), which in `ReactToEntry` is after `REACTION_ON_OWN_ENTRY`/`ENTRY_IMMUTABLE`; §9's stated precedence ("`BOND_ARCHIVED` → `BOND_PAUSED` → the route's own conflicts") is met relative to `BOND_ARCHIVED` and not reordered against conflicts that already precede it.
