# CloseDay rulings (ADR-0033 q1, ADR-0035 q9) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the owner's two 2026-10-21 rulings on what the close job reveals once a bond has ended: a lone entry is withheld only on an *archived* bond (q1), and a day both wrote on is withheld when the bond was archived before its reveal fell due (q9).

**Architecture:** `BondClosingView` gains `archivedAt` beside `endedAt` (which also covers a deletion counting down). `BondDay` gains the q9 rule as domain functions (`revealFallsDueAt`, `withholdReveal`, `close(now, withheld)`), and `CloseDay` asks it once, under its locks, for both places it reveals. A withheld day closes `PENDING_REVEAL` with `closedAt` and counts `COMPLETE` for the streak (owner, 2026-10-22).

**Tech Stack:** Kotlin, Spring Boot, JDBC stores, Postgres via Testcontainers, Kotest matchers, JUnit 5.

**Spec:** `adr/0033-the-close-job.md` (q1 ruling, under "Questions that are the owner's"), `adr/0035-the-outbox-poller-and-withdrawal.md` (q9 ruling), `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` §6.3–6.4.

## Global Constraints

- No migration. No change to `contracts/openapi.json`. (V26 stays free for the favourites ruling.)
- The ruling on q1 covers the lone-entry reveal rule and nothing else: `BondClosingView.endedAt` keeps its meaning, and the streak's `AFTER_THE_END` and `GET /today`'s `prompt: null` during a countdown are unchanged.
- "Ended" for q9 means **archived**, as for q1 (controller's decision; a countdown that is called off must not lock a both-wrote day for ever).
- q9 is judged by **when the reveal fell due**, never by when the job ran: `archivedAt.isBefore(day.revealFallsDueAt(revealTime))` withholds. Mirrors q1's `isBefore(endsAt)`.
- A withheld day: status `PENDING_REVEAL`, `closedAt` set, `revealedAt` null, no entry stamped, no `DayRevealed`, one `DayClosed`. Streak: `COMPLETE` (owner, 2026-10-22).
- Lock order and transaction shape of `CloseDay` unchanged. Commit titles follow the repo's format (see `CLAUDE.md`; CI checks the title with its ` (#N)` suffix); end each commit with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- KDoc density and voice: match `CloseDay.kt` and `BondDay.kt` (rule, then why, then the ADR it comes from).

## Review Focus

1. **The job runs late:** reveal due 21:00, Bea leaves 21:05, the sweep runs 21:16 → the day IS revealed (it fell due while the bond was theirs). Pinned in Task 2.
2. **A countdown, then nothing:** both wrote; deletion requested before the reveal time; the reveal time passes → revealed (a countdown is not "ended"). Same for q1's lone entry. Pinned in Tasks 1 and 2.
3. **The reveal time was cleared while pending** (`revealTime == null` on a `PENDING_REVEAL` day): due is taken as `endsAt`, the safe side; archived before the end → withheld. Pinned in Task 2 (domain).
4. **A joining day both wrote on, archived before its reveal:** `resumeJoiningDay` gives a two-entry `PARTIAL`; `withholdReveal` must make it `PENDING_REVEAL` or `close` fails its `check`. Pinned in Task 2 (domain).
5. **Reads of a withheld day:** neither member reads the other's words in the archive/day view, and search does not find the partner's words. Pinned in Task 2 (integration).

---

### Task 1: q1 — only an archived bond withholds a lone entry

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/api/BondAccess.kt` (`BondClosingView`, ~line 224)
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/service/BondAccessAdapter.kt` (`closingView`, ~line 101)
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/CloseDay.kt` (`close`, and the call in `underTheDayLock`)
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/service/CloseDayTest.kt`
- Test: the bond module's closing-view test, if one exists (`grep -rln "closingViewOf\|lockClosingViewOf" modules/bond/src/test`); add the field assertion there.

**Interfaces:**
- Produces: `BondClosingView.archivedAt: Instant?` — when the bond was archived (left or blocked), `null` while it is live or only counting down to deletion. Task 2 uses it.

- [ ] **Step 1: Write the failing tests** in `CloseDayTest`, beside `a lone entry on a bond that ended before the day did is never revealed...` (keep that test; it is the archived case). How a test requests deletion: find the route in `modules/bond` (`grep -rn "deletion" modules/bond/src/main/kotlin/com/moyi/bond/web`), and use it as the leave test uses `/leave`.

```kotlin
@Test
fun `a lone entry on a day that ended while a deletion counted down is revealed - a countdown is not an ending`() {
    val bond = pairedBond()
    submit(ada, bond, """{"text":"written before the countdown"}""").status shouldBe 201
    clock.set(NOW.plusSeconds(7_200))
    requestDeletion(bea, bond) // 202/204 as the route returns

    closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

    status(bond) shouldBe "SOLO"
    jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 1
}

@Test
fun `a lone entry on a bond archived after the day ended is revealed`() {
    // archived at END + 1 min, settled at END + 2 min: ended after the day did
}
```

- [ ] **Step 2: Run them; the first fails** (today `endedAt` includes `deletionRequestedAt`).

Run: `./gradlew :modules:gratitude:test --tests '*CloseDayTest*'`
Expected: the countdown test FAILS with `0 shouldBe 1`.

- [ ] **Step 3: Implement.** In `BondClosingView`, after `endedAt`:

```kotlin
    /**
     * When the bond was archived — left or blocked — and `null` while it is
     * live or only counting down to deletion. Narrower than [endedAt], on
     * purpose: a countdown can be called off, and the couple are then still
     * together. What the closer asks before revealing anything once the bond
     * has ended (ADR-0033 question 1, ADR-0035 question 9, ruled 2026-10-21).
     */
    val archivedAt: Instant?,
```

In `BondAccessAdapter.closingView`: `archivedAt = bond.archivedAt,`. Check that a leave or block during `PENDING_DELETION` sets `archivedAt` (`Bond.kt` ~line 278); if it does not, stop and report.

In `CloseDay`: pass `view.archivedAt` to `close`, rename its parameter `bondArchivedAt`, and the local `bondArchivedFirst = bondArchivedAt != null && bondArchivedAt.isBefore(closed.endsAt)`. Rewrite the comment above it: archived only; a countdown is not an ending, because it can be called off and would leave the entry its author's alone with nothing to release it (ADR-0033 q1, ruled 2026-10-21). Update the class KDoc's step 6 to say "archived" where it says "stopped taking writes".

- [ ] **Step 4: Run** `./gradlew :modules:gratitude:test --tests '*CloseDayTest*' :modules:bond:test` — PASS.
- [ ] **Step 5: Mutate.** Put `view.endedAt` back in the call; the countdown test must fail. Restore.
- [ ] **Step 6: Commit** `fix(gratitude): a lone entry is withheld only on an archived bond (ADR-0033 q1)`.

---

### Task 2: q9 — a both-wrote day is withheld when the bond was archived before its reveal fell due

**Files:**
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/BondDay.kt` (`revealWhenDue` neighbourhood; `close`)
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Streak.kt` (`DayOutcome.of`)
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/CloseDay.kt`
- Modify: the `BondDayStatus.SUSPENDED` KDoc / the "Closed is a fact about the status" paragraph in `BondDay.kt`: a closed `PENDING_REVEAL` is the third settled-but-not-`isClosed` row.
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/CloseTransitionTest.kt`, `RevealTransitionTest.kt`, `StreakRulesTest.kt` (or wherever `DayOutcome.of` is tested)
- Test: `CloseDayTest.kt`; `StreakEvaluationTest.kt`; one archive read test (find the day-view test: `grep -rln "/days/" modules/gratitude/src/test`)

**Interfaces:**
- Consumes: `BondClosingView.archivedAt` (Task 1).
- Produces, on `BondDay`:
  - `fun revealFallsDueAt(revealTime: LocalTime?): Instant` — the reveal time on this day's calendar, capped at `endsAt`; `endsAt` when `revealTime` is null.
  - `fun withholdReveal(): BondDay` — a two-entry unrevealed `PARTIAL`/`PENDING_REVEAL` day becomes `PENDING_REVEAL`; anything else is returned as is.
  - `fun close(now: Instant, withheld: Boolean = false): BondDay` — with `withheld`, `PENDING_REVEAL` keeps its status and gains only `closedAt`.

- [ ] **Step 1: Domain tests first** (`CloseTransitionTest` / `RevealTransitionTest`, in their existing style):

```kotlin
@Test
fun `a pending day closed withheld keeps its status, gains closedAt, and records no reveal`() {
    val day = pendingDay() // two entries, PENDING_REVEAL
    val closed = day.close(day.endsAt, withheld = true)
    closed.status shouldBe BondDayStatus.PENDING_REVEAL
    closed.closedAt shouldBe day.endsAt.truncatedTo(ChronoUnit.MICROS)
    closed.revealedAt shouldBe null
    closed.isSettled shouldBe true
}

@Test
fun `withholding a resumed joining day both wrote on makes it pending, so it can close`() {
    val day = twoEntryPartialDay()
    day.withholdReveal().status shouldBe BondDayStatus.PENDING_REVEAL
}

@Test
fun `withholding leaves a revealed day, a SOLO day and an open one as they are`() { /* three asserts */ }

@Test
fun `the reveal falls due at the reveal time on the day's calendar, capped at its end, and at its end with no reveal time`() {
    // Lagos day 2026-09-15: 20:00 -> 19:00Z; 23:59 -> 22:59Z; null -> endsAt; a time past a westward-extended end -> endsAt
}
```

And in the `DayOutcome` test: `DayOutcome.of(BondDayStatus.PENDING_REVEAL) shouldBe DayOutcome.COMPLETE`.

- [ ] **Step 2: Run; they fail to compile.** `./gradlew :modules:gratitude:test --tests '*TransitionTest*'`
- [ ] **Step 3: Implement the domain.**

```kotlin
    /**
     * When this day's reveal fell due: the reveal time on its own calendar,
     * or its end if that comes first (a day's end outranks a later reveal
     * time, [close]). With no reveal time a two-entry day reveals as the
     * second entry lands and is never pending; a pending day whose time was
     * cleared afterwards is taken to fall due at its end, the side on which
     * nothing is revealed early.
     */
    fun revealFallsDueAt(revealTime: LocalTime?): Instant =
        revealTime?.let { minOf(date.atTime(it).atZone(anchorTimezone).toInstant(), endsAt) } ?: endsAt

    /**
     * Both wrote, but the bond was archived before the reveal fell due
     * (ADR-0035 question 9, ruled 2026-10-21): an ended bond reveals nothing
     * new. The day waits as `PENDING_REVEAL` and closes so, never `REVEALED`.
     */
    fun withholdReveal(): BondDay =
        if (entryCount == MAX_ENTRIES && revealedAt == null && status in setOf(BondDayStatus.PARTIAL, BondDayStatus.PENDING_REVEAL)) {
            copy(status = BondDayStatus.PENDING_REVEAL)
        } else {
            this
        }
