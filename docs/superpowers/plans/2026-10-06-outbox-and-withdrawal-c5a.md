# Slice C5a, the outbox poller and withdrawal — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [x]`) syntax for tracking.

**As built (2026-10-07).** The slice is built and ADR-0035 is its record. The build changed
several things this plan says; they are listed at the end, under "As built — where the
slice left this plan". From decision 8 on, this plan's decision numbers are not the ADR's.
The text below is the plan as written, with the steps ticked and a note where a step was
done differently.

**Goal:** A member who blocks (or leaves) can take their own words back for both people:
the words stop being readable the moment the request commits, and are erased shortly after
by the outbox's first consumer.

**Architecture:** `common:events` gains a consumer registry, publish-time fan-out to
`outbox_deliveries`, and a dispatcher that claims one due delivery per transaction with
`FOR UPDATE SKIP LOCKED`, runs its handler and acknowledges it in that same transaction.
`modules/scheduling` owns the two-second trigger and the meters, as it owns the close job's.
`bond` records a withdrawal in a table of its own, in the block's transaction, and shows it
on `BondMembership`; `gratitude`'s one read gate (`Entry.canBeReadBy`) treats a withdrawn
author's entries as erased from that instant, and its consumer erases them through the same
routine `DELETE /entries/{id}` uses.

