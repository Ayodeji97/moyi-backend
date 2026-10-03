# Slice C1 rework — the deferred anchor timeline, the lock order, and idempotency that stores ids

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring slice C1 into line with the daily-loop spec as revised on 2026-09-28 (`dccd381`), so that a bond's day boundaries are decided by a persisted, bond-owned timeline rather than a zone string copied at row creation; so that every gratitude write serialises against the bond's own lifecycle; and so that an idempotency key stores result identity rather than a second copy of the couple's words.

**Architecture:** Five deltas, built in dependency order. The new load-bearing structure is `bond_anchor_intervals` — a contiguous, non-overlapping, bond-owned record of which IANA zone was *effective* over which UTC span. B5's confirmation path stops taking effect immediately and instead schedules a handoff at the end of the current logical day. `bond_days` stops deriving its boundaries from a copied string and persists the UTC interval `[startsAt, endsAt)` it was resolved against. `gratitude` reaches all of this through `bond.api.BondAccess`, which gains a locking method so the bond row is held from before day resolution until commit. Idempotency moves out of a `preHandle` interceptor and into the handler's own transaction, keyed on `(user_id, key)`, storing the result's identity instead of its body.

**Tech Stack:** Kotlin 2.4, Spring Boot 4, Spring Data JPA + plain JDBC for the idempotency store, Flyway (one global version sequence), Postgres 16, Testcontainers, JUnit 5 + Kotest matchers, Konsist, ktlint, detekt, PIT for mutation testing.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` (743 lines, as revised by `dccd381`). Read §2.1, §3.1, §5.4, §6.1, §6.2 and §12.5 before Task 1. The original C1 plan is `docs/superpowers/plans/2026-09-28-gratitude-day-c1.md` and describes the code as it stands *before* this rework.

**Branch:** `feat/gratitude-day`, worktree `.worktrees/feat-gratitude-day`, PR **#46 (draft)**, merged up to `main` at `c12f91b` as of `ed0e9f2`. Do not open a second branch; this plan continues the existing one.

---

## Global Constraints

- **Migrations are forward-only once merged** (doc 07 §1). V11 and V12 are on this branch and **unmerged**, so this plan edits them in place rather than adding corrective migrations — the exception the project has already used, and V11's own header comment names it.
- **Versions are one global sequence across modules.** After this plan: V11 `idempotency_keys` (`common:web`), V12 `bond_days`/`entries` (`modules:gratitude`), **V13 `bond_anchor_intervals` (`modules:bond`, new)**. Every later slice shifts by one — C2's outbox becomes **V14**, C3's ShedLock **V15**, C4's streaks **V16**, C5's reactions **V17**, C6's prompts **V18**. Spec §7's table must be corrected to match (Task 10).
- **No foreign key crosses a module boundary** (doc 25 §6, ADR-0026). `bond_anchor_intervals.bond_id` references `bonds(id)` because both are `modules:bond`. Nothing in `gratitude` references it — the timeline reaches `gratitude` only as a public DTO through `bond.api.BondAccess`.
- **`BondMembership`'s constructor stays `internal`** (ADR-0026, spec §2.1). Any new DTO the port exposes carries the same `internal` constructor for the same reason.
- **Text limits are unchanged:** `EntryText.MAX_GRAPHEMES = 500`, `MAX_OCTETS = 8192`, NFKC before counting, `BreakIterator.getCharacterInstance()`. Do not touch `EntryText`.
- **An edge constraint never restates a domain rule** — it runs the domain factory and reports its complaint. This is the third lesson (`@Version`, `Membership.left`, `@ValidEntryText`); do not regress it.
- **Every zone is an IANA region id**, never an offset (doc 04 §6). `char_length BETWEEN 1 AND 64` is the stored bound, matching `bonds.anchor_timezone`.
- **Timestamps are truncated to microseconds before persistence** (`ChronoUnit.MICROS`), matching what B5's bond audit established on `main`.
- **`./gradlew build` must be green at the end of every task.** Konsist, ktlint and detekt run inside it.

---

## Decisions made for this plan

Three rulings that the corpus does not make and the spec does not settle. Each is recorded here so an implementer does not re-derive it, and each goes into ADR-0031 in Task 10.

**R1 — the close job takes no bond lock (ruled by Daniel, 2026-09-30).** C3's sweep processes many bonds per run; queueing behind each bond's row would serialise the whole sweep. The consequence, and the reason this matters in C1: `bond_days`'s unique `(bond_id, date)` index stays genuinely load-bearing, because a live submission and the sweep can still collide. **The existing "two first-entries racing produce one row" test stops proving anything** the moment Task 4 puts the bond lock in front of the submit path — both members now queue on the bonds row — so Task 5 replaces it with a race the lock does not cover.

**R2 — `BondMembership` keeps `hasLeft`.** Spec §2.1's field list omits it. `SubmitEntry.kt:125` reads `if (membership.hasLeft || !membership.isOpen)`, and that check exists because the second review of PR #41 found `RequestDeletion.cancel` assuming `isOpen` covered it. The spec revision predates that review landing. Treat §2.1's list as *additive*: keep `hasLeft` and `awaitingSecondMember`, add `activeSince` and `endedAt`, and rename nothing.

**R3 — a westward zone change merges two calendar dates into one long day, and that is accepted.** Doc 04 §8.5 addresses only the eastward case, where a date is skipped outright and is granted `FROZEN` so the streak is not broken. Moving west produces the mirror: the handoff instant lands on a date label the bond has already used. Spec §3.1's answer is "extend the handoff to the next unused date boundary", which makes the current day up to ~26 hours long. The couple therefore gets one fewer opportunity to write, but **no day is missed**, so BR-4's run is not broken and §8.5's protection is not needed. No compensating `FROZEN` day is created — there is no unused date label to give it. `freezeProgress` counts the extended day once, like any other day.

---

## Corpus findings this plan carries

Recorded so they are not rediscovered mid-build.

- **`activeSince` is not stored and the obvious derivation is wrong.** `bonds` has `created_at`, `archived_at`, `deletion_requested_at` — no activation instant. It is `max(bond_members.joined_at)`, i.e. the *second* member's join. Reaching for `bonds.created_at` instead would make C3's sweep manufacture `EMPTY` days across the whole `PENDING_MEMBER` window — precisely the harm doc 04 §8.3a's `SUSPENDED` resolution exists to prevent.
- **A skipped date's day has a degenerate interval.** `startsAt == endsAt`. The reflexive aggregate invariant `require(startsAt < endsAt)` is therefore **wrong** and will fail on the first eastward date-line test. The invariant is `require(!startsAt.isAfter(endsAt))`.
- **V11's and V12's header comments about "V10 is reserved elsewhere and is not on this worktree's classpath"** are stale — V10 (`bond_proposals`) arrived with B5 and the merge at `ed0e9f2`. V12's F11 merge-sequencing warning is likewise discharged. Both comments are corrected in the tasks that touch those files.

---

## File Structure

**`modules/bond` — the timeline's owner.**

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V13__bond_anchor_intervals.sql` | **New.** The table, its constraints, and the backfill for bonds that already exist. |
| `src/main/kotlin/com/moyi/bond/domain/AnchorInterval.kt` | **New.** The immutable domain value, and `AnchorTimeline` — the pure function that answers "which zone was effective at instant *t*" and "when does the logical day containing *t* end". No Spring, no store. |
| `src/main/kotlin/com/moyi/bond/infra/database/AnchorIntervalEntity.kt` | **New.** Flat JPA entity, hand mapper, matching `BondEntity`'s shape (ADR-0026). |
| `src/main/kotlin/com/moyi/bond/infra/database/AnchorIntervalStore.kt` | **New.** Read the open interval, close it, open the next. All writes assume the bond row lock is already held. |
| `src/main/kotlin/com/moyi/bond/service/CreateBond.kt` | Modify — seeds the bond's first interval at creation. |
| `src/main/kotlin/com/moyi/bond/service/ChangeTimezone.kt:96-119` | Modify — `confirm` schedules a handoff instead of taking effect now. |
| `src/main/kotlin/com/moyi/bond/api/BondAccess.kt` | Modify — `lockMembershipOf`, `activeSince`, `endedAt`, and the timeline DTO. |
| `src/main/kotlin/com/moyi/bond/infra/BondAccessAdapter.kt` | Modify — implements the above. |