```

`close(now, withheld = false)`: the `PENDING_REVEAL` branch becomes
`if (withheld) copy(closedAt = at) else copy(status = REVEALED, revealedAt = revealedAt ?: at, closedAt = at)`. Add a bullet to its KDoc. `DayOutcome.of`: `PENDING_REVEAL -> COMPLETE` (only settled days are asked; a closed one is a day both wrote — owner, 2026-10-22); keep `OPEN, PARTIAL -> null`. Fix its KDoc.

- [ ] **Step 4: Domain tests pass.**
- [ ] **Step 5: Integration tests** in `CloseDayTest` (Lagos day; reveal 20:00 = 19:00Z):

```kotlin
@Test
fun `a day both wrote on is withheld when the bond was archived before its reveal - nothing revealed, closed pending`() {
    val bond = pairedBond()
    jdbc.update("UPDATE bonds SET reveal_time_local = '20:00' WHERE id = ?::uuid", bond)
    submit(ada, bond, """{"text":"hers"}""").status shouldBe 201
    submit(bea, bond, """{"text":"his"}""").status shouldBe 201
    clock.set(Instant.parse("2026-09-15T15:00:00Z"))
    leave(bea, bond) shouldBe 204

    closeDay.settle(dayOf(bond), Instant.parse("2026-09-15T19:01:00Z")) shouldBe Outcome.NOT_YET
    closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

    status(bond) shouldBe "PENDING_REVEAL"
    closedAt(bond) shouldNotBe null
    jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL OR status = 'REVEALED'", Int::class.java) shouldBe 0
    events("DayRevealed") shouldBe 0
    events("DayClosed") shouldBe 1
    closeDay.settle(dayOf(bond), END.plusSeconds(900)) shouldBe Outcome.ALREADY_CLOSED
}

