# Shared-setting pushes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task ends green and is reviewed against the spec before the next.

**Goal:** a `SHARED_SETTING_CHANGED` push to the other member when a shared setting changes or a
proposal is accepted, declined or withdrawn; its own toggle; how each proposal ended, recorded and
shown on the bond; coalesced bursts; nothing on leave or block, and nothing held across them; and
FR-082's per-Bond line for every non-reveal push.

**Architecture:** `bond` records a proposal's ending (V70) and publishes `BondSettingsChanged` or
`ProposalClosed` in the actor's transaction; `bond.notify` reads the row, calls off a stale opening
push, and asks `notification` for one push per other member. `notification` knows the category
(V71), coalesces it (two-minute settle, group supersede, earliest `not_before`, `SEVERAL`), applies
the generalised budget line, and renders per-change keys. `EndBond` calls off every owed push of the
Bond. One HTTP change, additive: `closedProposals`, two pending `id`s, the `sharedSettings` toggle.

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA (bond), `JdbcTemplate` (notification), Postgres
+ Flyway, Micrometer, JUnit 5 + Testcontainers, springdoc (the contract), FCM v1 payload maps.

**Spec:** `docs/superpowers/specs/2026-11-16-shared-setting-push-design.md` (rulings cited as A1–A6,
B7–B25) and `adr/0069-shared-setting-pushes.md` (decisions 1–14). Where this plan and the spec
disagree, the spec wins: stop and report.

## Global Constraints

- **V70** `modules/bond/src/main/resources/db/migration/V70__bond_proposal_endings.sql` and **V71**
  `modules/notification/src/main/resources/db/migration/V71__notification_shared_setting_changed.sql`,
  the SQL of spec §6.1 and §6.2. Re-check `origin/main` before the PR: if V70 or V71 has landed,
  renumber here and say so. V69 is PR #210's.
- `SETTLE = 2 min` lives once, in `notification.domain.Coalescing`. `STALE_AFTER = 24 h` stays
  `NotifyBondMembers.STALE_AFTER`.
- `PushRequest.sharedChange` is required and nullable: every caller decides (as `nudge`).
- Declined and withdrawn are derived in `Proposal.outcome`, never stored, never restated.
- `EndBond` never names a member on the proposals it closes, and publishes no new event.
- No member, user, bond or proposal id, no setting name, no time, in a metric tag or a log line
  above DEBUG.
- Tests that fail when the mechanism is removed: each task names its mutation; the implementer breaks
  it, sees red, restores it by edit.
- Concurrency tests synchronise on `pg_locks` with a failing deadline; never a sleep.
- Before each commit: `./gradlew :modules:<m>:test :modules:<m>:ktlintCheck :modules:<m>:detekt`
  for every touched module. Commits are Conventional, subject ≤ 80 characters (the squash adds
  ` (#NNN)`), ending with the session's `Co-Authored-By` and `Claude-Session` lines.
- Never touch another worktree, the default compose project, port 8080/5432.

## Review Focus

1. **Leave and block are indistinguishable from the partner's side** after this change: the ledger,
   `closedProposals` and every push. Test in Task 9.
2. **A withdrawal while the opening push is held** → the opening push `CANCELLED`, nothing asked.
   Test in Task 8.
3. **Five changes in a minute** → one owed push, `not_before` the first's. Test in Task 2.
4. **The budget flip is a ruling, not a bent test**: `PushBudgetTest`'s "leaveFree 0 → REMAINDER"
   case for a per-Bond last slot flips under spec B19, with the commit saying so. Task 3.
5. **The deletion agreement reaches the proposer** in `PENDING_DELETION`. Test in Task 8.

---

### Task 1: V71, the category, the change and the toggle (notification)

**Model:** sonnet

