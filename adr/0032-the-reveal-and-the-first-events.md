# ADR-0032 — The reveal, and the first events

**Status:** Proposed · **Date:** 2026-10-05 · **Deciders:** Daniel

## Context

Slice C2 is the one the product is for: two people each write, and then each reads the
other's words (spec §6.3). It also gives an author a way to change or withdraw an entry
(`PATCH` and `DELETE /entries/{entryId}`, §5.2), takes a couple's first shared day out of
`SUSPENDED` (§12.4), and starts the outbox (§8). ADR-0031 left six obligations on it by name.

It was built by two agents. Codex wrote the slice between 2026-10-04 and the morning of
2026-10-05, when its session ended with the work uncommitted and its own notes two hours
behind the code. Claude committed that work as it stood (`09ea440`, a green build), had it
read whole by three independent reviewers, and finished it. The reviews found no leak, no
authorisation hole and no deadlock. They found two defects reachable through the API, one
latent one, and a number of things built and not tested. Each defect was reproduced by a test
that failed before the fix; "Decision" below says what stands now, and the last section says
which parts were found that way.

## Decision

**1. The reveal is a transition of `BondDay`, persisted under the day's row lock in the
transaction that persists the second entry.** `BondDay.revealWhenDue` decides; `RevealDay.apply`
writes the day, stamps `revealed_at` on both entries, and publishes `DayRevealed`, all in the
caller's transaction (`MANDATORY`). Counting an entry (`withEntry`) and revealing are separate
steps: a two-entry `PARTIAL` day exists only between them, in memory, and is never committed.
`entries.revealed_at` is monotonic twice over — `Entry.reveal` does nothing to an entry that
has one, and `EntryStore.update` never clears or moves a stamp the row already holds.

**2. The reveal reads the day's entries again from the database before it writes them.**
`EntryStore.update` writes every column. Hibernate answers a row the transaction has already
loaded from its identity map, and a route that finds its entry by id loads it before any lock
is held. So a reveal working from the map wrote the older copy back: an edit undone, or erased
words restored. It was not reachable — every writer of an entry also reconciled the joining
day in the same commit, so the copy could not differ — but that is an invariant held somewhere
else, and C3 and C5 add writers that do not hold it. `findForDayFresh` refreshes each row.
ADR-0031 decision 9 records the same trap for a day.

**3. The concurrency requirement is held by two tests, not one.** Spec §6.3 asks for a test
that fires both submissions at once, asserts exactly one `DayRevealed`, and fails with the lock
removed. Every submission takes the bond's row lock first (ADR-0031 decision 17), so two
submissions for one bond are already serial and that test stays green without the day lock.
The requirement is therefore split: the simultaneous test asserts one event, and a second holds
the day's row from another connection and asserts a submission is blocked on it — which is the
one that goes red when `lockRow` is removed. The day lock is not redundant: C3's close job
takes no bond lock (decision 18), and against it the day lock is the only thing there is.
*Codex's notes record that the owner approved this split on 2026-10-04; it is written here so
that the approval has a place to be confirmed or withdrawn.*

**4. The reveal time is read on the day's own date, in the zone the day opened in, and
compared as an instant.** `date.atTime(revealTimeLocal).atZone(anchorTimezone)`: a time that
does not exist that day (a spring-forward gap) falls after the gap, and a time that happens
twice (an autumn overlap) falls on the first. A day run on by a westward change (ADR-0031
decision 12) still reveals at the time on the date it is labelled with. The time is the
bond's **current** setting, not a copy taken when the day opened: changing it changes when
today reveals.

**5. Nothing in C2 takes a day out of `PENDING_REVEAL`.** The synchronous path puts it there;
the second sweep that releases it is C3's (spec §6.3). Until C3, a couple with a reveal time
who both write before it stay locked to each other, and clearing the time afterwards releases
nothing. This is the slicing the spec chose; it is stated here because nothing else said so.

