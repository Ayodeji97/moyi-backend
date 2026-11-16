# Reminder nudges Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends green and is reviewed against the spec before the next.

**Goal:** the daily reminder becomes up to three nudges per member per Bond-day (at the reminder
time, then every three hours until they write), within FR-082 as amended (four per Bond; nudges 2
and 3 leave cross-Bond room for every reveal still owed), with `data.nudge` and an Android tag.

**Architecture:** `bond`'s roster finds each nudge by shifting the sweep's window (a pure
`NudgeSchedule` over `ReminderTime`) and supplies, for nudges 2 and 3, the user's Bond-days a reveal
may still come for. `gratitude`'s sweep asks `notification` which of those are settled and requests
the nudge with a `Nudge(index, leaveFreeAcrossBonds, supersedes)`. `notification` cancels the
superseded rows and inherits their claim in one statement, keeps the two values on the row (V68), and
`PushBudget.decide` gains one term. No new job, no HTTP change.

**Tech Stack:** Kotlin, Spring Boot, JDBC (`JdbcTemplate`), Postgres + Flyway, Micrometer, JUnit 5 +
Testcontainers, FCM v1 payload maps.

**Spec:** `docs/superpowers/specs/2026-11-16-reminder-nudges-design.md` (rulings cited as A1–A6,
B7–B11, C12–C20) and `adr/0068-reminder-nudges-and-the-push-budget.md`. Where this plan and the spec
disagree, the spec wins: stop and report.

## Global Constraints

