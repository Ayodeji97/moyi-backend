# ADR-0033 — The close job

**Status:** Proposed · **Date:** 2026-10-05 · **Deciders:** Daniel

## Context

Until this slice, a day ended only when somebody did something to it. A day one member wrote
on stayed `PARTIAL` for ever; a day neither wrote on had no row at all; and a couple with a
reveal time who both wrote before it stayed locked to each other (ADR-0032 decision 5).
Slice C3 is the part of the loop that runs without anybody submitting (spec §6.4): every
quarter of an hour a job settles the days that have ended, reveals the days whose time has
come, and writes the days nobody opened.

ADR-0031 and ADR-0032 left twelve obligations on it by name. The hard one is ADR-0031
decision 18, ruled by the owner: **the closer takes no bond lock.** Every other writer of a
day begins by taking the bond's row; the closer handles every bond on a timer and would
serialise behind each one. So the closer and a live submission meet at the day's row and at
`bond_days`'s unique `(bond_id, date)`, and nowhere else.

The slice was built in eight tasks and then read whole by two independent reviewers, one
for locking and time, one against the spec. They found no deadlock. They found one race a
request could lose, one violation of the spec, and four smaller things. "How this was
checked" says which of the decisions below came from that.

## Decision

**1. `DayCloser` takes the time, not a zone.** Spec §2.2 sketched
`closeElapsedDays(zone: ZoneId)`, a call per timezone. §6.4, written later, makes that
unbuildable: a day's end is read from its bond's own timeline of zones (ADR-0031 decision
13), and a day opened under one zone may belong to a bond that now requests another. "The
days in this zone" is not a question the data can answer. The port is
`closeElapsedDays(now, budget)` and nothing iterates zones. §2.2 is amended.

**2. `scheduling` knows when; `gratitude` knows what.** `modules/scheduling` holds the
timer, the lock and the meters and calls `gratitude.api.DayCloser`. What the end of a day
does to it is a transition on `BondDay` (`close`), beside the ones the synchronous path
uses, so the two cannot state one rule twice (spec §2.2). Nothing in `scheduling` can reach
past `gratitude.api`: everything else there is Kotlin `internal`.

**3. One day, one transaction, one row lock, no bond lock** (`CloseDay.settle`). The writers
that hold more than one day of a bond take them oldest first under the bond's lock
(ADR-0032 decision 6). The closer has no bond lock, so the only way it can never be the
other half of a deadlock with them is never to hold two days at once. A failure then costs
one day, which is a candidate again on the next run.

Inside, in this order, and the order is the decision:

1. Lock the day's row and read it again.
2. Bring its span up to the bond's timeline (`extendedTo`). A row opened before a westward
   zone change still ends where the calendar said then.
3. Resume it if it is the joining day and apply the reveal rule — the same two steps, by
   the same code, the first request to meet the day would have taken. This is also the
   second sweep of spec §6.3: a `PENDING_REVEAL` day whose time has come is revealed here
   whether or not it has ended.
4. Only now ask whether the day has ended.
5. Close it: `OPEN` to `EMPTY`, `PARTIAL` to `SOLO` with the lone live entry revealed,
   `PENDING_REVEAL` to `REVEALED`. A day revealed while it was open, and a `SUSPENDED` day,
   gain `closedAt` and nothing else.

**4. A stored end finds every day that has ended, and decides none of them.** The sweep's
candidates are unclosed rows whose stored `ends_at` had passed a minute ago (decision 5),
plus rows pending a reveal time. A day's
end only ever moves later (ADR-0031 decision 13), so its true end is never before the stored
one and the filter misses no ended day; it also finds, early, a row a westward change has
since lengthened, which step 2 extends and step 4 answers "not yet". So no bond is visited
to find days that have a row. V15 adds the partial index the scan uses
(`bond_days (ends_at) WHERE closed_at IS NULL`); V12's `bond_days_open_idx`, on
`(status, date)` for three statuses, was written for this job before it was designed; at
most it now serves the `PENDING_REVEAL` arm of the scan. Which the planner chooses for
that arm has not been looked at: the test asserts only that V15's index is used.

**5. A day is settled only once it has been over for a minute.** This is the price of
decision 18. Without the bond's lock the closer sees of a bond what has *committed*. A
pairing stamped in a day's last second can still be committing in the next — and the job
fires when days end. Read then, the bond is still one person, the day is closed as a
suspended day nobody is asked about, and when the pairing lands it is the couple's joining
day, closed, which nothing reopens (decision 7). A westward zone change confirmed across
midnight is the same shape: the day is closed at its old end, a day early.

`DayCloser.SETTLE_MARGIN` is one minute: far longer than a commit takes or two instances'
clocks differ by. **It is a margin and not a proof.** `ChangeTimezone` reads its clock
under the bond's lock, so its gap is a few statements and a commit. `AcceptInvite` reads
its clock *before* it takes the lock, so its gap is however long it waits for that lock —
and nothing bounds that in production: no `lock_timeout` is set outside tests (ADR-0031,
Owed). A bond write held up for more than a minute across a midnight still loses the race,
and the cost when it does is the one above: a joining day with an entry on it, closed
suspended, for good. The job fires at one minute past each quarter, so the margin costs a
minute and not fifteen. A reveal falls due at `now` itself: nothing a bond write does can
make an already-due reveal wrong.

