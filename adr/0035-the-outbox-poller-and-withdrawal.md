# ADR-0035 — The outbox poller and withdrawal

**Status:** Proposed · **Date:** 2026-10-07 · **Deciders:** Daniel

## Context

Since C2 every change of state has written an event to `outbox_events`, and nothing has read
one. Spec §8 put the reader in slice C5: a poller every two seconds, delivery state kept per
consumer, at least once. Spec §6.7 gave it its first consumer: when a member blocks the other
and takes their own entries back (FR-029a, BR-10a), `bond` ends the bond and `gratitude`
erases the entries, and the outbox carries the one to the other because a call back from
`bond` into `gratitude` would be a module cycle. §6.7 also set the hard requirement: the
words must stop being readable when the block's transaction commits, with the poller stopped,
and not when the erasure happens.

Before the slice was planned, the product corpus was read against the spec for everything C5
touches. That read found 22 contradictions (X1 to X22) and 26 things the corpus is silent on.
Eleven of the contradictions concern this record and are listed under "The corpus" below.
Four changed the design: the spec's event `BondBlocked {withdrawEntries}` broke two built
rules at once (X8); FR-029a requires the offer on leaving too, and the spec covered only block
(X6); a member who had already left had no way to take words back at all (X6, X15); and the
spec's one migration for C5 could not hold a change to `bond` (X20).

**C5 is three pull requests.** With 22 contradictions to settle, one pull request holding the
poller, withdrawal, the archive, favourites and reactions would have been too large to
review. This is **C5a**, the poller and withdrawal. C5b is the archive and favourites; C5c is
reactions. The migrations follow: **V20** (`common:events`) and **V21** (`bond`) here, V22
for C5b, V23 for C5c, and C6 moves to V24.

The slice was built in seven tasks, each by an implementing agent and then read by an
independent reviewer who ran the code. The plan
(`docs/superpowers/plans/2026-10-06-outbox-and-withdrawal-c5a.md`) made thirteen decisions.
Fifteen are recorded here. Where one differs from the plan, it says what changed and which
review caused it; "How this was checked" lists them.

Two terms. A **delivery** is one row of `outbox_deliveries`: one event owed to one consumer.
The **marker** is a row of `bond_entry_withdrawals`: the bond's own record that a member
withdrew.

## Decision

**1. A delivery is written when its event is published, one per subscribed consumer, in the
publisher's transaction.** `JdbcEventPublisher` inserts the event and then, in a second
statement, one `outbox_deliveries` row for each row of `outbox_subscriptions` with that event
type, due from the event's own `occurredAt`. An event and what is owed for it commit or
vanish together.

*Rejected:* a poller that scans for events with no delivery yet. It needs a cursor, and
ADR-0032's Owed note says why a cursor fails: event ids are time-ordered at insert, not at
commit, so a cursor by id skips an event whose transaction committed late. With fan-out at
publish there is no scan, and nothing depends on the order of ids.

**2. A consumer registers at start-up under a table lock, with a bounded wait.**
`ConsumerRegistry` runs in `SmartInitializingSingleton.afterSingletonsInstantiated`, which is
after Flyway and before the web server accepts a connection (`ApplicationReadyEvent` is after
the port opens). For each consumer, in one transaction, it takes
`LOCK TABLE outbox_events IN SHARE ROW EXCLUSIVE MODE`, writes the consumer and its
subscriptions, and backfills (decision 3). The mode conflicts with the `ROW EXCLUSIVE` lock
every insert takes, and with itself. That closes three interleavings:

- A publisher that has inserted and not committed. Registration waits for it, so the event
  is committed and in the backfill.
- A publisher about to insert. It waits at its insert until registration commits; its
  fan-out is a separate statement and sees the new subscription.
- Two instances registering the same consumer. One waits for the other.

**The wait is bounded.** `register` first issues `SET LOCAL lock_timeout` (written as
`set_config('lock_timeout', …, true)` so it takes a parameter and ends with the transaction),
from `moyi.events.registration.lock-timeout`, default ten seconds; zero is refused because
Postgres reads it as no limit. The review of Task 1 proved why: while registration waits,
every new publisher queues behind it, so one session left idle in a transaction hung the
starting instance silently and stopped every submission and close on the instances already
running. Bounded, the start fails with a message naming the consumer and the bound, the
context does not come up, and the publishers move again. No instance runs with a consumer it
has not registered.