**6. The joining day is reconciled by the first gratitude operation that meets it, in a
transaction of its own.** `GET /today`, `POST /entries`, `PATCH` and `DELETE` call
`ReconcileJoiningDay.beforeRead` before their own work (a replay calls it too, inside the
key's transaction — a replay writes nothing else and is not refused for what it finds): zero entries becomes `OPEN`, one
`PARTIAL`, two follow the reveal rule, on the day whose span contains `activeSince` and no
other. It commits before the request's own transaction because the request can be refused *by
the reconcile's own result*: on a day C1 left `SUSPENDED` with both entries, the reconcile
reveals it, which is what makes the `PATCH` that triggered it a `409 ENTRY_IMMUTABLE`. Inside
one transaction the refusal rolled the reveal back, on every retry.

Three consequences, each a change to something written earlier:

- **A read may now take the bond's row lock and may extend a day.** ADR-0031 decision 17 said
  a read never does, and spec §3.1 that `GET /today` extends nothing. Both hold except for
  this: once per bond, while its joining day is still `SUSPENDED`. No `lock_timeout` is set
  outside tests (ADR-0031, Owed), so that one read can wait behind a stuck bond write.
- **It runs on an ended bond and for a member who has left.** An ended bond is read-only to
  its members (ADR-0028), and this is not a member's write: spec §6.4 says an archived bond
  must not strand an earlier day, and a legacy joining day is exactly that.
- **The writers reconcile again under the bond lock**, which is what keeps the lock order
  when a pairing lands between the first read and the lock. The order is unchanged — key,
  bond, day, entry — and where a request holds two or three days of one bond (the joining
  day, the claimed day, the BR-3a fallback) it takes them oldest first.

**7. A day that ended before the second member arrived opens `SUSPENDED`, whether or not it
already had a row.** The opening status is decided from `activeSince` against the day's span.
Decided from "is the bond still waiting" alone, a back-fill onto such a day that had no row
opened it `OPEN`, and two back-fills revealed it, while the same day with a row stayed
`SUSPENDED` and private. Spec §12.4: earlier days "remain private and excluded".

**8. `PATCH` changes the text and nothing else, until the reveal; `DELETE` is a soft erase,
any time.** `PATCH` is `409 ENTRY_IMMUTABLE` once the entry is revealed (BR-7) or erased, and
refuses media ids (`422`) as submission does. `DELETE` sets `status = DELETED` and
`deleted_at` and removes the text, answers `204`, and is repeatable. The row is kept, also
before the reveal: the idempotency key that produced it still has to replay *something*, and
what it replays is the tombstone. Before the reveal a delete frees BR-2's slot and steps the
day back (`PENDING_REVEAL` to `PARTIAL`, `PARTIAL` to `OPEN`); after it the day is a record and
does not change, and since a revealed day is settled, the author cannot write that day again
(`409 DAY_CLOSED`) — which is what stops delete-then-rewrite from replacing words already read.

**9. One `404` for an entry that is not the caller's, and it is one class.** No such id, an
id that is not a UUID, an entry in a bond the caller is not in, and an entry the caller's
partner wrote all throw `EntryNotFoundException`. The partner gets `404` and not `403`: they
are a member, but `403` on an entry id would confirm an id they have never been shown. The
author on an ended bond gets `409 BOND_ARCHIVED`, decided only after the author check.
**These two routes are not in the route-driven cross-tenant suite** (spec §9 says every new
endpoint is): it discovers routes by `{bondId}`. `EntryChangesTest` is their cross-tenant
test, and it asserts the exact set of `{entryId}` routes so that a third cannot arrive
uncovered.

**10. `Idempotency-Key` is optional on `PATCH` and absent from `DELETE`.** A `PATCH` is
naturally repeatable — the same text twice is the same row — so the key is accepted and
honoured (`@Idempotent(required = false)`), not demanded; a replay re-reads the entry and
renders it as it stands, a tombstone included. `DELETE` is idempotent by itself and takes
none. The 1 MiB body bound (ADR-0031 decision 24) belongs to the route, not to the header:
as first built it applied only when a key was sent.

**11. `entries:create` is twenty a day per user, and every attempt counts.** Doc 06 §4's
number (it writes `entry:create`; spec §5.5's spelling is used). The limiter runs before the
idempotency interceptor, so a replay, a refused write and a `404` each spend a token: the
twenty-first retry of one key in a day is `429`, not the replay. `PATCH` and `DELETE` carry no
named bucket; the global per-user one covers them.

**12. An event names things by id and carries nothing else.** `OutboxEvent.references` is a
`Map<String, UUID>`, so a payload cannot hold an entry's words by accident.
`EntrySubmitted {bondId, bondDayId}` on aggregate `Entry` and `DayRevealed {bondId}` on
`BondDay`, each written in the transaction of the change it describes (`MANDATORY`), after
the insert has been flushed — so a submission refused by BR-2 writes no event.
`outbox_deliveries` exists and is empty: delivery state belongs to a consumer (spec §8), and
the first consumer is C5's.

**13. A constraint violation leaves `gratitude` as the constraint's name and nothing else.**
`redacted()` builds a new exception with no cause: Postgres reports a violated CHECK on an
`UPDATE` with the failing row, and the reveal updates both members' rows. Every write of
`entries` is under a catch that applies it. Hibernate's own log line for the same failure is
switched off in production configuration and now in `gratitude`'s tests, which is what let a
test see the leak at all.

**14. `GET /today` picks a member's entry deterministically: a live row before an erased
one, then the newest.** Withdraw-then-rewrite puts two rows by one author on a day.

## Consequences

- **One new `ErrorCode`, `ENTRY_IMMUTABLE`, so this is a breaking change** for a client that
  generates the codes as a sealed class. The pull request carries `breaking-api-change`.
- `common:events` is a new module (spec §12.7) and owns V14. `app` names it.
- `POST /entries` reads the membership once more than it did, outside its transaction, for
  the reconcile's unlocked check. One indexed read per submission.
- A deleted entry before the reveal is visible to the partner as a deletion (below).

## Owed

**C3, the close job.**

- **The second sweep**, which is the only way out of `PENDING_REVEAL` (decision 5). It needs
  the bond's `revealTimeLocal` without a caller: `RevealDay.apply` takes it as a parameter,
  and the closer-facing accessor ADR-0031 already owes on `BondAccess` lists only the
  timeline and the lifecycle instants. Add it there.
- **Reconcile the joining day under its lock before stamping `closedAt` on it**, or make
  `resumeJoiningDay` refuse a closed day. Spec §6.4 has the closer stamp elapsed `SUSPENDED`
  rows. If it stamps a joining day first, a one-entry day resumes to `PARTIAL` with
  `closedAt` set and may never become `SOLO`; and a two-entry day one member then deletes
  from keeps `entry_count = 2` (a settled day is not stepped back), resumes, and reveals the
  other member's words to the one who withdrew theirs.
- **"Never hold two days of one bond at once, or take them older first"** now protects three
  paths, not one: `SubmitEntry` can hold the joining, claimed and fallback days, and
  `ChangeEntry` the joining day and the entry's.
- **A writer that changes an entry must go through `EntryStore.lockAndFind` or
  `findForDayFresh`** (decision 2).

**C5, the outbox's first consumer.**

- There is **no event for an edit or an erasure**, so a consumer of `EntrySubmitted` will see
  one for an entry since withdrawn, and must read the entry before acting on it.
- Event ids are time-ordered at insert, not at commit: a poller that keeps a cursor by id can
  skip an event whose transaction committed late. Poll by delivery row, as spec §8 says.
- `outbox_deliveries.last_error` will hold a consumer's exception message. Hold it to the
  same rule as a log line.

**Not assigned.** `DayAssignment` refuses a claim from before the bond was created, not from
before the caller joined it, so a joiner can file an entry on a day they were not yet a
member for. Since decision 7 that day is `SUSPENDED` and the entry is read by its author only.

## Questions that are the owner's

Each is built one way, pinned by one test, and cheap to turn.

1. **An author cannot delete their own entry once the bond has ended** (`409 BOND_ARCHIVED`,
   ADR-0028's rule that an ended bond is read-only). Until C5's withdrawal there is then no
   way to take back words a former partner can still read.
2. **A delete before the reveal is visible to the partner**: `partnerEntry` becomes
   `{authorMemberId, status: REMOVED}` and the day steps back to `OPEN`. ADR-0031 decision 10
   ruled that shape before any request could produce it. FR-064 forbids disclosing activity;
   whether "wrote and thought better of it" is activity is a product question.
3. **A replay spends a rate-limit token** (decision 11).

## Revisit when

- C3 lands: everything under "Owed, C3", and decision 5 stops being true.
- C5 registers the first consumer: `outbox_deliveries` gets its first rows and the three
  notes above fall due.
- A second route by entry id arrives (favourites and reactions, C5): it uses
  `ChangeEntry.authorOf`'s rule or states why not, and joins `EntryChangesTest`'s route set.

## How this was checked

What was executed, and by whom, so that "verified" means one thing in this document.

- **Reproduced by a test that failed first, then fixed:** decision 6's rollback
  (`JoiningDayTest`, four cases red: the day stayed `SUSPENDED`), decision 7
  (`JoiningDayTest`: `expected SUSPENDED but was REVEALED`), decision 2
  (`RevealFreshReadTest`: the original text came back), and decision 10's body bound (`413`
  expected, `200` returned).
- **Mutation runs** — the mechanism removed, the named test seen to fail, the file restored —
  are listed with their results in the pull request, not here: they are a fact about a commit.
- **Read, not run:** the lock-order table for every path, and the claim that no two paths can
  deadlock, are a reviewer's trace of the code. The day-lock test proves one edge of it.
