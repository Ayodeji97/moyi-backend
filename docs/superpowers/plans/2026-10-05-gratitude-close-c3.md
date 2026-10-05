# Slice C3, the close job — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** The daily loop runs without anybody submitting: every fifteen minutes a job settles
the days that have ended (`OPEN → EMPTY`, `PARTIAL → SOLO` with the lone entry revealed),
releases the days waiting on a reveal time, and writes the days nobody opened.

**Architecture:** `modules/scheduling` owns the trigger, ShedLock and the counters, and
calls `gratitude.api.DayCloser`. `gratitude` owns what a crossing does to a day, as
transitions on `BondDay` beside the ones the synchronous path uses, each applied to **one day,
in one transaction, under that day's row lock and no bond lock**. `bond.api` gains a
closer-facing read of a bond's calendar that needs no caller.

**Tech stack:** Kotlin, Spring Boot 4.1, Postgres 18 (Testcontainers), JPA, Spring
`@Scheduled`, ShedLock (JDBC provider), Micrometer.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` §2.2, §6.3 (row 4),
§6.4, §9, §12.3; `adr/0031-…` "Owed, C3"; `adr/0032-…` "Owed, C3".

**Branch `feat/gratitude-close`**, worktree `.worktrees/feat-gratitude-close`. It began
stacked on PR #53; #53 merged on 2026-10-05 and the branch was rebased onto `main`.
Migrations **V15** and **V16**.

## Global constraints

- Never commit to `main`; the owner merges. Draft pull request.
- Conventional Commits, subject ≤ 88 characters. Test first, seen to fail for the stated
  reason. After a task's tests pass, break the mechanism and see them fail.
- A concurrency test synchronises on a lock held or a statement blocked, never on a sleep.
- **The closer takes no bond lock** (ADR-0031 decision 18, ruled by the owner). The day's row
  lock and the unique `(bond_id, date)` are all that stand between it and a live submission.
- **The closer never holds two days of one bond at once.** One day, one transaction.
- Every window comes from the bond's timeline — never a zone's natural midnight, never a
  stored `ends_at` trusted as final.
- Entry text never reaches a log, an exception or an event.
- V15 is applied to no shared database: smoke with `MOYI_DB=<name>`.
- "Verified" means executed and seen.

## Decisions this plan makes (each goes into ADR-0033; the first two amend the spec)

1. **`DayCloser` is not per-zone.** Spec §2.2 sketches `closeElapsedDays(zone: ZoneId)`. §6.4,
   written later and amended twice, makes that unbuildable: a day's end comes from its bond's
   timeline, the bond's *current* zone is the wrong question for a day opened under another,
   and the sweep must find days "regardless of the bond's current status or current
   requested zone". The port is `closeElapsedDays(now: Instant, budget: Int): CloseResult`,
   and the job passes the clock. Nothing iterates zones.
2. **A stored `ends_at` is a complete filter, though not a final answer.** `ends_at` only
   ever moves later (decision 13), so a day's true end is never before its stored one. So
   `WHERE closed_at IS NULL AND ends_at <= :now` finds every day that *may* have ended; each
   candidate is then re-decided under its lock against the timeline. No bond is scanned to
   find days that have a row.
3. **One day per transaction, oldest first within a bond.** A failure on one day costs that
   day, the next run retries it, and a rolling deploy that kills the job mid-run loses
   nothing committed.
4. **A joining day is reconciled before it is closed** (ADR-0032, Owed): the closer runs
   `resumeJoiningDay` and the reveal rule under the day's lock first, and
   `resumeJoiningDay` refuses a day with `closedAt` set. Without both, a closed joining day
   resumes into a state nothing settles.
5. **`SUSPENDED` days that are not the joining day get `closedAt` and nothing else**: status
   kept, entries not revealed (§6.4 step 2).
6. **`SOLO` reveals the lone entry** — `entries.revealed_at`, in the closing transaction —
   and a `DayClosed` event is written for every day the closer settles.
7. **Missing days are written closed.** A day nobody opened is inserted as `EMPTY` (or
   `FROZEN` for a label the timeline skipped, without spending a freeze) with `closedAt`
   set, never opened and then closed in a second step. At most 400 a run across all bonds.
8. **The idempotency-key reaper rides the same module** (ADR-0031, Owed): a second
   `@Scheduled` method under its own ShedLock name, deleting keys past their 24 hours.
9. **Streaks are not evaluated** (§6.4 step 3 is C4's). `CloseResult` reports which bonds
   changed so that C4 has somewhere to attach.

## Review focus

Conditions the spec implies that are most likely to hurt a couple if missed:

1. A member submits in the seconds the closer is settling their day — the entry must land
   on a day that can hold it or be refused, never on a closed one. (Task 4)
2. A couple who paired but never wrote on their joining day — it must end `EMPTY`, not stay
   `SUSPENDED` for ever, and their earlier solo days must stay private. (Task 3)
3. A day lengthened by a westward zone change — it must not close at its old midnight.
   (Task 3)
4. A bond that has been quiet for months — the backlog drains in bounded batches and
   today's lazily opened row does not hide the gap behind it. (Task 6)
5. Two instances during a rolling deploy — one runs; and if the lock is lost anyway, two
   runs still settle each day once. (Tasks 4, 7)

---

### Task 1: `bond.api` — a bond's calendar for a caller with no user

**Files:** `modules/bond/.../api/BondAccess.kt`, `.../service/BondAccessAdapter.kt`,
`.../infra/database/BondRepositories.kt`, tests in `modules/bond/.../api/`.

**Produces:**
```kotlin
class BondClosingView internal constructor(
    val bondId: UUID,
    val activeSince: Instant?,        // null while PENDING_MEMBER
    val endedAt: Instant?,
    val revealTimeLocal: LocalTime?,
    val anchorTimeline: BondAnchorTimeline,
)
interface BondAccess {
    fun closingViewOf(bondId: UUID): BondClosingView?            // read-only, no guard, no lock
    fun bondsToSweep(after: UUID?, limit: Int): List<UUID>       // paired at some time; keyset by id
}
```
- [x] Test: `closingViewOf` for a pending, an active, an archived and an unknown bond;
      `activeSince` is the second member's `joined_at`; the timeline answers `dayBoundsAt`.
- [x] Test: `bondsToSweep` pages every bond that ever had two members, in id order, and
      never returns a bond that is still `PENDING_MEMBER`.
- [x] Architecture: the port has no `userId`, so document on it that it is for the system's
      own jobs and must never be reachable from a controller; add a Konsist rule that only
      `gratitude.service` and `scheduling` may call it.
- [x] Implement; `./gradlew :modules:bond:check`; commit `feat(bond): a closer-facing view`.

### Task 2: the close, as transitions on `BondDay`

**Files:** `gratitude/domain/BondDay.kt`, `domain/RevealTransitionTest.kt` → split a new
`domain/CloseTransitionTest.kt`.

**Produces:** `BondDay.closedAt(now, elapsed)`-style pure function:
```kotlin
fun close(now: Instant): BondDay   // caller has already proved the day elapsed by the timeline
```
- [x] Tests, one per row: `OPEN → EMPTY`; `PARTIAL(1) → SOLO`; `PENDING_REVEAL → REVEALED`
      with `revealedAt = now` if unset; `SUSPENDED → SUSPENDED` with `closedAt`; an already
      settled day returns itself; `closedAt` is truncated to microseconds; a `PARTIAL(2)`
      day is refused (`check`) — it can only exist in memory.
- [x] `resumeJoiningDay` returns `this` when `closedAt != null`; test.
- [x] `revealWhenDue` on a `PENDING_REVEAL` day past its time (exists; add the test that it
      is what the sweep calls, with a day whose time passed *yesterday*).
- [x] Commit `feat(gratitude): closing a day, as the aggregate decides it`.

### Task 3: `CloseDay` — one day, one transaction, one lock

**Files:** `gratitude/service/CloseDay.kt` (new), `infra/database/BondDayStore.kt`,
`service/RevealDay.kt`, `web/…` none; tests `service/CloseDayTest.kt` (integration).

**Consumes:** `BondAccess.closingViewOf`, `BondDay.close`, `RevealDay.apply`,
`EntryStore.findForDayFresh`, `EventPublisher`.

**Produces:** `CloseDay.settle(dayId: BondDayId, now: Instant): Outcome` where `Outcome` is
`CLOSED`, `REVEALED`, `NOT_YET`, `ALREADY_SETTLED`.

Order inside the transaction, and why each step is where it is:
1. `days.lockAndFind(id)` — nothing is decided from a row read before this.
2. Already settled → `ALREADY_SETTLED`.
3. `closingViewOf(bondId)`; `extendedTo(timeline window)` — a stale `ends_at` is brought up
   before anybody asks whether the day is over.
4. `resumeJoiningDay(activeSince)` then `revealWhenDue(revealTimeLocal, now)` via
   `RevealDay.apply` — the joining day and a due `PENDING_REVEAL` are handled by the same
   rules the synchronous path uses.
5. If `now < endsAt` → persist what changed, `NOT_YET` (or `REVEALED`).
6. `close(now)`; for `SOLO`, stamp the lone live entry's `revealed_at` (fresh read);
   publish `DayClosed {bondId}` (ids only; none for a `SUSPENDED` day); persist.

*As built:* the outcome for a closed day is `ALREADY_CLOSED`. One branch has no test: a day
that reaches its end still `PENDING_REVEAL` with a reveal time later than that end, which
only a day cut short by an eastward zone change can produce. Task 8's matrix owes it.

- [x] Tests (HTTP to build state, then `CloseDay` directly with a moved clock): each
      transition; `SOLO` makes the lone entry readable by the partner through
      `GET /bonds/{id}/days`-less means — assert `revealed_at` and `canBeReadBy`; an `EMPTY`
      day; a joining day with zero, one and two entries; a pre-pairing `SUSPENDED` day gets
      `closedAt`, keeps its status, reveals nothing; a day extended westward is `NOT_YET` at
      its old midnight and `CLOSED` after the new one; a `PENDING_REVEAL` day before and
      after its time; run twice → second is `ALREADY_SETTLED`, one `DayClosed`.
- [x] Privacy: a probe CHECK violated by the closing `UPDATE` — neither member's words in
      output; the redaction is applied here too.
- [x] Mutations: skip step 3 (the westward test goes red); skip step 4's resume (the
      joining tests go red); drop the entry stamp in step 6.
- [x] Commit `feat(gratitude): CloseDay settles one day under its own lock`.

### Task 4: the closer against a live submission

**Files:** tests only, unless they find something: `web/CloseRaceTest.kt`.

- [x] A submission holds the day (blocked from a second connection on its insert); the
      closer waits on the day lock; the submission commits; the closer then sees
      `PARTIAL(1)` or `REVEALED` and decides on that.
- [x] The closer holds the day; a submission waits; the closer commits `EMPTY`; the
      submission is redirected once (BR-3a) or refused `409 DAY_CLOSED` — never filed on
      the closed day. (`SubmitEntrySettledDayTest` simulates the closer by hand today; these
      replace the hand with `CloseDay`.)
- [x] A `PATCH` and a `DELETE` against the closer, both orders.
- [x] Two `CloseDay.settle` calls on one day at once: one `DayClosed`.
- [x] Mutation: remove `lockRow`; these go red.
- [x] Commit `test(gratitude): the closer and a live write, both orders`.

### Task 5: the sweep of days that have a row

**Files:** `gratitude/api/DayCloser.kt` (new package `api`), `service/CloseElapsedDays.kt`,
`infra/database/GratitudeRepositories.kt`, V15 adds the index it needs.

**Produces:**
```kotlin
interface DayCloser { fun closeElapsedDays(now: Instant, budget: Int): CloseResult }
data class CloseResult(val closed: Int, val revealed: Int, val created: Int, val bondsChanged: Set<UUID>, val backlog: Boolean)
```
- [x] Candidate query, keyset-paged, ordered `(bond_id, date)`: unsettled rows with
      `ends_at <= :now`, **plus** `PENDING_REVEAL` rows regardless of `ends_at`. The existing
      partial index is on `(status, date)` and omits `SUSPENDED`; V15 adds
      `bond_days (ends_at) WHERE closed_at IS NULL`. `EXPLAIN` test that it is used.
- [x] Each candidate → `CloseDay.settle` in its own transaction; an exception on one is
      logged (ids only) and counted, and the sweep goes on.
- [x] Tests: a mixed set across three bonds and four zones settles in one call; a failing
      day does not stop the rest; `budget` is honoured and `backlog` says so; an ended
      bond's `PARTIAL` day still becomes `SOLO`; a second call is a no-op.
- [x] Commit `feat(gratitude): DayCloser sweeps every day that may have ended`.

### Task 6: the days nobody opened

**Files:** `service/CreateMissingDays.kt`, `infra/database/BondDayStore.kt`.

- [x] For each bond from `bondsToSweep`: walk its timeline from `activeSince` to
      `min(endedAt, now)`, one window at a time (`dayBoundsAt(previous.endsAt)`), and collect
      windows that have ended and have no row. Labels the timeline skipped between an
      interval's last label and its successor's first are `FROZEN`.
- [x] Insert each as a **closed** row (`EMPTY`/`FROZEN`, `closedAt = now`) with
      `ON CONFLICT (bond_id, date) DO NOTHING` — a submission that opened it first wins and
      Task 5 settles it. No lock is needed for a row that does not exist.
- [x] At most 400 inserts a run; resume from the bond and date reached; `backlog = true`
      and a `WARN` with the count.
- [x] Tests: interior gaps behind a newer lazy row; nothing before `activeSince`; nothing
      after `endedAt`, but gaps before it are filled; a skipped label is `FROZEN`; a bond
      quiet for 500 days drains in two runs; a degenerate (empty) window is not written;
      racing a submission for the same date leaves one row.
- [x] Commit `feat(gratitude): days nobody opened are written, closed`.

### Task 7: `modules/scheduling`

**Files:** `modules/scheduling/**` (new sources), `V15__scheduling_shedlock.sql`,
`gradle/libs.versions.toml`, `app/build.gradle.kts`, `ArchitectureTest.kt`.

- [x] V15: `shedlock(name, lock_until, locked_at, locked_by)` and Task 5's index.
- [x] `CloseJob`: `@Scheduled(cron = "0 */15 * * * *")`,
      `@SchedulerLock(name = "close-days", lockAtMostFor = "PT14M", lockAtLeastFor = "PT30S")`,
      calls `DayCloser`, sets `gratitude_close_job_last_success_timestamp` on **every**
      successful run and adds to `gratitude_bonds_closed_total`. Off by property in tests
      (`moyi.scheduling.enabled`), so no test depends on a timer.