**Tech stack:** Kotlin, Spring Boot 4.1, Postgres 18 (Testcontainers), `JdbcTemplate` for the
outbox, JPA for the domain, Micrometer.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` §4, §6.7, §8, §9
("Withdrawal and outbox"); doc 03 FR-029a; doc 04 BR-10a; doc 26 §5.1; `adr/0028`
(decision 8), `adr/0031` (Owed, C5), `adr/0032` (Owed, C5; question 1).

**C5 is three pull requests, not one.** This is the first. C5b is the archive and
favourites; C5c is reactions. The spec's §7 gives C5 one migration; this plan takes **V20**
(`common:events`) and **V21** (`bond`), C5b takes V22, C5c V23, and C6 moves to V24.

Branch `feat/gratitude-archive` off `main` (`9c8eb31`), worktree
`.worktrees/feat-gratitude-archive`.

## Global constraints

- Never commit to `main`; the owner merges. Conventional Commits, subject ≤ 88 characters.
- Test first, seen to fail for the stated reason. After a task's tests pass, break the
  mechanism and see the named test fail; say which in the report.
- Entry text never reaches a log, an exception message, an event payload or
  `outbox_deliveries.last_error`.
- **Nothing anywhere says "block"** (ADR-0028 decision 8): no event type, consumer id, log
  line, table, column, metric or error code may tell a block from a leave. The words are
  "withdraw" and "end".
- A leave and a block answer the same bytes and the same `ETag` afterwards, with or without
  a withdrawal (`DiscreetExitTest`). The marker must not move `bonds.version`.
- Lock order is bond, then bond-day, then entry. Never hold two days of one bond unless the
  older was taken first.
- Truncate instants to microseconds before storing them.
- Concurrency tests synchronise on a lock held or a statement blocked, never on a sleep.
- V20 and V21 are applied to no shared database: smoke with `MOYI_DB=<name>`, dropped after.
- The module test contexts share one Testcontainers database. A test that runs the
  dispatcher sees every due delivery in it: clean `outbox_deliveries` **before** the test,
  and assert on the rows the test made, not on totals.

## Decisions this plan makes (each goes into ADR-0035)

1. **Delivery rows are written when the event is published**, in the publisher's
   transaction, one per subscribed consumer, read from `outbox_subscriptions`. There is no
   scan for "events with no delivery yet", so nothing depends on event ids being in commit
   order (ADR-0032, Owed, C5).
2. **A consumer is registered at start-up under a table lock.** Registration takes
   `LOCK TABLE outbox_events IN SHARE ROW EXCLUSIVE MODE`, which waits for every
   transaction that has already published and blocks every one about to; then it writes the
   subscription and backfills deliveries for the events it can now see. A publisher that was
   blocked reads the new subscription when it resumes. No event falls between the two.
3. **A consumer names where it starts**: `BEGINNING` (every retained event of its types) or
   `NOW` (only events published after it registered). Backfilled rows are due at once.
4. **One delivery, one transaction.** Claim (`FOR UPDATE SKIP LOCKED`), handle, acknowledge,
   commit. A handler that throws rolls its own work and the claim back; the failure is then
   recorded in a second transaction: `attempts + 1`, `next_attempt_at` pushed out,
   `last_error` set to the exception's **class name only**.
5. **Backoff is 2 s doubling to a cap of 15 min, and there is no last attempt.** A
   withdrawal must never be given up on. A delivery that has failed five times or more is
   counted by a gauge of its own; that gauge is the dead-letter queue.
6. **No ordering is promised.** A failed delivery steps aside and later ones proceed, so a
   handler must give the same result whatever order events arrive in, and must tolerate the
   same event twice.
7. **`SKIP LOCKED` is the concurrency control; the poller takes no ShedLock.** Two instances
   polling at once is the design, not a fault to be locked out.
8. **The event is `EntriesWithdrawn {bondId, memberId}` on aggregate `Bond`**, not
   `BondBlocked {withdrawEntries}`: payloads are ids only, and the name must not say block.
   The consumer id is `gratitude.withdrawal`.
9. **The marker is a row in `bond_entry_withdrawals (bond_id, member_id)`**, insert-only,
   and is shown to other modules as `BondMembership.withdrawnMemberIds`.
10. **`POST /block` and `POST /leave` take an optional body `{"withdrawEntries": boolean}`.**
    Absent, it is `true` for block and `false` for leave (FR-029a: "on blocking, given by
    default"; "on leaving, offered"). A repeat block may withdraw what the first declined.
11. **The read gate treats a withdrawn author's entry as erased.** `Reader` carries the
    withdrawn member ids; `Entry.canBeReadBy` is the one place that asks. Every read and
    replay already goes through it.
12. **The consumer erases through the routine `DELETE` uses**, entry by entry, oldest day
    first, under the bond lock and each day's lock, in the delivery's transaction. A
    withdrawal and a run of single deletes leave the same rows.
13. **An author may delete their own entry on an ended bond, and after leaving it** (the
    owner's ruling, 2026-10-06, on ADR-0032 question 1). `PATCH` stays `409 BOND_ARCHIVED`.

## Review focus

- A read that commits after the block and before the poller runs shows no withdrawn text:
  `GET /today`, and an `Idempotency-Key` replay of `POST /entries` and of `PATCH`.
- Two dispatchers at once deliver each event once, and a handler that fails is retried
  without holding up the others.
- A consumer that registers while events are being published misses none.
- A partner's never-revealed entry, withdrawn, is `REMOVED` with no id and no timestamps,
  before and after the consumer runs.
- A withdrawal on the in-flight day leaves a day the close job can still settle.

---

### Task 1: The consumer registry and publish-time fan-out

**Files:**
- Create: `common/events/src/main/resources/db/migration/V20__common_outbox_consumers.sql`
- Create: `common/events/src/main/kotlin/com/moyi/common/events/EventConsumer.kt`
- Create: `common/events/src/main/kotlin/com/moyi/common/events/ConsumerRegistry.kt`
- Modify: `common/events/src/main/kotlin/com/moyi/common/events/JdbcEventPublisher.kt`
- Modify: the migration-contiguity test (`FlywayMigrationTest`, find it with `grep -rn "FlywayMigrationTest"`)
- Test: `common/events/src/test/kotlin/com/moyi/common/events/ConsumerRegistryTest.kt`,
  `EventPublisherTest.kt`

**Interfaces — produces:**

```kotlin
package com.moyi.common.events

/** An event as a consumer receives it: what was published, and its id to deduplicate by. */
data class ReceivedEvent(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val references: Map<String, UUID>,
    val occurredAt: Instant,
)

