# Reminder nudges: every three hours until the member writes, at most three a Bond-day

**Status:** Draft, 2026-11-16, lead rulings folded in the same day. Issue #200. The owner's rulings
in the issue are fixed (§3, A); the lead's rulings (§3, B, and those marked "lead ruling
2026-11-16, owner may revise") and the builder's (§3, C) are made for the owner and are **his to
overrule**; each says what it costs if it is wrong. ✱ marks a departure from the wording of the
issue, the corpus or the lead's brief.

**Requirements served:** FR-080 (the daily reminder), FR-081 (toggles, quiet hours), FR-082 as
amended here (the push budget), FR-076 and doc 09 T-09 (nothing in a push says anything about the
other member), FR-084 and T-14 (no content in a push).

**Corpus this builds on:** ADR-0044 (the ledger, the `notification.push` consumer, the payload,
decision 9 and its amendment of 2026-11-10: `data: {bondId, kind, date}`), ADR-0045 (toggles, quiet
hours held in the ledger, the budget by reservation, the reminder: `ReminderJob`, `ReminderRoster`,
`SendDailyReminders`, `ReminderKey`, writing calls a reminder off), ADR-0046 (which events push),
ADR-0067 decision 7 (`DAILY_REMINDERS`, the clamped window), BR-3a and FR-045 (the twelve-hour
late-entry grace, `LateEntryGrace`). The client's request is moyi-app `docs/backend-contract-gaps.md`
row 44 (moyi-app#111). Issue #205 (a `SHARED_SETTING_CHANGED` push) competes for the same budget
and is **not** designed here; §3 C14 says what this leaves for it.

## 1. Why this slice

Today a member gets **one** reminder per Bond-day, at their own reminder time, if they have not
written (ADR-0045 decision 6). The owner wants up to three: the first at the reminder time, then one
every three hours until the member writes that Bond-day. FR-082 caps a member at three pushes per
Bond per day with one reserved for the reveal, so three nudges cannot fit beside the reveal without
amending it; and six across Bonds in a rolling 24 hours, which three Bonds' nudges (nine) would
overrun, taking the reveals with them.

What the code does now, read for this spec:

- `ReminderJob` (`scheduling`), every five minutes at :30 under ShedLock `daily-reminders`, asks
  `DailyReminders.remindDue(after, upTo)` about `(boundary − 30 min, boundary]`, the start clamped to
  the moment `DAILY_REMINDERS` was switched on (`windowStart`, `PausedWindow.kt`).
- `ReminderRosterAdapter.remindersDueBetween` reads every member of a bond in `PENDING_MEMBER` or
  `ACTIVE` who has not left (`MemberNotifications.reminderCandidates`), drops paused bonds' members
  (A3), keeps those whose `reminder_time_local` in `reminder_timezone` fell in the window
  (`ReminderTime.occurrenceIn`: a skipped wall time falls at the jump, a doubled one at its first
  reading), drops one that fell before its bond began, and labels each with the Bond-day it fell in
  on the bond's anchor timeline (`dayBoundsAt`), with `bondDayEndsAt`.
- `SendDailyReminders`, one transaction per member: written that Bond-day (`WrittenToday`) → nothing;
  otherwise `Notifications.request` a `REMINDER` with `dedupeKey = ReminderKey.of(memberId,
  bondDate)` (`reminder:{memberId}:{date}`), `staleAfter = bondDayEndsAt`, `bondDate`.
- `SubmitEntry` calls `notifications.cancel(ReminderKey.of(memberId, day.date))` in the entry's
  transaction: a `REQUESTED` or `HELD` reminder becomes `CANCELLED`.
- `SendPush` decides, in order: past `stale_after` → `EXPIRED`; not wanted → `SUPPRESSED`; inside
  quiet hours → `HELD` until they end; then `PushBudget.decide` under
  `pg_advisory_xact_lock(4, hashtext(user_id))`: `ACROSS_BONDS = 6` over a rolling 24 h (sent rows
  only, reveal-class included), `PER_BOND_PER_DAY = 3` in the member's reminder-zone day,
  `RESERVED_PER_BOND_PER_DAY = 1` for `REVEAL`/`SOLO_UNLOCK`.
- `FcmPushSender` sends `data: {bondId, kind, date}`, OS-rendered localisation keys
  `push_reminder_title/_body`, Android channel `reminder`, collapse key `REMINDER:{bondId}`
  (`apns-collapse-id` on iOS, `collapse_key` on Android).

## 2. Goals, non-goals, anti-goals

**Goals**
- A member who has not written a Bond-day is nudged at their reminder time, three hours later and
  six hours later, each only if still unwritten and still inside that Bond-day.
- Writing calls off every nudge of that Bond-day still unsent; quiet hours release at most one.
- FR-082: four per Bond per member per day, the reveal slot still reserved; the cross-Bond cap of six
  still holds, and the extra nudges never take the slot a reveal will need.
- `data.nudge` (`"1"`, `"2"`, `"3"`) on every nudge.
- On both platforms a Bond-day's later nudge replaces the earlier one on the phone.

**Non-goals:** the words of nudges 2 and 3 and per-nudge localisation keys (owed, §13); a new toggle (nudges
are reminders, under the `reminder` toggle); a nudge interval or count a member chooses; #205.

**Anti-goals (a failure even if it works):** a nudge that depends on, or says anything about, the
other member (T-09); a nudge after the member has written that Bond-day; a reveal dropped because
nudges spent the cross-Bond cap; a pile of held nudges released together in the morning; a nudge
about a Bond-day that has ended.

## 3. The rulings

Each: the decision; why; what it costs if wrong.

### A. The owner's (issue #200, fixed)

1. **Nudge 1 at the member's reminder time** (`reminderTimeLocal`, `reminderTimezone`, as today),
   **then every three hours until they write that Bond-day, at most three per Bond-day.**
2. **FR-082's per-Bond cap goes from three to four**; the reveal slot stays reserved.
3. **Quiet hours still hold a nudge; one held past the end of its Bond-day expires**
   (`staleAfter = bondDayEndsAt`, as today).
4. **Each nudge has its own dedupe key.**
5. **`nudge` (1, 2 or 3) in the push `data`.**
6. **Neutral content**: a nudge depends only on the member's own writing and says nothing of the
   other member (FR-076, T-09).

### B. The lead's (2026-11-16; the owner may revise)

7. **The cross-Bond cap stays six; nudges 2 and 3 are soft.** Nudge 1 is decided as today's
   reminder is. A soft nudge is sent only if, after it, the member's rolling 24-hour count still
   leaves one slot for each Bond whose reveal for its current Bond-day has not been settled.
   ✱ **The lead's brief said "each of their OTHER Bonds"; this spec counts the nudged Bond too**
   (*lead ruling 2026-11-16, owner may revise*: accepted on this table).
   The cross-Bond cap applies to reveal-class pushes (ADR-0045 decision 5), and the nudged Bond's
   reveal is the one the nudge exists to bring about. Counting only the others loses a reveal in the
   very case the rule protects. Two Bonds, both reminders at 08:00, the member writing in neither
   until the afternoon:

   | | count before | owed (all) | owed (others) | all: sent? | others: sent? |
   |---|---|---|---|---|---|
   | 08:00 A1, B1 (hard) | 0, 1 | | | yes, yes (2) | yes, yes (2) |
   | 11:00 A2 | 2 | 2 | 1 | 2+2<6 yes (3) | 2+1<6 yes (3) |
   | 11:00 B2 | 3 | 2 | 1 | 3+2<6 yes (4) | 3+1<6 yes (4) |
   | 14:00 A3 | 4 | 2 | 1 | 4+2=6 **no** | 4+1<6 yes (5) |
   | 14:00 B3 | 4 / 5 | 2 | 1 | **no** | 5+1=6 no |
   | evening: A's reveal, B's reveal | 4 / 5 | | | 5, 6: **both sent** | 6, then **B's dropped** |

   *Cost:* a member in two Bonds gets one fewer nudge; one in three Bonds gets no nudge 2 or 3 at all
   until a reveal settles (08:00 → 3 sent; 3 + 3 owed = 6). **Accepted as a recorded consequence**
   (*lead ruling 2026-11-16, owner may revise*). How "owed" is computed is ruling C17.
8. **Quiet hours: a newer nudge supersedes an older one still unsent** for the same member and
   Bond-day, so at most one nudge is released when the hours end. *Cost:* the member sees nudge 2's
   (or 3's) index on a push that is the first they see that day; §3 C11 keeps its budget hard.
9. **Writing calls off all of that Bond-day's nudges**, `REQUESTED` and `HELD`, in the entry's
   transaction. *Cost:* none.
10. **A nudge that would fall after its Bond-day ends is never scheduled**, nor, therefore, one in
    the late-entry grace: the grace (BR-3a, twelve hours) lies wholly after `bondDayEndsAt`, so "after
    the end" already covers it. A member may still write the day during the grace; they are not
    nudged to. *Cost:* a member whose reminder is 22:00 gets one nudge; 20:00, two (with the anchor
    zone the reminder zone).
11. **Milestones, on-this-day, partner-joined and proposals still share the remainder.** With four
    per Bond and one reserved, the remainder is three, and three nudges spend it: on such a day those
    pushes are `DROPPED_BUDGET`. Accepted and recorded (ADR-0068 Consequences). *Cost:* on a day the
    member does not write until late, a milestone push is lost (the milestone itself is not).

### C. The builder's (for the owner; each his to overrule)

12. **The series is anchored at nudge 1 and lies in one Bond-day.** Nudge *n* falls at nudge 1's
    instant plus 3 h·(*n* − 1), **elapsed time**. It is due only if nudge 1's instant is at or after
    the start of the Bond-day containing nudge *n* (`dayBoundsAt(nudgeAt).startsAt`). One rule gives
    three results: a nudge past the day's end is in the next day, whose start is after nudge 1 (ruling
    10); a series whose nudge 1 fell before its bond began has none of its nudges either (the first
    day starts at the bond's creation, `max(naturalStart, effectiveFrom)`); and a nudge's Bond-day,
    key and `staleAfter` are its series'. *Cost:* a bond made at 09:30 by a member whose reminder is
    08:00 gets no nudges that first day.
13. **DST: nudge 1 keeps `ReminderTime`'s rule; nudges 2 and 3 are three and six real hours after
    it.** The roster finds nudge *n* by asking `ReminderTime.occurrenceIn` about the window shifted
    back by 3 h·(*n* − 1), then adding the shift back, so there is one DST rule, already tested over
    six weeks of ticks. This equals "reminder time + 3 h in the member's zone" on every day with no
    clock change between nudge 1 and nudge *n*; it differs only for a reminder in the hours before a
    change (00:30 on a spring-forward night: nudge 2 at 04:30 wall time, three hours later). The
    alternative, wall-clock times, would need its own gap and overlap rule (a 01:30 read twice) and a
    second copy of the once-a-day guarantee. *Cost:* the issue's "08:00, 11:00, 14:00" reads as wall
    times; on a clock-change night it is "three hours apart" instead.
14. **No new job.** The reminder sweep finds nudges 2 and 3 in the same five-minute run, the same
    lock and the same thirty-minute look-back (ADR-0045): a missed tick is caught up, and the keys
    make the overlap ask for nothing twice. **`DAILY_REMINDERS` covers every nudge** with no change to
    `ReminderJob`: the clamp applies to each nudge's own instant, so a nudge that falls while the flag
    is off is never sent, and one that falls after it is switched on is (nudge 1 at 08:00 lost to a
    pause, nudge 2 at 11:00 sent). *Cost:* the roster computes three occurrences per candidate per
    run instead of one (pure arithmetic, a few thousand candidates).
15. **Keys.** Nudge 1 keeps today's key, `reminder:{memberId}:{date}`, so a reminder in flight at the
    deploy is still called off by writing and is not asked twice on the deploy day. Nudges 2 and 3 are
    `reminder:{memberId}:{date}:2` and `:3` (58 characters at most, inside the 200 of V29). The rule
    lives in `ReminderKey` (`of(memberId, date, nudge)`, `series(memberId, date)`). The keys also
    bound a day at three whatever else changes: a member who moves their reminder time mid-day gets
    each index at most once. *Cost:* such a member may get an index out of its usual spacing (nudge 1
    at 08:00, then a 10:00 reminder time whose nudge 2 falls at 13:00).
16. **A nudge that supersedes an unsent one takes over its budget claim** (*lead ruling
    2026-11-16, owner may revise*: accepted). Ruling 8 cancels the
    older nudge; ruling 7 makes the newer one soft. Together they would turn a night's only reminder
    (nudge 1 held from 05:00 to 09:00, superseded at 08:00 by nudge 2) into a soft push that the cap
    may drop, leaving the member nothing. So the superseding push inherits the **smallest**
    "leave free" of the rows it cancels (ruling C17's column): superseding a hard nudge 1 makes it
    hard. Done in `notification`, in the one statement that cancels and returns what it cancelled, so
    a chain (3 supersedes 2, which superseded 1) carries through. *Cost:* one `RETURNING` column.
17. **"Owed a reveal", computed at request time, carried on the row** (ruling 7's term).
    - *Which Bonds:* every Bond of the member's user, the nudged one included, that is `ACTIVE` (two
      members: a Bond waiting for its second member has no one to reveal with), not paused (A3: it
      cannot be written), in which that user's member row wants reveals
      (`preferences.allows(PushCategory.REVEAL)`: one turned off will be `SUPPRESSED`, not sent), and
      which has a Bond-day at the nudge's instant.
    - *Settled:* a reveal-class push (`REVEAL` or `SOLO_UNLOCK`) to that user in that Bond with
      `bond_date` = that Bond-day, in a final status (`SENT`, `SUPPRESSED`, `DROPPED_BUDGET`,
      `EXPIRED`, `FAILED_PERMANENT`). `REQUESTED` and `HELD` are still owed. A Bond-day not yet
      revealed has no row and is owed.
    - *Where:* `bond` already loads every candidate each run, so the roster adds, for each nudge 2 or
      3 only, the user's other candidate rows' Bond-days at the nudge instant (timelines read only for
      those Bonds; at most three Bonds a user, FR-025, `MAX_OPEN_BONDS_PER_USER`). `gratitude` asks
      `notification` one question, `revealClassSettled(userId, {bondId → date})`, over
      `notifications_recipient_idx (user_id, category, created_at)` with `created_at` within 72 hours
      (a current Bond-day's reveal is requested during that day). The count is the request's
      `leaveFreeAcrossBonds`; `notification` stays a leaf, receiving values, as with `PushRecipient`.
    - *Why not at send time:* the consumer cannot read Bonds or days (spec §2.1), and a held push is
      republished from its row alone (V58's reason for `bond_date`). Request and send are seconds
      apart unless quiet hours hold the nudge.
    - *The approximation, stated:* (a) a held nudge carries the count from when it was asked: overnight
      another Bond's reveal may settle (the count is then too high, and the nudge is dropped when it
      need not be: safe), or another Bond's next day may begin (too low: the released nudge may take a
      slot that day's reveal needs). Ruling 8 bounds this at one nudge per member and Bond-day.
      (b) Only each Bond's **current** day is counted; a `SOLO_UNLOCK` of the day before, sent during
      its grace, is not. (c) The twelve-hour grace means a Bond's previous day may still reveal; not
      counted, as (b).
    *Cost:* one indexed read per nudge 2 or 3 request (and per re-ask in the look-back: at most six).
18. **The rule lives in `PushBudget.decide`, generalised by one term.** `sentAcrossBonds +
    leaveFreeAcrossBonds >= ACROSS_BONDS` → dropped. With zero it is today's `sentAcrossBonds >= 6`
    exactly, so every other category, reveal-class included, is decided as before. A soft drop is
    `DROPPED_BUDGET` in the ledger (it is the budget) and a distinct outcome in the metric (§9).
    *Cost:* none.
19. **One migration, V68, two columns on `notifications`** (§6). The lead asked to avoid one if
    possible; it is not: ruling 17's count is decided at send time and must survive a hold, and the
    only thing a held push is republished from is its row; the index must reach the payload from the
    same row. Deriving the index by parsing the dedupe key would be a second statement of
    `ReminderKey`'s format, in a module that must not know it. *Cost:* an additive migration.
20. **The API contract does not change.** No HTTP route, request or response changes;
    `contracts/openapi.json` is untouched (push `data` is not in it; ADR-0044 decision 9 holds it).
    **Notification preferences are unchanged:** nudges are `REMINDER`s, governed by the `reminder`
    toggle, shown in the `reminder` channel with the `push_reminder_*` keys; the six-toggle body is
    the same. A member who turns reminders off gets up to three `SUPPRESSED` rows a day instead of one
    (the ledger records what was asked for, ADR-0045 decision 3). *Cost:* ledger rows, at most three
    `REMINDER`s per member per Bond-day.

## 4. The flow

**Roster** (`bond`): for each candidate, for *n* in 1..3, shift = 3 h·(*n* − 1):

```
first  = ReminderTime.occurrenceIn(local, zone, after − shift, upTo − shift)   // nudge 1's instant
at     = first + shift                                                          // in (after, upTo]
skip unless first ≥ timeline's first effectiveFrom
day    = timeline.dayBoundsAt(at)
skip unless first ≥ day.startsAt                                                // ruling C12
DueNudge(DueReminder(…, remindAt = at, bondDate = day.date, bondDayEndsAt = day.endsAt, …),
         index = n, revealDays = n ≥ 2 ? the user's reveal Bond-days at `at` : [])
```

On-this-day keeps `dueBetween` with shift zero only; `DueReminder` is unchanged, so
`PushAudiences.onThisDayDueBetween` is unchanged.

**Sweep** (`gratitude`, `SendDailyReminders`, one transaction per due nudge, as today one per due member):

1. Written that Bond-day → `WRITTEN`, nothing asked.
2. `leaveFree` = 0 for nudge 1; for 2 and 3, `revealDays.size − notifications.revealClassSettled(…).size`.
3. `notifications.request(PushRequest(…, category = REMINDER, dedupeKey = ReminderKey.of(m, d, n),
   staleAfter = bondDayEndsAt, bondDate = d, nudge = Nudge(n, leaveFree, supersedes =
   ReminderKey.series(m, d) before n)))`.

**Request** (`notification`, `PushRequests.request`, the caller's transaction): `insertOnce` as
today; if it recorded the row and `supersedes` is not empty, `UPDATE notifications SET status =
'CANCELLED' WHERE dedupe_key = ANY(?) AND status IN ('REQUESTED', 'HELD') RETURNING
leave_free_across_bonds, nudge`, and the new row's `leave_free_across_bonds` is lowered to the least
of its own and those returned. A re-ask, whose key is already recorded, supersedes nothing (*lead
ruling 2026-11-16*): not even a push asked for after the first ask.

**Send** (`SendPush`, unchanged order): expired → suppressed → held → `PushBudget.decide(…,
leaveFreeAcrossBonds = push.leaveFree)` → send with `PushMessage.nudge`.

**Write** (`SubmitEntry`): `notifications.cancel(ReminderKey.series(memberId, day.date))`, one
statement over the three keys.

**Worked day** (one Bond, reminder 08:00 Europe/London, anchor London, quiet hours 22:00–07:00,
nothing else sent): 08:00 nudge 1 sent (1); 11:00 nudge 2, owed 1, 1 + 1 < 6, sent (2); she writes
at 12:10, nudge 3 is never asked (written); her partner writes at 19:00, the reveal takes the
reserved slot (3). With a reminder at 05:00 instead: nudge 1 held to 07:00; at 07:00 the release
sends it (nudge 2 at 08:00 is after the hours, nothing to supersede); with quiet hours 22:00–09:00,
nudge 2 at 08:00 supersedes the held nudge 1, inherits its zero, and alone is released at 09:00.

## 5. Where the code lives

- **`bond`:** `api/ReminderRoster.kt`: `remindersDueBetween` returns `List<DueNudge>`; `DueNudge`
  (`reminder: DueReminder`, `index`, `revealDays: List<RevealDay>`), `RevealDay(bondId, bondDate)`
  (both `internal constructor`, ids-only `toString`), and `NUDGES_PER_BOND_DAY = 3`,
  `NUDGE_EVERY = 3 h` on the interface's companion: the one place the schedule's numbers live,
  read by `gratitude`'s `ReminderKey`. `service/ReminderRosterAdapter.kt` as §4.
  `infra/database/MemberNotifications.reminderCandidates` selects `b.status` too.
- **`gratitude`:** `domain/ReminderKey.kt` (`of(memberId, date, nudge)`, `series`);
  `service/SendDailyReminders.kt` (§4); `api/DailyReminders.kt`'s KDoc ("one push per member per
  bond-day" becomes "at most three") and `ReminderRun` gains per-nudge counts; `SubmitEntry` cancels
  the series.
- **`notification`:** `api/Notifications.kt`: `PushRequest.nudge: Nudge?`, **required, no default**
  (as `bondDate` was: every caller decides; `NotifyDayMembers`, `SendOnThisDayPushes` and `bond`'s
  joined and proposal pushes pass `null`), `init` requires `nudge == null || category == REMINDER`;
  `Nudge(index ≥ 1, leaveFreeAcrossBonds in 0..5, supersedes)`; `cancel(dedupeKeys: Collection)`;
  `revealClassSettled(userId, days)`. `domain/PushBudget.kt` (`PER_BOND_PER_DAY = 4`, the term of
  C18); `service/PushRequests.kt`, `service/SendPush.kt`, `infra/database/NotificationStore.kt`,
  `api/PushSender.kt` (`PushMessage.nudge: Int?`), `infra/push/FcmPushSender.kt` (`data.nudge`,
  the reminder's Android `tag`).
- **`scheduling`:** `ReminderJob` registers the per-nudge counter from `ReminderRun`; the job's
  registry line in `JobConsole` reads "the daily reminder and its nudges".
- **`ArchitectureTest`:** `declaredIn` gains `DueNudge`, `RevealDay`, `Nudge`; the rule that only the
  reminder sweep names `ReminderRoster` is unchanged.

## 6. Data model: V68 (`modules/notification`)

```sql
-- Issue #200 (ADR-0068). Which nudge of its Bond-day a REMINDER is; null for every other push and
-- for every row written before this migration (the payload then leaves `nudge` out). No upper bound
-- here: the count is bond.api's (NUDGES_PER_BOND_DAY), and a second copy would drift.
ALTER TABLE notifications ADD COLUMN nudge smallint;
ALTER TABLE notifications ADD CONSTRAINT notifications_nudge_check
    CHECK (nudge IS NULL OR (nudge >= 1 AND category = 'REMINDER'));

-- FR-082 as amended: how many of the member's cross-Bond slots this push must leave free when it is
-- sent (ruling 7). Zero for everything but nudges 2 and 3. Kept on the row because a held push is
-- decided again from its row alone (V58's reason).
ALTER TABLE notifications ADD COLUMN leave_free_across_bonds smallint NOT NULL DEFAULT 0;
ALTER TABLE notifications ADD CONSTRAINT notifications_leave_free_check
    CHECK (leave_free_across_bonds BETWEEN 0 AND 5);
```

No index: the supersede and the cancel go through `notifications_dedupe_key_key`, the reveal read
through `notifications_recipient_idx`, the budget through `notifications_budget_idx`. Additive and
defaulted, so the jar before it runs against the schema after it. **V68 is claimed here**; #205 will
need a migration too (its category in the CHECK), and versions are one sequence: the second to merge
renumbers on its unmerged branch.

## 7. Concurrency

| Race | What decides it | Result |
|---|---|---|
| Two runs (instances) asking for one nudge | `daily-reminders` lock; then the unique key | one row |
| Nudge *n* asked while the consumer sends nudge *n* − 1 | the consumer's `FOR UPDATE` on that row; the supersede `UPDATE` waits | sent and left alone, or still owed and cancelled; never both. A sent one is not inherited (only cancelled rows are returned) |
| A write vs a nudge being sent | as today: the cancel waits on the consumer's row lock | sent or cancelled, never both |
| A write vs the sweep asking for a nudge | none new: the sweep reads `WrittenToday` then inserts | **existing, unchanged**: an entry committed between the read and the insert leaves one nudge owed; the next nudge is not asked |
| Two nudges for one member decided at once (two Bonds) | budget lock, namespace 4 | serialised; each counts the other's sent row |
| A Bond's reveal settles between a nudge's request and its send | none | the count is a little high; the nudge may drop when it need not (safe) |

## 8. Payload and contract

`data` becomes `{bondId, kind, date, nudge}`; `nudge` is `"1"`, `"2"` or `"3"` (FCM data values
are strings) on a `REMINDER` with a stored index, and absent everywhere else, as `date` is. It names
an ordinal, never a time, a count of anything else, or the other member. The localisation keys, the
channel and the collapse key are unchanged: **v1 sends the same `push_reminder_*` keys for all
three nudges** (*lead ruling 2026-11-16, owner may revise*); per-nudge keys are owed (§13).

**`android.notification.tag` on a `REMINDER`** (*lead ruling 2026-11-16, owner may revise*):
`REMINDER:{bondId}:{date}`, so on Android each nudge of a Bond-day replaces the last in the shade,
as `apns-collapse-id` (`REMINDER:{bondId}`) already does on iOS. `collapse_key` alone replaces only
a message still waiting for an offline phone. Per member is implicit: the phone is the member's,
and the tag names no member. The Bond-day is in it so that tomorrow's first nudge is a new
notification, not a silent replacement of yesterday's. No other category gains a tag (each keeps
today's behaviour). `PushMessage` already carries `bondId`, `category` and `bondDate`, so the tag
needs no new field.

Unchanged: every HTTP route, `contracts/openapi.json`, the preferences resource, the state map
(no endpoint answers differently).

## 9. Observability

- `gratitude.reminders.nudges{nudge="1|2|3", outcome="requested|written|already_asked|failed"}`,
  registered by `ReminderJob` from `ReminderRun`. The existing `gratitude.reminders.requested` keeps
  its name and now counts every nudge asked for (its description says so).
- `notification.push.settled{category, nudge="none|1|2|3", outcome}` in `SendPush`, outcome one of
  `sent`, `held`, `suppressed`, `expired`, `dropped_budget`, `dropped_leaving_room` (C18's soft drop),
  `failed`. Partly discharges ADR-0045 Owed (`…{category,status}`). It counts dispatch decisions: a
  held push contributes `held` once per hold plus exactly one final outcome later, unless it is
  cancelled while held (written or superseded): then it has none, and `notification.push.cancelled`
  counts it. So the outcomes other than `held` sum to the pushes that reached a final outcome (lead
  ruling 2026-11-16).
- `notification.push.cancelled{category, nudge, reason="written|superseded"}` in `PushRequests`.
- Bounded tags only. No user, member or bond id, no time of day, in a tag or a log line; the sweep's
  log stays counts.

## 10. Tests and mutation targets

**Unit**
- `PushBudgetTest`: FR-082's example restated (four before 22:00: three sent, one dropped, the
  reveal in the reserved slot); "no more than four a day in one Bond"; with `leaveFree = 0` every
  existing case unchanged; `sent = 3, leaveFree = 2` → remainder; `sent = 4, leaveFree = 2` → dropped
  (leaving room); a reveal-class push is never given a leave-free by any caller (property over the
  three callers' categories).
- `NudgeScheduleTest` (the roster's arithmetic, pure): 08:00 London → 08:00, 11:00, 14:00; 20:00 with
  the anchor in London → two; 22:00 → one; a bond made at 09:30 with an 08:00 reminder → none;
  23:30 Reykjavik in a Lagos-anchored bond (ADR-0045's case) → the Lagos day; 00:30 New York on a
  spring-forward night → 00:30, 04:30, 07:30; a fall-back night → each once; six weeks of five-minute
  ticks across DST in four zones: every nudge index once per member and Bond-day, at most three, never
  after the day's end.
- `ReminderKeyTest`: `series` is the three keys, nudge 1's is today's.

**Integration** (gratitude's context scans `notification`, so the real ledger and consumer)
- `DailyRemindersTest` gains: three nudges to one who never writes, keys and `nudge` stored; writing
  after nudge 1 means no nudge 2; writing calls off a held nudge 2 and a requested nudge 3 at once;
  whether the other member wrote changes nothing about any nudge (T-09); quiet hours holding nudges 1
  and 2: 1 `CANCELLED`, 2 `HELD` with leave-free 0, one release; a superseded chain 1→2→3 keeps zero;
  three Bonds with reveals owed: nudge 2 `DROPPED_BUDGET`, then the evening's three reveals all `SENT`
  (**red if the nudged Bond is not counted**); a reveal toggled off is not owed; a pending Bond is not
  owed; a settled `REVEAL` row for the current date is not owed, one for yesterday's date is.
- `PausedSendWindowTest` (app, the real roster): `DAILY_REMINDERS` switched on between nudge 1 and
  nudge 2: nudge 1 never asked, nudge 2 asked; switched off, no nudge asked.
- `FcmPushSenderTest`: `data.nudge` on a nudge, absent on a reveal and on a pre-V68 reminder;
  `android.notification.tag` is `REMINDER:{bondId}:{date}` on a reminder and absent on every other
  category and on a reminder with no date.
- `NotificationsCheckTest` (new, as `FeatureFlagCheckTest`): `nudge` on a non-`REMINDER` row is
  refused by the CHECK.

**Mutation targets (each must turn a test red):** drop the leave-free term; count only the other
Bonds; let a superseding nudge keep its own leave-free; cancel only nudge 1's key on a write;
schedule a nudge past the day's end; wall-clock instead of elapsed (the spring-forward case); put the
per-Bond cap back to three; drop `supersedes`; send `nudge` on a non-reminder; drop the reminder's
Android `tag`, or put the Bond-day out of it.

## 11. Operations

- **Smoke** (`scripts/smoke.sh`, the N2 section): the job cannot wait three hours, so a fresh bond's
  first anchor interval is moved back four hours by SQL (as the section already moves a hold's
  `not_before`), its anchor zone chosen so local time is near noon, and the member's reminder set to
  the last boundary minus three hours. Probes: one row keyed `…:2`, `nudge = 2`,
  `leave_free_across_bonds = 1`, nothing keyed `…:1` (nudge 1 fell before the look-back); the next
  run asks for nothing twice; writing turns it `CANCELLED` and asks for no `…:3`; the logging
  provider's line carries no id; no member id in the log. The plan settles the zone arithmetic.
- **Runbook:** none new. `ops/` notes for `DAILY_REMINDERS` say it pauses nudges too.
- **Deploy:** V68 is additive; nothing to do on the server but deploy. The first day after it,
  reminders already asked keep their keys; nudges 2 and 3 begin that day.

## 12. Amendments (in the build PR, and the corpus)

- **ADR-0068** (this slice), amending ADR-0044 decision 9 (`data.nudge`, in place, as #146 did) and
  ADR-0045 decisions 5 and 6 (four per Bond; the reminder is a series; `CANCELLED` also means
  superseded).
- **Documents repository:** doc 03 FR-082 ("≤4 pushes per Bond per member per day"; nudges 2 and 3
  leave room for owed reveals across Bonds) and FR-080 (the nudges); the Phase 4 spec's table row
  (`2026-10-27-…` line 52) is history and is not edited.

## 13. Owed, and what was settled (lead rulings 2026-11-16, owner may revise)

**Owed**
1. **Per-nudge words.** The phone shows `push_reminder_title/_body`, chosen by the server and
   **rendered by the operating system** for a background push (Android runs no app code for a
   notification message; iOS would need a Notification Service Extension and `mutable-content: 1`).
   v1 sends the same keys for all three nudges plus `data.nudge`, which the app can read in the
   foreground and on a tap. **Per-nudge keys** (`push_reminder_2_*`, `push_reminder_3_*`) are a
   follow-up, coordinated with the app once a release carries the strings; **no service extension**.
   The words are the owner's to approve.

**Settled, recorded as consequences**
2. **Three Bonds:** a member in three Bonds gets no nudge 2 or 3 until a reveal settles. Accepted.
3. **Counting the nudged Bond** (ruling 7 ✱): accepted.
4. **Android stacking:** in scope, the tag of §8.
5. **#205:** `SHARED_SETTING_CHANGED` shares the per-Bond remainder first-come and drops on a
   three-nudge day. Accepted for now; the hook, if it should outrank nudge 3, is a per-Bond leave-free
   on nudge 3, the same shape as C18's term.
6. **The request-time race** (§7): unchanged, recorded.