@Test
fun `a reveal that fell due before the bond was archived is made, however late the job runs`() {
    // leave at 19:05Z; settle at 19:16Z -> REVEALED, 2 entries stamped, DayRevealed 1
}

@Test
fun `a day still pending at its end is withheld when the bond was archived before the end`() {
    // reveal 23:59 (22:59Z); leave at 15:00Z; settle(END) -> CLOSED, PENDING_REVEAL, 0 revealed
}

@Test
fun `a day both wrote on whose reveal comes during a deletion countdown is revealed`() {
    // deletion requested 15:00Z; settle 19:01Z -> REVEALED
}
```

In `StreakEvaluationTest` (its own helpers): a withheld day evaluates `COMPLETE` and extends the run; the evaluation does not throw on it.

In the archive/day-view test: after a withheld close, `GET` the day as Ada → her own entry readable, Bea's not (in whatever shape a locked partner entry has today — read `EntryReadabilityTest` and the day view first); the day's status is `PENDING_REVEAL`. Search as Ada for a word only in Bea's entry → nothing.

- [ ] **Step 6: Run; they fail.**
- [ ] **Step 7: Implement in `CloseDay.underTheDayLock`:**

```kotlin
        val prepared = locked.extendedTo(window).resumeJoiningDay(view.activeSince)
        // ADR-0035 question 9, ruled 2026-10-21: an ended bond reveals nothing
        // new. Asked of when the reveal fell due, not of when this run came: a
        // job a quarter-hour late must not decide who reads what. Archived only,
        // as for the lone entry: a countdown can be called off.
        val withheld =
            prepared.entryCount == BondDay.MAX_ENTRIES &&
                prepared.revealedAt == null &&
                view.archivedAt?.isBefore(prepared.revealFallsDueAt(view.revealTimeLocal)) == true