enum class StartFrom { BEGINNING, NOW }

interface EventConsumer {
    /** Stable for ever: it is the key delivery state is kept under. */
    val id: String
    val eventTypes: Set<String>
    val startFrom: StartFrom

    /** Runs in the delivery's transaction. Throwing rolls it back and schedules a retry. */
    fun handle(event: ReceivedEvent)
}
```

```sql
CREATE TABLE outbox_consumers (
    consumer_id   text        PRIMARY KEY CHECK (char_length(consumer_id) BETWEEN 1 AND 100),
    registered_at timestamptz NOT NULL
);
CREATE TABLE outbox_subscriptions (
    consumer_id   text        NOT NULL REFERENCES outbox_consumers (consumer_id),
    event_type    text        NOT NULL,
    subscribed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer_id, event_type)
);
CREATE INDEX outbox_subscriptions_by_type_idx ON outbox_subscriptions (event_type);
ALTER TABLE outbox_deliveries
    ADD CONSTRAINT outbox_deliveries_consumer_fk FOREIGN KEY (consumer_id) REFERENCES outbox_consumers (consumer_id);
```

`ConsumerRegistry` is a `@Component` taking `List<EventConsumer>`; it registers in
`SmartInitializingSingleton.afterSingletonsInstantiated`, so that no request is served
before registration (`ApplicationReadyEvent` would be after the port opens). It also exposes
`fun consumer(id: String): EventConsumer?` for the dispatcher.

Registration, per consumer, in one transaction:

1. `LOCK TABLE outbox_events IN SHARE ROW EXCLUSIVE MODE`.
2. Upsert `outbox_consumers`.
3. For each event type with no `outbox_subscriptions` row: insert it, and when
   `startFrom == BEGINNING` backfill
   `INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at) SELECT id, ?, now() FROM outbox_events WHERE event_type = ? ON CONFLICT DO NOTHING`.
4. A subscription the consumer no longer declares is deleted; its deliveries stay.

Two consumers with one id fail start-up (`IllegalStateException`).

The publisher, after its insert, in the same statement batch:

```sql
INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at)
SELECT ?, consumer_id, ? FROM outbox_subscriptions WHERE event_type = ?
```

`next_attempt_at` is the event's `occurredAt`, truncated.

- [x] **Step 1: failing tests.** `ConsumerRegistryTest`: a new consumer gets its two tables'
  rows; `BEGINNING` backfills an event published before it registered, `NOW` does not;
  registering twice writes nothing new; a dropped event type loses its subscription and
  keeps its deliveries; duplicate ids refuse to start. `EventPublisherTest`: publishing an
  event of a subscribed type writes one delivery per subscriber and none for an
  unsubscribed type; a rolled-back publisher leaves neither event nor delivery.
- [x] **Step 2: the race, as a test that synchronises on a blocked statement.** Connection
  A opens a transaction and publishes an event (does not commit). Start registration on
  another thread; poll `pg_stat_activity`/`pg_locks` until it is waiting on the table lock.
  Commit A. Assert the consumer has a delivery for A's event. Then the mirror: hold
  registration's transaction open (test seam: register through a `TransactionTemplate` the
  test controls), start a publisher, see it blocked, commit registration, assert the
  publisher's event has a delivery. **Mutation:** remove the `LOCK TABLE`; the first test
  must fail.
- [x] **Step 3: implement; run** `./gradlew :common:events:test`.
- [x] **Step 4:** update the migration-contiguity assertion to V20. Run it.
- [x] **Step 5: commit** `feat(events): consumers register under a lock; deliveries are written at publish`.

### Task 2: The dispatcher

**Files:**
- Create: `common/events/src/main/kotlin/com/moyi/common/events/OutboxDispatcher.kt`
- Create: `common/events/src/main/kotlin/com/moyi/common/events/Backoff.kt`
- Test: `common/events/src/test/kotlin/com/moyi/common/events/OutboxDispatcherTest.kt`,
  `BackoffTest.kt`

**Interfaces — consumes:** Task 1's `EventConsumer`, `ReceivedEvent`,
`ConsumerRegistry.consumer(id)`.

**Interfaces — produces:**

```kotlin
interface OutboxDispatcher {
    /** Delivers what is due at [now], at most [budget] deliveries. Safe to run concurrently. */
    fun dispatchDue(now: Instant, budget: Int): DispatchResult
    /** Due and unprocessed, per consumer; and the age in seconds of the oldest such delivery. */
    fun backlog(now: Instant): List<ConsumerBacklog>
}
data class DispatchResult(val delivered: Int, val failed: Int, val more: Boolean)
data class ConsumerBacklog(val consumerId: String, val pending: Long, val failing: Long, val oldestAgeSeconds: Long)

