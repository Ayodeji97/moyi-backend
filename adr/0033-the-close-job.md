# ADR-0033 — The close job

**Status:** Accepted · **Date:** 2026-10-05 · **Deciders:** Daniel
**Amended:** 2026-10-09 (the status; "C5, the archive" discharged — ADR-0036) · 2026-11-08
(decision 15, the late-entry grace, issue #132: decisions 4, 5, 6 and 9 now wait for it — see
"Amendment — 2026-11-08")

*(2026-10-09: the status read "Proposed" until this date, though the slice merged as #54 on 2026-10-06. Brought to the convention of ADR-0026 to ADR-0031, which say "Accepted"; noted in ADR-0036's slice.)*

## Context

Until this slice, a day ended only when somebody did something to it. A day one member wrote
on stayed `PARTIAL` for ever; a day neither wrote on had no row at all; and a couple with a
reveal time who both wrote before it stayed locked to each other (ADR-0032 decision 5).
Slice C3 is the part of the loop that runs without anybody submitting (spec §6.4): every
quarter of an hour a job settles the days that have ended, reveals the days whose time has
come, and writes the days nobody opened.

ADR-0031 and ADR-0032 left twelve obligations on it by name. The hard one was ADR-0031 decision 18, which ruled out a bond lock for the closer. A review
of PR #54 found that a one-minute settle margin could not protect a joining day when an
accept request waited longer than a minute for the bond lock. The C3 implementation is now
amended: both pairing and close serialize on the bond row, and close then takes its one
bond-day row.

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

**3. One day, one transaction, bond then day** (`CloseDay.settle`). The writers that hold
more than one day take them oldest first under the bond lock (ADR-0032 decision 6). Close
takes the same bond lock first, then only one day, so it cannot form a deadlock with them.
A failure costs one day, which is a candidate again on the next run.

Inside, in this order, and the order is the decision:

1. Read the day to learn its immutable bond id; lock that bond; then lock and reread the day.
2. Bring its span up to the bond's timeline (`extendedTo`). A row opened before a westward
   zone change still ends where the calendar said then.
3. Resume it if it is the joining day and apply the reveal rule — the same two steps, by
   the same code, the first request to meet the day would have taken. This is also the
   second sweep of spec §6.3: a `PENDING_REVEAL` day whose time has come is revealed here
   whether or not it has ended.
4. Only now ask whether the day has ended.
5. Close it: `OPEN` to `EMPTY`, `PARTIAL` to `SOLO` with the lone live entry revealed
   (unless the bond ended first: decision 9, as amended), `PENDING_REVEAL` to `REVEALED`. A day revealed while it was open, and a `SUSPENDED` day,
   gain `closedAt` and nothing else.

*Amended 2026-10-07 (ADR-0035 decision 14).* Between steps 1 and 2 the closer first erases a
withdrawn author's live entries on the day, by the routine `DELETE` uses, and goes on from
the day that leaves.

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

**5. A day is settled only once it has been over for a minute.** *(Amended 2026-11-08,
decision 15: for its twelve-hour late-entry grace and a minute.)* The one-minute margin
absorbs ordinary clock skew between instances. It is no longer relied on to cover a
transaction waiting for the bond lock: pairing and timezone changes serialize with close on
the bond row. `AcceptInvite` captures `joinedAt` after it acquires that lock and rechecks
invite expiry then, so if close gets the lock first, activation cannot be backdated into the
closed interval. The job still fires one minute past each quarter; a reveal falls due at
`now` itself and is not delayed by the settle margin.

*Rejected:* taking the bond row `FOR SHARE SKIP LOCKED` under the day lock and returning
"not yet" when held. That reverses the application's bond-then-day order and can deadlock
with a writer; the closer instead takes the ordinary bond lock before the day lock.

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

*Amended 2026-10-06, the owner's ruling on question 1.* The lone entry is **not** revealed
when the bond stopped taking writes before the day ended (`BondClosingView.endedAt` earlier
than the day's `endsAt`). The day still closes `SOLO` and still gets its `DayClosed`; the
entry keeps no `revealedAt`, and only its author reads it. A day that ended before the bond
did reveals as before. This supersedes spec §6.4 step 2's "`ARCHIVED` or `PENDING_DELETION`
… cannot strand an earlier partial … day" for the unlock, and only for the unlock: the day
is still settled.
*(Amended 2026-10-22, ADR-0040: "stopped taking writes" and `endedAt` are narrowed to
`BondClosingView.separatedAt`, the archive or a member's leaving; a countdown alone does not
withhold the entry.)*

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

**C4, streaks.** *Discharged 2026-10-05, ADR-0034 decisions 2 and 3 — with one change: the
bonds to evaluate are read from the days, not taken from `bondsChanged`.* Evaluate the
streak for `CloseResult.bondsChanged` (spec §6.4 step 3).
The `FROZEN` row for a skipped date exists from the handoff on. `EMPTY` days written
through a cancelled deletion (question 2 below) will read as missed days.

**C5, the archive.** Question 1 below must be ruled before a past day can be read.
*(Discharged 2026-10-09. Question 1 was ruled on 2026-10-06 and is decision 9 as it now
reads. The archive honours it without reading the day's status: such an entry has no
`revealedAt`, so its day is listed for its author alone — ADR-0036 decisions 1 and 2.)*

**The deploy slice.** Export the meters; alert on the gauge's maximum going stale and on
the counter staying flat for 25 hours (doc 11); configure a `lock_timeout` outside tests.

**Resolved in the 2026-10-05 amendment.** `AcceptInvite` now samples `joinedAt` after it
gets the bond lock and checks invite expiry against that instant. Pairing and timezone
changes serialize with close. The one-minute margin absorbs ordinary clock skew.

## Questions that are the owner's

1. **The closer does not reveal a lone entry on a bond that ended before the day did.**
   *Ruled by the owner on 2026-10-06, replacing the first reading of spec §6.4.* Ada writes
   at ten; Bea leaves, or blocks her, at noon; at midnight the day closes `SOLO` and Ada's
   entry stays unrevealed. The day still counts as written (`SOLO`); only the unlock is
   skipped, because the partner is gone and the author wrote for a bond that was still hers.
   "Ended" is the closer's view (`BondClosingView.endedAt`: archived, or counting down to
   deletion), compared with the day's own end. A day that ended before the bond did still
   reveals. C5's archive must read a closed `SOLO` day with no `revealedAt` as private to its
   author *(discharged 2026-10-09: "Owed", C5, above, and ADR-0036 decisions 1 and 2)*. This does not change ADR-0032's first question (an author cannot delete on an
   ended bond), which stays open.

   *(Amended 2026-10-07: ADR-0032's first question was ruled on 2026-10-06 and built in
   ADR-0035 decision 15: the author may delete.)*

   *Still the owner's, and wider than the ruling:* "ended" includes a deletion that is
   counting down. A lone entry on a day that ends inside a countdown is not revealed, and
   if the deletion is then called off the couple are still together and that entry stays
   readable by its author alone, with nothing that releases it. Private is the safe side;
   narrowing the check to an archived bond needs the closer's view to say which of the two
   it is. No test pins the countdown case either way.
   *(Amended 2026-10-22, ADR-0040: it is pinned now. `CloseDayTest`: "a lone entry on a day that
   ended while a deletion counted down is revealed - a countdown is not a separation", "a lone
   entry on a bond separated after the day ended is revealed", "a lone entry is withheld when a
   member left during a countdown before the day ended" and "a lone entry stays withheld when
   the deletion is called off after a member left in it".)*

   *Ruled by the owner on 2026-10-21, to be built: only an archived bond counts as ended
   for the lone-entry rule; a deletion that is counting down does not.* The closer's view
   must then say which of the two an ending is; today `endedAt` covers both. V19's
   `bond_write_pauses` (ADR-0034 ruling 1) is written only when a countdown is cancelled, so
   it cannot tell a running countdown from an archive by itself; the builder decides the
   source. The ruling covers the lone-entry reveal rule and nothing else. Reads gated on the
   bond taking no writes are unchanged by it: `prompt` stays `null` on `GET /today` while a
   deletion counts down (ADR-0038 ruling 6).
   *(Built 2026-10-22, ADR-0040. The closer's view gained `separatedAt`: the earliest of the
   bond's `archivedAt` and any member's `leftAt`. It is not `archivedAt` alone, as the brief
   said: a leave or block during a countdown stamps only `leftAt`, and a cancel later
   archives at the cancel time, so `archivedAt` alone would reveal the lone entry of a
   couple one of whom had already gone. `endedAt` is unchanged and still includes a
   countdown.)*
2. **A deletion that is called off leaves no record that it was ever counting down** (and
   one called off after a member has left archives the bond at the cancel, with the same
   effect).
   `deletionRequestedAt` is cleared on cancel. While the countdown runs no day is written;
   once it is cancelled the job sees a live bond and writes that month as `EMPTY` days — a
   month in which every write was refused. Recording the interval is a change to `bond`.
3. **A margin plus serialization** (amended decision 5): one minute on every close for
   ordinary clock skew, with bond-then-day locking to serialize lifecycle changes and
   pairing. The exact long-wait race is covered by the PR #54 regression test.

   *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.*

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


## Amendment — 2026-10-05: serialize close and pairing

PR #54 review identified a concrete lost joining-day race: `AcceptInvite` sampled activation
time before waiting on the bond lock, while close could settle the suspended day after the
one-minute margin. The regression test queues both operations behind a held bond row, advances
the clock beyond the margin, and verifies close waits and activation is timestamped after the
lock is acquired. The code now uses bond-then-day locking in `CloseDay` and records
`joinedAt` after `AcceptInvite` obtains the bond lock. This supersedes the no-bond-lock ruling
for the existing-day close path. `CreateMissingDays` still uses its unique conflict handling
for rows it inserts directly; it does not close an existing day.

## Amendment — 2026-11-08: the late-entry grace (issue #132)

**The owner's ruling, 2026-11-08** (FR-045 and BR-3a amended in the corpus the same day): an
entry written offline before a Bond-day ends and sent within twelve hours counts for the day
it was written, and a Bond-day is not closed until twelve hours after it ends. Before this,
BR-3a's offline-draft rule was unreachable as built: this job settled every day a minute after
its end, so an entry written at 23:00 offline and sent at 07:00 found its day closed and was
filed under the day of sending (BR-3a's third clause), and the lone entry the partner had
written was already unlocked and the streak already broken.

**15. A day is settled once its late-entry grace has run out, and a minute more.** The grace
is `moyi.gratitude.days.late-entry-grace` (default `PT12H`), read once into a
`LateEntryGrace` bean (`gratitude.service.DayProperties`); every reader takes the bean. Two
rules use it, and they are the same length by construction:

- `DayAssignment.resolve` trusts an `intendedAt` no older than the grace before the
  submission (BR-3a's window, thirty-six hours until this ruling; ADR-0031 decision 20 as
  amended). There is no separate "offline window" any more.
- `CloseElapsedDays` passes `now − grace − SETTLE_MARGIN` as the threshold to both of its
  steps: the candidates (decision 4) and `CloseDay`'s "has it ended?" (decision 3 step 4),
  **and** `CreateMissingDays` (decision 6). The last is not optional. A day nobody had opened
  would otherwise be written closed `EMPTY` a minute after midnight, and a late entry meant
  for it would find it settled and be filed on today — the fault this amendment removes,
  through the other door.

Because the two lengths are one, a claim the first rule accepts lies inside a day that ended
after the claim, so less than the grace ago, and the second rule has not settled it. The
recheck under the day lock (ADR-0031 decision 20) still stands for what can still settle a
day early: an instance whose clock runs ahead, and a `REVEALED` day (settled by the reveal,
not the close). `CloseRaceTest` holds a late submission against the job at the boundary, in
both orders, synchronised on the bond row: first, it lands on its day and the job closes the
day with it; second, it finds the day settled and is filed on today, never on the settled day.

**What waits for the grace, and what does not.**

- **Decided once, after it:** the close itself (`OPEN` → `EMPTY`, `PARTIAL` → `SOLO`, a
  `PENDING_REVEAL` day past its time → `REVEALED`), the lone entry's unlock to the partner
  (FR-063, decision 9), the missed day, the rest-day spend and the streak (ADR-0034: the
  evaluation runs only on settled days), and `DayClosed` with every push that follows from it.
  Nothing is recomputed afterwards: a day is still closed and evaluated once.
- **Not delayed:** a reveal. Two entries reveal the day the moment the second lands, late
  or not, and a `PENDING_REVEAL` day is still revealed by this job when its time comes
  (decision 3 step 3 is unchanged). The reveal gate is unchanged too: a late entry is a
  member writing, so it is what lets them read the other's — the partner of a lone writer
  cannot read the lone entry during the grace without writing first, because the unlock that
  would have shown it at midnight now waits for the close.
- **Judged by the day's end, not by the close:** whether the bond separated before the day
  ended (decision 9 as amended, ADR-0040) and the Strict mode a day ended under (ADR-0034
  decision 6). A later close changes neither question's answer.
- **Time-zone changes** (ADR-0031): a confirmed change takes hold at the end of the current
  Bond-day, and the timeline is history, so yesterday's window during its grace is the one it
  had; a westward merge lengthens the *new* day, never the one in its grace. The grace is
  counted from the day's own end, whatever its length.
- **A rejected claim is unchanged:** an `intendedAt` older than the grace, ahead of the clock,
  before the bond, or on a settled day still falls back to the day of submission.

**What a reader sees.** `GET /streak` draws every undecided day — today, and an earlier day
inside its grace — `OPEN` until it has revealed (`COMPLETE`), with a new required, nullable
`settlesAt` on each square that is set exactly on an `OPEN` one; and the shown run adds every
undecided day that has revealed, oldest first, while each follows the last evaluated day
(ADR-0034 decision 8 as amended). `GET /today`'s `bondDay` gains a required `settlesAt`: when
today settles. `GET /days` shows yesterday as it is (`PARTIAL`, say) until it settles.

**Consequences.**

- A lone entry is unlocked to the partner, and a missed day is announced, about twelve hours
  later than before — noon or so the next day, in the bond's zone. `DayClosed` pushes arrive
  then rather than just after midnight.
- **The monitoring check had to move with it.** `ops/monitoring/close-check.sh` alarmed on any
  day ended more than thirty minutes ago and still unclosed; every day now is, for twelve
  hours. It now allows the grace (`LATE_ENTRY_GRACE`, default `12 hours`). The new script must
  be on the server before the image that carries this (ops/monitoring/README.md).
- No migration: no stored value changes meaning. Days closed before this ships stay closed.
- The skipped date of an eastward change (decision 6) is written once the day before it has
  settled, so up to twelve hours later than before; nothing reads it sooner, because the
  streak cannot be evaluated past the unsettled day in front of it.

**Checked:** `LateEntryGraceTest`, `DayAssignmentTest`, `CloseElapsedDaysTest` (a day left open
through its grace takes a late entry and is decided once; a day nobody opened is not written
inside it), `CloseMatrixTest` (every zone's day is still `PARTIAL` a minute after its end and a
second before its grace runs out), `CloseRaceTest` (the boundary race, both orders),
`StreakEndpointTest` (yesterday `OPEN` with its `settlesAt`, completed late and counted once),
the smoke run's late-entry section (`scripts/smoke.sh`).
