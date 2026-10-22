# ADR-0039 — Search and on this day

**Status:** Accepted · **Date:** 2026-10-22 · **Deciders:** Daniel, for the four rulings of
2026-10-21 (Q-S2, Q-S4, Q-O3, Q-O2) only. The ten defaults under "The controller's" were set
by the controller of the C6 session; every other decision here was made by the plan or by
the agents that ran it.

## Context

Two reads of the archive, the second and third of slice C6's three pull requests (ADR-0038,
Context: C6a prompts, **C6b** search and on-this-day with V25, C6c milestones).

**Search.** FR-050 (doc 03): "The full-text of entries MUST be searchable by their Bond
members only." FR-091: "Members MUST be able to full-text search their Bond's revealed
entries." Doc 06:172: `GET /bonds/{id}/search?q=&limit&cursor`, "Full-text over revealed
entries in this Bond only". Doc 07:149–157 specifies `text_search text`, a generated
`search_vector tsvector` from it, and a GIN index (doc 07:268); doc 07:200–202 says the
projection is the text itself before Phase 5 and `'simple'`, not `'english'`, because entries
are multilingual; doc 07:319 says an erasure nulls `text_search`. NFR-004: p95 ≤ 300 ms over
500k entries; NFR-008: no response over 256 KB; NFR-033: no query whose cost grows with the
table rather than the page. T-11: `plainto_tsquery`, never raw input; T-01, T-02: no
cross-tenant read; T-19: no entry content in logs or traces. D-13 (doc 09:140): after Phase 5
search works against a lossy projection, "an acknowledged partial disclosure". ADR-0035's
Owed: V12 has `entries.text_search` and nothing writes it; the slice that writes it must have
every erasure clear it, and a search must go through the gate. ADR-0036 decisions 1, 2, 3, 5,
6, 7 and 15 (the archive's predicate, the gate as the decider, the day shape, the cursor, the
byte bound, the windowed fill that hides a withdrawal, the marker read last) say how any
further read of entries must behave.

**On this day.** FR-092 (doc 03, SHOULD): "'On this day' resurfacing, opt-in, at most one per
week." Doc 06:175: `GET /bonds/{id}/on-this-day`, "Same date in prior years". Doc 02:135:
"one year ago today", opt-in, one push a week.

**The corpus read** (`c6-corpus.md` §2, §3 and §5, 2026-10-15, in the session's working folder
and not in this repository) found ten contradictions and fourteen privacy hazards:

- **S1** "revealed entries" against the archive's predicate, which includes one's own
  unrevealed entry; **S2** doc 06 returns entries and the screen draws days; **S3** relevance
  or time; **S4** what the projection is (doc 07:200's stemmed tokens against doc 07:202's
  copy under `'simple'`); **S5** doc 07's `search_vector` and GIN did not exist, and V12's
  comment described `text_search` as the vector; **S6** what an erasure clears (ADR-0035:757,
  "a withdrawal sets neither", held only while nothing wrote the column); **S7** GIN on the
  vector alone against NFR-033.
- **O1** one year ago or every prior year; **O2** opt-in of the surface or of the push;
  **O3** a job or a read.
- **H-S1** a withdrawn author's words before the erasure; **H-S2** the partner's unrevealed
  entry must not match; **H-S3** no count to infer a match from; **H-S4** tombstones;
  **H-S5** the query is the user's words; **H-S6** caching; **H-S7** cross-tenant; **H-S8** a
  snippet that Phase 5 would take away. **H-O1** the archive's visibility; **H-O2** withdrawn
  words; **H-O3** an ended bond, byte-identical left or blocked; **H-O4** opt-in per member and
  private; **H-O5** days of tombstones; **H-O6** the bond's date.