internal object Backoff {
    /** 2 s, 4 s, 8 s … capped at 15 min. [attempts] is the count after this failure, from 1. */
    fun delayAfter(attempts: Int): Duration
}
```

One delivery, inside `TransactionTemplate.execute`:

```sql
SELECT d.event_id, d.consumer_id FROM outbox_deliveries d
WHERE d.processed_at IS NULL AND d.next_attempt_at <= ?
ORDER BY d.next_attempt_at, d.event_id
LIMIT 1 FOR UPDATE OF d SKIP LOCKED
```

then load the event, call `consumer.handle(event)`, then
`UPDATE outbox_deliveries SET processed_at = ?, attempts = attempts + 1 WHERE event_id = ? AND consumer_id = ?`.
A delivery whose consumer id has no bean in this process is skipped, not failed: another
instance may own it (select with `AND d.consumer_id = ANY(?)` over the registered ids).

On an exception from the transaction: a new transaction runs
`UPDATE … SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE … AND processed_at IS NULL`
with `last_error = exception::class.java.name`, logs at `WARN` the consumer id, event id,
event type, attempt count and the class name — **not the message, not the stack trace's
message** — and the loop goes on. `failing` counts unprocessed deliveries with
`attempts >= 5`.

- [x] **Step 1: failing tests.** Delivered once and acknowledged; the handler's write and
  the acknowledgement commit together (handler writes a probe row, then a second handler
  throws after writing: no probe row, delivery unprocessed, `attempts = 1`,
  `next_attempt_at = now + 2 s`, `last_error` is the class name and does not contain the
  exception's message — use a message that looks like entry text); a failing delivery does
  not stop a later one in the same run; not due is not delivered; `budget` is honoured and
  `more` is true when work remains; a delivery for an unknown consumer id is left alone;
  `backlog` counts per consumer. `BackoffTest`: 1→2 s, 2→4 s, 10→15 min, 1000→15 min (no
  overflow).
- [x] **Step 2: concurrency.** Two dispatchers, one event, a handler that blocks on a latch
  the test holds: the second `dispatchDue` returns `delivered = 0` while the first is
  inside the handler (it skipped the locked row). Release; the handler ran once.
  **Mutation:** remove `SKIP LOCKED`'s row lock (`FOR UPDATE`); the handler runs twice or
  the second call blocks — the test must fail.
- [x] **Step 3: implement; run** `./gradlew :common:events:build`.
- [x] **Step 4: commit** `feat(events): the dispatcher — claim, handle and acknowledge in one transaction`.

### Task 3: The poller

**Files:**
- Create: `modules/scheduling/src/main/kotlin/com/moyi/scheduling/service/OutboxJob.kt`
- Modify: `modules/scheduling/build.gradle.kts` (`implementation(projects.common.events)`)
- Modify: `scripts/smoke.sh` only if the job needs a property to run there (it should not:
  two seconds is its default)
- Test: `modules/scheduling/src/test/kotlin/com/moyi/scheduling/service/OutboxJobTest.kt`

`OutboxJob` mirrors `CloseJob`: `@Scheduled(fixedDelayString = "\${moyi.scheduling.outbox.delay:PT2S}")`
calling `dispatchOnce()`, **no `@SchedulerLock`** (decision 7; say why in the KDoc). It stays
off wherever `moyi.scheduling.enabled=false`, as the close job does. `BUDGET = 200` per run.
Meters, registered once, refreshed from `OutboxDispatcher.backlog` after each run and tagged
`consumer`: `gratitude.outbox.pending` (doc 11's `gratitude_outbox_pending`),
`gratitude.outbox.age.seconds.max`, `gratitude.outbox.failing`; counters
`gratitude.outbox.delivered` and `gratitude.outbox.failed`. A run that throws (the database
is away) is logged at `WARN` and does not kill the schedule.

- [x] **Step 1: failing tests**: `dispatchOnce` delivers a due event through a test
  consumer and moves the counters; the pending gauge reads 1 before and 0 after; the
  failing gauge reads 1 for a delivery with five attempts; a dispatcher that throws does
  not propagate.
- [x] **Step 2: implement; run** `./gradlew :modules:scheduling:build`.
- [x] **Step 3: commit** `feat(scheduling): the outbox poller, every two seconds, and its meters`.

### Task 4: `bond` records a withdrawal

**Files:**
- Create: `modules/bond/src/main/resources/db/migration/V21__bond_entry_withdrawals.sql`
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/infra/database/EntryWithdrawals.kt`
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/web/EndBondRequest.kt`
- Modify: `bond/service/EndBond.kt`, `bond/web/BondsController.kt`,
  `bond/api/BondAccess.kt` (`BondMembership`), `bond/service/BondAccessAdapter.kt`,
  `modules/bond/build.gradle.kts` (`implementation(projects.common.events)`)
- Test: `bond/web/BondEndingEndpointTest.kt`, `bond/web/DiscreetExitTest.kt`,
  `bond/api/BondAccessTest.kt`; `contracts/openapi.json` regenerated

```sql
-- A member took their own entries back when the bond ended (FR-029a, BR-10a).
-- Written in the transaction that ends the bond, so every read can honour it
-- from that commit on, before anything has been erased. Insert-only.
CREATE TABLE bond_entry_withdrawals (
    bond_id      uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    member_id    uuid        NOT NULL,
    withdrawn_at timestamptz NOT NULL,
    PRIMARY KEY (bond_id, member_id)
);
```

**Interfaces — produces:**

```kotlin
// bond.api.BondMembership gains, last:
/** Members of this bond who have withdrawn what they wrote. Their entries are erased, or about to be. */
val withdrawnMemberIds: Set<UUID>,
```

`EndBond.leave(membership, withdrawEntries: Boolean)` and
`EndBond.block(membership, withdrawEntries: Boolean)`. When `withdrawEntries`, after the
bond is ended and inside the same transaction: `EntryWithdrawals.insertIfAbsent(bondId, memberId, now)`
(`ON CONFLICT DO NOTHING`, returns whether it inserted); when it inserted, publish
`OutboxEvent("Bond", bondId, "EntriesWithdrawn", mapOf("bondId" to bondId, "memberId" to memberId), now)`.
A repeat block with the flag on, after one with it off, inserts and publishes; a second
with it on does neither. `leave` on an ended bond is still `409` and withdraws nothing.

The request body is optional (`@RequestBody(required = false)`); `withdrawEntries` is a
nullable `Boolean`; a body that is not JSON, or a non-boolean value, is the standing `422`.
The log lines do not change.

- [x] **Step 1: failing tests.** Block with no body writes the marker and one event; block
  with `false` writes neither; leave with no body writes neither; leave with `true` writes
  both; a second block with `true` writes nothing more; block `false` then block `true`
  writes both; leave `true` on an ended bond is `409` with no marker; the marker leaves
  `bonds.version` and the `ETag` unchanged; `membershipOf` and `lockMembershipOf` report
  the withdrawn member to **both** members; the event's payload is exactly the two ids;
  a block that rolls back leaves no marker and no event.
- [x] **Step 2: `DiscreetExitTest`** — a block with withdrawal and a leave without answer
  the same status, headers and (empty) body, and `GET /bonds/{id}` afterwards is
  byte-identical with the same `ETag`, read as the other member.
- [x] **Step 3: implement.** Regenerate the contract with the command `OpenApiContractTest`
  prints; read the diff (two optional request bodies, nothing else).
- [x] **Step 4: mutation**: publish outside the `if (inserted)`; "a second block writes
  nothing more" must fail.
- [x] **Step 5: run** `./gradlew :modules:bond:build`; **commit**
  `feat(bond): a member may withdraw their entries when leaving or blocking (FR-029a)`.

### Task 5: The read gate honours the marker at once

**Files:**
- Modify: `gratitude/domain/EntryReading.kt` (`Reader`), `gratitude/domain/Entry.kt`
  (`canBeReadBy`), `gratitude/service/BondCalendars.kt` (`asReader`)
- Test: `gratitude/domain/EntryReadabilityTest.kt`,
  create `gratitude/web/WithdrawalReadTest.kt`

```kotlin
internal class Reader(val memberId: UUID, val bondId: UUID, val withdrawnAuthors: Set<UUID>)

