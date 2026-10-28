# ADR-0032 — The reveal, and the first events

**Status:** Accepted · **Date:** 2026-10-05 · **Deciders:** Daniel
**Amended:** 2026-10-05 (C3: the "Owed, C3" list discharged, decision 5 superseded — ADR-0033) · 2026-10-09 (the status; and the Revisit note on a second route by entry id, answered for favourites — ADR-0036) · 2026-10-28 (question 2 built: a partner's entry erased before it was seen is no entry, on every route that renders a day, for a day not yet settled; question 4 added for the settled exception)

*(2026-10-09: the status read "Proposed" until this date, though the slice merged as #53 on 2026-10-05. Brought to the convention of ADR-0026 to ADR-0031, which say "Accepted"; noted in ADR-0036's slice.)*

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
words restored. By a reviewer's trace it was not reachable — every writer of an entry also
reconciled the joining day in the same commit, so the copy could not differ — but that is an
invariant held somewhere else, and C3 and C5 add writers that do not hold it. `findForDayFresh` refreshes each row.
ADR-0031 decision 9 records the same trap for a day.

**3. The concurrency requirement is held by two tests, not one.** Spec §6.3 asks for a test
that fires both submissions at once, asserts exactly one `DayRevealed`, and fails with the lock
removed. Every submission takes the bond's row lock first (ADR-0031 decision 17), so two
submissions for one bond are already serial and that test stays green without the day lock.
The requirement is therefore split: the simultaneous test asserts one event, and a second holds
the day's row from another connection and asserts a submission is blocked on it — which is the
one that goes red when `lockRow` is removed. The day lock is not redundant: C3's close job
takes the bond lock before the day lock (amended by ADR-0033 after PR #54 review); against
other day writers, the day lock still protects the read-modify-write.
*Codex's notes record that the owner approved this split on 2026-10-04; it is written here so
that the approval has a place to be confirmed or withdrawn.*

**4. The reveal time is read on the day's own date, in the zone the day opened in, and
compared as an instant.** `date.atTime(revealTimeLocal).atZone(anchorTimezone)`: a time that
does not exist that day (a spring-forward gap) falls after the gap, and a time that happens
twice (an autumn overlap) falls on the first. A day run on by a westward change (ADR-0031
decision 12) still reveals at the time on the date it is labelled with. The time is the
bond's **current** setting, not a copy taken when the day opened: changing it changes when
today reveals.

**5. Nothing in C2 looks at a `PENDING_REVEAL` day again when its time arrives.** *(Superseded
2026-10-05: slice C3's close job does — ADR-0033 decision 3.)* The
synchronous path puts it there; the second sweep that releases it is C3's (spec §6.3). Until
C3, a couple with a reveal time who both write before it stay locked to each other, and
clearing the time afterwards releases nothing. (A delete steps such a day back to `PARTIAL`,
decision 8, and a rewrite then meets the reveal rule afresh; that is a way out only for
somebody who withdraws their entry.) This is the slicing the spec chose; it is stated here because nothing else said so.

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
  this: while a bond's joining day is still `SUSPENDED` — once per bond as things stand,
  since the first reconcile ends that state. No `lock_timeout` is set outside tests
  (ADR-0031, Owed), so that one read can wait behind a stuck bond write.
- **The same wait now precedes a write's own refusals.** The reconcile runs before the
  idempotency key is examined, so a duplicate that would be refused at once
  (`409 IDEMPOTENCY_KEY_IN_FLIGHT`) can queue behind the bond lock first, in that same
  window; and a request refused for a reason that has nothing to do with the joining day
  still leaves the reconcile committed. No status or body changes. Only a member of the
  bond (`POST`) or the entry's author (`PATCH`, `DELETE`) can set it off.
- **A `DELETE` as the first request on a legacy joining day reveals, then erases.** The
  member withdrawing can then read the partner's words, and the partner gets a tombstone.
  Both entries were already owed a reveal, so this is the order of two things that were
  each due, and a test pins it.
- **It runs on an ended bond and for a member who has left.** An ended bond is read-only to
  its members (ADR-0028), and this is not a member's write: spec §6.4 says an archived bond
  must not strand an earlier day, and a legacy joining day is exactly that.
- **The writers reconcile again under the bond lock**, which is what keeps the lock order
  when a pairing lands between the first read and the lock. The order is unchanged — key,
  bond, day, entry — and where a request holds two or three days of one bond (the joining
  day, the claimed day, the BR-3a fallback) it takes them oldest first. A keyed `PATCH` is
  the case that needed care: it reconciles before it opens the key's transaction and calls
  only the locked half inside it, because a reconcile inside would join that transaction
  and could take the joining day ahead of an older one.

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
*(Amended 2026-10-07, ADR-0035 decision 15: for a `PATCH` only. The author's `DELETE` on an
ended bond is `204` — question 1 below, as ruled.)*
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
`BondDay`, each written in the transaction of the change it describes (`MANDATORY`) — so a
submission that is refused writes no event, because the refusal rolls it back.
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
- Every write pays for the reconcile's unlocked check, for good: `POST` resolves the
  membership once more than it did and reads the joining day's row; `PATCH` and `DELETE`
  also read the entry once more. A handful of indexed reads per write, not measured.
- A deleted entry before the reveal is visible to the partner as a deletion (below).

## Owed

**C3, the close job.** *All four discharged on 2026-10-05 by ADR-0033: the second sweep and
`revealTimeLocal` on the closer's view (its decisions 3 and 14); a joining day reconciled
before `closedAt`, and a closed one never resumed — both halves of the "or" (decision 7);
one day at a time (decision 3); entries read fresh before they are written (decision 3,
step 5).*

- **The second sweep**, which is what releases a `PENDING_REVEAL` day when its time comes
  (decision 5). It needs
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

**C5, the outbox's first consumer.** *The second and third notes were discharged on
2026-10-07 by ADR-0035: deliveries are written at publish and claimed by delivery row, so
nothing keeps a cursor by id (its decisions 1 and 4); `last_error` holds an exception's class
name and never its message (decision 5). The first note stands for whoever consumes
`EntrySubmitted`; C5a's consumer subscribes only to `EntriesWithdrawn`.*

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

   *Ruled by the owner on 2026-10-06, and built on 2026-10-07 (ADR-0035 decision 15): the
   author may.* `DELETE /entries/{entryId}` is `204` for its author after the bond has
   ended, and after leaving it; `PATCH` stays `409 BOND_ARCHIVED`. The test that pinned the
   `409` was turned (`EntryChangesTest`).
2. **A delete before the reveal is visible to the partner**: `partnerEntry` becomes
   `{authorMemberId, status: REMOVED}` and the day steps back to `OPEN`. ADR-0031 decision 10
   ruled that shape before any request could produce it. FR-064 forbids disclosing activity;
   whether "wrote and thought better of it" is activity is a product question.

   *Ruled by the owner on 2026-10-21, to be built: the partner sees nothing, in `GET /today`
   and in the archive.* The same ruling as ADR-0036's sixth question. Until it is built, the
   shape above is what is served.

   *Built 2026-10-28* (branch `feat/unseen-erasure-hidden`). What "nothing" was made to mean,
   and the decisions taken in building it:

   - **Nothing is `partnerEntry: null`**, the value of a day the partner never wrote on.
     **For a day that had not settled when the entry was erased**, the two are one answer,
     byte for byte, on every route that renders a day: `GET /today`, `/days`,
     `/days/{date}`, search and on this day. `UnseenErasureTwinTest` holds each route, and
     the streak and the milestones besides, to a twin bond whose partner never wrote, for a
     delete and for a withdrawal, with the caller written and not.
   - **The exception: a day that settled first keeps what it settled as** (BR-10: settled
     history is authoritative; the owner's ruling of 2026-10-22 that a withheld both-wrote
     day counts complete). The scenario: a member leaves, the close withholds a both-wrote
     day as `PENDING_REVEAL` (ADR-0035 question 9), and then the partner deletes their
     entry, as an author may on an ended bond, or withdraws it. The caller is shown no
     partner entry, as ruled, but the day still says `PENDING_REVEAL` and keeps its
     complete square in the streak, where a partner who never wrote would have left it
     `SOLO`. Likewise a lone entry on a bond that ended before its day did closes `SOLO`,
     unrevealed, and keeps that status, and whatever the streak made of it, when its author
     erases it, where no entry would have closed `EMPTY`. For such a day an erasure is told from no entry.
     Pinned by `ShownDayTest`, "a day settled before the partner's entry was erased keeps
     its status, though the entry is not shown" (and the settled cases of "the status
     steps back only as an erasure would step it back"). Question 4 below is the owner's.
   - **The rule lives in one place**, `service.shownTo` (beside `onEachSideOf`), which
     `GetToday` and `GetDays` both read every day through. The gate is unchanged:
     `Readability.TOMBSTONE_UNSEEN` is still its answer, and favourites and reactions still
     answer it as an entry that does not exist (`404`). Only a day stops drawing it. The
     caller's own tombstone, and a partner's tombstone the caller had read (`TOMBSTONE`), are
     as before. `PartnerEntryResponse.of` now refuses a `TOMBSTONE_UNSEEN` reading rather than
     choose a shape for it, so that a renderer which skipped the rule fails instead of
     carrying a second copy of it.
   - **The listing needed no change.** An entry erased before its reveal was never revealed,
     so ADR-0036's `SEEN` rule never lists a day for it alone, and search and on this day
     also ask for a live row. A day the caller is listed on through their own entry is the
     day a partner who never wrote would give.
   - **The day's status is shown as its erasures will leave it.** A delete by hand steps an
     unsettled day back at once (`BondDay.withoutEntry`); a withdrawal hides its author's
     entries from its commit but steps the day back only when the outbox's consumer erases
     them. In between, the row said `PARTIAL` (or `PENDING_REVEAL`) where a delete by hand
     says `OPEN` (or `PARTIAL`), which told a withdrawal from a delete and from no entry at
     all (ADR-0028 decision 8). `shownTo` now derives the status from the entries the read
     can see: the live rows loaded, less a withdrawn author's. When the row counts more
     than that, an unsettled day is stepped back as the erasure will step it
     (`BondDayStatus.afterErasure`, which `withoutEntry` now uses too), so the response
     does not move when the erasure runs. The same derivation covers a read that loaded the
     day just before a delete committed and its entries just after (review, 2026-10-28):
     the entries are the later statement and win. A read still writes nothing; the step is
     taken at read time. A settled day is never stepped back. This changes what both
     members are shown of such a day, the withdrawer included, and only until the erasure.
     It supersedes the `PARTIAL` before the erasure that ADR-0035 question 4 accepted.
   - **The contract loses a branch.** No route can produce `{authorMemberId, status:
     REMOVED}` any more, so `ErasedEntryResponse` and the discriminator's `REMOVED` are gone
     from `partnerEntry`'s `oneOf` on `TodayResponse` and `DayResponse`: a breaking change,
     labelled `breaking-api-change`, with no generated client yet to break.
3. **A replay spends a rate-limit token** (decision 11).

   *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.*
4. **A settled day's status and streak square survive a later erasure** (question 2, as
   built, 2026-10-28). A day that settled before the partner's entry was erased keeps the
   status and the square it settled with: after a leave, a both-wrote day the close
   withheld stays `PENDING_REVEAL` and complete when the partner then deletes or withdraws,
   and a lone entry's `SOLO` day stays `SOLO`. The caller is shown no partner entry, but
   the status and the streak say one was there, where a partner who never wrote would have
   left `SOLO` (or `EMPTY`) and that square. Built: history stands (BR-10, and the ruling
   of 2026-10-22 that a withheld both-wrote day counts complete); `ShownDayTest` pins it.
   Turn it by showing a settled day's status as its erasure would have left it unsettled.
   The cheapest form is read-side and one line: drop `isSettledWith(closedAt)` from the
   step `shownTo` takes, for the shown status only, writing nothing. That turns the status
   on every route; the streak square and the milestones would still count the day unless
   the erasure also recounts the streak (`RecalculateStreak` exists), which is a write and
   the larger half of the change.

## Revisit when

- *(Done 2026-10-05, ADR-0033.)* C3 lands: everything under "Owed, C3", and decision 5 stops being true.
- *(Done 2026-10-07, ADR-0035.)* C5 registers the first consumer: `outbox_deliveries` gets
  its first rows and the three notes above fall due.
- A second route by entry id arrives (favourites and reactions, C5): it uses
  `ChangeEntry.authorOf`'s rule or states why not, and joins `EntryChangesTest`'s route set.
  *(Answered for favourites 2026-10-09, ADR-0036 decision 11: the favourite routes do not
  use `authorOf`'s rule and say why. A favourite changes nothing of the entry, so either
  member may mark either entry the read gate shows them in full; what the two rules share
  is the one `404` for everyone the entry was never shown to. Both routes are in
  `EntryChangesTest`'s set. Still open for reactions, C5c.)*

## How this was checked

What was executed, and by whom, so that "verified" means one thing in this document.

- **Reproduced by a test that failed first, then fixed:** decision 6's rollback
  (`JoiningDayTest`, three cases red — a `PATCH`, a keyed `PATCH` and a `POST` — each with
  the day still `SUSPENDED`), decision 7
  (`JoiningDayTest`: `expected SUSPENDED but was REVEALED`), decision 2
  (`RevealFreshReadTest`: the original text came back), and decision 10's body bound (`413`
  expected, `200` returned).
- **Mutation runs** — the mechanism removed, the named test seen to fail, the file restored —
  are listed with their results in the pull request, not here: they are a fact about a commit.
- **Read, not run:** the lock-order table for every path, and the claim that no two paths can
  deadlock, are a reviewer's trace of the code. The day-lock test proves one edge of it. So
  are decision 2's "not reachable", and the keyed `PATCH`'s lock order in decision 6: the
  fix for the latter was made from a reviewer's interleaving, and no test holds it.
