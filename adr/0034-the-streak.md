# ADR-0034 — The streak

**Status:** Proposed · **Date:** 2026-10-05 · **Deciders:** Daniel

## Context

After C3 every day of a bond ends: `REVEALED`, `SOLO`, `EMPTY`, or `FROZEN` for a date a zone
change stepped over. Slice C4 turns those days into the number two people share (spec §6.5,
doc 04 BR-4 and BR-5, FR-070 to FR-076): a run that grows with every day both wrote, a
freeze earned every fourteen complete days that covers one missed day, a Strict mode that
turns freezes off, and a `recalculate` that must rebuild all of it from the days alone.

The spec gives the rules. It does not say when a day is judged, what is written down when
it is, or what a member is shown. Those are the decisions here. With C4 the daily loop is
complete, which is milestone **M3** — and M3 needs a way for two people to run it with no
app, so the slice also ships a CLI and a Bruno collection (doc 15 §4).

The slice was built in six tasks and then read whole by two independent reviewers. They
found one defect that showed a couple a number and then took a day from it, one way to
rescue a missed day after the fact, and a calendar that disclosed what the design system
forbids. "How this was checked" says which decisions came from that.

## Decision

**1. The rules are one pure function, and everything that judges a day calls it.**
`StreakRules.step(state, date, outcome, strict, recordedFreeze?)` takes a streak and one
day and returns the streak after it, whether a freeze was spent, and what changed. The
close job folds it over new days; `recalculate` folds it over old ones; a read applies it
once, to today. There is no second statement of BR-4 or BR-5 to drift from the first.

**2. A day is evaluated once, after it is settled, in date order, and never ahead of an
unsettled day before it.** The close job's first two steps can settle a bond's days out of
order: a missing day is written before an older row is closed, and a day that fails to
close stays open while later ones close. A streak is a fold, and a fold over days in the
wrong order is a different number. So evaluation is a third step (spec §6.4 step 3) that
takes the *prefix* of a bond's settled days and stops at the first that is not settled, or
does not directly follow the one before. Days from before the bond was two people are the
exception: they are whichever days the creator wrote on, need not be consecutive, and are
all `SUSPENDED`.

**3. Which bonds to evaluate is derived, not remembered.** Any bond with a day that is
closed and not evaluated. ADR-0033 owed C4 "evaluate the streak for
`CloseResult.bondsChanged`"; that set is what one run happened to touch, and a run that
died between closing and evaluating would leave days no later run was told about. The set
stays on the result, for the log.

**4. What was decided for a day is written on the day.** Four columns on `bond_days` (V17):
`evaluated_at`, `evaluated_as`, `evaluated_strict`, `freeze_applied`. Spec §6.5: "persist
the applied strict-mode and freeze events alongside day outcomes so recalculation never
substitutes today's setting for past decisions". `evaluated_as` is there because the status
is not enough: a missed day a freeze covered *becomes* `FROZEN`, the same status as a date
a zone change stepped over, and the two replay differently; and a day missed after the bond
ended (decision 7) has an ordinary status and moves nothing.

**5. What counts** (BR-4, BR-5).

| The day | The run | Counts toward a freeze |
|---|---|---|
| `REVEALED` | +1 | yes |
| `FROZEN`, a date a zone change stepped over (BR-6), **on a run** | +1 | no |
| `SOLO` / `EMPTY`, a freeze banked, not Strict, **and a run to save** | +1, the freeze is spent, the day becomes `FROZEN` | no |
| `SOLO` / `EMPTY` otherwise | ends | — |
| `SUSPENDED` | unchanged | — |

A covered day extends the run and is not a *complete* day: `totalCompleteDays`,
`freezeProgress` and `lastCompleteDate` do not move. The fourteenth complete day resets
progress and banks a freeze only outside Strict mode and below the cap of two; in Strict
mode it resets and banks nothing. Never the formula (BR-5). **A freeze is not spent on a
run of zero**: there is nothing to save, and spent there it made a streak of one out of a
day nobody wrote on. For the same reason **a stepped-over date keeps a run going and does
not start one**. The spec says neither; both follow from what a rest day is for.

The one change evaluation makes to a settled day is `SOLO`/`EMPTY` → `FROZEN`. The lone
entry on it was revealed when the day closed and stays revealed (spec §4).

