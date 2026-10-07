# Slice C5b, the archive and favourites — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** A member can page back through the days of a bond, newest first, read both entries
of a day they are entitled to read, and privately bookmark an entry.

**Architecture:** The archive is a read of `bond_days` by keyset on `date`, filtered in SQL to
days the caller has something to see on, then rendered entry by entry through the one read
gate (`Entry.canBeReadBy`), with who-has-withdrawn asked after the entries are loaded
(ADR-0035 decision 12). A favourite is a row keyed by member and entry, never a field of the
entry; it is written by one guarded statement and removed by `EraseEntry`. Both archive
routes answer a conditional `GET` from a digest of the caller's own rendered response.

**Tech stack:** Kotlin, Spring Boot 4.1, Postgres 18 (Testcontainers), `JdbcTemplate` for the
feed and the favourites (the `StreakCalendar` precedent), JPA for entries.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` §4, §5.2, §6.6, §7, §9,
§11; doc 03 FR-090, FR-093, FR-064; doc 04 `EntryFavourite`, BR-1, BR-8; doc 06 §3 (the
`/days` rows, pagination, favourites); `states.md` §6 and §9; `adr/0035` "Owed — C5b".

**Stacked on `feat/gratitude-archive` (PR #58, unmerged).** Branch `feat/gratitude-days`,
worktree `.worktrees/feat-gratitude-days`. Migration **V22**. Rebase when #58 merges.

## Global constraints

- Never commit to `main`; the owner merges. Conventional Commits, subject ≤ 88 characters.
- Test first, seen to fail for the stated reason. After a task's tests pass, break the
  mechanism and see the named test fail; say which in the report.
- **Every entry leaves the service layer as an `EntryReading`.** No route decides
  readability from a day's status, a SQL predicate or a flag. A closed `SOLO` day whose
  entry has no `revealedAt` is private to its author (ADR-0033 decision 9).
- **Who has withdrawn is asked after the entries are loaded** (`BondAccess.readerNow`,
  ADR-0035 decision 12), on every path that renders without the bond lock.
- **BR-8:** an entry the caller was never shown serialises to `{authorMemberId, status}` and
  nothing else, live (`LOCKED`) or erased (`REMOVED`). No id, no time, no `favourited`.
- **No response carries a tombstone's `deletedAt` or `updatedAt`**, and none carries the
  partner's favourites in any shape: no count, no aggregate, no flag (FR-093, FR-064).
- A non-member, an unknown bond and a malformed id get one byte-identical `404`.
- Entry text never reaches a log, an exception message or an `ETag` in recoverable form.
- No response over 256 KB (NFR-008).
- V22 is applied to no shared database: smoke with `MOYI_DB=<name>`, dropped after.
- Log-scan and body-scan markers in tests must not be able to occur in a UUID.

## Decisions this plan makes (each goes into ADR-0036)

1. **Which days the archive lists: a day the caller has something to see on.** A day is
   listed for a member when it holds an entry they wrote, or one that has been revealed.
   That is doc 06's "revealed and solo days", the spec's "subsequently frozen solo days", and
   `states.md` §9's all-tombstone days, in one rule; it also lists a member's own entry on a
   day that never revealed, to that member only (BR-1's first clause). `EMPTY` days, and
   days holding only an entry the caller may not read, are absent. Today is listed on the
   same rule.
2. **The SQL filter is a filter; the gate decides.** The query picks days by the rule in 1;
   every entry is then rendered through `canBeReadBy`. A test holds the two together: over
   the whole matrix of day status × entry state × reader, a day is listed exactly when the
   gate answers `FULL` or `TOMBSTONE` for at least one of its entries.
3. **A day is `{date, status, myEntry, partnerEntry}`**, the shape of `GET /today` without
   the streak. `status` is the day's own (the owner's ruling, 2026-10-06).
4. **`EntryResponse` gains `favourited: Boolean`**, the caller's own mark, on every route
   that returns one. It is `true` only on a `FULL` reading; a tombstone says `false`.
5. **Keyset on `date`, newest first.** `limit` 1–50, default 20. The cursor is opaque
   (base64url of a versioned date) and means "days before this one". `until=YYYY-MM-DD`
   starts a page at that date or the nearest earlier one: the month jump `states.md` §6
   draws and doc 06 gives no way to make. A cursor, limit or date that cannot be read is
   `422 VALIDATION_FAILED`.
6. **A page is also bounded by bytes.** Days are added while the page's entry text stays
   within 192 KiB; the first day is always included. `nextCursor` then comes earlier than
   `limit` would have put it. Twenty days of two full entries would be 327,680 bytes.
7. **`favourites=true` lists days holding an entry the caller has marked and can read.**
8. **`GET /days/{date}`** is `404 DAY_NOT_FOUND` for a date that is not in the caller's
   archive by the rule in 1, whatever the reason: no such day, a day with nothing for this
   caller, a date that is not a date. One answer, so the route cannot be used to ask
   whether the partner wrote.
9. **Conditional `GET`:** both routes send a strong `ETag`, the SHA-256 of the response body
   as serialised for this caller, and `Cache-Control: private, no-cache`. `If-None-Match`
   that matches is `304` with no body. The digest is of the whole body, so a favourite, a
   tombstone or a reveal each change it; and it is of text, so it is not reversible to text.
10. **A favourite is a row `(entry_id, member_id, created_at)`**, primary key the pair, with
    a real foreign key to `entries` (same module). `PUT` and `DELETE
    /entries/{entryId}/favourite` are `204` and repeatable.
11. **Who may mark what, decided by the gate's answer for the caller:** `FULL` and revealed —
    yes. `FULL` and not revealed (their own entry, waiting) — `409 ENTRY_NOT_REVEALED`.
    `TOMBSTONE` — `409 ENTRY_IMMUTABLE` on `PUT`; `DELETE` is `204`. `LOCKED`,
    `TOMBSTONE_UNSEEN`, not a member, no such entry, not a UUID — the one `404`. This is not
    `ChangeEntry.authorOf`'s rule, on purpose: either member may mark either entry (doc 04).
12. **The mark is one guarded statement**: `INSERT … SELECT … FROM entries WHERE id = ? AND
    revealed_at IS NOT NULL AND deleted_at IS NULL AND status <> 'DELETED' ON CONFLICT DO
    NOTHING`. An erasure that commits first leaves nothing to insert. No lock is taken.
13. **`EraseEntry` deletes the entry's favourites**, so a delete, a withdrawal and the
    closer's pre-step all remove them. A foreign-key cascade never fires: the row is kept.
14. **Favourites are allowed on an ended bond and after leaving it.** A bookmark is the
    member's own and changes nothing the other person can see; the archive is what an ended
    bond *is*. BR-9's "every write on an archived bond is `409`" is read as writes to what
    the two share. *The owner's to turn.*

## Review focus

- A stranger, the partner and a missing id are indistinguishable on every new route.
- The partner's favourites change no byte of any response the other member can get,
  including the `ETag`.
- A withdrawal committed mid-request hides its author's words in the feed and the day view.
- A page never exceeds 256 KB, and paging to the end visits every listed day exactly once,
  including across a day added or erased between pages.
- The feed answers in time that does not grow with the bond's age (the plan of the query).

---

### Task 1: Favourites

**Files:**
- Create: `modules/gratitude/src/main/resources/db/migration/V22__gratitude_entry_favourites.sql`
- Create: `gratitude/infra/database/Favourites.kt`, `gratitude/service/FavouriteEntry.kt`,
  `gratitude/web/FavouritesController.kt`
- Modify: `gratitude/service/EraseEntry.kt`, `gratitude/web/EntryResponse.kt` (+`favourited`),
  `gratitude/service/GetToday.kt` and the replay/submit paths that build an `EntryResponse`,
  `gratitude/service/GratitudeErrors.kt`, `common/web/.../ErrorCode.kt` (`ENTRY_NOT_REVEALED`),
  the migration-contiguity test, `contracts/openapi.json` (regenerated)
- Test: `gratitude/web/FavouritesTest.kt` (new), `gratitude/web/EntryChangesTest.kt` (its
  route-set assertion), `gratitude/service/WithdrawEntriesTest.kt`, `RevealGateTest.kt`

```sql
CREATE TABLE entry_favourites (
    entry_id   uuid        NOT NULL REFERENCES entries (id) ON DELETE CASCADE,
    member_id  uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (entry_id, member_id)
);
CREATE INDEX entry_favourites_by_member_idx ON entry_favourites (member_id, entry_id);
```

**Interfaces — produces:**

```kotlin
@Component
internal class Favourites(/* JdbcTemplate */) {
    /** True when a row now exists for the pair; false when the entry is not one that can be marked. */
    fun mark(entryId: EntryId, memberId: UUID, now: Instant): Boolean
    fun unmark(entryId: EntryId, memberId: UUID)
    fun removeAllOf(entryId: EntryId)
    /** Which of [entryIds] this member has marked. One query. */
    fun markedBy(memberId: UUID, entryIds: Collection<EntryId>): Set<EntryId>
}
```

`EntryResponse.of(reading, date, favourited: Boolean)`; `favourited` is forced `false` unless
the reading is `FULL`. `FavouriteEntry.mark/unmark(userId, entryId)` resolve the entry, then
the membership (`NotFoundException` → the one `404`), then build the reader **after** the
entry is loaded and decide by decision 11.

- [ ] **Step 1: failing tests** (`FavouritesTest`): mark and unmark, each twice, `204`;
  `favourited` then shows on `GET /today` for the caller and **not** for the partner; either
  member may mark either revealed entry; own unrevealed entry `409 ENTRY_NOT_REVEALED`;
  partner's locked entry, a stranger, a missing id and a non-UUID are byte-identical `404`s
  on both verbs; a tombstone is `409 ENTRY_IMMUTABLE` on `PUT`, `204` on `DELETE`; a
  withdrawn author's entry before the consumer runs answers as a tombstone does; allowed on
  an ended bond and for a member who left; a delete, a withdrawal and the closer's pre-step
  each leave no `entry_favourites` row for the entry; the partner marking and unmarking
  changes no byte of the other member's `GET /today`.
- [ ] **Step 2: the race.** Hold the entry's row in a transaction that erases it; start a
  `PUT`; see the insert's statement blocked or, if it does not block, see that after the
  erasure commits no favourite row exists and the `PUT` answered `409`. Assert on rows.
- [ ] **Step 3: implement.** `EntryChangesTest`'s route set gains the two routes.
  Regenerate the contract; read the diff.
- [ ] **Step 4: mutations**: drop `revealed_at IS NOT NULL` from the insert; skip
  `removeAllOf` in `EraseEntry`; render `favourited` from any member's row.
- [ ] **Step 5: `./gradlew build`; commit**
  `feat(gratitude): a member can privately bookmark an entry (FR-093)`.

### Task 2: The archive feed

**Files:**
- Create: `gratitude/infra/database/ArchiveDays.kt` (the keyset query),
  `gratitude/service/GetDays.kt`, `gratitude/web/DaysController.kt`,
  `gratitude/web/DayResponse.kt`, `gratitude/web/DayCursor.kt`
- Modify: `GratitudeCrossTenantTest` (fixtures), `contracts/openapi.json`
- Test: `gratitude/web/DaysFeedTest.kt`, `gratitude/web/ArchiveGateTest.kt`,
  `gratitude/infra/database/ArchiveDaysTest.kt`

**Interfaces — produces:**

```kotlin
internal data class DayView(val date: LocalDate, val status: BondDayStatus, val myEntry: EntryReading?, val partnerEntry: EntryReading?, val marked: Set<EntryId>)
internal data class DaysPage(val days: List<DayView>, val next: LocalDate?)