**`modules/gratitude` — the consumer.**

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V12__gratitude_bond_days_and_entries.sql` | Modify — `starts_at`, `ends_at`, and a corrected header. |
| `src/main/kotlin/com/moyi/gratitude/domain/BondDay.kt` | Modify — carries the interval; `open`/`openSuspended`/`openFrozen` take it. |
| `src/main/kotlin/com/moyi/gratitude/domain/DayAssignment.kt` | Modify — resolves against a timeline, not a single `ZoneId`. |
| `src/main/kotlin/com/moyi/gratitude/domain/Entry.kt` | Modify — `canBeReadBy` keys on `revealedAt`; tombstones. |
| `src/main/kotlin/com/moyi/gratitude/infra/database/BondDayStore.kt` | Modify — `openOrGet` takes the interval; new `openFrozen`. |
| `src/main/kotlin/com/moyi/gratitude/service/SubmitEntry.kt` | Modify — lock order, settled-day recheck, the redirect. |
| `src/main/kotlin/com/moyi/gratitude/service/GetToday.kt` | Modify — BR-1 through `revealedAt`. |

**`common:web` — idempotency.**

| File | Responsibility |
|---|---|
| `src/main/resources/db/migration/V11__common_idempotency_keys.sql` | Modify — drop `response_body`, add result identity, correct the header. |
| `src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyRecord.kt` | Modify — the record's new shape; `IdempotencyKeyStore` gains the advisory lock. |
| `src/main/kotlin/com/moyi/common/web/idempotency/IdempotentExecution.kt` | **New.** The in-transaction execute-once wrapper that replaces the interceptor's reserve step. |
| `src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyInterceptor.kt` | Modify — keeps the header contract and the replay response; no longer reserves. |

---

## Task 1: The anchor timeline — table, domain, backfill

**Files:**
- Create: `modules/bond/src/main/resources/db/migration/V13__bond_anchor_intervals.sql`
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/domain/AnchorInterval.kt`
- Create: `modules/bond/src/test/kotlin/com/moyi/bond/domain/AnchorTimelineTest.kt`
- Modify: `app/src/test/kotlin/com/moyi/app/FlywayMigrationTest.kt` — the required subset becomes `(1..13)`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.moyi.bond.domain.AnchorInterval(val zone: ZoneId, val effectiveFrom: Instant, val effectiveTo: Instant?)`; `com.moyi.bond.domain.AnchorTimeline(val intervals: List<AnchorInterval>)` with `fun zoneAt(at: Instant): ZoneId`, `fun dateAt(at: Instant): LocalDate`, `fun dayBoundsAt(at: Instant): ClosedInstantRange`, and `fun handoffFor(now: Instant, newZone: ZoneId, usedLabels: Set<LocalDate>): Handoff`.

- [ ] **Step 1: Write the failing domain test**

Create `modules/bond/src/test/kotlin/com/moyi/bond/domain/AnchorTimelineTest.kt`:

```kotlin
package com.moyi.bond.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal class AnchorTimelineTest {
    private val lagos = ZoneId.of("Africa/Lagos")       // UTC+1 all year
    private val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14
    private val honolulu = ZoneId.of("Pacific/Honolulu")     // UTC-10

    private fun timelineOf(vararg parts: AnchorInterval) = AnchorTimeline(parts.toList())

    @Test
    fun `a single open interval answers the date in its own zone`() {
        val timeline = timelineOf(AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), null))

        // 23:30Z on the 14th is 00:30 on the 15th in Lagos (UTC+1).
        timeline.dateAt(Instant.parse("2026-09-14T23:30:00Z")) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `the day's bounds are the zone's own midnights, in UTC`() {
        val timeline = timelineOf(AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), null))

        val bounds = timeline.dayBoundsAt(Instant.parse("2026-09-15T12:00:00Z"))

        bounds.startsAt shouldBe Instant.parse("2026-09-14T23:00:00Z")
        bounds.endsAt shouldBe Instant.parse("2026-09-15T23:00:00Z")
    }

    @Test
    fun `an instant inside a closed interval reads that interval's zone, never the later one`() {
        val handoff = Instant.parse("2026-09-15T23:00:00Z")
        val timeline =
            timelineOf(
                AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), handoff),
                AnchorInterval(kiritimati, handoff, null),
            )

        // One second before the handoff the bond is still on Lagos time.
        timeline.zoneAt(handoff.minusSeconds(1)) shouldBe lagos
        timeline.zoneAt(handoff) shouldBe kiritimati
    }

    @Test
    fun `a day is clipped to its interval, so bounds never straddle a handoff`() {
        val handoff = Instant.parse("2026-09-15T23:00:00Z")
        val timeline =
            timelineOf(
                AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), handoff),
                AnchorInterval(kiritimati, handoff, null),
            )

        // Kiritimati's own 16th began at 2026-09-15T10:00Z, before the handoff.
        // The day the bond actually lives starts at the handoff.
        val bounds = timeline.dayBoundsAt(Instant.parse("2026-09-16T00:00:00Z"))

        bounds.startsAt shouldBe handoff
        bounds.endsAt shouldBe Instant.parse("2026-09-16T10:00:00Z")
    }

    @Test
    fun `moving east defers to the end of the current logical day and names the skipped labels`() {
        val timeline = timelineOf(AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), null))

        // Confirmed at midday on the 15th, Lagos time.
        val handoff = timeline.handoffFor(Instant.parse("2026-09-15T11:00:00Z"), kiritimati, usedLabels = emptySet())

        // The current day (Lagos 15th) runs to its own midnight and no further.
        handoff.at shouldBe Instant.parse("2026-09-15T23:00:00Z")
        // At that instant Kiritimati is already on the 16th at 13:00, so the
        // first day of the new interval is the 16th — no label is lost here.
        handoff.firstLabel shouldBe LocalDate.of(2026, 9, 16)
        handoff.skippedLabels shouldBe emptyList()
    }

    @Test
    fun `moving east far enough to clear a whole label marks it skipped`() {
        // Lagos day ends 23:00Z; at that instant Kiritimati is on the 16th.
        // A bond whose last used label is the 15th loses nothing. A bond that
        // confirmed just after its own midnight loses the intervening label.
        val timeline = timelineOf(AnchorInterval(honolulu, Instant.parse("2026-09-01T00:00:00Z"), null))

        // Honolulu is UTC-10; its 15th ends at 2026-09-16T10:00Z. At that
        // instant Kiritimati (UTC+14) is on the 17th at 00:00 — the 16th
        // never occurs for this bond.
        val handoff = timeline.handoffFor(Instant.parse("2026-09-15T20:00:00Z"), kiritimati, usedLabels = emptySet())

        handoff.at shouldBe Instant.parse("2026-09-16T10:00:00Z")
        handoff.firstLabel shouldBe LocalDate.of(2026, 9, 17)
        handoff.skippedLabels shouldBe listOf(LocalDate.of(2026, 9, 16))
    }

    @Test
    fun `moving west never opens a label the bond has already used`() {
        val timeline = timelineOf(AnchorInterval(kiritimati, Instant.parse("2026-09-01T00:00:00Z"), null))

        // Kiritimati's 16th ends at 2026-09-15T10:00Z. At that instant
        // Honolulu is on the 15th — a label this bond has already used — so
        // the handoff is pushed to Honolulu's next midnight instead.
        val handoff =
            timeline.handoffFor(
                now = Instant.parse("2026-09-15T00:00:00Z"),
                newZone = honolulu,
                usedLabels = setOf(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 16)),
            )

        handoff.at shouldBe Instant.parse("2026-09-16T10:00:00Z")
        handoff.firstLabel shouldBe LocalDate.of(2026, 9, 17)
        handoff.skippedLabels shouldBe emptyList()
    }

    @Test
    fun `a DST spring-forward day is twenty-three hours and a fall-back day is twenty-five`() {
        val london = ZoneId.of("Europe/London")
        val timeline = timelineOf(AnchorInterval(london, Instant.parse("2026-01-01T00:00:00Z"), null))

        val spring = timeline.dayBoundsAt(Instant.parse("2026-03-29T12:00:00Z"))
        java.time.Duration.between(spring.startsAt, spring.endsAt).toHours() shouldBe 23L

        val autumn = timeline.dayBoundsAt(Instant.parse("2026-10-25T12:00:00Z"))
        java.time.Duration.between(autumn.startsAt, autumn.endsAt).toHours() shouldBe 25L
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:bond:test --tests "com.moyi.bond.domain.AnchorTimelineTest"`
Expected: FAIL — `Unresolved reference: AnchorInterval`.

- [ ] **Step 3: Write the domain**

Create `modules/bond/src/main/kotlin/com/moyi/bond/domain/AnchorInterval.kt`:

```kotlin
package com.moyi.bond.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One span of wall-clock time over which a single IANA zone was **effective**
 * for a bond — which is not the same as the zone the bond currently requests.
 *
 * BR-6 and ADR-0030 require an approved anchor change to take effect from the
 * *next* Bond-day, never to recompute an existing one. B5 records the request
 * the moment consent completes; this records when the request starts deciding
 * dates. The two differ by up to one logical day, and that gap is the whole
 * reason this type exists: a zone string copied onto a day at lazy row
 * creation cannot defer anything, because on a day nobody has written to yet
 * there is no row to have copied it (spec §3.1).
 *
 * [effectiveTo] is null for exactly one interval per bond — the open one.
 * Intervals are contiguous: each one's [effectiveFrom] is the previous one's
 * [effectiveTo], with no gap and no overlap, which is what makes [AnchorTimeline]
 * total over every instant from the bond's creation onward.
 */
internal data class AnchorInterval(
    val zone: ZoneId,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
) {
    init {
        require(effectiveTo == null || effectiveTo.isAfter(effectiveFrom)) {
            "an anchor interval ends after it begins"
        }
    }

    fun contains(at: Instant): Boolean =
        !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo))
}

/** The UTC span of one Bond-day, half-open: `[startsAt, endsAt)`. */
internal data class DayBounds(
    val date: LocalDate,
    val startsAt: Instant,
    val endsAt: Instant,
) {
    init {
        require(!startsAt.isAfter(endsAt)) { "a bond-day ends no earlier than it begins" }
    }

    /**
     * True for a date the bond's own clock skipped entirely — an eastward
     * anchor move can clear a whole calendar label (doc 04 §8.5). The day
     * exists as a row so BR-4's run is not broken; no instant maps to it, so
     * its span is empty. **This is why the invariant above is `!isAfter` and
     * not `isBefore`** — the obvious `startsAt < endsAt` is wrong and fails on
     * the first eastward date-line test.
     */
    val isDegenerate: Boolean get() = startsAt == endsAt
}

/** Where a confirmed anchor change starts deciding dates, and what it costs. */
internal data class Handoff(
    val at: Instant,
    val firstLabel: LocalDate,
    val skippedLabels: List<LocalDate>,
)