- **V68** only: `modules/notification/src/main/resources/db/migration/V68__notification_nudges.sql`.
  Re-check `origin/main` before the PR; if V68 has landed (e.g. #205), renumber and say so.
- Nudge 1's key stays `reminder:{memberId}:{date}`; nudges 2 and 3 are `…:2`, `…:3` (C15).
- `NUDGES_PER_BOND_DAY = 3` and `NUDGE_EVERY = 3 h` live once, on `bond.api.ReminderRoster`'s
  companion; nothing else writes 3 or 3 h for the schedule (the V68 CHECK has no upper bound).
- `PER_BOND_PER_DAY = 4`, `RESERVED_PER_BOND_PER_DAY = 1`, `ACROSS_BONDS = 6` in `PushBudget`.
- Nudge times are elapsed: nudge *n* = nudge 1's instant + 3 h·(*n* − 1) (C13).
- `PushRequest.nudge` is required and nullable: every caller decides (as `bondDate`).
- No member, user or bond id, no time of day, in a metric tag or a log line above DEBUG.
- Tests that fail when the mechanism is removed: each task names its mutation; the implementer breaks
  it, sees red, restores it.
- Concurrency tests synchronise on `pg_locks` with a failing deadline; never a sleep.
- Before each commit: `./gradlew :modules:<m>:test :modules:<m>:ktlintCheck :modules:<m>:detekt`
  for every touched module. Commits are Conventional, subject ≤ 88 characters, ending with the
  session's `Co-Authored-By` and `Claude-Session` lines.
- Never touch another worktree, the default compose project, port 8080/5432.

## Review Focus

1. **A member changes their reminder time mid-day** → never a fourth nudge that Bond-day; each index
   at most once (keys). Test in Task 9.
2. **A member writes, then deletes the entry** → later nudges of the day are asked again, as today's
   reminder is. Test in Task 9.
3. **Anchor zone ≠ reminder zone, a series crossing the member's midnight but not the Bond-day's
   end** → all three nudges, under the Bond's date. Test in Task 7.
4. **Deploy day: a pre-V68 `REMINDER` row (`nudge` null) exists for today** → nudge 1 not asked again,
   its payload has no `nudge`, writing cancels it. Tests in Tasks 5 and 9.
5. **The look-back re-asks a nudge** (six times in 30 minutes) → nothing cancelled twice, no metric
   counted twice, no second row. Test in Task 3.

---

### Task 1: V68, the `Nudge` value and the ledger columns (notification)

**Model:** sonnet

**Files:**
- Create: `modules/notification/src/main/resources/db/migration/V68__notification_nudges.sql` (the SQL of spec §6, verbatim)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/api/Notifications.kt` (`PushRequest`, new `Nudge`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/infra/database/NotificationStore.kt` (`NewPush`, `StoredPush`, `insertOnce`, `lock`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/PushRequests.kt` (pass the values through)
- Modify (pass `nudge = null`): `modules/bond/.../service/NotifyBondMembers.kt`, `modules/gratitude/.../service/NotifyDayMembers.kt`, `.../SendOnThisDayPushes.kt`, `.../SendDailyReminders.kt` (null for now; Task 9 fills it), and the test callers `PushApiTest`, `LiveOrSentTest`, `RequestingConsumer`, `PushPreferencesBudgetTest`, `SendPushTest`, `app/.../SecretsNeverLoggedTest`
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/infra/NudgeColumnsTest.kt`, `modules/notification/src/test/kotlin/com/moyi/notification/api/PushApiTest.kt`

**Interfaces:**
- Produces: `data class Nudge(val index: Int, val leaveFreeAcrossBonds: Int, val supersedes: Set<String>)` in `notification.api`, `init`: `index >= 1`, `leaveFreeAcrossBonds in 0 until PushBudget.ACROSS_BONDS`, every key 1..200 characters. `PushRequest(..., bondDate: LocalDate?, nudge: Nudge?)`, `init`: `nudge == null || category == PushCategory.REMINDER`. `NewPush.nudge: Int?`, `NewPush.leaveFreeAcrossBonds: Int`; `StoredPush.nudge: Int?`, `StoredPush.leaveFreeAcrossBonds: Int`.

- [ ] **Step 1: Write the failing tests**
  - `PushApiTest`: `a nudge on any category but REMINDER is refused` (`assertThrows<IllegalArgumentException>` for `REVEAL` with `Nudge(1, 0, emptySet())`); `a nudge's leave-free is 0 to 5` (6 and −1 throw; 5 does not).
  - `NudgeColumnsTest` (the module's Testcontainers context): a request with `Nudge(2, 1, emptySet())` is stored with `nudge = 2`, `leave_free_across_bonds = 1` and `lock(id)` reads both back; a request with `nudge = null` stores `nudge IS NULL`, `leave_free_across_bonds = 0`; a raw `INSERT` of a `REVEAL` row with `nudge = 1` fails on `notifications_nudge_check`; `leave_free_across_bonds = 6` fails on `notifications_leave_free_check`.
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*NudgeColumnsTest" --tests "*PushApiTest"` → FAIL (no `Nudge`, no column).
- [ ] **Step 3:** Write V68; add `Nudge` and the field with the KDoc of spec §5; carry both values through `PushRequests.request` → `NewPush` → `insertOnce`; select both in `lock`. Pass `nudge = null` at every other caller.
- [ ] **Step 4:** the same command → PASS; then `./gradlew :modules:notification:test :modules:gratitude:compileTestKotlin :modules:bond:compileTestKotlin :app:compileTestKotlin`.
- [ ] **Step 5:** Mutation: drop the `category = 'REMINDER'` half of the CHECK → the raw-insert case goes red. Restore.
- [ ] **Step 6: Commit** `feat(notification): V68, a nudge's index and leave-free on the push ledger`

### Task 2: the budget rule, four per Bond and the leave-free term (notification)

**Model:** opus

**Files:**
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/domain/PushBudget.kt`
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/SendPush.kt` (`budgetSlot` passes `push.leaveFreeAcrossBonds`; `DROPPED_LEAVING_ROOM` settles `DROPPED_BUDGET`)
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/domain/PushBudgetTest.kt`, `modules/notification/src/test/kotlin/com/moyi/notification/service/PushPreferencesBudgetTest.kt`

**Interfaces:**
- Consumes: `StoredPush.leaveFreeAcrossBonds` (Task 1).
- Produces: `BudgetDecision.DROPPED_LEAVING_ROOM`; `PushBudget.decide(sentInBondToday: Int, reservedSpentToday: Boolean, sentAcrossBonds: Int, revealClass: Boolean, leaveFreeAcrossBonds: Int): BudgetDecision`; `PER_BOND_PER_DAY = 4`.

- [ ] **Step 1: Write the failing tests** in `PushBudgetTest`:
  - rename and restate `FR-082's own example - four before 22-00 send three, and the reveal still goes`: `decide(3, false, 3, false, 0) == DROPPED`, `decide(3, false, 3, true, 0) == RESERVED`.
  - `no more than four a day in one Bond, whatever they are`: `decide(4, true, 4, true, 0) == DROPPED`.
  - `with nothing to leave free, every decision is the rule before nudges` (property over sent 0..7, reserved both, across 0..7, revealClass both: equal to the same call with `leaveFreeAcrossBonds = 0` and to the documented table; the per-Bond line uses 4).
  - `a nudge goes if, after it, a slot is left for each reveal owed`: `decide(0, false, 3, false, 2) == REMAINDER`; `decide(0, false, 4, false, 2) == DROPPED_LEAVING_ROOM`; `decide(0, false, 5, false, 0) == REMAINDER`; `decide(0, false, 6, false, 0) == DROPPED`.
  - the spec's two-Bond table (B7) as one test: counts 2, 3 → sent; 4 with leave-free 2 → `DROPPED_LEAVING_ROOM`; reveals at 4 and 5 → `RESERVED`, `RESERVED`.
  - `PushPreferencesBudgetTest`: a stored `REMINDER` with `leave_free_across_bonds = 2` and four sent across Bonds in 24 h settles `DROPPED_BUDGET`; the same with 0 is `SENT`.
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*PushBudgetTest" --tests "*PushPreferencesBudgetTest"` → FAIL.
- [ ] **Step 3:** In `decide`, in this order: `sentAcrossBonds >= ACROSS_BONDS` → `DROPPED`; `sentAcrossBonds + leaveFreeAcrossBonds >= ACROSS_BONDS` → `DROPPED_LEAVING_ROOM`; then today's per-Bond, reserved and remainder lines with `PER_BOND_PER_DAY = 4`. Update the KDoc's FR-082 quote to the amended text and cite ADR-0068. `SendPush` maps both drops to `NotificationStatus.DROPPED_BUDGET`.
- [ ] **Step 4:** the same command → PASS; `./gradlew :modules:notification:test`.
- [ ] **Step 5:** Mutations: remove the leave-free line → the nudge cases go red; set `PER_BOND_PER_DAY` back to 3 → the example goes red. Restore.
- [ ] **Step 6: Commit** `feat(notification): FR-082 at four per Bond, and soft nudges leave room for reveals`

### Task 3: supersede, inherit, and cancel a series (notification)

**Model:** opus

**Files:**
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/infra/database/NotificationStore.kt`
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/PushRequests.kt`
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/api/Notifications.kt` (`cancel` signature)
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SubmitEntry.kt` (call site compiles: `cancel(listOf(ReminderKey.of(memberId, day.date)))`; Task 9 makes it the series)
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/service/SupersedeTest.kt`

**Interfaces:**
- Consumes: `Nudge.supersedes`, `Nudge.leaveFreeAcrossBonds` (Task 1).
- Produces: `Notifications.cancel(dedupeKeys: Collection<String>): Int` (replaces `cancel(String): Boolean`; same KDoc, plural); `NotificationStore.cancelOwed(keys: Collection<String>): List<CancelledPush>` where `CancelledPush(category: PushCategory, nudge: Int?, leaveFreeAcrossBonds: Int)`, one `UPDATE … WHERE dedupe_key = ANY(?) AND status IN ('REQUESTED','HELD') RETURNING category, nudge, leave_free_across_bonds`. `PushRequests.request`: when `nudge.supersedes` is non-empty, `cancelOwed` first; the inserted row's leave-free is `min(own, min of returned)`.

- [ ] **Step 1: Write the failing tests** in `SupersedeTest` (real ledger, `RequestingConsumer`'s context):
  - `a newer nudge cancels an older one still held, and only one is left owed`: nudge 1 `HELD`; request nudge 2 superseding it → nudge 1 `CANCELLED`, nudge 2 `REQUESTED`.
  - `a nudge that supersedes a hard one is hard`: nudge 1 leave-free 0 `HELD`; nudge 2 asked with 2 → stored 0.
  - `the claim carries down a chain`: 1 (0) held, 2 (2) supersedes → 0; 3 (2) supersedes {1, 2} → 0.
  - `a sent nudge is not superseded and gives nothing`: nudge 1 `SENT`; nudge 2 asked with 2 → nudge 1 still `SENT`, nudge 2 stored 2.
  - `asked again, a nudge cancels nothing twice` (Review Focus 5): request nudge 2 twice → second returns `false`, one row, nudge 1 cancelled once.
  - `cancel takes a series in one call`: three owed keys → returns 3, all `CANCELLED`; a `SENT` one among them is left `SENT`.
  - `a supersede waits for a send in progress and leaves it sent` (concurrency): a transaction holds `lock(nudge1)`; the request blocks (seen in `pg_locks` as waiting on the row) ; the first settles `SENT` and commits → nudge 2 stored with its own leave-free.
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*SupersedeTest"` → FAIL.
- [ ] **Step 3:** Implement `cancelOwed`, the plural `cancel` over it, and the supersede in `request` inside the caller's `MANDATORY` transaction, before `insertOnce`.
- [ ] **Step 4:** same command → PASS; `./gradlew :modules:notification:test :modules:gratitude:test --tests "*DailyRemindersTest"`.
- [ ] **Step 5:** Mutation: insert with the request's own leave-free (no `min`) → the hard-claim tests go red. Restore.
- [ ] **Step 6: Commit** `feat(notification): a newer nudge supersedes an unsent one and takes over its budget claim`

### Task 4: which reveals are settled (notification)

**Model:** opus

**Files:**
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/api/Notifications.kt`
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/infra/database/NotificationStore.kt`
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/PushRequests.kt`
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/service/RevealSettledTest.kt`

**Interfaces:**
- Produces: `Notifications.revealClassSettled(userId: UUID, bondDays: Map<UUID, LocalDate>): Set<UUID>` (`@Transactional(readOnly = true)`): the Bonds of `bondDays` with a `REVEAL` or `SOLO_UNLOCK` row to `userId`, `bond_date` equal to that Bond's date, `created_at` after `clock.instant() − 72 h`, status in `PushRequests.SETTLED = {SENT, SUPPRESSED, DROPPED_BUDGET, EXPIRED, FAILED_PERMANENT}`. Empty map → empty set, no query.

- [ ] **Step 1: Write the failing tests** in `RevealSettledTest`: `SENT` reveal for the date → settled; `REQUESTED` and `HELD` → not; `SUPPRESSED`, `DROPPED_BUDGET`, `EXPIRED`, `FAILED_PERMANENT` → settled; a `SOLO_UNLOCK` → settled; yesterday's date → not; another user's reveal → not; another Bond's → not; a `MILESTONE` → not; three Bonds at once answer each correctly; older than 72 h → not.
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*RevealSettledTest"` → FAIL.
- [ ] **Step 3:** One query (`bond_id = ANY(?)` then filter `(bond_id, bond_date)` in Kotlin, or a `VALUES` join); use `notifications_recipient_idx` (`user_id`, `category = ANY`, `created_at >`). `SETTLED` sits beside `LIVE_OR_SENT` with a KDoc saying they partition every status but `CANCELLED`.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5:** Mutation: count `REQUESTED` as settled → its test goes red. Restore.
- [ ] **Step 6: Commit** `feat(notification): ask which Bond-days' reveals are settled for a member`

### Task 5: the payload, `data.nudge` and the reminder's Android tag (notification)

**Model:** sonnet

**Files:**
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/api/PushSender.kt` (`PushMessage.nudge: Int? = null`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/SendPush.kt` (pass `push.nudge`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/infra/push/FcmPushSender.kt` (`dataOf`, `payloadOf`, class KDoc)
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/infra/push/FcmPushSenderTest.kt`

**Interfaces:**
- Consumes: `StoredPush.nudge` (Task 1).
- Produces: `FcmPushSender.androidTag(message: PushMessage): String?` = `"REMINDER:${bondId}:${bondDate}"` for `REMINDER` with a `bondDate`, else `null`; put under `android.notification.tag` only when non-null.

- [ ] **Step 1: Write the failing tests:** `a nudge carries its index as a string in data` (`nudge = 2` → `data["nudge"] == "2"`); `no other push carries nudge` (`REVEAL` → no key); `a reminder recorded before V68 carries no nudge` (`REMINDER`, `nudge = null` → no key) — Review Focus 4; `a reminder carries a tag per Bond and Bond-day` (`android.notification.tag == "REMINDER:$bondId:2026-11-16"`); `no other category carries a tag` (each of the other six → no `tag` key); `a reminder with no date carries no tag`; the localisation keys of nudges 2 and 3 are `push_reminder_title`/`_body` (v1).
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*FcmPushSenderTest"` → FAIL.
- [ ] **Step 3:** Implement; ADR-0044's payload line in the class KDoc becomes `data: {bondId, kind, date, nudge}` and names the tag.
- [ ] **Step 4:** → PASS; `SecretsNeverLoggedTest` still passes (`./gradlew :app:test --tests "*SecretsNeverLoggedTest"`).
- [ ] **Step 5:** Mutation: drop the date from the tag → its test goes red. Restore.
- [ ] **Step 6: Commit** `feat(notification): a nudge's index in push data, and a tag that replaces it on Android`

### Task 6: settlement and cancel metrics (notification)

**Model:** sonnet

**Files:**
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/SendPush.kt`, `.../PushRequests.kt`
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/service/PushMetricsTest.kt`

**Interfaces:**
- Consumes: `CancelledPush` (Task 3), `BudgetDecision.DROPPED_LEAVING_ROOM` (Task 2).
- Produces: counters `notification.push.settled{category, nudge, outcome}` (`nudge` = `none|1|2|3`; `outcome` = `sent|held|suppressed|expired|dropped_budget|dropped_leaving_room|failed`) and `notification.push.cancelled{category, nudge, reason}` (`reason` = `written` from `cancel`, `superseded` from `request`). Constants on each class's companion.

- [ ] **Step 1: Write the failing tests** (the module context's `MeterRegistry`): one test per outcome, driven through `SendPush.handle` with seeded rows; a superseded nudge 1 counts `cancelled{reason=superseded, nudge=1}`; `cancel(series)` counts each cancelled row `reason=written`; a re-ask counts nothing (Review Focus 5); no tag value is a UUID (assert on every meter's tags).
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*PushMetricsTest"` → FAIL.
- [ ] **Step 3:** Increment where each decision is settled (a `Retry` throws and counts nothing).
- [ ] **Step 4:** → PASS; `./gradlew :modules:notification:test`.
- [ ] **Step 5:** Mutation: count before the budget decision → `dropped_leaving_room` test goes red. Restore.
- [ ] **Step 6: Commit** `feat(notification): count settled and cancelled pushes by category and nudge`

### Task 7: the nudge schedule (bond, pure)

**Model:** sonnet

**Files:**
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/domain/NudgeSchedule.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/api/ReminderRoster.kt` (companion constants only)
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/domain/NudgeScheduleTest.kt`

**Interfaces:**
- Produces: `ReminderRoster.NUDGES_PER_BOND_DAY = 3`, `ReminderRoster.NUDGE_EVERY: Duration = Duration.ofHours(3)` (companion object). `internal object NudgeSchedule { fun dueIn(local: LocalTime, zone: ZoneId, after: Instant, upTo: Instant): List<NudgeTime> }`, `internal data class NudgeTime(val index: Int, val first: Instant, val at: Instant)`: for each index *n*, `first = ReminderTime.occurrenceIn(local, zone, after − shift, upTo − shift)`, `at = first + shift`, `shift = NUDGE_EVERY × (n − 1)`. It knows no Bond-day; the adapter applies C12's day rule (Task 8).

- [ ] **Step 1: Write the failing tests:** 08:00 London, windows of five minutes walked across a day → `at` 08:00, 11:00, 14:00, indices 1, 2, 3, each once; 00:30 America/New_York on 2027-03-14 → 05:30Z, 08:30Z, 11:30Z (00:30 EST, 04:30 EDT, 07:30 EDT); the 2026-11-01 fall-back night → each index once; Australia/Lord_Howe (30-minute shift) and Asia/Kathmandu walked over six weeks of five-minute ticks → each index exactly once per local date, and `at − first` is always 0, 3 h or 6 h.
- [ ] **Step 2:** `./gradlew :modules:bond:test --tests "*NudgeScheduleTest"` → FAIL.
- [ ] **Step 3:** Implement over `ReminderTime.occurrenceIn` (no second DST rule).
- [ ] **Step 4:** → PASS.
- [ ] **Step 5:** Mutation: compute nudge *n* as wall time + 3 h (`atZone(zone).plusHours`) → the New York case goes red. Restore.
- [ ] **Step 6: Commit** `feat(bond): the nudge schedule, three real hours apart from the reminder time`

### Task 8: the roster returns nudges and the Bond-days a reveal is owed for (bond)

**Model:** sonnet

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/api/ReminderRoster.kt` (`DueNudge`, `RevealDay`, return type)
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/service/ReminderRosterAdapter.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/infra/database/MemberNotifications.kt` (`b.status` into `ReminderCandidate.bondStatus: BondStatus`)
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SendDailyReminders.kt` (compiles over `due.reminder`; Task 9 completes it)
- Modify: `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt` (`declaredIn` gains `DueNudge`, `RevealDay`, `Nudge`)
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/service/NudgeRosterTest.kt`, `modules/bond/src/test/kotlin/com/moyi/bond/service/PausedBondTest.kt`

**Interfaces:**
- Consumes: `NudgeSchedule.dueIn` (Task 7).
- Produces: `ReminderRoster.remindersDueBetween(after: Instant, upTo: Instant): List<DueNudge>`; `class DueNudge internal constructor(val reminder: DueReminder, val index: Int, val revealDays: List<RevealDay>)`; `class RevealDay internal constructor(val bondId: UUID, val bondDate: LocalDate)`; both with ids-only `toString`. `reminder.remindAt` = the nudge's `at`; `bondDate`/`bondDayEndsAt` = `dayBoundsAt(at)`. A nudge is kept only if `at >= first interval's effectiveFrom` and `first >= dayBoundsAt(at).startsAt` (C12). `revealDays` is empty for index 1; for 2 and 3, every unpaused candidate row of the same `userId` (the nudged one included) whose `bondStatus == ACTIVE`, whose `preferences.allows(PushCategory.REVEAL)`, and whose Bond's timeline has begun by `at`, with `dayBoundsAt(at).date`. On-this-day's `dueBetween` path is unchanged (shift zero only).

- [ ] **Step 1: Write the failing tests** in `NudgeRosterTest` (bond's context, real tables):
  - 08:00 London member in a London bond: sweeps at 08:00, 11:00, 14:00 each return one `DueNudge`, indices 1, 2, 3, same `bondDate`, `bondDayEndsAt` the London midnight.
  - 20:00 → indices 1 and 2 only; 22:00 → 1 only (the 01:00 one is never returned).
  - a bond made at 09:30 with an 08:00 reminder → nothing at 11:00 or 14:00 that day.
  - Review Focus 3: reminder 20:00 America/New_York in a bond anchored in Asia/Tokyo (nudges at 01:00Z, 04:00Z, 07:00Z: 10:00, 13:00, 16:00 Tokyo, one Bond-day; nudge 3 falls after New York's midnight) → three nudges with one `bondDate`.
  - `revealDays` for nudge 2 of a member in three bonds: the three, each with its own date; a PENDING_MEMBER bond left out; a bond where that member turned reveals off left out; a paused bond left out; nudge 1's is empty.
  - `PausedBondTest`: a paused bond's member gets no nudge 2 either.
- [ ] **Step 2:** `./gradlew :modules:bond:test --tests "*NudgeRosterTest" --tests "*PausedBondTest"` → FAIL.
- [ ] **Step 3:** Implement; load timelines only for bonds with a due nudge and, for indices 2–3, that user's other bonds.
- [ ] **Step 4:** → PASS; `./gradlew :modules:bond:test :modules:gratitude:compileKotlin :app:test --tests "*ArchitectureTest"`.
- [ ] **Step 5:** Mutation: drop the `first >= startsAt` check → the 22:00 and 09:30 cases go red. Restore.
- [ ] **Step 6: Commit** `feat(bond): the reminder roster returns nudges and the Bond-days a reveal is owed for`

### Task 9: the sweep asks for nudges, and writing calls off the series (gratitude)

**Model:** opus

**Files:**
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/ReminderKey.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SendDailyReminders.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/api/DailyReminders.kt` (KDoc; `ReminderRun`)
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SubmitEntry.kt`
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/ReminderKeyTest.kt`, `modules/gratitude/src/test/kotlin/com/moyi/gratitude/service/DailyRemindersTest.kt`

**Interfaces:**
- Consumes: `DueNudge`, `RevealDay`, `NUDGES_PER_BOND_DAY` (Tasks 7–8); `Nudge`, `revealClassSettled`, plural `cancel` (Tasks 1, 3, 4).
- Produces: `ReminderKey.of(memberId: UUID, bondDate: LocalDate, nudge: Int): String` (nudge 1 → today's key; else `…:$nudge`); `ReminderKey.series(memberId, bondDate): List<String>` (indices 1..`NUDGES_PER_BOND_DAY`); `ReminderKey.before(memberId, bondDate, nudge): Set<String>`. `ReminderRun(due, requested, alreadyWritten, failed, byNudge: Map<Int, NudgeCounts>)`, `data class NudgeCounts(val requested: Int, val written: Int, val alreadyAsked: Int, val failed: Int)`.

- [ ] **Step 1: Write the failing tests:**
  - `ReminderKeyTest`: `of(m, d, 1) == "reminder:$m:$d"`; `of(m, d, 3) == "reminder:$m:$d:3"`; `series` has the three; `before(m, d, 3)` is keys 1 and 2.
  - `DailyRemindersTest` (real roster and ledger):
    - `one who never writes gets three nudges, each with its index` (rows keyed 1, `:2`, `:3`, `nudge` 1, 2, 3, all `staleAfter` the Bond-day's end).
    - `writing after nudge 1 means no nudge 2`.
    - `writing calls off a held nudge 2 and a requested nudge 3 at once` (both `CANCELLED`).
    - `whether the other member wrote changes nothing about any nudge` (T-09: two runs, partner written or not, identical rows for her).
    - `quiet hours holding nudges 1 and 2 release one` (1 `CANCELLED`, 2 `HELD` with leave-free 0; release → one `SENT`).
    - `nudge 2's leave-free counts the nudged Bond and every Bond still owed a reveal` (three bonds, no reveals → 3; one with a `SENT` reveal for today's date → 2). **Mutation target: count only the others.**
    - `three Bonds: nudge 2 is held back and the evening's three reveals are all sent` (through the consumer).
    - `nudge 1 is asked with leave-free 0 whatever is owed`.
    - Review Focus 1: `moving the reminder time mid-day never asks a fourth nudge` (08:00 → nudge 1 at 08:00; at 09:00 move to 10:00; sweep to midnight → at most one row per index).
    - Review Focus 2: `a member who wrote and deleted the entry is nudged again later that day`.
    - Review Focus 4: `a reminder recorded before V68 is not asked again, and writing calls it off` (seed today's nudge-1 key with `nudge` NULL).
    - `ReminderRun` counts each index.
- [ ] **Step 2:** `./gradlew :modules:gratitude:test --tests "*ReminderKeyTest" --tests "*DailyRemindersTest"` → FAIL.
- [ ] **Step 3:** `remind(due: DueNudge)`, per nudge in its own transaction as today: written → `WRITTEN`; leave-free = 0 for index 1, else `revealDays.size − notifications.revealClassSettled(userId, revealDays.associate { it.bondId to it.bondDate }).size`; request with `Nudge(index, leaveFree, ReminderKey.before(...))`. `SubmitEntry` cancels `ReminderKey.series(membership.memberId, day.date)`. `DailyReminders` KDoc: "at most three per member per bond-day".
- [ ] **Step 4:** → PASS; `./gradlew :modules:gratitude:test`.
- [ ] **Step 5:** Mutations: cancel only `of(…, 1)` on a write → the "at once" test goes red; drop the nudged Bond from the count (filter `bondId != due.reminder.bondId`) → the three-Bonds test goes red. Restore.
- [ ] **Step 6: Commit** `feat(gratitude): nudges every three hours until the member writes, at most three a day`

### Task 10: the job's counters, the flag through the real roster (scheduling, app)

**Model:** sonnet

**Files:**
- Modify: `modules/scheduling/src/main/kotlin/com/moyi/scheduling/service/ReminderJob.kt`
- Modify: `modules/scheduling/src/main/kotlin/com/moyi/scheduling/service/ScheduledJobsConsole.kt:103` ("Asks for the daily reminders and their nudges due in the last half hour.")
- Modify: `modules/scheduling/src/test/kotlin/com/moyi/scheduling/infra/SchedulingTestApplication.kt` (the fake `DailyReminders` returns the new `ReminderRun`)
- Test: `modules/scheduling/src/test/kotlin/com/moyi/scheduling/service/ReminderJobTest.kt`, `app/src/test/kotlin/com/moyi/app/admin/PausedSendWindowTest.kt`

**Interfaces:**
- Consumes: `ReminderRun.byNudge` (Task 9).
- Produces: counter `gratitude.reminders.nudges{nudge="1|2|3", outcome="requested|written|already_asked|failed"}`; `gratitude.reminders.requested` keeps its name, description "Reminder nudges asked for, every index".

- [ ] **Step 1: Write the failing tests:** `ReminderJobTest`: a run whose `byNudge` is `{1: (2,1,0,0), 2: (1,0,0,0)}` increments the tagged counters by those values; `PausedSendWindowTest`: `DAILY_REMINDERS switched on between nudge 1 and nudge 2 asks for nudge 2 only` (real roster: member at 08:00, flag `On(since = 09:00)`, run at 11:00:30 → one row, `:2`; run at 08:00:30 with `Off` → none).
- [ ] **Step 2:** `./gradlew :modules:scheduling:test --tests "*ReminderJobTest" :app:test --tests "*PausedSendWindowTest"` → FAIL.
- [ ] **Step 3:** Register and increment; change nothing in the window or the clamp.
- [ ] **Step 4:** → PASS; `./gradlew :modules:scheduling:test :app:test --tests "*JobConsoleTest"`.
- [ ] **Step 5:** Mutation: tag every increment `nudge=1` → the counter test goes red. Restore.
- [ ] **Step 6: Commit** `feat(scheduling): count reminder nudges by index and outcome`

### Task 11: records, smoke probes, and the whole build

**Model:** sonnet

**Files:**
- Modify: `adr/0044-push-transport-the-ledger-and-token-ownership.md` (decision 9: an "*Amended 2026-11-16 (issue #200)*" paragraph: `data.nudge`, the reminder's `android.notification.tag`)
- Modify: `adr/0045-push-preferences-quiet-hours-and-the-budget.md` (decisions 5 and 6: an amended note pointing at ADR-0068; four per Bond; the reminder is a series; `CANCELLED` also means superseded)
- Modify: `adr/0068-reminder-nudges-and-the-push-budget.md` (status stays Proposed; "Rulings while it was built" if any)
- Modify: `ops/admin/README.md:288` (`DAILY_REMINDERS` pauses "the daily reminder and its nudges")
- Modify: `scripts/smoke.sh` (the N2 section, spec §11)
- Check, do not expect to change: `docs/state-map/data/*.json` (no endpoint answers differently: confirm with `grep -n -i reminder`), `contracts/openapi.json` (`OpenApiContractTest` passes with no diff)

- [ ] **Step 1: Write the smoke probes** after the existing reminder probes, in a fresh bond (two members, so `ACTIVE`): move its first `anchor_intervals.effective_from` (and `bonds.created_at`) back four hours by `sql`, with the anchor zone an `Etc/GMT±N` chosen so the local hour is between 10 and 14; set her reminder to the last five-minute boundary minus three hours in that zone; poll (as the existing loop, ≤ 40 s) for `db_is` checks: one row with `dedupe_key LIKE 'reminder:%:%:2'`, `nudge = 2`, `leave_free_across_bonds = 1`; no row without the `:2` suffix for her; after six more seconds still one row; she writes → that row `CANCELLED` and no `:3` row; her token and member id in no log line.
- [ ] **Step 2: Run** `scripts/smoke.sh` → every probe passes, the new ones included (paste the section's tail and the totals line).
- [ ] **Step 3:** Amend ADR-0044, ADR-0045, the ops README line; confirm the state map and contract need no change.
- [ ] **Step 4: Run** `./gradlew build` → `BUILD SUCCESSFUL` (all tests, ktlint, detekt, architecture rules).
- [ ] **Step 5: Commit** `docs(push): nudges in ADR-0044 and ADR-0045, the runbook, and smoke probes`
