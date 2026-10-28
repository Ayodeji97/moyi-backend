# ADR-0045 — Push: the member's toggles, quiet hours that hold, a budget by reservation, and the daily reminder

**Status:** Accepted · **Date:** 2026-10-28 · **Deciders:** Daniel

## Context

Phase 4 slice N2 (spec `2026-10-27-phase-4-media-and-notifications-design.md` §4 N2, D-2, D-14,
D-15, D-16; rulings Q7 and Q9) is the member's half of push: which kinds they want, when they
are not to be woken, how many pushes a day they can be sent, and the daily reminder to write.
N1 (ADR-0044) built the transport and left one named seam in the `notification.push`
consumer, where every push was treated as wanted, in hours and in budget. This replaces it.

Five things made it non-obvious.

**The module graph.** `notification` is a leaf (ADR-0044 decision 1): it cannot read a
member's settings, which live on `bond_members` (V9). Yet the spec's dispatch path (§2.2)
decides "category off", "in quiet hours" and "over budget" in that module's consumer.

**Quiet hours versus the reveal** (D-15). FR-061 wants both members told of a reveal within
60 s; FR-081 gives each member quiet hours. Q7 ruled: a reveal inside them is held to their
end, not dropped, and quiet hours are off by default.

**Which day the budget counts** (D-16). FR-082 caps pushes "per day", but a member has a
reminder zone per Bond (up to three, FR-025) and the user has none.

**The reservation** (FR-082). One slot per Bond per day is held for reveal-class pushes, and a
dispatcher that sees pushes one at a time must enforce that without knowing what comes later.
Two pushes for one member decided at once must not both take the last slot.

**The daily reminder.** It must remind a member who has not written, and never one who has,
and nothing about it may tell a member anything about the other (FR-076, T-09).

## Decision

**1. The toggles are a resource of their own, kept on `bond_members.notification_prefs`.**
`GET` and `PUT /api/v1/bonds/{bondId}/members/me/notifications`, body and answer
`{categories: {reminder, partnerJoined, reveal, milestone, onThisDay, proposal}}`. Not three
more fields on the settings `PUT`, which replaces the whole row and clears what it is not sent
(doc 06:111): a client that did not know a toggle would turn it off. Here **an absent toggle
keeps its value**; `categories` itself is required (422). Same posture as the settings
resource otherwise: `me` only, the guard first and a stranger's 404 (`BondCrossTenantTest`),
no `If-Match` (ADR-0029), the bond lock taken for a write, readable and not writable once the
bond has ended (409 `BOND_ARCHIVED`, ADR-0028).

- **Stored as chosen, read through the defaults.** V9 filled every row with `{}`. A missing
  key is the default, and **the defaults and the toggle-to-category rule are notification's**
  (`NotificationPreferences` in `notification.api`): everything on but on-this-day (Q7, FR-092
  opt-in). Bond stores and serves; it restates neither.
- **Plain JDBC beside the entity.** `BondMemberEntity` still leaves the `jsonb` column
  unmapped; one class (`MemberNotifications`) reads and writes it.
- **A `proposal` toggle, beyond the spec's five.** Q13 ruled a push for a pending proposal in,
  and FR-081 gives a member control of *which categories they receive*. Without a toggle,
  `PROPOSAL_OPENED` would be the one push a member could not turn off. On by default, as Q13
  wants it sent. A day that opened with one entry (`SOLO_UNLOCK`, Q8) is the reveal's news and
  is governed by `reveal`.

**2. Settings arrive resolved, and are kept with the push.** `PushRequest` gains a required
`PushRecipient(zone, quietHours, preferences)`, with no default so no caller can forget it.
`Notifications.request` keeps on the row (V30) what the consumer will need, as it stood when
the push was asked for: `zone` (the member's reminder zone in that Bond), `quiet_hours_start`
and `_end`, and `wanted` (the toggle's answer for this category, asked once, by
`NotificationPreferences.allows`). Who lives where:

| Value | Kept by | Reaches the consumer as |
|---|---|---|
| Category toggles | `bond_members.notification_prefs` (bond) | `notifications.wanted` |
| Quiet hours | `bond_members.quiet_hours_*` (bond, the settings `PUT`) | `notifications.quiet_hours_*` |
| Reminder zone | `bond_members.reminder_timezone` (bond) | `notifications.zone` |
| Budget counts | `notifications` (notification) | read under the lock at send time |

A change of settings after a push was asked for does not reach that push. For a push sent
within seconds that costs nothing; for one held overnight it means a member who mutes the
category at 02:00 still gets the reveal held from 23:00. Accepted: the alternative is a
notification module that reads bond's table (ADR-0044's rejected alternative).