/**
 * A bond's effective-zone history, ordered oldest first, contiguous, with
 * exactly one open interval last. Pure: it holds values and answers questions,
 * and the store is what loads it.
 */
internal class AnchorTimeline(
    val intervals: List<AnchorInterval>,
) {
    init {
        require(intervals.isNotEmpty()) { "a bond always has at least the interval it was created in" }
        require(intervals.last().effectiveTo == null) { "the last interval is the open one" }
        intervals.zipWithNext { earlier, later ->
            require(earlier.effectiveTo == later.effectiveFrom) {
                "anchor intervals are contiguous: ${earlier.effectiveTo} != ${later.effectiveFrom}"
            }
        }
    }

    fun zoneAt(at: Instant): ZoneId = intervalAt(at).zone

    fun dateAt(at: Instant): LocalDate = at.atZone(zoneAt(at)).toLocalDate()

    /**
     * The bounds of the logical day containing [at], **clipped to the interval
     * that decides it**. Clipping is what keeps days contiguous across a
     * handoff: the zone's own midnight before the handoff may lie inside the
     * previous interval, where a different zone was in charge.
     */
    fun dayBoundsAt(at: Instant): DayBounds {
        val interval = intervalAt(at)
        val zoned = at.atZone(interval.zone)
        val date = zoned.toLocalDate()
        val naturalStart = date.atStartOfDay(interval.zone).toInstant()
        val naturalEnd = date.plusDays(1).atStartOfDay(interval.zone).toInstant()
        val start = maxOf(naturalStart, interval.effectiveFrom)
        val end = interval.effectiveTo?.let { minOf(naturalEnd, it) } ?: naturalEnd
        return DayBounds(date = date, startsAt = start, endsAt = end)
    }

    /**
     * When a change confirmed at [now] starts deciding dates, and which
     * calendar labels it costs.
     *
     * The handoff is the end of the current logical day — never sooner, which
     * is BR-6's deferral. From there two things can go wrong and both are
     * handled here rather than by the caller:
     *
     * - **Eastward**, the new zone may already be past one or more labels by
     *   the handoff instant. Those never occur for this bond and come back in
     *   [Handoff.skippedLabels]; doc 04 §8.5 makes each one `FROZEN` so the
     *   run is not broken.
     * - **Westward**, the new zone's label at the handoff may be one the bond
     *   has already used. Opening it again would violate `bond_days`'s unique
     *   `(bond_id, date)`. The handoff is pushed to the next new-zone midnight
     *   until the label is unused, which extends the current day rather than
     *   duplicating a label (R3 in the plan; no compensating `FROZEN` day,
     *   because no label was lost).
     */
    fun handoffFor(
        now: Instant,
        newZone: ZoneId,
        usedLabels: Set<LocalDate>,
    ): Handoff {
        var at = dayBoundsAt(now).endsAt
        val previousLabel = dayBoundsAt(now).date
        var label = at.atZone(newZone).toLocalDate()
        while (label in usedLabels || !label.isAfter(previousLabel)) {
            at = label.plusDays(1).atStartOfDay(newZone).toInstant()
            label = at.atZone(newZone).toLocalDate()
        }
        val skipped =
            generateSequence(previousLabel.plusDays(1)) { it.plusDays(1) }
                .takeWhile { it.isBefore(label) }
                .toList()
        return Handoff(at = at, firstLabel = label, skippedLabels = skipped)
    }

    private fun intervalAt(at: Instant): AnchorInterval =
        intervals.lastOrNull { it.contains(at) }
            ?: intervals.first().takeIf { at.isBefore(it.effectiveFrom) }
            ?: error("an anchor timeline covers every instant from the bond's creation: $at")
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `./gradlew :modules:bond:test --tests "com.moyi.bond.domain.AnchorTimelineTest"`
Expected: PASS, 8 tests.

If `moving east far enough to clear a whole label` fails, the `skippedLabels` loop is the suspect — it must enumerate labels strictly between the previous day's label and `firstLabel`, exclusive at both ends.

- [ ] **Step 5: Write the migration**

Create `modules/bond/src/main/resources/db/migration/V13__bond_anchor_intervals.sql`:

```sql
-- Which IANA zone was *effective* for a bond over which span of UTC time —
-- doc 04 §6, BR-6, ADR-0030, and the Phase 3 design §3.1 as revised.
--
-- `bonds.anchor_timezone` is the zone the bond currently *requests*. This
-- table is the zone that currently *decides dates*, and they differ for up to
-- one logical day after a change is confirmed. B5 writes the request the
-- instant consent completes (ChangeTimezone.confirm); this table is what
-- defers the effect, because BR-6 says an approved change takes effect from
-- the next Bond-day and never recomputes an existing one.
--
-- Why a table and not a column: on a day nobody has written to, there is no
-- `bond_days` row to have copied a zone onto, so a copied string cannot defer
-- anything. The spec says so in as many words — "copying a string at lazy row
-- creation is insufficient".
--
-- Contiguous and non-overlapping by construction: each row's effective_from
-- is the previous row's effective_to, and exactly one row per bond has a null
-- effective_to. The partial unique index below enforces the "exactly one
-- open" half; contiguity is the domain's invariant (AnchorTimeline's `init`).
--
-- Versions are one global sequence across modules: V1 app, V2-V8 identity,
-- V9-V10 bond, V11 common:web, V12 gratitude, V13 bond again. Forward-only
-- (doc 07 §1) once merged.
CREATE TABLE bond_anchor_intervals (
    id             uuid        PRIMARY KEY,
    bond_id        uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    -- An IANA region id, validated in the domain (`RegionZone`), bounded here
    -- the same way `bonds.anchor_timezone` is.
    zone           text        NOT NULL,
    effective_from timestamptz NOT NULL,
    -- Null for the one open interval per bond.
    effective_to   timestamptz,
    created_at     timestamptz NOT NULL,

    CONSTRAINT bond_anchor_intervals_zone_length_check CHECK (char_length(zone) BETWEEN 1 AND 64),
    CONSTRAINT bond_anchor_intervals_order_check CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- Exactly one open interval per bond. A second one would make "which zone is
-- effective now" ambiguous, which is the one question this table exists to
-- answer without ambiguity.
CREATE UNIQUE INDEX bond_anchor_intervals_open_key
    ON bond_anchor_intervals (bond_id) WHERE effective_to IS NULL;
-- The timeline load: every interval for one bond, oldest first.
CREATE INDEX bond_anchor_intervals_bond_idx ON bond_anchor_intervals (bond_id, effective_from);

-- Backfill. Every bond that already exists has been deciding dates by
-- `bonds.anchor_timezone` since it was created, and no change has ever been
-- deferred, so its whole history is one open interval in its current zone.
-- `gen_random_uuid()` rather than an application-assigned v7 id: this runs
-- once, for rows nothing is holding a reference to, and pgcrypto is already
-- an extension this schema installs (V1).
INSERT INTO bond_anchor_intervals (id, bond_id, zone, effective_from, effective_to, created_at)
SELECT gen_random_uuid(), b.id, b.anchor_timezone, b.created_at, NULL, b.created_at
FROM bonds b;
```

- [ ] **Step 6: Point `FlywayMigrationTest` at V13**

Modify `app/src/test/kotlin/com/moyi/app/FlywayMigrationTest.kt` — the required subset and its message:

```kotlin
        assertTrue(
            versions.containsAll((1..13).toList()),
            "Expected every module's migrations through V13, found: $versions",
        )
```

Update the comment above it: `V9`, `V10` and **`V13`** are `modules/bond`'s.

- [ ] **Step 7: Run the migration tests**

Run: `./gradlew :app:test --tests "com.moyi.app.FlywayMigrationTest" :modules:bond:test`
Expected: PASS. If Flyway refuses to start with "Detected applied migration not resolved locally", the shared dev Postgres has stale history — that is the known blocker in `.claude/HANDOVER.md` and needs the human-run `UPDATE flyway_schema_history` command. Testcontainers builds a fresh database every run and is unaffected.

- [ ] **Step 8: Commit**

```bash
git add modules/bond/src/main/kotlin/com/moyi/bond/domain/AnchorInterval.kt \
        modules/bond/src/test/kotlin/com/moyi/bond/domain/AnchorTimelineTest.kt \
        modules/bond/src/main/resources/db/migration/V13__bond_anchor_intervals.sql \
        app/src/test/kotlin/com/moyi/app/FlywayMigrationTest.kt
git commit -m "feat(bond): the effective-anchor timeline, and the day bounds it decides (BR-6, spec §3.1)"
```

---

## Task 2: Persisting the timeline, and the deferred handoff

**Files:**
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/infra/database/AnchorIntervalEntity.kt`
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/infra/database/AnchorIntervalStore.kt`
- Create: `modules/bond/src/test/kotlin/com/moyi/bond/service/DeferredTimezoneChangeTest.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/service/CreateBond.kt:41`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/service/ChangeTimezone.kt:96-119`

**Interfaces:**
- Consumes: `AnchorInterval`, `AnchorTimeline`, `Handoff` from Task 1.
- Produces: `AnchorIntervalStore.timelineOf(bondId: BondId): AnchorTimeline`, `AnchorIntervalStore.seed(bondId: BondId, zone: ZoneId, at: Instant)`, `AnchorIntervalStore.scheduleHandoff(bondId: BondId, handoff: Handoff, newZone: ZoneId, now: Instant)`.

- [ ] **Step 1: Write the failing integration test**

Create `modules/bond/src/test/kotlin/com/moyi/bond/service/DeferredTimezoneChangeTest.kt`. It drives the real `ChangeTimezone` against Testcontainers and asserts the one behaviour that distinguishes this design from what B5 does today — that after `confirm`, the bond *requests* the new zone but still *decides dates* by the old one:

```kotlin
package com.moyi.bond.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Fixture helpers (`bondForTwo`, `proposeAndConfirm`, `intervals`) follow the
// shape `modules/bond/src/test/kotlin/com/moyi/bond/web/BondTimezoneEndpointTest.kt`
// already uses; reuse them rather than inventing a second set.
internal class DeferredTimezoneChangeTest : /* the module's IntegrationTest base */ {
    @Test
    fun `confirming records the request immediately and defers the effect to the next day`() {
        val bond = bondForTwo(anchor = "Africa/Lagos", createdAt = Instant.parse("2026-09-01T00:00:00Z"))
        val confirmedAt = Instant.parse("2026-09-15T11:00:00Z") // midday, Lagos

        proposeAndConfirm(bond, to = "Pacific/Kiritimati", at = confirmedAt)

        // B5's behaviour is unchanged: the bond says what was agreed.
        bondRow(bond).anchorTimezone shouldBe "Pacific/Kiritimati"
        // BR-6: it does not decide dates yet.
        val timeline = intervals(bond)
        timeline.zoneAt(confirmedAt) shouldBe ZoneId.of("Africa/Lagos")
        timeline.zoneAt(Instant.parse("2026-09-15T23:00:00Z")) shouldBe ZoneId.of("Pacific/Kiritimati")
    }

    @Test
    fun `the deferred handoff closes the open interval rather than adding a second one`() {
        val bond = bondForTwo(anchor = "Africa/Lagos", createdAt = Instant.parse("2026-09-01T00:00:00Z"))

        proposeAndConfirm(bond, to = "Pacific/Kiritimati", at = Instant.parse("2026-09-15T11:00:00Z"))

        val rows = rawIntervals(bond)
        rows.size shouldBe 2
        rows.count { it.effectiveTo == null } shouldBe 1
        rows[0].effectiveTo shouldBe rows[1].effectiveFrom
    }

    @Test
    fun `a bond seeds one open interval when it is created`() {
        val bond = bondForTwo(anchor = "Europe/London", createdAt = Instant.parse("2026-09-01T00:00:00Z"))

        val rows = rawIntervals(bond)
        rows.size shouldBe 1
        rows.single().zone shouldBe "Europe/London"
        rows.single().effectiveFrom shouldBe Instant.parse("2026-09-01T00:00:00Z")
        rows.single().effectiveTo shouldBe null
    }

    @Test
    fun `a westward change never schedules a handoff onto a label the bond has used`() {
        val bond = bondForTwo(anchor = "Pacific/Kiritimati", createdAt = Instant.parse("2026-09-01T00:00:00Z"))
        writeDayLabel(bond, LocalDate.of(2026, 9, 15))
        writeDayLabel(bond, LocalDate.of(2026, 9, 16))

        proposeAndConfirm(bond, to = "Pacific/Honolulu", at = Instant.parse("2026-09-15T00:00:00Z"))

        val timeline = intervals(bond)
        // Honolulu's first label for this bond is the 17th: the 15th and 16th
        // are already used, so the handoff was pushed past them (R3).
        timeline.dateAt(Instant.parse("2026-09-16T10:00:00Z")) shouldBe LocalDate.of(2026, 9, 17)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:bond:test --tests "com.moyi.bond.service.DeferredTimezoneChangeTest"`
Expected: FAIL — `bond_anchor_intervals` has no rows for a newly created bond, so `AnchorTimeline`'s `init` throws "a bond always has at least the interval it was created in".

- [ ] **Step 3: Write the entity and store**

Create `AnchorIntervalEntity.kt` as a flat entity with a hand mapper, matching `BondEntity`'s shape exactly — `@Id` assigned in application code via `IdGenerator`, `Persistable` to avoid the wasted SELECT, no `data class` (ADR-0014's rule; a JPA entity is never a Kotlin data class).

Create `AnchorIntervalStore.kt`:

```kotlin
package com.moyi.bond.infra.database

import com.moyi.bond.domain.AnchorInterval
import com.moyi.bond.domain.AnchorTimeline
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.Handoff
import com.moyi.common.core.IdGenerator
import java.time.Instant
import java.time.ZoneId

/**
 * The bond's effective-zone history. **Every write here assumes the bond's
 * row lock is already held** — `BondStore.lockBond` — because the open
 * interval is read, closed and replaced as one decision, and two confirmations
 * racing would otherwise both close the same interval and leave two open ones.
 * The partial unique index `bond_anchor_intervals_open_key` is the backstop
 * that turns that race into a constraint violation rather than a silent
 * ambiguity; the lock is what keeps it from ever being reached.
 */
internal class AnchorIntervalStore(
    private val intervals: AnchorIntervalRepository,
    private val ids: IdGenerator,
) {
    fun timelineOf(bondId: BondId): AnchorTimeline =
        AnchorTimeline(
            intervals.findAllByBondIdOrderByEffectiveFromAsc(bondId.value).map {
                AnchorInterval(ZoneId.of(it.zone), it.effectiveFrom, it.effectiveTo)
            },
        )

    /** The first interval, written when the bond is created. */
    fun seed(bondId: BondId, zone: ZoneId, at: Instant) {
        intervals.save(
            AnchorIntervalEntity(
                id = ids.opaque(),
                bondId = bondId.value,
                zone = zone.id,
                effectiveFrom = at,
                effectiveTo = null,
                createdAt = at,
            ),
        )
    }

    /**
     * Close the open interval at the handoff and open the next one there.
     * Both writes, or neither: the caller's transaction is what makes that
     * true, and the caller holds the bond lock.
     */
    fun scheduleHandoff(bondId: BondId, handoff: Handoff, newZone: ZoneId, now: Instant) {
        val open =
            intervals.findByBondIdAndEffectiveToIsNull(bondId.value)
                ?: error("a bond always has one open anchor interval")
        open.effectiveTo = handoff.at
        intervals.save(open)
        intervals.save(
            AnchorIntervalEntity(
                id = ids.opaque(),
                bondId = bondId.value,
                zone = newZone.id,
                effectiveFrom = handoff.at,
                effectiveTo = null,
                createdAt = now,
            ),
        )
    }
}
```

- [ ] **Step 4: Seed on creation**

In `CreateBond.kt`, after the bond is persisted and inside the same transaction, call `anchorIntervals.seed(bond.id, bond.anchorTimezone.zoneId, now)`. The bond row lock is not needed here — nothing else can reference a bond that does not yet exist.

- [ ] **Step 5: Defer the effect in `confirm`**

In `ChangeTimezone.confirm`, replace the single `bonds.update(bond.withAnchorTimezone(...))` line with the request-plus-handoff pair. The bond lock is already taken on line 100, so both reads below are serialised:

```kotlin
        if (!proposals.confirm(proposal.id, membership.memberId, now)) throw ProposalNotFoundException()

        // B5's own behaviour, unchanged: the bond records what was agreed the
        // moment it is agreed, so `GET /bonds/{id}` reflects consent at once.
        bonds.update(bond.withAnchorTimezone(proposal.proposedZone(), now))

        // BR-6: what changes *dates* is deferred to the end of the current
        // logical day. Without this the couple's current day would be
        // recomputed under the new zone mid-day, which is exactly what
        // ADR-0030 forbids and what a copied string cannot prevent.
        val timeline = anchorIntervals.timelineOf(membership.bondId)
        val handoff =
            timeline.handoffFor(
                now = now,
                newZone = proposal.proposedZone().zoneId,
                usedLabels = days.usedLabelsOf(membership.bondId),
            )
        anchorIntervals.scheduleHandoff(membership.bondId, handoff, proposal.proposedZone().zoneId, now)
        log.info(
            "Bond {} moved its anchor zone by agreement; effective from {}, {} label(s) skipped",
            membership.bondId.value,
            handoff.at,
            handoff.skippedLabels.size,
        )
```

`days.usedLabelsOf` is a read of `bond_days` — which lives in `gratitude`, not `bond`. **Do not add a cross-module dependency for it.** Instead, `bond` owns a narrow read of the date labels it has issued: add `used_labels` as a query against `bond_anchor_intervals`' own sibling — no. The correct shape, and the one to build: `bond` records the labels it has issued by keeping `bond_anchor_intervals.first_label` on each row, and `usedLabelsOf` is derived as "every label from the first interval's first label through the current day's". Add a `first_label date NOT NULL` column to V13 and set it on `seed` and `scheduleHandoff`; the used set is then `(first interval's first_label) .. (dateAt(now))`, computed in `AnchorTimeline` with no cross-module read at all.

- [ ] **Step 6: Add `first_label` to V13 and derive the used set**

In `V13__bond_anchor_intervals.sql`, add after `zone`:

```sql
    -- The first calendar label this interval issues. With contiguity, the
    -- labels a bond has ever used are exactly the run from the earliest
    -- interval's first_label through today's — so "has this label been used"
    -- is answerable inside `bond`, with no read of `gratitude`'s bond_days
    -- and no dependency between the modules (doc 25 §6, ADR-0026).
    first_label    date        NOT NULL,
```

and in the backfill `SELECT`, `(b.created_at AT TIME ZONE b.anchor_timezone)::date` as `first_label`.

Add to `AnchorTimeline`:

```kotlin
    /**
     * Every calendar label this bond has issued up to [now]. Contiguity is
     * what makes this a simple run rather than a query: intervals never
     * overlap and never gap, so the labels are exactly the dates from the
     * first interval's own first label through the label in force at [now].
     */
    fun usedLabelsUpTo(now: Instant): Set<LocalDate> {
        val first = intervals.first().firstLabel
        val last = dateAt(now)
        return generateSequence(first) { it.plusDays(1) }
            .takeWhile { !it.isAfter(last) }
            .toSet()
    }
```

`AnchorInterval` gains `val firstLabel: LocalDate`, and `handoffFor` takes `usedLabels = usedLabelsUpTo(now)` by default.

- [ ] **Step 7: Run the tests**

Run: `./gradlew :modules:bond:test`
Expected: PASS, including B5's existing `BondTimezoneEndpointTest` and `ProposalRaceTest` — if either goes red, the deferral has changed an observable API response, which it must not: `GET /bonds/{id}` still reports the newly requested zone immediately.

- [ ] **Step 8: Commit**

```bash
git add modules/bond/src/main/kotlin/com/moyi/bond/infra/database/AnchorInterval*.kt \
        modules/bond/src/main/kotlin/com/moyi/bond/service/CreateBond.kt \
        modules/bond/src/main/kotlin/com/moyi/bond/service/ChangeTimezone.kt \
        modules/bond/src/main/resources/db/migration/V13__bond_anchor_intervals.sql \
        modules/bond/src/test/kotlin/com/moyi/bond/service/DeferredTimezoneChangeTest.kt
git commit -m "feat(bond): a confirmed anchor change takes effect at the next day boundary, not at once (BR-6, ADR-0030)"
```

---

## Task 3: `BondAccess` grows the lock, the timeline and the lifecycle instants

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/api/BondAccess.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/infra/BondAccessAdapter.kt`
- Create: `modules/bond/src/test/kotlin/com/moyi/bond/api/BondAccessLockingTest.kt`

**Interfaces:**
- Consumes: `AnchorIntervalStore.timelineOf` from Task 2.
- Produces: `BondAccess.lockMembershipOf(userId, bondId): BondMembership`; `BondMembership.activeSince: Instant?`, `.endedAt: Instant?`, `.anchorTimeline: BondAnchorTimeline`; `bond.api.BondAnchorTimeline` with `internal constructor` and the same four questions `AnchorTimeline` answers.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.moyi.bond.api

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.transaction.IllegalTransactionStateException
import java.time.Instant

internal class BondAccessLockingTest : /* the module's IntegrationTest base */ {
    @Test
    fun `lockMembershipOf refuses to run without a transaction`() {
        val bond = bondForTwo()

        // MANDATORY propagation: a caller that forgot the transaction gets a
        // loud failure rather than a lock that is released before the write it
        // was meant to protect. Spec §2.1.
        shouldThrow<IllegalTransactionStateException> {
            access.lockMembershipOf(creatorOf(bond), bond)
        }
    }

    @Test
    fun `activeSince is the second member's join, not the bond's creation`() {
        val created = Instant.parse("2026-09-01T00:00:00Z")
        val joined = Instant.parse("2026-09-04T09:00:00Z")
        val bond = bondForTwo(createdAt = created, secondMemberJoinedAt = joined)

        inTransaction { access.lockMembershipOf(creatorOf(bond), bond).activeSince shouldBe joined }
    }

    @Test
    fun `a bond still awaiting its partner has no activeSince`() {
        val bond = bondPendingMember()

        inTransaction { access.lockMembershipOf(creatorOf(bond), bond).activeSince shouldBe null }
    }

    @Test
    fun `the membership carries the effective timeline, not the requested zone`() {
        val bond = bondForTwo(anchor = "Africa/Lagos", createdAt = Instant.parse("2026-09-01T00:00:00Z"))
        proposeAndConfirm(bond, to = "Pacific/Kiritimati", at = Instant.parse("2026-09-15T11:00:00Z"))

        inTransaction {
            val membership = access.lockMembershipOf(creatorOf(bond), bond)
            membership.anchorTimezone shouldBe "Pacific/Kiritimati" // what was agreed
            membership.anchorTimeline.zoneIdAt(Instant.parse("2026-09-15T12:00:00Z")) shouldBe "Africa/Lagos"
        }
    }

    @Test
    fun `hasLeft survives on the port`() {
        // R2. Spec §2.1's field list omits it; SubmitEntry checks it, and the
        // second review of PR #41 is why. Deleting it reopens that defect.
        val bond = bondForTwo()
        leave(bond, creatorOf(bond))

        inTransaction { access.lockMembershipOf(creatorOf(bond), bond).hasLeft shouldBe true }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :modules:bond:test --tests "com.moyi.bond.api.BondAccessLockingTest"`
Expected: FAIL — `Unresolved reference: lockMembershipOf`.

- [ ] **Step 3: Extend the port**

In `BondAccess.kt`, add to the interface:

```kotlin
    /**
     * The caller's membership, read **under the bond's own row lock**, which
     * this method takes and the caller's transaction holds until commit.
     *
     * `MANDATORY` propagation, not `REQUIRED`: a caller without a transaction
     * would take a lock that is released the instant this method returns,
     * which looks identical to working and protects nothing. Failing loudly is
     * the only honest option (spec §2.1).
     *
     * **The lock order across this application is bond, then bond-day, then
     * entry**, on submission, editing, closing and lifecycle reconciliation.
     * `ChangeTimezone`, `EndBond`, `RequestDeletion`, `UpdateBond`,
     * `CreateInvite`, `RevokeInvite`, `AcceptInvite` and `MemberSettingsService`
     * already take this same lock first, which is what makes a gratitude write
     * serialise against leave, block, deletion and zone confirmation rather
     * than merely check `isOpen` and hope. Checking `isOpen` and then taking a
     * separate day lock would let a write commit after the bond ended.
     *
     * @throws com.moyi.common.web.NotFoundException as [membershipOf]
     * @throws org.springframework.transaction.IllegalTransactionStateException no transaction
     */
    fun lockMembershipOf(
        userId: UUID,
        bondId: UUID,
    ): BondMembership
```

Add to `BondMembership`, after `awaitingSecondMember`:

```kotlin
    /**
     * When the bond became `ACTIVE` — the **second** member's
     * `bond_members.joined_at`, not `bonds.created_at`. Null while the bond is
     * still `PENDING_MEMBER`.
     *
     * The distinction is load-bearing for C3, which generates missing day
     * labels from activation forward: starting at `created_at` instead would
     * manufacture `EMPTY` days across the whole waiting window, which is
     * precisely the harm doc 04 §8.3a's `SUSPENDED` resolution exists to
     * prevent. There is no activation column on `bonds`; this is derived.
     */
    val activeSince: Instant?,
    /** When the bond ended (`bonds.archived_at`), or null while it is live. */
    val endedAt: Instant?,
    /**
     * The zone that **decides dates** over time, which is not
     * [anchorTimezone] — that is the zone the bond currently *requests*. They
     * differ for up to one logical day after a change is confirmed (BR-6).
     */
    val anchorTimeline: BondAnchorTimeline,
```

Add the public DTO, in the same file, with the same `internal constructor` mechanism:

```kotlin
/**
 * `bond`'s effective-zone history, as much of it as another module may ask
 * about. A projection of `bond.domain.AnchorTimeline` rather than that type
 * itself: the domain type is `internal` to `bond`, and keeping it that way is
 * what stops `gratitude` from reaching `bond`'s private tables (spec §2.1).
 *
 * The constructor is `internal` for ADR-0026's reason, unchanged: a caller
 * outside `bond` can hold one, read it and pass it down, and cannot forge one.
 */
class BondAnchorTimeline internal constructor(
    private val delegate: Any,
    private val zoneAt: (Instant) -> String,
    private val dateAt: (Instant) -> LocalDate,
    private val boundsAt: (Instant) -> Pair<Instant, Instant>,
) {
    fun zoneIdAt(at: Instant): String = zoneAt(at)

    fun dateAt(at: Instant): LocalDate = dateAt.invoke(at)

    /** The UTC span `[startsAt, endsAt)` of the logical day containing [at]. */
    fun dayBoundsAt(at: Instant): Pair<Instant, Instant> = boundsAt(at)

    override fun toString(): String = "BondAnchorTimeline(intervals=${(delegate as? List<*>)?.size ?: 0})"
}
```

- [ ] **Step 4: Implement it in the adapter**

In `BondAccessAdapter.kt`, add `lockMembershipOf` with `@Transactional(propagation = Propagation.MANDATORY)`, taking `bonds.lockBond(BondId(bondId))` **before** any read, then delegating to the same projection `membershipOf` builds. Derive `activeSince` as the maximum `joined_at` across the bond's member rows **when the bond is not `PENDING_MEMBER`**, and null otherwise; `endedAt` from `archived_at`.

Keep `membershipOf` unlocked and unchanged — `GetToday` is a read and must not take a write lock.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :modules:bond:test :modules:gratitude:test`
Expected: PASS. `gratitude`'s tests still compile because `BondMembership` only gained fields; every construction site is `BondAccessAdapter`'s single named-argument call.

- [ ] **Step 6: Commit**

```bash
git add modules/bond/src/main/kotlin/com/moyi/bond/api/BondAccess.kt \
        modules/bond/src/main/kotlin/com/moyi/bond/infra/BondAccessAdapter.kt \
        modules/bond/src/test/kotlin/com/moyi/bond/api/BondAccessLockingTest.kt
git commit -m "feat(bond): the access port locks, and carries the timeline and the lifecycle instants (spec §2.1)"
```

---

## Task 4: The lock order in `SubmitEntry`, and days that carry their interval

**Files:**
- Modify: `modules/gratitude/src/main/resources/db/migration/V12__gratitude_bond_days_and_entries.sql`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/BondDay.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/DayAssignment.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/infra/database/BondDayStore.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SubmitEntry.kt`
- Modify: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/DayAssignmentTest.kt`

**Interfaces:**
- Consumes: `BondMembership.anchorTimeline` from Task 3.
- Produces: `BondDay.startsAt: Instant`, `.endsAt: Instant`; `DayAssignment.resolve(submittedAt, intendedAt, timeline, isSettled): Resolution` where `Resolution` gains `val bounds: DayBounds`.

- [ ] **Step 1: Add the columns to V12**

```sql
    -- The UTC span this day occupies, `[starts_at, ends_at)`, resolved
    -- against the bond's effective-zone timeline when the row was opened.
    -- A zone id alone cannot express a day that a mid-day anchor change
    -- clipped, nor a calendar label an eastward change skipped entirely —
    -- the latter is a day whose span is EMPTY (starts_at = ends_at), which
    -- is why the CHECK below is `>=` and not `>`.
    starts_at       timestamptz NOT NULL,
    ends_at         timestamptz NOT NULL,
```

and the constraint:

```sql
    CONSTRAINT bond_days_span_check CHECK (ends_at >= starts_at),
```

Correct V12's header at the same time: V10 is `bond_proposals` and is present; the F11 merge-sequencing warning is discharged by the merge at `ed0e9f2`; `anchor_timezone` is retained as the *snapshot* of the zone in force, with `starts_at`/`ends_at` as the authority on which instants belong to the day.

- [ ] **Step 2: Write the failing domain test**

Add to `DayAssignmentTest.kt`:

```kotlin
    @Test
    fun `a day resolved mid-handoff carries the interval it was resolved against, not the bond's new zone`() {
        val handoff = Instant.parse("2026-09-15T23:00:00Z")
        val timeline =
            timelineOf(
                AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), handoff, LocalDate.of(2026, 9, 1)),
                AnchorInterval(kiritimati, handoff, null, LocalDate.of(2026, 9, 16)),
            )

        val resolution = DayAssignment.resolve(
            submittedAt = Instant.parse("2026-09-15T12:00:00Z"),
            intendedAt = null,
            timeline = timeline,
            isSettled = { false },
        )

        resolution.date shouldBe LocalDate.of(2026, 9, 15)
        resolution.bounds.startsAt shouldBe Instant.parse("2026-09-14T23:00:00Z")
        resolution.bounds.endsAt shouldBe handoff
    }

    @Test
    fun `an offline draft from before a handoff resolves in the zone that was effective then`() {
        val handoff = Instant.parse("2026-09-15T23:00:00Z")
        val timeline =
            timelineOf(
                AnchorInterval(lagos, Instant.parse("2026-09-01T00:00:00Z"), handoff, LocalDate.of(2026, 9, 1)),
                AnchorInterval(kiritimati, handoff, null, LocalDate.of(2026, 9, 16)),
            )

        // Written on a flight at 22:00Z on the 15th — Lagos's 15th — and sent
        // three hours later, by which time Kiritimati decides dates. BR-3a's
        // window (36h) accepts the claim; the timeline is what keeps it on the
        // day it was actually written.
        val resolution = DayAssignment.resolve(
            submittedAt = Instant.parse("2026-09-16T01:00:00Z"),
            intendedAt = Instant.parse("2026-09-15T22:00:00Z"),
            timeline = timeline,
            isSettled = { false },
        )

        resolution.date shouldBe LocalDate.of(2026, 9, 15)
    }
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.domain.DayAssignmentTest"`
Expected: FAIL — `resolve` has no `timeline` parameter.

- [ ] **Step 4: Change `DayAssignment` to resolve against the timeline**

Replace the `zone: ZoneId` parameter with `timeline: AnchorTimeline` (the `gratitude`-side projection — `BondAnchorTimeline`), and replace `submittedAt.atZone(zone).toLocalDate()` with `timeline.dateAt(...)`, returning the bounds alongside:

```kotlin
    data class Resolution(
        val date: LocalDate,
        val resolvedAt: Instant,
        val bounds: DayBounds,
    )
```

Rename the `isClosed` lambda to `isSettled` and document it as "`closedAt != null`, including `FROZEN` and elapsed `SUSPENDED`" — spec §6.1.2's wording, which is what Task 8 makes true.

- [ ] **Step 5: Take the bond lock first in `SubmitEntry`**

Replace the opening of `submit`:

```kotlin
    fun submit(
        userId: UUID,
        bondId: UUID,
        draft: EntryDraft,
    ): EntryView {
        val text = EntryText.of(draft.text)
        val now = clock.instant()

        val view =
            try {
                transactions.execute {
                    // Bond, then bond-day, then entry — spec §2.1. The lock is
                    // taken INSIDE the transaction and before anything is read,
                    // so a leave, block, deletion or zone confirmation either
                    // completes before this write begins or waits behind it.
                    // Reading membership outside the transaction and checking
                    // `isOpen` would let this commit after the bond ended.
                    val membership = access.lockMembershipOf(userId, bondId)
                    if (membership.hasLeft || !membership.isOpen) throw BondArchivedException()
                    if (draft.imageMediaId != null || draft.voiceMediaId != null) throw MediaNotYetSupportedException()
                    // ... resolution, openOrGet, lockAndFind, insert, update
                }
```

`EntriesController` stops resolving membership itself and passes `userId`/`bondId` through; `GetToday` keeps using the unlocked `membershipOf`.

- [ ] **Step 6: Run the module's tests**

Run: `./gradlew :modules:gratitude:test`
Expected: PASS, except `SubmitEntryConcurrencyTest`, which Task 5 rewrites. If it is green here, read Task 5 before assuming that is good news.

- [ ] **Step 7: Commit**

```bash
git add modules/gratitude/src/main/resources/db/migration/V12__gratitude_bond_days_and_entries.sql \
        modules/gratitude/src/main/kotlin/com/moyi/gratitude/ \
        modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/DayAssignmentTest.kt
git commit -m "feat(gratitude): days carry the interval they were resolved against, under the bond lock (spec §3.1, §6.1)"
```

---

## Task 5: Prove the unique index, now that the lock hides the old race

**Files:**
- Rewrite: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/SubmitEntryConcurrencyTest.kt`

**Interfaces:**
- Consumes: everything from Task 4.
- Produces: nothing.

**Why this task exists.** Before Task 4, two members submitting at once genuinely raced to create the day row, and `INSERT … ON CONFLICT (bond_id, date) DO NOTHING` resolved it. After Task 4 both members queue on the bonds row, so that race cannot occur from the submit path and **the existing test passes whether or not the ON CONFLICT is there**. Under R1 the close job takes no bond lock, so the collision that remains — and the one worth testing — is a sweep inserting a missing day while a member is writing one.

- [ ] **Step 1: Rewrite the test to race a lock-free inserter against a submission**

```kotlin
package com.moyi.gratitude.web

/**
 * **What this test is for, and what it deliberately no longer claims.**
 *
 * It used to fire two members' first submissions at once and assert one day
 * row. That assertion is now true for the wrong reason: `SubmitEntry` takes
 * the bond's row lock before resolving a day (spec §2.1), so the two members
 * serialise and no insert ever conflicts. Deleting
 * `bond_days_bond_date_key` would not have turned it red. That is the third
 * time this project has nearly kept a mechanism nothing proved — after
 * `@Version` (ADR-0029) and `Membership.left` (#41) — and the fix is not a
 * better assertion but a race the lock does not cover.
 *
 * The close job is that race. It takes no bond lock (plan R1: it sweeps many
 * bonds per run and queueing per bond would serialise the whole sweep), so a
 * sweep filling in a missing date can collide with a member writing that same
 * date. The unique index is what makes that collision safe, and this test
 * drives exactly it.
 */
internal class SubmitEntryConcurrencyTest : /* the module's IntegrationTest base */ {
    @Test
    fun `a lock-free inserter racing a submission produces one day row, not two`() {
        val bond = activeBondForTwo(anchor = "Africa/Lagos")
        val date = LocalDate.of(2026, 9, 15)
        val barrier = CyclicBarrier(2)

        // The close job's shape: open the day directly, with no bond lock.
        val sweep = submit { barrier.await(); days.openOrGet(bond, date, boundsFor(bond, date), now, OPEN) }
        val writer = submit { barrier.await(); submitEntry(bond, member = ada, at = noonOn(date)) }

        sweep.get(); writer.get()

        rowCount("SELECT count(*) FROM bond_days WHERE bond_id = ? AND date = ?", bond, date) shouldBe 1
        rowCount("SELECT count(*) FROM entries WHERE bond_id = ?", bond) shouldBe 1
    }

    @Test
    fun `both members submitting at once still yields one day and two entries`() {
        // Kept, but it now tests the LOCK, not the index — which is what its
        // name and this comment must say, so nobody reads it as proof of the
        // constraint again.
        val bond = activeBondForTwo(anchor = "Africa/Lagos")
        val barrier = CyclicBarrier(2)

        val one = submit { barrier.await(); submitEntry(bond, member = ada) }
        val two = submit { barrier.await(); submitEntry(bond, member = eve) }
        one.get(); two.get()

        rowCount("SELECT count(*) FROM bond_days WHERE bond_id = ?", bond) shouldBe 1
        rowCount("SELECT count(*) FROM entries WHERE bond_id = ?", bond) shouldBe 2
    }
}
```

- [ ] **Step 2: Run it and confirm it passes**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.web.SubmitEntryConcurrencyTest"`
Expected: PASS, 2 tests.

- [ ] **Step 3: Prove the first test is load-bearing — the mandatory mutation**

Drop the index in a scratch run and confirm the first test, and only the first, fails:

```bash
# In V12, temporarily comment out:
#   CREATE UNIQUE INDEX bond_days_bond_date_key ON bond_days (bond_id, date);
./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.web.SubmitEntryConcurrencyTest"
```

Expected: `a lock-free inserter racing a submission` **FAILS** (two rows). `both members submitting at once` still passes — which is the point: it never tested the index. **Restore the index before committing.** If the first test also passes with the index gone, the race is not being driven concurrently and the test is worthless; fix the barrier before moving on.

- [ ] **Step 4: Commit**

```bash
git add modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/SubmitEntryConcurrencyTest.kt
git commit -m "test(gratitude): race the index against a lock-free inserter, not against the lock (plan R1)"
```

---

## Task 6: `idempotency_keys` stores result identity, not the couple's words

**Files:**
- Modify: `common/web/src/main/resources/db/migration/V11__common_idempotency_keys.sql`
- Modify: `common/web/src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyRecord.kt`
- Modify: `common/web/src/test/kotlin/com/moyi/common/web/idempotency/IdempotencyKeyStoreTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `IdempotencyRecord(id, userId, method, path, idempotencyKey, requestHash, responseStatus, resultId, resultKind, responseEtag, responseLocation, createdAt, expiresAt)`; `IdempotencyKeyStore.lockFor(userId, key): Boolean` (nonblocking advisory).

- [ ] **Step 1: Rewrite V11's shape**

Replace `endpoint text NOT NULL` with the pair the spec requires, and `response_body` with result identity:

```sql
    -- The request's own method and **concrete** path, e.g.
    -- `POST` and `/api/v1/bonds/3f2a.../entries`. A route template is not
    -- sufficient: two bonds are two different targets, and a key replayed
    -- against a different bond must be refused, not answered from the first
    -- bond's result (spec §5.4).
    method           text        NOT NULL,
    path             text        NOT NULL,
```

```sql
    -- What the first attempt produced, by identity — never a second copy of
    -- what it produced. The previous shape stored the whole response body,
    -- which for `POST /entries` meant a plaintext duplicate of the couple's
    -- words sitting here for 24 hours, and would have survived Phase 5's
    -- encryption of `entries.text` untouched. Storing the id instead means a
    -- replay re-reads the resource through the same authorisation and
    -- deletion checks the original read went through, so an entry erased
    -- between the two replays as a tombstone rather than as its old text
    -- (spec §5.4).
    result_id        uuid,
    -- Which resource `result_id` names, so a replay knows what to re-read.
    result_kind      text,
```

with `CONSTRAINT idempotency_keys_result_kind_check CHECK (result_kind IS NULL OR result_kind IN ('ENTRY'))` and the unique constraint unchanged at `(user_id, idempotency_key)` — which Ruling A already chose and the revised spec confirms.

Delete Ruling A's paragraph about the `endpoint` column and rewrite it for `method`/`path`; delete the `response_body` paragraph entirely, including the Phase 5 warning, and replace it with one sentence recording that the hazard is retired by this shape. Correct the stale "V10 is reserved elsewhere" sentence.

- [ ] **Step 2: Write the failing store test**

Add to `IdempotencyKeyStoreTest.kt`:

```kotlin
    @Test
    fun `the same key against a different concrete path is a reuse, not a replay`() {
        val key = "one-key"
        val first = store.reserve(recordFor(user, key, path = "/api/v1/bonds/$bondA/entries"))
        first shouldBe null // reserved

        val second = store.reserve(recordFor(user, key, path = "/api/v1/bonds/$bondB/entries"))

        // The row comes back so the caller can compare and refuse: two bonds
        // are two targets, and a template would have made them one.
        second.shouldNotBeNull()
        second.path shouldBe "/api/v1/bonds/$bondA/entries"
    }

    @Test
    fun `the advisory lock is nonblocking and one holder at a time`() {
        inTransaction {
            store.lockFor(user, "k") shouldBe true
            // A second transaction must be refused rather than queued: the
            // caller turns that into 409 IDEMPOTENCY_KEY_IN_FLIGHT, which is
            // a fact about timing the client can act on, rather than a request
            // thread held open for the duration of somebody else's write.
            inNewTransaction { store.lockFor(user, "k") shouldBe false }
        }
    }

    @Test
    fun `no column holds request or response text`() {
        val columns = columnsOf("idempotency_keys")
        columns shouldNotContain "response_body"
        columns shouldContainAll listOf("result_id", "result_kind", "method", "path", "request_hash")
    }
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `./gradlew :common:web:test --tests "com.moyi.common.web.idempotency.IdempotencyKeyStoreTest"`
Expected: FAIL — `Unresolved reference: lockFor`, and `response_body` still present.

- [ ] **Step 4: Update the record and the store**

`IdempotencyRecord` drops `responseBody`, splits `endpoint` into `method` and `path`, gains `resultId: UUID?` and `resultKind: String?`. `CapturedResponse` drops `body` and gains `resultId`/`resultKind`.

Add to `IdempotencyKeyStore`:

```kotlin
    /**
     * A transaction-scoped, **nonblocking** advisory lock for this
     * `(user_id, key)`. `pg_try_advisory_xact_lock` and not the blocking
     * variant: a second request under a key whose first attempt is still
     * running is `409 IDEMPOTENCY_KEY_IN_FLIGHT`, a fact about timing the
     * client can act on, rather than a request thread parked for the duration
     * of somebody else's transaction.
     *
     * Two-integer form, keyed the same way `identity`'s session lock is
     * (`pg_advisory_xact_lock(1, hashtext(user_id))`, ADR-0021 §1a) so the two
     * namespaces cannot collide: `2` is this lock's namespace.
     */
    fun lockFor(userId: UUID, key: String): Boolean =
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(2, hashtext(?))",
            Boolean::class.java,
            "$userId:$key",
        ) == true
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :common:web:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add common/web/src/main/resources/db/migration/V11__common_idempotency_keys.sql \
        common/web/src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyRecord.kt \
        common/web/src/test/kotlin/com/moyi/common/web/idempotency/IdempotencyKeyStoreTest.kt
git commit -m "feat(web): an idempotency key stores result identity, not a second copy of the words (spec §5.4)"
```

---

## Task 7: Reserve, mutate and complete in one transaction

**Files:**
- Create: `common/web/src/main/kotlin/com/moyi/common/web/idempotency/IdempotentExecution.kt`
- Modify: `common/web/src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyInterceptor.kt`
- Modify: `common/web/src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyConfiguration.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SubmitEntry.kt`
- Create: `common/web/src/test/kotlin/com/moyi/common/web/idempotency/IdempotentExecutionTest.kt`

**Interfaces:**
- Consumes: `IdempotencyKeyStore.lockFor`, the new `IdempotencyRecord` from Task 6.
- Produces: `IdempotentExecution.once(userId, key, method, path, requestHash, kind, block: () -> Outcome<T>): Result<T>`.

**Why this replaces the interceptor's reserve step.** Today `preHandle` inserts the row in its own transaction, the handler then opens a second one, and a crash between them leaves a committed reservation with no entry — permanently `409 IDEMPOTENCY_KEY_IN_FLIGHT` for a request that never happened. The spec requires all three — lock, reserve, mutate, complete — in one transaction so a crash rolls back all of it.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `a crash after the mutation rolls back the key as well as the entry`() {
        val key = "crash-key"
        shouldThrow<IllegalStateException> {
            execution.once(user, key, "POST", path, hash, kind = "ENTRY") {
                insertAnEntry()
                error("boom, after the domain write and before commit")
            }
        }

        // Neither survives: one transaction, one outcome.
        rowCount("SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?", key) shouldBe 0
        rowCount("SELECT count(*) FROM entries") shouldBe 0
    }

    @Test
    fun `a concurrent second request under the same key is refused, not queued`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = submit {
            execution.once(user, "k", "POST", path, hash, "ENTRY") {
                started.countDown(); release.await(); insertAnEntry()
            }
        }
        started.await()

        shouldThrow<IdempotencyKeyInFlightException> {
            execution.once(user, "k", "POST", path, hash, "ENTRY") { insertAnEntry() }
        }

        release.countDown(); slow.get()
        rowCount("SELECT count(*) FROM entries") shouldBe 1
    }

    @Test
    fun `a replay after the result was erased renders a tombstone, never the old text`() {
        val key = "replay-key"
        val created = execution.once(user, key, "POST", path, hash, "ENTRY") { insertAnEntry() }
        deleteEntry(created.resultId)

        val replayed = execution.once(user, key, "POST", path, hash, "ENTRY") { error("must not re-run") }

        replayed.wasReplayed shouldBe true
        replayed.render().status shouldBe "DELETED"
        replayed.render().text shouldBe null
    }
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./gradlew :common:web:test --tests "com.moyi.common.web.idempotency.IdempotentExecutionTest"`
Expected: FAIL — `Unresolved reference: IdempotentExecution`.

- [ ] **Step 3: Write `IdempotentExecution`**

A plain class (not `@Component` — `common:web` is on every module's classpath and `IdempotencyConfiguration`'s KDoc explains why nothing here is scanned), wired by an explicit `@Bean`. `once` runs inside the caller's `TransactionTemplate`:

1. `store.lockFor(userId, key)` — false ⇒ throw `IdempotencyKeyInFlightException` (409).
2. `store.reserve(record)` — a returned row means the key is known: compare `method`, `path` and `requestHash`; any mismatch ⇒ `IdempotencyKeyReusedException` (422); a match ⇒ return `Replayed(resultId, resultKind, status, etag, location)`.
3. Otherwise run `block()`, then `store.complete(recordId, status, resultId, resultKind, etag, location)` — in the same transaction.

The caller re-reads the resource by `resultId` through its ordinary authorisation path, which is what makes erasure win over replay.

- [ ] **Step 4: Move `SubmitEntry` inside it**

`SubmitEntry.submit` wraps its existing transaction body in `execution.once(...)`, passing `request.method`, the concrete `requestURI`, the body hash the controller computed, and `kind = "ENTRY"`. The interceptor keeps the header contract — reading `Idempotency-Key`, refusing a missing one with 422, setting `Idempotency-Replayed: true` on a replay — and no longer touches the store.

- [ ] **Step 5: Run everything**

Run: `./gradlew build`
Expected: PASS. `EntriesEndpointTest`'s idempotency cases should be unchanged from the client's point of view — same statuses, same headers — which is the check that this was a refactor of mechanism and not of contract.

- [ ] **Step 6: Commit**

```bash
git add common/web/src/main/kotlin/com/moyi/common/web/idempotency/ \
        common/web/src/test/kotlin/com/moyi/common/web/idempotency/IdempotentExecutionTest.kt \
        modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SubmitEntry.kt
