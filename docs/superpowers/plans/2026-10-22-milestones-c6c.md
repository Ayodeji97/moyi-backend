# Slice C6c, milestones — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps
> use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /bonds/{bondId}/milestones` answers which of the four milestones (7, 30, 100,
365 complete days) the bond has reached, each with the date of the day that reached it, and
the next threshold while the bond takes writes; and the close job's evaluation writes one
`MilestoneReached` (ids only) for each threshold a day takes the bond across.

**Architecture:** No migration. The rule is one object in `gratitude.domain`
(`Milestones`: the thresholds, which a day crosses, which are reached, which is next). The
event is written by `EvaluateStreaks`, beside `StreakExtended`, in the transaction that
records the day, from the same fold's `totalCompleteDays` before and after. The read dates
each milestone by the Nth day of the bond's timeline evaluated `COMPLETE` (`bond_days`, one
statement in `StreakCalendar`), and adds today, complete, by the rule `GET /streak` already
applies, extracted from `GetStreak.view` into `GetStreak.countsToday` so that one function
decides it for both. `next` is `null` whenever the caller's membership takes no writes.

**Tech Stack:** Kotlin, Spring Boot 4.1, Jackson 3 (`tools.jackson`), Postgres 18
(Testcontainers), `JdbcTemplate`, Konsist, Kotest matchers, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` §1 (milestone cards),
§5.2 (the C6 milestones row), §6.5, §7 (the C6c row), §8, §10, §11, §13. The C6 ledger
`.superpowers/sdd/2026-10-15-c6/progress.md`, section "C6c (2026-10-22)" (binding; restated
below). `c6-corpus.md` §4 and §5. ADR-0034 (decisions 1, 5, 7, 8, 9, 10, 11; Owed "C6"),
ADR-0033 (question 1 as ruled on 2026-10-21), ADR-0035 (ids-only payloads; question 9 as
ruled), ADR-0038 ruling 6, ADR-0039 decisions 14, 16, 17, 20, 21. Doc 03 FR-070, FR-071,
FR-074, FR-075, FR-076; doc 06:174; doc 04 BR-4, BR-10; doc 12:66.

