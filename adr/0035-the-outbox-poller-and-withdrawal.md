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
Twelve of the contradictions concern this record and are listed under "The corpus" below.
Four changed the design: the spec's event `BondBlocked {withdrawEntries}` broke two built
rules at once (X8); FR-029a requires the offer on leaving too, and the spec covered only block
(X6); a member who had already left had no way to take words back at all (X6, X15); and the
spec's one migration for C5 could not hold a change to `bond` (X20).

**C5 is three pull requests.** With 22 contradictions to settle, one pull request holding the
poller, withdrawal, the archive, favourites and reactions would have been too large to
review. This is **C5a**, the poller and withdrawal. C5b is the archive and favourites; C5c is
reactions. The migrations follow: **V20** (`common:events`) and **V21** (`bond`) here, V22
for C5b, V23 for C5c, and C6 moves to V24.

The slice was built in eight tasks (the seventh in two parts), each by an implementing
agent. Tasks 1 to 7 were each then read by an independent reviewer who ran the code; the
branch as a whole was then read against the spec, and that review's corrections are in this
record. The plan
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

Backoff is 2 s, doubling to a cap of 15 minutes, with no jitter (`Backoff`; the tenth
failure reaches the cap). **There is no last attempt and no dead-letter queue**: a
withdrawal must never be given up on. A delivery unprocessed after five failures or more is
counted by a gauge of its own (decision 8), and that gauge is the dead-letter queue.

**6. No ordering is promised.** A failed delivery steps aside and later ones proceed. A
handler must give the same result in any order and tolerate the same event twice
(`EventConsumer`'s contract). Spec §8 says handlers deduplicate by event id; nothing
enforces it and the one consumer does not: it is idempotent by the state it leaves, and
nothing stores the ids it has handled. A consumer with an external effect (Phase 4) will
need the id.

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
  script and the CLI send JSON or no content type. `BondEndingEndpointTest` pins it on both
  routes, with the same answer for a stranger as for a member.
- **Parsing before the guard is not an existence oracle.** The reviewer sent 154 requests
  across malformed, non-JSON and readable bodies as a member, a stranger, to a bond that
  does not exist and to an id that is not a UUID: each refusal was the same bytes for all of
  them, but for the caller's own path echoed in `instance`. A test now pins that for three
  bodies on both routes.
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
are one transaction after it) and `ReconcileJoiningDay.underBondLock`. Closer first and
consumer first end in identical rows and identical answers in every case. The author
deleting by hand ends in the same rows and answers in the two `CloseDay` cases (a day
waiting for its reveal time; a lone entry at midnight), each compared against a twin bond
in `WithdrawalRaceTest`. On a legacy `SUSPENDED` joining day there is no delete twin
(question 6): there the test pins that reader first, consumer first and closer first agree
with each other and that nothing is shown. Decision 13's exception stands.

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
- **The API gains two optional request bodies; the contract gains `400` and `422` on the two
  routes and loses the `409` on `DELETE /entries/{entryId}`.** Additive by oasdiff's
  reading: no new error code. **The contract documents a `422` on `/leave` and `/block`
  that nothing answers**: the generator adds `400` and `422` to any route with a body, and
  `EndBondRequestReader` refuses every unreadable body as `400`. Left as generated. Two
  behaviours change for an existing client: a non-JSON content type with an empty body is
  `415` (answered, and not in the contract: the generator documents `415` only on
  idempotent routes), and an author's `DELETE` on an ended bond is `204` where it was `409`.
- **The application now does one more thing on its own**, every two seconds, on every
  instance, and start-up now takes a table lock for a moment. `moyi.scheduling.enabled=false`
  turns the poller off with the close job and the hourly reap of idempotency keys.
- **A publisher must run at READ COMMITTED.** A `@Transactional(isolation = …)` on any path
  that publishes fails at the publish, by design.
- **The text outlives a withdrawal on disk** until the consumer runs: two seconds normally,
  as long as the poller is stopped or the delivery backs off otherwise. It cannot be read
  through the API in that time (decision 12), and a day that is closed or revealed in that
  time is erased first (decision 14).
- **A withdrawal holds the bond's row lock for the whole erasure**, and every row lock of
  the member's history at once. The bond has ended; only the close job's work on that bond
  (its unsettled days and their streak), an author's own delete, a joining-day reconcile and
  a repeat ending wait on it.
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
- Whether a day's page may say `SOLO` (ADR-0034, "Owed, C5, the archive": the same question
  its decision 12 answered for the calendar). The owner ruled on 2026-10-06 that an archive
  day carries its status; ADR-0034's Rulings, 2, records the calendar's half, where a solo
  day is now drawn. Still owed before it is built: read `states.md` §6, which by the
  corpus read draws no solo day card.