git commit -m "feat(web): reserve, mutate and complete in one transaction under an advisory lock (spec §5.4)"
```

---

## Task 8: BR-1 keys on `revealedAt`, and BR-3a rechecks a settled day

**Files:**
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Entry.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GetToday.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SubmitEntry.kt`
- Modify: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/RevealGateTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: `Entry.canBeReadBy(memberId: UUID): Readability` where `Readability ∈ {FULL, TOMBSTONE, LOCKED}`.

- [ ] **Step 1: Write the failing reveal-gate cases**

Add to `RevealGateTest.kt` — the file doc 12 calls the most important in the repository:

```kotlin
    @Test
    fun `a revealed entry stays readable after its day is frozen`() {
        // revealedAt is monotonic: applying a freeze to a solo day must not
        // hide words the partner has already seen (spec §4). Keying on day
        // status instead of the entry's own timestamp is what got this wrong.
        val day = solo(revealed = true)
        freeze(day)

        readAs(partner, day).partnerEntry.shouldBeFullyReadable()
    }

    @Test
    fun `a revealed entry stays readable after the bond is suspended`() {
        val day = revealed()
        suspend(day.bond)

        readAs(partner, day).partnerEntry.shouldBeFullyReadable()
    }

    @Test
    fun `a frozen day does not by itself reveal an entry`() {
        val day = partial(revealed = false)
        freeze(day)

        readAs(partner, day).partnerEntry.shouldBeLocked()
    }

    @Test
    fun `a withdrawn entry is a tombstone for everyone, its author included`() {
        val day = revealed()
        withdraw(day.entryBy(ada))

        readAs(ada, day).ownEntry.shouldBeTombstone()
        readAs(eve, day).partnerEntry.shouldBeTombstone()
    }

    @Test
    fun `a non-member reads nothing, regardless of reveal state`() {
        // Membership is checked FIRST: a revealed entry is not public.
        val day = revealed()

        shouldThrow<NotFoundException> { readAs(stranger, day) }
    }
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.web.RevealGateTest"`
Expected: FAIL on the freeze and suspension cases — `canBeReadBy` currently keys on `day.status`.