```

(If `MAX_ENTRIES` is not reachable from `service`, use the accessor the code already uses, or make the predicate a `BondDay` function `isWithheldBy(archivedAt: Instant?, revealTime: LocalTime?): Boolean` and test it in the domain — preferred, as the rule then lives in one place.) Then:

```kotlin
        val current = if (withheld) prepared.withholdReveal().also(days::update) else reveal.apply(prepared, view.revealTimeLocal, at)
```

and `close(current, at, view.archivedAt, withheld)`, where `close` skips the end-of-day `reveal.apply` when `withheld` and calls `day.close(now, withheld)`. Update the class KDoc steps 4 and 6. `REVEALED` outcome logic is unchanged (a withheld day never gains `revealedAt`).

- [ ] **Step 8: Run** `./gradlew :modules:gratitude:test` — PASS, including `MilestoneReachedTest` unchanged (its bond has no reveal time; the day reveals at the second entry).
- [ ] **Step 9: Mutate, one at a time, each must fail a test:** (a) `withheld = false`; (b) compare against `at` instead of the due instant; (c) `view.endedAt` instead of `archivedAt`; (d) `DayOutcome.of(PENDING_REVEAL) -> null`; (e) drop the cap at `endsAt`. Restore.
- [ ] **Step 10: Commit** `feat(gratitude): a day both wrote on is withheld once the bond was archived before its reveal (ADR-0035 q9)`.

---

### Task 3: The record

**Files:**
- Create: `adr/0040-what-an-ended-bond-reveals.md` (structure of `adr/0041-milestones.md`: Context, Decision — owner's rulings incl. 2026-10-22's, controller's decisions, Consequences, Owed, Questions that are the owner's, What the corpus now says that is false, How this was checked)
- Modify: `adr/0033-the-close-job.md`, `adr/0035-the-outbox-poller-and-withdrawal.md`: after each "to be built" ruling, `*(Built 2026-10-22, ADR-0040.)*`
- Modify: `docs/state-map/day.md` (and whatever `scripts/state-map-check --strict` reports); `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` §6.4 if it says a pending day always closes `REVEALED`
- Modify: `.claude/HANDOVER.md` (new top entry)

- [ ] **Step 1:** `grep -rn "PENDING_REVEAL" docs adr | grep -i "close\|REVEALED"` and amend every sentence that says a pending day always closes revealed.
- [ ] **Step 2:** Write ADR-0040, including Questions for the owner: (1) a cleared reveal time is taken as due at the end; (2) the leaver's archive — does the leaver see the withheld day as `PENDING_REVEAL` (same as Ada); (3) `GET /today` of an archived bond is unaffected.
- [ ] **Step 3:** `scripts/state-map-check --strict` → 0 wrong; `./gradlew build` green; `scripts/smoke.sh` (see memory `moyi-stack-gotchas` for `MOYI_DB` and the port) → 0 failed. Record the numbers in ADR-0040 "How this was checked".
- [ ] **Step 4: Commit** `docs: ADR-0040, what an ended bond reveals, and the rulings marked built`.