**C5b and C5c.** Each new route by entry id (favourites in C5b, reactions in C5c) uses
`ChangeEntry.authorOf`'s rule or states why not, and joins `EntryChangesTest`'s route set
(ADR-0032, Revisit).

**C6.** `entries.text_search` exists (V12) and nothing writes it: no entity maps it and no
statement sets it, so an erasure leaves it `NULL` only because it was never anything else.
The slice that first writes it, or replaces it with a generated `tsvector`, must have
`EraseEntry` clear it (spec §6.7, "search data"; doc 07, BR-10). And a search must go
through `Entry.canBeReadBy`: a withdrawn author's row keeps its text, and so its vector,
until the consumer runs. A generated column would be cleared with the text by the erasure
and still not hidden by the marker before it.

**The deploy slice.** Export the meters; alert on the pending and age gauges (doc 11), on
`failing` above zero, and on the last-success timestamp going stale; a Prometheus registry,
which decides the exported names. Keep `org.springframework.transaction`, `.jdbc` and
`.orm.jpa` off TRACE in production: a `MANDATORY` handler's exception is printed there by
Spring's interceptor.

**`org.hibernate.orm.core` must stay above DEBUG, and nothing pins it.** At DEBUG,
Hibernate's "Listing entities" prints every field of every managed entity at each flush,
`EntryEntity.text` included. Run and seen by the last task: `UnflushedHandlerTest` raises
every logger to DEBUG and asserts on Postgres's "Failing row", not on the words, for this
reason. **It predates this slice**: every flush of a live entry prints the same, on the
request paths too. **Not fixed here.**

No configuration in the repository sets that level. Read, not run:
`app/src/main/resources/application.yml` sets one logger, `org.hibernate.orm.jdbc.error`,
to `OFF`; `application-local.yml` has no logging block; there is no logback or log4j file;
no script, compose file or workflow passes a logging level. Two test configurations
(`identity`, `gratitude`) repeat the one `OFF` line and set nothing else. So the default
stands, which is Spring Boot's root at INFO, and "entry text never reaches a log" holds
only while nobody raises `org.hibernate.orm.core`, a parent of it or the root to DEBUG.
A property or an environment variable at deploy could; the deploy's environment is not in
this repository. Either pin the logger at INFO in `application.yml`, with a test that
raises it and finds no text, or keep the text out of what Hibernate prints of the entity.
Question 10.

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
10. **Hibernate prints an entry's text when `org.hibernate.orm.core` is at DEBUG.** Found by
    this slice's last task and older than the slice (Owed, the deploy slice, has what was
    seen and what was read). No shipped configuration sets that level and nothing prevents
    it. CLAUDE.md's rule that entry text never reaches a log is conditional on it. Whether
    to pin the level now, in a pull request of its own, or leave it to the deploy slice is
    the owner's.

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
  consumer of `EntrySubmitted` would, and would trip the age alert at once, since the age
  is the event's.
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
- **X19** — doc 07 §7 and doc 02 J5 say each member keeps read access and export,
  unqualified. *Left for the corpus:* both owe the exception `states.md` §9 got.
- **X20** — one migration for C5, and a `bond` change. *Resolved:* V20 and V21.
- **X21** — doc 05 and ADR-0008 say the outbox's first handlers are notifications. *Left
  for the corpus.*

The silences it named for withdrawal and the outbox (S16 to S26) are decided above: the
request and its defaults (11); scope, every live entry of that member in that bond and no
other bond (13); what is read in between (12); the marker is not reversible (10); budget,
backoff with no jitter and no last attempt (5, 7); no ordering (6); the starting position
(3); meter names and `last_error` (8, 5); no ShedLock (7). Retention is Owed.

## What the corpus now says that is false

For the owner to carry into the corpus repository, which is not edited here. Line numbers
are the corpus's at `docs/phase-3-daily-loop`, `9be5149`.

1. **Doc 11:86, the "Outbox stuck" alert** (`outbox_pending` > 100 for 10 min, or oldest
   event > 15 min). The meters are `gratitude.outbox.pending` and
   `gratitude.outbox.age.seconds.max`, each tagged `consumer`. No registry exports them, so
   the alert cannot be built yet. When it is, the rule is per consumer and takes the
   maximum across instances, not the sum. It needs two conditions doc 11 lacks:
   `gratitude.outbox.failing` above zero, and the last-success timestamp gone stale.
   `pending` counts only due deliveries, so one delivery in backoff never shows in it; the
   age and `failing` do. A retired consumer's deliveries hold both gauges up for ever.
2. **Doc 11:51-52, the two gauges.** They are unlabelled there. Pending is per consumer and
   due-only; the age is the event's, due or not. Four meters are missing from the table:
   `failing`, `delivered`, `failed` and `last.success.timestamp`.
