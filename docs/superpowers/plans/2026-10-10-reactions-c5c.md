# Slice C5c, reactions — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** A member can leave one small acknowledgement on their partner's revealed entry,
change it, and take it back; the author sees it.

**Architecture:** A reaction is a row keyed by entry and member, written by one guarded
statement the way a favourite is (ADR-0036 decision 12) and removed by `EraseEntry`. What a
caller may do is decided by the read gate's answer for that caller, with who-has-withdrawn
asked after the entry is loaded. An entry carries at most one reaction, because only the
member who did not write it may leave one, so it travels as one nullable field on the entry.

**Tech stack:** Kotlin, Spring Boot 4.1, Postgres 18 (Testcontainers), `JdbcTemplate`.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` §4, §5.2; doc 03
FR-048, FR-064; doc 04 "Reaction"; doc 06 §3 (the two reaction rows); `adr/0036` decisions
10–13 and "Owed — C5c"; `adr/0035` decisions 12–14.

**Stacked on `feat/gratitude-days` (PR #60, unmerged).** Branch `feat/gratitude-reactions`,
worktree `.worktrees/feat-gratitude-reactions`. Migration **V23**. Rebase when #60 merges.

## The owner's rulings (2026-10-06, given in conversation; recorded in ADR-0036 "Owed, C5c")

One reaction per member per entry. On the partner's revealed entry only. Visible to the
author. No timestamp is sent. A placeholder set of four: `HEART`, `THANK_YOU`, `SMILE`,
`MOVED`.

## Global constraints

- Never commit to `main`; the owner merges. Conventional Commits, subject ≤ 88 characters.
- Test first, seen to fail for the stated reason. After a task's tests pass, break the
  mechanism and see the named test fail; say which in the report.
- **What a caller may do is the read gate's answer for that caller**, the reader made after
  the entry is loaded (`BondAccess.readerNow`, ADR-0035 decision 12).
- **BR-8:** an entry the caller was never shown is one byte-identical `404` on every route
  by entry id, and serialises to `{authorMemberId, status}` and nothing else. No reaction.
- **No time of a reaction is stored or sent.** When a reaction was made is when the partner
  read the entry (FR-064).
- A reaction is the only thing in this slice the other person sees: nothing else about the
  reactor (that they opened the day, the archive, or looked and did not react) may change
  any byte the author can get.
- Entry text never reaches a log; nor does a line pairing a member with an entry they
  reacted to (the pinned loggers of ADR-0035 decision 16 and ADR-0036 cover JDBC values).
- V23 is applied to no shared database: smoke with `MOYI_DB=<name>`, dropped after.
- Markers in tests must not be able to occur in a UUID, a date or a hex digest.
- `git status --porcelain | grep -v '^?? \.kotlin/'`: never filter status by the word
  "kotlin".

## Decisions this plan makes (each goes into ADR-0037)

1. **The routes are `PUT /entries/{entryId}/reaction` with `{"type": …}` and `DELETE
   /entries/{entryId}/reaction`**, both `204`, both repeatable. Doc 06 has `POST
   …/reactions` and `DELETE …/reactions/{type}`, which is the shape for several reactions
   at once; with one per member a `PUT` sets or replaces it and the type in the path has
   nothing to select. The same shape as a favourite.
2. **`type` is one of `HEART`, `THANK_YOU`, `SMILE`, `MOVED`**, an enum in the contract.
   Anything else is `422 VALIDATION_FAILED`. Adding a value later is a breaking change.
3. **The row is `(entry_id, member_id, type)`, primary key the pair, with no time.** Doc 07
   gives it `created_at`; nothing may read it and it would be a record of when someone
   read, kept for no purpose. A `CHECK` holds the four types.
4. **Who may react to what, by the gate's answer for the caller:**
   - their **own** entry (any state they can see) — `409 REACTION_ON_OWN_ENTRY`;
   - the partner's entry, `FULL` — set or replace, `204`;
   - the partner's entry, `TOMBSTONE` — `409 ENTRY_IMMUTABLE` on `PUT`; `DELETE` is `204`;
   - `LOCKED`, `TOMBSTONE_UNSEEN`, not a member, no such entry, not a UUID — the one `404`.
   A partner's entry is `FULL` only once revealed, so "revealed only" needs no rule of its
   own, and `ENTRY_NOT_REVEALED` is not reachable here.
5. **A reaction cannot be left on an ended bond or after leaving it: `409 BOND_ARCHIVED`.**
   It is seen by the other person, and after an ending that is contact. Taking one's own
   reaction back (`DELETE`) is always allowed, as deleting one's own entry is.
   The check uses the caller's membership as read for the request, not the bond's lock:
   a `PUT` that races an ending by milliseconds can land. Taking the bond lock would make
   a reaction wait behind a long withdrawal with a pooled connection, which is the stall
   ADR-0036 decision 12 removed. *Recorded as the owner's to turn.*
6. **The write is one guarded statement**, as a favourite's is: `INSERT … SELECT … FROM
   entries WHERE id = ? AND revealed_at IS NOT NULL AND deleted_at IS NULL AND status <>
   'DELETED' AND author_member_id <> ? FOR SHARE … ON CONFLICT (entry_id, member_id) DO
   UPDATE SET type = excluded.type`, with a two-second transaction-local lock timeout that
   answers `409 ENTRY_IMMUTABLE`; the delete skips rows it cannot lock at once.
7. **An entry carries `reaction: type | null`**: the reaction of the member who did not
   write it. Present only on a `FULL` reading; a tombstone says `null`; the minimal `LOCKED`
   and `REMOVED` shapes gain nothing. Both members see the same value.
8. **`EraseEntry` removes the entry's reactions**, so a delete, a withdrawal and the
   closer's pre-step all do. Before an erasure the gate already renders a withdrawn
   author's entry as a tombstone, with no reaction, so the reader's tag does not move twice.
9. **A withdrawal does not remove the reactions its member left on the partner's entries.**
   FR-029a withdraws entries; and if it removed reactions too, a withdrawal could be told
   from deleting entries by hand by the reactions vanishing. The member may take each back.
   *The owner's to turn.*
10. **No event and no notification.** FR-080's list of pushes has no reaction, and an
    outbox row would be a timed record of a reading.
11. **The joining day is reconciled first**, as on every gratitude operation (spec §12.4).

## Review focus

- The partner's unrevealed entry, a stranger and a missing id are indistinguishable on both
  verbs.
- Nothing the reactor does except the reaction itself changes a byte the author can get.
- A reaction on an entry that is then erased or withdrawn is gone with it, in every order.
- A withdrawal twin (block with withdrawal vs delete by hand, then leave) stays
  byte-identical to the other member through the feed, the day view and `/today`.
- An odd `type` or body (unknown key, wrong case, a number, 1 MB) is the standing refusal.

---

### Task 1: Reactions

**Files:**
- Create: `modules/gratitude/src/main/resources/db/migration/V23__gratitude_reactions.sql`,
  `gratitude/domain/ReactionType.kt`, `gratitude/infra/database/Reactions.kt`,
  `gratitude/service/ReactToEntry.kt`, `gratitude/web/ReactionsController.kt`,
  `gratitude/web/ReactionRequest.kt`
- Modify: `gratitude/domain/Entry.kt` (the rule, beside `canBeFavouritedBy`),
  `gratitude/service/EraseEntry.kt`, `gratitude/web/EntryResponse.kt` (+`reaction`),
  `gratitude/service/GetToday.kt`, `GetDays.kt` and the submit/patch paths that build an
  `EntryResponse`, `GratitudeErrors.kt`, `common/web/.../ErrorCode.kt`
  (`REACTION_ON_OWN_ENTRY`), the migration-contiguity test, the OpenAPI configuration,
  `contracts/openapi.json` (regenerated), `app/.../SecretsNeverLoggedTest.kt`
- Test: `gratitude/web/ReactionsTest.kt` (new), `EntryChangesTest.kt` (its route set),
  `WithdrawEntriesTest.kt`, `WithdrawalRaceTest.kt`, `WithdrawalTwinArchiveTest.kt`,
  `DaysConditionalTest.kt`

```sql
CREATE TABLE reactions (
    entry_id  uuid NOT NULL REFERENCES entries (id) ON DELETE CASCADE,
    member_id uuid NOT NULL,
    type      text NOT NULL CHECK (type IN ('HEART', 'THANK_YOU', 'SMILE', 'MOVED')),
    PRIMARY KEY (entry_id, member_id)
);
```

**Interfaces — produces:**

```kotlin
internal enum class ReactionType { HEART, THANK_YOU, SMILE, MOVED }