**3. The consumer decides in a fixed order** (`SendPush.decide`): past `stale_after` →
`EXPIRED`; not wanted → `SUPPRESSED`; inside quiet hours, or before `not_before` → `HELD`;
over budget → `DROPPED_BUDGET`; otherwise sent. The deadline comes first, so a push held past
it is expired when it comes back. Each of these touches one member's copy only: the Bond-level
decision to tell both (ADR-0041 H-M5) is the caller's and is made once; a member's own mute,
quiet hours or spent budget hold back their copy and tell neither member anything about the
other (Q9, D-14). The test is `one member's mute, quiet hours or spent budget holds back their
copy only`.

**4. Quiet hours hold a push in the ledger, not in the outbox.** Inside quiet hours the
consumer sets the row `HELD` with `not_before` at their end, and **acknowledges** the
delivery. `HeldPushes.releaseDue`, called by `scheduling` every minute under ShedLock, puts
every `HELD` row whose `not_before` has passed back to `REQUESTED` (`FOR UPDATE SKIP LOCKED`)
and publishes `PushRequested` again, so a held push takes the same road and the same decisions
as a fresh one.

Chosen over the alternative the brief offered, a typed "not yet" the consumer throws and the
outbox backs off on, for three reasons found in the outbox's own code:

- the outbox records every throw as a failure: from the fifth it is counted in the
  `gratitude.outbox.failing` gauge, "the nearest thing to a dead-letter queue", and every
  retry is a WARN line. A night's hold would look like a broken consumer, every night;
- `gratitude.outbox.age.seconds.max` counts the oldest unhandled event **whether due or not**
  (ADR-0035), and is the gauge the stuck-event alert reads; an eight-hour hold would fire it;
- the backoff caps at fifteen minutes, so a reveal held to 07:00 would go at any time up to
  07:15. The release goes within a minute (plus the poller's two seconds).

`HELD` is a new, non-final status (V30 widens the CHECK). Quiet hours are judged **at the
moment of sending**, not of asking, so a push the outbox retries into the night is held too.

**The arithmetic** (`QuietHours`, pure): the window is wall-clock time in the member's zone,
`[start, end)`, wrapping midnight when start is after end. A wall time the clock skips is
never inside; an end the clock skips is reached at the transition, not an hour later; a wall
time read twice in an overlap is inside both times. A window whose start equals its end is
empty: as "all day" it would hold every push for ever. `QuietHoursTest` holds the named cases
and two properties over 3,000 seeded cases across Kathmandu and Chatham (45-minute offsets),
Lord Howe (a 30-minute DST shift), New York, London, Santiago, Sydney, Havana (DST at
midnight) and Lagos: a hold ends at the first instant outside the window and every probed
instant before it is inside.

**A held push still has its deadline.** A reminder is stale at the end of its bond-day, so one
held past midnight is `EXPIRED` when it comes back, never sent the next morning. A reveal's
deadline is N3's to set (the spec's: the end of the following bond-day), long enough for any
night. NFR-006's 60 s is measured outside quiet hours (D-15).

**5. The budget: reservation, its two days, and its lock** (`PushBudget`, pure).