**The publisher refuses any isolation stricter than READ COMMITTED.** The second
interleaving rests on the fan-out taking a fresh snapshot. The reviewer proved that a
REPEATABLE READ publisher held back by a registration resumed on its old snapshot and
committed an event with no delivery. So the event's insert carries
`WHERE current_setting('transaction_isolation') IN ('read committed', 'read uncommitted')`
and a publication that inserted nothing throws. The level is asked of the database and not
of Spring, which answers null for "the default" and cannot see a level set on a role or by
hand. No publisher uses another level today.

**3. A consumer names where it starts.** `StartFrom.BEGINNING` backfills a delivery for
every retained event of the type; `NOW` receives only events published after it registered.
It is asked once per (consumer, event type), when the subscription row is first written, so a
restart cannot repeat a backfill. Backfilled deliveries are due at once, stamped from the
injected clock and not SQL `now()`: the dispatcher compares against that clock, and tests pin
it. `gratitude.withdrawal` starts from `BEGINNING`. A type a consumer stops declaring loses
its subscription and keeps the deliveries already owed.

**4. One delivery, one transaction: claim, handle, acknowledge.** `JdbcOutboxDispatcher`
claims one due delivery with `FOR UPDATE OF d SKIP LOCKED`, calls the consumer, and sets
`processed_at` in the same transaction. A handler marked `@Transactional(MANDATORY)` joins
it. There is no moment at which an entry is erased and the delivery still owed, or the
reverse.

- **Only (consumer, declared event type) pairs this process has are claimed.** The plan
  matched on the consumer id alone. The review of Task 1 showed what that does in a rolling
  deploy: two builds of one consumer run side by side, and the old build, which never
  declared a new type, claims that type's deliveries and acknowledges events it was not
  written to handle. A delivery that fails the test is left unlocked for the instance that
  owns it.
- **The handler's exception is caught inside the transaction callback.** The transaction is
  marked rollback-only and the exception is carried out as a value. Allowed out through
  `TransactionTemplate`, Spring logs it whole at DEBUG on every rollback, and at ERROR when
  the rollback itself fails (the review of Task 2 proved both). A handler works on entries,
  and its exception may quote one.
- **What would fail at commit is made to fail before it.** After the acknowledgement the
  dispatcher flushes pending JPA writes and issues `SET CONSTRAINTS ALL IMMEDIATE`. An
  exception raised at commit belongs to the transaction manager, outside the callback: it is
  logged whole at DEBUG, and Postgres words a violated constraint with the row. The
  consumer's own test found this. **The schema has no deferred constraint today**, so the
  statement guards a future one; the tests build theirs with a constraint trigger.
- `dispatchDue` refuses to run inside a caller's transaction, where every delivery would
  join it. A `VirtualMachineError` is recorded against its delivery and then rethrown.

**5. A failure is recorded by exception class name only, and retried for ever.** After the
rollback a second statement, in a transaction of its own, sets `attempts = attempts + 1`,
`last_error` to the exception's class name, and
`next_attempt_at = greatest(next_attempt_at, now + backoff)`. No message is stored and no
throwable is handed to a logger: ADR-0032 held `last_error` to the rule for a log line.

The statement is guarded by `AND processed_at IS NULL`, picks the wait from the count it
finds in the row, and can only move the next attempt later. All three came from the review
of Task 2: between the rollback and this statement the delivery is unlocked and due, so
another instance may run it. As first built, a delivery acknowledged in that gap was then
marked failed, and two instances failing together both waited the first step.

Backoff is 2 s, doubling to a cap of 15 minutes (`Backoff`; the tenth failure reaches the
cap). **There is no last attempt and no dead-letter queue**: a withdrawal must never be
given up on. A delivery unprocessed after five failures or more is counted by a gauge of its
own (decision 8), and that gauge is the dead-letter queue.