@Component
internal class Reactions(/* JdbcTemplate, TransactionTemplate */) {
    /** True when the entry qualified and the reaction is now [type]; false when it did not. */
    fun set(entryId: EntryId, memberId: UUID, type: ReactionType): Boolean
    fun remove(entryId: EntryId, memberId: UUID)
    fun removeAllOn(entryId: EntryId)
    /** The one reaction on each of [entryIds] that has one. One query. */
    fun on(entryIds: Collection<EntryId>): Map<EntryId, ReactionType>
}
```

`EntryResponse.of(reading, date, favourited, reaction)`; `reaction` is forced `null` unless
the reading is `FULL`.

- [ ] **Step 1: failing tests** (`ReactionsTest`): set, replace and remove, each twice,
  `204`; the reaction shows on `/today`, the feed and the day view **for both members**,
  the same value; own entry `409 REACTION_ON_OWN_ENTRY` (revealed and unrevealed); the
  partner's unrevealed entry, a stranger, a missing id and a non-UUID are whole-response
  identical `404`s on both verbs; a tombstone `409 ENTRY_IMMUTABLE` on `PUT`, `204` on
  `DELETE`; a withdrawn author's entry before the consumer runs answers as a tombstone
  does; an ended bond and a member who left: `PUT` `409 BOND_ARCHIVED`, `DELETE` `204`;
  every body the standing refusal: unknown type, lower case, a number, `null`, missing,
  an unknown key beside it, a duplicated key, an array, 1 MB; no `Content-Type`; the two
  members each react to the other's entry and neither changes the other's reaction.
- [ ] **Step 2: nothing else moves.** The author's `/today`, feed and day view (bodies and
  `ETag`s) are byte-identical before and after the partner reads `/today`, the feed and the
  day, marks a favourite, and sends a refused reaction; they change exactly when a reaction
  is set, replaced or removed.
- [ ] **Step 3: erasure.** A delete, a withdrawal (consumer first and closer first) each
  leave no `reactions` row on the entry; the withdrawer's own reactions on the partner's
  entries stay (decision 9); the withdrawal twin in `WithdrawalTwinArchiveTest`, with
  reactions on both sides, is still identical to the other member in every view; the
  reader's tag does not move a second time when the row is erased.
- [ ] **Step 4: the statement.** An erasure in flight: the `PUT` waits, then `409`, no row;
  the lock timeout through a test seam; a `DELETE` returns at once while an eraser holds
  the row; the caller's own entry can never get a row whatever the service does (assert
  on the statement at the store).
- [ ] **Step 5: implement.** Route set in `EntryChangesTest`; `SecretsNeverLoggedTest`
  drives both verbs; contract regenerated, the diff read.
- [ ] **Step 6: mutations**: drop `author_member_id <> ?`; drop the ended-bond refusal; skip
  `removeAllOn` in `EraseEntry`; render `reaction` on a tombstone; `on` returning the
  caller's own reaction only; `409` for a locked entry; reader made before the entry.
- [ ] **Step 7: `./gradlew build`; commit**
  `feat(gratitude): a member can react to their partner's revealed entry (FR-048)`.

