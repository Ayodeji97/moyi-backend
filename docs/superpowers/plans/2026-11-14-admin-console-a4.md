# Admin console A4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends green and is reviewed against the spec before the next.

**Goal:** the jobs page (status, run the close job now, the date check, a bond's streak rebuilt), three
feature flags switched at runtime, and the support inbox, in the admin console.

**Architecture:** `scheduling` gains an `api` (`JobConsole`) over a registry of its eleven locked jobs,
ShedLock's `LockingTaskExecutor` and `job_runs`; `gratitude` exposes `StreakRecalculation` (over
`RecalculateStreak`) and `DaySettlement`; `support` exposes `SupportInbox` over ADR-0057's functions;
`common:core` holds the `FeatureFlags` read port, which `admin.infra.FeatureFlagStore` implements over
V67's `feature_flags`; `scheduling` and `ai` read it. `admin` adds three consoles, three controllers,
four pages and six audit actions.

**Spec:** `docs/superpowers/specs/2026-11-14-admin-console-a4-design.md`. Where this plan and the spec
disagree, the spec wins: stop and report.

## Global constraints

- **V67** only, `modules/admin/src/main/resources/db/migration/V67__admin_flags_and_a4_audit.sql`.
  Re-check `origin/main` before the PR; if V67 has landed, renumber and say so.
- Console services: `@PreAuthorize` on every public method, `@Transactional` where a port and its
  audit row commit together; the audit row through `AuditTrail.record` (`MANDATORY`). Controllers are
  never `@Transactional` (ArchitectureTest) and check, in order: role → ids → note → code → act.
- The justification is `ModerationNote.require`, never restated; it reaches only
  `audit_log.justification`.
- Tests that fail when the mechanism is removed: each task names its mutation and the implementer
  breaks it, sees red, restores it.
- Concurrency tests synchronise on `pg_locks`, with a deadline that fails; never a sleep.
- `./gradlew :modules:<m>:test`, then `ktlintCheck detekt` for each touched module, before a commit.
- Never touch another worktree, the default compose project, port 8080/5432.

## Task 1: the flag read port and V67 (common:core, admin)

**Files:** create `common/core/src/main/kotlin/com/moyi/common/core/FeatureFlags.kt`
(`enum class FeatureFlag(val defaultOn: Boolean, val pauses: String)` with `DAILY_REMINDERS`,
`ON_THIS_DAY_PUSHES`, `AI_EMBEDDINGS`; `interface FeatureFlags { fun isOn(flag: FeatureFlag): Boolean }`);
V67; `modules/admin/.../infra/FeatureFlagStore.kt` (`@Component`, implements `FeatureFlags`:
`isOn` = row's `enabled` or `defaultOn`; plus `internal` `states(): List<FlagState>`,
`lockAndRead(flag): Boolean` (`pg_advisory_xact_lock(67, hashtext(key))`, then the value),
`write(flag, enabled, at, adminId)` (upsert)); `AuditAction` + six values; `Disclosure` explicit case.

**Tests:** `FeatureFlagCheckTest` (CHECK = enum); `FeatureFlagStoreTest` (no row → default; a row
wins; upsert; `lockAndRead` blocks a second transaction, seen in `pg_locks`); `AuditActionCheckTest`
passes; `DisclosureTest` gains "no A4 action is ever shown"; `V67MigrationTest` (table, FK, key check).
**Mutation:** remove the advisory lock → the lock test goes red.

## Task 2: the jobs port (scheduling)

**Files:** create `scheduling/api/JobConsole.kt` (`interface JobConsole { fun jobs(): List<JobStatus>;
fun timerOn(): Boolean; fun runCloseNow(beforeRun: () -> Unit): CloseRun }`; `JobStatus(name, purpose,
schedule, lastStartedAt, lockedUntil, lastFinishedAt, running)`; `sealed interface CloseRun { Ran(...counts);
Busy }`); `service/ScheduledJobsConsole.kt` (the registry of eleven: lock, purpose, cron property and
default; `DefaultLockingTaskExecutor` with `LockConfiguration(now, CloseJob.LOCK, 14 min, ZERO)`);
`infra/LockRows.kt` (reads `shedlock`, UTC); `JobRuns.all()`. Six jobs gain `runs.succeeded(LOCK, now)`.
`ReminderJob`, `OnThisDayJob`, `EmbeddingJob` read their `FeatureFlag` at the start of the one-run
method and skip (still stamping their gauge and `job_runs`: a pause is not a failure).
`SchedulingTestApplication` gains a `SettableFlags` bean. `build.gradle.kts`: `implementation(libs.shedlock...)` already there.

**Tests:** `JobConsoleTest`: eleven jobs listed with their lock names; a `shedlock` row and a
`job_runs` row are shown; `running` only when started after the last finish and still locked;
`runCloseNow` calls `beforeRun` then the closer once and answers `Ran` with its counts; with a
held lock (row `lock_until` in the future) answers `Busy`, `beforeRun` and the closer uncalled; a
`beforeRun` that throws stops the run (closer uncalled) and releases the lock. `JobRunsRecordedTest`:
each of the six writes its row. `FlagPausesJobsTest`: reminders and on-this-day ask for nothing while
their flag is off and ask again when on; embedding skips the sweep. **Mutation:** run without the
lock → `Busy` test red; drop a flag check → its test red.

## Task 3: the switched embedding port (ai)

**Files:** `ai/infra/SwitchedEmbeddingPort.kt` (`space` = delegate's; `embed` checks
`AI_EMBEDDINGS` first and throws `EmbeddingUnavailableException("switched off")`);
`AiConfiguration.embeddingPort` takes `FeatureFlags` and wraps every choice; `ai/build.gradle.kts`
already has `common:core`.

