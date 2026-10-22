# ADR-0041 — Milestones

**Status:** Accepted · **Date:** 2026-10-22 · **Deciders:** Daniel, for the four rulings of
2026-10-22 (Q-M1, Q-M2, Q-M3, Q-M5) only. The eight defaults under "The controller's" were set
by the controller of the C6 session; every other decision here was made by the plan or by the
agents that ran it.

## Context

The last of slice C6's three pull requests (ADR-0038, Context: C6a prompts, C6b search and
on-this-day, **C6c** milestones). It closes Phase 3.

**What the corpus asks.** FR-075 (doc 03:85, SHOULD): "Milestones at 7, 30, 100, 365 complete
days MUST produce an in-app celebration and a shareable card containing no entry content by
default." It derives from the streak's requirements: FR-070 and FR-071 (the run and today),
FR-074 (recompute from "the Bond-day timeline"), FR-076 (no loss-aversion, guilt or urgency),
BR-4 (what counts) and BR-10 (a settled status is never recomputed from entries, which "preserves
the surviving partner's `longestStreak` and `totalCompleteDays`"). Doc 06:174: `GET
/bonds/{id}/milestones`, "Achieved and next milestone". Spec §1: "The milestone *dates* are
derivable from the streak state". ADR-0034 decision 11 ("the outbox gets two": `StreakExtended`
and `StreakBroken`) and its Owed "C6" (`MilestoneReached` and the milestones endpoint). Doc
12:66: "Streak: seven consecutive complete days → streak 7 → milestone fires."

**The corpus read** (`c6-corpus.md` §4, 2026-10-15, in the session's working folder and not in
this repository) found six contradictions and five privacy hazards:

- **M1** counted on what: complete days (FR-075, doc 02:136) or the run (doc 12:66, spec §1),
  which under ADR-0034 includes covered and stepped-over days that nobody wrote on.
- **M2** "derivable from the streak state": `streak_states` holds a count and one date, not the
  date of the 7th or the 30th complete day.
- **M3** "next milestone" (doc 06:174) against no gamification and no countdown pressure.
- **M4** an ended bond: "no streak" (doc 02:144, `states.md`:790) against a built streak that
  answers on an ended bond.
- **M5** `MilestoneReached` cannot say which milestone: payloads are ids only (ADR-0035).
- **M6** which screen owns milestones: spec §11 says §7 *Streak*, `states.md` puts the row in
  §6 *Archive*, and doc 27:85 says it has no frame.
- **H-M1** no per-member "celebrated" flag on the server (a read receipt, FR-064).
- **H-M2** bond-level counts only (a per-member count resolves to the partner's misses).
- **H-M3** no entry content: thresholds and dates only.
- **H-M4** ended bonds answer identically, however they ended.
- **H-M5** the push reaches both members or neither.

Which test answers each hazard, as built (the plan's table, corrected by the reviews):

| Hazard | Test that fails without its mechanism |
|---|---|
| H-M1 | `ArchitectureTest`: "the milestones are read from the bond's days, and from nothing a member did alone", which pins `GetMilestones`' constructor to `[BondAccess, Clock, GetStreak, StreakCalendar]` and, since the Task 2 review (m1), `MilestonesController`'s to `[BondAccess, GetMilestones, ReconcileJoiningDay]`; `MilestonesEndpointTest`: "the seventh complete day is reached as soon as both have written it, and keeps its date when the job evaluates it" (both members, the same bytes, in either order) |
| H-M2 | `MilestonesEndpointTest`: "the payload carries these fields and no other, and a stranger gets the one 404"; `OpenApiContractTest`: "milestones are what was reached and the next threshold, and no count" (an added `totalCompleteDays` turned both red, Task 2 G2). The Task 2 review also read every response header: none carries a count |
| H-M3 | the same two; `MilestoneReachedTest`: "the seventh complete day writes one MilestoneReached, naming the bond and that day and nobody" (payload keys) |
| H-M4 | `MilestonesEndpointTest`: "an ended bond keeps what it reached with nothing next, the same bytes left, blocked or counting down, for both members" (left, blocked with withdrawal, blocked without it since the Task 2 review's m2, the countdown; bodies and headers; before and after the erasure and the job; a countdown called off gives `next` back); "a block committed after the guard resolved the membership leaves nothing next" (the controller's ruling 1) |
| H-M5 | **Phase 4's** (Owed). What this slice holds: the event names the bond and the day and no member (`MilestoneReachedTest`'s payload test), so a consumer has no member to address but through the bond |

The cross-tenant fixture for the route is in `GratitudeCrossTenantTest` (red without it, Task 2
Step 8); the response is never stored (`CacheHeadersTest`, the member and the stranger).

The slice was built in three tasks (`docs/superpowers/plans/2026-10-22-milestones-c6c.md`),
all on 2026-10-22. Tasks 1 and 2 each had an independent review and a fix round; Task 3 had no
review of its own, and was reviewed by the two whole-branch reviews (conformance and privacy;
concurrency, data integrity and performance), which were followed by one fix round. The branch
`feat/gratitude-milestones` was cut from C6b's at `6116756`, C6b's branch tip, which is not on
`main`: #68 (C6b) squash-merged as `dcb3b36`, with the same tree. After Task 3 the branch was
rebased onto `dcb3b36` (`git rebase --onto origin/main 6116756`). Commits are named here as
they stand after that rebase; each run before it was made on the same change (every rebased
commit has its original's tree), and the local tag `pre-rebase-c6c-t3` holds those commits.

## Decision

### The owner's rulings

Asked directly by the controller on 2026-10-22, and answered by Daniel:

- **Q-M1. Milestones count `totalCompleteDays`**: cumulative, days both wrote on, never lost;
  a covered or missed day is never celebrated. Resolves M1.
- **Q-M2. Exactly four thresholds, 7, 30, 100 and 365; nothing after.**
- **Q-M3. `next` is the threshold only**: no days-remaining field, nothing else a countdown
  could be read from. Resolves M3.
- **Q-M5. Reached at the reveal**: `GET /milestones` counts today at read time once both have
  written, as `GET /streak` does; `MilestoneReached` is still written when the close job
  evaluates the day.

A peer session's pull request, which merges after this one, builds the owner's ruling of
2026-10-21 on ADR-0035 question 9 (a `PENDING_REVEAL` day is withheld once its bond has
ended). Such a day will then not be `REVEALED`, so not complete: no milestone counts it and no
`MilestoneReached` names it. Nothing here needs to change for that; no test here pins the
reveal of an ended bond's waiting day.

*(Amended 2026-10-22, ADR-0040, which built that ruling. The paragraph above was wrong about
its effect: the owner ruled on 2026-10-22 that a withheld day closes `PENDING_REVEAL` and
**counts `COMPLETE`**. So milestones do count it, it can be a milestone's `achievedOn`, and
`MilestoneReached` can name it.)*

### The controller's defaults

Set by the controller of the C6 session on 2026-10-22, each the owner's to turn:

1. **An ended bond answers `achieved` as history and `next: null`**, byte-identical for left,
   blocked and the deletion countdown (Q-M4, M4, H-M4). The predicate is `takesWrites`, as the
   controller asked to be checked: it is the one ADR-0038 ruling 6 and ADR-0039 decision 14 use
   for every read that answers differently on an ended bond, so one bond is "ended" the same
   way on every screen (plan decision 7, below).
2. **`achievedOn` comes from the `bond_days` timeline** (the Nth day evaluated `COMPLETE`) plus
   today at read time; no migration; it survives `recalculate` and account deletion (M2).
3. **`MilestoneReached {bondId, bondDayId}`**, ids only, one per threshold crossed, written in
   the evaluation's transaction, once per bond and day, never from `recalculate` (M5).
4. **No server-side per-member "seen" or "celebrated" state**; novelty is the client's, from
   `achievedOn` (H-M1).
5. **Bond-level only**; no per-member count (H-M2).
6. **Thresholds and dates only**; no entry id, no text (H-M3).
7. **Any future push reaches both members or neither** (H-M5), recorded for Phase 4.
8. **Spec §11 is amended: milestones have no frame** (`states.md` §6's row) (M6).

The controller also ruled on the plan's questions, on its reviews' findings and on the
implementers' deviations (the C6c ledger, 2026-10-22). Where a ruling changed what the plan
decided, the decision below says so.

### What the plan decided

Numbered as the plan numbers them, because code and tests cite them by number. Under each,
what was run to decide it or to hold it. "Red" means a test was seen to fail for the stated
reason; each mutation was restored and the restore checked.

1. **The rule lives in one place: `gratitude.domain.Milestones`.** `THRESHOLDS` (7, 30, 100,
   365), `crossed(before, after)`, `next(total)` and `achieved(total, dates)`; the event and
   the read both ask it. *Run (Task 1):* `MilestoneRulesTest` failed to compile first, then
   passed, 4 tests. `crossed`'s `it > before && it <= after` → `it <= after`: red ("a day
   crosses a threshold…", `crossed(7, 7)` gave `[7]`). `next`'s `it > total` → `it >= total`:
   red (`next(7)` gave 7).
2. **A milestone counts days evaluated `COMPLETE`, and is dated by the Nth of them**, from
   `bond_days`, never from `streak_states`. The two agree while the projection is true:
   `StreakRules.complete` is the only step that adds to `totalCompleteDays`, adds exactly one,
   and runs for exactly the days recorded `COMPLETE` (read, not run). A day both wrote on keeps
   `REVEALED` and `COMPLETE` through a tombstone, a withdrawal's erasure and an account deletion
   (BR-10), so a date survives all three. *Run (Task 2):* the count taken from the stored
   projection instead (M8): red, "the dates are the bond's days, not the stored streak…", on
   the drifted read (`achieved` empty). `evaluated_as` → `status = 'REVEALED'` (M7): red, "today
   is not counted…", and three more through the evaluation, which shares the statement since
   `712a061`.
3. **One statement gives the count and the dates**, `StreakCalendar.completeDays(bondId,
   ordinals)`: `row_number()` and `count(*)` over the bond's complete days, keeping the rows
   asked for and the last one; the ordinals travel as one bound `?::int[]`. *Built in Task 1's
   fix round* (`712a061`), not Task 2, because the event came to need it (decision 8). *Run:*
   Postgres casts the bound text `{7,30,100,365}` to `int[]` (the "all four" test reads all
   four dates) and `{}` too (Task 1). `ORDER BY date DESC` (M5): red, four tests, the event's
   date moving with the read's. Dropping `OR n = total` (M6): red, five tests, `achieved` empty.
4. **Today is counted by `GET /streak`'s rule, now one function**, `GetStreak.countsToday(
   membership, today, todayComplete)`: today's square is `COMPLETE` (it has **revealed**) and it
   follows the last evaluated day, or is the joining day with nothing evaluated. On a bond with
   a reveal time a day both have written waits for it (`PENDING_REVEAL`, an `OPEN` square) and
   counts for neither read. *Run (Task 2):* `StreakEndpointTest` 19 of 19 after the extraction.
   `countsToday` → `todayComplete` in `GetMilestones` (M1): red, "today is not counted while
   the day before it is still to be evaluated…". The same in `GetStreak.countsToday` itself
   (M9): red here and in two `StreakEndpointTest` tests, one rule for two screens. Today's
   square read as "any square" (M2): red, three tests. `counted = false` (M3): red, six.
   Whether extracting the function exposed an earlier disagreement between the two reads: it
   did not; `StreakEndpointTest` passed unchanged.
5. **The read order** (*changed by the controller's ruling on the Task 2 review, I1;*
   `ed3a848`). The plan read the timeline first, then today's square, then the last evaluated
   date. The review found, by reading, that an evaluation committing between the first and the
   last statement would give "7 reached today" for the day `MilestoneReached` dates yesterday,
   an answer neither moment gives. **As built, `GetMilestones.of` reads today's square, then
   `countsToday` (which reads the last evaluated date), then `completeDays`.** Each statement
   at `READ COMMITTED` sees what has committed by then, and an evaluation sets a day's
   `evaluated_at` and `evaluated_as` in one commit. A stale last evaluated date withholds
   today, so the answer is at most a day behind; a fresh one implies a timeline that holds
   that day. Today is never evaluated while it is today (ADR-0034, Consequences), so nothing is
   counted twice. That depends on the settle margin, which the final concurrency review named
   (m3): a day is settled only once it has been over for a minute (ADR-0033 decision 5), so a
   request that computed `today` just before midnight cannot see that day evaluated by its
   later statements. If the margin is ever removed, this order no longer suffices; one
   `REPEATABLE_READ` snapshot for the four reads would. **Argued, not run:** no existing hook can stop a request between two of its
   statements without a new test context, and the ruling accepted the argument in place of a
   test.
6. **The response is `{achieved: [{threshold, achievedOn}], next: {threshold} | null}` and
   nothing else**, `achieved` oldest first. **No `totalCompleteDays`**: Q-M3, and M3's reason,
   that leaving the count out keeps "days to next" from being one subtraction on one response.
   `GET /streak` already sends `totalCompleteDays` and keeps it; the count is derivable
   elsewhere too (its calendar, the archive), so Q-M3 is held as "no countdown on this
   response", not "no countdown anywhere" (an owner question, below).
   *Run:* the payload test and the contract test (both red with the field added, Task 2 G2);
   the smoke compares the bytes exactly, field order included (`achieved`, then `next`).
7. **`next` is `null` whenever the caller's membership takes no writes** (archived by a leave
   or a block, the member who left, the deletion countdown), and after 365; `achieved` never
   changes with an ending (doc 02:127). *Why `takesWrites` and not "archived":* above, default
   1; the owner's ruling of 2026-10-21 on ADR-0033 question 1 (only an archived bond counts as
   ended) covers the lone-entry reveal and says reads gated on taking writes are unchanged by
   it; a countdown has no future while it runs, and calling it off brings `next` back. **Asked
   of the membership resolved again** (`access.membershipNow(membership)`), after the
   joining-day reconcile, not of the guard's. *Changed by the controller's ruling 1* on the
   plan's question: the plan had asked it of the guard's membership, as `GET /streak` asks
   `isOpen`; the ruling followed the codebase's rule since C6b (an ending that commits after
   the guard must not leave a read answering as before it) at the cost of one more lookup.
   *Run (Task 2):* `next` always set (M4a): red, the ended-bond test and the race test;
   `endedAt == null` (M4b): red, the ended-bond test (the countdown kept `next`); `!hasLeft`
   (M4c): red, both; the guard's membership instead of `now` (M4e): red, the race test only,
   which waits on the membership-resolution hook, not a sleep. **Survived: `takesWrites` →
   `isOpen` (M4d), as predicted.** `takesWrites` is `isOpen && !hasLeft`, and a leave ends the
   bond, so no reachable membership is open and left at once: an equivalent mutant, as
   ADR-0039 decision 14 records for on-this-day.
8. **`MilestoneReached` is written by `EvaluateStreaks`, per evaluated day, after `record`,**
   for each threshold in `Milestones.crossed(before, after)`, as `OutboxEvent("BondDay", dayId,
   "MilestoneReached", {bondId, bondDayId}, now)`. It shares the transaction with
   `StreakStore.recordDay`, which updates only `WHERE evaluated_at IS NULL` and refuses anything
   but one row, so a day is announced once or not at all. Never from `RecalculateStreak`.
   **Counted on the timeline, not the stored total** (*changed by the controller's ruling on the
   Task 1 review, m1;* `712a061`). The plan crossed on the fold's `totalCompleteDays`. As built,
   the evaluation reads `calendar.completeDays(bondId, emptyList()).total` once per bond per
   run, after the bond lock and the streak row's `FOR UPDATE`, and adds one as each day
   evaluates `COMPLETE`, so **the event and the read count from one source**, and an event's
   `bondDayId` is always the day the read dates. *Run (Task 1 and its fix round):*
   `MilestoneReachedTest`, 7 tests, the first four seen red before the call existed (8 since
   the final fix round, below). Removing
   the call (E1): all red. A threshold re-announced (E3, `it <= after`): red, the backlog test
   and "a day evaluated again…". A payload without `bondDayId` (E4): all red. The aggregate as
   the bond (E5): red, the first test. Publishing from `RecalculateStreak` (E6): red, "…a
   recalculation, write no second MilestoneReached", 3 where 1. Publishing in `REQUIRES_NEW`
   (mutation C): **green until the review's probe became the fifth test** ("a bond whose
   evaluation fails announces nothing, and its retry announces once", a trigger failing the
   `streak_states` update); then red, that test only. Crossing on the stored total again: red,
   "a drifted stored total neither moves nor repeats the announcement" (`[day 4, day 7]`, early
   and twice), the test seen red on the old code first. The count restarted at 0 each run: red,
   two tests. Crossing on the run: red, the backlog test. **Survived: E3 against the break
   test** ("a day only one wrote on reaches nothing, and the break after it takes nothing
   back"), predicted red: its total reaches 7 only on its last day, so no later day could
   re-cross. The mechanism is held by the backlog test and "a day evaluated again…"; the review
   judged no extension owed. The review also probed a 40-day backlog closed in one run and the
   same history over five runs with the stored total bumped by five after each: the event days
   equalled the read's `achievedOn` every time (run). **The read's place under the lock was
   held by nothing** until the final concurrency review (I1) moved it above the bond lock and
   41 tests stayed green. The final fix round added "a run that waited on the bond's lock counts
   from the days committed while it waited": a connection holds the bond row, the run waits on
   it (seen in `pg_stat_activity`, not a sleep), the holder commits days 1 to 6 evaluated
   `COMPLETE`, and the released run announces 7 on day 7. *Run:* the read moved above
   `lockClosingViewOf`: red, that test only (no announcement); restored, green.
9. **It is written on an ended bond too.** A day both wrote on before the bond ended is
   complete (ADR-0034 decision 7), and the event is history; doc 02:144's "no notifications"
   binds Phase 4's consumer (Owed). *Run (Task 1's fix round, m2):* "a day both wrote on
   before the bond ended still writes its MilestoneReached"; skipping ended bonds: red, that
   test only. The ended-bond endpoint test also counts four events for its four ended bonds.
10. **The route:** `GET /api/v1/bonds/{bondId}/milestones`, `MilestonesController.milestones`,
    `BondAccess.guard`, then `ReconcileJoiningDay.beforeRead`, then `GetMilestones.of`. No
    parameters; a member who has left reads it. *Run:* the stranger's and a non-UUID id's 404
    equal the streak route's (the Task 2 review); the smoke sees the stranger's. **Survived:
    removing `beforeRead` (M10), as predicted.** The reconcile only revives a legacy suspended
    joining day, which moves a milestone by at most the one day it contributes, and the next
    write or read of any other route reconciles it. `/streak`, `/on-this-day` and `/search`
    carry the same untested call; the guard owed is route-wide, for the module, not this slice's
    (Owed).
11. **The API's default `Cache-Control`** (`no-cache, no-store, max-age=0, must-revalidate`,
    `Pragma`, `Expires`); no `ETag`; no bucket of its own. *Run:* `private, max-age=60` in the
    controller (G3): red, `CacheHeadersTest`. The smoke reads the header off the jar.
12. **No per-member state, held by the constructor** (H-M1): `GetMilestones` is read-only and
    is handed `BondAccess`, `StreakCalendar`, `GetStreak` and `Clock`. `BondAccess` was added
    for ruling 1; it is the bond's access port, not a per-member store. *Run:* an unused
    `StreakStore` in the constructor (G4): red, naming the list. The controller's constructor
    is pinned too (`44b2673`): an unused `GetStreak` there: red on the new pin only.
13. **Nothing is logged, and no `SecretsNeverLoggedTest` probe was to be added**: a probe that
    watches for words cannot fail on a route that returns thresholds and dates. **As built,
    Task 2 added one** (`GET /milestones` after `GET /streak`, the KDoc's list extended). It is
    harmless and, for the plan's reason, holds nothing; it is recorded here because the plan
    said otherwise and no report named it a deviation.
14. **The contract states the four thresholds in words**, a mirror of `Milestones.THRESHOLDS`:
    the domain test holds the list, the contract test the sentence. Two copies of a constant
    the owner ruled fixed. *Run:* without `documentMilestones` (G1): red, the contract test
    (description null) and the stale contract.
15. **The state map gets one card and five rows** in the bond machine (`NO_BOND`,
    `PENDING_MEMBER`, `ACTIVE`, `PENDING_DELETION`, `ARCHIVED`). *Run:* `scripts/state-map-check
    --strict` exit 0 (How this was checked).
16. **The CLI is `moyi milestones`; Bruno gains `23 Milestones`.**

## Consequences

- **One new route, additive**: `GET /bonds/{bondId}/milestones` (`MilestonesResponse`,
  `AchievedMilestoneResponse`, `NextMilestoneResponse`). The contract gained 117 lines and lost
  none: the path (200, 401, 403, 404, 429, 500, as every bond route), the description, three
  schemas, `achievedOn` a `date`, `next` `oneOf [NextMilestoneResponse, null]`. **No new
  `ErrorCode`**, so no `breaking-api-change`.
- **No migration.** Spec §7's C6c row holds ("none"). Milestones read `bond_days`' V17
  columns.
- **A third event type from the close job's evaluation**: `MilestoneReached`, aggregate
  `BondDay`, `{bondId, bondDayId}`. It has no consumer yet, so no delivery rows: publication
  writes a delivery only per row of `outbox_subscriptions` for the type, and the dispatcher
  claims only the types its consumers declare (read and run by the Task 1 review).
- **The evaluation reads the timeline once per bond per run** (`completeDays`, one window query,
  O(complete days of the bond)), under the locks it already takes; nothing joins the lock graph.
- **A close run by an older build announces nothing.** During a blue/green cutover, or after a
  rollback to an image from before this slice, the old container's scheduler can take the
  close job's lock and evaluate days without writing `MilestoneReached`. Those days are
  evaluated once (`recordDay`'s `WHERE evaluated_at IS NULL`), so a threshold crossed in such a
  run is never announced: the loss is permanent, and accepted. `GET /milestones` is unaffected
  (it dates every threshold from the timeline), and nothing is announced twice. It matters
  only once Phase 4's consumer is live, and that consumer starts from `NOW` (Owed). Read by the
  final concurrency review (m1), not run.
- **`GetStreak.countsToday` is now the one rule for today**, shared by two routes.
- **The read is four reads of one bond's rows** (the membership resolved again, today's
  square, the last evaluated date, the timeline), with no lock, in the order decision 5 argues.
  The plan said three; ruling 1 added the first.
- **The shared test Postgres starts with `max_connections=300`** (`64a9cd1`). The gratitude
  test JVM had reached the default 100: about 25 cached Spring contexts, each holding Hikari's
  4 idle connections. Task 2's first `MilestonesEndpointTest` context, the 26th, made an
  unrelated class (`TimezoneMatrixTest`) fail to load with "too many clients". The limit was
  there before this slice; `PostgresContainerTest` holds the setting (red at 100).
- **`scripts/moyi milestones`; Bruno `23 Milestones`; the state map's card and five rows**; the
  smoke's milestones section; the state map's streak row on today re-quoted to
  `countsToday`'s call.

## Owed

- **Phase 4: the shareable card** (FR-075: "containing no entry content by default"; what the
  card shows beyond that, display names or the bond's name, is the owner's), and **the in-app
  celebration**, the client's: a device knows a milestone is new to it from `achievedOn`, never
  from server state (H-M1).
- **Phase 4: the consumer of `MilestoneReached`.** Any notification made of it reaches **both
  members or neither** (H-M5), and **none on a bond that takes no writes** (doc 02:144, "no
  notifications"): the consumer must ask the bond whether it takes writes before notifying,
  because the event is written for an ended bond's last days too (decision 9). Delivery is
  at-least-once, so it **dedupes on `bondDayId`**. It registers with **`StartFrom.NOW`**:
  `BEGINNING` would push every milestone ever reached on its first deploy.
- **The milestone's frame**: `states.md` §6 has the row and no frame (doc 27:85); spec §11 is
  amended (default 8). `states.md` lives outside this repository.
- **A route-wide guard that every bond read runs the joining-day reconcile** (decision 10's
  survivor): an `ArchitectureTest` rule, or one parametrised `JoiningDayTest` over the read
  routes. Owed for the module.
- **The corpus**: doc 12:66, spec §1, doc 06:174 and ADR-0034 decision 11 (below), carried into
  `Gratitude/documents/`, and ADR-0035 to ADR-0041 copied into `Gratitude/documents/adr/`
  (c6-corpus X3).
- **The guard's older copies** (ADR-0039 decision 21), unchanged.

## Questions that are the owner's

The four rulings are his. Then one line per controller default, with the alternative and what
turning it would cost:

1. *Ended: achieved kept, next null, `takesWrites`.* Alternative: hide `achieved` too (doc
   02:144's "no streak"), which diminishes the archive (doc 02:127); or "archived" only, as
   ADR-0033 question 1 was ruled, which shows a countdown a goal it cannot reach.
2. *Dated by the timeline.* Alternative: a `milestones` table written at the close; a
   migration, a second copy of a fact the days already hold, and a date the reveal could not
   show until midnight.
3. *`MilestoneReached {bondId, bondDayId}`.* Alternative: one event type per threshold, or a
   threshold in the payload; four types, or a number in a payload that is ids only.
4. *No per-member "seen".* Alternative: a server flag; a read receipt (FR-064).
5. *Bond-level only.* Alternative: "you wrote 30 days"; it resolves to the partner's misses.
6. *Thresholds and dates only.* Alternative: the day's entries or ids on a milestone; entry
   content on a surface FR-075 says carries none by default.
7. *Both or neither.* Alternative: notify the member who opened the app; it tells them about
   the other.
8. *No frame, spec §11 amended.* Alternative: decide Archive or Streak now, with no frame to
   build to.

Then the plan's decisions most worth his eye, one line each:

- **Decision 6, no `totalCompleteDays` here, while `GET /streak` keeps sending it.** Q-M3 is
  held as "no countdown field on `/milestones`". A countdown stays derivable whatever that
  field does: `/streak` also sends its calendar, 371 days of squares (`GetStreak.CALENDAR_DAYS`),
  and counting the `COMPLETE` ones gives the exact total on any bond up to 371 days old, which
  covers every threshold through 365; the archive (`GET /days`) lists the days as well. So
  dropping `totalCompleteDays` from `/streak` would be a breaking change to that response and
  would not close the gap. Whether any surface should make counting hard, and whether the
  streak screen shows the total at all, is the owner's and design's, not this slice's to close.
- **Decision 7, `next` asked of the membership resolved again**, not the guard's (ruling 1):
  one more lookup per request, for an answer that cannot lag an ending.
- **Decision 9, the event is written on an ended bond**; the consumer decides not to notify.
- **Decision 4, today counts at the reveal, not at the second entry**, on a bond with a reveal
  time: if both have written by 18:00 and the reveal is at 20:00, the milestone is reached at
  20:00, on both screens alike.

## What the corpus now says that is false

For the owner to carry into the corpus repository, which is not edited here.

1. **Doc 12:66**, "streak 7 → milestone fires": seven complete days, not a run of seven
   (Q-M1). A run of seven with a covered day in it reaches nothing.
2. **Spec §1** and **§7's C6c row**, "derivable from the streak state": `streak_states` holds
   no dates; they are the timeline's (amended in place).
3. **Doc 06:174**, "Achieved and next milestone": `next` is a threshold and nothing else, and
   null once all four are reached or the bond takes no writes.
4. **Doc 02:144** and **`states.md`:790**, "no streak" on an archived bond: milestones answer
   there, as history, as the streak does.
5. **Spec §11**, "`/milestones` → §7 *Streak*": no frame; `states.md` §6's row (amended).
6. **ADR-0034 decision 11**, "the outbox gets two": the evaluation now writes three event types
   (its Owed "C6" is discharged).

## Revisit when

- A bond's history passes a few thousand days: the read numbers every complete day of the bond
  and keeps five rows, and the evaluation does the same once per bond per run. *Measured by the
  final concurrency review* (a throwaway `postgres:18`, `bond_days` with V12/V15/V17's indexes
  and checks, 2,002,000 rows: 5,000 bonds of 400 days and one of 2,000, about 75% `COMPLETE`,
  then `VACUUM ANALYZE`): on the 2,000-day bond the read took 1.10 ms and the evaluation's call
  0.69 ms (0.77 ms as a generic plan), a bitmap scan of `bond_days_feed_idx`, no Seq Scan; with
  the table rebuilt in date order, as production inserts it, 420 heap blocks and 3.4 ms cold;
  the evaluation's call for all 5,001 bonds at one midnight, looped inside the database, 5.7 s,
  about 1.1 ms a bond inside its lock. Acceptable now: it grows with a bond's history, not with
  activity. When it is due, a partial index `bond_days (bond_id, date) WHERE evaluated_as =
  'COMPLETE'` makes both calls index-only; the evaluation could also read the count only once
  its first day evaluates.
- A fifth threshold or a yearly one is wanted (Q-M2): one constant and the contract's sentence.
- Phase 4 builds the card and the push.
- A second consumer of `MilestoneReached` appears: it carries ids only, by design.
- ~~The peer's `PENDING_REVEAL` ruling lands~~ It landed in ADR-0040 and changes what is
  counted (the amendment under the rulings).

## How this was checked

"Run" means executed and seen by the party named. Nothing here was run by the owner. Every run
below is the named agent's report of its own work, except Task 3's, which are this record's
author's.

- **Task 1** (2026-10-22, its implementer; `5a96d99`, `a5f458e`). `MilestoneRulesTest` failed
  to compile, then passed; `MilestoneReachedTest` 4 of 4 red first (each on `milestones()`,
  `[]` where ids were expected; test 2's `FROZEN` line and test 3's `current_streak = 4` line
  passed before it, as predicted), then green. The mutations of decisions 1 and 8, above, with
  the one survivor. `./gradlew build` green, 1,515 tests, 0 failures, 2 skipped.
  **Its review** (Critical 0, Important 1, Minor 4): reran the suites, mutated the break
  (red) and a freeze adding a complete day (red), found `REQUIRES_NEW` green and wrote the
  probe that became the fifth test; read once-per-threshold across a re-run, a backlog, a
  failed close and two concurrent closes (bond lock, then the streak row, then
  `recordDay WHERE evaluated_at IS NULL`). **Its fix round** (`e4cf0cd`, `712a061`, `ec2eeaf`):
  the rollback test, the timeline crossing (the drift test seen red first), the ended-bond
  event; mutations as in decisions 8 and 9. Build green, 1,518 tests, 2 skipped.
- **Task 2** (2026-10-22, its implementer; `94b909d`, `41d9ebc`). `MilestonesEndpointTest` 10
  of 10 red first, each `expected:<200> but was:<404>`; `GratitudeCrossTenantTest` red without
  the fixture, naming the route; `OpenApiContractTest` red twice (stale; description null).
  Predictions marked "to be confirmed by running", each confirmed: the job reveals a day whose
  reveal time has come (Lagos 20:00, the clock at 19:01Z); two `POST deletion-requests` put the
  bond in `PENDING_DELETION`; pairing leaves no `bond_days` row for day 0 (the hand-made rows
  counted `through + 1`); Postgres casts the bound text to `int[]`. The mutations of decisions
  2–7 and 10–14, above, with M4d and M10 surviving as predicted. A first module build failed:
  the extra context's "too many clients" (Consequences); the test was moved onto an existing
  context pair. Build green, 1,530 tests, 2 skipped. **Its review** (Critical 0, Important 2,
  Minor 4): ran 65 gratitude and 57 app tests, a probe of the 40-day and five-run backlogs
  (event days equal to the read's dates), the live headers, and a plain block against a leave
  (identical); found the read order (I1, by reading) and the connection limit (I2). **Its fix
  round** (`64a9cd1`, `ed3a848`, `44b2673`, `1e1fc07`): `max_connections=300` with its test (red
  at 100) and the old import pair restored for one run (673 gratitude tests, 0 failed); the
  read order (decision 5; argued, not run); the controller's pin (red); the plain block.
  `./gradlew build --rerun-tasks`: 1,531 tests, 0 failures, 2 skipped.
- **Task 3** (2026-10-22, this record's author; `b829743` and the commit of this record).
  - **The smoke.** The compose Postgres could not bind 5432, which another project's container
    held, so it was started on 55432 through a compose override, a database of its own
    (`moyi_c6c_smoke`) created, and the jar, built from `1e1fc07`, booted by hand exactly as
    `scripts/smoke.sh` boots it (JDK 25.0.4, local profile, the close job every five seconds)
    on port 18094; the script ran with `--attach`. First run: **929 passed, 0 failed, 1
    skipped, 24 sections**; the skip was the withdrawal section's tag-across-the-erasure check,
    the poller having won its race. Again on a fresh database and the same jar: **930 passed,
    0 failed, 0 skipped, 24 sections.** The milestones section's 28 probes passed both times;
    the six hand-made complete days met V17's evaluation check, so its seventh-day and
    ending probes ran rather than skipped. The application's log held no `ERROR` line in
    either run. Each database was dropped and seen gone, the jar stopped, Postgres stopped.
  - **The CLI**, against the first run's jar: `register`, `verify`, `login` for two profiles,
    `bond new`, `bond join`; `milestones` on the new bond (nothing reached, 7 next); both
    `write -`; `milestones` again (the same: one complete day); six earlier complete days put
    in by the smoke's SQL; `milestones` (7 reached on 2026-10-22, 30 next); `bond leave
    --keep-entries`; `milestones` for the one who stayed (7 kept, `next` null);
    `milestones extra` (the usage line, exit 1). **Bruno**: one request written, not opened.
  - **The state map**: the card, five bond rows and the streak row re-quoted; every quoted
    line checked against the tree with `grep -F`; `scripts/state-map-check --strict` exit 0,
    "657 rows, 286 never run, 0 pending, 29 contract gaps. Nothing wrong."; the checker's 154
    tests pass.
  - **`./gradlew build --rerun-tasks`** at `b829743` (the code of `1e1fc07`; this record, the
    spec, ADR-0034, the README and the log were being written in the tree, documentation
    only): BUILD SUCCESSFUL in 3 m 58 s, 217 tasks executed, **1,531 tests, 0 failures, 0
    errors, 2 skipped** (the two search measurements, gated by `MOYI_MEASURE`), counted from
    all 179 result files. `scripts/state-map-check --strict` after it: exit 0.
- **The whole-branch reviews** (2026-10-22, at `95ba089`, two agents, read-only on the branch).
  *Conformance and privacy* (Critical 0, Important 2, Minor 5; docs only): ran oasdiff
  (`breaking` exit 0, one `endpoint-added`, so no `breaking-api-change` label), the commit-message
  hook on all 14 commits, an ancestry check of every SHA cited (the pre-rebase names, I1) and a
  `grep -F` of every new state-map quote; found by reading that `/streak`'s calendar also gives
  the count (I2, decision 6's owner question). *Concurrency, data integrity and performance*
  (Critical 0, Important 1, Minor 4; a test gap): `./gradlew build --rerun-tasks` 1,531 tests, 0
  failures, 2 skipped; the smoke at `95ba089`'s jar, 930 passed, 0 failed, 0 skipped, 24
  sections; the count read moved above the bond lock, 41 tests green (I1, decision 8); the
  measurements under Revisit when; the older-build close run (m1) and the settle margin (m3),
  by reading.
- **The final fix round** (2026-10-22, its implementer). The lock test of decision 8, seen
  green, then red with the read moved above `lockClosingViewOf` (that test only), then green
  restored; the pre-rebase names replaced (every rebased commit's tree equal to its original's,
  run); the owner question reworded; the test-infra comments; the state-map row's evidence;
  this record's additions. `scripts/state-map-check --strict` exit 0. `./gradlew build
  --rerun-tasks` at the round's last change to code or comments (only this record changed
  after it): BUILD SUCCESSFUL in 4 m 3 s, 217 tasks executed, **1,532 tests, 0 failures, 0
  errors, 2 skipped** (the search measurements), counted from all 179 result files, every one
  written by that run. The smoke was not run again: `scripts/smoke.sh` changed only in its
  header comment.
- **Argued, not run:** decision 2's invariant (the count of `COMPLETE` days equals
  `total_complete_days` while the projection is true); that today is never evaluated while it
  is today; decision 5's read order; that an account deletion leaves a day's status as it was,
  which no test here drives, being `identity`'s route (the withdrawal's erasure is the test).
- **Not run by anyone:** the Bruno request; CI's contract action; the peer's `PENDING_REVEAL`
  ruling against these tests (it is not yet built).