**6. No ordering is promised.** A failed delivery steps aside and later ones proceed. A
handler must give the same result in any order and tolerate the same event twice
(`EventConsumer`'s contract).

**7. No ShedLock on the poller; it runs on a thread of its own; a delivery has a time
limit.** The close job takes a ShedLock because two instances closing a day is work done
twice. Here `SKIP LOCKED` is the concurrency control: a second instance passes over what the
first holds, and two instances sharing the queue is the design.

`OutboxJob.run` is called by the timer every two seconds (`moyi.scheduling.outbox.delay`)
and only hands the tick to a single daemon thread the job owns; while a tick runs, the
timer's ticks are skipped, not queued. The plan ran the tick on the scheduler's thread. The
review of Task 3 proved what that rested on: with `spring.threads.virtual.enabled` off,
Spring Boot's scheduler has one thread, and one held handler stopped the close job's cron
from firing at all, with no log line. The setting is on, for the web server; nothing tied a
day's closing to it. `PollerIndependenceTest` holds a handler and sees the close job fire
under both schedulers. A tick takes up to 200 deliveries a pass and goes round again, at
most ten times, only while a pass filled its budget.

A delivery may take `moyi.outbox.delivery-timeout`, default sixty seconds, kept twice: as
the transaction's deadline, and as `statement_timeout` set on the server for the transaction.
**It bounds** any statement and any wait for a lock; a handler that spent the time away from
the database fails at its next statement or at the acknowledgement. **It does not bound** a
handler that is neither at a statement nor returning: parked, computing, or waiting on
something that is not this database. Nothing interrupts a thread, and until such a handler
returns its transaction holds the delivery and every lock it took.

**8. Six meters.** Three gauges per consumer, tagged `consumer`:
`gratitude.outbox.pending` (due and unprocessed), `gratitude.outbox.age.seconds.max`, and
`gratitude.outbox.failing` (unprocessed after five failures or more, due or not). Two
counters, `gratitude.outbox.delivered` and `gratitude.outbox.failed`. One gauge,
`gratitude.outbox.last.success.timestamp`: epoch seconds of the last tick that could read
the backlog the gauges show.

- **The age is the event's**, the time since `occurred_at` of the oldest event with an
  unprocessed delivery, due or not. As first built it measured how long a delivery had been
  due. A delivery retrying at the cap is due again every fifteen minutes, so its age could
  never exceed that, and doc 11's alert on "oldest event older than fifteen minutes" could
  not fire for the case it exists for.
- **The gauges are refreshed in a `finally`**, after a tick that threw as well. As first
  built a tick that threw skipped the refresh, so a dispatcher failing on every tick left
  every gauge flat. When the backlog itself cannot be read the gauges keep their last
  values, and the last-success timestamp stops moving: that is what an alert can see.
- **The two counters carry no consumer tag.** A run reports totals; tagging them means
  per-consumer counts in `DispatchResult`, a data class compared whole in about 35
  assertions. It costs doc 11's alerts nothing: they read the two gauges.

**9. The event is `EntriesWithdrawn {bondId, memberId}` on aggregate `Bond`.** Not the
spec's `BondBlocked {withdrawEntries}`, which broke two built rules (X8). A payload is a map
of name to UUID (ADR-0032 decision 12) and cannot hold a boolean. And ADR-0028 decision 8
forbids anything that says "block": an event type is a durable record read by every consumer
and written into log lines. The event is named for its effect, which also serves a leave.
The consumer's id is `gratitude.withdrawal`.

**10. The marker is a row in `bond_entry_withdrawals (bond_id, member_id, withdrawn_at)`**
(V21), insert-only, written by `EndBond` in the transaction that ends the bond. The event is
published only by the call that wrote the row, so a repeat is one withdrawal and one event.

- **It does not move `bonds.version`.** The version is the `ETag` both members hold
  (ADR-0028 decision 5); a version that moved for a withdrawal would tell the other member
  that something happened on a bond that takes no writes.
- **Other modules read it as `withdrawnMemberIds`**, on `BondMembership` and on
  `BondClosingView`: the same set whichever member asks, and read after the bond's lock when
  the locking accessor is used. The plan put it on the membership only; decision 14 is why
  the closer's view has it too.
- `member_id` carries no foreign key. A cascade would delete the marker with the member row
  and un-hide entries not yet erased. The row goes with its bond.

**11. `POST /leave` and `POST /block` take an optional body `{"withdrawEntries": boolean}`.**
Absent, the flag is `true` for block and `false` for leave: FR-029a says "given by default"
on blocking and "offered" on leaving. No body, `{}`, a JSON `null` body and an explicit
`null` value are all "absent". **Withdrawal is offered on leave too**, which FR-029a
requires and the spec did not cover.

- **The body is read strictly.** Only JSON `true`, `false` or `null` is accepted as the
  value; an unknown key and a key sent twice are refused; each is the standing
  `400 MALFORMED_REQUEST` (the plan said `422`; the application's answer for an unreadable
  body is `400`). Jackson's own reading took `"true"` and `1` as true and `""` as null, so
  `{"withdrawEntries": ""}` sent to a block withdrew. Worse, and proved by the review of
  Task 4: Jackson ignores a key it does not know, so `{"withdrawEntry": false}` on `/block`
  was an absent flag, and the caller who declined in so many words was withdrawn from,
  irreversibly. `EndBondRequestReader` is a reader for the whole type, because the two
  lenient behaviours are mapper-wide settings.
- **A non-JSON `Content-Type` with an empty body is now `415`**; it was `204`. The smoke
  script and the CLI send JSON or no content type.
- **Parsing before the guard is not an existence oracle.** The reviewer sent 154 requests
  across malformed, non-JSON and readable bodies as a member, a stranger, to a bond that
  does not exist and to an id that is not a UUID: each refusal was the same bytes for all of
  them. A test now pins that for three bodies on both routes.
- **A repeat block may withdraw what the first declined.** `leave` on an ended bond is still
  `409` and withdraws nothing.
- **Both endings write the same log line**, "A member ended bond {id}". The code had logged
  "left" for a leave and "ended" for a block since #39, against ADR-0028 decision 8, which
  says one line. Neither said "block"; the line still told which it was.

**12. The read gate treats a withdrawn author's entry as erased.** `Reader` carries
`withdrawnAuthors`, with no default, built only from a `BondMembership`. `Entry.canBeReadBy`
is the one place that asks: `isErased || authorMemberId in reader.withdrawnAuthors`. From
the commit of the ending, both people get a tombstone, the author included: the wide one
for a reader who could read the entry before, `{authorMemberId, status: REMOVED}` for a
partner it was never revealed to. `Entry.isErased` does not change; it is the row's own
state, which the edit rule and the reveal read.

Every path that returns entry content goes through the gate:

| Path | Where its `Reader` comes from |
|---|---|
| `GET /bonds/{id}/today` | `membershipOf`, in the controller |
| `POST /bonds/{id}/entries`, fresh | `lockMembershipOf`, in the write's transaction |
| `POST …/entries`, replay | `membershipOf` |
| `PATCH /entries/{id}`, fresh | `lockMembershipOf` |
| `PATCH /entries/{id}`, replay | `membershipOf` |

`DELETE` returns no body and `GET /streak` no entry content. A fresh write cannot meet a
marker over HTTP: a marker exists only on an ended bond, where a write is `409`.

**13. The consumer erases through `EraseEntry`, the routine `DELETE` uses.**
`WithdrawEntries.handle` takes the bond's lock, lists the member's live entries in that bond
oldest day first, and erases each through `EraseEntry`, in the delivery's transaction. So
each erasure is a single delete by construction: text and media references nulled,
`status = DELETED`, `deleted_at` set, `revealed_at` kept, an unsettled day stepped back, a
settled day unchanged. No event is published, and `author_deleted_account` is not touched
(X16).

**What is claimed is the same entry rows and the same answers to every reader as a run of
single deletes leaves.** Not the same day rows in every case. The plan claimed "the same
rows", and a reviewer proved one case where that is false: on a joining day still
`SUSPENDED` that nobody has read since the pairing, a withdrawal leaves the day
`SUSPENDED` with a count of zero where delete-then-end leaves `OPEN` with zero, because a
`DELETE` reconciles the joining day first and the consumer does not. The API's answers were
identical, and the first read or the close brings the two rows level. Accepted, not fixed.

- **It checks the bond's own record and fails without it.** The plan trusted the event.
  Erasure cannot be undone, so the handler requires the member to be in
  `withdrawnMemberIds`; an event with no marker throws, is retried, and shows in the failing
  gauge. `bond` writes the two together, so the code cannot produce one.
- A bond that is gone is acknowledged: no later attempt could find more. An event missing a
  reference fails.
- **Measured:** 2,000 entries in one delivery take about 5 s on a developer's laptop
  (2.5 ms an entry, linear once the persistence context is cleared between entries; 9.2 s
  before). The 60 s limit is reached near 24,000 entries. A delivery that reached it would
  fail the same way on every retry and show as failing; the cure is to erase in batches that
  each commit, which redelivery already tolerates.

**14. Whoever reaches a day first erases.** The gate hides the words; it does not stop the
code that writes. The review of Task 5 proved it. Ada and Bea both write on a day waiting
for its reveal time; Ada blocks; the close job runs before the consumer and reveals the day.
Bea's view of Ada's withdrawn entry gained its id and timestamps. Ada read Bea's words after
taking her own back. The streak went from 0 to 1. A midnight form: a lone entry, withdrawn
just after its day ended, was stamped revealed and the day closed `SOLO` where a delete
leaves `EMPTY`. The reveal stamp outlives the erasure, so the outcome depended on who took
the bond's lock first.

So every path that reveals or settles a day first erases a withdrawn author's live entries
on it, through the same `EraseEntry` (`EraseWithdrawnEntries.on`), and decides from the day
that leaves: `CloseDay` (the reveal rule, the reveal at the day's end and the `SOLO` close
are one transaction after it) and `ReconcileJoiningDay.underBondLock`. Closer first,
consumer first, and the author deleting by hand end in identical rows and identical answers
(`WithdrawalRaceTest`, compared against a twin bond).

- *Rejected:* teaching the reveal to skip a withdrawn author's entry. That is a second
  account of what an erasure does, to be kept in step with the first.
- `SubmitEntry` reveals too and has no pre-step: a marker exists only on an ended bond, and
  a write there is refused before a day is touched. A new path that reveals on an ended bond
  must call it.
- The erasure is in the day's transaction and is neither counted against the close job's
  budget nor reported: a count would say that somebody withdrew.

**15. An author may delete their own entry after the bond has ended, and after leaving it.**
The owner's ruling of 2026-10-06 on ADR-0032 question 1. `DELETE /entries/{id}` is `204` for
its author on a bond that ended either way, for the member who left and the one who stayed,
and during a deletion countdown. `PATCH` stays `409 BOND_ARCHIVED`: an ended bond takes no
new words. The reason is this slice's: if a single delete were refused on an ended bond,
tombstones appearing after the end could only mean a block (X15). The partner still gets the
one `404`.

## Consequences

- **Migrations V20 (`common:events`) and V21 (`bond`).** Neither has been applied to a
  shared database.
- **The API gains two optional request bodies** and a `400` on the two routes. Additive: no
  new error code. Two behaviours change for an existing client: a non-JSON content type with
  an empty body is `415`, and an author's `DELETE` on an ended bond is `204` where it was
  `409`.
- **The application now does a second thing on its own**, every two seconds, on every
  instance, and start-up now takes a table lock for a moment. `moyi.scheduling.enabled=false`
  turns the poller off with the close job.
- **A publisher must run at READ COMMITTED.** A `@Transactional(isolation = …)` on any path
  that publishes fails at the publish, by design.
- **The text outlives a withdrawal on disk** until the consumer runs: two seconds normally,
  as long as the poller is stopped or the delivery backs off otherwise. It cannot be read
  through the API in that time (decision 12), and a day that is closed or revealed in that
  time is erased first (decision 14).
- **A withdrawal holds the bond's row lock for the whole erasure**, and every row lock of
  the member's history at once. The bond has ended; only an author's own delete and a
  joining-day reconcile wait on it.
- **Backoff counts from the start of the pass, not from the failure.** A delivery that fails
  by its 60 s limit has already outlived the first steps, so it is retried on the next tick
  about six times before the wait is felt, and holds the one poller thread for a minute each
  time. This delays erasure and nothing else. Found by review, not fixed.
- **The claim sorts every due row.** `ORDER BY next_attempt_at, event_id, consumer_id`
  cannot use V14's partial index. A reviewer measured 4 ms a claim at 200 due rows and 26 ms
  at 20,000. Irrelevant at this size; the first thing to index when it is not.
- **None of the meters can be read by anyone yet**, as with the close job's (ADR-0033).
  Every instance reports the same database-wide numbers, so an alert takes the maximum
  across instances, not the sum.
- **A reader of the database can tell a withdrawal from a delete on one kind of day, for a
  while**: the unread `SUSPENDED` joining day of decision 13. A reader of the API cannot.

## Owed

**ADR-0032's "Owed, C5" and ADR-0031's "Owed — C5, withdrawal on block" are discharged**:
polling by delivery row (decision 1), `last_error` (decision 5), a replay consulting the
marker (decision 12, `WithdrawalReadTest`). ADR-0032's first note stands for whoever
consumes `EntrySubmitted`: there is no event for an erasure, so read the entry before acting.

**C5b, the archive and favourites.**

- A closed `SOLO` day whose entry has no `revealedAt` is private to its author (ADR-0033
  decision 9, as ruled). The archive must go through `Entry.canBeReadBy`, which already
  answers so, and must not infer readability from the day's status.
- A delete keeps the row, so no foreign-key cascade will ever remove a favourite (X5).
  Favourites are removed in `EraseEntry`, which serves `DELETE`, the withdrawal and decision
  14 at once.
- The default page of 20 days can exceed NFR-008's 256 KB: 20 days, two entries, 8,192 bytes
  each is 327,680 bytes of text alone (X11).
- The second route by entry id joins `EntryChangesTest`'s route set (ADR-0032).

**C6.** Whatever search data an entry gains must be cleared by `EraseEntry` too (spec §6.7:
"search data").

**The deploy slice.** Export the meters; alert on the pending and age gauges (doc 11), on
`failing` above zero, and on the last-success timestamp going stale; a Prometheus registry,
which decides the exported names. Keep `org.springframework.transaction`, `.jdbc` and
`.orm.jpa` off TRACE in production: a `MANDATORY` handler's exception is printed there by
Spring's interceptor.

**Phase 4.** The notification consumer. It registers with `NOW` or accepts a backfill of
every event since C2 under the table lock; the backfill scans `outbox_events` by type with
no index.

**Phase 5.** Export, which the block's confirm copy promises "first" (question 2).

**Not assigned.**

- **Retiring a consumer is a migration.** A consumer whose bean is removed keeps its
  subscriptions; publishers go on writing deliveries nobody claims, and the pending and age
  gauges for it never return to zero. The same holds for the outstanding deliveries of a
  type a consumer has dropped.
- **Nothing removes old `outbox_events` or processed deliveries.** Doc 07's "30 days after
  processing" has no meaning now that processing is per consumer (X9), and a `BEGINNING`
  consumer needs the history.

## Questions that are the owner's

Each is built one way and cheap to turn.

1. **A bare `POST /block` withdraws.** The default destroys on an absent field. It is
   FR-029a's wording ("given by default") and doc 26's reason: the person blocking should
   not have to ask. A client that sends `{"withdrawEntries": false}` keeps the entries.
   Turning it is one `?: true` in `BondsController`.
2. **No working export exists before the destructive act.** Doc 04 rests the destructive
   reading on "the confirm flow offers an export first". Export is Phase 5. Until then a
   withdrawal destroys the only copy. The spec said to reject withdrawal as unsupported
   until the read path was complete; it is complete, and it is not rejected.
3. **Withdrawal on leave is the same destructive mechanism.** BR-10a speaks of "the
   blocker's own entries" and the leave screen's copy still promises "you keep your archive"
   (X7). Built: one mechanism, off by default on leave.
4. **Before the erasure, a day's status can tell a withdrawal from a delete.** A lone
   withdrawn entry leaves the day `PARTIAL` where a deleted one leaves `OPEN`; two pending
   entries leave `PENDING_REVEAL` where a delete leaves `PARTIAL`. The partner sees
   `REMOVED` beside a day that still counts the entry, and the status changes when the
   consumer runs, normally within two seconds. Accepted, so that a read never rewrites a
   day. A reviewer's twin-bond comparison; `WithdrawalReadTest` pins `PARTIAL`.
5. **`GET /today` resolves the caller's membership before it reads the entries**, in two
   steps with no lock. An ending that commits between them is missed by that one response.
   The replays have the same shape. Read by a reviewer, not run.
6. **On a legacy joining day, a withdrawal and a manual delete differ.** A day `SUSPENDED`
   with two entries, which no request can produce now. A withdrawal leaves it unrevealed in
   every order. The author's own `DELETE` is itself a gratitude operation, so it resumes and
   reveals the day before erasing. The two cannot be made equal there.
7. **Postgres `transaction_timeout` is not used.** It would bound a handler parked outside
   the database (decision 7) by killing its session. The only handler does database work
   only.
8. **Retries never stop** (decision 5). A delivery that can never succeed is retried every
   fifteen minutes for ever and stays in the failing gauge until somebody acts.
9. **The close job reveals a day both wrote on after the bond has ended, when neither
   withdrew.** Found by this slice's last reviewer and not this slice's code. Ada and Bea
   both write on a day waiting for its reveal time; Bea leaves, or blocks and keeps her
   entries, that afternoon; at the reveal time, or at the day's end, `CloseDay` reveals the
   day and each reads the other's words. The 2026-10-06 ruling (ADR-0033 decision 9) stops
   the unlock of a *lone* entry on a bond that ended first; `CloseDay` asks that only on the
   `SOLO` branch, and the reveal of a `PENDING_REVEAL` day does not ask whether the bond has
   ended. Both wrote for a bond that was still theirs, so revealing may be right. Doc 09
   T-20 says leaving "revokes access to *new* content", and these words become readable
   after the leaving. Built: revealed. The reviewer ran it at `6de2a67`; no test pins it
   either way.

### The corpus

What the corpus read found that concerns this slice, and what became of each. The corpus
documents are not edited here.

- **X6** — doc 06 gives `/block` and `/leave` no body, and FR-029a's offer on leaving had no
  route. *Resolved, decision 11.* Doc 06 owes the body.
- **X7** — the leave confirm promises retention; BR-10a decided destruction, for "blocking".
  *Built one way; question 3.* The strings owe a change.
- **X8** — `BondBlocked {withdrawEntries}` breaks ids-only payloads and "nothing says
  block". *Resolved, decision 9.*
- **X9** — doc 07's outbox retention against the replay promise. *Left; Owed.*
- **X10** — doc 11 has two unlabelled gauges, the spec one per consumer and no age gauge.
  *Resolved, decision 8:* both, per consumer. The withdrawal consumer's backfill is of an
  event type with no history, so it cannot trip the pending alert; a later `BEGINNING`
  consumer of `EntrySubmitted` would.
- **X15** — on an ended bond only a block could cause tombstones. *Narrowed, decisions 10
  and 15:* a single delete now can too, and the marker moves no `ETag`. What remains: the
  defaults differ, so every entry turning to a tombstone at the moment of ending is still
  evidence of a block.
- **X16** — what erasure sets, between BR-10 and BR-10a. *Resolved, decision 13:* what
  `DELETE` sets; `author_deleted_account` stays false.
- **X17** — the in-flight day when a bond ends. *The reveal was ruled on 2026-10-06*
  (ADR-0033 decision 9); a withdrawn entry on that day is decision 14. Doc 04's "closed
  immediately" is still not what is built: the day closes at its end.
- **X18** — "export first" ships before export. *Left; question 2.*
- **X20** — one migration for C5, and a `bond` change. *Resolved:* V20 and V21.
- **X21** — doc 05 and ADR-0008 say the outbox's first handlers are notifications. *Left
  for the corpus.*

The silences it named for withdrawal and the outbox (S16 to S26) are decided above: the
request and its defaults (11); scope, every live entry of that member in that bond and no
other bond (13); what is read in between (12); the marker is not reversible (10); budget,
backoff and no last attempt (5, 7); no ordering (6); the starting position (3); meter names
and `last_error` (8, 5); no ShedLock (7). Retention is Owed.

## Revisit when

- A second consumer registers. Decisions 2 to 6 have run for one consumer and one event
  type; the backfill under the table lock and the untagged counters get their first real
  use.
- A second instance is deployed: `SKIP LOCKED` and the guarded failure record have only met
  a second dispatcher in tests.
- A handler talks to anything but this database: decision 7's limit stops bounding it.
- A deferred constraint is added to the schema: decision 4 forces it before commit.
- The failing gauge is ever above zero in production: question 8.
- Export exists: question 2, and a request for an export followed at once by a block races
  the erasure unless the export snapshots first.

## How this was checked

"Run" means executed and seen by the party named. Implementers and reviewers each worked in
their own checkout; reviewers wrote probes that assert nothing and print what they saw.

- **Run by the implementing agents:** each task's tests were seen to fail before the code
  existed, and `./gradlew build` was green at each commit but one. Tests in the build's
  result files: 1089 at `4f8a842`, 1113 at `c495d1f`, 1131 at `b3db2e4`, 1135 at `99694e1`
  (`--rerun-tasks`, all 217 tasks), 1153 at `294e940`, 1166 at `176949c`, 1179 at `d77c076`,
  1185 at `26cfb2d`, 1202 at `f236d73`, 1204 at `7fad97a`. At `6a06bcf` only
  `:common:events:build` was run (45 tests). Except at `99694e1`, modules whose inputs had
  not changed were up to date and not re-executed.
- **Not run by any task before the last:** `scripts/smoke.sh`. The smoke run, the rerun of
  the whole build at the head of the branch and the CI contract check are recorded on the
  pull request, with the commit they ran at. One implementer ran oasdiff locally at
  `294e940` (0 errors, 0 warnings, 6 informational changes); CI uses a different packaging
  of it, and that verdict was not run by anyone during the tasks.
- **Found by review, not by a test written first.** Each was proved by the reviewer running
  it, and each now has a test seen to fail with its fix removed:
  - the reveal between a withdrawal and its erasure, in both forms (decision 14);
  - a misspelt key on `/block` withdrawing (decision 11);
  - the poller starving the close job with virtual threads off (decision 7);
  - gauges that went flat when the dispatcher threw on every tick (decision 8);
  - a REPEATABLE READ publisher losing its delivery, and an unbounded wait at start-up
    (decision 2);
  - the handler's exception logged whole by Spring when the rollback failed (decision 4);
  - the age gauge blind to a delivery that keeps failing (decision 8);
  - an `Error` at hand-over stopping the poller for good (`7fad97a`);
  - the two endings' different log lines (decision 11).
- **Found by the consumer's own test:** the commit-time exception logged at DEBUG with the
  row (decision 4).
- **Mutations.** Each is a mechanism removed, a named test seen to fail, the file restored.
  Run by the implementers: 6 on the registry, 16 on the dispatcher, 8 on the registry's
  fixes, 10 on the poller, 7 on the failure record, 13 on `bond`'s withdrawal, 11 on the
  poller's fixes, 2 on the read gate, 6 on the strict reader and the closing view, 5 on
  decision 14, 3 on the last fixes. All were caught but one: "leave withdraws before it
  refuses an ended bond" survived and is equivalent, because the `409` rolls the marker
  back. One dispatcher mutation survived at first because its test's budget of one was met
  before the foreign delivery could be claimed; the test was strengthened.
- **Mutations that survived a reviewer and were answered with a test:** the table lock taken
  in one transaction and the writes in the next (all 14 of Task 1's tests stayed green); the
  failure record's `processed_at IS NULL` guard removed; `withdrawnMemberIds` read before
  the bond's lock; and three tests that claimed more than they checked: one named "at any
  level" that raised two packages to DEBUG, one for counters "counted pass by pass", one for
  a WARN no test read.
- **Two commits' mutations were run afterwards, by a reviewer.** The authors of `d420edf`
  (decision 15) and `6de2a67` (decision 13) were cut off by network failures before running
  theirs. An independent reviewer ran twenty-five on the two commits. Thirteen were caught
  by the commits' own tests, among them: the ended-bond refusal restored, the day not
  stepped back, the partner let through, `PATCH` allowed, the bond lock removed, the marker
  check skipped, the author predicate dropped, a bulk `UPDATE` in place of `EraseEntry`,
  `SET CONSTRAINTS` removed, a gone bond made to throw. Three survivors are equivalent to
  the code. **The rest survived, at `6de2a67`:**
  - `EraseEntry` reading the entry without taking its lock or reading it fresh, in two
    forms: caught only by a neighbouring class, `CloseRaceTest`, and by neither commit's
    tests.
  - `status.flush()` in the dispatcher: no test. No handler in the build leaves a write
    unflushed.
  - the `forgetLoaded()` call, and its flush: no test, not even the 2,000-entry one, which
    is still inside its bound at 9 s. An optimisation.
  - newest day first in place of oldest: no test, and none can show it, because no other
    holder of two days can run beside the consumer.
  - the `bond_id` predicate in the consumer's query: no test. It is redundant: a member id
    belongs to one bond.
  - `EraseEntry`'s check that the entry is filed on the day it was given, and the order of
    `PATCH`'s refusals on an ended bond (`409` before `422`): no test.

  **All of these are open at `7fad97a`,** the head this record was written at. Tests for the
  first two were being written then; whether they landed is on the pull request.
- **Found by review after the twin test had passed:** decision 13's qualification. The twin
  test compared a withdrawal with a run of deletes and was green; the reviewer's probe put
  both on an unread `SUSPENDED` joining day, which the twin test had not, and the day rows
  differed.
- **Seen once and not reproduced:** two log-leak tests in `WithdrawEntriesTest` failed on a
  reviewer's run and passed on thirty more. They search log lines for `ada-` and `bea-`,
  which are hexadecimal digits and a hyphen, so a UUID can spell them. The mechanism is the
  reviewer's reasoning, not a reproduction.
- **Read, not run:** that no path renders an entry except through the gate (two readers,
  by search); that a marker exists only on an ended bond, which is what excuses `SubmitEntry`
  from decision 14 and a fresh write from decision 12; the lock order of every new path
  (bond, day, entry, days oldest first), with one edge held by a test (the handler waits for
  a held bond row); registration running before the port opens, which is Spring's ordering;
  the exported meter names, there being no registry to export them; question 5.
- **Corrected by a reviewer:** the Task 7 report said V8 and V13 hold deferred constraints.
  They do not; no migration declares one.