**Tests:** `SwitchedEmbeddingPortTest`: off → throws, delegate never called (a counting delegate);
on → delegate's answer; `space` unchanged when off. `AiConfigurationTest` adjusted.
**Mutation:** remove the check → red.

## Task 4: the streak rebuild and the date check (gratitude)

**Files:** `api/StreakRecalculation.kt` (`fun rebuild(bondId): StreakRebuild`, `MANDATORY`;
`StreakRebuild(result: RebuildResult, before: StreakFigures?, after: StreakFigures?)`;
`StreakFigures(current, longest, freezesAvailable, completeDays)`; `enum RebuildResult { CHANGED,
UNCHANGED, NO_DAYS }`); `service/StreakRecalculationService.kt`; `api/DaySettlement.kt`
(`fun on(date, now): DateSettlement(open, pastDue, uncounted)`); `service/DaySettlementService.kt`;
`infra/database/DateSettlementQuery.kt`. The close job's threshold is extracted once
(`CloseElapsedDays.settledBy(grace, now)`) and both use it.

**Tests:** `StreakRecalculationServiceTest`: outside a transaction → `IllegalTransactionStateException`;
a drifted row → `CHANGED` with before/after, row restored; again → `UNCHANGED`; no days → `NO_DAYS`,
no row written; no outbox row in any case. `DaySettlementTest`: seeded days of a date (open before
the threshold, open after it, closed unevaluated, evaluated, another date) counted exactly.
**Mutation:** report `UNCHANGED` always → red; drop the threshold → red.

## Task 5: the support inbox port (support)

**Files:** `api/SupportInbox.kt` (`openCount()`; `list(actor)`, `read(ticketId, actor)`,
`close(ticketId, actor, note)`, each `MANDATORY`; `TicketRow`, `TicketCase` (redacting `toString`),
`TicketCloseOutcome { CLOSED, NOT_OPEN }`); `infra/database/SupportInboxStore.kt` (`@Component`,
`JdbcClient`, calling the three functions; an unknown function answer fails loudly naming the function).

**Tests:** `SupportInboxTest`: list carries lengths and writes `LISTED` with the actor; read returns
the words, reference, reply-to and confirmed flag and writes `READ`; unknown → null, no `READ`; close
→ `CLOSED`, words and reference null, `CLOSED` row with the note; again → `NOT_OPEN`; outside a
transaction refuses; `TicketCase.toString` has no word. **Mutation:** drop `MANDATORY` → red.

## Task 6: the three consoles (admin service)

**Files:** `service/JobsConsole.kt` (`jobs()`, `settlement(date)` SUPPORT; `runClose(note, adminId, from)`
ADMIN, not transactional itself: note first, then `JobConsole.runCloseNow { record STARTED in a
TransactionTemplate }`, `Busy` → a `REFUSED` row in its own transaction; `rebuild(bondId, note,
adminId, from)` ADMIN `@Transactional`); `service/FlagConsole.kt` (`states()`, `set(flag, enabled,
note, adminId, from)` ADMIN `@Transactional`); `service/SupportConsole.kt` (`openCount`, `inbox`,
`ticket`, `close(ticketId, note, secondFactorAt, adminId, from)` SUPPORT, `@Transactional`, step-up
first). Metrics and one INFO line each, never the note. `AdminTestApplication` gains fakes for
`JobConsole`, `StreakRecalculation`, `DaySettlement`, `SupportInbox` where the module context needs
them.

**Tests (admin module, service level):** `JobsConsoleServiceTest`, `FlagConsoleServiceTest`,
`SupportConsoleServiceTest`: each outcome's row (action, outcome, target, metadata, justification);
role refusals (`AccessDeniedException`, no row); note refused before any port call; `STARTED` row
committed before the closer is called; a failing audit write stops the run; `UNCHANGED` refused;
stale step-up throws `StepUpRequired` before the port.

## Task 7: the pages (admin web, app tests, ArchitectureTest)

**Files:** `web/JobsController.kt`, `web/FlagsController.kt`, `web/SupportInboxController.kt`
(`@Controller("adminSupportController")`); templates `jobs.html`, `flags.html`, `support.html`,
`ticket.html`; `layout.html` nav; `home.html` count; `DecisionForm.Wording.JUSTIFICATION`.
`ArchitectureTest`: `declaredIn` and the admin's allowed `gratitude.api` types; the
`feature_flags`-writer rule. `app` test `FeatureFlagsWiringTest`.

**Tests (app, through the real chain):** `JobsPageTest`, `FlagsPageTest`, `SupportInboxPageTest`:
pages per role (SUPPORT: jobs and inbox yes, forms no, flags 403; MODERATOR same; ADMIN all), each
POST's 303 and flash, 422/400/401/404 paths, no CSRF 403, the audit rows, nothing secret in any
`Location`, flash or log; a flag switched off through the page stops the reminder job's next run.

## Task 8: documents, state map, smoke

ADR-0067; ADR-0034/0050/0055/0057 amendment lines; `ops/admin/README.md`; `docs/state-map/data/model.json`
and `admin.json` + `scripts/state-map-generate` and `scripts/state-map-check`; `scripts/smoke.sh`
"admin A4" section and its header line; `docs/learning-log.md` entry; `./gradlew build`;
`scripts/smoke.sh` on a private compose project (`moyi-a4`: Postgres 55481, Valkey 56401, MinIO
59481, app 8099/9099).

## Plan rulings

1. The close run's `beforeRun` callback is how the admin's audit row is written inside the lock
   without `scheduling` knowing `admin` (spec ruling 5).
2. Flag-paused jobs still stamp their last-success gauge and `job_runs`: a deliberate pause is not a
   stopped job; the flags page says which are off.
