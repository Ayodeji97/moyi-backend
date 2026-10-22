# ADR-0040 — What an ended bond reveals

**Status:** Accepted · **Date:** 2026-10-22 · **Deciders:** Daniel, for the two rulings of
2026-10-21 (ADR-0033 question 1, ADR-0035 question 9) and the one of 2026-10-22 (what a
withheld day counts as). The two decisions under "The controller's" were made by the
controller of the session that built this; every other decision here was made by the plan or
by the agents that ran it.

## Context

Two rulings the owner gave on 2026-10-21, recorded as "to be built" in #67 and built here,
in one pull request, because both ask the same thing of the close job: *when has a bond
stopped being two people's, and what may it still reveal?*

- **ADR-0033 question 1.** A lone entry is not unlocked to a partner who did not write when
  the bond had ended before the day did. "Ended" covered a deletion that is only counting
  down, and a countdown can be called off. *Ruled: only an archived bond counts as ended for
  the lone-entry rule; a deletion that is counting down does not.*
- **ADR-0035 question 9.** The close job revealed a day both had written on even after the
  bond had ended. *Ruled: withheld. An ended bond reveals nothing new.* The ruling did not
  say what a withheld day is, to the streak and to the milestones. The owner answered that
  on 2026-10-22 (below).

The code before this record: `BondClosingView.endedAt` (archive, or the start of a deletion
countdown) was the only "ended" the closer could ask; `CloseDay` asked it of the `SOLO`
branch only; and `BondDay.close` turned every `PENDING_REVEAL` day into `REVEALED`.

## Decision

### The owner's rulings

- **ADR-0033 question 1, 2026-10-21.** The lone entry is withheld when the bond has *ended*
  before the day did, and a deletion that is counting down is not an ending. The ruling
  covers the lone-entry rule and nothing else: reads gated on the bond taking no writes
  (`prompt` null on `GET /today` while a deletion counts down, ADR-0038 ruling 6) are
  unchanged.