3. **Doc 07:271, the index** `outbox_events (next_attempt_at) where processed_at is null`.
   Those columns have been on `outbox_deliveries` since V14, and the claim's `ORDER BY`
   cannot use V14's index anyway (Consequences).
4. **Doc 07:312, "`outbox_events` | 30 days after processing".** It has no meaning per
   consumer, nothing removes anything, and a `BEGINNING` consumer needs the history (X9).
5. **Doc 07 §2** lacks `outbox_consumers`, `outbox_subscriptions`, the foreign key on
   `outbox_deliveries.consumer_id` (V20) and `bond_entry_withdrawals` (V21). **Doc 07:319**
   (BR-10) lists `text_search` and `author_deleted_account` among what an erasure sets; a
   withdrawal sets neither (X16). **Doc 07:308**, retention unqualified (X19).
6. **Doc 05:155, "dispatches to handlers: notifications in v1".** The first handler is the
   withdrawal (X21). **Doc 05:34** puts the poller in `events/`: the dispatcher is in
   `common/events`, the timer and the meters are `modules/scheduling`'s. Doc 05 does not
   say: deliveries written at publish; registration under a table lock; READ COMMITTED
   only; no ShedLock; no ordering; no last attempt.
7. **Doc 06:105-106, `/leave` and `/block` have no body** (X6). They owe
   `{"withdrawEntries": boolean}`, the opposite defaults, `400` for an unknown or repeated
   key, and `415`. **Doc 06:155, `DELETE /entries/{id}`** owes "`204` for its author on an
   ended bond". Doc 06:106's "byte-identical … same `ETag`" stays true.
8. **Doc 04, BR-9**, "all write operations … return `409`, except export and deletion": an
   author's entry `DELETE` is now excepted too. **BR-10a** says "the blocker's"; it is
   built for a leaver too. **Doc 04:249**, "closed immediately" (X17). **Doc 04:186**,
   "offers an export first" (X18).
9. **Doc 26 §5 and the strings**: the leave confirm's "you keep your archive" (X7).
   **Doc 02:145** (X19). **ADR-0008** in the corpus (X21).
10. **Doc 12:41, "outbox publish-and-consume"**, is now true. Nothing to carry.
11. **Doc 09, T-20**, "revokes access to *new* content": question 9 is against it, and is
    the owner's.

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
  existed, and `./gradlew build` was green at each code commit a report covers. Two have
  no count of their own: `d420edf` and `6de2a67`, whose authors were cut off; the next
  task's build started from them (1199 at `6de2a67`, by that task's arithmetic). Tests in
  the build's
  result files: 1089 at `4f8a842`, 1113 at `c495d1f`, 1131 at `b3db2e4`, 1135 at `99694e1`
  (`--rerun-tasks`, all 217 tasks), 1153 at `294e940`, 1166 at `176949c`, 1179 at `d77c076`,
  1185 at `26cfb2d`, 1202 at `f236d73`, 1204 at `7fad97a`. At `6a06bcf` only
  `:common:events:build` was run (45 tests). Except at `99694e1`, modules whose inputs had
  not changed were up to date and not re-executed.
- **Run at the end, by the last task** (after this record was first written; commits
  `5d2a889`, `88f7e13`, `33351ad`):
  - `./gradlew build` green at `5d2a889`, 1210 tests in the result files (1204 at
    `7fad97a`), and green again at `88f7e13`. The report does not say `--rerun-tasks`.
    `33351ad` changed `scripts/` and `tools/bruno`, and nothing the build compiles.
  - `scripts/smoke.sh` against a database of its own (`MOYI_DB=moyi_c5a_smoke`, the jar of
    `88f7e13`, dropped afterwards), with the poller running: 458 passed, 0 failed, 18
    sections, no skips. The two erasures were seen within 2 s and 1 s of the request. Run
    again with the poller held idle (`--moyi.scheduling.outbox.delay=3600000`): 454 passed,
    4 failed, and the four are the probes that depend on it (the two "the poller erases",
    the owed-deliveries count, the poller's own log line). Every API probe stayed green
    there: that is the read gate with no consumer. The next boot delivered the five
    deliveries that run left owed.
  - The CLI's `bond leave` and `bond block`, with each flag and with none, against a jar:
    kept, kept, erased, erased, kept, erased, as the defaults say. The Bruno requests were
    written and not opened in Bruno.
  - The contract: oasdiff 1.11.7 (the `tufin/oasdiff` image) against `origin/main`: 7
    changes, 0 error, 0 warning, 7 info; `breaking` exits 0 at both `--fail-on ERR` and
    `--fail-on WARN`. The seven: an optional request body on `/leave` and on `/block`;
    `400` and `422` added to each; the `409` removed from `DELETE /entries/{entryId}`.
    **CI's own action (`oasdiff-action/breaking@v0.1.17`) was not run by anyone**; by this
    verdict it will not ask for `breaking-api-change`.
  - **The stale `409` on `DELETE /entries/{entryId}` is out of the contract** (`88f7e13`):
    decision 15 removed the only conflict a delete answered. `OpenApiContractTest`
    asserted the `409`; it was turned to assert its absence and seen red before the
    contract was regenerated.
- **Run after the branch's conformance review** (2026-10-07, commits `936f6f4` and
  `abd1928`): `./gradlew build` green, 1211 tests in the result files. Modules whose
  inputs had not changed were up to date and not re-executed. The smoke run was not
  repeated: the two commits change comments and add one test.