- [x] `ReapIdempotencyKeys`: hourly, its own lock name, `IdempotencyKeyStore.deleteExpired`.
- [x] Tests: the job calls the port and moves both meters; with the lock row held by
      another name-holder the job does not run; a run that throws does not move
      `last_success`; the reaper removes an expired key and keeps a live one.
- [x] Architecture rule: `scheduling` depends on `gratitude.api` and `common` only.
- [x] Commit `feat(scheduling): the fifteen-minute job, ShedLock on Postgres, two counters`.

*As built, Tasks 5–7:* the budget counts days closed, revealed or failed, not days looked at.
Migrations are **V15** (`gratitude`, the close job's index) and **V16** (`scheduling`,
`shedlock`, with zoneless timestamps — see the migration). Not tested: that the failure log omits the exception's message (`CloseDay` already redacts
what reaches it). Task 8 found the one branch Task 3 could not test to be unreachable: no
day reaches its end with a reveal time still ahead, because the time is read on the day's
own date. It stays as a guard.

### Task 8: the timezone matrix, end to end

**Files:** `gratitude/.../web/CloseMatrixTest.kt`.

- [x] Spring forward (23 h), fall back (25 h), `Asia/Kathmandu`, `Pacific/Chatham`, members
      twelve hours apart, a date-line crossing east (a skipped label → `FROZEN`) and west (a
      merged day closes once, at the later end). For each: the day is `NOT_YET` one second
      before its end and settled at it.