- **Three per Bond per member per day, one of them reserved for reveal-class** (`REVEAL`,
  `SOLO_UNLOCK`). A reveal-class push takes the reserved slot first and the remainder after;
  anything else shares the other two. So FR-082's own example holds: three pushes before
  22:00 send two and drop one, and the 22:00 reveal still goes, in the reserved slot.
  `notifications.reserved` marks the push that took it; V30's CHECK allows it only on `SENT`.
  **The per-Bond total is checked before the reserved slot is given** (review of #99): three
  sent with the reserved slot unspent is a count the rule never makes, but the ledger can hold
  one, since the day is counted in the member's zone and a zone can change mid-day. Without the
  check that count granted a fourth push.
- **The per-Bond day is the member's day in that Bond's reminder zone** (D-16): 23 or 25 hours
  across a DST change, starting wherever that zone's midnight falls.
- **The cross-Bond cap is six in a rolling 24 hours**, because no one zone's day is the
  member's, and it **applies to every push, reveal-class included**, as the spec reads FR-082.
  The consequence, for the owner: in three Bonds whose remainders are all spent (six pushes),
  a seventh push that is a reveal is dropped though its own Bond's reserved slot is free.
  FR-082's reservation protects the reveal within a Bond, not across three. See Open.
- **Only sent pushes count.** Suppressed, held, dropped, expired and failed spent nothing.
- **Over budget is `DROPPED_BUDGET`, never queued** (FR-082).
- **Decided under `pg_advisory_xact_lock(4, hashtext(user_id))`**, held by the delivery's
  transaction until it commits, so a second push for the same member counts this one's slot
  as spent, or, if this one is retried, sees it given back with the rollback. **Namespace 4**:
  `1` is identity's sessions lock (ADR-0021), `3` the idempotency keys (ADR-0031), and `2` is
  taken twice already, by bond's "bonds of a user" lock (FR-025) and by N1's push-token lock
  (ADR-0044 decision 2, which calls it free). The concurrency test pauses the first send
  inside the provider with the lock held, and sees the second wait on that lock in
  `pg_locks` before it lets the first finish; with the lock removed the second sends too.
  **The time the budget counts from is read after the lock is taken** (review of #99): the wait
  can last a send, and a push that waited across the member's midnight counted against the day
  that had ended. The test holds the lock over a London midnight.

**6. The daily reminder: `scheduling` knows when, `bond` whose, `gratitude` who wrote.**

- `ReminderJob` (`scheduling`), at thirty seconds past every fifth minute, ShedLock
  `daily-reminders`, asks `gratitude.api.DailyReminders.remindDue(after, upTo)` about **the
  half hour that ended on the last five-minute boundary**: each run looks back over six of
  its windows (review of #99). A tick lost to a restart or a deploy is asked about by the next
  one, and the dedupe key makes the overlap ask for nothing twice (`DailyRemindersTest`: a
  missed tick, then two runs, one push each). Not "since the last run": a run that starts late,
  or on another instance, asks about the same half hour. An outage longer than that still
  loses reminders, bounded on purpose: a reminder more than half an hour after the time the
  member chose is noise (doc 25:69), and its `stale_after` (the end of the bond-day) bounds it
  in any case.
- `bond.api.ReminderRoster.remindersDueBetween` reads every active member of a bond that takes
  entries (`PENDING_MEMBER` or `ACTIVE`, not left) and keeps those whose
  `reminder_time_local` in `reminder_timezone` fell in the window (`ReminderTime`, pure: a
  wall time the clock skips falls at the jump, one read twice falls at its first reading, so
  every member is reminded once on every day of theirs, which `ReminderTimeTest` walks in
  five-minute ticks over six weeks across DST changes). Each comes with the bond-day by the
  bond's own anchor timeline and the member's resolved `PushRecipient`. **A reminder that fell
  before its bond began is not due**: it has no bond-day, and asked of the timeline it threw and
  failed the whole sweep, every member's reminder with it (found by the smoke run, a bond made at
  23:57 with a reminder at 23:55). It is unguarded, like
  the closer's view, and `ArchitectureTest` lets only the reminder sweep name it.
- `SendDailyReminders` (`gratitude`) asks, per member and in a transaction of its own,
  **whether that member has written** (an entry of theirs on that bond-day, not deleted) and
  otherwise requests a `REMINDER` push. **Whether the other member has written is never
  asked**, so neither the reminder's existence nor anything in it can say (FR-076, T-09); the
  push carries the localisation keys `push_reminder_*` and the Bond's id, nothing else (Q6).
  `stale_after` is the end of the bond-day.
- **Dedupe `reminder:{memberId}:{bond-day label}`**, not `{bondDayId}` as the spec writes: the
  bond-day's row is made by the first entry or by the close job, and the day a member is
  reminded on usually has none yet. A bond's labels are unique to it
  (`bond_days_bond_date_key`) and a member id to one bond, so the key names the same day.
  The rule lives in one place, `gratitude.domain.ReminderKey`, which both of its users ask.
- **Writing calls off one's own reminder** (review of #99). A reminder asked for before the
  member wrote can still be owed afterwards: a 06:30 reminder held by quiet hours to 07:00
  reaches a member who wrote at 06:45. So `SubmitEntry`, in the entry's own transaction, calls
  `Notifications.cancel` on that member's reminder key for the bond-day the entry landed on.
  A push still `REQUESTED` or `HELD` becomes **`CANCELLED`**; one already settled is left
  alone (V30's `sent_at` CHECK refuses a sent push being anything but `SENT`), and calling it
  off twice is calling it off once. The key stays spent, so a later sweep asks for nothing. It
  is the author's reminder only: the other member's is about their own writing (FR-076).
  **A status of its own rather than `SUPPRESSED`**: that one says the member turned the
  category off, and the ledger answers "why was this member not told" (ADR-0044). A reminder
  whose reason went away is a different answer, and reading it as a mute would be wrong. The
  cancel waits for a push the consumer has locked mid-send, then finds it settled or still
  owed, never both; the entry write never takes a lock the consumer holds while waiting for
  one of the write's, so there is no cycle.

## Consequences

**Positive.** A member's mute, night and budget are honoured per copy, and say nothing to the
other member. A reveal inside quiet hours arrives in the morning instead of never, and the
outbox's gauges stay true. FR-082's reservation is enforced by the same rule whatever order
pushes come in, and the lock makes it true under concurrency. The reminder needs no new table.

**Negative.** A setting changed after a push was asked for does not reach it (decision 2).
The cross-Bond cap can drop a reveal (decision 5). The roster reads every active member every
five minutes, a few thousand rows at the planned scale. The release is one more job with a
lock. A reminder whose tick fell in an outage longer than half an hour is lost. An entry write
may wait on a push the consumer is sending, for up to one send.

**Neutral.** `bond`, `gratitude` and `scheduling` now depend on `notification` (for `api`
values and calls; it remains a leaf). Gratitude's test context scans `notification` so that a
reminder test reads the real ledger. The app test JVM gets 1 GB instead of Gradle's 512 MB:
`SecretsNeverLoggedTest` raises every logger to TRACE and its console capture held 24 MB, and
with the module suites running beside it the suite ran out of heap, at N1's head as well as
here. The test also reads its capture once instead of once per watched secret.

## Alternatives considered

- **Toggles on the settings `PUT`.** Rejected in decision 1: a full replacement would clear
  them for every client that does not know them.
- **A user-level `/me/notification-preferences`** (doc 06:84). Rejected by D-2: everything else
  about notifications is per member, and the column exists.
- **Decide category and quiet hours at request time** and insert the row already `SUPPRESSED`
  or with a later `not_before`. Rejected: one path to every final status (ADR-0044 decision 4),
  and a push retried into the night would not be held.
- **A typed "not yet" backed off by the outbox.** Rejected in decision 4.
- **A precedence list for the budget.** FR-082 itself rejects it: the day's reminders would
  spend the budget before the reveal came.
- **The per-Bond cap counted in UTC, or in a rolling 24 hours.** Rejected by D-16: a member's
  day is their reminder zone's.
- **The reminder found by SQL on `reminder_time_local`.** Rejected for now: a zone's day and
  its DST changes are not a SQL predicate, and the pure rule is tested where it lives. A
  prefilter on the zone's current offset is the step if the roster grows.

## Open (for the owner)

- **Should the cross-Bond cap reserve too?** As built (the spec's reading), six non-reveal
  pushes across three Bonds can stop a seventh that is a reveal. Reserving one of the six per
  Bond the member is in would protect every reveal, at the cost of fewer reminders for members
  in three Bonds.

## Owed

- **N3** sets each push's `stale_after` and resolves recipients for the reveal, solo unlock,
  milestone, partner-joined, on-this-day and proposal pushes, through the same
  `PushRecipient`; `bond.api` gains the per-member resolution it needs then.
- **Metrics**: `gratitude_push_sent_total{category,status}` (ADR-0044 Owed) should count
  `HELD`, `SUPPRESSED` and `DROPPED_BUDGET` too.
- **ADR-0044 decision 2 says namespace 2 was free**; it is shared with bond's lock. Harmless
  (a hash collision would only serialise two unrelated requests), but the record is wrong.

## Revisit when

A member is in three Bonds and loses a reveal to the cross-Bond cap (Open). The roster read
is seen in the reminder job's duration. A client needs a setting change to reach a push
already held.