- **ADR-0035 question 9, 2026-10-21.** A day both wrote on is withheld when the bond ended
  before its reveal. It agrees with ADR-0033 decision 9 and doc 09 T-20 ("revokes access to
  *new* content").
- **What a withheld day is, 2026-10-22.** The day closes `PENDING_REVEAL`, with `closedAt`.
  No reveal is stamped on it or on its entries, no `DayRevealed` is written, and one
  `DayClosed` is. It counts **`COMPLETE`** for the streak and for the milestones
  (`DayOutcome.of(PENDING_REVEAL) = COMPLETE`). Both wrote; the day was theirs.

### The controller's decisions

1. **"Ended" is "separated", for both rulings (q1 and q9), and separated is `separatedAt`.**
   A countdown can be called off, so a day whose reveal fell due inside one is revealed.
   `BondClosingView.separatedAt` is the earliest of `bond.archivedAt` and any member's
   `leftAt`, and `null` while neither is set. `BondMembership.separatedAt` is the same value,
   computed by one file-level function in the adapter (`separatedAtOf`), so the closer and
   the read path cannot disagree. `endedAt` is unchanged and still includes a countdown.
   - *The brief said `archivedAt`.* That is what the owner's sentence says, and it is not
     enough. A leave or a block during a `PENDING_DELETION` countdown stamps only `leftAt`;
     the bond is archived only when the countdown ends or, if it is called off, at the
     cancel time, which can be after the day's end. With `archivedAt` alone, a couple one of
     whom had left would have the lone entry unlocked, or the pair revealed, because the
     bond was archived late by a cancellation. The ruling was narrowed *because a
     called-off countdown leaves the couple together*; once one of them has left they are not
     together, whatever the countdown does next.
   - *What it costs if wrong.* If the owner meant "archived and nothing else", a member who
     leaves during a countdown, and a countdown that is then called off, gets the lone
     entry revealed to the partner who had left. That is the larger exposure, and is why the
     default is the narrower reading of the owner's narrowing. It is Question 4 below.
2. **The reveal is judged by when it fell due, not by when the job ran.** `BondDay.isWithheldBy`
   asks `separatedAt` against `revealFallsDueAt`: the reveal time on the day's own calendar,
   capped at the day's end, or the day's end where there is no reveal time. A separation at
   the very instant the reveal falls due is too late to withhold it. The close job runs up to
   a quarter of an hour late; a late run must not decide who reads what. A reveal that fell
   due before the bond separated is made, however late the job comes to it.

### What the plan decided

- **One predicate, one place.** `BondDay.isWithheldBy(separatedAt, revealTime)`, with
  `revealFallsDueAt` and `withholdReveal`, in the domain. `CloseDay` asks it once, of the day
  as the reveal rule would meet it (after the span is extended and the joining day resumed),
  and uses the answer at both of its reveal sites: the sweep and the end-of-day reveal.
- **`ReconcileJoiningDay` asks it too.** The read-path reconcile of a legacy joining day
  (a `SUSPENDED` two-entry day resumed by the first read that meets it) revealed the day on
  any archive read, even after the bond had separated, before the closer could withhold it.
  It now withholds, using `BondMembership.separatedAt`. **The plan had scoped q9 to
  `CloseDay` only;** the implementer found the gap by reading, and the task review confirmed
  it (How this was checked).
- **A withheld day closes without a reveal.** `BondDay.close(now, withheld)`: a withheld
  `PENDING_REVEAL` day gains `closedAt` and nothing else. The streak walk counts a day closed
  when `closed_at` is set, so `DayOutcome.of` was the only thing that had to change.
- **Before its end a withheld day stays `PENDING_REVEAL` and unclosed.** The sweep picks up
  every `PENDING_REVEAL` day on every run until its end and asks again; the answer is stable,
  because `separatedAt` can only move earlier.
- **The guard is one property.** `awaitsReadingTogether` (two entries, `revealedAt` null,
  `PARTIAL` or `PENDING_REVEAL`) replaces three copies of the same condition in `BondDay`.

## Consequences

- **No migration (V26 stays free), no contract change.** No endpoint, schema or error code
  moved. `separatedAt` is read from rows the closer already reads.
- **A withheld day is still a complete day.** It can be a milestone's `achievedOn` and write
  `MilestoneReached` (ids only: `{bondId, bondDayId}`). So the couple can see "7 reached on
  <date>" for a day whose words were never revealed to each other. The owner's ruling of
  2026-10-22 named milestones. This reverses what ADR-0041's note under its rulings said
  (amended there): that such a day "will then not be `REVEALED`, so not complete".
- **`GET /today`'s `todayComplete` reads `status == REVEALED`** (`GetToday`), so a day
  withheld but not yet closed is not "complete today" on that route. The closed day is
  `COMPLETE` in the walk. `GET /today` on an archived bond still answers: on the afternoon
  of a withheld day the stayer's streak and milestones show N, and after the midnight close
  they show N+1, a preserved streak ticking up after the partner left. Harmless, and the
  same as any pending day; recorded because the two readings of "complete" now differ for
  one status.
- **A closed `PENDING_REVEAL` is the third settled-but-not-`isClosed` status**, with an elapsed
  `SUSPENDED` day and a called-off deletion's `SUSPENDED` day (`BondDayStatus`'s KDoc). Nothing
  that asks `isClosed` may assume a closed day is not `PENDING_REVEAL`; the close job's
  candidate scan keeps picking such a day up until its end only.
- **A partner's entry is readable or not by the entry's own `revealedAt`**, never by the
  day's status (`Entry.canBeReadBy`). So a withheld day's partner entry is `LOCKED` with no
  further mechanism: the day view shows `{authorMemberId, status: LOCKED}` and the search
  does not find it. Run, not read (DayViewTest, How this was checked).
- **The due instant is read from the bond's current reveal time**, so a move made while the
  bond is live shifts it, earlier or later, and a clear makes it the day's end. Once the bond
  has separated the time cannot change: `UpdateBond.patch` refuses a bond that is not open
  (`Bond.isOpen` is `PENDING_MEMBER` or `ACTIVE`; `BondArchivedException`, 409), which covers
  `ARCHIVED` and `PENDING_DELETION`. A leave on an open bond archives it (`Bond.end`), and a
  leave during a countdown leaves the status `PENDING_DELETION`; either way the time is
  frozen from then on. So the time read at the close is the one in force at the separation,
  which is what the rule needs. Read in the code, not run: no test moves the time and then
  separates. A countdown called off reopens the bond only when nobody left in it
  (`Bond.cancelDeletion` restores `ACTIVE`, or `PENDING_MEMBER`); when somebody has, it
  archives, and `separatedAt` already fixes the separation at the leave.