- [ ] **Step 3: Rewrite the gate**

```kotlin
    /**
     * BR-1, in the order the spec states it (§4, as revised):
     *
     * 1. **Membership first.** A revealed entry is not public; a non-member
     *    gets a `NotFoundException` before any content question is asked.
     * 2. **Erasure beats everything.** A deleted or withdrawn entry is a
     *    tombstone for both people, its author included — there is one copy of
     *    the text and BR-10a makes the erasure total.
     * 3. **Otherwise: your own words, or a revealed entry.** Keyed on
     *    `revealedAt != null`, **not** on the day's status. The distinction is
     *    the whole fix: a solo day that reveals and is then frozen still has
     *    `revealedAt` set, and words the partner has already read must not
     *    become unreadable because a later transition rewrote the day. That
     *    timestamp is monotonic — nothing in this phase clears it.
     */
    fun canBeReadBy(memberId: UUID): Readability =
        when {
            deletedAt != null -> Readability.TOMBSTONE
            authorMemberId == memberId -> Readability.FULL
            revealedAt != null -> Readability.FULL
            else -> Readability.LOCKED
        }
```

- [ ] **Step 4: Recheck the settled day under the day lock in `SubmitEntry`**

After `days.lockAndFind(opened.id)`:

```kotlin
                    // BR-3a, rechecked under the day's own lock. The first
                    // check ran against a status read before the lock was
                    // held, so a close could have landed in between — and the
                    // close job takes no bond lock (plan R1), which is exactly
                    // what makes that window real rather than theoretical.
                    if (day.isSettled) {
                        if (resolution.usedIntendedAt) {
                            // Redirect once to the submission-time day rather
                            // than refusing: BR-3a's answer to "what happens to
                            // the words" is that they are kept, on a day that
                            // can still hold them.
                            return@execute submitOnto(timeline.dayBoundsAt(now), membership, text, now)
                        }
                        throw DayClosedException()
                    }
```