@Service
internal class GetDays {
    fun page(membership: BondMembership, before: LocalDate?, until: LocalDate?, limit: Int, favouritesOnly: Boolean): DaysPage
    fun day(membership: BondMembership, date: LocalDate): DayView?   // Task 3
}
```

`GET /api/v1/bonds/{bondId}/days?limit&cursor&until&favourites` → `{ items: [DayResponse], nextCursor }`.

The query (decision 1), `limit + 1` rows:

```sql
SELECT d.id, d.date, d.status FROM bond_days d
WHERE d.bond_id = :bondId AND d.date < :before AND d.date <= :until
  AND EXISTS (SELECT 1 FROM entries e WHERE e.bond_day_id = d.id
              AND (e.author_member_id = :me OR e.revealed_at IS NOT NULL))
ORDER BY d.date DESC LIMIT :n
```

with, for `favourites=true`, a second `EXISTS` over `entry_favourites` joined to a revealed,
unerased entry of that day. Entries for the page's days are loaded in one query, then
who-has-withdrawn is asked (`readerNow`), then each entry is read. Which row is "mine" and
which the partner's follows `GetToday`'s rule (live before erased, newest first).

- [ ] **Step 1: failing tests.** `DaysFeedTest`: newest first; `limit` default, bounds and
  `422`s; a cursor walks every listed day once and ends with `nextCursor: null`; a tampered
  or foreign cursor is `422`; `until` starts at the date or the nearest earlier day; the
  byte budget cuts a page of large entries short and the walk still visits every day once
  (assert the response is under 256 KB); `favourites=true`; an ended bond and a member who
  left can read; a day added or erased between two pages is neither skipped nor repeated.
- [ ] **Step 2: `ArchiveGateTest`**, the matrix (decision 2): every day status × each
  member's entry state (none, live unrevealed, revealed, erased before reveal, erased
  after) × reader (each member). For each: listed or not, and the exact JSON shape of each
  entry (`FULL` with text; wide tombstone; `{authorMemberId, status: LOCKED}`;
  `{authorMemberId, status: REMOVED}` — assert the exact key set). Includes the closed
  `SOLO` day with no `revealedAt` (#57): listed for its author, absent for the partner.
  And the cross-check: listed ⇔ the gate answers `FULL` or `TOMBSTONE` for some entry.
- [ ] **Step 3: withdrawal.** With no dispatcher run: a withdrawn author's entries are
  tombstones in the feed for both members. With a membership taken before a block that
  then commits: still tombstones (the marker is read last).
- [ ] **Step 4: cross-tenant fixtures**; the suite fails until they exist.
- [ ] **Step 5: the plan of the query.** `EXPLAIN` on a bond with 2,000 days: an index scan
  on `(bond_id, date)`, no sort, no scan of the whole table. Add the index doc 07 names
  (`bond_days (bond_id, date DESC)`) to V22 only if the unique `(bond_id, date)` does not
  already serve it; say what `EXPLAIN` showed.
- [ ] **Step 6: mutations**: drop the `EXISTS`; render from day status instead of the gate
  for a `SOLO` day; marker read first; no byte budget.
- [ ] **Step 7: `./gradlew build`; commit**
  `feat(gratitude): the archive feed — days a member may see, newest first (FR-090)`.

### Task 3: One day, and conditional requests

**Files:** `gratitude/web/DaysController.kt`, `gratitude/web/DayEntityTag.kt`,
`gratitude/service/GetDays.kt`, `GratitudeErrors.kt`, `ErrorCode` (`DAY_NOT_FOUND`);
tests `gratitude/web/DayViewTest.kt`, `gratitude/web/DaysConditionalTest.kt`.

- [ ] **Step 1: failing tests.** `GET /bonds/{bondId}/days/{date}`: the same `DayResponse`
  as the feed gives for that day (byte-identical); `404 DAY_NOT_FOUND`, one body, for a date
  with no row, an `EMPTY` day, a day holding only the partner's unrevealed entry, a future
  date, `2026-02-30`, `yesterday`; and the bond-level `404` for a stranger is the bond's,
  as everywhere.
- [ ] **Step 2: conditional.** Both routes: `ETag` present and quoted; `If-None-Match` equal
  → `304`, no body, same `ETag`; changes when the caller marks a favourite, when an entry
  is erased, when a day reveals; **does not change** when the partner marks a favourite;
  differs between the two members for the same day; `Cache-Control: private, no-cache`.
- [ ] **Step 3: implement; cross-tenant fixture; contract regenerated.**
- [ ] **Step 4: mutations**: digest only `date` and `status`; include the partner's marks in
  the digest input; answer `404` only for a missing row (the locked-partner day then leaks).
- [ ] **Step 5: `./gradlew build`; commit**
  `feat(gratitude): one day of the archive, and conditional reads of both`.

### Task 4: Smoke, the tools, and the record

- [ ] `scripts/smoke.sh`: a section that writes and reveals days (the script's existing way
  of ending a day, or the close job's short cron), pages the feed, reads a day, marks and
  unmarks, sends `If-None-Match`, and reads the archive after a withdrawal. Run with
  `MOYI_DB`; record totals; drop the database.
- [ ] `scripts/moyi`: `days`, `day <date>`, `favourite <entryId>`, `unfavourite <entryId>`.
  `tools/bruno`: the four requests.
- [ ] `adr/0036-the-archive-and-favourites.md` (the fourteen decisions; Consequences; Owed;
  Questions that are the owner's; How this was checked). Spec §5.2, §6.6, §7, §11 amended in
  place, dated. `docs/learning-log.md`. Label the PR `breaking-api-change` (two new error
  codes).
- [ ] Figma alignment (`states.md` §6): each endpoint has a screen, an adaptation, or a
  recorded gap. Known gaps to record: no author display name on an archive entry; a solo
  day card is not drawn; the month jump's `until`.

## After the tasks

Whole-branch review by three readers (concurrency; privacy and contract; conformance),
fixes test-first, then a draft pull request with the concept brief.