- **`DayOutcome.of(PENDING_REVEAL)` is `COMPLETE`.** `OPEN` and `PARTIAL` are still null.
- **The lone-entry rule changes for a countdown.** A lone entry on a day that ends inside a
  deletion countdown is revealed (the couple may be together after the countdown is called
  off); one on a bond where a member left in it is withheld, and stays so after a cancel.
- **The state map**: `day.json` gained six rows (the withheld reveal-time and end transitions,
  for the day, your entry and your partner's) and re-cites the rest; four reasons that said
  "ended bond or not (... no test pins it)" are rewritten. 663 rows, 0 wrong.

## Owed

- **The corpus** (`Gratitude/documents/`, not edited here): ADR-0033 and ADR-0035 amendments
  carried across with ADR-0040, and the sentences listed under "What the corpus now says that
  is false".
- **Phase 4's `MilestoneReached` consumer** must not notify for a withheld day's milestone on a
  bond that takes no writes (already ADR-0041's Owed), and may celebrate a date for a day
  whose words were never shared; see Question 5.
- **The KMP consumer.** The API has no field telling a day withheld for good from one pending
  until its time. A client must read `PENDING_REVEAL` on a bond that is not open as
  withheld, or it counts down to nothing, on `/today` and on past days.

## Questions that are the owner's

The three rulings are his. Then, one line each, with the alternative and what it costs:

1. *A moved or cleared reveal time is read as it stands, and a clear is taken as due at the
   end of the day.* Either member of an open bond may change the reveal time (`PATCH
   /bonds/{bondId}`, `PatchBondRequest.revealTimeLocal`; the route checks membership and that
   the bond is open, and no role), and nothing remembers what it was. A day pending on a time
   that was moved is judged against the new time; one pending on a time that was cleared is
   withheld only if the bond separates before the day's end. Alternative: record each change
   and judge a day by the time in force when the second entry landed. Costs a stored history
   the schema does not hold. See the consequence on this under Consequences. One edge: a
   legacy C1 joining day with no reveal time is taken as due at its end, so a leave that
   afternoon withholds a day that would have revealed at the second entry but for C1's
   suspension. Pre-deploy rows only; conservative.
2. *The leaver's archive.* After a leave the leaver's own archive shows the withheld day
   exactly as the stayer's does: `PENDING_REVEAL`, the caller's entry in full, the partner's
   as a lock. Alternative: show the leaver nothing of a day on the bond they left. Costs
   the leaver's own history (doc 02:127).
3. *`GET /today` of an archived bond is unaffected.* It still answers as ADR-0038 ruling 6
   and ADR-0041 set it. Nothing here changes it; recorded so that the answer is a decision.
4. *A leave or block during a countdown counts as a separation.* Should it instead count only
   once the bond is archived? Alternative: `archivedAt` alone, as the brief read the ruling.
   Costs: if a countdown is called off after a leave, the lone entry is unlocked (and the
   pair revealed) to the member who left. The controller chose the narrower reading; it is
   the owner's to turn.
5. *A withheld day counts, and a milestone can be dated on it.* That is the owner's own
   2026-10-22 ruling; the line is here because the effect is visible: "7 reached on <date>"
   for a day whose words were never shared. Alternative: a withheld day counts toward the
   streak and not toward milestones. Costs a second meaning of `COMPLETE`.

## What the corpus now says that is false

For the owner to carry into the corpus repository, which is not edited here.

1. **ADR-0035 question 9**, "no test pins it either way": `CloseElapsedDaysTest` pinned the
   reveal. Amended in place.
2. **Spec §6.4** ("`PENDING_REVEAL → REVEALED`", "the bond stopped taking writes before the
   day ended") and §6.1's reveal table: amended in place, as above.
3. **ADR-0041**, the note under its rulings ("not complete: no milestone counts it"): amended
   in place.
4. **`docs/state-map/day.md`**, four rows said an ended bond's waiting day is revealed all the
   same, and that no test pins it: rewritten.
5. **Doc 02:144 / doc 09 T-20** are now built as written: leaving "revokes access to *new*
   content"; the corpus needs no change for this, and the ADR-0035 text that suggested it
   might was wrong.

## Revisit when

- The owner turns Question 4 (`archivedAt` alone): `separatedAt` becomes `archivedAt`, and the
  tests of the countdown-leave cases flip.
- The streak or the day view acquires a reader that treats `isClosed` as "settled".

## How this was checked

"Run" means executed and seen by the party named. Nothing here was run by the owner.

- **Task 1** (2026-10-22, its implementer; the lone-entry rule). Four `CloseDayTest` cases
  (a countdown alone is revealed; separated after the day's end is revealed; a leave during a
  countdown is withheld; a leave during a countdown then a cancel after the day's end is
  withheld) and a `BondAccessTest` assertion on the four bond shapes through both reads.
  Mutations: `view.endedAt` for `separatedAt` fails the countdown test; `bond.archivedAt`
  alone fails the countdown-leave test and `BondAccessTest`. A first cancel test cancelled
  before the day's end and did not discriminate; it was moved. No separate red run before the
  implementation: the mutation runs are the red evidence.
- **Task 2** (2026-10-22, its implementer; q9). `CloseTransitionTest` and `StreakRulesTest`
  failed to compile, then passed. `CloseDayTest`: "withheld when archived before its reveal"
  failed with `expected:<NOT_YET> but was:<REVEALED>`. Seven mutations: no withholding (6
  tests red), the run's time for the due instant (red), `endedAt` for `separatedAt` (red),
  `DayOutcome.of(PENDING_REVEAL)` null (3 red), no cap at the day's end (red), the end-of-day
  close with `withheld = false` (2 red), and dropping `withholdReveal()` in `CloseDay`, which
  **survived** until the resumed-joining-day test was added. `CloseElapsedDaysTest`'s "an ended
  bond's day that was waiting on its reveal time is still revealed when the time comes"
  failed under the new code and was inverted. Searched and read: the day view and the
  search do not show the partner's words on a withheld day, run in `DayViewTest`.
- **Its review** found the read path (`ReconcileJoiningDay` revealed a legacy joining day
  without asking), and **its fix round** (`7c8d408`) added `BondMembership.separatedAt` and the
  `JoiningDayTest` case, seen red (`"status":"REVEALED"`, both entries readable) before the
  fix. Mutations (h), no `isWithheldBy` branch, and (i), the membership built from
  `archivedAt`, both red. The module suites at that commit: 692 gratitude tests, 300 bond,
  ktlint and detekt green.
- **Task 3** (2026-10-22, this record's author).
  - **`scripts/state-map-check --strict`**: exit 0, "663 rows, 284 never run, 0 pending, 29 contract gaps. Nothing wrong." The checker's 154 tests pass. Before the data changed the check named 16 rows whose cited code had moved (`BondDay.close`'s `when`, `CloseDay`'s `bondEndedFirst` and its reveal line, `revealWhenDue`'s guard); each was read again, re-cited, and where the ruling changed what it says, rewritten.
  - **`./gradlew build --rerun-tasks`** at `6445d92`: `BUILD SUCCESSFUL in 4m 30s`, 217 tasks executed, **1,551 tests, 0 failures, 0 errors, 2 skipped** (the two search measurements, gated by `MOYI_MEASURE`), counted from all 179 result files, every one written by that run. `6445d92` is the code of `7c8d408`; the commits after it are documentation.
  - **The smoke**: the compose Postgres could not bind 5432, which another project's container held, so it was started on 55432 through a compose override, a database of its own (`moyi_smoke_c40`) created, and the jar, built from `6445d92`, booted by hand exactly as `scripts/smoke.sh` boots it (JDK 25.0.4, local profile, the close job every five seconds) on port 18095; the script ran with `--attach`: **930 passed, 0 failed, 0 skipped, 24 sections**. The application's log held no `ERROR` line. The database was dropped and seen gone, the jar stopped, and the Moyi Postgres left running. The smoke does not drive a separation before a reveal time (no day ends during a run), so the withheld day is the tests', not the smoke's.
- **Argued, not run:** that a countdown called off after a leave leaves the leaver's entry
  unlocked under `archivedAt` alone (Question 4).