// Entry.canBeReadBy, clause 2:
val erased = isErased || authorMemberId in reader.withdrawnAuthors
```

`asReader()` passes `withdrawnMemberIds`. Nothing else changes: the gate is asked by every
read. `Entry.isErased` itself does **not** change (it is the row's own state, and the edit
rule and the reveal use it).

- [x] **Step 1: domain tests** in `EntryReadabilityTest`: a withdrawn author's live entry is
  `TOMBSTONE` to its author, `TOMBSTONE` to a partner it was revealed to,
  `TOMBSTONE_UNSEEN` to a partner it was not; the other member's entries are untouched;
  membership still comes first.
- [x] **Step 2: `WithdrawalReadTest`, with no dispatcher run at all** (the poller stopped):
  two members write and the day reveals; A blocks. Then, for **both** A and B:
  `GET /today` shows A's entry with `text: null`, `status: DELETED`, and B's entry whole;
  a replay of A's original `POST /entries` key answers `Idempotency-Replayed: true` with
  no text; a replay of A's `PATCH` key likewise. A second scenario where only A wrote
  (never revealed): B sees `{authorMemberId, status: REMOVED}` and nothing else — assert
  the exact key set. Assert the rows still hold their text (nothing was erased yet), so
  the test proves the gate and not the consumer.
- [x] **Step 3: mutation**: drop the `in reader.withdrawnAuthors` clause; Step 2 must fail.
- [x] **Step 4: run** `./gradlew :modules:gratitude:build`; **commit**
  `feat(gratitude): a withdrawal hides its author's words from the commit on`.