Which test answers each hazard, as built (the plan's table, corrected by the reviews):

| Hazard | Test that fails without its mechanism |
|---|---|
| H-S1 | `SearchWithdrawalTest`, all three tests (the exclusion before `LIMIT`: "…over a history longer than one request reads"; the gate after the entries: the race test) |
| H-S2 | `SearchTest`: "the caller's own unrevealed entry is found…", "a word only in the partner's unrevealed entry of a past day…", "…over a history longer than one request reads" (only the last sees the row predicate moved to the day: decision 2) |
| H-S3 | `SearchTest`: "a page is days and a cursor and nothing else, and is bounded by count and by bytes" |
| H-S4 | **`SearchProjectionTest`** (the delete, withdrawal and close-job tests) and `V25BackfillTest`; **`SearchCandidatesTest`** for an erased row whose projection an older build left in place (`LIVE_ROW`, final review I1). Not `SearchTest`, as the plan said: with the projection kept after an erasure, every search test stays green, because the gate drops the tombstoned day (Task 2 review m1) |
| H-S5 | `SearchTermsTest`, `SearchTest`'s 422 test, `SecretsNeverLoggedTest` (q watched, every request line, an undecodable q, a refused request target) |
| H-S6 | `CacheHeadersTest`, the search and on-this-day lines |
| H-S7 | `GratitudeCrossTenantTest` fixtures for both routes; the stranger's one 404 for every q that Tomcat can decode (an undecodable one is everyone's 400, member or stranger, after authentication: decision 22) |
| H-S8 | `SearchTest`: "a found day carries no snippet, highlight or rank"; `OpenApiContractTest` |
| H-O1 | `OnThisDayCandidatesTest`: "the partner's unrevealed entry is no candidate…"; `OnThisDayTest`: "a past day is resurfaced by the archive's rule…" |
| H-O2 | `OnThisDayCandidatesTest`: "a day whose only live entry is a known withdrawn author's…"; `OnThisDayTest`'s race test |
| H-O3 | `OnThisDayTest`: "an ended bond resurfaces nothing, the same bytes left or blocked, for both members" (bodies and headers), "a deletion countdown resurfaces nothing…", "an ending committed after the guard resolved the membership still resurfaces nothing" (final review m2) |
| H-O4 | `OnThisDayTest`: "the answer does not move with anything the partner does but react" (no setting exists to read; the partner's bookmark is the nearest leak, and the review saw it red) |
| H-O5 | `OnThisDayCandidatesTest`: "a day of tombstones is no candidate…"; `OnThisDayTest`: "a day of tombstones is not resurfaced…" |
| H-O6 | `OnThisDayTest`: "today is the bond's date, not UTC's", "29 February only on 29 February"; `OnThisDayCandidatesTest`'s 29 February test. DST, a day an anchor change skipped and a bond younger than a year are held **by construction, not by route tests**: the date is `todayOf` (decision 16), which is `DayAssignment.dateFor`, shared with `GET /today`; a skipped day has no row, so no memory; a bond's first year has no earlier row on any date |

The slice was built in four tasks (`docs/superpowers/plans/2026-10-21-search-c6b.md`), each
with an independent review, on 2026-10-21 and 2026-10-22, then two whole-branch reviews and
one fix round. The branch was cut from C6a's and rebased onto `main` twice: at `4207f85`
once #66 merged (after Task 2), and at `25fa9c1` once #67 merged (after Task 4). Commits are
named here as they stand on the branch after the second rebase; each run was made on the
same change before it, and the local tags `pre-rebase-c6b-t2` and `pre-rebase-c6b-t4` hold
those commits. #67 changed ADRs only, so no code differs.

## Decision

### The owner's rulings

Asked directly by the controller on 2026-10-21, and answered by Daniel:

- **Q-S2. Search uses the archive's predicate** (`ArchiveDays.SEEN`): the caller's own
  unrevealed entries are found; the partner's only once revealed. Resolves S1.
- **Q-S4. Whole words; accents fold.** `unaccent` under the `'simple'` configuration: no
  stemming, no prefix. Resolves S4 for v1.
- **Q-O3. On-this-day answers empty on an ended bond**, left or blocked alike, byte-identical.
  Resolves H-O3.
- **Q-O2. Opt-in gates only the weekly push** (Phase 4). The endpoint always answers; C6b
  builds no setting. Resolves O2 and H-O4.

A peer session recorded on 2026-10-21 that the owner's ruling "a deletion countdown doesn't
count as ended" amends ADR-0033's question 1 only (the lone-entry reveal); decision 14 below
and ADR-0038's ruling 6 stand.

### The controller's defaults

Set by the controller of the C6 session on 2026-10-21, each the owner's to turn:

1. **No server snippet or highlight** (Q-S1, H-S8); a client may highlight.
2. **Results are days in the archive's shape, newest first**, keyset on the date with the
   archive's cursor; no count or total (H-S3); pages bounded by bytes. (S2, S3.)
3. **Search reads on an ended bond and for the member who left**, as the archive does (Q-S5).
4. **On-this-day gives every prior year** with a listed day on that bond-local date, newest
   first, unpaginated and bounded by bytes (Q-O1).
5. **Days whose visible entries are all tombstones are not resurfaced**, excluded in SQL; solo
   days follow `SEEN` (Q-O4, H-O5).
6. **Placement is a design gap**: there is no frame (Q-O5); recorded in spec §11.
7. **29 February shows only on 29 February; "today" is the bond-local date** (BR-3, H-O6).
8. **Doc 07 is followed**: the application writes `text_search` (on submit and on a pre-reveal
   `PATCH`); `search_vector` is generated from `text_search`; a GIN index; every erasure nulls
   `text_search`; V25 backfills live rows. The plan of the query is measured on a seeded large
   dataset, and the index becomes `btree_gin (bond_id, search_vector)` if the plan scales with
   the table (S5, S6, S7). It did (decision 7).
9. **`plainto_tsquery`, never raw; `q` 1–100 graphemes, blank `422`, never echoed; `q` in no
   log or trace** (T-11, H-S5).
10. **`Cache-Control: private, no-store` on both; both routes get cross-tenant fixtures**
    (H-S6, H-S7). **Built otherwise, by decision 17:** both routes keep the API's default
    `no-cache, no-store, max-age=0, must-revalidate`, with `Pragma: no-cache` and `Expires: 0`.
    The controller accepted the deviation on 2026-10-21.

### What the plan decided

Numbered as the plan numbers them, because code and tests cite them by number. Under each,
what was run to decide it or to hold it.

1. **Search is the archive's reading with another candidate query.** `GetDays.page`'s loop
   (candidates in windows, the gate after each window's entries, the marker read last, a dropped
   day leaving no trace, the byte bound, the look-ahead) is one private `fill` that `page`,
   `search` and (through `Filling`) `onThisDay` share. ADR-0036 decision 7 is what a second
   copy cost. *Run:* after the refactor the archive's suites (`DaysFeedTest` 29,
   `WithdrawalTwinArchiveTest` 5, `ArchiveGateTest` 14, `DayViewTest` 17,
   `ArchiveReadOrderTest` 5) passed unchanged, 70 of 70 (Task 2).