- [x] `the job run twice changes nothing the second time` over the whole matrix.
- [x] Commit `test(gratitude): the close job over the timezone matrix`.

### Task 9: run it, record it, open it

- [x] `./gradlew build --rerun-tasks`; counts.
- [x] Smoke: the job cannot be time-travelled over HTTP. Add a probe that the scheduler is
      registered and `gratitude_close_job_last_success_timestamp` appears on
      `/actuator/metrics` after startup (the job runs once on boot in the `local` profile),
      and run `MOYI_DB=moyi_c3_smoke scripts/smoke.sh`.
- [x] Two whole-branch reviews (concurrency and time; spec, privacy and tests), fixed.
      *As built:* a one-minute settle margin (ADR-0033 decision 5), the deletion
      cooling-off, per-bond isolation, lock-time stamps, `FROZEN` at the handoff.
- [ ] ADR-0033; ADR-0031 and ADR-0032 "Owed, C3" discharged; spec amended at §2.2 and
      §6.4; learning log; README module list; corpus copies and doc 05's ShedLock sentence.
- [ ] Draft pull request; update `.claude/HANDOVER.md`.

## Not in this slice

Streak evaluation, freezes consumed, `StreakBroken`/`StreakExtended` (C4). The outbox
poller (C5). A `lock_timeout` outside tests (the deploy slice) — but note in ADR-0033 that
the closer, holding only a day, is the writer least exposed to it.