`submitOnto` is the extracted tail of the existing body; it redirects **once** — it does not recurse.

- [ ] **Step 5: Run and commit**

Run: `./gradlew :modules:gratitude:test`
Expected: PASS.

```bash
git add modules/gratitude/src/main/kotlin/com/moyi/gratitude/ \
        modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/RevealGateTest.kt
git commit -m "feat(gratitude): BR-1 keys on the entry's own reveal, and BR-3a rechecks under the lock (spec §4, §6.1)"
```

---

## Task 9: The timezone matrix, end to end

**Files:**
- Create: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/TimezoneMatrixTest.kt`

Spec §9 names this and calls it not a suggestion. One test per row, each driving the real endpoints:

- [ ] **Step 1: Write all six cases** — a DST spring-forward day (23 h), a fall-back day (25 h), `Asia/Kathmandu` (+5:45), `Pacific/Chatham` (+12:45), two members ≥12 h apart writing into the same bond-day, and a bond crossing the date line in **both** directions. The eastward case asserts a `FROZEN` day exists for the skipped label with `starts_at = ends_at`; the westward case asserts no label is opened twice and the current day's `ends_at` was extended.

- [ ] **Step 2: Run** — `./gradlew :modules:gratitude:test --tests "com.moyi.gratitude.web.TimezoneMatrixTest"` — and **Step 3: Commit.**

---

## Task 10: The contract, the ADR, the log and the smoke

**Files:**
- Modify: `adr/0031-the-bond-day-and-the-first-entry.md` and its corpus copy on the Gratitude repo
- Modify: `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` (§7's version table)
- Modify: `contracts/openapi.json` (regenerated), `docs/learning-log.md`, `scripts/smoke.sh`

- [ ] **Step 1: Amend ADR-0031 §3.** It currently *defends* copying the zone string at lazy row creation. Rewrite it to record the timeline, why a string cannot defer a change on a day with no row, and R1/R2/R3 as decisions with their reasoning.
- [ ] **Step 2: Correct spec §7's version table** — C1 owns V11–V13; C2 `V14`, C3 `V15`, C4 `V16`, C5 `V17`, C6 `V18`. Add a line to §12.5 recording that §7 was stale.
- [ ] **Step 3: Regenerate the contract.** `./gradlew :app:test --tests "com.moyi.app.OpenApiContractTest"` fails with "contracts/openapi.json is stale"; then `cp app/build/openapi/openapi.json contracts/openapi.json` and **read the diff** — it is the API contract changing.
- [ ] **Step 4: Write the learning-log entry** (doc 16 §5), honest about what was wrong: a mechanism that stopped being load-bearing the moment a lock was added, and a spec field list that would have deleted a check a review had just added.
- [ ] **Step 5: Extend `scripts/smoke.sh`** with a deferred-handoff section: confirm a zone change, assert `GET /bonds/{id}` reports the new zone at once, and assert an entry written immediately afterwards still lands on the old zone's date.
- [ ] **Step 6: Run the full build and the smoke suite.**

```bash
./gradlew build
# Needs the human-run dev-database repair first. A checksum reset is NOT the
# repair (an earlier version of this plan said it was): V11 and V12 changed
# table shape in place. The procedure is in ADR-0031, under Owed.
./scripts/smoke.sh
```

- [ ] **Step 7: Commit, push, and mark PR #46 ready** with the `breaking-api-change` label and the five `ErrorCode` values.

---

## Self-review

**Spec coverage.** §2.1 → Tasks 3, 4. §3.1 → Tasks 1, 2, 4. §5.4 → Tasks 6, 7. §6.1 → Tasks 4, 8. §6.2 → Tasks 4, 5. §4 (the reveal gate) → Task 8. §9's named tests → Tasks 5, 8, 9. §12.5 → Tasks 1, 10. **Not covered, deliberately:** §6.3 the reveal, §6.4 the close, §6.5 streaks, §6.6 archive/search, §6.7 withdrawal, §8 the outbox — all C2 and later. Task 8 builds the *read* side of BR-1 that C2's reveal will set `revealedAt` for; it does not build the reveal.

**Known gap to resolve during Task 2.** Step 5's first draft reached into `gratitude`'s `bond_days` for used labels, which would have crossed a module boundary. Step 6 corrects it with `first_label` on the interval row. An implementer starting at Step 5 must read Step 6 before writing code.

**Types.** `AnchorInterval` gains `firstLabel` in Task 2 Step 6 — every construction in Task 1's test needs the fourth argument once that step lands; Task 4's test already passes it.
