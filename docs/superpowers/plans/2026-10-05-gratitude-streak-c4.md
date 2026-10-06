# Slice C4, streaks — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [x]`) syntax for tracking.

**Goal:** A bond has a streak two people share: it grows with every day both wrote, survives
a missed day when a freeze is banked, and can be recomputed from the bond's days alone.
With it the daily loop is complete (**M3**).

**Architecture:** The rules are a pure fold over a bond's settled days
(`StreakRules.step(state, day, strict)`), so the property tests need no database. The close
job gains its third step (spec §6.4): after days are written and closed, each bond's
settled-and-unevaluated days are evaluated in date order, in one transaction per bond under
the bond's `streak_states` row. What was decided for a day — whether a freeze was applied,
and whether Strict mode was on — is written **on the day**, so `recalculate` replays
decisions and never re-makes them with today's settings.

**Tech stack:** Kotlin, Spring Boot 4.1, Postgres 18 (Testcontainers), JDBC for the
evaluation's writes, JUnit 5 with seeded random timelines.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` §3.3, §5.1, §5.2,
§6.4 step 3, §6.5, §8, §9 ("Streak properties"), §10; doc 04 BR-4, BR-5, BR-10, §8.1–§8.3;
doc 03 FR-070–FR-076; `adr/0033-the-close-job.md` "Owed, C4".