*Rejected:* taking the bond's row `FOR SHARE SKIP LOCKED` under the day lock, and answering
"not yet" when it is held. It is exact against a transaction in flight and does nothing for
clock skew between instances; and it is a bond lock, which decision 18 ruled out. If the
margin is ever found wanting, this is the next step, and it needs that ruling revisited.

**6. The days nobody opened are written closed, in one statement.** `CreateMissingDays`
walks each bond's timeline from its activation, one window at a time — each asked of the
timeline at the instant the last one ended, never worked out from a zone's midnight — and
inserts every ended window the bond has no row for as `EMPTY` with `closedAt` set,
`ON CONFLICT (bond_id, date) DO NOTHING`. A day that has already ended has no reason to
exist as `OPEN` first. If a submission opened the date a moment ago its row stands, the
insert does nothing, and the sweep settles that row like any other; if the job's row is
there first, the submission finds a settled day under its lock and is redirected once to
today (BR-3a) or refused. An entry is never filed on the closed day and never lost.

- **Not from the greatest existing date.** A row opened today says nothing about the week
  behind it; every window from activation is checked against the dates the bond has.
- **Never before the pairing** (doc 04 §8.3a) **and never after the bond stopped taking
  writes.** The day it stopped *on* is written: it began while the bond was live.
- **A bond counting down to deletion has stopped taking writes.** Its cooling-off month is
  not a run of days the couple missed, so the closer's view of a bond ends at the deletion
  request as well as at an archive (spec §6.4: "deletion or archived intervals").
- **A date an eastward change stepped over is `FROZEN`**, without spending a freeze
  (ADR-0031 decision 14): a row of no length at the moment the calendar moved past it,
  written as soon as that moment has passed.
- **At most 400 a run, across all bonds** — bonds in id order, each bond's days oldest
  first (spec §6.4). A run that stops there
  says so, and the next one resumes from whatever is still missing: nothing remembers
  where the last one got to.

**7. A joining day is reconciled before it is closed, and a closed one is never resumed.**
ADR-0032 offered either; both were built. Step 3 of decision 3 is the first.
`BondDay.resumeJoiningDay` refusing a day with `closedAt` set is the second: resumed after
closing, a one-entry day would be `PARTIAL` and closed at once, which nothing settles.

**8. `closedAt` and `revealedAt` are when it happened, read under the lock.** A run reads
the time once and may take minutes. Stamped from that reading, a day could be closed
"before" an entry written on it while the run was under way, and `DayClosed` would precede
the `EntrySubmitted` that caused it. The stamp is the clock inside the transaction, never
earlier than the run's own reading; the run's reading is only the threshold.

**9. `DayClosed` for every day settled, ids only, and none for a suspended day.** Aggregate
`BondDay`, payload `{bondId}`, written in the transaction that closes or creates the day.
A consumer reads the day to tell `SOLO` from `EMPTY` from `FROZEN`. A `SOLO` day gets no
`DayRevealed` and its `revealedAt` stays unset: that column records two people reading
together, which did not happen; the lone *entry* is revealed (FR-063). A `SUSPENDED` day is
excluded from evaluation, so closing it settles nothing anybody is waiting to hear about.

**10. One day failing does not stop the rest, and neither does one bond.** A day that
throws is counted, logged by id and exception class — never the message — and left for the
next run. The same holds per bond when missing days are written, which runs first: without
it, one bond whose calendar could not be read stopped every couple's days from closing.
The budget (5,000 a run) counts days closed, revealed or failed, not days looked at: a day
waiting on a reveal time hours away is a candidate on every run, and charged for, enough of
them sorted first would use the budget up every time.

**11. ShedLock on Postgres, and the lock is a courtesy.** One instance runs the job at a
time (spec §12.3: Postgres, because Redis fails open here). `lockAtMostFor` is a minute
short of the interval. If the lock is lost anyway, two runs overlap, and what keeps a day
from being closed twice is decision 3's row lock and `closedAt`, which `gratitude`'s own
tests hold. V16's `shedlock` table stores its instants **without** a time zone, unlike
every other table here: ShedLock's database clock writes a zoneless UTC value, and in a
`timestamptz` column that is read as local time. At UTC+1 a lapsed lock looked an hour
away and the job did not run.

**12. Two meters, and a third.** `gratitude.close.job.last.success.timestamp` is set on
every run that finishes, including the many that find nothing, so it goes stale only when
the job has stopped. `gratitude.bonds.closed` counts **days** settled — closed, revealed at
their time, or written — under the name doc 11 gave it. `gratitude.close.days.failed`
keeps what could not be settled apart: days, and bonds whose missing days could not be
written. A backlog, or any failure, is logged at `WARN`.

**13. The reaper for `Idempotency-Key` rows rides the same module** (ADR-0031, Owed):
hourly, at seven minutes past, under a lock of its own, deleting rows past their 24 hours.