**6. A day is judged by the Strict mode it ended under.** Evaluation runs after a day is
over — a minute after, or days after if the job was down — and either member can change
Strict mode alone, at once. Judged by the setting at evaluation, a couple could
miss a day in Strict mode, switch it off before the job ran, and have a freeze spent on
it; and after an outage every day in the backlog took the setting of the moment. FR-073:
"toggling Strict mode never alters past days".

`bond_strict_mode_changes` (V18) records every real change of the setting with its time,
written by `UpdateBond` under the bond's lock, and the closer's view of a bond answers
`strictModeBefore(instant)`: the setting in force up to that instant, not including it. A
day is `[startsAt, endsAt)`, so a change stamped exactly at a day's end belongs to the next
day. The view has no "Strict mode now" at all.

**A single `strict_mode_changed_at` column was built first and is not enough.** The third
review found the hole: off and on again, both after the day ended and before the job ran,
and the last change alone says "it was not strict before this". The rescue this decision
exists to prevent, at the cost of one more request. So it is a history. A bond with no row
has never changed the setting; days from before V18 are judged by the setting the bond has
when they are first evaluated, there being nothing else to judge them by.

**7. A bond that has ended keeps its streak** (doc 04 §8.3: "the streak freezes rather than
breaks — it is preserved at its value"). A day **missed** after the bond stopped taking
writes is recorded as `AFTER_THE_END` and moves nothing. Only a missed day: a day both
wrote on before one of them left that afternoon is a complete day like any other. §8.3
protects a streak from a break; it does not take a day from it.

**8. Today counts when it is complete, at read time, by the same function.**
`streak_states` holds the run through the last evaluated day. A read applies
`StreakRules.step` once more for today when today is `REVEALED` and directly follows the
last evaluated day (or is the bond's first shared day). So a couple at thirty who both
write see thirty-one now, not at midnight, and on a fourteenth day the freeze shows as
earned beside the fourteen — every number moves together because one function moved them.
Nothing else is computed at read time. A run that has lapsed reads zero because the job
wrote and evaluated the missed days, never because a read noticed a gap; and while the job
is behind, today is *not* added to a run that yesterday may have ended.

**9. The streak's row is its mutex.** Evaluation and `recalculate` take the bond's
`streak_states` row `FOR UPDATE` (inserted if absent) and hold it for the bond's
transaction. **It takes the bond's lock first**, as the closer does since ADR-0033 was
amended (`lockClosingViewOf`): the order is bond, then streak, then the days it writes, and
a change of Strict mode still committing is in, with its stamp, before a day is judged by
it. Nothing that holds a day waits for a bond or a streak. Each day's record also refuses a
second write (`evaluated_at IS NULL` in the update, and `streak_events` is unique on bond,
date and event), so without the lock a day is still counted once — but the second run
fails, and a failure is a bond left for the next run for no reason. One bond failing stops
no other.

**10. `recalculate` replays and announces nothing.** It folds decision 1's function over the
bond's evaluated days using decision 4's columns, under decision 9's lock, and writes
`streak_states` with `recomputed_at`. It touches no day and writes no event: a
recalculation that published would tell two people their streak grew, again. It is a
**service and not a route**: FR-074 calls it an admin operation and there is no admin
surface or role yet. The route arrives with `modules/admin`.

**11. Events.** `streak_events` is the audit log (doc 07): one line per day that changed the
run — `EXTENDED`, `FREEZE_CONSUMED`, `BROKEN` — and one for `FREEZE_BANKED`. The outbox
gets two (spec §8), in the evaluation's transaction, each carrying the bond's id and
nothing else:

- **`StreakExtended` only for a day both wrote on.** A covered day and a stepped-over date
  also extend the run; announced, they would celebrate a day somebody missed.
- **`StreakBroken` must never become a message to a member** (FR-076). To the one who
  wrote, "your streak ended" says the other did not. It is for the screen to state when
  opened (`states.md` §7) and for analytics. Phase 4's notification consumer must not
  subscribe to it.

`MilestoneReached` is C6's, with the milestones endpoint.

**12. The calendar has its own vocabulary.** *(As first built; the owner reversed the
treatment of solo days on 2026-10-06 — see Rulings, 2.)*
`GET /bonds/{bondId}/streak` returns the numbers and `days`, a square per date:
`COMPLETE`, `FROZEN`, `MISSED`, `OPEN`. Not the day's status. `states.md` §7: "Solo is not
rendered on the calendar ... a month-long ledger of days when exactly one person wrote is a
durable inference surface: you know your own history, so every Solo cell resolves to a
partner miss." So a `SOLO` day and an `EMPTY` day are the same `MISSED` square, and today
is `OPEN` until it is complete, whoever has written. (`GET /today` does share today's
status. That is one day, the product needs it, and spec §4 lists it as deliberately
shared; a year of them is a different thing.)

A square is drawn for a day once it is evaluated, and for today. **Today always has its
square while the bond takes writes**, whether or not it has a row yet: a day gets its row
with its first entry, and a square that appeared only then would itself say that somebody
had written. Suspended days and days missed after the end are not drawn. The calendar runs from the day the bond became two
people (spec §12.4: earlier days are the creator's alone), for at most 371 days — 53
weeks — and for an ended bond it ends where the bond did, so the record doc 04 §8.3 says is
kept does not scroll away. The route runs the joining-day reconciliation `GET /today` runs,
because a joiner's first request may be this one. A non-member gets the one `404`.

`GET /today` gains `streak: {current, longest, freezesAvailable, strictMode}` (doc 06 §3.4).

**13. The property tests are seeded random timelines in plain JUnit.** The rules are a fold
over a list; a generator is a seeded `Random` and a loop, and a failure prints its seed.
Spec §6.5 names four invariants. Two of them, as first written here, **could not fail**:
"replay is a fixed point" replayed with the same Strict-mode values it decided with, and
"toggling Strict mode alters no past day" is true of any fold. They are replaced by
properties that fail when the code is broken: a replay with Strict mode *inverted* on every
missed day still reproduces every state (so the decision is read from the record); a day
evaluated in Strict mode banks nothing and spends nothing; a freeze is only ever spent on a
run that exists. FR-073 itself is held where it can fail — decision 6, against a real
`PATCH`.

**14. M3's tooling.** `scripts/moyi` is the loop from a terminal: sign in, write, today,
streak, with the session in `~/.config/moyi/<profile>.json` at mode 600. **Nothing secret is
an argument of a process it starts** — the password, tokens and entry text reach `curl` and
`python3` through pipes and a private temporary directory, because arguments are readable
by every user of a machine. It refuses plain `http` to anything but this machine unless
told otherwise, and refuses an id or invite code that is not shaped like one before it is
put in a URL. `tools/bruno` is the same twelve requests for somebody who would sooner
click. Both decide nothing: every rule is the server's.

## Consequences

- **Migrations V17 (`gratitude`) and V18 (`bond`, a table).** C4 took two versions, so C5 and C6 each
  move one later than spec §7 said. Neither has been applied to a shared database.
- **The API gains a route and a field.** Additive: no new error code, no
  `breaking-api-change` label.
- **`BondClosingView` no longer exposes the current Strict mode**, only
  `strictModeBefore(instant)`. There is no way left to judge a day by today's setting by
  accident.
- **A bond is given a `streak_states` row the first time it has a day to evaluate.** A read
  of a bond with none answers zeros.
- **The evaluation reads every unevaluated day of a bond, oldest first.** After a long
  outage that is a bond's whole backlog in one transaction, and nothing bounds it per run.
- **A read is two statements with no lock.** An evaluation committing between them can
  make one response show yesterday's number. It cannot count a day twice: a today that
  has been evaluated is never added.
- **`states.md` and this API differ in three places**, recorded as gaps for the design
  system and not guessed at: the calendar has no field that tells a freeze-covered day from
  a stepped-over date (both are `FROZEN`, and the "we used a rest day" copy fits only the
  first); the length of a run that has just broken ("your streak ended at 23 days") is not
  sent, `longest` being the longest ever; and `states.md` §7's Strict-mode
  paragraph reads as if switching it on clears banked freezes, where FR-073 and BR-5 keep
  them unspent.

## Owed

**ADR-0033's "Owed, C4" is discharged** by decisions 2 and 3, with the one change decision 3
states.

**C5, the archive.** A day's page will show its status. Whether a `SOLO` day may say so
there is the same question decision 12 answers for the calendar, and `states.md` §6 should
be read before it is built.

**C6.** `MilestoneReached` and the milestones endpoint.

**`modules/admin`.** The route for `recalculate`.

**Phase 4.** The notification consumer must not subscribe to `StreakBroken` (decision 11).

**Not assigned.** Suspension of a member (doc 04 §8.1, §8.2): nothing sets it yet; the
rules already skip a `SUSPENDED` day, and a property test holds that for a run of any
length. The Bruno collection has not been opened in Bruno.

## Rulings — the owner, 2026-10-06

The four questions this record first asked, as ruled. The first three are built in the
pull request that follows the slice's.

1. **A deletion that is called off: its days are `SUSPENDED`.** `bond` now records the
   stretch (`bond_write_pauses`, V19) when a countdown is cancelled, including when the
   cancel archives the bond because a member left meanwhile. The closer's view answers
   `wasPausedDuring(startsAt, endsAt)`. A missing day that overlaps such a stretch is
   written `SUSPENDED`, not `EMPTY`, with no `DayClosed`; a day that already had a row and
   was missed (one member wrote on what was left of the day the deletion was called off)
   keeps its status and is *evaluated* as suspended. Either way the run goes on. Any part
   of a day counts: a day the bond refused writes for an hour of is not a day anybody can
   be said to have missed.
2. **Solo days are drawn on the calendar.** Decision 12 is reversed in this one respect:
   the vocabulary is `COMPLETE`, `FROZEN`, `SOLO`, `MISSED`, `OPEN`. A day one of the two
   wrote on, that ended and was not covered by a freeze, is `SOLO`. `states.md` §7's
   "Solo is not rendered on the calendar" is withdrawn; the inference it warned of — a
   member knows their own days, so a solo square names the other's — was weighed and
   accepted. Today is still only `OPEN` until complete. A solo day a freeze covered is
   `FROZEN`, and one that moved nothing (after the end, or in a called-off countdown) has
   no square, as before.
3. **FR-073 stands over `states.md` §7's Strict-mode paragraph.** Switching Strict mode
   changes no past day. The backend already did this; the drawn Strict-mode frame, which
   turns a past rest day into a missed one, is to be redrawn.
4. **A rest day keeps a run and does not start one** (decision 5): not ruled on, and stands
   as built.

## Revisit when

- A bond whose deletion was called off **before V19** has no record of it: those days
  stay as they were judged. None exists; nothing has been deployed.
- A second consumer of `StreakExtended` appears: it carries only the bond's id, by design.
- The number of bonds makes "every bond with an unevaluated day" show in the run's length.

## How this was checked

- **Run:** the build and the smoke run are recorded on the pull request, with the commit
  they ran at. The smoke run reads `/streak` and `today.streak` after two people write, on
  a throwaway database. No day in a smoke run ends, so it proves the read and the wiring,
  and **not an evaluation**: that is the integration tests', which run the job with an
  instant.
- **Found by a third review, of the fixes:** that one instant of Strict-mode history could
  be defeated with two requests (decision 6, now a table); that today's square existed
  only once somebody had written (decision 12); and "does not start one" in decision 5.
- **Found by review, not by a test written first:** decision 7's "only a missed day" (a day
  both wrote on, on the day the bond ended, was recorded `AFTER_THE_END`: the couple were
  shown 31 and then 30); decision 6 (Strict mode read at evaluation); "a run to save" in
  decision 5; decision 12 (the first calendar returned the raw status, `SOLO` included);
  decision 8's use of the one function (the first read added one to two counters and left
  the freeze numbers behind); `StreakExtended` for covered days in decision 11; and
  decision 13's two tests that could not fail. Each now has a test, and each test was seen
  to fail with its fix removed.
- **Mutations**, each a mechanism removed and a named test seen to fail, are listed on the
  pull request.
- **Read, not run:** that evaluation cannot deadlock with a writer — it takes the bond's
  row, then one `streak_states` row, then updates day rows by id; every other writer
  takes the bond first too, and no request takes a streak row.
  The CLI was run end to end against the jar; **the Bruno collection was not opened in
  Bruno.**