### Task 6: An author may delete their own entry after the bond has ended

**Files:**
- Create: `gratitude/service/EraseEntry.kt`
- Modify: `gratitude/service/ChangeEntry.kt`
- Test: `gratitude/web/EntryChangesTest.kt`

Extract from `ChangeEntry.changeUnderLocks` the part that erases one entry once the bond
lock is held:

```kotlin
@Component
internal class EraseEntry(private val days: BondDayStore, private val entries: EntryStore) {
    /**
     * Erases one entry. The caller holds the bond's lock; this takes the day's, then the
     * entry's. A day that is not settled steps back (`BondDay.withoutEntry`); a settled
     * one does not change. Idempotent. Returns the entry as it now stands and its day.
     */
    fun erase(entryId: EntryId, dayId: BondDayId, now: Instant): Pair<Entry, BondDay>
}
```

`ChangeEntry` keeps the joining-day ordering it has (`lockDays`) and calls this for a
delete. The archived/left refusal becomes: `if (replacement != null && (membership.hasLeft || !membership.isOpen)) throw BondArchivedException()`.

- [x] **Step 1: failing tests** in `EntryChangesTest`: `DELETE` by the author is `204` on a
  bond that ended by leave, by block, and by the *other* member leaving, for the member
  who left and the one who stayed; the partner then sees the tombstone; repeatable;
  `PATCH` on the same bonds is still `409 BOND_ARCHIVED`; the partner's `DELETE` is still
  the one `404`; a delete during a deletion countdown (`PENDING_DELETION`) is `204`.
  Find and turn the existing test that pinned the `409` (ADR-0032 question 1 says one
  test pins it).
