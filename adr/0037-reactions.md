# ADR-0037 — Reactions

**Status:** Accepted · **Date:** 2026-10-13 · **Deciders:** Daniel, for the five rulings of
2026-10-06 and for his rulings of 2026-10-21 on every question in "Questions that are the
owner's" except the third, which was not put to him. Every other decision here was made by
the plan or by the agents that ran it.

## Context

FR-048: a member reacts to a revealed entry with one of a small fixed set. Doc 04's glossary
calls a reaction "a small fixed-vocabulary acknowledgement of a revealed Entry" and gives it
no fields. Doc 06 gives it two routes, `POST /entries/{id}/reactions` and
`DELETE /entries/{id}/reactions/{type}`; doc 07 gives it a table with an `id`, a
`created_at` and a unique key of `(entry_id, member_id, type)`. `states.md` draws no
reaction anywhere.

This is **C5c**, the third of C5's three pull requests (ADR-0035), after the poller and
withdrawal (C5a, #58) and the archive and favourites (C5b, #60). The migration is **V23**
(`modules:gratitude`); C6 takes V24.

**The owner ruled five things on 2026-10-06**, in conversation; ADR-0036 "Owed, C5c" is
where they were first written down, and nothing else in this repository vouches for them:

1. one reaction per member per entry;
2. on the partner's revealed entry only;
3. visible to the author;
4. no timestamp;
5. a placeholder set of four: `HEART`, `THANK_YOU`, `SMILE`, `MOVED`. "Placeholder" is the
   owner's word.

**Everything else was decided without him.** The plan
(`docs/superpowers/plans/2026-10-10-reactions-c5c.md`) made eleven decisions under those
rulings, and marked two of them (5 and 9) as his to turn. The eleven are recorded below
under the plan's own numbers, because comments in the code cite them by number. Then four
things happened that the plan did not know:

- **PR #60 merged while Task 1 was being built** (`main` at `5839d47`, 2026-10-10), and its
  last commit had changed a favourite's unmark from "skip a row that is locked" to
  `FOR UPDATE NOWAIT` and `409 FAVOURITE_BUSY`. The plan's decision 6 copied the older
  idiom. **The coordinating agent** (the session that dispatched the implementers and the
  reviewers) ruled that a reaction's removal follows the merged idiom: `409 REACTION_BUSY`.
  Decision 6 is rewritten below.
- **The review of Task 1 found that this ruling had made an oracle**: for as long as a
  withdrawal's delivery was open, `DELETE …/reaction` answered `409` for a withdrawn entry
  and `204` for its twin deleted by hand. The same oracle was already on `main` for
  bookmarks, merged that day. The coordinating agent ruled a narrowing for both tables: a
  held row is `204` when the entry is already a tombstone to the caller. **The bookmark
  half changes behaviour the owner merged the same day.** It is its own commit (`01752a7`)
  so that it reverts alone.
- **The implementer decided four things the plan left open**, and the review upheld each:
  `DELETE` on the caller's own entry is `204`; a body that is not a reaction is refused
  before the entry is looked at; whether the bond has ended is asked of the membership as
  resolved after the entry; `BOND_ARCHIVED` is the last of the refusals. They are recorded
  in decisions 2, 4 and 5.
- **The guarded write on an entry's row became one copy**, `EntryRowGuard`, shared by
  `Favourites` and `Reactions`. That is a refactor of code C5b merged; decision 6.

The slice was built in two tasks. Each was begun by an agent that stopped before committing
anything, and finished by a second that treated what the first left as a draft. Task 1 had
two independent reviews, of the task and then of its fixes, and neither ran anything. Task 2
(the smoke section, the tools and this record) and the whole branch had three independent
reviews on 2026-10-15: of Task 2, of the branch's conformance and contract, and of its
concurrency and privacy. They read and did not run, except that two of them ran `main`'s
state-map check against the branch. Fix round 2 answered them the same day. "How this was
checked" says who ran what.

Terms, as in ADR-0036. The **gate** is `Entry.canBeReadBy`; its answers are `FULL`,
`TOMBSTONE`, `LOCKED`, `TOMBSTONE_UNSEEN` and `NOT_A_MEMBER`. The **marker** is the bond's
record that a member withdrew their entries (ADR-0035). The **twin** of a withdrawal is the
same history ended by a member who deleted each entry by hand and then left; the other
member must not be able to tell the two apart (ADR-0035, and `WithdrawalTwinArchiveTest`).
The **reactor** is the member who leaves a reaction, and the **author** the member whose
entry it is left on.

## Decision

**1. The routes are `PUT /entries/{entryId}/reaction` with `{"type": …}` and
`DELETE /entries/{entryId}/reaction`.** Both answer `204` with no body, and both are
repeatable **in sequence**: a second `PUT` of the same type changes nothing, a `PUT` of
another type replaces the first, and a `DELETE` of nothing is success. Not at once: a
`DELETE` that overlaps the member's own other `DELETE`, or their own `PUT`, on an entry read
in full finds the row held and is `409 REACTION_BUSY` (decision 6), retried. That is
inherited from the favourite unmark (#60), and is reasoned from the code, not run. Doc 06's `POST …/reactions` and
`DELETE …/reactions/{type}` are the shape for several reactions at once (X2). With one per
member per entry there is one resource, which the caller sets and unsets, and a type in the
path has nothing to select. It is the shape of the favourite routes beside it. Neither
route reads an `Idempotency-Key` and neither has a rate-limit bucket of its own, for the
reasons ADR-0036 decision 16 gives.

**2. `type` is one of `HEART`, `THANK_YOU`, `SMILE`, `MOVED`**, an enum in the contract,
matched exactly (`ReactionType.named`: `heart` is not `HEART`, and nothing is trimmed). The
set is written once, in the domain enum. The edge validator asks it, the contract's two
enums are generated from it, and V23's `CHECK` holds the same four names. Adding or
renaming a value is a migration and a breaking change for a generated client.

*Beyond the plan, the implementer's, upheld by the review:* which refusal an odd body gets,
and when.

| Body | Answer |
|---|---|
| `type` absent, `null`, or any plain value that is not one of the four names exactly (unknown, lower case, padded, a number, a boolean) | `422 VALIDATION_FAILED`, one `errors` entry naming `type`, the value not repeated |
| not an object; any key other than `type`, alone or beside it; `type` twice, even with one value; a value that is an object or an array; anything after the closing brace | `400 MALFORMED_REQUEST` |
| no `Content-Type`, or one that is not JSON | `415` |

The request is read by a reader of its own (`ReactionRequestReader`), because the
application's JSON reading ignores a key it does not know and keeps the last of a key sent
twice, and here that would set something the other person sees from a body that said two
things. Nothing is coerced: `3` is not the fourth type. **All of this is decided from the
request alone, before the entry is looked at**, so a stranger to the bond who sends a bad
body is told `422` or `400` and not `404`, and is told exactly what a member is told. The
other order would make the `404` depend on whether the body was valid, for no gain.

**3. The row is `reactions (entry_id, member_id, type)`, primary key the pair, and there is
no time in it.** Doc 07 gives the table `id` and `created_at` and keys it by
`(entry_id, member_id, type)`. The key is the pair because the ruling is one per member per
entry. There is no `id` because nothing refers to a reaction. There is no `created_at`
because a reaction can only be made by somebody reading the entry: its time is the time the
partner read it, which FR-064 forbids exposing, and nothing orders, filters or returns by
it. V22 kept `entry_favourites.created_at` because the corpus specifies it (ADR-0036
question 7); this table keeps less. `entry_id` has a foreign key to `entries` with
`ON DELETE CASCADE`, which is for the day an entry's row goes and is not what removes a
reaction on an erasure (decision 8). `member_id` has none: `bond_members` is another
module's table. There is no second index: every question starts from the entry.

**4. Who may react to what is the gate's answer for the caller, and one thing more**
(`Entry.canBeReactedToBy`, which asks `canBeReadBy` and then whether the reader wrote the
entry).

| The entry, as the gate answers for the caller | `PUT` | `DELETE` |
|---|---|---|
| the caller's own, in any state they can see (revealed, unrevealed, their own tombstone) | `409 REACTION_ON_OWN_ENTRY` | `204` |
| the partner's, `FULL` | `204`, set or replaced; or decision 5's `409`; or decision 6's | `204`; or `409 REACTION_BUSY` (decision 6) |
| the partner's, `TOMBSTONE`: erased, or its author has withdrawn and the row is still whole | `409 ENTRY_IMMUTABLE` | `204` |
| `LOCKED`, `TOMBSTONE_UNSEEN`, not a member, no such entry, not a UUID | the one `404` | the one `404` |

A partner's entry is `FULL` only once it is revealed, so "revealed only" has no rule of its
own and `ENTRY_NOT_REVEALED` cannot be reached here. Like `FavouriteEntry`, the rule is
the gate's answer for the caller. Unlike `ChangeEntry.authorOf`, and unlike a bookmark
(which either member may leave on any revealed entry, their own included), the author is
the one member who may **not**. What all three share is the refusal: everything the caller
was never shown is one `404`, so no `409` on these routes can confirm an id.

Two lines hold "never on one's own entry", and they are independent. The service refuses
first, with a code of its own. The statement refuses again
(`AND e.author_member_id <> ?`), so a later caller of `Reactions.set` that forgot to ask
still cannot give an author a row.

*Beyond the plan, the implementer's, upheld by the review:*

- **`DELETE` on the caller's own entry is `204`.** The plan's row gives the own entry
  `409 REACTION_ON_OWN_ENTRY` and names no verb. The author can hold no row on their own
  entry, so "absent is success" is simply true there, and a `409` would be a code for a
  request that changes nothing.
- **The order of the refusals:** never shown (`404`), then own
  (`409 REACTION_ON_OWN_ENTRY`), then the partner's tombstone (`409 ENTRY_IMMUTABLE`), then
  the ended bond (`409 BOND_ARCHIVED`), then the statement. `BOND_ARCHIVED` is last, so it
  is told only to a caller who could otherwise have reacted, and a withdrawn author's entry
  answers as any other tombstone does although its bond has ended. That keeps the twin's
  `PUT` identical.

**5. A reaction cannot be left on a bond that has ended, or by a member who has left:
`409 BOND_ARCHIVED`.** This is where a reaction parts from a bookmark, which ADR-0036
decision 14 allows there. A bookmark is the member's own and the other person never sees
it; a reaction is made to be seen, and after an ending that is contact. Taking one's own
reaction back is always allowed, as deleting one's own entry is (ADR-0035 decision 15).

**No lock on the bond is taken**, beyond decision 11's reconcile. Taking it would put a
reaction behind a withdrawal's delivery, holding a pooled connection, which is the stall
ADR-0036 decision 12 removed. So
**a `PUT` that races an ending can land.** The request asks the gate and the membership,
the ending commits, and the statement, which knows nothing of bonds, writes the row. If the
ending withdrew the entry, nothing shows that row (every later read is a tombstone) and the
erasure removes it. If the ending withdrew nothing, **the author is shown a reaction that
was sent before the ending and landed a moment after it.** Both cases are run as tests
(`ReactionsTest`: "a withdrawal that commits between the gate and the statement can leave a
reaction, which nothing shows and the erasure removes", and "an ending without withdrawal
that commits between the gate and the statement lets the reaction land, and the author sees
it"). The second pins the accepted behaviour so that a change to it is noticed. *The plan
recorded this as the owner's to turn* (question 4).

*Beyond the plan, the implementer's, upheld by the review:* whether the bond has ended is
asked of the membership **as resolved after the entry was loaded**
(`BondAccess.membershipNow`, which `readerNow` now is `.asReader()` of), not of the first
resolution. The first one can be older than an ending that committed while the joining-day
reconcile waited on the bond's lock, exactly as it can be for who has withdrawn (ADR-0035
decision 12). It is still no lock and still two resolutions a request. Both `hasLeft` and
`!isOpen` are asked, as on every write.

**6. The write is one guarded statement, and the removal never waits and never reports a
removal it cannot promise. The plan's last clause was superseded twice.**

```sql
WITH reactable AS (
    SELECT e.id FROM entries e
    WHERE e.id = ? AND e.revealed_at IS NOT NULL AND e.deleted_at IS NULL AND e.status <> 'DELETED'
      AND e.author_member_id <> ?
    FOR SHARE
), reacted AS (
    INSERT INTO reactions (entry_id, member_id, type)
    SELECT id, ?, ? FROM reactable
    ON CONFLICT (entry_id, member_id) DO UPDATE SET type = excluded.type
)
SELECT count(*) FROM reactable
```

- **The `PUT`** is ADR-0036 decision 12's mark with one more condition and an update on
  conflict. It answers whether the entry qualified, not whether a row changed. It runs in a
  transaction of its own with a two-second, transaction-local lock timeout; an erasure that
  commits first, or holds the entry's row past the wait, is answered
  `409 ENTRY_IMMUTABLE`. Each step that led to that statement was forced by a failure that
  was run, for bookmarks; this slice ran the same failures against reactions and did not
  rediscover them.
- **The `DELETE`, as the plan had it:** "the delete skips rows it cannot lock at once",
  `204` either way. That was the bookmark's idiom when the plan was written.
- **The `DELETE`, first supersession (the coordinating agent's ruling, 2026-10-10; commit
  `2bbcca4`).** PR #60's last commit had replaced the skip with `FOR UPDATE NOWAIT` and
  `409 FAVOURITE_BUSY`, because a skipped row was reported as removed and an erasure that
  then rolled back left it there (ADR-0036 decision 12, amended 2026-10-10). A reaction's
  removal was made to follow: a row another transaction holds is `409 REACTION_BUSY`,
  retryable. The reason given was that the owner had merged that idiom an hour before for a
  stated reason, and two idioms for the same row lock would drift.
- **The `DELETE`, second supersession (the coordinating agent's ruling on the review's
  finding I-1, 2026-10-10; commits `6630c56` and `01752a7`).** The only thing that holds a
  reaction's row on a tombstone for longer than an instant is a withdrawal's delivery,
  which keeps every row it has deleted until the whole delivery commits. So while a
  delivery was open, `DELETE …/reaction` on a withdrawn entry answered `409`, and on its
  twin deleted by hand `204`. Every view was identical throughout; this verb was the one
  place the two could be told apart. The reviewer described the test that would settle it;
  it was written, and failed `409` against `204`.
  **As built: a held row is a conflict only on an entry the caller reads in full.** On a
  partner's tombstone a held row is `204` like any other. The rollback that the first
  ruling guards against cannot falsify a `204` there: the entry is a tombstone to the
  caller by something that has committed (an erasure, or the marker, which is insert-only
  by the second review's reading of `EntryWithdrawals`),
  a tombstone renders `reaction: null` whatever rows exist, and the holder is the erasure
  that removes the row, retried until it has. On the caller's own entry there is no row of
  theirs to be held.
- **The same narrowing was applied to a bookmark's unmark** (`01752a7`):
  `FavouriteEntry.unmark` answers `409 FAVOURITE_BUSY` only when the row is held and the
  gate's answer is not `ERASED`. `main` had the same oracle since #60 merged.

**Neither supersession was decided by the owner.** Both were decided by the agent running
the plan, on 2026-10-10, and the second changes what a route the owner merged that day
answers in one cell: bookmark, tombstone, row held, `409` to `204`. Questions 7 and 8.

**One copy of the idiom.** `EntryRowGuard` holds what a bookmark and a reaction share: the
condition for an entry that can carry such a row, the bounded wait
(`waitingForALockAtMost`), and the removal that is refused at once
(`deleteOrBeRefused`, `removedUnlessHeld`). `Favourites` was refactored onto it in this
slice (`15963ce`), on the coordinating agent's ruling that two copies would drift. `main`'s
`FavouritesTest` and `FavouriteLockWaitTest` passed untouched on that commit, by the
implementer's report.

**7. An entry carries `reaction: type | null`:** the reaction of the member who did not
write it. An entry has at most one, because only one member may leave one and the key is
the pair, so it travels as one nullable field and not a list. It is on the wide shape only,
required and nullable, and **both members are sent the same value**: `Reactions.on` names
entries and no member. It is `null` unless the gate's answer is `FULL`. Two layers hold
that, as for `favourited`: the services ask only about entries read in full, and
`EntryResponse.of` forces `null` off `FULL` whatever it is handed. The narrow `LOCKED` and
`REMOVED` shapes gain nothing. Nothing says when it was left, by whom beyond "the other
one", or how many times it was changed.

A fresh write and an edit answer `reaction: null` without a query, since an entry nobody
else has read has none; a replay looks it up.

**8. `EraseEntry` removes every reaction on the entry it erases**, in the erasure's
transaction, under the entry's row lock, beside the bookmarks and only when that call is
the one that erases. So the author's delete, the withdrawal consumer and the close job's
pre-step all do, and cannot come to differ. The foreign key's cascade cannot do it: an
erasure keeps the row. Before the erasure reaches a withdrawn author's entry the gate
already renders it as a tombstone with no reaction, so the reader's bytes and tag move
once, at the ending, and not again at the erasure.

**9. A withdrawal does not remove the reactions its member left on the partner's entries.**
FR-029a withdraws entries. And if a withdrawal removed reactions too, it could be told from
deleting each entry by hand by the reactions vanishing. The member who withdrew is still
shown what they left and may take each back; they cannot change it (decision 5). *The plan
recorded this as the owner's to turn* (question 5).

**10. No event and no notification.** FR-080's list of pushes has no reaction, and an
outbox row would be a timed record of a reading. A refused reaction writes the handler's
one WARN line (a code, a status and a fixed sentence, with no member, entry or path, by the
review's reading); a reaction that is made writes no line.

**11. The joining day is reconciled first**, as on every gratitude request (spec §12.4): on
a day C1 left `SUSPENDED` with both entries, that is the reveal, and the difference between
the `404` and a reaction. So either verb can take the bond's lock and write, once, on a
legacy joining day, and does so even when the request then ends in `404`. **That lock is
taken with no timeout** (`ReconcileJoiningDay.beforeRead`), whenever the joining day is
still `SUSPENDED` and unclosed. On such a bond, a reaction verb sent while a withdrawal's
delivery holds the bond's lock waits for the whole delivery, holding a pooled connection,
and then erases what the delivery left (`underBondLock` runs `EraseWithdrawnEntries`). It is
the stall decision 5 avoids, reached the one way decision 5 does not close, and every
gratitude route on that bond shares it. Only a legacy bond's joining day can be in that
state (the review of Task 1 and the concurrency review, by reading). Bounding the
reconcile's wait is not this slice's (Owed).

## Consequences

- **Migration V23 (`modules:gratitude`).** It has been applied to no shared database. Two
  throwaway databases were created for smoke runs and dropped (How this was checked).
- **The contract gains two operations, one schema, one field and two error codes.**
  `reactToEntry` (`204`, `400`, `401`, `403`, `404`, `409`, `422`, `429`, `500`) and
  `unreactToEntry` (`204`, `401`, `403`, `404`, `409`, `429`, `500`) under
  `/api/v1/entries/{entryId}/reaction`; `ReactionRequest`; `EntryResponse.reaction`,
  required and nullable, its enum listing `null` (the contract's first nullable enum;
  springdoc generates it without, which by JSON Schema would forbid `null`);
  `REACTION_ON_OWN_ENTRY` and `REACTION_BUSY` in the code enum. **The two codes make this a
  breaking change for the generated client** (ADR-0024's amendment), so the pull request
  carries `breaking-api-change`. The description of `unfavouriteEntry` also changed, to
  say where its `409` stops.
- **An existing client sees one new field**, `reaction`, on every entry it already receives
  in the wide shape.
- **The state map has the two routes** (`docs/state-map/`, added on `main` by #59 after this
  slice began; fix round 2, 2026-10-15). Two endpoint cards, filed under the day machine as
  the favourite cards are, and a `204` row for each verb in each of the day's nine built
  states, since an entry is addressed by id whatever today's day is. `REACTION_ON_OWN_ENTRY`,
  `ENTRY_IMMUTABLE`, `BOND_ARCHIVED` and the one `404` are on the `PUT`'s card,
  `REACTION_BUSY` and the `404` on the `DELETE`'s, each citing the smoke probe or the test
  that asserts its code. The favourite `DELETE` card's `FAVOURITE_BUSY` now says it is never
  given on an entry shown as erased (`01752a7`); the checker reads no reasons, so nothing
  would have caught that sentence. The stamp stays `5839d47`: the map was not re-read in
  full. `scripts/state-map-check --strict` exits 0.
- **`PUT` and `DELETE` answer a held row differently, and the `PUT`'s answer sounds
  permanent** (the review's M-1). Both mean "an eraser that may yet roll back holds it". A
  `PUT` that waits two seconds behind one answers `409 ENTRY_IMMUTABLE`, whose sentence is
  "This entry can no longer be changed"; a `DELETE` answers `409 REACTION_BUSY`, "try
  again". A client that hides the control on `ENTRY_IMMUTABLE` is wrong when the erasure
  rolls back. It is the same on the favourite routes, and was accepted there for the same
  reason: a revealed entry's row is held past two seconds by an erasure and by nothing
  else. Not changed.
- **A reaction can land a moment after a plain ending, and the author sees it** (decision
  5). Run, pinned, and the owner's to turn.
- **On the tombstone path a `204` can be followed by a row that is still in the table.**
  Two ways, both shown to nobody. The eraser that held the row rolls back: the `204` was
  given, the row stays, both members are still sent `reaction: null`, and the row goes with
  the consumer's retry (run: `ReactionLockWaitTest`, "when the eraser of a withdrawn entry
  rolls back…"). Or the holder was the member's own `PUT`, which passed the gate before the
  withdrawal committed: its row commits after the `204` and is removed when the consumer
  reaches the entry (reasoned by the second review, not run). If a delivery never
  succeeded, the row would stay, unseen. The same holds for a bookmark.
- **The held world costs one caught exception more than its twin.** On the two `DELETE`s a
  held row on a tombstone and an absent row both answer `204` with the same bytes, but in
  the first Postgres answers `55P03` and Spring builds an exception that is caught, and in
  the second a delete matches no row. Neither waits. **The difference in time was not
  measured**, by anybody. It is noted because these routes are a privacy mechanism.
- **`409 REACTION_BUSY` tells the reactor that something else holds their reaction's row on
  an entry they can read**: for an instant, their own other request; for longer, the
  author's own delete in flight, which they are about to see anyway. That is the first
  ruling's recorded cost. A member's own quick second request can meet it (reasoned by the
  implementer, not run).
- **The `PUT` waits at most two seconds for the entry's row and the `DELETE` never waits.**
  Neither takes the bond's lock, apart from decision 11's legacy case, where the wait is
  unbounded.
- **The four ways of never having been shown an entry do different amounts of work before
  the same `404`**: not a UUID, nothing; no such id, one read; a stranger, a read and a
  membership; a partner's locked entry, two reads, two memberships and the reconcile check.
  That is `FavouriteEntry`'s shape exactly and is inherited, not new. It is measured
  nowhere.
- **`reaction` spends about 2.3 KB of the archive page's margin.** The largest page
  `DaysFeedTest` builds is 230,663 octets with every `reaction` null; it was 229,063
  before the field (both the implementer's measurements). A reaction's longest name is
  seven octets more than `null`, on each of a hundred entries: 700 octets more. That leaves
  about 31 KB of the 262,144 by the test's measure (262,144 − 230,663 − 700 = 30,781), and
  about 29 KB once ADR-0036's 1.4 KB for production's microsecond timestamps (seven octets
  in each of two fields of a hundred entries, which the test clock does not write) is
  counted as well (ADR-0036 decision 6).
- **The 2,000-entry withdrawal, with a bookmark and a reaction to remove from each entry,
  took 4,671 ms in one delivery**: one run, on the implementer's machine. ADR-0036 measured
  4,559 ms with bookmarks alone. The asserted bound is thirty seconds.
- **`SecretsNeverLoggedTest` drives both verbs**, and the pinned loggers of ADR-0035
  decision 16 and ADR-0036 cover the statements: no line pairs a member with an entry they
  reacted to. Neither lock refusal is logged; its message names a row.
- **One commit on the branch answers `204` for a removal it did not make.** From `22d7069`
  (the route) until `2bbcca4` (the refusal), a removal that found its row held discarded
  the answer: the defect PR #60 fixed for bookmarks. **The pull request must be
  squash-merged**, as every pull request here has been, so that `main` never holds that
  state. A bisect inside the branch would.
- **Nothing removes a member's reactions when the member goes.** `member_id` has no foreign
  key, on purpose. Owed, Phase 5.

## Owed

**ADR-0036's "Owed, C5c" is discharged or carried**, item by item; that record carries the
same list as a dated amendment.

- A route by entry id that is not the author's rule: decision 4. Both routes are in
  `EntryChangesTest`'s route set.
- No timestamp: decisions 3 and 7; `ReactionsTest`, "no response carries when a reaction
  was made, and the table has nowhere to keep it"; `OpenApiContractTest`, "an entry carries
  the reaction on it … and nothing says when it was left".
- `EraseEntry` removes an entry's reactions: decision 8.
- A reaction on an ended bond: decision 5. Not allowed, where a bookmark is.
- The owner's five rulings: built as ruled (decisions 1, 2, 3, 4 and 7). **Carried:** the
  set is still a placeholder (question 1).
- X2, X3 and S1 to S5: "The corpus", below. **Carried:** X3's screen, and S4's account
  deletion.

**The design.** No reaction is drawn (question 9). Until one is, the client has nothing to
show and nowhere to put the control.

**C6, search, on-this-day.** A response that returns entries renders `reaction` through
`EntryResponse.of` and asks `Reactions.on` only of entries read in full, as the three
existing reads do. `EraseEntry` must clear search data (ADR-0035, unchanged).

**Phase 5, account deletion and export.** Nothing removes a member row today; when
something does, that change removes the member's rows of `reactions`, as of
`entry_favourites`. A member's export should carry the reactions they left. Whether it
carries the ones left on their entries is not decided anywhere.

**Not assigned.**

- The time difference between a held row and an absent one on the two `DELETE`s
  (Consequences) is unmeasured.
- `ReactionLockWaitTest`'s "while the row is held a stranger still gets the one 404…" cannot
  fail by any ordering: the gate refuses before any statement. It is kept for its other
  half, the author's `204` (the review's M-6).
- Whether `hasLeft` and `!isOpen` are each load-bearing in decision 5's check is not shown:
  every ending in the tests ends the bond for both (M-8).
- `ImpatientReactions` in `ReactionLockWaitTest` overrides `Reactions.set`, so that seam
  still depends on the all-open plugin opening the class (M-9), as
  `FavouriteLockWaitTest`'s does.
- The favourites rollback test reads `/today` only, and for the caller only.
- Nothing sets the connection pool's size or a statement timeout for a request (ADR-0036,
  unchanged).
- The joining-day reconcile's wait for the bond's lock is unbounded (decision 11). Bounding
  it as `EntryRowGuard` bounds the row wait would cover every gratitude route on a legacy
  bond, not only these two.

## Questions that are the owner's

Each is built one way. The first six are the ones the plan and the brief named; 7 and 8 are
decisions made on 2026-10-10 by the agent running the plan, which the owner has not seen; 11
is a review's finding of 2026-10-15, left as built.

1. **The placeholder set.** Four names, in the contract, the table's `CHECK` and the
   domain enum. Built as ruled. The final set, and whether each is drawn as a glyph or a
   word, is the owner's and the design's. Changing it after merge is a migration and a
   breaking change, so it is cheapest to turn before a client is generated.

   *Ruled by the owner on 2026-10-21, to be built: five, `HEART`, `THANK_YOU`, `SMILE`,
   `MOVED` and `ME_TOO` ("I'm grateful for that too"), in the contract's enum, the table's
   `CHECK` and the domain enum.* Whether each is drawn as a glyph or a word is left to the
   design.
2. **The routes' shape against doc 06.** `PUT`/`DELETE …/reaction` where doc 06 has
   `POST …/reactions` and `DELETE …/reactions/{type}` (decision 1). It follows from "one
   per member per entry". Turning it is a rename, and doc 06 is to be corrected either way:
   as written it permits every type at once.

   *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.* Doc 06
   still owes the correction.
3. **No stored time.** Doc 07 specifies `created_at`; V23 has none (decision 3). Built
   without because the only thing it could record is when the partner read. Adding it later
   is a new migration, and the column would be empty for every reaction made before it.

   *Not put to the owner on 2026-10-21; it stands as built.*
4. **The millisecond race in decision 5.** A reaction sent before a plain ending can land
   just after it, and the author sees it. Built: accepted, pinned by a test. The other way
   is the bond's lock on every `PUT`, which waits behind a withdrawal's delivery with a
   pooled connection.

   *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.*
5. **Decision 9.** A withdrawal leaves the withdrawer's own reactions on the partner's
   entries. Built: kept, for the twin's sake. The other way is to remove them in the
   consumer, and then a deletion by hand must be made to do the same or the two differ.

   *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.*
6. **A visible reaction is, by the corpus's own argument, a read receipt.** The corpus read
   said so (X4): a reaction cannot be made without reading, so showing it shows that the
   partner read. FR-064 forbids read receipts. The owner ruled "visible to the author" on
   2026-10-06. ADR-0036 records the rulings as answering X2 and parts of S1 to S3; it does
   not say X4 was put to him. The plan and the first review both write of the consequence
   as "ruled acceptable", and this record follows them only as far as the ruling's words
   go: a reaction is something the reactor chooses to send, and it is shown. What was built
   to keep it to that: no
   time is stored or sent, and nothing else the reactor does (opening the day or the
   archive, bookmarking, a refused reaction, looking and not reacting) changes a byte or a
   tag the author can get (run: `ReactionsTest`, "nothing the partner does but react…", and
   the smoke section). **What remains:** the author can see that a reaction appeared
   between two of their own reads. No sentence of the owner's in this repository says a
   read receipt of this kind is acceptable; if "visible" was not meant to carry that, this
   is the question.

   *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.* A
   reaction is something the reactor chooses to send, and showing it is acceptable.
7. **`409 REACTION_BUSY`, and where it stops** (decision 6). Not in the plan and not ruled
   by the owner. Built: a held row on an entry read in full is a retryable `409`; on a
   tombstone, `204`. The other ways are the plan's (`204` always, which can report a
   removal that an eraser's rollback undoes) and the first ruling's (`409` always, which
   tells a withdrawal from its twin for as long as the delivery runs).

   *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.*
8. **The same narrowing on a bookmark's unmark** (`01752a7`). It changes what #60 merged:
   `DELETE …/favourite` on a tombstone whose row is held was `409 FAVOURITE_BUSY` and is
   `204`. Built because the codebase treats a withdrawal oracle as a defect and the twin
   test proved one. It is its own commit and reverts alone; reverting it without reverting
   `6630c56` leaves the two tables answering differently.

   *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.*
9. **No reaction is drawn.** The Figma-alignment record for the two routes and the field,
   from `states.md` and `tokens.md` as read at the corpus's `9be5149`:

   | Screen need | The API | Verdict |
   |---|---|---|
   | A reaction on an entry | `reaction` on every entry read in full, the same for both members | **gap in the design**: `states.md` has no reaction in any section, and `tokens.md` §4's `EntryCard` has two properties (`authorName`, `entryText`) and four variants, with no slot for one |
   | A control to leave, change and take back a reaction | `PUT` and `DELETE`, both `204` and repeatable | **gap in the design**: none is drawn. Nothing says an entry can take one; it is the partner's entry whose `status` is `REVEALED`, on a bond that has not ended |
   | The control on an archived bond | `PUT` is `409 BOND_ARCHIVED`, `DELETE` is `204` | **gap in the design**: `states.md` §9 says every write there is `409`; a reaction left before the ending can still be taken back |
   | A reaction on a tombstone | always `null` | nothing to draw |
   | When a reaction was left | not sent | none may be drawn (`tokens.md`: no timestamp slot on an entry card) |

   No screen was invented and Figma was not touched.

   *Ruled by the owner on 2026-10-21, as ADR-0036's fifth question was: the gaps are to be
   written into `states.md` as specifications to draw from, and drawn when Figma is available
   again.*
10. **The four decisions the implementer made** (decisions 2, 4 and 5): `204` for a
    `DELETE` on one's own entry; body refusals before the entry lookup; the ended-bond
    check on the membership read after the entry; `BOND_ARCHIVED` last. The first two are
    status codes a client sees.

    *Ruled by the owner on 2026-10-21: it stands as built, and the question is closed.*

11. **After a block that keeps the entries, the blocked member can still take reactions off
    the blocker's entries.** Raised by the concurrency review of 2026-10-15 (its m1), by
    reading; not run as a block in its own right, though `ReactionsTest` ("no reaction can
    be left once the bond has ended…") runs the `DELETE` after an ending. `unreact` is
    allowed on an ended bond, to either member, as deleting one's own entry is (decision 5).
    So the person blocked can change what the blocker sees on the blocker's own words,
    after the block: a reaction goes from `HEART` to `null`, and the tag moves, one a day if
    they like. Decision 5 refuses the `PUT` because after an ending a reaction is contact; a
    removal the other person sees is contact too. **Built: allowed, unchanged.** The same
    channel already exists through ADR-0035 decision 15, under which the blocked member
    deleting their own entries changes the blocker's archive; here it carries at most one
    bit per reaction ever left, since none can be added again, and it is no withdrawal
    oracle: a withdrawal and its twin answer alike. The other way is to refuse `unreact`'s
    `ALLOWED` branch with `react`'s `hasLeft || !isOpen` check (twin-safe, since both worlds
    are equally ended and the `ERASED` branch is untouched); it then needs a second
    decision, whether a reaction the blocked member can no longer remove stays shown to the
    blocker or is hidden from them. Not decided by anyone; the owner's to rule.

    *Ruled by the owner on 2026-10-21, to be built: `unreact` is refused once the bond has
    ended, by `react`'s `hasLeft || !isOpen` check. A reaction the blocked member can no
    longer remove stays shown to the blocker.*

### The corpus

What the corpus read (`c5-corpus.md`, which is not in this repository) found about
reactions, as ADR-0036 "Owed, C5c" carried it, and what became of each.

- **X2** — doc 03 says one of a fixed set; doc 07's key and doc 06's route allow every type
  at once. *Resolved, decisions 1 and 3*, by the owner's first ruling.
- **X3** — reactions have an endpoint and no screen, no fixed set and no recorded gap. *The
  set: decision 2, a placeholder. The gap: recorded, question 9 and spec §11. The screen:
  carried.*
- **X4** — a visible reaction is a read receipt. *Question 6.*
- **S1**, the set, its wire names and whether it is an enum in the contract: decision 2.
- **S2**, whose entry may be reacted to: decision 4.
- **S3**, whether and where reactions are returned, and in what shape: decision 7.
- **S4**, what an erasure does to them: by delete and by withdrawal, decision 8; the
  withdrawing member's own, decision 9; **by account deletion, carried** (Owed, Phase 5).
- **S5**, the status codes: a repeat is `204`; an unknown type `422`; a tombstone `409` on
  `PUT` and `204` on `DELETE`; before the reveal, the partner's entry is the one `404` and
  one's own is `409 REACTION_ON_OWN_ENTRY` (decisions 1, 2 and 4).

## What the corpus now says that is false

For the owner to carry into the corpus repository, which is not edited here. Line numbers
are the corpus's at `docs/phase-3-daily-loop`, `9be5149`, read for this record.

1. **Doc 06:156-157**, `POST /entries/{id}/reactions` with `{type}` and
   `DELETE /entries/{id}/reactions/{type}`. The routes are `PUT` and
   `DELETE /entries/{id}/reaction`, both `204` and repeatable. The rows also owe: `422` for
   a type not in the set, `400` for any other key; `409 REACTION_ON_OWN_ENTRY`,
   `409 ENTRY_IMMUTABLE` for a tombstone, `409 BOND_ARCHIVED` on the `PUT`;
   `409 REACTION_BUSY` on the `DELETE`; and the one `404`.
2. **Doc 07:168**, `reactions(id, entry_id fk, member_id, type, created_at) -- unique
   (entry_id, member_id, type)`. There is no `id` and no `created_at`; the primary key is
   `(entry_id, member_id)`; `type` has a `CHECK`. **Doc 07:252**, the diagram's
   `entries 1──n reactions`: an entry has at most one.
3. **Doc 04:45**, the aggregate diagram's `Reaction ×0..n` under `Entry`: zero or one. Doc
   04 §3 has no entity section for a reaction at all; it owes one: `entryId · memberId ·
   type`, no time, and why.
4. **Doc 03:59, FR-048**, "react to a revealed entry". Only the partner's. A member's own
   revealed entry takes none.
5. **Doc 03:71, FR-064**, "MUST NOT expose read receipts". A reaction shown to the author
   is one by X4's argument (question 6). The requirement owes either an exception for a
   reaction the reader chose to send, or a different FR-048.
6. **Doc 06:16**, "all `POST`/`PUT`/`PATCH` accept `Idempotency-Key`". The reaction `PUT`
   reads none, as the favourite `PUT` reads none (ADR-0036, item 5).
7. **Doc 04:189 (BR-9), doc 04:56 (I-5), doc 06:117 and `states.md`:858**, "every write on
   an archived Bond is `409`". `DELETE …/reaction` is one more exception, with the
   favourite routes and the author's entry `DELETE`. The reaction `PUT` conforms.
8. **Doc 06:38**, "Error codes are enumerated and exhaustive". `REACTION_ON_OWN_ENTRY` and
   `REACTION_BUSY` are in no corpus document, and nor is `FAVOURITE_BUSY`, which #60 added.
9. **Doc 06:158-159**, the favourite rows. The `DELETE` row owes `409 FAVOURITE_BUSY`, and
   that it is never given on an erased entry.
10. **`states.md`** draws no reaction, and **`tokens.md` §4 row 1**, `EntryCard`, has no
    slot for one (question 9). FR-048 is a SHOULD with an endpoint and no screen.
11. **Doc 03:92, FR-080**, lists no push for a reaction, and none is sent. True; nothing to
    carry.

## Revisit when

- The set stops being a placeholder (question 1): a migration for the `CHECK`, the enum,
  and a breaking change in the contract.
- A third kind of row hangs on an entry: `EntryRowGuard` is the idiom, and the tombstone
  rule of decision 6 goes with it.
- The withdrawal's delivery is split into several transactions: the reason a held row on a
  tombstone can only be an eraser's is that the delivery holds its rows to the end.
- A marker can be removed, or an entry un-erased: decision 6's `204` on a tombstone rests
  on both being one-way.
- An eraser is written that does not go through `EraseEntry`: it must remove reactions
  itself.
- A bond can seat more than two: an entry then has more than one possible reactor, and
  `reaction` as one nullable field (decision 7) no longer holds.
- A notification for a reaction is asked for: decision 10, and it would be a timed record.
- A field is added to `EntryResponse`: the page's margin is about 29 KB, with production's
  timestamps counted (Consequences).
- A member row can be removed: Owed, Phase 5.

## How this was checked

"Run" means executed and seen by the party named. Nothing here was run by the owner. **The
reviews read and did not run**, and say so, with one exception: two of the three reviews of
2026-10-15 ran `main`'s state-map check against the branch. Every run below is
an implementing agent's report of its own work, except Task 2's, which are this record's
author's.

- **Run by the implementer of Task 1** (2026-10-10; its report). The HTTP tests of
  `ReactionsTest` were run red before any route existed: 24 tests, 21 failed. The contract
  tests were run red: 3 of 36. The key-set and route-set tests were run red after the field
  existed: 22 of 555. Twenty-one mutations of production code, each restored: the plan's
  seven and fourteen more. None survived. A narrower form of one of the seven (the
  renderer's guard alone removed) fails only a unit test, because the services already ask
  only about entries read in full
  and the two layers mask each other over HTTP. `./gradlew build` green with 1,398 tests at
  the pre-rebase head.
- **After the rebase onto `5839d47`** (same agent, same day). `REACTION_BUSY` was written
  test first: four tests red, then green; three mutations, all caught. Build green, 1,403
  tests at `2bbcca4`.
- **The first review** (at `2bbcca4`; read, not run). No critical finding; one important
  (I-1, decision 6's oracle), found by reading the service against
  `WithdrawalTwinArchiveTest`'s own header; nine minor. It checked seven named risks
  outside the diff by reading one file each.
- **The fix round** (same implementer, same day). The twin test and the rollback test were
  run red for reactions (2 of 7 in `ReactionLockWaitTest`) and for bookmarks (2 of 6 in
  `FavouriteLockWaitTest`, against `main`'s behaviour), then green. The plain-ending race
  of decision 5, until then reasoned, was run as a test. Three trailing-content bodies were
  added and were already `400`. The "not logged" assertion on `REACTION_BUSY` was checked
  to fail with a log line added. Build green, 1,408 tests at `a42409d`.
- **The second review** (of `2bbcca4..a42409d`; read, not run). All six findings
  addressed; nothing new at critical or important. It argued, from the code, that a `204`
  on a tombstone cannot be given on uncommitted state: both sources of `ERASED` are plain
  reads outside any transaction, and both are one-way.
- **Run for Task 2** (2026-10-13, by this record's author).
  - **The smoke run**, `MOYI_DB=moyi_c5c_smoke_1013 scripts/smoke.sh`, on the jar built
    from `cf40796`: **814 passed, 0 failed, no skips, 21 sections.** The new section is
    167 of them. The database was dropped afterwards and seen to be gone. Three of
    the section's four new helpers (`rows_are`, `whole_response`, `without_reaction`) were
    fed wrong values outside the run and seen to fail; `rx_views` was not.
    A database `moyi_c5c_smoke`, left on 2026-10-10 by the agent that stalled on this task,
    was found and dropped first: it held V23, 35 users and no reaction, and nothing was
    connected to it. That run's totals were recorded nowhere.
  - **What the smoke run cannot reach.** A script holds no row. `409 REACTION_BUSY`,
    `409 FAVOURITE_BUSY`, and the `204` a held row gets on a tombstone, twin against twin,
    are covered **only** by `ReactionLockWaitTest` and `FavouriteLockWaitTest`. What the
    section compares is the twin with nothing held: both verbs on a withdrawn entry, before
    and after the poller, against an entry deleted by hand, as whole responses.
  - **The tools.** `scripts/moyi react` and `unreact` were run against a booted jar on the
    same database before it was dropped: seven forms refused locally; from the server `204`
    for a set, a repeat, a replacement and removals, `422` three times, `409` twice (own
    entry, ended bond) and `404` twice. `tools/bruno` gains two requests, written and not
    run: the collection has still never been opened in Bruno.
  - **The contract**, regenerated by the repo's command and identical to the committed
    file. oasdiff 1.11.7, `main` at `5839d47` against the working copy:
    `breaking --fail-on ERR` exits 0, "440 changes: 0 error, 440 warning, 0 info", every
    one `response-property-enum-value-added` (the two new codes on each problem response);
    `--fail-on WARN` exits 1. The changelog adds 10 infos: two endpoints, and `reaction`
    required on eight responses. CI's own action was not run.
  - **`./gradlew build`**, once, at `69609dc` (everything but this paragraph): green,
    **1,408 tests, no failures, errors or skips**, counted from the result files. `app`
    57, `gratitude` 569 and `scheduling` 32 were executed; the other nine modules were up
    to date and their results reused. The count is `a42409d`'s: Task 2 added assertions
    to three existing test functions, in two classes, and no test.
- **The three reviews of 2026-10-15** (of Task 2, of conformance, of concurrency and
  privacy; at `c57ae14`). Critical 0 in each; one important between them, found by two
  (the state map, above, proved by running `main`'s checker); 19 minor, of which the
  coordinating agent ruled two out (naming, commit length), not the owner. Their files, and fix round 2's brief and report, are in
  the slice's working folder, not in this repository.
- **Run for fix round 2** (2026-10-15, by its implementer, `f2d66ba..` this commit).
  - `scripts/state-map-check --strict`: exit 0, "634 rows, 268 never run, 0 pending, 29
    contract gaps. Nothing wrong."; the checker's own 154 tests pass.
  - The new rollback test ("an erasure that rolls back after removing the entry's reaction
    and bookmark…") was run red twice, once with each removal moved onto an autocommit
    connection of its own, and green with the code as it is.
  - `./gradlew build` at `dfac89b`: green, **1,409 tests, no failures, errors or skips**,
    counted from the result files (1,408 and that test).
  - **The smoke run**, `MOYI_DB=moyi_c5c_fix2_1015 scripts/smoke.sh`, on the jar built from
    `dfac89b`: **814 passed, 0 failed, 0 skipped, 21 sections.** The withdrawal's
    poller lost the race, so the tag across the erasure was checked. The database was
    dropped afterwards and seen to be gone.
- **Argued, not run:** that the time a held `DELETE` takes cannot be told from an absent
  one's; that a member's own second request can meet `REACTION_BUSY`; the second way to a
  leftover row after a `204`; that every erasure goes through `EraseEntry` (by search:
  three callers); what the handler logs for a refusal; rate limiting on the two routes.
- **Not run by anyone:** the smoke run on the pull request's final head (it ran on
  `dfac89b`; the commit after it changes this record and the script's header only); V23 against a shared database; CI's contract action; the collection in
  Bruno; any independent re-run of the held-lock tests on a second machine.