**Branch:** `feat/gratitude-milestones`, worktree `.worktrees/feat-gratitude-milestones`, cut
from `feat/gratitude-search` @ `6116756` (C6b, PR #68, unmerged). When #68 merges:
`git rebase --onto origin/main 6116756 feat/gratitude-milestones`. (Done after Task 3:
`6116756` is C6b's branch tip and not on `main`; #68 squash-merged as `dcb3b36`, and the
branch was rebased onto it. ADR-0041 names commits as they stand after the rebase.) **No
migration.**
**ADR-0041** (ADR-0040 and V26 are held by the peer session gratitude-0f, whose close-job
pull request merges after this one; ping it when C6c merges).

## The rulings this plan implements

**The owner's (Daniel, 2026-10-22, asked directly):**

- **Q-M1.** Milestones count `totalCompleteDays`: cumulative, days both wrote on; never lost;
  a covered or missed day is never celebrated.
- **Q-M2.** Exactly four thresholds, 7, 30, 100 and 365; nothing after.
- **Q-M3.** `next` is the threshold only: no days-remaining field and nothing else a
  countdown could be read from.
- **Q-M5.** Reached at the reveal: `GET /milestones` counts today at read time once both have
  written, as `GET /streak` does; `MilestoneReached` is still written when the close job
  evaluates the day.

**The controller's defaults (2026-10-22; each "the owner's to turn" in ADR-0041):**

1. Q-M4: an ended bond answers `achieved` as history and `next: null`, byte-identical for
   left, blocked and the deletion countdown (the `takesWrites` predicate of ADR-0038 ruling 6
   and ADR-0039 decision 14; "check whether 'ended' here should be `takesWrites`": it should,
   decision 7).
2. M2: `achievedOn` is derived from the `bond_days` timeline (the Nth day evaluated
   `COMPLETE`) plus today at read time; no migration; it survives `recalculate` and account
   deletion.
3. M5: `MilestoneReached {bondId, bondDayId}`, ids only, one per threshold crossed, written in
   the evaluation's transaction, idempotent per bond and day, never from `recalculate`.
4. H-M1: no server-side per-member "seen" or "celebrated" state; novelty is the client's,
   from `achievedOn`.
5. H-M2: bond-level only; no per-member count.
6. H-M3: thresholds and dates only; no entry id, no text.
7. H-M5: any future push reaches both members or neither (recorded for Phase 4).
8. M6: spec §11 is amended: milestones have no frame (`states.md` §6's row).

## Global constraints

- Never commit to `main`; never push; the owner merges. Conventional Commits; the part after
  `type(scope): ` is at most 88 characters (`git config core.hooksPath .githooks` once, so the
  hook checks it). The scope is one word of `[a-z0-9-]`: `feat(gratitude)`, never
  `feat(gratitude,app)`.
- Test first, seen to fail for the stated reason. After a step's tests pass, apply each
  mutation the step names, watch the named test go red **for the stated reason** (read the
  failure message: a context that fails to start, or a compile error, is not the test seeing
  the mechanism), restore it, and check the restore with `git diff --quiet -- <file>`. Say in
  the report which mutations were run and what each turned red, and which survived. **A
  predicted outcome marked "to be confirmed by running" is a prediction**: if the run
  disagrees, report what was seen; do not change the test to match the prediction.
- No sleeps in tests.
- `contracts/openapi.json` is regenerated by running `OpenApiContractTest` and copying
  `app/build/openapi/openapi.json` over it. Never edited by hand. Read the diff: it must add
  one path and three schemas and change nothing else.
- **No new `ErrorCode`, no migration.** The contract change is additive; the pull request
  does not carry `breaking-api-change`. If a step finds it needs either, stop and report.
- **Ids only in an event** (spec §8, ADR-0035): `MilestoneReached`'s payload is
  `{bondId, bondDayId}`. No threshold, no count, no member.
- **Nothing in the milestones response names a member** (FR-076, H-M2): thresholds and dates.
- A blocked bond and a left bond are indistinguishable to either member in every byte
  (`states.md` §9), and so is the deletion countdown here.
- `git status --porcelain | grep -v '^?? \.kotlin/'` before each commit; never filter status
  by the word "kotlin". Never bare `git stash`.
- When the spec and the code disagree, stop and report. Do not bend a test.

## Decisions this plan makes (each goes into ADR-0041, apart from the rulings)

1. **The rule lives in one place: `gratitude.domain.Milestones`.** `THRESHOLDS` (7, 30, 100,
   365), `crossed(before, after)` (the thresholds a step from `before` complete days to
   `after` takes the bond across), `achieved(total, dates)` and `next(total)`. The event and
   the read both ask it. *Reason:* CLAUDE.md, "a rule lives in one place"; a second statement
   of "which thresholds" in the read and the evaluation would drift.
2. **A milestone counts days evaluated `COMPLETE`, and is dated by the Nth of them**, read
   from `bond_days` (`evaluated_as = 'COMPLETE'`, ordered by date), never from
   `streak_states`. *Reason:* `streak_states` holds a count and one date, not the date of the
   7th or the 30th day (c6-corpus M2); the timeline is what `recalculate` trusts (FR-074); and
   a drifted projection must not move a date the couple were shown. **Why the two agree:**
   `StreakRules.complete` is the only step that adds to `totalCompleteDays`, it adds exactly
   one, and it runs for exactly the days recorded `COMPLETE`; so the count of `COMPLETE` days
   is `total_complete_days` whenever the projection is true (read; held by
   `MilestonesEndpointTest`'s drift test, which shows the read does not depend on it). A day
   both wrote on keeps `REVEALED` and `COMPLETE` through a tombstone, a withdrawal's erasure
   and an account deletion (BR-10: a settled status is never recomputed from entries), so a
   date survives all three.
3. **One statement gives the count and the dates**, `StreakCalendar.completeDays(bondId,
   ordinals)`: `row_number()` and `count(*)` over the bond's complete days, keeping the rows
   whose number is asked for and the last one (which carries the count when no threshold is
   reached). The ordinals travel as one bound parameter (`?::int[]`), never concatenated into
   the SQL. *Reason:* two statements could give a count and a set of dates of two moments.
4. **Today is counted by `GET /streak`'s rule, which becomes one function**,
   `GetStreak.countsToday(membership, today, todayComplete)`: today is complete **and** it
   directly follows the last evaluated day (or is the bond's joining day with nothing
   evaluated). `GetStreak.view` calls it; `GetMilestones` calls it. `todayComplete` is
   "today's square is `COMPLETE`" as `StreakCalendar.between` draws it, which is how
   `GetStreak.detail` reads it: the day has **revealed**. *What "both have written" means
   precisely (Q-M5):* the day is `REVEALED`. On a bond with no reveal time that is the moment
   the second entry arrives; on a bond with one, it is the reveal time, and a day both have
   written that waits for it (`PENDING_REVEAL`, an `OPEN` square) is not counted, by
   `GET /streak` either. When the job is behind (yesterday is over and not evaluated), today
   waits, as the streak's number does. *Reason:* two screens that disagree about today for a
   quarter of an hour are a defect the owner would see; one function is the only way to keep
   them together.
5. **The read takes the timeline first, then today's square, then the last evaluated date**
   (inside `countsToday`), with no lock. An evaluation committing between them can leave one
   response a day behind; it cannot count a day twice, because today is never evaluated while
   it is today and an evaluated today is never added (ADR-0034, Consequences: "a read is two
   statements with no lock").
6. **The response is `{achieved: [{threshold, achievedOn}], next: {threshold} | null}`, and
   nothing else**, `achieved` oldest first. **No `totalCompleteDays`.** *Reason:* Q-M3, and the
   corpus's own reason for it (M3): leaving the count out "keeps it from being computed by
   accident" — with it, "days to next" is one subtraction on one response. `GET /streak`
   already sends `totalCompleteDays` (FR-070's number for the streak screen) and keeps it, so
   the server cannot make a countdown impossible; it does not hand one over on this response,
   and a client that joins the two does so on purpose. Whether the streak screen shows the
   total at all is a design question, recorded (ADR-0041 question).
7. **`next` is `null` whenever the caller's membership takes no writes** (`!takesWrites`:
   archived by a leave or a block, the member who left, the deletion countdown), and after
   365. `achieved` does not change with an ending: it is history (doc 02:127, "the archive is
   never diminished"). *Why `takesWrites` and not "archived":* it is the predicate ADR-0038
   ruling 6 and ADR-0039 decision 14 use for every read that answers differently on an ended
   bond, so one bond is "ended" the same way on every screen; the owner's ruling of 2026-10-21
   on ADR-0033 question 1 (only an archived bond counts as ended) covers the lone-entry reveal
   and nothing else, and says so ("reads gated on the bond taking no writes are unchanged by
   it"); a countdown has no future while it runs, and calling it off brings `next` back. *Asked
   of the guard's membership*, as `GET /streak` asks `isOpen`, not of one resolved again
   (`membershipNow`, ADR-0039 decision 14): an ending that commits while the joining-day
   reconcile waits gives the answer from before it, `next` set for left and blocked alike,
   which says nothing of how it ended and nothing about an entry. The alternative costs one
   more membership resolution per request; the controller's to turn.
8. **`MilestoneReached` is written by `EvaluateStreaks`, per evaluated day, after
   `record`**: for each threshold in `Milestones.crossed(before.totalCompleteDays,
   after.totalCompleteDays)`, `OutboxEvent("BondDay", dayId, "MilestoneReached",
   {bondId, bondDayId}, now)`, the aggregate as `DayRevealed` and `DayClosed` have it. One
   step adds at most one complete day, so a day crosses at most one threshold; a backlog
   crosses several on several days, each its own event with its own `bondDayId`.
   **Idempotent per bond and day by construction**: it shares the transaction with
   `StreakStore.recordDay`, which updates only `WHERE evaluated_at IS NULL` and refuses
   anything but one row, so a day is announced once or not at all, and a bond that fails rolls
   both back. **Never from `RecalculateStreak`** (ADR-0034 decision 10). Counted on the fold's
   `totalCompleteDays` under the bond's streak lock, which equals the timeline's count while
   the projection is true (decision 2).
9. **It is written on an ended bond too.** A day both wrote on before the bond ended is
   complete (ADR-0034 decision 7), and the event is history. Doc 02:144's "no notifications"
   on an archived bond binds Phase 4's consumer, which must ask whether the bond takes writes
   before it tells anybody (ADR-0041, Owed). *Reason:* an event that depends on what a
   consumer will do with it is the consumer's rule written in the producer.
10. **The route:** `GET /api/v1/bonds/{bondId}/milestones`, `MilestonesController.milestones`
    (the operationId), the guard `BondAccess.guard` (ADR-0039 decision 21), then
    `ReconcileJoiningDay.beforeRead` as `GET /streak` runs it (a joiner's first request may
    be this one), then `GetMilestones.of`. No parameters. A member who has left reads it.
11. **The API's default `Cache-Control`** (`no-cache, no-store, max-age=0, must-revalidate`,
    `Pragma`, `Expires`), as ADR-0039 decision 17 built and the controller accepted; no
    `ETag`; no rate bucket of its own (the global 120 a minute; the read is three indexed
    statements on one bond).
12. **No per-member state, held by the constructor** (H-M1): `GetMilestones` is
    `@Transactional(readOnly = true)` and is handed `StreakCalendar`, `GetStreak` and `Clock`,
    nothing else; `ArchitectureTest` pins that list, as it pins `DailyPrompt`'s (ADR-0038). A
    store that remembered who had seen which milestone would have to be handed in.
13. **Nothing is logged, and no `SecretsNeverLoggedTest` probe is added.** The route takes a
    bond id and returns thresholds and dates; the event carries two ids. A probe that watched
    for words could not fail here (ADR-0039, How this was checked: a probe answered
    `{"items":[]}` and so could not see a `toString` that printed words).
14. **The contract's description states the four thresholds in words**, a mirror of
    `Milestones.THRESHOLDS` as `SEARCH_MAX_GRAPHEMES` mirrors `SearchTerms`'s: the domain test
    holds the list, the contract test holds the sentence. Two copies of a constant that the
    owner ruled fixed; recorded.
15. **The state map gets one card and five rows**, in the bond machine (`NO_BOND`,
    `PENDING_MEMBER`, `ACTIVE`, `PENDING_DELETION`, `ARCHIVED`), as the streak's bond rows are
    drawn. *Reason:* what changes the answer is the bond's state (the countdown and the
    archive null `next`); today's day status changes it only at `REVEALED`, which the
    `ACTIVE` row's reason says. The checker demands a row from every built state of a region
    an action appears in.
16. **The CLI is `moyi milestones`; Bruno gains `23 Milestones`.**

## Review focus

The five failure modes most likely to reach a person, each pinned by a test in the task that
owns the code:

1. **The two screens disagree about today.** Both have written; `GET /streak` says 7 and
   `GET /milestones` has not reached 7, or the reverse: the job a run behind, a day waiting for
   its reveal time, the bond's midnight. Must agree at every moment, by one function.
   (Task 2: `the seventh complete day is reached as soon as both have written it…`,
   `today is not counted while the day before it is still to be evaluated…`,
   `a day waiting for its reveal time is reached when it reveals…`.)
2. **A milestone announced twice, late, or for a day nobody wrote.** A second run of the job,
   a recalculation, a freeze-covered day, a run that broke and restarted: one event per
   threshold, on the day that crossed it, counted in complete days. (Task 1, all four
   integration tests.)
3. **An ended bond that says how it ended.** Left, blocked, blocked with withdrawal before and
   after the erasure, the countdown: one body and one set of headers for every member, `next`
   null, `achieved` intact; a cancelled countdown gives `next` back. (Task 2: the ended-bond
   test.)
4. **A date that moves.** Today's date shown at the reveal must be the date the job later
   evaluates; a drifted `streak_states` row and a recalculation must move no date. (Task 2:
   the seventh-day test, the drift test.)
5. **The ends of the range.** A bond of one person, a new bond, a bond at 364 that reaches 365
   today, a bond past 365: nothing reached and 7 next; all four and nothing next. (Task 2:
   the first, the waiting-bond and the all-four tests.)

## Privacy hazards, each answered by a named test

| Hazard (c6-corpus §4.3) | Test |
|---|---|
| H-M1 no per-member "celebrated" state | `MilestonesEndpointTest`: "the seventh complete day is reached as soon as both have written it, and keeps its date when the job evaluates it" (both members, the same bytes, read again and in the other order; the partner's lone entry moves nothing); `ArchitectureTest`: "the milestones are read from the bond's days, and from nothing a member did alone" |
| H-M2 bond-level counts only | `MilestonesEndpointTest`: "the payload carries these fields and no other, and a stranger gets the one 404"; `OpenApiContractTest`: "milestones are what was reached and the next threshold, and no count" |
| H-M3 no entry content | the same two; `MilestoneReachedTest`: "the seventh complete day writes one MilestoneReached, naming the bond and that day and nobody" (payload keys) |
| H-M4 ended bonds identical | `MilestonesEndpointTest`: "an ended bond keeps what it reached with nothing next, the same bytes left, blocked or counting down, for both members" |
| H-M5 the push, both or neither | **Phase 4's**, recorded in ADR-0041 Owed (and doc 02:144: none on an ended bond). What this slice can hold: the event names the bond and the day and no member (`MilestoneReachedTest`'s payload test), so a consumer has no member to address but through the bond |

The cross-cutting requirements (c6-corpus §5.2): tenancy, `GratitudeCrossTenantTest`'s
fixture; ended bonds, the H-M4 test; no surveillance (FR-064, T-09), the H-M1 test (one
member's lone entry moves nothing); contract, additive (`OpenApiContractTest`, no new
`ErrorCode`).

## File structure

| File | Responsibility |
|---|---|
| `G/domain/Milestones.kt` (new) | `Milestone`, `Milestones`: the thresholds and the three questions asked of them |
| `G/service/EvaluateStreaks.kt` | `announceMilestones`, after `record`, in the evaluation's transaction |
| `G/infra/database/StreakCalendar.kt` | `CompleteDays`, `completeDays`: the count and the Nth dates, one statement |
| `G/service/GetStreak.kt` | `countsToday`, extracted from `view` |
| `G/service/GetMilestones.kt` (new) | `MilestonesView`, `GetMilestones.of` |
| `G/web/MilestonesController.kt` (new) | the route and `MilestonesResponse`, `AchievedMilestoneResponse`, `NextMilestoneResponse` |
| `contracts/src/main/kotlin/com/moyi/contracts/MilestoneDocumentation.kt` (new), `OpenApiConfiguration.kt`, `contracts/openapi.json` | documented, regenerated |
| `GT/domain/MilestoneRulesTest.kt`, `GT/service/MilestoneReachedTest.kt`, `GT/web/MilestonesEndpointTest.kt` (new), `GT/web/GratitudeCrossTenantTest.kt` | the module's tests |
| `app/.../OpenApiContractTest.kt`, `CacheHeadersTest.kt`, `ArchitectureTest.kt` | the build's checks |
| `scripts/smoke.sh`, `scripts/moyi`, `tools/bruno/23 Milestones.bru`, `tools/bruno/README.md`, `docs/state-map/data/{endpoints,bond}.json` (and the generated pages) | the tools |
| `adr/0041-milestones.md`, `adr/0034-the-streak.md` (Owed "C6" discharged), the spec, `README.md`, `docs/learning-log.md` | the record |

`G` below is `modules/gratitude/src/main/kotlin/com/moyi/gratitude`, `GT` its test root
(`modules/gratitude/src/test/kotlin/com/moyi/gratitude`), `APPT` is
`app/src/test/kotlin/com/moyi/app`.

---
### Task 1: The rule, and the event the close job writes when a day reaches it

**Files:**
- Create: `G/domain/Milestones.kt`
- Modify: `G/service/EvaluateStreaks.kt` (one call in `evaluateBond`'s loop, one private
  function, the class KDoc's list of what is written)
- Test: `GT/domain/MilestoneRulesTest.kt` (new), `GT/service/MilestoneReachedTest.kt` (new)

**Interfaces:**
- Consumes: `StreakState.totalCompleteDays`; `DayToEvaluate.id` (a `UUID`);
  `EventPublisher.publish(OutboxEvent)`; in tests, `DayCloser.closeElapsedDays(Instant, Int)`,
  `RecalculateStreak.recalculate(UUID)`, `StreakEvaluationTest.TimeConfiguration` (its
  `MutableClock` bean, so the two classes share one Spring context).
- Produces (Task 2 relies on these, exactly):
  - `internal data class Milestone(val threshold: Int, val achievedOn: LocalDate)` in
    `com.moyi.gratitude.domain`
  - `internal object Milestones` with `val THRESHOLDS: List<Int>` (`[7, 30, 100, 365]`),
    `fun crossed(before: Int, after: Int): List<Int>`, `fun next(total: Int): Int?`,
    `fun achieved(total: Int, dates: Map<Int, LocalDate>): List<Milestone>`
  - the outbox row: `event_type = 'MilestoneReached'`, `aggregate_type = 'BondDay'`,
    `aggregate_id` = the day's id, `payload = {"bondId": …, "bondDayId": …}`.

- [ ] **Step 1: The rule's failing tests.** Create `GT/domain/MilestoneRulesTest.kt`:

```kotlin
package com.moyi.gratitude.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * FR-075 as the owner ruled it on 2026-10-22 (ADR-0041): four thresholds of complete days,
 * cumulative, and nothing after the last. What a day crosses, what is reached, what is next.
 */
internal class MilestoneRulesTest {
    @Test
    fun `the thresholds are 7, 30, 100 and 365, and nothing after`() {
        // Q-M2. The contract's description states the same four in words (ADR-0041 decision 14).
        Milestones.THRESHOLDS shouldBe listOf(7, 30, 100, 365)
    }

    @Test
    fun `a day crosses a threshold when it takes the count from below it to it`() {
        Milestones.crossed(6, 7) shouldBe listOf(7)
        Milestones.crossed(7, 7) shouldBe emptyList()
        Milestones.crossed(7, 8) shouldBe emptyList()
        Milestones.crossed(29, 30) shouldBe listOf(30)
        Milestones.crossed(99, 100) shouldBe listOf(100)
        Milestones.crossed(364, 365) shouldBe listOf(365)
        Milestones.crossed(365, 366) shouldBe emptyList()
        // Not one step's: a step adds one complete day at most. Stated so that "one per threshold" is a property of the rule.
        Milestones.crossed(0, 30) shouldBe listOf(7, 30)
        Milestones.crossed(0, 1_000) shouldBe listOf(7, 30, 100, 365)
    }

    @Test
    fun `next is the first threshold not yet reached, and none after the last`() {
        Milestones.next(0) shouldBe 7
        Milestones.next(6) shouldBe 7
        Milestones.next(7) shouldBe 30
        Milestones.next(364) shouldBe 365
        Milestones.next(365) shouldBe null
        Milestones.next(1_000) shouldBe null
    }

    @Test
    fun `what is reached is each threshold at or below the count, dated by the day that reached it`() {
        val dates = mapOf(7 to day(7), 30 to day(31), 31 to day(32))

        Milestones.achieved(6, emptyMap()) shouldBe emptyList()
        Milestones.achieved(30, dates) shouldBe listOf(Milestone(7, day(7)), Milestone(30, day(31)))
        // A count with no date for a threshold it passes is a read that went wrong, not an answer.
        shouldThrow<IllegalStateException> { Milestones.achieved(100, dates) }
    }

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())
}
```

- [ ] **Step 2: Run it to see it fail.**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.domain.MilestoneRulesTest"`
Expected: FAIL to compile, "Unresolved reference 'Milestones'" (and `Milestone`).

- [ ] **Step 3: The rule.** Create `G/domain/Milestones.kt`:

```kotlin
package com.moyi.gratitude.domain

import java.time.LocalDate

/** A milestone a bond has reached: [threshold] complete days, the last of them on [achievedOn]. */
internal data class Milestone(
    val threshold: Int,
    val achievedOn: LocalDate,
)

/**
 * FR-075's milestones, as the owner ruled them on 2026-10-22 (ADR-0041): **complete days**,
 * days both wrote on, counted over the bond's whole life (Q-M1). The count never goes down, so
 * nothing reached is ever lost; a day a freeze covered and a date a zone change stepped over
 * extend the run and are not complete days (ADR-0034 decision 5), so no milestone celebrates a
 * day somebody missed. Exactly four thresholds, and nothing after the last (Q-M2).
 *
 * Asked by the close job's evaluation, which announces a crossing (`EvaluateStreaks`), and by
 * the read, which dates what was reached and names what is next (`GetMilestones`). Pure, as
 * [StreakRules] is: no clock, no store.
 */
internal object Milestones {
    private const val A_WEEK = 7
    private const val A_MONTH = 30
    private const val A_HUNDRED = 100
    private const val A_YEAR = 365

    /** Oldest first. The contract states them in words (`MilestoneDocumentation.kt`): change both. */
    val THRESHOLDS: List<Int> = listOf(A_WEEK, A_MONTH, A_HUNDRED, A_YEAR)

    /** The thresholds a step from [before] complete days to [after] takes the bond across. */
    fun crossed(
        before: Int,
        after: Int,
    ): List<Int> = THRESHOLDS.filter { it > before && it <= after }

    /** The first threshold above [total], or `null` once all four are reached (Q-M2). Not a countdown: no count goes with it (Q-M3). */
    fun next(total: Int): Int? = THRESHOLDS.firstOrNull { it > total }

    /**
     * Every threshold at or below [total], oldest first, each dated by [dates]: the date of the
     * day that was that threshold's complete day, by its place among them.
     */
    fun achieved(
        total: Int,
        dates: Map<Int, LocalDate>,
    ): List<Milestone> =
        THRESHOLDS.filter { it <= total }.map { threshold ->
            Milestone(threshold, checkNotNull(dates[threshold]) { "a reached threshold has its day's date: $threshold" })
        }
}
```

- [ ] **Step 4: Run it to see it pass.**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.domain.MilestoneRulesTest"`
Expected: PASS, 4 tests.

Mutations, each restored: `it > before && it <= after` → `it <= after`: "a day crosses a
threshold…" red (`crossed(7, 8)` gives `[7]`). `firstOrNull { it > total }` →
`firstOrNull { it >= total }`: "next is the first threshold…" red (`next(7)` gives 7).

- [ ] **Step 5: The event's failing tests.** Create `GT/service/MilestoneReachedTest.kt`:

```kotlin
package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * `MilestoneReached` (FR-075, spec §8; ADR-0041 decision 8): written by the close job's
 * evaluation, in the transaction that records the day, once for each threshold of complete
 * days a day takes the bond across, carrying the bond's id and the day's and nothing else.
 *
 * Days are written by two people's requests and the job is run with an instant, as in
 * `StreakEvaluationTest` (whose clock configuration this shares, so the two share a context).
 * `Africa/Lagos`: day *n* is 2026-09-01 plus *n*, ends at 23:00Z, and the job settles it a
 * minute later. The bond is paired on day 0.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakEvaluationTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class MilestoneReachedTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val recalculate: RecalculateStreak,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(at(0))
        val created = createBond(ada)
        bond = idOf(created)
        accept(bea, codeOf(created))
    }

    /** Before as well as after ([setUp] calls it first): the job works on every bond in a shared database. */
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    /** Spec §8: ids only. H-M5: no member is named, so a consumer can reach the two only through the bond. */
    @Test
    fun `the seventh complete day writes one MilestoneReached, naming the bond and that day and nobody`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        milestones() shouldBe emptyList()

        bothWriteOn(7)
        closeThrough(7)

        milestones() shouldBe listOf(Announced(dayId(7), "BondDay", dayId(7), bond, "bondDayId,bondId"))
        // The same transaction as the day's record: the day is evaluated, and as COMPLETE.
        jdbc.queryForObject(
            "SELECT evaluated_as FROM bond_days WHERE id = ?",
            String::class.java,
            dayId(7),
        ) shouldBe "COMPLETE"
    }

    /**
     * One run of the job over a month: each threshold on the day that crossed it. Day 15 only
     * Ada wrote, and the freeze banked on day 14 covers it: the run goes on and the day is not
     * a complete day (Q-M1). So the run reaches 30 on day 30 and the count on day 31.
     */
    @Test
    fun `a backlog crosses each threshold on its own day, and a day a freeze covered counts toward none`() {
        (1..14).forEach { bothWriteOn(it) }
        writeOn(15, ada)
        (16..31).forEach { bothWriteOn(it) }

        closeThrough(31)

        jdbc.queryForObject("SELECT status FROM bond_days WHERE id = ?", String::class.java, dayId(15)) shouldBe "FROZEN"
        milestones().map { it.dayId } shouldBe listOf(dayId(7), dayId(31))
    }

    /** Day 4 only Ada wrote, with no freeze banked: the run breaks. The count does not: 3 before, 4 after. */
    @Test
    fun `a day only one wrote on reaches nothing, and the break after it takes nothing back`() {
        (1..3).forEach { bothWriteOn(it) }
        writeOn(4, ada)
        (5..8).forEach { bothWriteOn(it) }

        closeThrough(8)

        jdbc.queryForObject("SELECT current_streak FROM streak_states WHERE bond_id = ?::uuid", Int::class.java, bond) shouldBe 4
        milestones().map { it.dayId } shouldBe listOf(dayId(8))
    }

    /** ADR-0034 decisions 9 and 10: a day is evaluated once, and a recalculation announces nothing, even when it changes the row. */
    @Test
    fun `a day evaluated again, and a recalculation, write no second MilestoneReached`() {
        (1..7).forEach { bothWriteOn(it) }
        closeThrough(7)
        closeThrough(7)
        closeThrough(9)

        recalculate.recalculate(UUID.fromString(bond)).changed shouldBe false
        jdbc.update("UPDATE streak_states SET total_complete_days = 3 WHERE bond_id = ?::uuid", bond) shouldBe 1
        recalculate.recalculate(UUID.fromString(bond)).let { (it.before.totalCompleteDays to it.after.totalCompleteDays) shouldBe (3 to 7) }

        milestones().map { it.dayId } shouldBe listOf(dayId(7))
        // Every row of the type, not only those that name a day of this bond: a replay has no day id to name.
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 1
    }

    // ---- helpers ----------------------------------------------------------

    private data class Announced(
        val dayId: UUID,
        val aggregateType: String,
        val aggregateId: UUID,
        val bondId: String,
        val keys: String,
    )

    /** Every MilestoneReached, in the order the days were evaluated (each day's events share its `occurred_at`). */
    private fun milestones(): List<Announced> =
        jdbc.query(
            """
            SELECT (payload->>'bondDayId')::uuid AS day_id, aggregate_type, aggregate_id, payload->>'bondId' AS bond_id,
                   (SELECT string_agg(k, ',' ORDER BY k) FROM jsonb_object_keys(payload) AS k) AS keys
            FROM outbox_events o JOIN bond_days d ON d.id = (o.payload->>'bondDayId')::uuid
            WHERE event_type = 'MilestoneReached' ORDER BY d.date
            """.trimIndent(),
        ) { row, _ ->
            Announced(
                row.getObject("day_id", UUID::class.java),
                row.getString("aggregate_type"),
                row.getObject("aggregate_id", UUID::class.java),
                row.getString("bond_id"),
                row.getString("keys"),
            )
        }

    private fun dayId(n: Int): UUID =
        jdbc.queryForObject("SELECT id FROM bond_days WHERE bond_id = ?::uuid AND date = ?", UUID::class.java, bond, day(n))!!

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    /** Runs the job once, a minute after day *n* ends. */
    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(Instant.parse("${day(n)}T23:01:00Z"), BUDGET).failed shouldBe 0
    }

    private fun bothWriteOn(n: Int) {
        clock.set(at(n))
        submit(ada)
        submit(bea)
    }

    private fun writeOn(
        n: Int,
        author: UUID,
    ) {
        clock.set(at(n))
        submit(author)
    }

    private fun submit(caller: UUID) {
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thank you"}"""
            }.andReturn()
            .response.status shouldBe 201
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    private fun accept(
        userId: UUID,
        code: String,
    ) {
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        const val BUDGET = 1_000
    }
}
```

`StreakEvaluationTest.TimeConfiguration` is a nested class of an `internal` test class in the
same package; if Kotlin refuses the reference, give this class its own identical
`@TestConfiguration` (`MutableClock(start = Instant.parse("2026-09-01T10:00:00Z"))`) and say so.

- [ ] **Step 6: Run it to see it fail.**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.service.MilestoneReachedTest"`
Expected: the first three FAIL on `milestones()` (an empty list where one or two events were
expected); the fourth FAILS on its last line (`[]` where `[day 7's id]` was expected). Its
`recalculate` lines pass already: `(3 to 7)` is ADR-0034's replay.

- [ ] **Step 7: The event.** In `G/service/EvaluateStreaks.kt`:

Add the import `com.moyi.gratitude.domain.Milestones`. In `evaluateBond`'s loop, after the
`record(…)` line and before `state = step.state`:

```kotlin
            record(bondId, day.date, state, step.state, announce = outcome == DayOutcome.COMPLETE, step, now)
            announceMilestones(bondId, day.id, state, step.state, now)
            state = step.state
```

After `record`, add:

```kotlin
    /**
     * `MilestoneReached` for each threshold of complete days this day took the bond across
     * (FR-075; ADR-0041 decision 8). Here, in the transaction that records the day: `recordDay`
     * refuses a day already evaluated, so a day announces once or, when its bond fails and rolls
     * back, not at all. One day adds at most one complete day, so it crosses at most one
     * threshold; a backlog crosses several on several days, each its own event.
     *
     * **Counted in complete days, never the run** (the owner's Q-M1): a day a freeze covered or
     * a zone change stepped over extends the run and moves `totalCompleteDays` not at all, so it
     * reaches nothing, and a break takes nothing back. Written on an ended bond as on any other:
     * a day both wrote on before the end is complete (ADR-0034 decision 7); whether anybody is
     * told is Phase 4's to decide, and doc 02:144 says nobody is.
     *
     * **Ids only** (spec §8): the bond and the day. Which threshold is the consumer's to count
     * from the timeline, as `GET /milestones` does; a number in the payload would break the rule
     * that a payload is a map of names to ids (ADR-0035). It names no member, so a notification
     * made of it can only reach both or neither (H-M5).
     *
     * Never from `RecalculateStreak` (ADR-0034 decision 10): a replay that announced would
     * tell two people again.
     */
    private fun announceMilestones(
        bondId: UUID,
        dayId: UUID,
        before: StreakState,
        after: StreakState,
        now: Instant,
    ) {
        Milestones.crossed(before.totalCompleteDays, after.totalCompleteDays).forEach { _ ->
            events.publish(
                OutboxEvent("BondDay", dayId, "MilestoneReached", mapOf("bondId" to bondId, "bondDayId" to dayId), now),
            )
        }
    }
```

In the class KDoc, after the paragraph that ends "`RecalculateStreak` replays those and never
asks again.", add:

```kotlin
 *
 * **And what each day reached** ([announceMilestones]): a `MilestoneReached` for a day that
 * takes the bond's complete days across 7, 30, 100 or 365 (ADR-0041).
```

- [ ] **Step 8: Run it to see it pass, with what it sits beside.**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.service.MilestoneReachedTest" --tests "com.moyi.gratitude.service.StreakEvaluationTest" --tests "com.moyi.gratitude.service.RecalculateStreakTest" --tests "com.moyi.gratitude.domain.MilestoneRulesTest"`
Expected: PASS, every test. (`StreakEvaluationTest`'s "a streak event carries the bond's id
and nothing else" asks only `event_type LIKE 'Streak%'`, so the new type does not touch it.)

Mutations, each restored, each run against `MilestoneReachedTest` alone:
- The `announceMilestones(…)` call removed → the first three tests red (`[]`).
- `Milestones.crossed(before.totalCompleteDays, after.totalCompleteDays)` →
  `Milestones.crossed(before.current, after.current)` (the run) → the backlog test red (day 30
  in place of day 31) and the break test red (`[]`: the run is 4).
- In `Milestones.crossed`, `it > before && it <= after` → `it <= after` → the backlog test
  red (days 8 to 31 each announce 7) and the break test red. The first test stays green
  (no complete day follows day 7): say so.
- `mapOf("bondId" to bondId, "bondDayId" to dayId)` → `mapOf("bondId" to bondId)` → the first
  test red (the join finds no day: `[]`).
- The aggregate `dayId` → `bondId` → the first test red (`aggregateId`).
- In `RecalculateStreak`, inject `EventPublisher` and publish
  `OutboxEvent("Bond", bondId, "MilestoneReached", mapOf("bondId" to bondId), now)` for each
  threshold `Milestones.crossed` gives inside the fold → the fourth test red on its last line
  (`3` where `1` was expected: each of the two recalculations folds from nothing and
  crosses 7 again).
  The `milestones()` line before it stays green (the join finds no day for a replay's event):
  that is why the count is there.

- [ ] **Step 9: The module, whole, and commit.**

```bash
./gradlew :modules:gratitude:build
git status --porcelain | grep -v '^?? \.kotlin/'
git add modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Milestones.kt \
  modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/EvaluateStreaks.kt \
  modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/MilestoneRulesTest.kt \
  modules/gratitude/src/test/kotlin/com/moyi/gratitude/service/MilestoneReachedTest.kt
git commit -m "feat(gratitude): MilestoneReached, once for each threshold the close job's evaluation crosses"
```

Expected: `BUILD SUCCESSFUL` (tests, ktlint, detekt). If detekt flags `MagicNumber` in the
tests or `LongParameterList` on `announceMilestones` (five parameters; detekt's default
threshold is six), report it and follow the module's existing suppression style.

### Task 2: `GET /bonds/{bondId}/milestones`, dated by the bond's days

**Files:**
- Modify: `G/infra/database/StreakCalendar.kt` (`CompleteDays`, `completeDays`),
  `G/service/GetStreak.kt` (`countsToday`, extracted from `view`)
- Create: `G/service/GetMilestones.kt`, `G/web/MilestonesController.kt`,
  `contracts/src/main/kotlin/com/moyi/contracts/MilestoneDocumentation.kt`
- Modify: `contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt` (one call),
  `contracts/openapi.json` (regenerated)
- Test: `GT/web/MilestonesEndpointTest.kt` (new), `GT/web/GratitudeCrossTenantTest.kt` (one
  fixture), `APPT/OpenApiContractTest.kt` (one test), `APPT/CacheHeadersTest.kt` (two lines),
  `APPT/ArchitectureTest.kt` (one test)

**Interfaces:**
- Consumes (Task 1): `Milestone(threshold: Int, achievedOn: LocalDate)`;
  `Milestones.THRESHOLDS: List<Int>`, `Milestones.achieved(total: Int, dates: Map<Int,
  LocalDate>): List<Milestone>`, `Milestones.next(total: Int): Int?`; the outbox row
  `MilestoneReached` with `payload->>'bondDayId'`. Existing: `todayOf(membership, clock)`
  (`service/BondCalendars.kt`), `BondAccess.guard(caller, bondId)` (`web/BondGuard.kt`),
  `ReconcileJoiningDay.beforeRead(membership)`, `StreakCalendar.between(bondId, from, to,
  today): List<StreakDay>`, `BondMembership.takesWrites`, `WithdrawalRig` (test, package
  `com.moyi.gratitude.service`), `StreakEndpointTest.TimeConfiguration` (test).
- Produces (Task 3's state map quotes these lines exactly as committed):
  - `internal data class CompleteDays(val total: Int, val dates: Map<Int, LocalDate>)` and
    `StreakCalendar.completeDays(bondId: UUID, ordinals: List<Int>): CompleteDays`
  - `GetStreak.countsToday(membership: BondMembership, today: LocalDate, todayComplete: Boolean): Boolean`
  - `internal data class MilestonesView(val achieved: List<Milestone>, val next: Int?)` and
    `GetMilestones.of(membership: BondMembership): MilestonesView`
  - the route `GET /api/v1/bonds/{bondId}/milestones`, operationId `milestones`, schemas
    `MilestonesResponse {achieved, next}`, `AchievedMilestoneResponse {threshold, achievedOn}`,
    `NextMilestoneResponse {threshold}`
  - the lines `next = if (membership.takesWrites) Milestones.next(total) else null,` and
    `val total = if (counted) evaluated.total + 1 else evaluated.total` in `GetMilestones.kt`
  - the test names of `MilestonesEndpointTest`, as written below.

- [ ] **Step 1: The route's failing tests.** Create `GT/web/MilestonesEndpointTest.kt`:

```kotlin
package com.moyi.gratitude.web

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.RecalculateStreak
import com.moyi.gratitude.service.WithdrawalRig
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * `GET /bonds/{bondId}/milestones` (FR-075, doc 06:174; ADR-0041): the four thresholds of
 * complete days a bond has reached, each dated by the day that reached it, and the next.
 *
 * `MilestoneRulesTest` holds the thresholds and `MilestoneReachedTest` the event. This holds
 * what a member is shown: today counted when, and only when, `GET /streak` counts it; the dates
 * read off the bond's days and not the stored streak; one answer for both members and for every
 * way a bond ends.
 *
 * `Africa/Lagos`; day *n* is 2026-09-01 plus *n*, and the bond is paired on day 0. A history too
 * long to write a request at a time is put down by hand ([daysByHand]) as the job would have
 * left it.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakEndpointTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class MilestonesEndpointTest(
    @Autowired private val closer: DayCloser,
    @Autowired private val recalculate: RecalculateStreak,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val json: ObjectMapper,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(at(0))
        bond = rig.pair(ada, bea)
    }

    /** Before as well as after ([setUp] calls it first): the job and the dispatcher work on every bond in a shared database. */
    @AfterEach
    fun clear() {
        rig.clear()
        users.clear()
    }

    @Test
    fun `a bond nobody has written in has reached nothing, and seven is next, for both alike`() {
        for (member in listOf(ada, bea)) body(member) shouldBe NOTHING_YET
    }

    /**
     * Q-M5: reached at the reveal, which on a bond with no reveal time is the second entry. The
     * partner's lone entry moves nothing for either of them (FR-064); once complete, both read
     * the same bytes, again and in either order (H-M1, H-M2). The job then evaluates the day: the
     * date shown is the date kept, and the day the event names.
     */
    @Test
    fun `the seventh complete day is reached as soon as both have written it, and keeps its date when the job evaluates it`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        clock.set(at(7))
        rig.submit(ada, bond, "thank you")
        listOf(ada, bea).map { body(it) } shouldBe listOf(NOTHING_YET, NOTHING_YET)

        rig.submit(bea, bond, "thank you")

        val reached = reachedSeven(day(7))
        listOf(bea, ada, ada, bea).map { body(it) } shouldBe List(4) { reached }
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7

        closeThrough(7)
        clock.set(at(8))
        body(ada) shouldBe reached
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7
        jdbc.queryForObject(
            """
            SELECT d.date FROM outbox_events o JOIN bond_days d ON d.id = (o.payload->>'bondDayId')::uuid
            WHERE o.event_type = 'MilestoneReached'
            """.trimIndent(),
            LocalDate::class.java,
        ) shouldBe day(7)
    }

    /** As `StreakEndpointTest`'s "today is not added while the day before it is still to be evaluated": the two numbers move together. */
    @Test
    fun `today is not counted while the day before it is still to be evaluated, as the streak does not count it`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        // Nobody writes on day 7, and the job has not run for it. Both write on day 8.
        bothWriteOn(8)

        body(ada) shouldBe NOTHING_YET
        streak(ada)["totalCompleteDays"].asInt() shouldBe 6

        closeThrough(7)

        body(ada) shouldBe reachedSeven(day(8))
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7
    }

    /** "Both have written" is the reveal: a day waiting for its reveal time is an OPEN square, and counts for neither number yet. */
    @Test
    fun `a day waiting for its reveal time is reached when it reveals, not when the second entry arrives`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        jdbc.update("UPDATE bonds SET reveal_time_local = '20:00:00'::time WHERE id = ?::uuid", bond) shouldBe 1
        bothWriteOn(7)
        dayStatus(7) shouldBe "PENDING_REVEAL"

        body(ada) shouldBe NOTHING_YET
        streak(ada)["totalCompleteDays"].asInt() shouldBe 6

        // 20:00 in Lagos is 19:00Z. To be confirmed by running: the job reveals a day whose reveal time has come.
        closer.closeElapsedDays(Instant.parse("${day(7)}T19:01:00Z"), BUDGET).failed shouldBe 0
        dayStatus(7) shouldBe "REVEALED"

        body(ada) shouldBe reachedSeven(day(7))
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7
    }

    @Test
    fun `all four, and nothing after the last`() {
        daysByHand(bond, through = 364)
        clock.set(at(365))
        rig.submit(ada, bond, "thank you")
        val three = """{"threshold":7,"achievedOn":"${day(7)}"},{"threshold":30,"achievedOn":"${day(30)}"},""" +
            """{"threshold":100,"achievedOn":"${day(100)}"}"""
        body(bea) shouldBe """{"achieved":[$three],"next":{"threshold":365}}"""

        rig.submit(bea, bond, "thank you")

        body(ada) shouldBe """{"achieved":[$three,{"threshold":365,"achievedOn":"${day(365)}"}],"next":null}"""
    }

    /**
     * H-M4 and the controller's default 1: `takesWrites` (ADR-0041 decision 7). Three bonds with
     * the same history on the same dates, ended three ways: a leave, a block with withdrawal, a
     * deletion countdown. Every member of every one reads the same body and the same headers,
     * before the job and the erasure run and after. A countdown called off gives `next` back.
     */
    @Test
    fun `an ended bond keeps what it reached with nothing next, the same bytes left, blocked or counting down, for both members`() {
        val cy = users.verified("Cy")
        val dee = users.verified("Dee")
        val fay = users.verified("Fay")
        val gus = users.verified("Gus")
        val left = bond
        val blocked = rig.pair(cy, dee)
        val counting = rig.pair(fay, gus)
        val bonds = listOf(left to listOf(ada, bea), blocked to listOf(cy, dee), counting to listOf(fay, gus))
        bonds.forEach { (b, _) -> daysByHand(b, through = 6) }
        clock.set(at(7))
        bonds.forEach { (b, members) -> members.forEach { rig.submit(it, b, "thank you") } }
        // Not vacuous: each has a next while it takes writes.
        bonds.forEach { (b, members) -> body(members.first(), b) shouldBe reachedSeven(day(7)) }

        clock.set(at(7).plusSeconds(3_600))
        rig.leave(bea, left)
        rig.block(dee, blocked, true)
        deletionRequest(fay, counting, "POST")
        deletionRequest(gus, counting, "POST")
        // To be confirmed by running, as in OnThisDayTest: two requests put the bond in PENDING_DELETION.
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, counting) shouldBe "PENDING_DELETION"

        val ended = """{"achieved":[{"threshold":7,"achievedOn":"${day(7)}"}],"next":null}"""
        val answers = bonds.flatMap { (b, members) -> members.map { raw(it, b) } }
        answers.map { it.status to it.contentAsString }.toSet() shouldBe setOf(200 to ended)
        val headers = answers.map(::headersOf).toSet()
        withClue("$headers") { headers.size shouldBe 1 }

        // The withdrawal's erasure runs, and the job evaluates day 7 on all three: the record is the same.
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET).failed shouldBe 0
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND author_member_id = ? AND text IS NOT NULL",
            Int::class.java,
            blocked,
            rig.memberId(blocked, dee),
        ) shouldBe 0
        closeThrough(7)
        clock.set(at(9))
        bonds.flatMap { (b, members) -> members.map { raw(it, b) } }.map { it.contentAsString }.toSet() shouldBe setOf(ended)
        // Decision 9: a day both wrote on before the end is announced, ended or not.
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 3

        deletionRequest(fay, counting, "DELETE")
        body(gus, counting) shouldBe reachedSeven(day(7))
    }

    @Test
    fun `a bond still waiting for its second person has reached nothing, whatever its creator wrote`() {
        val cara = users.verified("Cara")
        clock.set(at(1))
        val waiting = rig.idOf(rig.create(cara))
        rig.submit(cara, waiting, "thank you")
        closeThrough(1)
        clock.set(at(2))
        rig.submit(cara, waiting, "thank you")

        body(cara, waiting) shouldBe NOTHING_YET
    }

    /** Decision 2: the dates are the timeline's, which `recalculate` trusts (FR-074). A drifted row moves the streak, not a date. */
    @Test
    fun `the dates are the bond's days, not the stored streak - a drifted row moves nothing, and a recalculation announces nothing`() {
        (1..7).forEach { bothWriteOn(it) }
        closeThrough(7)
        clock.set(at(8))
        val reached = body(ada)
        reached shouldBe reachedSeven(day(7))

        jdbc.update("UPDATE streak_states SET total_complete_days = 2 WHERE bond_id = ?::uuid", bond) shouldBe 1
        // Not vacuous: the streak's own number has drifted.
        streak(ada)["totalCompleteDays"].asInt() shouldBe 2
        body(ada) shouldBe reached

        recalculate.recalculate(UUID.fromString(bond)).changed shouldBe true
        streak(ada)["totalCompleteDays"].asInt() shouldBe 7
        body(ada) shouldBe reached
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'MilestoneReached'", Int::class.java) shouldBe 1
    }

    /** FR-076, H-M2, H-M3, Q-M3: thresholds and dates about the bond, and no count to make a countdown of. Named here, all of them. */
    @Test
    fun `the payload carries these fields and no other, and a stranger gets the one 404`() {
        (1..6).forEach { bothWriteOn(it) }
        closeThrough(6)
        bothWriteOn(7)

        val payload = json.readTree(body(ada))

        payload.propertyNames().toSet() shouldBe setOf("achieved", "next")
        payload["achieved"].single().propertyNames().toSet() shouldBe setOf("threshold", "achievedOn")
        payload["next"].propertyNames().toSet() shouldBe setOf("threshold")
        val eve = users.verified("Eve")
        val refused = raw(eve, bond)
        refused.status shouldBe 404
        refused.contentAsString.replace("/milestones", "/streak") shouldBe get(eve, "/api/v1/bonds/$bond/streak").contentAsString
        raw(ada, "not-an-id").status shouldBe 404
    }

    // ---- helpers ----------------------------------------------------------

    private fun reachedSeven(on: LocalDate): String = """{"achieved":[{"threshold":7,"achievedOn":"$on"}],"next":{"threshold":30}}"""

    private fun body(
        member: UUID,
        b: String = bond,
    ): String = raw(member, b).also { it.status shouldBe 200 }.contentAsString

    private fun raw(
        member: UUID,
        b: String,
    ): MockHttpServletResponse = get(member, "/api/v1/bonds/$b/milestones")

    private fun streak(member: UUID): JsonNode =
        json.readTree(get(member, "/api/v1/bonds/$bond/streak").also { it.status shouldBe 200 }.contentAsString)

    private fun get(
        member: UUID,
        path: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(member).token}") }
            .andReturn()
            .response

    /** Every header of [response] by name, but those that change from one moment to the next. */
    private fun headersOf(response: MockHttpServletResponse): Map<String, List<String>> =
        response.headerNames
            .filterNot { name -> VOLATILE_HEADERS.any { it.equals(name, ignoreCase = true) } }
            .sorted()
            .associateWith { response.getHeaders(it) }

    private fun deletionRequest(
        user: UUID,
        b: String,
        method: String,
    ) {
        val path = "/api/v1/bonds/$b/deletion-request"
        val bearer = "Bearer ${tokens.issue(user).token}"
        val response =
            if (method == "POST") {
                mockMvc.post(path) { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response
            } else {
                mockMvc.delete(path) { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response
            }
        withClue("$method deletion-request: ${response.contentAsString}") { (response.status / 100) shouldBe 2 }
    }

    private fun dayStatus(n: Int): String? =
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?", String::class.java, bond, day(n))

    /**
     * Day 0 (the pairing day: nobody wrote) and days 1 to [through], both written, closed and
     * evaluated, as the job would have left them, with the streak row that fold gives outside
     * Strict mode: a freeze banked every fourteenth day, two at most (BR-5). No entries: nothing
     * here reads them. The series is of `timestamp`, so `AT TIME ZONE 'UTC'` makes the instants
     * whatever the session's zone. To be confirmed by running: pairing leaves no row for day 0,
     * so the insert makes `through + 1` rows.
     */
    private fun daysByHand(
        b: String,
        through: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at,
                                   closed_at, version, evaluated_at, evaluated_as, evaluated_strict, freeze_applied)
            SELECT gen_random_uuid(), ?::uuid, d::date, CASE WHEN d::date = ?::date THEN 'EMPTY' ELSE 'REVEALED' END, 'Africa/Lagos',
                   (d AT TIME ZONE 'UTC') - interval '1 hour', (d AT TIME ZONE 'UTC') + interval '23 hours',
                   CASE WHEN d::date = ?::date THEN 0 ELSE 2 END, (d AT TIME ZONE 'UTC') - interval '1 hour',
                   (d AT TIME ZONE 'UTC') + interval '23 hours 1 minute', 0, (d AT TIME ZONE 'UTC') + interval '23 hours 1 minute',
                   CASE WHEN d::date = ?::date THEN 'MISSED' ELSE 'COMPLETE' END, false, false
            FROM generate_series(?::date::timestamp, ?::date::timestamp, interval '1 day') AS d
            """.trimIndent(),
            b,
            day(0),
            day(0),
            day(0),
            day(0),
            day(through),
        ) shouldBe through + 1
        jdbc.update(
            """
            INSERT INTO streak_states (bond_id, current_streak, longest_streak, last_complete_date, freezes_available,
                                       freeze_progress, freezes_consumed, total_complete_days, updated_at)
            VALUES (?::uuid, ?, ?, ?, ?, ?, 0, ?, ?)
            """.trimIndent(),
            b,
            through,
            through,
            day(through),
            minOf(2, through / 14),
            through % 14,
            through,
            Timestamp.from(clock.instant()),
        ) shouldBe 1
    }

    private fun day(n: Int): LocalDate = LocalDate.of(2026, 9, 1).plusDays(n.toLong())

    private fun at(n: Int): Instant = Instant.parse("${day(n)}T10:00:00Z")

    private fun closeThrough(n: Int) {
        closer.closeElapsedDays(Instant.parse("${day(n)}T23:01:00Z"), BUDGET).failed shouldBe 0
    }

    private fun bothWriteOn(n: Int) {
        clock.set(at(n))
        rig.submit(ada, bond, "thank you")
        rig.submit(bea, bond, "thank you")
    }

    private companion object {
        const val NOTHING_YET = """{"achieved":[],"next":{"threshold":7}}"""
        const val BUDGET = 1_000
        const val DISPATCH_BUDGET = 10
        val VOLATILE_HEADERS = listOf(HttpHeaders.DATE, HttpHeaders.LAST_MODIFIED)
    }
}
```

The ninth test's name has a hyphen where a colon would be: a colon is not allowed in a JVM
method name on every platform. If ktlint or detekt flags a name's length, keep the name (the
state map and ADR-0041 quote it) and suppress as the module's other test classes do.

- [ ] **Step 2: Run it to see it fail.**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.web.MilestonesEndpointTest"`
Expected: every test FAILS on its first `body(…)` with `expected:<200> but was:<404>` (no
such route; the 404 is the application's not-found answer). The ninth's "Not vacuous" line is
not reached.

- [ ] **Step 3: The count and the dates, in one statement.** In
`G/infra/database/StreakCalendar.kt`, before the class, add:

```kotlin
/**
 * The bond's days evaluated `COMPLETE`: how many there are, and the dates of those asked for by
 * their place among them, oldest first (the 7th, the 30th…). What `GetMilestones` dates each
 * milestone by (ADR-0041 decision 2).
 */
internal data class CompleteDays(
    val total: Int,
    val dates: Map<Int, LocalDate>,
)
```

and in the class, after `between`:

```kotlin
    /**
     * [CompleteDays] for the bond, with the dates of the [ordinals]th complete days. **One
     * statement**, so the count and the dates are of one moment. The rows kept are the ones asked
     * for and the last, which carries the count when none of those has been reached. The
     * ordinals are one bound parameter, never part of the SQL.
     *
     * **The timeline, not `streak_states`.** The projection holds a count and one date; the
     * timeline holds every date, is what `recalculate` trusts (FR-074), and a day's
     * `evaluated_as` is never recomputed from its entries (BR-10).
     */
    fun completeDays(
        bondId: UUID,
        ordinals: List<Int>,
    ): CompleteDays {
        val rows =
            jdbc.query(
                """
                SELECT n, date, total FROM (
                    SELECT date, row_number() OVER (ORDER BY date) AS n, count(*) OVER () AS total
                    FROM bond_days WHERE bond_id = ? AND evaluated_as = 'COMPLETE'
                ) complete
                WHERE n = ANY (?::int[]) OR n = total
                """.trimIndent(),
                { row, _ -> Triple(row.getInt("n"), row.getObject("date", LocalDate::class.java), row.getInt("total")) },
                bondId,
                ordinals.joinToString(",", "{", "}"),
            )
        return CompleteDays(
            total = rows.firstOrNull()?.third ?: 0,
            dates = rows.filter { it.first in ordinals }.associate { it.first to it.second },
        )
    }
```

To be confirmed by running: Postgres casts the bound text `{7,30,100,365}` to `int[]`.

- [ ] **Step 4: Today, decided in one place.** In `G/service/GetStreak.kt`, in `view`, replace

```kotlin
            if (todayComplete && todayFollows(membership, today)) {
```

with

```kotlin
            if (countsToday(membership, today, todayComplete)) {
```

and after `view` add:

```kotlin
    /**
     * Whether a complete [today] is added to what is stored: the one rule for it, which [view]
     * applies to the numbers and `GetMilestones` to the milestones (ADR-0041 decision 4), so that
     * the two screens never disagree about today. [todayComplete]: today's square is `COMPLETE`,
     * which is to say the day has **revealed** — a day both have written that waits for its
     * reveal time is not complete yet. And today must directly follow the last evaluated day:
     * see the class KDoc.
     */
    fun countsToday(
        membership: BondMembership,
        today: LocalDate,
        todayComplete: Boolean,
    ): Boolean = todayComplete && todayFollows(membership, today)
```

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.web.StreakEndpointTest"`
Expected: PASS, 19 tests (a refactor: nothing moves).

- [ ] **Step 5: The reading.** Create `G/service/GetMilestones.kt`:

```kotlin
package com.moyi.gratitude.service

import com.moyi.bond.api.BondMembership
import com.moyi.gratitude.domain.Milestone
import com.moyi.gratitude.domain.Milestones
import com.moyi.gratitude.domain.StreakCell
import com.moyi.gratitude.infra.database.StreakCalendar
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** What a bond has reached (FR-075): each milestone with the date of the day that reached it, oldest first, and the next one to aim at. */
internal data class MilestonesView(
    val achieved: List<Milestone>,
    /** `null` once all four are reached, and whenever the caller's membership takes no writes. */
    val next: Int?,
)

/**
 * `GET /bonds/{bondId}/milestones` (ADR-0041).
 *
 * **Dated by the bond's days.** The Nth day evaluated `COMPLETE` is the day that reached N
 * (decision 2); `streak_states` is not read. **Today is added by `GET /streak`'s own rule**
 * ([GetStreak.countsToday]): once it has revealed, and only when it follows the last evaluated
 * day. Its square is read as `GetStreak.detail` reads it, from [StreakCalendar.between].
 *
 * **`next` is `null` when the membership takes no writes** (decision 7): ended, left, blocked
 * and the deletion countdown alike, so nothing says which. What was reached stays.
 *
 * **It remembers nothing about who asked** (H-M1): read-only, and handed the calendar, the
 * streak's rule and the clock, nothing else (`ArchitectureTest` holds the list). Whether a
 * milestone is new to somebody is their device's to know, from `achievedOn`.
 */
@Service
internal class GetMilestones(
    private val calendar: StreakCalendar,
    private val streak: GetStreak,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun of(membership: BondMembership): MilestonesView {
        val today = todayOf(membership, clock)
        // The timeline, then today's square, then (in countsToday) the last evaluated day. An
        // evaluation committing in between can leave one answer a day behind; nothing is counted
        // twice, for today is never evaluated while it is today (ADR-0034, Consequences).
        val evaluated = calendar.completeDays(membership.bondId, Milestones.THRESHOLDS)
        val todayComplete = calendar.between(membership.bondId, today, today, today).singleOrNull()?.cell == StreakCell.COMPLETE
        val counted = streak.countsToday(membership, today, todayComplete)
        val total = if (counted) evaluated.total + 1 else evaluated.total
        val dates = if (counted) evaluated.dates + (total to today) else evaluated.dates
        return MilestonesView(
            achieved = Milestones.achieved(total, dates),
            next = if (membership.takesWrites) Milestones.next(total) else null,
        )
    }
}
```

- [ ] **Step 6: The route.** Create `G/web/MilestonesController.kt`:

```kotlin
package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.gratitude.service.GetMilestones
import com.moyi.gratitude.service.MilestonesView
import com.moyi.gratitude.service.ReconcileJoiningDay
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * `GET /api/v1/bonds/{bondId}/milestones` (FR-075, doc 06:174; ADR-0041): the guard, the
 * joining-day reconcile `GET /streak` runs (a joiner's first request may be this one), the read.
 * No parameter. A member who has left reads it. The API's default `Cache-Control`, no `ETag`,
 * no bucket of its own (decision 11).
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class MilestonesController(
    private val access: BondAccess,
    private val getMilestones: GetMilestones,
    private val joining: ReconcileJoiningDay,
) {
    // Named `milestones`: the method name is the API's operationId.
    @GetMapping("/{bondId}/milestones")
    fun milestones(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): MilestonesResponse {
        val membership = access.guard(caller, bondId)
        joining.beforeRead(membership)
        return MilestonesResponse.from(getMilestones.of(membership))
    }
}

/**
 * What the bond has reached, and what is next.
 *
 * **Thresholds and dates about the bond, and nothing else** (FR-076, H-M2, H-M3): no field
 * names a member, carries an entry, or counts days. Not even `totalCompleteDays`, which
 * `GET /streak` sends: here it would make "days to the next" one subtraction on one response,
 * the countdown the owner ruled out (Q-M3).
 */
internal data class MilestonesResponse(
    /** Oldest first. Kept when the bond ends. */
    val achieved: List<AchievedMilestoneResponse>,
    /** The next threshold, or `null` once all four are reached or the bond takes no writes from the caller. */
    val next: NextMilestoneResponse?,
) {
    companion object {
        fun from(view: MilestonesView): MilestonesResponse =
            MilestonesResponse(
                achieved = view.achieved.map { AchievedMilestoneResponse(it.threshold, it.achievedOn) },
                next = view.next?.let(::NextMilestoneResponse),
            )
    }
}

internal data class AchievedMilestoneResponse(
    /** 7, 30, 100 or 365 complete days. */
    val threshold: Int,
    /** The bond's date of the day that was that many. */
    val achievedOn: LocalDate,
)

internal data class NextMilestoneResponse(
    val threshold: Int,
)
```

- [ ] **Step 7: Run it to see it pass, and break it.**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.web.MilestonesEndpointTest" --tests "com.moyi.gratitude.web.StreakEndpointTest"`
Expected: PASS, every test. If the reveal at 19:01Z, the day-0 row count, or the countdown's
status is not what the test predicts, report what was seen.

Mutations, each restored, each run against `MilestonesEndpointTest` and read for its reason:
- In `GetMilestones`, `streak.countsToday(membership, today, todayComplete)` →
  `todayComplete` → "today is not counted while the day before it…" red (`achieved` holds 7
  on day 8 before day 7 is evaluated).
- `… == StreakCell.COMPLETE` → `… != null` (a written today counts) → "the seventh complete
  day…" red (Ada's lone entry reaches 7) and "a day waiting for its reveal time…" red.
- `val counted = …` → `val counted = false` → "the seventh complete day…", "a day waiting
  for its reveal time…" and "all four…" red.
- `if (membership.takesWrites)` removed (always `Milestones.next(total)`) → the ended-bond
  test red. `takesWrites` → `membership.endedAt == null` → the ended-bond test red (the
  countdown keeps `next`). `takesWrites` → `!membership.hasLeft` → the ended-bond test red
  (the member who stayed keeps `next`). `takesWrites` → `membership.isOpen` → **survives**,
  as in ADR-0039 decision 14: no request leaves a bond open while a member has left it.
  Record it as surviving.
- In `completeDays`, `ORDER BY date` → `ORDER BY date DESC` → "all four…" red (the dates).
- `WHERE n = ANY (?::int[]) OR n = total` → `WHERE n = ANY (?::int[])` → "the seventh
  complete day…" red (six evaluated days and no threshold among them: the count reads 0, and
  today is the first).
- `evaluated_as = 'COMPLETE'` → `status = 'REVEALED'` → "today is not counted while the day
  before it…" red (days 1 to 6 and today give seven before the job has run for day 7).
- Read the total from the projection: hand `GetMilestones` a `StreakStore` and use
  `(streaks.find(membership.bondId)?.totalCompleteDays ?: 0)` for `evaluated.total` → "the
  dates are the bond's days…" red on the drifted read (`achieved` empty; read the message: it
  must be the body, not a context failure).
- In `GetStreak.countsToday`, `todayComplete && todayFollows(…)` → `todayComplete` →
  `StreakEndpointTest`'s "today is not added while the day before it is still to be
  evaluated" red **and** this class's matching test red: one rule, two screens.
- `joining.beforeRead(membership)` removed → expected to **survive** here, as ADR-0039
  decision 20 found for search (`JoiningDayTest` owns the joining day). Record it either way.

- [ ] **Step 8: Every bond-scoped route has a stranger's test.**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.web.GratitudeCrossTenantTest"`
Expected: FAIL, naming `GET /api/v1/bonds/{bondId}/milestones` as a route with no fixture.

In `GT/web/GratitudeCrossTenantTest.kt`, after the on-this-day fixture, add:

```kotlin
            // Slice C6c. No parameters: a stranger gets the bond's 404, never a list of what it reached.
            "GET /api/v1/bonds/{bondId}/milestones" to Fixture(),
```

Run it again. Expected: PASS.

- [ ] **Step 9: Commit the module.**

```bash
./gradlew :modules:gratitude:build
git status --porcelain | grep -v '^?? \.kotlin/'
git add modules/gratitude/src/main/kotlin/com/moyi/gratitude/infra/database/StreakCalendar.kt \
  modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GetStreak.kt \
  modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GetMilestones.kt \
  modules/gratitude/src/main/kotlin/com/moyi/gratitude/web/MilestonesController.kt \
  modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/MilestonesEndpointTest.kt \
  modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/GratitudeCrossTenantTest.kt
git commit -m "feat(gratitude): GET /bonds/{bondId}/milestones, dated by the bond's days"
```

- [ ] **Step 10: The build's checks, failing.** In `APPT/OpenApiContractTest.kt`, after the
on-this-day test, add:

```kotlin
    @Test
    fun `milestones are what was reached and the next threshold, and no count`() {
        val milestones = api.paths["/api/v1/bonds/{bondId}/milestones"]!!.get
        milestones.operationId shouldBe "milestones"
        milestones.parameters.filter { it.`in` == "query" } shouldBe emptyList()
        milestones.responses.keys shouldContainAll listOf("200", "401", "404", "429")
        milestones.responses.keys shouldNotContain "422"
        milestones.responses["200"]!!
            .content["application/json"]!!
            .schema.`$ref` shouldBe "#/components/schemas/MilestonesResponse"
        // FR-076, H-M2, H-M3, Q-M3: thresholds and dates, nothing about a member, and no count.
        api.components.schemas["MilestonesResponse"]!!.properties.keys shouldBe setOf("achieved", "next")
        api.components.schemas["AchievedMilestoneResponse"]!!.properties.keys shouldBe setOf("threshold", "achievedOn")
        api.components.schemas["NextMilestoneResponse"]!!.properties.keys shouldBe setOf("threshold")
        val told = milestones.description.shouldNotBeNull()
        // ADR-0041 decision 14: the thresholds in words, a mirror of Milestones.THRESHOLDS.
        told shouldContain "7, 30, 100 and 365"
        told shouldContain "takes no writes"
    }
```

In `APPT/CacheHeadersTest.kt`, after the two on-this-day lines, add:

```kotlin
        // Milestones (slice C6c): the API's default, as search and on-this-day (ADR-0041 decision 11).
        neverStored("GET /milestones", get("/bonds/$bond/milestones", ada), 200)
        neverStored("GET /milestones, a stranger", get("/bonds/$bond/milestones", eve), 404)
```

In `APPT/ArchitectureTest.kt`, after the prompt-of-the-day test, add:

```kotlin
    @Test
    fun `the milestones are read from the bond's days, and from nothing a member did alone`() {
        // H-M1 (ADR-0041 decision 12): no server-side "seen" or "celebrated" state per member. A
        // store that remembered who had been shown a milestone would be a read receipt (FR-064),
        // and it would have to be handed to GetMilestones. So what it is handed is pinned.
        val file =
            project.files.single { it.name == "GetMilestones" && it.normalisedProjectPath.contains("/modules/gratitude/src/main/") }
        val milestones = file.classes().single { it.name == "GetMilestones" }
        val handed =
            milestones.primaryConstructor
                ?.parameters
                .orEmpty()
                .map { it.type.name }
        assertTrue(
            handed.sorted() == listOf("Clock", "GetStreak", "StreakCalendar") && milestones.secondaryConstructors.isEmpty(),
            "GetMilestones is handed the calendar, the streak's rule for today and the clock, and nothing else. Found: $handed",
        )
    }
```

Run: `./gradlew :app:test --tests "com.moyi.app.OpenApiContractTest" --tests "com.moyi.app.CacheHeadersTest" --tests "com.moyi.app.ArchitectureTest"`
Expected: `OpenApiContractTest` FAILS (the committed contract is stale: it prints the command
that regenerates it; and the new test fails on the description, which is `null`).
`CacheHeadersTest` and `ArchitectureTest` PASS already: the default header is Spring
Security's, and the constructor is as pinned. Each is a guard, not a driver; Step 12's
mutations are what show they see something.

- [ ] **Step 11: The description.** Create
`contracts/src/main/kotlin/com/moyi/contracts/MilestoneDocumentation.kt`:

```kotlin
package com.moyi.contracts

import io.swagger.v3.oas.models.OpenAPI

private const val MILESTONES_OPERATION = "milestones"

/**
 * `GET /bonds/{bondId}/milestones` (ADR-0041) says what its two fields cannot: what counts, when
 * today does, and when `next` is null. The four thresholds are stated in words, a mirror of
 * `gratitude.domain.Milestones.THRESHOLDS`, which is internal to that module; `MilestoneRulesTest`
 * holds the list and `OpenApiContractTest` holds this sentence.
 */
internal fun documentMilestones(api: OpenAPI) {
    api.paths.values
        .flatMap { it.readOperations() }
        .filter { it.operationId == MILESTONES_OPERATION }
        .forEach { it.description = MILESTONES_DESCRIPTION }
}

private const val MILESTONES_DESCRIPTION =
    "The bond's milestones at 7, 30, 100 and 365 complete days: days on which both members wrote, counted over the " +
        "bond's whole life. A day a freeze covered is not a complete day, and a broken streak loses no milestone. " +
        "`achieved` lists each one reached, oldest first, with the date of the day that reached it. Today counts once it " +
        "has revealed, by the same rule as the streak's numbers.\n\n" +
        "`next` is the next threshold and nothing else. It is null once all four are reached, and whenever the bond " +
        "takes no writes from the caller: it has ended, the caller has left, or it is counting down to deletion. " +
        "`achieved` is kept when the bond ends."
```

In `contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt`, after
`documentOnThisDay(api)`, add `documentMilestones(api)`.

Run `./gradlew :app:test --tests "com.moyi.app.OpenApiContractTest"`, then
`cp app/build/openapi/openapi.json contracts/openapi.json`, then run it again.
Expected: PASS. `git diff contracts/openapi.json` adds the path (operationId `milestones`,
`200`, `401`, `404`, `429`, the description) and the three schemas, `achievedOn` with
`format: date`, and **changes nothing else**. If it changes anything else, stop and report.

- [ ] **Step 12: Break the guards.** Each restored:
- Remove the description call from `OpenApiConfiguration` → the new contract test red.
- Add `val totalCompleteDays: Int` to `MilestonesResponse` (filled from `view.achieved.size`
  for the mutation's sake) → the contract test red, and `MilestonesEndpointTest`'s "the
  payload carries these fields and no other…" red.
- In `MilestonesController.milestones`, take a `HttpServletResponse` and set
  `Cache-Control: private, max-age=60` → `CacheHeadersTest` red on "GET /milestones".
- Add a `StreakStore` parameter to `GetMilestones`'s constructor (unused) → `ArchitectureTest`
  red, naming it.
- Remove the cross-tenant fixture → `GratitudeCrossTenantTest` red (Step 8 already saw it).

- [ ] **Step 13: The whole build, and commit.**

```bash
./gradlew build
git status --porcelain | grep -v '^?? \.kotlin/'
git add contracts/src/main/kotlin/com/moyi/contracts/MilestoneDocumentation.kt \
  contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt contracts/openapi.json \
  app/src/test/kotlin/com/moyi/app/OpenApiContractTest.kt app/src/test/kotlin/com/moyi/app/CacheHeadersTest.kt \
  app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt
git commit -m "feat(contracts): milestones documented, with no count, never stored, and read from nothing personal"
```

Expected: `BUILD SUCCESSFUL`. Count the tests from the result files and say the number.

### Task 3: Smoke, the tools, the state map and the record

**Files:**
- Modify: `scripts/smoke.sh` (header, one section before "database state"), `scripts/moyi`
  (`milestones`; help), `tools/bruno/README.md`, `docs/state-map/data/endpoints.json`,
  `docs/state-map/data/bond.json` (and the pages `scripts/state-map-generate` writes),
  `README.md` (one line), `adr/0034-the-streak.md` (Owed "C6" discharged),
  `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` (§1, §5.2, §6.5, §7, §8, §10,
  §11, §13), `docs/learning-log.md`
- Create: `tools/bruno/23 Milestones.bru`, `adr/0041-milestones.md`

**Interfaces:**
- Consumes: the route as Task 2 built it; the exact lines Task 2 committed in
  `GetMilestones.kt` and `BondGuard.kt` (state-map `codeRef`s quote them); the test names of
  `MilestonesEndpointTest` (state-map `evidenceRef`s quote them); what Tasks 1 and 2 reported
  (mutations run and their results, test counts, predictions confirmed or not).
- Produces: nothing code depends on.

- [ ] **Step 1: The smoke section.** In `scripts/smoke.sh`, before `echo; echo "database
state"`:

```bash
echo; echo "milestones — what the bond has reached of 7, 30, 100 and 365 complete days, and what is next (FR-075, ADR-0041)"
flush_buckets
# What this section is for: milestones as the jar serves them. A bond one day old has reached
# nothing and 7 is next. Six earlier complete days, put in by hand (no day ends during a run),
# make today the seventh: reached the moment both have written, the same bytes for both, with
# nothing in the answer but what was reached and what is next. A stranger gets the bond's 404;
# no cache may keep the answer. Once the bond has ended, what was reached stays and nothing is
# next, the same bytes for both. MilestoneReached is the close job's, and no day ends during a
# run: MilestoneReachedTest holds it.
verified_account "milestoner" "203.0.113.130"; MI_A="$ACCOUNT_ACCESS"
verified_account "milestoned" "203.0.113.131"; MI_B="$ACCOUNT_ACCESS"
verified_account "outsider-m" "203.0.113.132"; MI_O="$ACCOUNT_ACCESS"
revealed_bond "milestones" "$MI_A" "$MI_B" "thank you for the first week" "and thank you for the seventh day"
MI_BOND="$RB_BOND"
expect "milestones: a bond one day old has reached nothing, and seven is next" 200 '"next":{"threshold":7}' -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_A"
is "milestones: …exactly that" "$LAST_BODY" '{"achieved":[],"next":{"threshold":7}}'
header_is "milestones: never stored" "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
expect "milestones: a stranger is told the bond's 404" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_O"

# Six complete days before today, evaluated, as the job would have left them.
MI_TODAY="$(sql "SELECT (now() AT TIME ZONE 'Africa/Lagos')::date")"
case "$MI_TODAY" in
  psql-unavailable|psql-error*) MI_MADE=0 ;;
  *) MI_MADE="$(sql "WITH d AS (INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, closed_at, version, evaluated_at, evaluated_as, evaluated_strict, freeze_applied) SELECT gen_random_uuid(), '$MI_BOND', g::date, 'REVEALED', 'Africa/Lagos', g::date::timestamptz, (g::date + 1)::timestamptz, 2, now(), (g::date + 1)::timestamptz, 0, now(), 'COMPLETE', false, false FROM generate_series('$MI_TODAY'::date - 6, '$MI_TODAY'::date - 1, interval '1 day') AS g RETURNING 1) SELECT count(*) FROM d")" ;;
esac
if [ "$MI_MADE" = 6 ]; then
  MI_SEVEN="{\"achieved\":[{\"threshold\":7,\"achievedOn\":\"$MI_TODAY\"}],\"next\":{\"threshold\":30}}"
  MI_ENDED="{\"achieved\":[{\"threshold\":7,\"achievedOn\":\"$MI_TODAY\"}],\"next\":null}"
  expect "milestones: the seventh complete day is today, reached the moment both wrote" 200 "\"achievedOn\":\"$MI_TODAY\"" -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_A"
  is "milestones: …the threshold and its date, and thirty next" "$LAST_BODY" "$MI_SEVEN"
  is "milestones: the answer is what was reached and what is next, and nothing else" "$(python3 -c 'import json,sys; print(sorted(json.loads(sys.argv[1])))' "$LAST_BODY")" "['achieved', 'next']"
  expect "milestones: …the same for him" 200 "" -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_B"
  is "milestones: …the same bytes for him" "$LAST_BODY" "$MI_SEVEN"
  expect "milestones: he leaves" 204 "" -- -X POST "$API/bonds/$MI_BOND/leave" -H "Authorization: Bearer $MI_B"
  expect "milestones: nothing next once the bond has ended, for the one who stayed" 200 '"next":null' -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_A"
  is "milestones: …and what was reached is kept" "$LAST_BODY" "$MI_ENDED"
  expect "milestones: …and for the one who left" 200 '"next":null' -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_B"
  is "milestones: …the same bytes for the one who left" "$LAST_BODY" "$MI_ENDED"
else
  skip "milestones: the seventh day and the ending (the days could not be put in by hand)"
fi
```

In the header comment, after the paragraph that ends "and after a leave, the same nothing for
both, while search still reads.", add:

```bash
# Since slice C6c it reads milestones: nothing reached and 7 next on a new bond; the seventh
# complete day today, six earlier ones put in by hand, reached the moment both wrote and the
# same for both; and after a leave, what was reached kept and nothing next, for both alike.
```

- [ ] **Step 2: Run the smoke, against its own database.**

```bash
MOYI_DB=moyi_c6c_smoke scripts/smoke.sh 2>&1 | tee /tmp/smoke-c6c.log | tail -40
grep -c '^  FAIL' /tmp/smoke-c6c.log; grep -c '^  skip' /tmp/smoke-c6c.log
docker compose exec postgres dropdb -U moyi moyi_c6c_smoke
```

Expected: `0 failed`, `0 skipped`, 24 sections (C6b's 23 and this one), 902 passed plus this
section's probes (the three account blocks, the bond's, and the section's own; count them from
the log rather than predicting them). If Postgres's 5432 is held by another project, start it
on another port and boot the jar by hand exactly as `scripts/smoke.sh` boots it, with
`--attach`, as ADR-0039's Task 4 did, and say so. Update the header's "Last run on …"
sentence with the date, the commit the jar was built from, the totals and the section count
as printed. A failure is fixed test-first in the task that owns the code, and said so in the
report. Then `grep -nE 'ERROR|Exception' <the jar's log>` must show no line from this section.

- [ ] **Step 3: The CLI.** In `scripts/moyi`, after the `on-this-day)` case:

```bash
  milestones)
    [ $# -eq 0 ] || die "usage: moyi milestones"
    signed_in; BOND="$(bond)"; authed GET "/bonds/$BOND/milestones"; show 200
    ;;
```

and in the help block, after the `moyi on-this-day` line:

```
#   moyi milestones                          the milestones reached at 7, 30, 100 and 365 days you both wrote,
#                                            each with its date, and the next; nothing next once it has ended
```

Run it against the jar of Step 2 (before the database is dropped, or against a fresh one):
`scripts/moyi milestones` for a bond with nothing reached, then after the by-hand days, then
`scripts/moyi milestones extra` (the usage line, exit non-zero).

- [ ] **Step 4: Bruno.** Create `tools/bruno/23 Milestones.bru`:

```
meta {
  name: Milestones
  type: http
  seq: 23
}

get {
  url: {{api}}/bonds/{{bondId}}/milestones
  body: none
  auth: bearer
}

auth:bearer {
  token: {{accessToken}}
}

docs {
  The milestones the bond has reached, at 7, 30, 100 and 365 days on which you both wrote, each with the date of the day that reached it, and the next threshold. Today counts once it has revealed. Nothing is next once the bond has ended, whether it was left or blocked, or while it counts down to deletion; what was reached stays.
}
```

In `tools/bruno/README.md`, the sentence "Nor are 21 Search and 22 On this day (slice C6b,
2026-10-21); `scripts/moyi search` and `moyi on-this-day` send the same requests and were
run." gains, after it: "Nor is 23 Milestones (slice C6c, 2026-10-22); `scripts/moyi
milestones` sends the same request and was run." And "(Search and On this day come after them
only because they were added later; both only read.)" becomes "(Search, On this day and
Milestones come after them only because they were added later; all three only read.)"

- [ ] **Step 5: The state map.** In `docs/state-map/data/endpoints.json`, after the
`GET /api/v1/bonds/{bondId}/on-this-day` card, add:

```json
  {
    "id": "GET /api/v1/bonds/{bondId}/milestones",
    "summary": "the milestones reached, and the next",
    "machine": "bond",
    "auth": "bearer",
    "headers": [],
    "requestErrors": [
      {
        "status": 404,
        "code": "NOT_FOUND",
        "reason": "The bond does not exist, the id is not a UUID, or you are not a member. These cases share one answer.",
        "evidence": "smoke",
        "evidenceRef": "milestones: a stranger is told the bond's 404",
        "codeRef": "modules/gratitude/src/main/kotlin/com/moyi/gratitude/web/BondGuard.kt#throw NotFoundException(\"That bond was not found.\")"
      }
    ],
    "curl": "curl \"$API/bonds/$BOND/milestones\" -H \"Authorization: Bearer $TOKEN\""
  },
```

In `docs/state-map/data/bond.json`, after the row `bond-archived-streak`, add five rows, one
per state of the bond machine, by script so that the file's own form is kept:

```bash
python3 - <<'EOF'
import json
path = "docs/state-map/data/bond.json"
rows = json.load(open(path))
action = "GET /api/v1/bonds/{bondId}/milestones"
signed_in = [{"region": "account", "states": ["SIGNED_IN"]}]
milestones = "modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GetMilestones.kt#"
test = "MilestonesEndpointTest#"
def row(slug, state, outcome, status, code, reason, rule, evidence, ref, code_ref):
    return {"id": f"bond-{slug}-milestones", "region": "bond", "from": state, "action": action, "actor": "you",
            "when": "", "guards": signed_in, "outcome": outcome, "to": state, "status": status, "code": code,
            "reason": reason, "rule": rule, "evidence": evidence, "evidenceRef": ref, "codeRef": code_ref}
added = [
    row("none", "NO_BOND", "refused", 404, "NOT_FOUND",
        "There is no bond of yours under this id: you were never a member of it, it does not exist, or the id is not a UUID. One answer for all three, and never 403.",
        "T-02, ADR-0026 §2", "smoke", "milestones: a stranger is told the bond's 404",
        "modules/gratitude/src/main/kotlin/com/moyi/gratitude/web/BondGuard.kt#throw NotFoundException(\"That bond was not found.\")"),
    row("pending-member", "PENDING_MEMBER", "ok", 200, None,
        "A bond of one has reached nothing, whatever its creator wrote: achieved is empty and 7 is next.",
        "doc 04 §8.3a", "test", test + "a bond still waiting for its second person has reached nothing, whatever its creator wrote",
        milestones + "val total = if (counted) evaluated.total + 1 else evaluated.total"),
    row("active", "ACTIVE", "ok", 200, None,
        "Each of 7, 30, 100 and 365 complete days reached, with the date of the day that reached it, and the next threshold, with no count. Today counts once it has revealed and follows the last evaluated day, as the streak's numbers do.",
        "FR-075, ADR-0041", "smoke", "milestones: the seventh complete day is today, reached the moment both wrote",
        milestones + "val counted = streak.countsToday(membership, today, todayComplete)"),
    row("pending-deletion", "PENDING_DELETION", "ok", 200, None,
        "What was reached is kept and nothing is next: the bond takes no writes while it counts down. Calling the deletion off brings next back. The same bytes as an ended bond's.",
        "ADR-0041 §7", "test", test + "an ended bond keeps what it reached with nothing next, the same bytes left, blocked or counting down, for both members",
        milestones + "next = if (membership.takesWrites) Milestones.next(total) else null,"),
    row("archived", "ARCHIVED", "ok", 200, None,
        "An ended bond keeps what it reached, and nothing is next: the same bytes for both members, whether it was left or blocked.",
        "ADR-0041 §7, states.md §9", "smoke", "milestones: nothing next once the bond has ended, for the one who stayed",
        milestones + "next = if (membership.takesWrites) Milestones.next(total) else null,"),
]
at = next(i for i, r in enumerate(rows) if r["id"] == "bond-archived-streak")
rows[at + 1:at + 1] = added
json.dump(rows, open(path, "w"), indent=2, ensure_ascii=False)
open(path, "a").write("\n")
EOF
git diff --stat docs/state-map/data/bond.json
```

First check `git diff docs/state-map/data/bond.json` shows only the five added rows: if
`json.dump` reflowed the file (its indent, key order or final newline differ from what was
there), restore it (`git checkout -- docs/state-map/data/bond.json`) and insert the rows as
text in the file's own form instead. Then:

```bash
scripts/state-map-check --strict; echo "exit $?"
PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests
scripts/state-map-generate
git diff --stat docs/state-map
```

Expected: `exit 0`, the checker's tests pass, and the regenerated pages show the endpoint and
its five rows. Every `codeRef` must be the line exactly as Task 2 committed it; if ktlint
wrapped one, quote the wrapped line that holds the same expression. If `--strict` asks for
something these rows do not give (the card's `machine`, a reason's form, an `evidenceRef`'s
form), follow the checker, not this step, and say what it asked.

- [ ] **Step 6: README.** In `README.md`'s "Using it by hand" block, after
`scripts/moyi on-this-day`, add:

```
scripts/moyi milestones                   # 7, 30, 100 and 365 days you both wrote: which, and when
```

- [ ] **Step 7: ADR-0041.** Create `adr/0041-milestones.md` in ADR-0039's form, each section
written in full from this plan and from what the tasks found (their reports, not this plan's
predictions):
  - **Status:** Accepted · **Date:** the day it is written · **Deciders:** "Daniel, for the
    four rulings of 2026-10-22 (Q-M1, Q-M2, Q-M3, Q-M5) only. The eight defaults under 'The
    controller's' were set by the controller of the C6 session; every other decision here was
    made by the plan or by the agents that ran it."
  - **Context:** FR-075 (doc 03:85, SHOULD) and the requirements it derives from (FR-070,
    FR-071, FR-074, FR-076, BR-4, BR-10); doc 06:174; spec §1's "derivable from the streak
    state"; ADR-0034 decision 11 and its Owed "C6"; doc 12:66; the corpus read's M1–M6 and
    H-M1–H-M5 (`c6-corpus.md` §4, 2026-10-15, in the session's working folder and not in this
    repository), each stated in one line; then **the table of which test answers each hazard,
    as built** (this plan's "Privacy hazards" table, corrected by what the reviews found); then
    the slice's history: three tasks, the plan's file, the branch cut from C6b's at `6116756`,
    and any rebase with the commit it landed on.
  - **Decision**, in three parts kept apart: *The owner's rulings* (the four, as above, each
    with the contradiction it resolves: Q-M1 → M1, Q-M2, Q-M3 → M3, Q-M5); *The controller's
    defaults* (1–8 as above; at default 1, that the predicate is `takesWrites` and why, which
    is this plan's decision 7); *What the plan decided* (decisions 1–16, numbered as here, each
    with what was run to decide or hold it: the tests seen red first, the mutations and the
    tests they turned red, **and the ones that survived** — `takesWrites` → `isOpen`, the
    joining-day reconcile, `crossed`'s lower bound against the first event test — with why).
    Add one line under the rulings: "A peer session's pull request, which merges after this
    one, builds the owner's ruling of 2026-10-21 on ADR-0035 question 9 (a `PENDING_REVEAL` day
    is withheld once its bond has ended). Such a day will then not be `REVEALED`, so not
    complete: no milestone counts it and no `MilestoneReached` names it. Nothing here needs to
    change for that; no test here pins the reveal of an ended bond's waiting day."
  - **Consequences:** one new route, additive; no new `ErrorCode`, no `breaking-api-change`;
    **no migration** (spec §7's C6c row holds); a third event type from the close job's
    evaluation (`MilestoneReached`, aggregate `BondDay`, `{bondId, bondDayId}`), with no
    consumer yet, so no delivery rows; `GetStreak.countsToday` is now the one rule for today,
    shared by two routes; the read is three statements on one bond's rows, no lock;
    `scripts/moyi milestones`; Bruno `23 Milestones`; the state map's card and five rows.
  - **Owed:**
    - **Phase 4: the shareable card** (FR-075: "containing no entry content by default"; the
      card's contents, display names or the bond's name, are the owner's), and **the in-app
      celebration**, the client's: novelty is known on the device from `achievedOn`, never
      from server state (H-M1).
    - **Phase 4: any notification made of `MilestoneReached` reaches both members or neither**
      (H-M5), and **none on a bond that takes no writes** (doc 02:144, "no notifications"): the
      consumer must ask the bond, because the event is written for an ended bond's last days
      too (decision 9).
    - **The milestone's frame**: `states.md` §6 has the row and no frame (doc 27:85); spec §11
      amended (default 8). `states.md` lives outside this repository.
    - **The corpus**: doc 12:66, spec §1, doc 06:174 and ADR-0034 decision 11 (below), carried
      into `Gratitude/documents/`, and ADR-0035–ADR-0041 copied into
      `Gratitude/documents/adr/` (c6-corpus X3).
    - **The guard's older copies** (ADR-0039 decision 21), unchanged.
  - **Questions that are the owner's:** the four rulings are his. Then **one line per
    controller default, with the alternative and what turning it would cost**:
    1. *Ended: achieved kept, next null, `takesWrites`.* Alternative: hide `achieved` too
       (doc 02:144's "no streak"), which diminishes the archive (doc 02:127); or "archived"
       only, as ADR-0033 question 1 was ruled, which shows a countdown a goal it cannot reach.
    2. *Dated by the timeline.* Alternative: a `milestones` table written at the close; a
       migration, a second copy of a fact the days already hold, and a date the reveal could
       not show until midnight.
    3. *`MilestoneReached {bondId, bondDayId}`.* Alternative: one event type per threshold, or
       a threshold in the payload; four types, or a number in a payload that is ids only.
    4. *No per-member "seen".* Alternative: a server flag; a read receipt (FR-064).
    5. *Bond-level only.* Alternative: "you wrote 30 days"; it resolves to the partner's
       misses.
    6. *Thresholds and dates only.* Alternative: the day's entries or ids on a milestone; entry
       content on a surface FR-075 says carries none by default.
    7. *Both or neither.* Alternative: notify the member who opened the app; it tells them
       about the other.
    8. *No frame, spec §11 amended.* Alternative: decide Archive or Streak now, with no frame
       to build to.
    Then the plan's decisions most worth his eye, one line each: **6** (no `totalCompleteDays`
    here, while `GET /streak` keeps sending it: whether the streak screen shows the total at
    all is design, and the one way left to make a countdown); **7** (the guard's membership,
    not one resolved again); **9** (the event is written on an ended bond); **4** (today counts
    at the reveal, not at the second entry, on a bond with a reveal time).
  - **What the corpus now says that is false:**
    1. **Doc 12:66**, "streak 7 → milestone fires": seven complete days, not a run of seven
       (Q-M1). A run of seven with a covered day in it reaches nothing.
    2. **Spec §1** and **§7's C6c row**, "derivable from the streak state": `streak_states`
       holds no dates; they are the timeline's (amended in place).
    3. **Doc 06:174**, "Achieved and next milestone": `next` is a threshold and nothing else,
       and null once all four are reached or the bond takes no writes.
    4. **Doc 02:144** and **`states.md`:790**, "no streak" on an archived bond: milestones
       answer there, as history, as the streak does.
    5. **Spec §11**, "`/milestones` → §7 *Streak*": no frame; `states.md` §6's row (amended).
    6. **ADR-0034 decision 11**, "the outbox gets two": the evaluation now writes three event
       types (its Owed "C6" is discharged).
  - **Revisit when:** a bond's history passes a few thousand days (the read numbers every
    complete day of the bond, and keeps five rows); a fifth threshold or a yearly one is wanted
    (Q-M2; one constant and the contract's sentence); Phase 4 builds the card and the push; a
    second consumer of `MilestoneReached` appears (it carries ids only, by design); the peer's
    `PENDING_REVEAL` ruling lands (the "Decision" note above).
  - **How this was checked:** what was run, with dates and commits, by whom: each task's tests
    seen red first and why; every mutation with the test it turned red, and the survivors with
    why; the predictions marked "to be confirmed by running" and what each run showed (the
    reveal at 19:01Z, the day-0 row, the countdown's status, the `int[]` binding, the field
    order); `./gradlew build` with its test count from the result files; the smoke run's totals
    and how the jar was booted; the CLI by hand; `scripts/state-map-check --strict`; the
    contract's diff as read; and what was only read (the invariant of decision 2; that today
    is never evaluated while it is today; that account deletion leaves a day's status, which
    no test here drives, being `identity`'s route: the withdrawal's erasure is the test).

- [ ] **Step 8: ADR-0034's Owed.** In `adr/0034-the-streak.md`, the header's
`**Amended:** 2026-10-09 (the status; "Owed — C5, the archive" discharged — ADR-0036)` becomes
`**Amended:** 2026-10-09 (the status; "Owed — C5, the archive" discharged — ADR-0036);
2026-10-22 ("Owed — C6" discharged — ADR-0041)`, and after the Owed line
"**C6.** `MilestoneReached` and the milestones endpoint." add:

```
*(Discharged 2026-10-22, ADR-0041. `MilestoneReached` is written by the evaluation, beside
`StreakExtended`, for each threshold of complete days a day crosses: decision 11's "the outbox
gets two" is now three. The endpoint dates each milestone by the bond's days and counts today
by decision 8's rule, now one function, `GetStreak.countsToday`.)*
```

- [ ] **Step 9: The spec, amended in place and dated** ("*Amended 2026-10-22, ADR-0041.*"):
  - **§1**, after "the rendered shareable card is Phase 4.": " *(Amended 2026-10-22,
    ADR-0041: the dates are the `bond_days` timeline's, the Nth day evaluated `COMPLETE`; the
    streak state holds no dates. Built in C6c, with `MilestoneReached`.)*"
  - **§5.2**, after the C6b "As built" table, a C6c one:

```
*Amended 2026-10-22, ADR-0041 — the milestones row as slice C6c built it.*

| Method | Path | As built |
|---|---|---|
| GET | `/bonds/{bondId}/milestones` | `{achieved: [{threshold, achievedOn}], next: {threshold} \| null}` (`MilestonesResponse`). The thresholds are 7, 30, 100 and 365 complete days, cumulative (the owner's Q-M1, Q-M2), oldest first, each dated by the day that reached it: the Nth day evaluated `COMPLETE`, or today once it has revealed and follows the last evaluated day, by `GET /streak`'s own rule (Q-M5). `next` is the threshold only (Q-M3); `null` after 365 and whenever the bond takes no writes from the caller (ended, left, blocked, counting down), the same bytes for every cause. No count. No parameters. The default `Cache-Control`; no `ETag` (decisions 2–7, 11) |
```

  - **§6.5**, after the 2026-10-05 amendment's last bullet:

```
**Amended 2026-10-22 — milestones (ADR-0041).**

- **FR-075's milestones count `totalCompleteDays`**, days both wrote on, over the bond's life
  (the owner's Q-M1): a covered or stepped-over day reaches nothing, and a break loses
  nothing. Four thresholds, 7, 30, 100 and 365, and nothing after (Q-M2).
- **A milestone is dated by the Nth day evaluated `COMPLETE`**, read from `bond_days`, not
  from `streak_states`, which holds no dates.
- **Today is added by one function for both reads**, `GetStreak.countsToday`: complete
  (revealed) and following the last evaluated day. A day waiting for its reveal time counts
  for neither.
- **`MilestoneReached {bondId, bondDayId}`** is written by the evaluation, in the day's
  transaction, for each threshold the day crosses; never by `recalculate`.
```

  - **§7:** the C6c row becomes

```
| C6c | none *(2026-10-22, ADR-0041)* | | milestones are derived from the `bond_days` timeline (the Nth day evaluated `COMPLETE`), not from `streak_states`, which holds no dates |
```

    and after "C6b takes V25; C6c is expected to need none." add "Amended 2026-10-22,
    ADR-0041: it needed none."
  - **§8**, after "Phase 3 writes: … `MilestoneReached`.": " *(Amended 2026-10-22, ADR-0041:
    `MilestoneReached` is `{bondId, bondDayId}` on the day's aggregate, one for each threshold
    of complete days the day crossed, written by the close job's evaluation.)*"
  - **§10:** the C6c row's "Milestones" becomes "Milestones *(built 2026-10-22, ADR-0041:
    `GET /milestones` dated by the bond's days and counting today by the streak's own rule;
    `MilestoneReached` from the evaluation; no migration)*".
  - **§11:** the `GET /streak`, `/milestones` row gains, at its end: " *(Amended 2026-10-22,
    ADR-0041: `/milestones` is not §7's. `states.md` puts the Milestone row in §6 *Archive*'s
    table and doc 27:85 says it has no frame: a **design gap** (the controller's default 8).
    What the API gives a design: `achieved` with dates, from which a device knows a milestone
    is new to it (there is no server state, H-M1), and `next` as a threshold with no count.
    The shareable card is Phase 4's.)*"
  - **§13:** add, as the next number, "15. Milestones are dated by the bond's days and today is
    counted by the streak's own function; the event is the evaluation's, ids only (ADR-0041).
    *(Amended 2026-10-22, ADR-0041.)*"

- [ ] **Step 10: The learning log.** Append to `docs/learning-log.md` an entry headed
`## 2026-10-22 · Phase 3 · Milestones — <one line on what the slice taught>`, in the form of
the entries above it (Expected / Reality / Wrong about), written from what the tasks found:
which predictions held and which did not, which mutations survived and why, and whether
extracting `countsToday` turned up any place where the two reads had already disagreed.

- [ ] **Step 11: Build, check, commit.**

```bash
./gradlew build
scripts/state-map-check --strict
git status --porcelain | grep -v '^?? \.kotlin/'
git add scripts/smoke.sh scripts/moyi tools/bruno docs/state-map
git commit -m "test(smoke): milestones from the jar, the CLI, Bruno and the state map"
git add README.md adr/0041-milestones.md adr/0034-the-streak.md \
  docs/superpowers/specs/2026-09-28-the-daily-loop-design.md docs/learning-log.md
git commit -m "docs: ADR-0041, milestones, and what slice C6c makes of the spec and ADR-0034"
```

## After the tasks

An independent review of each task, then two whole-branch readers: privacy and correctness
(Review Focus 1–4; the hazard table, each row's test read and its mechanism mutated by the
reader; the event's idempotence and its place in the transaction) and contract and
conformance (the rulings and defaults, decision 6's missing count, decision 7's predicate and
membership, the spec amendments, the state map). Fixes test-first. A pull request against
`main` once #68 has merged and this branch is rebased (`git rebase --onto origin/main 6116756
feat/gratitude-milestones`), with the concept brief; **no** `breaking-api-change` label unless
a review adds an `ErrorCode`. Ping the peer session gratitude-0f when it merges (its close-job
pull request follows). Update `moyi-backend/.claude/HANDOVER.md` at each checkpoint.