- [x] **Step 2: implement; mutation**: restore the unconditional refusal; the new tests fail.
  *As built:* the author was cut off by the network before running it. A reviewer ran it
  afterwards, among twenty-five on this commit and Task 7's: red, three tests.
- [x] **Step 3: run** `./gradlew :modules:gratitude:build`; **commit**
  `feat(gratitude): an author can delete their own entry after the bond has ended`.

### Task 7: The withdrawal consumer

**Files:**
- Create: `gratitude/service/WithdrawEntries.kt`
- Modify: `gratitude/infra/database/EntryStore.kt` + repository (a finder for the live
  entry ids of one author in one bond, with each entry's day id and date, oldest day first)
- Modify: `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt` (who may call
  `lockClosingViewOf`: add `WithdrawEntries.kt`, and rename `THE_CLOSER` to say what the
  set now is)
- Test: create `gratitude/service/WithdrawEntriesTest.kt`

```kotlin
@Component
internal class WithdrawEntries(/* BondAccess, EntryStore, EraseEntry, Clock */) : EventConsumer {
    override val id = "gratitude.withdrawal"
    override val eventTypes = setOf("EntriesWithdrawn")
    override val startFrom = StartFrom.BEGINNING

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent)
}
```

`handle`: read `bondId` and `memberId` from `event.references` (a missing one is an
`IllegalStateException`: retried, counted, never silently dropped); take the bond lock with
`access.lockClosingViewOf(bondId)` (a bond that is gone: nothing to do, return); list the
author's live entries oldest day first; `EraseEntry.erase` each. No event is published, no
day status is recomputed for a settled day, `author_deleted_account` is not touched.

- [x] **Step 1: failing tests** (each publishes through a real block, then calls
  `OutboxDispatcher.dispatchDue`): every live entry of the withdrawing member in that bond
  has `text` null, `status = DELETED`, `deleted_at` set; the other member's entries, and
  the same member's entries in **another bond**, are untouched; `revealed_at` survives;
  settled days keep their status and count; an unsettled day steps back and
  `CloseDay` then settles it without revealing anything (run the closer after); running
  the delivery twice (reset `processed_at` to null) changes nothing; the rows match, column
  for column except the timestamps, what a run of `DELETE /entries/{id}` leaves on a twin
  bond; the delivery is acknowledged; with the handler made to throw on the third entry,
  none is erased and the delivery is retried.
- [x] **Step 2: the race** — hold the bond's row lock from another connection; the
  dispatcher's handler is seen blocked on it; release; it completes. **Mutation:** remove
  the bond lock; this test fails.
- [x] **Step 3: implement; run** `./gradlew build` (the whole build: the architecture test
  is in `app`). *As built:* no report gives a count at this commit (`6de2a67`); its author
  was cut off. The next task's build started from it and was green.
- [x] **Step 4: commit** `feat(gratitude): the outbox's first consumer erases withdrawn entries`.

### Task 8: Smoke, the tools, and the record

**Files:**
- Modify: `scripts/smoke.sh` (a new section: two members write and reveal; A blocks; within
  ten seconds, polling once a second, B's `GET /today` shows A's entry as a tombstone and
  the database row has no text; block with `{"withdrawEntries": false}` on a second bond
  leaves the text; `DELETE` own entry on the ended bond is `204`)
- Modify: `scripts/moyi` (`block` and `leave` take `--keep-entries` / `--withdraw-entries`),
  `tools/bruno` (the two requests' bodies)
- Create: `adr/0035-the-outbox-poller-and-withdrawal.md` (the thirteen decisions above;
  Consequences; Owed; Questions that are the owner's; How this was checked)
- Modify: the spec (§6.7, §7, §8, §10 amended in place, dated, as earlier slices did);
  `adr/0032` question 1 marked ruled; `adr/0028` (the optional body); `README.md` module
  notes; `docs/learning-log.md`; `.github` nothing

Questions that are the owner's, to be written into the ADR as built-one-way:
block's default when no body is sent (`true`); no working export exists before the
destructive act (export is Phase 5); nothing ever removes old `outbox_events` (doc 07's
"30 days after processing" has no meaning now delivery is per consumer); withdrawal on
leave uses the same destructive mechanism.

- [x] **Step 1:** smoke section; run `MOYI_DB=moyi_c5a_smoke scripts/smoke.sh`; record the
  counts; drop the database.
- [x] **Step 2:** tools; run the CLI block against the jar once. *As built:* `scripts/moyi`
  had no `leave` or `block` and `tools/bruno` no such requests; both were added. The CLI
  was run against a jar with each flag and with none. The Bruno requests were not opened
  in Bruno.
- [x] **Step 3:** ADR, spec, logs. `./gradlew build --rerun-tasks`; record the test count.
  *As built:* the build was green with 1210 tests at `5d2a889`; the report does not say
  `--rerun-tasks`.
- [x] **Step 4: commit** `docs: ADR-0035 and what slice C5a makes of the spec`. *As built:*
  several commits, the ADR's own being `84d25c1`.

## After the tasks

Whole-branch review by three readers (concurrency; privacy and contract; spec conformance),
fixes test-first, then a draft pull request with the concept brief, labelled
`breaking-api-change` if the contract check asks for it.

## As built — where the slice left this plan (ADR-0035)

Seven reviews of the built slice changed what this plan says. ADR-0035 is the record. Its
decisions are numbered differently from this plan's from 8 on: plan 8 to 13 are ADR 9, 10,
11, 12, 13 and 15, and ADR 8 and 14 are new.

- The branch's base is `f5a65ba`, not `9c8eb31`.
- Registration's wait for the table lock is bounded, and the publisher refuses any
  isolation above READ COMMITTED (ADR decision 2). Backfilled deliveries are stamped from
  the injected clock, not SQL `now()` (decision 3).
- The claim matches (consumer, declared event type) pairs, not `consumer_id = ANY(?)`. The
  handler's exception never leaves the transaction callback (decision 4). The failure
  record is guarded by `processed_at IS NULL` and can only move the next attempt later
  (decision 5).
- The poller runs on a thread of its own, not the scheduler's, goes round up to ten times
  while a pass fills its budget, and a delivery has a sixty-second limit (decision 7).
- There are six meters, not five. The two counters carry no `consumer` tag, and the age
  gauge is the event's age (decision 8).
- A body that cannot be read is `400 MALFORMED_REQUEST`, not `422`, and the body is read
  strictly: an unknown key or a repeated one is refused (decision 11).
- The contract's diff was not "two optional request bodies, nothing else": the generator
  added `400` and `422` to both routes, and the `409` on `DELETE /entries/{entryId}` was
  later removed.
- The log lines did change. A leave and a block now write one line (decision 11).
- The marker is also on `BondClosingView`; this plan's decision 9 put it on the membership
  only (ADR decisions 10 and 14).
- This plan's decision 12, "the same rows", is narrower: the same entry rows and the same
  answers (ADR decision 13). The consumer requires the marker and fails without it.
- New, and in no task here: whoever reaches a day first erases (`EraseWithdrawnEntries`,
  ADR decision 14). It was built as a second part of Task 7.
- Thirteen decisions became fifteen, and the seventh task was built in two parts.