**Files:**
- Create: `modules/notification/src/main/resources/db/migration/V71__notification_shared_setting_changed.sql` (spec §6.2, verbatim)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/api/Notifications.kt` (`PushCategory.SHARED_SETTING_CHANGED`, `SharedChange`, `PushRequest.sharedChange`, `NotificationPreferences.sharedSettings`, `allows`, `CancelReason`, `cancel(dedupeKeys, reason)`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/infra/database/NotificationStore.kt` (`NewPush.sharedChange`, `StoredPush.sharedChange`, `insertOnce`, `lock`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/PushRequests.kt` (pass through; `cancel` takes the reason and tags the metric with it)
- Modify (pass `sharedChange = null`): `modules/bond/.../service/NotifyBondMembers.kt`, `modules/gratitude/.../service/NotifyDayMembers.kt`, `.../SendOnThisDayPushes.kt`, `.../SendDailyReminders.kt`, and the test callers listed by `grep -rln "PushRequest(" modules app`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/SubmitEntry.kt:547` (`CancelReason.WRITTEN`)
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/api/PushApiTest.kt`, `modules/notification/src/test/kotlin/com/moyi/notification/infra/SharedSettingColumnsTest.kt` (new)

**Interfaces:**
- Produces: `enum class SharedChange { SETTING, PROPOSAL_ACCEPTED, PROPOSAL_DECLINED, PROPOSAL_WITHDRAWN, SEVERAL }`; `PushRequest(..., nudge: Nudge?, sharedChange: SharedChange?)`, `init`: `(sharedChange != null) == (category == SHARED_SETTING_CHANGED)` and `sharedChange != SEVERAL`; `NotificationPreferences.sharedSettings: Boolean = true`; `enum class CancelReason { WRITTEN, PROPOSAL_CLOSED }`; `Notifications.cancel(dedupeKeys: Collection<String>, reason: CancelReason): Int` (metric reason = `reason.name.lowercase()`). `PushCategory.SHARED_SETTING_CHANGED.isRevealClass == false`.

- [ ] **Step 1: Write the failing tests**
  - `PushApiTest`: `a shared change is required on the category and refused on any other`; `SEVERAL cannot be asked for`; `the sharedSettings toggle governs the category and is on by default`.
  - `SharedSettingColumnsTest`: a request with `SETTING` stored and read back by `lock`; a raw insert of a `REVEAL` with `shared_change` fails on `notifications_shared_change_check`; a `SHARED_SETTING_CHANGED` row without one fails; `'BOGUS'` fails.
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*PushApiTest" --tests "*SharedSettingColumnsTest"` → FAIL.
- [ ] **Step 3:** Write V71; add the types with KDoc citing ADR-0069; carry the value through; update every caller.
- [ ] **Step 4:** the same → PASS; `./gradlew :modules:notification:test :modules:gratitude:test :modules:bond:compileTestKotlin :app:compileTestKotlin`.
- [ ] **Step 5:** Mutation: drop the `category <> 'SHARED_SETTING_CHANGED'` half of the CHECK → the `REVEAL` case goes red. Restore.
- [ ] **Step 6: Commit** `feat(notification): V71, a shared-setting push category, its change and toggle`

### Task 2: coalescing, and calling off a Bond's owed pushes (notification)

**Model:** opus

**Files:**
- Create: `modules/notification/src/main/kotlin/com/moyi/notification/domain/Coalescing.kt`
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/PushRequests.kt`
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/infra/database/NotificationStore.kt`
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/api/Notifications.kt` (`cancelBond`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/domain/PushSettlement.kt` (the `CANCELLED` KDoc: superseded by a coalescing push, called off by a proposal's ending, or by the Bond's)
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/domain/CoalescingTest.kt`, `modules/notification/src/test/kotlin/com/moyi/notification/service/CoalescingLedgerTest.kt`, `modules/notification/src/test/kotlin/com/moyi/notification/service/CancelBondTest.kt`

**Interfaces:**
- Consumes: `SharedChange`, `StoredPush.sharedChange` (Task 1).
- Produces: `internal object Coalescing { val SETTLE: Duration = Duration.ofMinutes(2); fun coalesces(category: PushCategory): Boolean; fun merged(own: SharedChange, cancelled: Collection<SharedChange>): SharedChange }`. `NotificationStore.cancelOwedInGroup(userId, bondId, category, except: UUID): List<CoalescedPush>` (`CoalescedPush(notBefore: Instant, sharedChange: SharedChange?)`; one `UPDATE … WHERE user_id = ? AND bond_id = ? AND category = ? AND id <> ? AND status IN ('REQUESTED','HELD') RETURNING not_before, shared_change`); `NotificationStore.retime(id, notBefore, sharedChange)`; `NotificationStore.cancelOwedOfBond(bondId): List<CancelledPush>`. `Notifications.cancelBond(bondId: UUID): Int` (`MANDATORY`; metric reason `bond_ended`). `PushRequests.request`: for a coalescing category `notBefore = now + SETTLE`; after `insertOnce` records the row, `cancelOwedInGroup`, count each `superseded`, then `retime` to the earliest `notBefore` and `merged` change, all before the event is published.

- [ ] **Step 1: Write the failing tests**
  - `CoalescingTest` (pure): the five cases of spec §10; `coalesces` true only for `SHARED_SETTING_CHANGED`.
  - `CoalescingLedgerTest` (real ledger): `five requests leave one owed, with the first's not_before` (Review Focus 3); `the earliest not_before wins, a held one included` (seed one `REQUESTED` with `not_before` 05:59, ask at 06:00 → the new row's `not_before` 05:59, not 06:02; seed one `HELD` until 07:00, ask at 06:00 → 06:02); `different changes make SEVERAL`; `a re-ask cancels nothing and publishes nothing`; `another member's, another Bond's, another category's and a SENT push are untouched`; `a supersede waits for a send in progress and leaves it sent` (hold `lock(id)` in one transaction; the request blocks, seen in `pg_locks`; commit `SENT` → the older stays `SENT`, the new row keeps its own `not_before`).
  - `CancelBondTest`: owed pushes of both members, three categories → `CANCELLED`, count returned, `cancelled{reason=bond_ended}` per row; `SENT` and another Bond's untouched; without a transaction it refuses.
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*Coalescing*" --tests "*CancelBondTest"` → FAIL.
- [ ] **Step 3:** Implement as the interfaces say, in the caller's `MANDATORY` transaction.
- [ ] **Step 4:** → PASS; `./gradlew :modules:notification:test`.
- [ ] **Step 5:** Mutations: retime to the newest `not_before` → the five-requests test goes red; keep the newest change instead of `merged` → the `SEVERAL` test goes red. Restore.
- [ ] **Step 6: Commit** `feat(notification): coalesce shared-setting pushes, and call off a Bond's owed pushes`