### Task 2: Smoke, the tools, and the record

- [ ] `scripts/smoke.sh`: probes in the archive section's style — set, replace, remove; both
  members see it; own entry `409`; a random id `404`; an unknown type `422`; after an
  ending, `PUT` is `409 BOND_ARCHIVED` and `DELETE` `204`; after a withdrawal the reaction
  on the withdrawn entry is gone and the tag is steady across the erasure. Run with
  `MOYI_DB`; record totals and sections; drop the database.
- [ ] `scripts/moyi`: `react <entryId> <type>`, `unreact <entryId>`. `tools/bruno`: two
  requests.
- [ ] `adr/0037-reactions.md` (the eleven decisions; Consequences; Owed; Questions that are
  the owner's: the placeholder set, the routes' shape against doc 06, no stored time, the
  millisecond race in decision 5, decision 9, that a visible reaction is by the corpus's
  own argument a read receipt and was ruled acceptable; what the corpus now says that is
  false; How this was checked). Spec §5.2, §5.3, §7 amended in place, dated.
  `docs/learning-log.md`. ADR-0036 "Owed, C5c" marked discharged item by item.
- [ ] Figma: `states.md` draws no reaction and `EntryCard` has no slot for one. Record the
  gap in ADR-0037 and spec §11; do not invent a screen.

## After the tasks

An independent review of Task 1, then two whole-branch readers (privacy, contract and
conformance; concurrency), fixes test-first, then a pull request labelled
`breaking-api-change`, with the concept brief.