- **Run by a reviewer, and pinned by a test only since `936f6f4`:** the `415` for a
  non-JSON content type with an empty body (decision 11). The test was written and run
  green. It pins behaviour that already existed, so no mutation was run against it.
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

  One finding of the last task is in that list's company and has no fix: Hibernate printing
  entity fields at DEBUG, entry text included (`org.hibernate.orm.core`, "Listing
  entities"). It is older than this slice. Owed, the deploy slice; question 10.
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
  `SET CONSTRAINTS` removed, a gone bond made to throw. Twelve stayed green. The reviewer
  judged three of them equivalent to the code and **nine survivors, at `6de2a67`**.

  **Six were closed at `5d2a889`**, each by a test seen to fail under the mutation: five of
  the nine, and one of the three called equivalent.
  - `EraseEntry` reading the entry without taking its lock, and `EntryStore.lockAndFind`
    without its refresh (two of the nine): `EraseFreshReadTest`, "a delete that waited
    while its entry was erased elsewhere does not step the day back a second time".
    Before, only a neighbouring class (`CloseRaceTest`) caught them.
  - `status.flush()` in the dispatcher: `UnflushedHandlerTest`, in `gratitude`, with a
    handler that leaves a JPA write unflushed. Without the flush, Spring's "Initiating
    transaction rollback after commit exception" line carried the row.
  - `EraseEntry`'s check that the entry is filed on the day it was given:
    `EraseFreshReadTest`, "an entry is not erased against a day it is not filed on, and
    nothing is written".
  - The order of `PATCH`'s refusals on an ended bond (`409` before `422`): an assertion
    added to `EntryChangesTest`, "an ended bond's entries cannot be edited but can be
    deleted by their author…".
  - The day read without its lock in `EraseEntry`, which the reviewer had judged
    equivalent because every other writer of a day takes the bond's lock first:
    `EraseFreshReadTest`, "a withdrawal waits for the day's own lock…", fails under it.
    Two of the twelve are still held equivalent.

  One mutation the commits' tests already caught has a second test since the same commit:
  the day stepped back for an entry already erased now also fails `WithdrawEntriesTest`,
  "the author's own delete and the withdrawal reach the same entry in either order…".

  **Open at `33351ad`**, four of the nine, in three kinds:
  - the `forgetLoaded()` call, and its flush (two mutations): no test, not even the
    2,000-entry one, which is still inside its bound at 9 s. An optimisation.
  - newest day first in place of oldest: no test, and none can show it, because no other
    holder of two days can run beside the consumer.
  - the `bond_id` predicate in the consumer's query: no test. It is redundant: a member id
    belongs to one bond.

  A joining-day twin was added to `WithdrawEntriesTest` at the same commit and pins
  decision 13's qualification: answers identical, entries identical, the day `SUSPENDED`
  against `OPEN` until the first read, then identical.
- **Found by review after the twin test had passed:** decision 13's qualification. The twin
  test compared a withdrawal with a run of deletes and was green; the reviewer's probe put
  both on an unread `SUSPENDED` joining day, which the twin test had not, and the day rows
  differed.
- **Seen once and not reproduced:** two log-leak tests in `WithdrawEntriesTest` failed on a
  reviewer's run and passed on thirty more. They searched log lines for `ada-` and `bea-`,
  which are hexadecimal digits and a hyphen, so a UUID can spell them. The mechanism is the
  reviewer's reasoning, not a reproduction. The markers are `Ada~wrote~` and `Bea~wrote~`
  since `5d2a889`, which no UUID can spell.
- **Read, not run:** that no path renders an entry except through the gate (two readers,
  by search); that a marker exists only on an ended bond, which is what excuses `SubmitEntry`
  from decision 14 and a fresh write from decision 12; the lock order of every new path
  (bond, day, entry, days oldest first), with one edge held by a test (the handler waits for
  a held bond row); registration running before the port opens, which is Spring's ordering;
  the exported meter names, there being no registry to export them; question 5.
- **Corrected by a reviewer:** the Task 7 report said V8 and V13 hold deferred constraints.
  They do not; no migration declares one.