**Stacked on `feat/gratitude-close` (PR #54, unmerged).** Branch `feat/gratitude-streak`,
worktree `.worktrees/feat-gratitude-streak`. Migrations **V17** and **V18** (the second came out of review). Rebase when #54 changes.

## Global constraints

- Never commit to `main`; the owner merges. Draft pull request.
- Conventional Commits, subject ≤ 88 characters. Test first, seen to fail for the stated
  reason; after a task's tests pass, break the mechanism and see them fail.
- A day's status is authoritative once settled (BR-10). The one change evaluation may make
  to a settled day is `SOLO`/`EMPTY` → `FROZEN` when a freeze is spent, once, at evaluation.
- `longestStreak` never decreases (FR-071). Toggling Strict mode never alters a past day
  (FR-073).
- No copy, field or event tells one member that the other has not written (FR-076).
- Entry text never reaches a log, an exception or an event.
- V17 is applied to no shared database: smoke with `MOYI_DB=<name>`.

## Decisions this plan makes (each goes into ADR-0034)

1. **A day is evaluated once, after it is settled, in date order, and never ahead of an
   unsettled day before it.** The close job's steps 1 and 2 can settle a bond's days out of
   order (a missing day is written before an older row is closed), so evaluation is a
   separate step that takes the *prefix* of settled days and stops at the first that is not.
2. **Which bonds to evaluate is derived, not remembered**: any bond with a day that is
   closed and not evaluated. A run that failed half-way is found again by the next.
3. **The decision is stored on the day**: `bond_days.evaluated_at`, `evaluated_as`,
   `evaluated_strict`, `freeze_applied`. Spec §6.5: "persist the applied strict-mode and freeze events alongside
   day outcomes". `recalculate` folds the same rules over those columns and must reproduce
   `streak_states` exactly.
4. **What counts.** `REVEALED` extends the run and is a *complete* day (it advances
   `freezeProgress` and `totalCompleteDays`). `FROZEN` extends the run and is not a complete
   day. `SUSPENDED` is skipped. `SOLO`/`EMPTY` spend a freeze if one is banked and Strict
   mode is off — the day becomes `FROZEN` — and otherwise end the run.
5. **Freezes accrue incrementally** (BR-5): the fourteenth complete day resets progress and
   banks a freeze only if Strict mode is off at that evaluation and fewer than two are
   banked. Never the formula.
6. **A bond that has ended keeps its streak** (doc 04 §8.3): days that end after the bond
   stopped taking writes are not evaluated, so the day it ended on cannot break the run.
7. **Today counts when it is complete.** `streak_states` holds the run through the last
   evaluated day; a read adds today when today is `REVEALED` and directly follows it.
   Nothing else is computed at read time: a run that has lapsed reads zero because the
   close job wrote and evaluated the missed days.
8. **`recalculate` is a service, not a route.** FR-074 asks for an admin operation; there
   is no admin surface or role yet. `RecalculateStreak` is callable and tested; the route
   arrives with `modules/admin`.
9. **Events.** `StreakExtended` and `StreakBroken` (spec §8), ids only, in the evaluation's
   transaction. `streak_events` is the audit log (doc 07): one row per evaluated day that
   changed the state. `MilestoneReached` is C6's, with the milestones endpoint.
10. **Property tests are seeded random timelines in plain JUnit**, not a property-testing
    library: the rules are a fold over a list, and a failing seed is printed.

## Review focus

1. A couple whose streak is 30 and who both write today must see 31 today, not at midnight.
2. A couple with one freeze banked who miss a day must keep their streak, and the day must
   say `FROZEN` — and the lone entry on it, already revealed, must stay readable.
3. A couple in Strict mode who turn it off must not be handed freezes for the past.
4. A bond that ends must keep the streak it had.
5. A run of days the job writes all at once after downtime must give the same streak as the
   same days evaluated one a night.

---

### Task 1: the rules, as a fold

**Files:** `gratitude/domain/Streak.kt` (new), `domain/StreakRulesTest.kt`,
`domain/StreakPropertiesTest.kt`.

**Produces:**
```kotlin
data class StreakState(current, longest, lastCompleteDate, freezesAvailable, freezeProgress,
                       freezesConsumed, totalCompleteDays)          // StreakState.NONE
enum class DayOutcome { COMPLETE, FROZEN_BY_SKIP, MISSED, SUSPENDED }
data class Evaluation(state: StreakState, freezeApplied: Boolean, change: StreakChange)
object StreakRules { fun step(state, date, outcome, strict, freezeApplied: Boolean? = null): Evaluation
                     const val DAYS_PER_FREEZE = 14; const val MAX_FREEZES = 2 }
```
`freezeApplied = null` decides; a value replays (for `recalculate`).

- [x] Table tests: each outcome from each relevant state; the 14th day banks, in Strict it
      does not, at the cap it does not; a missed day with a freeze, without, in Strict.
- [x] Properties over seeded random timelines: `longest` never decreases; replaying the
      recorded decisions reproduces the state (fixed point); inserting a `SUSPENDED` run of
      any length anywhere changes nothing; flipping Strict mode for the *rest* of a timeline
      changes no earlier evaluation.
- [x] Commit `feat(gratitude): the streak rules, as a fold over a bond's days`.

### Task 2: V17 and the closer's view

**Files:** `V17__gratitude_streaks.sql`, `bond/api/BondAccess.kt` (+ adapter, test),
`FlywayMigrationTest`.

- [x] `streak_states` (doc 07 §2's columns), `streak_events` (append-only), and on
      `bond_days`: `evaluated_at`, `evaluated_strict`, `freeze_applied`, with a partial
      index on `(bond_id, date) WHERE closed_at IS NOT NULL AND evaluated_at IS NULL`.
- [x] `BondClosingView.strictMode`.
- [x] Commit `feat(gratitude): V17 — streak state, its audit log, and the decision on the day`.

### Task 3: `EvaluateStreaks`, the close job's third step

**Files:** `service/EvaluateStreaks.kt`, `infra/database/StreakStore.kt`,
`service/CloseElapsedDays.kt`, `api/DayCloser.kt` (`CloseResult.evaluated`),
`service/StreakEvaluationTest.kt`.

Per bond, one transaction: lock (insert-if-absent, then `FOR UPDATE`) the `streak_states`
row; read the bond's unevaluated days in date order; stop at the first unsettled day, and
at the first day that ends after the bond stopped taking writes; fold; write each day's
decision (and `FROZEN` when a freeze was spent), the state, `streak_events`, and the two
outbox events.

- [x] Tests through `DayCloser`: N revealed days → N; a missed day → 0 and `StreakBroken`;
      14 complete days bank a freeze and the next missed day is `FROZEN` with the run kept
      and the lone entry still revealed; Strict mode spends none and banks none; a
      `SUSPENDED` day neither extends nor breaks; a skipped date counts; an ended bond keeps
      its streak; a backlog written in one run equals the same days a night at a time; an
      unsettled older day holds evaluation back; one bond failing stops no other; two runs
      at once evaluate a day once.
- [x] Mutations for each.
- [x] Commit `feat(gratitude): the close job evaluates the streak of the days it settles`.

### Task 4: reading it

**Files:** `service/GetStreak.kt`, `web/StreakController.kt`, `web/StreakResponse.kt`,
`service/GetToday.kt`, `web/TodayResponse.kt`, `GratitudeCrossTenantTest`,
`contracts/openapi.json`, `OpenApiContractTest`.

- [x] `GET /bonds/{bondId}/streak` → `{current, longest, freezesAvailable, freezeProgress,
      strictMode, totalCompleteDays, lastCompleteDate, days: [{date, status}]}`; `days` is
      the heatmap, the last 371 days at most (53 weeks), oldest first.
- [x] `GET /today` gains `streak: {current, longest, freezesAvailable, strictMode}` (doc 06
      §3.4).
- [x] Today counts when complete (decision 7): tested at 30 → 31 on the second submission.
- [x] A non-member gets the one `404`; the route joins the cross-tenant suite.
- [x] Nothing in either payload says who has or has not written beyond the day statuses
      `GET /today` already shares (FR-076); a test names the fields.
- [x] Regenerate the contract. Additive: no new error code, no label.
- [x] Commit `feat(gratitude): GET /streak, and the streak on today`.

### Task 5: `recalculate`

**Files:** `service/RecalculateStreak.kt`, `service/RecalculateStreakTest.kt`.

- [x] Folds the rules over the bond's evaluated days using the stored decisions, under the
      same row lock, and writes `streak_states` with `recomputed_at`.
- [x] Tests: after each scenario of Task 3, `recalculate` changes nothing (fixed point);
      with `streak_states` corrupted by hand it restores it; with Strict mode toggled since,
      it does not re-decide a past freeze; it never changes a `bond_days` row.
- [x] Commit `feat(gratitude): recalculate replays a bond's days and changes nothing`.

### Task 6: M3's tooling

**Files:** `tools/bruno/**`, `scripts/moyi` (a small CLI), `README.md`.

- [x] A Bruno collection: register, verify, login, create bond, invite, accept, write,
      today, edit, delete, streak — with an environment file and no secrets committed.
- [x] `scripts/moyi`: `login`, `write "<text>"`, `today`, `streak`, against `MOYI_API`,
      keeping its token in `~/.config/moyi/` with mode 600. What two people need to run the
      loop for a week by hand.
- [x] Commit `chore(tools): a Bruno collection and a CLI, for a week of the loop by hand`.

### Task 7: run it, record it, open it

- [x] `./gradlew build --rerun-tasks`; smoke with `MOYI_DB`, with probes for `/streak` and
      `today.streak`.
- [x] Whole-slice reviews by lens; fix; one review of the fixes.
- [x] ADR-0034; ADR-0033 "Owed, C4" discharged; spec amended; learning log; corpus.
- [x] Draft pull request, based on `feat/gratitude-close` until #54 merges.

## Not in this slice

Milestones and their event (C6). The admin route for `recalculate`. Suspension of a member
(doc 04 §8.1–§8.2): nothing sets it yet; the rules already skip a `SUSPENDED` day. A
deletion called off (ADR-0033, question 2): the month it leaves is evaluated as missed days.

## As built — where the slice left this plan (ADR-0034)

Two reviews of the built slice changed six things this plan says:

- Decision 6 is narrower: only a day **missed** after the end moves nothing. A day both
  wrote on before the bond ended that day counts.
- Strict mode is the setting the day **ended** under (`bond_strict_mode_changes`, V18),
  not the one in force "at that evaluation" (decisions 4 and 5). A review of the fixes
  found one "last changed at" column could be defeated by toggling twice; it is a history.
- A freeze is not spent on a run of zero, and a stepped-over date does not start a run.
- Decision 7's "adds today" is one more application of `StreakRules.step`, so the freeze
  numbers move with the run.
- Task 4's `days: [{date, status}]` carries a calendar vocabulary (`COMPLETE`, `FROZEN`,
  `MISSED`, `OPEN`), not the day's status: `states.md` §7 forbids drawing a solo day.
- `StreakExtended` is published only for a day both wrote on.
- Task 1's fourth property (and the second, as written) could not fail; ADR-0034
  decision 13 says what replaced them.