**14. `BondAccess` gains two reads that run no membership guard**, because the closer has
no caller: `closingViewOf` (when the bond became two, when it stopped taking writes, when
its days reveal, its timeline — no member, no name) and `bondsToSweep` (bonds that have
ever had two members). They are the only two a request must never reach, and an
architecture rule lists the two classes that may name them.

## Consequences

- **Migrations V15 and V16**, so every later slice's migration is one later than spec §7's
  table said: streaks are V17.
- **The app now does something on its own.** `moyi.scheduling.enabled` (default on) turns
  the timer off for tests; `moyi.scheduling.close.cron` exists for the smoke run, which
  cannot wait a quarter of an hour.
- **Neither meter can be read by anyone yet.** Actuator exposes `health` only and there is
  no Prometheus registry on the classpath. The meters are registered and tested; exporting
  them is the deploy slice's. The gauge is per instance — an alert must take the maximum —
  and is zero until an instance's first run.
- **Every bond's dates are read on every run** to find its missing days. A query per bond
  per quarter-hour is nothing at the size this runs at, and is the first thing to replace
  when it is not: with a watermark that only advances past dates verified settled (spec
  §6.4). It is also unbudgeted, so the run's length grows with the number of bonds.
- **A day revealed while open is not extended** by a later westward change (`extendedTo`
  leaves a settled day alone) and is closed at its stored end. Nothing can be filed on it,
  so nothing is refused; the stored spans have a gap against §3.1's contiguity.
- **An unclosed day whose bond row is gone** is left and logged at `WARN` on every run.

## Owed

**C4, streaks.** Evaluate the streak for `CloseResult.bondsChanged` (spec §6.4 step 3).
The `FROZEN` row for a skipped date exists from the handoff on. `EMPTY` days written
through a cancelled deletion (question 2 below) will read as missed days.

**C5, the archive.** Question 1 below must be ruled before a past day can be read.

**The deploy slice.** Export the meters; alert on the gauge's maximum going stale and on
the counter staying flat for 25 hours (doc 11); a `lock_timeout` outside tests — the
closer, holding one day and no bond, is the writer least exposed to its absence.

**Not assigned.** `AcceptInvite` reads its clock before it takes the bond's lock, so a
pairing can be stamped earlier than it could have committed; the margin covers it unless
the wait for that lock outlasts the margin, and
reading the clock under the lock, as `ChangeTimezone` does, would shrink what it has to
cover. A westward zone change racing the closer is covered by the same margin and has no
test of its own; the pairing case does.

## Questions that are the owner's

1. **The closer reveals a lone entry on a bond that ended earlier that day.** Ada writes at
   ten; Bea leaves, or blocks her, at noon; at midnight the day closes `SOLO` and Ada's
   entry is revealed, as spec §6.4 asks ("`ARCHIVED` … cannot strand an earlier partial …
   day"). Ada cannot delete it: ADR-0032's first question, still open, is that an ended
   bond refuses the author's own delete. No route reads a past day yet, so nothing is shown
   to anyone in C3 — but the permission is stored, and C5's archive will honour it.
2. **A deletion that is called off leaves no record that it was ever counting down** (and
   one called off after a member has left archives the bond at the cancel, with the same
   effect).
   `deletionRequestedAt` is cleared on cancel. While the countdown runs no day is written;
   once it is cancelled the job sees a live bond and writes that month as `EMPTY` days — a
   month in which every write was refused. Recording the interval is a change to `bond`.
3. **A margin, not a lock** (decision 5): a minute's delay on every close, in exchange for
   keeping decision 18 whole — and a residual race, if a pairing waits more than a minute
   for the bond's lock across a midnight. The exact fix is a bond lock that never waits.

## Revisit when

- C4 lands and reads these days: questions 1 and 2 stop being hypothetical.
- The number of bonds makes the per-bond read of dates show in the run's length.
- A second instance is deployed: decision 11's "courtesy" gets its first real exercise.

## How this was checked

- **Run:** the build and the smoke run are recorded on the pull request, with the commit
  they ran at. The smoke run starts the job on a five-second schedule and checks that it
  took its lock, logged no failure of a day or a bond and left no ended day unclosed; no day in a smoke run
  has ended, so that proves the wiring and not a close.
- **Reproduced by a test that failed first, then fixed:** decision 5 (a day closed while
  its pairing was still committing — a count of one where zero was right); the deletion
  cooling-off in decision 6 (five days written where three are right); V16's column type
  (four lock tests failed at UTC+1 before the cause was known).
- **Found by breaking the code, not by a test written first:** the budget neither counted
  work nor stopped mid-page (decision 10). A mutation run showed both.
- **Mutations**, each a mechanism removed and a named test seen to fail, are listed on the
  pull request.
- **Read, not run:** that the closer cannot deadlock with any writer is a reviewer's trace
  of every lock sequence. Tests hold the closer against a submission, an edit and a delete
  in both orders, against a second closer, and against a held bond row. The insert race in
  decision 6 was traced in both orders and tested in one, sequentially. That the failure
  log omits an exception's message is not pinned: `CloseDay` already redacts what reaches it.