2. **The match and the visibility are asked of the same row** (H-S2), and the known withdrawn
   are excluded on that row before `LIMIT` (H-S1). `ArchiveDays.matching` returns each day with
   the ids of its matching entries. **Two guards hold H-S2 and H-S1**: this SQL and decision
   3's filter. *Run:* with `SEEN_ROW` asked of the day instead of the row, only "…over a history
   longer than one request reads" went red (`expected [["2020-01-01"]] but was [[],
   ["2020-01-01"]]`, an empty page with a cursor); the two shorter H-S2 tests stayed green,
   because the gate drops the day. Passing `emptySet()` for the withdrawn did the same to
   `SearchWithdrawalTest`'s long-history test, and the twin test stayed green. So each guard's
   removal is invisible while the other stands, except over a history longer than one request
   reads (Task 2).
3. **After the gate, a day is shown only if a matching entry was read in full.** A withdrawal
   that commits between the candidates and the marker read is dropped by the gate and the fill
   reads on. *Run:* `shows = { _, _ -> true }` left all of `SearchTest` green and turned the race
   test red; the twin test stayed green, against the plan's prediction, because its block has
   committed before the search, so the SQL already excludes her rows and nothing reaches the
   filter. Weakened to "any entry read in full", only the race test went red (Task 2 review m2).
   **This filter is held by the race test alone.**
4. **A shown day is the whole day**, both entries through the gate; nothing says which matched
   (default 1). *Run:* `SearchTest`'s "a found day carries no snippet, highlight or rank".
5. **`text_search` is written by `EntryStore`, from the entry's text, and nowhere else**
   (`searchProjectionOf`, `infra/database/SearchProjection.kt`; today the text as stored). The
   domain `Entry` does not carry it. *Run (Task 1):* `searchProjectionOf` returning null turned
   4 of 5 `SearchProjectionTest` tests red; dropping the write from `EntryStore.update` turned
   4 red (edit, delete, withdrawal, close job); `textSearch = null` in `toEntity` turned the
   submit and close-job tests red (the edit test stayed green, against the prediction: the edit
   goes through `update`), and the review confirmed the submit test reads the projection before
   any reveal. *Read (Task 1 review):* every write of `entries` in `src/main` goes through
   `EntryStore.insert` or `update`; there is no trigger and no other writer. **The search does
   not rely on that write to hide an erased entry**: `matchingQuery` asks `LIVE_ROW` of the row
   that matched, as on-this-day does, because a build from before V25 erases without emptying
   the projection (final review I1; the deploy step in Consequences empties the row). *Run (fix
   round):* without `LIVE_ROW`, `SearchCandidatesTest` went red, 2 of 2, and every HTTP search
   test stayed green, since the gate drops the day either way.
6. **`search_vector` is generated from `text_search` through an `IMMUTABLE` wrapper**,
   `public.immutable_unaccent(text)`, a SQL-standard body naming the dictionary and the schema.
   *Run (2026-10-21, plan):* `unaccent` is `STABLE` in both forms (`provolatile = 's'`), and the
   wrapper works in a generated column. *Run (Task 1 review):* the function is `IMMUTABLE`, its
   body is recorded (`prosqlbody`) with a dependency on `public.unaccent`, so `DROP EXTENSION
   unaccent` is refused; with an `evil.unaccent` first on `search_path`, and with `search_path =
   ''`, it still folded correctly. A generated column without the wrapper turned
   `V25BackfillTest` red (`finds(live, "CAFE")`, Task 1).
7. **The query folds `q` with the same function**: `e.search_vector @@ plainto_tsquery('simple',
   public.immutable_unaccent(?))`, bound as a parameter. Every word must be in **one** entry;
   words split between the day's two entries do not find it. *Run (2026-10-21, plan, a throwaway
   `postgres:18` as a non-superuser):* `cafe` and `CAFÉ` find "café", `acao` finds "Ação", `caf`
   finds nothing, `ﬁne` finds "fine", Cyrillic and CJK runs are indexed as words, an emoji is no
   lexeme. *Run (Task 2):* the brief's `obrigada ação` (two words in two entries) found nothing,
   as this decision says; the brief's line was wrong and the controller ratified moving it to the
   not-found list (D1). Without the wrapper on `q`, the folding test went red, and per word in
   SQL exactly the predicted words stopped matching (`AÇÃO`, `café`, `Café`). `to_tsquery` in
   place of `plainto_tsquery` gave three `500`s.

   **The index, measured** (`SearchPlanMeasurement`, run with `MOYI_MEASURE=1`, Testcontainers
   `postgres:18`; one measured bond of 730 revealed days and 1,460 entries, the rest other bonds
   of 500 entries; `thank` in every entry, `lamp` in about one in eight, `zebra` in one;
   `EXPLAIN (ANALYZE, BUFFERS)` of the third, warm run. Criterion: no index scan returns more
   rows than the bond's 1,460 entries, at either size, and under 300 ms at 500k; after Task 2's
   review, also no `Seq Scan` on `bond_days` or `entries`.) With GIN on the vector alone it
   failed, on `lamp`, as the corpus predicted:

   ```
   C6b SEARCH MEASUREMENT 100k: {thank=Plan(buffers=913, widestIndexScan=1460.0, milliseconds=4.385, shape=Bitmap Index Scan on entries_bond_recent_idx), lamp=Plan(buffers=924, widestIndexScan=12506.0, milliseconds=1.47, shape=Bitmap Index Scan on entries_bond_recent_idx, Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey), zebra=Plan(buffers=216, widestIndexScan=1460.0, milliseconds=0.639, shape=Bitmap Index Scan on entries_bond_recent_idx, Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey)}
   C6b SEARCH MEASUREMENT 500k: {thank=Plan(buffers=5920, widestIndexScan=1460.0, milliseconds=2.259, shape=Bitmap Index Scan on entries_bond_recent_idx, Index Scan on bond_days_pkey), lamp=Plan(buffers=954, widestIndexScan=62509.0, milliseconds=2.588, shape=Bitmap Index Scan on entries_bond_recent_idx, Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey), zebra=Plan(buffers=16, widestIndexScan=1460.0, milliseconds=0.048, shape=Bitmap Index Scan on entries_bond_recent_idx, Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey)}
   ```

   With `btree_gin` and `GIN (bond_id, search_vector)` it passed (`lamp` 175 rows, not 62,509):

   ```
   C6b SEARCH MEASUREMENT 100k: {thank=Plan(buffers=913, widestIndexScan=1460.0, milliseconds=4.278, shape=Bitmap Index Scan on entries_bond_recent_idx), lamp=Plan(buffers=702, widestIndexScan=175.0, milliseconds=0.809, shape=Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey), zebra=Plan(buffers=133, widestIndexScan=1.0, milliseconds=0.442, shape=Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey)}
   C6b SEARCH MEASUREMENT 500k: {thank=Plan(buffers=5921, widestIndexScan=1460.0, milliseconds=2.157, shape=Bitmap Index Scan on entries_bond_recent_idx, Index Scan on bond_days_pkey), lamp=Plan(buffers=862, widestIndexScan=175.0, milliseconds=0.784, shape=Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey), zebra=Plan(buffers=119, widestIndexScan=1.0, milliseconds=0.429, shape=Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey)}
   ```

   The criterion did not see a second cost. A node-by-node print showed `thank` at 100k joined
   by a **Seq Scan of all of `bond_days`** (49,980 rows, 862 of 913 buffers): nothing told the
   planner that `d.bond_id` equals the bond. The join became `ON d.id = e.bond_day_id AND
   d.bond_id = e.bond_id`, the criterion gained "no Seq Scan", and the run without the clause
   failed at 100k only (the 500k plan happened to choose 1,460 primary-key probes). With it:

   ```
   C6b SEARCH MEASUREMENT 100k: {thank=Plan(buffers=73, widestIndexScan=1460.0, seqScans=[], milliseconds=0.726, shape=Bitmap Index Scan on entries_bond_recent_idx, Bitmap Index Scan on bond_days_feed_idx), lamp=Plan(buffers=200, widestIndexScan=730.0, seqScans=[], milliseconds=0.842, shape=Bitmap Index Scan on bond_days_feed_idx, Bitmap Index Scan on entries_search_idx), zebra=Plan(buffers=133, widestIndexScan=1.0, seqScans=[], milliseconds=0.426, shape=Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey)}
   C6b SEARCH MEASUREMENT 500k: {thank=Plan(buffers=75, widestIndexScan=1460.0, seqScans=[], milliseconds=0.757, shape=Bitmap Index Scan on entries_bond_recent_idx, Bitmap Index Scan on bond_days_feed_idx), lamp=Plan(buffers=911, widestIndexScan=187.0, seqScans=[], milliseconds=0.743, shape=Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey), zebra=Plan(buffers=119, widestIndexScan=1.0, seqScans=[], milliseconds=0.381, shape=Bitmap Index Scan on entries_search_idx, Index Scan on bond_days_pkey)}
   ```

   `thank` went from 913 to 73 buffers at 100k and from 5,921 to 75 at 500k. On-this-day's
   statement was measured the same way in Task 3 (two earlier years in the measured bond):

   ```
   C6b ON-THIS-DAY MEASUREMENT 100k: Plan(buffers=31, widestIndexScan=730.0, seqScans=[], milliseconds=0.086, shape=Bitmap Index Scan on bond_days_feed_idx, Index Scan on entries_bond_day_idx)
   C6b ON-THIS-DAY MEASUREMENT 500k: Plan(buffers=31, widestIndexScan=730.0, seqScans=[], milliseconds=0.075, shape=Bitmap Index Scan on bond_days_feed_idx, Index Scan on entries_bond_day_idx)
   ```

   With its `WHERE d.bond_id = ?` neutralised, that measurement went red: `Seq Scan on
   bond_days`, 8,326 buffers and 15.6 ms at 500k. The plan's own probe on 2026-10-21, before any
   task, had predicted the shape: GIN alone read the mid-frequency word's postings across all
   bonds (62,500), the `btree_gin` composite 63.
8. **A `q` with no lexeme** (punctuation only, emoji only) is `200` with `{"items":[],
   "nextCursor":null}`, not `422`. *Run (2026-10-21):* Postgres's notice ("text-search query
   contains only stop words or doesn't contain lexemes, ignored") carries no part of `q`. *Run:*
   `SearchTest`'s "words with no letters or digits in them…"; the smoke's `!!!` probe.
9. **`q` is required, 1 to 100 graphemes (counted on the NFKC form, trimmed, as an entry is
   counted today), at most 1,024 UTF-8 octets, with no U+0000.** One `422 VALIDATION_FAILED` per
   problem on field `q` (`REQUIRED`, `VALID_SEARCH`), never the value, read after the guard.
   *Run (Task 2):* dropping `.trim()`, counting `length` instead of graphemes, dropping the
   octet check and dropping the NUL check each turned `SearchTermsTest` red. `isBlank()` →
   `isEmpty()` survived: it is equivalent, because Kotlin's `trim()` already removes every
   character `isBlank` would see, after NFKC maps U+00A0 to a space. The check is redundant, not
   wrong, and has the same shape as `EntryText`'s. **The owner has since ruled how an entry is
   counted, to be built later** (ADR-0031, "Rulings — the owner, 2026-10-21", merged in #67):
   ruling 1, the 500 are counted as typed, as grapheme clusters of the text as sent, not on the
   NFKC form; ruling 2, an entry made only of zero-width characters is refused. Until those are
   built, `q` and an entry are counted the same way, on the NFKC form. Whether `q` follows the
   entry when they are is the owner's (question 11 below); today a `q` of zero-width characters
   only is accepted and finds nothing.
10. **The grapheme count lives in `domain/Graphemes.kt`**, shared by `EntryText` and
    `SearchTerms`. It counts whatever text it is given; what each caller hands it (the NFKC
    form today) is the caller's, and ADR-0031's ruling 1 changes what `EntryText` hands it.
    *Run:* `EntryTextTest` 12 of 12 unchanged.
11. **`q` sent twice is read as Spring binds it**, the two values joined by a comma. *Not run:*
    no test sends `q` twice; this is read from Spring's binding.
12. **The search page is `DaysResponse`**, the feed's `{items, nextCursor}`; limit 1–50, default
    20, and the cursor, are parsed by `DaysQuery`. Search also declares `400 MALFORMED_REQUEST`
    for an undecodable `q` (decision 22; final review m1), and its two limits are named mirrors of
    `SearchTerms`'s, which `SearchTermsTest` holds the committed contract to (final review m9).
    *Run:* `OpenApiContractTest`. The contract diff against `main` (`25fa9c1`) at the end of the
    fix round: 216 additions and 0 removals; two new paths and one new schema,
    `OnThisDayResponse`. oasdiff v1.11.7, run locally: `breaking --fail-on ERR` exit 0;
    `changelog` two infos, both `endpoint-added`. The fix round's own change, against the
    branch before it: one info, `response-non-success-status-added` (the 400 on search).
13. **On-this-day is `{items: [day…]}`** (`OnThisDayResponse`): days on the bond-local today's
    month and day in earlier years, newest first, at most `ON_THIS_DAY_YEARS = 100` candidates,
    filled by the archive's `Filling` under the same 192 KiB text bound, the first day always
    taken; days past the bound are left out with no cursor, **the oldest first**. *Run (Task 3):*
    a three-day test with the large day in the middle shows `Filling` stops at the first day that
    does not fit and does not take a smaller one after it; a "skip and keep filling" `Filling`
    turned it red, and `DaysFeedTest`'s largest-page test with it.
14. **On-this-day is empty whenever the bond takes no writes from the caller** (`!takesWrites`:
    ended, left, blocked, and the deletion countdown), decided before anything is read, so every
    cause gives the same bytes; a cancelled countdown brings the memories back. **It is asked of
    the membership resolved again** (`membershipNow`), after the joining-day reconcile and
    before any entry is read, not of the guard's: an ending that commits while the reconcile
    waits on the bond's lock left the guard's membership taking writes, and a block then got
    last year's memories (final review m2, reproduced). The same resolution gives the withdrawn
    the candidates exclude, so a request still resolves the membership three times. An ending
    that commits after that resolution is an answer from before it (ADR-0035 decision 12). *Run
    (fix round):* `OnThisDayTest`'s "an ending committed after the guard resolved the membership
    still resurfaces nothing" red before the change; with the guard's membership put back, only
    that test went red. *Run (Task 3):* the check dropped turned the ended-bond and countdown
    tests red; `hasLeft` in its place, and `!takesWrites && withdrawnMemberIds.isEmpty()`, each
    turned the ended-bond test red; the reviewer's `!isOpen && !hasLeft` (the leaver given
    memories) did too; a controller that added a header by cause turned the header comparison
    red. **`takesWrites` → `isOpen` survives**: no request leaves a bond open while it takes no
    writes. The empty body is decided after the guard and `ReconcileJoiningDay.beforeRead`
    (which reads the joining day's row, never entries) and before any read of entries (Task 3
    review, read and run).
15. **On-this-day lists a day only if it holds an entry SQL believes the caller may read in
    full** (`SEEN_ROW`, `LIVE_ROW`, not withdrawn, `d.bond_id = ?`), and after the gate only if
    one was read in full. *Run (Task 3):* `LIVE_ROW` dropped → the candidates test red; the
    exclusion of the withdrawn dropped → its candidates test red; `SEEN_ROW` made true → the
    partner's-unrevealed candidates test red; the gate's filter dropped → the race test red; both
    `LIVE_ROW` and the filter dropped → the HTTP tombstone test red. `WHERE d.bond_id = ?`
    neutralised was **green in both test classes** (the gate's `NOT_A_MEMBER` and the filter
    dropped another bond's days), so the implementer added "another bond's day on today's date
    is no candidate", which then went red (D1 of Task 3).
16. **"Today" is the bond's: `todayOf(membership, clock)`** in `service/BondCalendars.kt`,
    `DayAssignment.dateFor(clock.instant(), null, anchorTimeline.asCalendar()) { false }`, the one
    place the bond's today is computed for a read; `GET /today`, `GET /streak` and
    `GET /on-this-day` all call it (Task 3 review m2). Today itself is excluded (`d.date <
    today`); the match is on `EXTRACT(MONTH)` and `EXTRACT(DAY)`, so 29 February matches only 29
    February. *Run:* with `todayOf` returning UTC's date, six tests went red on `/today` and
    `/on-this-day` and **none on `/streak`**; a `StreakEndpointTest` at 23:30 UTC in Lagos (the
    bond's next day) was then added, and the same mutation turned it red (`expected:<2> but
    was:<1>`) and nothing else in its class (Task 4).
17. **Both routes keep the API's default `Cache-Control`** (`no-cache, no-store, max-age=0,
    must-revalidate`, with `Pragma: no-cache` and `Expires: 0`), against the letter of default 10;
    it forbids storing as `private, no-store` does, without making the routes exceptions in
    `CacheHeadersTest`. *Run:* a controller returning `private, no-cache` turned `CacheHeadersTest`
    red; the smoke reads the header off both routes from the jar.
18. **No `ETag`, no conditional read** on either route. *Read.*
19. **No rate bucket of their own**; the global 120 a minute per user. *Run:* every measured
    statement took at most 4.4 ms in the runs printed under decision 7, and under 1 ms in every
    run after the join (decision 7's third run, and the final reviews' and fix round's runs in
    How this was checked); one request is in practice one statement (a second window needs a
    race). The Task 2 review judged 120 a minute at most about a second of
    database time a minute (read).
20. **Both routes run the joining-day reconcile** after the guard, as the archive does (spec
    §12.4). *Run:* dropping it from search left `SearchTest` green; `JoiningDayTest` owns the
    joining day, and no search test was added for it.
21. **The guard is one function for the new controllers**, `BondAccess.guard` in
    `web/BondGuard.kt`: the one `404` for a bond that does not exist, one the caller is not in,
    and a value that is not a UUID. The older copies in `DaysController`, `EntriesController` and
    `StreakController` are left, because state-map `codeRef`s quote them (owed).
22. **The query string is held to the entry's standard in the logs.** `SecretsNeverLoggedTest`
    watches a search's words at every level. *Run:* it named **five** loggers that printed `q`
    below INFO, not the two predicted: Tomcat's `Parameters` (DEBUG), Spring Security's
    `FilterChainProxy` (DEBUG) and `RequestMatcherDelegatingAuthorizationManager` (TRACE),
    `HandlerMethod` (TRACE, the arguments) and `DispatcherServlet` (TRACE, the request line).
    Review rounds added `ExceptionHandlerExceptionResolver` (an undecodable `q` in a resolved
    exception) and Tomcat's `Http11Processor` (at WARN: it logs a refused request target,
    query and all, at INFO). Each is pinned in `application.yml`. With the first fourteen pins of
    that block in place, each removed in turn turned the test red naming exactly that logger
    (Task 2's fix round); the fifteenth, `Http11Processor`, was seen red without its pin through
    a raw-socket probe, since no HTTP client will send the refused request line. Two
    findings came with them, both fixed in this slice: **a query parameter that is not UTF-8**
    was a `500` whose `ERROR` line quoted the decodable part of the value (pre-existing,
    API-wide; now `400 MALFORMED_REQUEST` from `UndecodableParameterFilter`, ordered just after
    Spring Security so a `401` still comes first, with an `@ExceptionHandler` as a second guard);
    and **multipart parsing** wrote an upload to disk before authentication on the public
    POSTs (now `spring.servlet.multipart.enabled: false`; `MultipartRefusedTest`).
23. **CLAUDE.md's "never … a second column" gains its one exception**: `entries.text_search`,
    the search projection, and the `search_vector` Postgres generates from it (with its index),
    all emptied by every erasure; "search terms" join the list of what never reaches a log. The
    vector and its index were named after the final review (m3): they hold the same words, as
    lexemes. *Reason:* default 8 creates exactly the copy the rule forbids, and
    CLAUDE.md says the document wins and the file is the bug. The controller accepted it on
    2026-10-21 as a binding-rule change the owner may turn before merge. (How this was checked
    says where the text of the amendment stands.)
24. **`scripts/moyi` gives curl the URL in a file** (`-K`), for every command, so a search's
    words and an invite code are never an argument of a process. *Run (Task 4):* `ps` sampled
    while sixty searches ran showed `curl … -K <file>` and never the words; the same method on
    the previous script showed the whole URL.
25. **Search reads on an ended bond and for a member who has left** (default 3): no
    `takesWrites` check. *Run:* `SearchTest`'s "an ended bond is searched by both members…"; the
    smoke's last probe.

## Consequences

- **Two new routes, additive**: `GET /bonds/{bondId}/search` (`q`, `limit`, `cursor`;
  `DaysResponse`) and `GET /bonds/{bondId}/on-this-day` (`OnThisDayResponse`). **No new
  `ErrorCode`**, so the pull request does not carry `breaking-api-change`. `400
  MALFORMED_REQUEST` now also answers an undecodable query parameter on every route, where a
  `500` did; that code already existed.
- **Migration V25 (`modules:gratitude`)**: `unaccent`, `btree_gin`,
  `public.immutable_unaccent`, the backfill of `text_search` for live rows, the generated
  `search_vector` and `entries_search_idx` (GIN on `(bond_id, search_vector)`). Every write to
  `entries` now maintains the stored vector and the index.
- **V25 locks `entries` first, `ACCESS EXCLUSIVE`, then backfills and rewrites it.** Measured
  by the Task 1 review on 190,127 live rows (`postgres:18` 18.6): the backfill UPDATE 2.65 s,
  `ADD COLUMN … STORED` 5.53 s and the index 0.38 s; the table grew from 136 to 362 MB. With
  the lock taken before the backfill (final review m1), `entries` is unreadable for all three,
  **about 8 s per 200k rows** rather than the 6 s of the rewrite alone (the reviewer's figure,
  not re-measured). Taken only at the `ALTER`, the lock deadlocked with any writer of a row the
  backfill had locked: run by the reviewer and again in the fix round (`deadlock detected`, the
  migration the victim); taken first, the same writer waited and committed after it. V25
  begins with `SET LOCAL lock_timeout = '5s'`, which now bounds that first lock: run in the fix
  round, with another session holding the table, `canceling statement due to lock timeout`
  after 5.06 s, and the migration rolled back whole.
- **Deploy prerequisites.** Production's Postgres must ship `unaccent` **and** `btree_gin`
  (both are `trusted` in `postgres:18`, run). The migrating role must own the database or hold
  `CREATE` on schema `public`: a non-owner with only database `CREATE` gets the trusted
  extensions and then fails on the function, and the migration rolls back (run, Task 1
  review). If `unaccent` already lives in another schema, as some managed providers put it,
  `IF NOT EXISTS` skips it and the function fails to resolve `public.unaccent`: loud at migrate
  time.
- **Deploy prerequisite: reconcile the projection after the cutover, and after any rollback
  across V25.** A build from before V25 does not map `text_search`, so while it serves next to
  the new one (Kamal's blue/green, ADR-0007 and ADR-0011) or after a rollback (V25 stays
  applied), an entry it submits has no projection and an entry it erases **keeps its words in
  `text_search` and `search_vector`** (final review I1, run on a scratch database; the fix
  round's lock probe left exactly that row). Once only the new build serves, run
  `UPDATE entries SET text_search = text WHERE text_search IS DISTINCT FROM text`. It is
  idempotent, empties the stale projections of erased rows and fills the rows the old build
  wrote; it holds only while the projection is the text (until Phase 5). No production
  exists yet (README), so this is a documented step, not a V26. The search no longer depends
  on it to hide an erased entry: `matchingQuery` asks `LIVE_ROW` (decision 5), and the gate
  drops such a day as well; the step is what empties the words from the row.
- **Deploy prerequisite: the boot-time budget.** V25 runs as the new container boots, under
  Kamal's health-checked cutover: about 8 s per 200k rows plus up to 5 s of `lock_timeout`.
  That must fit Kamal's deploy and readiness timeouts, and the migrating role must have no
  `statement_timeout`, or one longer than the migration; otherwise the container is killed or
  the statement cancelled, the transaction rolls back (safe), and every retry fails the same
  way. Nothing comes near it at today's size.
- **The logger pins and what they give up.** Fifteen pins in `application.yml`; the five search
  pins, `ExceptionHandlerExceptionResolver` and `Http11Processor` are this slice's. They cost
  the request-line debugging of every route: Spring Security's "Securing …" lines and filter
  walk, the `DispatcherServlet`'s "GET …" and "Completed 200" lines, the resolved-exception
  lines, and Tomcat's report of a malformed request line. What `logging.level.web=DEBUG` used to
  show in an incident is gone. The alternative, if wanted back, is a logback `TurboFilter` or
  converter that strips query strings from those loggers. A pin holds against an ancestor's
  level only: setting one of those loggers by its exact name brings its lines back (with the
  `DispatcherServlet` at TRACE by name, the filter still answers an undecodable `q` with a 400
  and nothing logs it: run).
- **Outside the application, `q` can still be recorded**: Postgres's server log (bind
  parameters on an error or a slow statement) and any reverse proxy's access log. Both are
  deploy configuration, and entry text is held to the same rule there.
- **`scripts/moyi` passes every URL in a file**, so an invite code is no longer an argument of
  curl either.
- **The CLI, Bruno and the state map** have both routes; the state map has two cards and
  eighteen rows (nine per route), and `scripts/state-map-check --strict` is clean.

## Owed

- **The weekly on-this-day push and its opt-in**, per member and private (Q-O2; Phase 4,
  FR-081, FR-082).
- **Placement of on-this-day on a screen** (default 6: there is no frame).
- **`states.md`'s on-this-day row and the search snippet note**, which live outside this
  repository (`Gratitude/.claude/skills/moyi-design-system/references/states.md`).
- **The guard's older copies** (decision 21), with their state-map `codeRef`s.
- **Phase 5's lossy projection**: `searchProjectionOf` is the one function to change. The only
  guard that the vector reads `text_search` and not `text` is `V25BackfillTest`, on a hand-made
  row (the Task 1 review: generating the vector from `text` left `SearchProjectionTest` green);
  Phase 5 must add a test where `text_search ≠ text` at the request level.
- **Regenerating the vectors after an `unaccent` rules change** or a change of the database's
  `LC_CTYPE`: `UPDATE entries SET text_search = text_search`, with the backfill's row locks.
- **Emoji are not searchable.** Doc 14:78 asks that they round-trip "through … the search
  index": they round-trip through the column, and match nothing.
- **CJK text is indexed as unsegmented runs** under `'simple'`, so a word inside a run is not
  found.
- **The corpus**: doc 06, doc 07 and the spec amendments carried into it, and ADR-0035 to
  ADR-0039 copied into `Gratitude/documents/adr/` (c6-corpus X3).
- **The `400 MALFORMED_REQUEST` for an undecodable query parameter is declared on search
  only.** Every route answers it; the contract says so where a client is most likely to send
  such bytes (final review m1). The API-wide declaration is owed with the `required` question
  below.
- **Response schemas declare no `required` fields**, contract-wide, so the generated client
  treats every response field as optional. One owner question for all responses, not a patch
  to these two (Task 3 review).

## Questions that are the owner's

The four rulings are his. Then one line per controller default: what was decided, the
alternative, and what turning it would cost.

1. *No snippet.* Alternative: a server `ts_headline`; it works only on plaintext, so it
   vanishes or needs a second plaintext copy in Phase 5 (H-S8).
2. *Days, newest first, no total.* Alternatives: entries, or relevance order. Entries need a
   second renderer and the client to regroup them into the drawn day cards; relevance needs a
   rank cursor that is not stable, and a total says how often a word was written.
3. *Search on an ended bond, by the leaver too.* Alternative: refuse it. The archive is
   readable there, so search would hide what the feed shows, and a blocked member could compare
   the two.
4. *Every prior year, unpaginated.* Alternative: one year ago only (doc 02:135). The client can
   cut the list to one, and the API's own wording is "prior years".
5. *No days of tombstones.* Alternative: resurface them as the archive lists them, a memory of
   "you wrote something and took it back" a year later.
6. *Placement a design gap.* Alternative: decide Today or Archive now; there is no frame to
   build to.
7. *29 February only on 29 February; the bond's today.* Alternatives: show 29 February on 28
   February in other years (a memory three years in four, at the cost of a day shown on a date
   it was not), or the member's local date (two members, two answers).
8. *The application writes `text_search`; the vector is generated from it; GIN, measured; every
   erasure nulls it; V25 backfills.* Alternative: generate the vector from `text`; it indexes
   ciphertext in Phase 5. Or no backfill; existing entries unsearchable.
9. *`plainto_tsquery`, `q` 1–100 graphemes, never echoed or logged.* Alternative:
   `websearch_to_tsquery` (quotes, `or`, `-`), which offers syntax nobody asked for; a wider
   `q`; logging `q` for diagnosis, which logs what people look for in each other's words.
10. *`private, no-store`, cross-tenant fixtures.* Built as the API's default (`no-cache,
    no-store, max-age=0, must-revalidate`), which also forbids storing (decision 17); turning it
    to the letter costs one header per route and a case in `CacheHeadersTest`.

One question the owner's own ruling of 2026-10-21 opens:

11. **Does `q` follow the entry when ADR-0031's rulings are built?** Ruling 1 counts an entry's
    500 as typed (grapheme clusters of the text as sent, no NFKC); ruling 2 refuses an entry
    of zero-width characters only. `q` is counted on the NFKC form today, as an entry is
    (decision 9). The likely answer is yes, in the same pull request, so that a client counts
    both one way: `SearchTerms` would count the raw text and refuse a zero-width-only `q` as
    blank (`422`). Leaving it costs a client two counting rules for the same app.

Then the plan's decisions most worth his eye, one line each:

- **Decision 2, two guards.** Each hides the other's removal except over a long history; both
  stay because the SQL decides what `LIMIT` cuts and the gate decides what is shown.
- **Decision 9, the octet cap.** 1,024 bytes as well as 100 graphemes: 41 family emoji are 100
  graphemes and 1,025 bytes, and are refused.
- **Decision 11, `q` twice.** Joined by a comma and both searched; not tested.
- **Decision 13, days past the byte bound left out**, oldest first, with no cursor; they stay in
  `/days`.
- **Decision 14, the deletion countdown is "ended" too.** A countdown resurfaces nothing; a
  cancelled one brings the memories back.
- **Decision 17, the header.** The default rather than `private, no-store`.
- **Decision 19, no bucket.** A search-as-you-type client would want one.
- **Decision 22, the pins.** Request-line debugging given up on every route.
- **Decision 23, CLAUDE.md amended.** A binding rule gains a named exception.
- **The deploy prerequisites** (Consequences): two extensions and `CREATE` on `public`, the
  reconciliation after cutover and after a rollback, and the boot-time budget.

## What the corpus now says that is false

For the owner to carry into the corpus repository, which is not edited here.

1. **Doc 06:172**, "Full-text over revealed entries": search lists days, by the archive's
   predicate (the caller's own unrevealed entries too, Q-S2), not entries.
2. **Spec §6.6**, "revealed entries in the caller's own Bond only": the same (amended in place).
3. **Spec §3.2**, `searchVector` on `Entry`: it is a column, not a field of the domain object
   (amended in place).
4. **Doc 07:154**: the generated column folds accents through `public.immutable_unaccent`,
   which doc 07 does not have.
5. **Doc 07:157 and :268**: the index is GIN on `(bond_id, search_vector)` through
   `btree_gin`, not on `search_vector` alone.
6. **ADR-0035:757**, "a withdrawal sets neither": every erasure, a withdrawal's included, now
   empties `text_search`.
7. **Doc 02:135**, "one year ago today": every prior year (default 4).
8. **Doc 03:106 and `states.md`:566**: "opt-in" reads as the surface's; it is the push's
   (Q-O2).
9. **Doc 14:78**: emoji round-trip through the column and match nothing in the index.

## Revisit when

- A bond's history passes a few thousand days: on-this-day walks the bond's days; search's GIN
  cost was measured at 500k entries with bonds of at most 1,460.
- **Phase 5** encrypts `text`: the projection, and a request-level guard that the vector reads
  `text_search` (Owed).
- **Phase 10** (FR-203, FR-204): voice transcripts and semantic search.
- A Postgres major upgrade, or a restore under another `LC_CTYPE`: the `unaccent` rules and the
  tokeniser; stored vectors keep the old folding until regenerated.
- A client highlights: it must fold accents the way the server does.
- Search as the user types: decision 19's bucket.

## How this was checked

"Run" means executed and seen by the party named. Nothing here was run by the owner. Every
run below is the named agent's report of its own work, except Task 4's, which are this
record's author's.

- **While the plan was written** (2026-10-21, the planner; a throwaway `postgres:18` container,
  as a non-superuser): the image ships `unaccent` and `btree_gin`, both `trusted`;
  `immutable_unaccent`'s `RETURN` body works in a generated column; `cafe`, `CAFÉ` and `acao`
  match and `caf` does not; an emoji is no lexeme; the no-lexeme notice carries no part of `q`;
  a 500,000-row probe in which GIN alone read the mid-frequency word's postings across all bonds
  (62,500) and the `btree_gin` composite 63.
- **Task 1** (2026-10-21, its implementer; `a6aaea2`, then the controller's `998dc4c`).
  `FlywayMigrationTest`, `SearchProjectionTest` and `V25BackfillTest` seen red, then green.
  Mutations, each restored: the backfill's erasure clause dropped → `V25BackfillTest`; the
  generated column without the wrapper → `V25BackfillTest`; decision 5's three (above);
  `EntryEntity.toString` printing `textSearch` → `EntityIdentityTest`. `./gradlew build` green,
  1,452 tests. Its review ran V25 on a populated database (the timings and sizes above), the
  wrapper's immunity to `search_path`, the extension as a non-owner, and two mutations (the
  insert path without the projection: red; the vector from `text`: only `V25BackfillTest` red).
  Critical 0, Important 0, Minor 4. The controller added `lock_timeout` and saw the `ALTER`
  cancelled after 5 s with the table held.
- **Task 2** (2026-10-21, its implementer, then a fix session; `630c783` to `7266ba5`).
  `SearchTermsTest`, `SearchTest` (red with 404 before the route existed), `SearchWithdrawalTest`,
  `GratitudeCrossTenantTest`, `OpenApiContractTest`, `CacheHeadersTest` and
  `SecretsNeverLoggedTest` each seen red for the stated reason first. Mutations in decisions 2, 3,
  7, 9, 17 and 20, and: `GetDays.read` taking the membership instead of `readerNow` → the race
  test and four `DaysFeedTest` tests. Four deviations, each ratified or justified by its review:
  D1 (`obrigada ação`, decision 7), D2 (`it!!`), D3 (the brief's probe word `wrote` is no lexeme
  inside `~wrote~`; `after` used), D4 (the exhaustive list of query parameters in
  `OpenApiContractTest`). The measurement (decision 7). Its review ran the full build (1,475
  tests) and reproduced the undecodable-`q` `ERROR` line (Important 1) and the `bond_days` Seq
  Scan (Important 2); the fix round's re-review found Tomcat's `Http11Processor` logging a refused
  request target at INFO (Important 1) and judged the filter's first position (before Security)
  to cost more than it bought. Fixes: the handler, the filter after Security (a `401` comes
  first, probed), the `Http11Processor` pin (a raw-socket probe red without it), multipart off
  (`MultipartRefusedTest` red before, `HTTP/1.1 415` after), the join (the measurement red
  without it), the state map's `MALFORMED_REQUEST` reason. Each pin removed in turn → red,
  naming it. `./gradlew build` green, 1,483 tests, 1 skipped (the measurement, gated by
  `MOYI_MEASURE`).
- **Task 3** (2026-10-22, its implementer; `bf2443a` to `50c269a`). `OnThisDayCandidatesTest`
  and `OnThisDayTest` seen to fail to compile, then pass; `GratitudeCrossTenantTest` red without
  the fixture. Mutations in decisions 13, 14, 15 and 16. Its review (Critical 0, Important 1,
  Minor 4) mutated four more (the partner's bookmark read without `member_id`, the leaver given
  memories, the newest-first order reversed, the bond clause neutralised) and showed that
  `SecretsNeverLoggedTest`'s on-this-day probe answered `{"items":[]}` and so could not see a
  response `toString` that printed words: with `OnThisDayResponse.toString` printing the text it
  stayed green. Fixed: the probe now moves the bond's days back four years (so a run on 29
  February still lands on 29 February) and asserts the body holds both entries' words; the same
  `toString` mutation then turned it red (`RequestResponseBodyMethodProcessor TRACE: entry
  text`). Also fixed: the headers compared across every ending; `todayOf` in one place; the
  three-day byte-bound test. `./gradlew build` green, 1,502 tests, 2 skipped (the measurements).
- **Task 4** (2026-10-22, this record's author).
  - **The streak at the bond's midnight** (`e1ac2be`): `StreakEndpointTest` 19 of 19; `todayOf`
    → UTC turned only the new test red (decision 16).
  - **The smoke run.** The compose Postgres could not bind 5432, which another project's
    container held, so it was started on 55432 through a compose override, and the jar (built
    from `50c269a`'s main code; `e1ac2be` changed only a test) was booted by hand exactly as
    `scripts/smoke.sh` boots it, on its own database `moyi_c6b_smoke`, and the script run with
    `--attach`. **902 passed, 0 failed, 0 skipped, 23 sections.** The new section's 49 probes
    all passed. Two predictions confirmed: `q=zq%00zq` reached the application and was its
    `422` on `q`, not a `400` from Tomcat; the hand-made `bond_days` and `entries` rows of last
    year satisfied V12's checks, so the on-this-day probes ran rather than skipped. The
    application's log held none of the searched words and no `ERROR` line. After two probes
    were rewritten for the state-map checker (they had gone through a wrapper whose label the
    checker cannot see), the run was repeated on a fresh database: the same 902, 0, 0 in 23.
    Each database was dropped and seen to be gone.
  - **The CLI**, against the same jar: `register`, `verify`, `login`, `bond new`, `bond join`,
    `write`, `write -`, `today`, `days`, `day`, `search` (by argument, from stdin, blank → the
    `422` printed, with `&` in the words, with `--limit` and `--cursor`), and `on-this-day`
    (empty, then last year's day after a hand insert). `ps -ww` sampled during sixty searches: 9
    samples of curl's arguments, none with the words; the URL files read at the same time held
    them. **Bruno**: two requests written, not opened.
  - **The state map**: `scripts/state-map-check --strict` exit 0, "652 rows, 286 never run, 0
    pending, 29 contract gaps. Nothing wrong."; the checker's 154 tests pass.
  - **`./gradlew build`** at `eec45bb` with this record and the spec, README and log
    amendments in the tree (documentation only): green, **1,503 tests, 0 failures, 0 errors, 2
    skipped** (the two measurements, gated by `MOYI_MEASURE`), counted from the result files.
    `app` and `gratitude` ran; the other modules were up to date and their results reused.
  - **CLAUDE.md and AGENTS.md** (decision 23): PR #67, merged into `main` as `25fa9c1` on
    2026-10-22, rewrote the same rule (an invite code may reach a log below the default level,
    ADR-0035 question 10), and the plan's replacement text was written against the sentence
    before it. The branch was rebased onto `25fa9c1` and the amendment applied to `main`'s
    wording, keeping #67's invite-code clause: "Entry text, search terms, passwords and tokens
    never reach a log, an exception message, or a second column (one exception:
    `entries.text_search`, … ADR-0039); nor does an invite code, except …". AGENTS.md's rule 3
    carries the same words.
- **The two final reviews** (2026-10-22, at `c5b98b6`). Conformance: Critical 0, Important 1
  (ADR-0031's ruling on counting, decision 9), Minor 9, documentation only; oasdiff run.
  Concurrency: Critical 0, Important 1 (an older build's erasure keeps the projection), Minor 3
  (V25's deadlock, on-this-day's stale membership, the boot-time budget), each reproduced on a
  scratch database or by a probe; `./gradlew build --rerun-tasks` 1,503 tests, 0 failed, 2
  skipped; the smoke 901, 0 and 1 skipped (a reactions timing check) in 23 sections; the
  measurement under 1 ms with no sequential scan.
- **The fix round** (2026-10-22, its implementer; `c0cb14e` to the head). Each code change seen
  red first and its mutation red, restored: `LIVE_ROW` (decision 5), V25's lock (Consequences),
  the fresh membership (decision 14), the 400 and the mirrored limits (decision 12).
  `./gradlew build --rerun-tasks` at `569b420`: green, 217 tasks executed, **1,507 tests, 0
  failures, 0 errors, 2 skipped** (the measurements), counted from all 175 result files.
  `MOYI_MEASURE=1`: search at 100k and 500k and on-this-day at both, every statement under
  1 ms (at most 0.805 ms), no `Seq Scan`, no index scan wider than the bond's 1,460; the
  shapes those of the final review's run. **The smoke**, on a fresh database
  `moyi_c6b_fixsmoke` in the compose Postgres on 55432, the jar built from `569b420` booted by
  hand as `scripts/smoke.sh` boots it and the script run with `--attach`: **902 passed, 0
  failed, 0 skipped, 23 sections**, the search and on-this-day section 49; the application's
  log held no `ERROR` line, no `q=` and none of the searched words. The database was dropped
  and seen gone, the jar stopped, Postgres stopped again. `scripts/state-map-check --strict`
  exit 0.
- **Argued, not run:** that `q` sent twice is joined by a comma (decision 11); that no
  Security filter reads a request parameter (the re-review ran every level at TRACE and saw
  nothing, which is evidence for today's filters only); that the backfill's cost grows linearly
  with the table.
- **Not run by anyone:** V25 against a shared or production database; NFR-004's p95 under
  concurrent load; the Bruno collection; CI's contract action (a local oasdiff v1.11.7 run by
  the conformance review and by the fix round agrees: decision 12).
