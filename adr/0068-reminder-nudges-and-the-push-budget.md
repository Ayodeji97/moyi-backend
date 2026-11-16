# ADR-0068 — Reminder nudges, and the push budget that makes room for them

**Status:** Proposed · **Date:** 2026-11-16 · **Deciders:** the owner ruled the behaviour (issue
#200, 2026-11-15); the lead and the builder ruled the rest for him, **his to overrule**, each with
its cost (spec §3). · **Amends:** FR-082 (four per Bond per member per day; nudges 2 and 3 leave
room across Bonds), ADR-0044 decision 9 (`data.nudge`), ADR-0045 decisions 5 and 6 (the per-Bond
cap; the reminder is a series of up to three; `CANCELLED` also means superseded)

## Context

A member gets one reminder per Bond-day today (ADR-0045 decision 6). The owner asked for up to three:
at the reminder time, then every three hours until the member writes that Bond-day (issue #200,
moyi-app#111). FR-082 allowed three pushes per Bond per member per day, one reserved for the reveal,
so three nudges did not fit; and its cross-Bond cap of six in a rolling 24 hours, which applies to
reveals too, would let three Bonds' nudges spend the slots their reveals need. Issue #205 will add a
push that competes for the same budget.

The spec (`docs/superpowers/specs/2026-11-16-reminder-nudges-design.md`) holds the detail and the
arithmetic; this record is what was decided.

## Decision

✱ marks a departure from the corpus's wording or the lead's brief.

1. **A series of up to three nudges per member per Bond-day** (owner). Nudge 1 at the reminder time
   in the member's zone, as today; nudge *n* three real hours after nudge *n* − 1, while the member has
   not written that Bond-day. Found by the existing five-minute sweep (`ReminderJob`, ShedLock
   `daily-reminders`), which asks the roster about each nudge's own instant: no new job, and
   `DAILY_REMINDERS` (ADR-0067 decision 7) pauses every nudge. Nudges 2 and 3 are found by shifting the
   window back and reusing `ReminderTime`, so DST has one rule: on a night the clock changes between
   two nudges they are three hours apart, not three wall-clock hours.
2. **A series lies in one Bond-day.** Nudge *n* is due only if nudge 1 fell in the same Bond-day:
   a nudge past the day's end, in the late-entry grace (which lies wholly after it) or in a series
   whose nudge 1 fell before the bond began is never scheduled. Each nudge's `staleAfter` is its
   Bond-day's end, so one held past it expires (owner).
3. **Keys:** nudge 1 keeps `reminder:{memberId}:{date}`; nudges 2 and 3 add `:2`, `:3`. One rule,
   `ReminderKey`. Writing calls off the whole series still unsent, `REQUESTED` or `HELD`, in the
   entry's transaction.
4. **FR-082's per-Bond cap is four**, one still reserved for reveal-class; the remainder is three
   (owner). Milestones, on-this-day, partner-joined and proposals share it with the nudges, so on a
   three-nudge day they are dropped.
5. **The cross-Bond cap stays six; nudges 2 and 3 are soft** (lead). One is sent only if, after it,
   the member's rolling count leaves a slot for every Bond whose reveal for its current Bond-day is
   still owed. ✱ **The nudged Bond counts**, not only the others as the lead's brief read: the
   cross-Bond cap applies to reveals, and the nudged Bond's reveal is the one the nudge exists to
   bring about (spec §3 ruling 7 shows a reveal lost otherwise). `PushBudget.decide` gains one term:
   `sentAcrossBonds + leaveFreeAcrossBonds >= 6` → dropped; zero for every other push, which is
   today's rule exactly.
6. **"Owed" is computed when the nudge is asked for and kept on the row.** Owed: each `ACTIVE`,
   unpaused Bond of the member's user, wanting reveals, with a Bond-day at the nudge's instant, and no
   reveal-class push for that date in a final status in the ledger. `bond`'s roster supplies the
   Bonds and days, `notification` answers which are settled, `gratitude` subtracts. The consumer
   cannot read Bonds (it is a leaf), and a held push is decided again from its row alone, so the count
   rides on the row. Approximate when held overnight: bounded at one nudge per member and Bond-day by
   decision 7.
7. **A newer nudge supersedes an older one still unsent** (lead), so quiet hours release at most
   one. The superseding push **inherits the smallest leave-free of what it cancelled** (builder): a
   night's only reminder stays hard even if it is nudge 2's.
8. **`data.nudge`**: `"1"`, `"2"` or `"3"` on a nudge, absent on every other push and on reminders
   recorded before V68 (owner). Localisation keys, channel and collapse key unchanged.
9. **V68 adds `notifications.nudge` and `notifications.leave_free_across_bonds`**, additive and
   defaulted. No index. Not avoidable: decisions 5 to 8 need values on the row.
10. **No API contract change.** No HTTP route changes; preferences are unchanged (nudges are
    `REMINDER`s under the `reminder` toggle).
11. **Metrics:** `gratitude.reminders.nudges{nudge,outcome}`, `notification.push.settled{category,
    nudge,outcome}` (with `dropped_leaving_room` for decision 5's drop),
    `notification.push.cancelled{category,nudge,reason}`. Bounded tags, no ids.

## Consequences

**Positive:** a member who has not written is nudged up to three times, never after writing and never
about a day that has ended; a reveal is never dropped because nudges spent the cross-Bond cap; quiet
hours release one push, not a pile; nothing in a nudge depends on the other member (T-09): the only
other-member fact read, a reveal in another Bond, is one the member already sees there.

**Negative:** on a three-nudge day the per-Bond remainder is spent, and a milestone, on-this-day,
partner-joined or proposal push is dropped. A member in three Bonds gets no nudge 2 or 3 until a
reveal settles. A held nudge's count can be stale by a night. Up to three `REMINDER` rows per member
per Bond-day in the ledger, `SUPPRESSED` ones included for a member who turned reminders off. A bond
made after its member's reminder time has no nudges that first day.

**Neutral:** without nudges, the per-Bond cap of four gives every other category one more slot a day.
The existing race between a write and the sweep's read of it (one push owed to a member who has just
written) is unchanged, now possible per nudge.

## Alternatives considered

- **A new job for nudges.** A second lock and look-back for the same roster. Rejected for the sweep.
- **Wall-clock nudge times.** A second DST rule and a second once-a-day guarantee. Rejected (1).
- **Counting only the other Bonds** (the lead's wording). Loses the nudged Bond's reveal. Rejected (5).
- **Deciding "owed" at send time** through a port `notification` would call. Makes the leaf read
  Bonds and days (ADR-0044's rejected alternative). Rejected (6).
- **Deriving the index from the dedupe key.** A second statement of `ReminderKey`'s format in a
  module that must not know it. Rejected (9).
- **Keeping the older held nudge instead of the newer.** Equivalent in count; the lead ruled the
  newer, and decision 7's inheritance removes the difference that mattered.

## Owed (the owner decides)

- **The words of nudges 2 and 3**, and how they can differ at all: the OS renders the server's
  `push_reminder_*` keys for a background push, so `data.nudge` alone changes nothing on screen
  unless the app ships per-nudge keys the server then sends, or an iOS service extension with
  `mutable-content` (spec §13.1).
- **Three Bonds** (Negative): accept, or raise the cross-Bond cap for nudges.
- **Decision 5's ✱.**
- **Android stacking:** `android.notification.tag` would make a later nudge replace an earlier one in
  the shade, as iOS's collapse id already does.
- **#205's place in the remainder**: first-come as built; a per-Bond leave-free on nudge 3 is the hook
  if it should outrank it.
- **Corpus:** doc 03 FR-082 and FR-080.

## Revisit when

A member in three Bonds asks why they are nudged once; #205 lands; the roster read shows in the
reminder job's duration; the owner chooses per-nudge wording.