### Task 3: the per-Bond last slot for every non-reveal push; `settling` (notification)

**Model:** opus

**Files:**
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/domain/PushBudget.kt` (the line and the class KDoc's "leaving room in its own Bond" paragraph and drop-reason order paragraph)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/SendPush.kt` (`settling` versus `held`)
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/domain/PushBudgetTest.kt`, `modules/notification/src/test/kotlin/com/moyi/notification/service/PushPreferencesBudgetTest.kt`, `modules/notification/src/test/kotlin/com/moyi/notification/service/PushMetricsTest.kt`

**Interfaces:**
- Produces: in `decide`, `!revealClass && wouldTakeRevealsLastSlot -> BudgetDecision.DROPPED_LEAVING_ROOM`, replacing `leaveFreeAcrossBonds > 0 && …`, same place in the order; signature unchanged. `SendPush.SETTLING = "settling"`: the hold is `settling` when `push.notBefore` is after now and quiet hours would not hold it later than that, else `held`.

- [ ] **Step 1: Write the failing tests**
  - `PushBudgetTest`: the spec B19 table as one test; per category (`MILESTONE`, `PARTNER_JOINED`, `PROPOSAL_OPENED`, `ON_THIS_DAY`, `SHARED_SETTING_CHANGED`, `REMINDER` with leave-free 0): `decide(3, true, 3, false, 0) == DROPPED_LEAVING_ROOM`. **Change** the existing nudge-1 case (`leaveFree 0` with the reserved slot spent and three sent: was `REMAINDER`) to `DROPPED_LEAVING_ROOM`, with a comment citing ADR-0069 decision 10 (Review Focus 4). Keep: reserved unspent and three sent → `REMAINDER` for a non-reveal push; reveal-class, reserved spent, three sent → `REMAINDER`.
  - `PushPreferencesBudgetTest`: the real day: `SOLO_UNLOCK` of D−1 `SENT` reserved, two reminders `SENT`, then a `SHARED_SETTING_CHANGED` → `DROPPED_BUDGET`, then the `REVEAL` → `SENT`.
  - `PushMetricsTest`: a push asked with `not_before` two minutes on and no quiet hours counts `settling`; one inside quiet hours counts `held`.
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*PushBudgetTest" --tests "*PushPreferencesBudgetTest" --tests "*PushMetricsTest"` → FAIL.
- [ ] **Step 3:** Change the line and the KDoc (it now says "a non-reveal push", cites ADR-0069 decision 10, and the order paragraph's "nudges held back" becomes "non-reveal pushes held back"); add `settling`.
- [ ] **Step 4:** → PASS; `./gradlew :modules:notification:test :modules:gratitude:test` (gratitude's `DailyRemindersTest` has ADR-0068's per-Bond day; if a case there asserted nudge 1 sent into the last slot, it flips by the same ruling: change it, cite it, report it).
- [ ] **Step 5:** Mutation: put back `leaveFreeAcrossBonds > 0` → the per-category and real-day tests go red. Restore.
- [ ] **Step 6: Commit** `feat(notification): no non-reveal push takes the slot a Bond's reveal needs`

### Task 4: the payload, per-change keys, `data.change` and the tag (notification)

**Model:** sonnet

**Files:**
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/api/PushSender.kt` (`PushMessage.sharedChange: SharedChange? = null`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/service/SendPush.kt` (pass `push.sharedChange`)
- Modify: `modules/notification/src/main/kotlin/com/moyi/notification/infra/push/FcmPushSender.kt` (`locKeys(message)`, `dataOf`, `androidTag`, class KDoc)
- Test: `modules/notification/src/test/kotlin/com/moyi/notification/infra/push/FcmPushSenderTest.kt`

**Interfaces:**
- Produces: `locKeys(message: PushMessage): Pair<String, String>` (every existing category unchanged; this category's title `push_shared_setting_changed_title`, body per spec B15); `androidTag` returns `SHARED_SETTING_CHANGED:{bondId}` for this category, the reminder's as today, else `null`; `data.change` only on this category.

- [ ] **Step 1: Write the failing tests:** each change's body key; `SEVERAL` and `SETTING` share the generic body; every other category's keys unchanged (one assertion per category); `data.change == "PROPOSAL_DECLINED"`; no `change` on a `REVEAL` even if a message carries one (the wire gate); the tag on this category; no tag on the five other non-reminder categories; channel `shared_setting_changed`.
- [ ] **Step 2:** `./gradlew :modules:notification:test --tests "*FcmPushSenderTest"` → FAIL.
- [ ] **Step 3:** Implement; the class KDoc's payload line names `change` and the second tag.
- [ ] **Step 4:** → PASS; `./gradlew :app:test --tests "*SecretsNeverLoggedTest"`.
- [ ] **Step 5:** Mutation: give `SEVERAL` the accepted body → its test goes red. Restore.
- [ ] **Step 6: Commit** `feat(notification): per-change words, data.change and a tag for shared-setting pushes`

### Task 5: V70, how a proposal ended (bond)

**Model:** opus

**Files:**
- Create: `modules/bond/src/main/resources/db/migration/V70__bond_proposal_endings.sql` (spec §6.1, verbatim)
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/domain/Proposal.kt` (`cancelledBy`, `cancelCause`, `CancelCause`, `ProposalOutcome`, `outcome`, the `require`s)
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/infra/database/BondProposalEntity.kt`, `ProposalMappers.kt`, `BondRepositories.kt`, `ProposalStore.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/service/ChangeTimezone.kt:167`, `RequestDeletion.kt:128`, `EnableSemanticSearch.kt:67`, `UpdateBond.kt:92` (pass the actor), `EndBond.kt:221` (unchanged call; the repository writes `BOND_ENDED`)
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/domain/ProposalTest.kt`, `modules/bond/src/test/kotlin/com/moyi/bond/infra/database/ProposalPersistenceTest.kt`, `modules/bond/src/test/kotlin/com/moyi/bond/infra/database/ProposalEndingsMigrationTest.kt` (new)

**Interfaces:**
- Produces: `enum class CancelCause { MEMBER, LAPSED, BOND_ENDED }`; `enum class ProposalOutcome { ACCEPTED, DECLINED, WITHDRAWN, LAPSED, BOND_ENDED, UNRECORDED }`; `Proposal.outcome: ProposalOutcome?` (null while open and unlapsed is the caller's to know: the domain states no clock predicate; an open row handed to it by the ended-read is `LAPSED`; KDoc says so) and `Proposal.closedBy: MemberId?`. `ProposalStore.cancel(id, by: MemberId, now)`, `cancelLive(bondId, kind, by, now)`; `closeLapsed` writes `LAPSED`; `cancelLiveOf` writes `BOND_ENDED` and no member; `findInBond(bondId, id): Proposal?`; `findLatestEndedOf(bondIds, now): Map<BondId, List<Proposal>>` (not live by `findLive`'s predicate, its complement written beside it in `BondRepositories`, latest per kind by `coalesce(confirmed_at, cancelled_at, expires_at)`).

- [ ] **Step 1: Write the failing tests**
  - `ProposalTest`: outcome for each cause; the proposer cancelling is `WITHDRAWN`, the other `DECLINED`; confirmed is `ACCEPTED` with `closedBy` the confirmer; `BOND_ENDED` names nobody; a `MEMBER` cause without a member is refused.
  - `ProposalPersistenceTest`: each path writes its cause and member (`cancel` by each member, `closeLapsed`, `cancelLiveOf`, `cancelLive`); `findLatestEndedOf` returns one per kind, the latest, including an unclosed lapsed row as `LAPSED`; a live row is never returned.
  - `ProposalEndingsMigrationTest`: rows written with the V69 schema (Flyway `target = 69`, then migrate) — a lapsed close, an ended close at `archived_at`, a member cancel — read `LAPSED`, `BOND_ENDED`, `UNRECORDED` after V70.
- [ ] **Step 2:** `./gradlew :modules:bond:test --tests "*ProposalTest" --tests "*ProposalPersistenceTest" --tests "*ProposalEndingsMigrationTest"` → FAIL.
- [ ] **Step 3:** Implement; every caller passes the actor's `membership.memberId`.
- [ ] **Step 4:** → PASS; `./gradlew :modules:bond:test`.
- [ ] **Step 5:** Mutations: make `cancelLiveOf` record a member (any member of the bond) → the CHECK or the `BOND_ENDED` test goes red; read `DECLINED` when `by == proposer` → the outcome test goes red. Restore.
- [ ] **Step 6: Commit** `feat(bond): V70, who called a proposal off and why, never naming a leaver`

### Task 6: the bond shows how proposals ended; the toggle on the wire (bond, contract)

**Model:** sonnet

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/service/BondViews.kt` (`BondView.closedProposals`), `service/BondEntityTag.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/web/BondResponse.kt` (`closedProposals`, `ClosedProposalResponse`, `id` on the two pending responses)
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/web/MemberNotifications.kt`, `infra/database/MemberNotifications.kt` (`sharedSettings`, stored key `sharedSettings`)
- Modify: `contracts/openapi.json` (regenerated by the command `OpenApiContractTest` prints; never by hand)
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondTimezoneEndpointTest.kt`, `BondDeletionEndpointTest.kt`, `SemanticSearchSettingTest.kt`, `BondNotificationsEndpointTest.kt`, `BondSettingsEndpointTest.kt`

**Interfaces:**
- Consumes: `findLatestEndedOf`, `Proposal.outcome`, `closedBy` (Task 5); `NotificationPreferences.sharedSettings` (Task 1).
- Produces: the contract of spec §8.

- [ ] **Step 1: Write the failing tests:** after each of decline, withdraw, accept, lapse (clock moved past seven days, no write) and leave, `GET /bonds/{id}` shows `closedProposals` with that outcome and `closedByMemberId` (null for lapse and leave); both members see the same entry; the pending deletion and semantic-search responses carry `id` equal to the closed entry's; the `ETag` changes when a proposal lapses with no write; `sharedSettings` true by default, set to false, kept when a later `PUT` omits it.
- [ ] **Step 2:** `./gradlew :modules:bond:test --tests "*EndpointTest" --tests "*SemanticSearchSettingTest"` → FAIL.
- [ ] **Step 3:** Implement; one batched query beside `findAllLiveOf`; regenerate the contract and read its diff (only additions).
- [ ] **Step 4:** → PASS; `./gradlew :modules:bond:test :app:test --tests "*OpenApiContractTest"`.
- [ ] **Step 5:** Mutation: leave `closedProposals` out of `entityTag` → the lapse-`ETag` test goes red. Restore.
- [ ] **Step 6: Commit** `feat(bond): show how each kind of proposal last ended, and a sharedSettings toggle`

### Task 7: the events, and ending a Bond calls off its owed pushes (bond)

**Model:** sonnet

**Files:**
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/service/BondSettingsChanged.kt`, `ProposalClosed.kt` (the `ProposalOpened.kt` shape; aggregate `Bond` and `BondProposal`; references `bondId`, `memberId`, and `proposalId`)
- Modify: `UpdateBond.kt`, `ChangeTimezone.kt`, `RequestDeletion.kt`, `EnableSemanticSearch.kt` (spec §4's table), `EndBond.kt` (`notifications.cancelBond(ended.id.value)` after `cancelLiveOf`)
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/service/SharedChangeEventsTest.kt` (new)

**Interfaces:**
- Consumes: `Notifications.cancelBond` (Task 2), `ProposalStore` (Task 5).
- Produces: the two event types, published only where spec §4 says.

- [ ] **Step 1: Write the failing tests** (read the outbox table): each row of spec §4's table publishes exactly one event of its type with the actor's member id; a same-value `PATCH` publishes none; leave, block, a lapse closed by a new proposal, a lone member's deletion and the PATCH's `cancelLive` publish no `ProposalClosed`; a leave turns a seeded owed push of the bond `CANCELLED`, and a block does the same.
- [ ] **Step 2:** `./gradlew :modules:bond:test --tests "*SharedChangeEventsTest"` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** → PASS; `./gradlew :modules:bond:test`.
- [ ] **Step 5:** Mutation: publish `ProposalClosed` from `EndBond` → the leave case goes red; drop `cancelBond` → its case goes red. Restore.
- [ ] **Step 6: Commit** `feat(bond): publish shared changes and proposal endings, and silence a Bond as it ends`

### Task 8: `bond.notify` tells the other member (bond)

**Model:** opus

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/service/NotifyBondMembers.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/api/PushAudiences.kt` (`PushAudience.hasEnded`), `service/PushAudiencesAdapter.kt`, `domain/Bond.kt` (`BondStatus.hasEnded`)
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/service/SharedSettingPushesTest.kt` (new), `BondPushesTest.kt` (the consumer's event set)

**Interfaces:**
- Consumes: the events (Task 7), `findInBond`, `outcome` (Task 5), `cancel(keys, PROPOSAL_CLOSED)` and `request(… sharedChange)` (Tasks 1–2).
- Produces: `eventTypes = {MemberJoined, ProposalOpened, BondSettingsChanged, ProposalClosed}`; key `shared:{event.id}:{userId}`; the opening key built by the same private function as `proposed`'s; counter `bond.notify.shared_changes{change, outcome}`; ids in log lines at DEBUG only.

- [ ] **Step 1: Write the failing tests** (real ledger and consumer): one per row of spec B9's table (who has a row, its key, its `change`; the actor none); the deletion agreement reaches the proposer while `PENDING_DELETION` (Review Focus 5; red under `takesWrites`); a change handled after the bond was archived asks nothing; a withdrawal while her opening push is `HELD` → that push `CANCELLED` (`proposal_closed`) and no row for her (Review Focus 2); an acceptance calls off the accepter's own `HELD` opening push; redelivery of an event asks nothing twice; `sharedSettings` off → `SUPPRESSED`; whether the other member wrote today changes nothing (T-09); the counter's outcomes.
- [ ] **Step 2:** `./gradlew :modules:bond:test --tests "*SharedSettingPushesTest" --tests "*BondPushesTest"` → FAIL.
- [ ] **Step 3:** Implement spec §4's consume block.
- [ ] **Step 4:** → PASS; `./gradlew :modules:bond:test :app:test --tests "*EventSubscriptionsTest"`.
- [ ] **Step 5:** Mutations: gate on `takesWrites` → the deletion-agreement test goes red; drop the `except` → the actor test goes red; drop the opening-push call-off → the held-withdrawal test goes red. Restore.
- [ ] **Step 6: Commit** `feat(bond): push the other member when a shared setting changes or a proposal ends`

### Task 9: leave and block stay silent and alike (bond, T-09)

**Model:** opus

**Files:**
- Modify: `modules/bond/src/test/kotlin/com/moyi/bond/web/DiscreetExitTest.kt`

**Interfaces:**
- Consumes: everything above. No production code; if a test here fails, stop and report: the spec, not the test, decides.

- [ ] **Step 1: Write the tests:** for a leave and for a block, each on a bond where Ada has a pending zone proposal, Ben a `HELD` setting push and a `HELD` reveal: no `SHARED_SETTING_CHANGED` row asked for anyone; the proposal `BOND_ENDED`, `cancelled_by_member_id` null; every owed row of the bond `CANCELLED` `bond_ended`; `GET /bonds/{id}` for the remaining member. Then **compare the leave run with the block run** for the remaining member: ledger rows (category, status, reason; ids aside) and the bond response (ids and times aside) equal (Review Focus 1).
- [ ] **Step 2:** `./gradlew :modules:bond:test --tests "*DiscreetExitTest"` → PASS (the mechanism is Tasks 5–8).
- [ ] **Step 3:** Mutations: in `EndBond`, record the blocker on `cancelLiveOf` for a block only → the comparison goes red; skip `cancelBond` on a block only → red. Restore.
- [ ] **Step 4: Commit** `test(bond): leave and block create no push and read alike after shared-setting pushes`

### Task 10: records, state map, smoke probes, and the whole build

**Model:** sonnet

**Files:**
- Modify: `adr/0044-push-transport-the-ledger-and-token-ownership.md` (decision 9, an "*Amended 2026-11-16 (issue #205)*" paragraph: `data.change`, the category's tag)
- Modify: `adr/0045-push-preferences-quiet-hours-and-the-budget.md` (decision 1: the seventh toggle)
- Modify: `adr/0046-which-events-push-and-to-whom.md` (decision 1's table: two rows; decision 4: ADR-0069 decisions 4 and 5; decision 7: ADR-0069 decision 3; decision 9's copy table: the five keys)
- Modify: `adr/0068-reminder-nudges-and-the-push-budget.md` (decision 19 and decision 7: amended notes pointing at ADR-0069 decisions 10 and 7)
- Modify: `adr/0030-two-party-consent-as-one-mechanism.md` (a cancel's cause and member)
- Modify: `adr/0069-shared-setting-pushes.md` (status stays Proposed; "Rulings while it was built" if any)
- Modify: `docs/state-map/data/bond.json`, `model.json`, `endpoints.json` (spec §11), then `scripts/state-map-generate`
- Modify: `scripts/smoke.sh` (a new section after the N3 pushes, spec §11)

- [ ] **Step 1: State map:** edit the data as spec §11 says; run `scripts/state-map-check` → passes; `scripts/state-map-generate`; the stamp is not moved.
- [ ] **Step 2: Smoke probes** as spec §11, a fresh pair and bond; then re-read the probes at `smoke.sh:3155` and `:3278` and any other that counts every category in a bond a `PATCH` now touches.
- [ ] **Step 3: Run** `scripts/smoke.sh` → every probe passes, the new ones included (paste the section's tail and the totals line).
- [ ] **Step 4:** Amend the ADRs; confirm `OpenApiContractTest` passes with the Task 6 contract.
- [ ] **Step 5: Run** `./gradlew build` → `BUILD SUCCESSFUL` (all tests, ktlint, detekt, architecture rules); paste the line and the test totals.
- [ ] **Step 6: Commit** `docs(push): shared-setting pushes in the ADRs and state map, with smoke probes`
