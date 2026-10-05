# Slice C2, the reveal — completion plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** Finish slice C2 on `feat/gratitude-reveal` from the checkpoint `09ea440` to a
draft pull request that a reviewer can trust: the defects three whole-branch reviews found
are fixed test-first, the requirements that are built but unpinned are pinned, and the
documents the project's rules require exist.

**Architecture:** Nothing is redesigned. C2's shape is already on the branch: the reveal is
`BondDay.revealWhenDue` persisted by `RevealDay.apply` under the day's row lock; `PATCH` and
`DELETE /entries/{entryId}` go through `ChangeEntry`; the joining day is reconciled by
`ReconcileJoiningDay`; events go to `outbox_events` through `common:events`. This plan
changes four behaviours, tightens four seams, adds tests, and writes the record.

**Tech stack:** Kotlin, Spring Boot 4.1 (Jackson 3), Postgres 18 via Testcontainers, JPA,
Bucket4j on Valkey, Kotest assertions, JUnit 5, springdoc.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` (§3, §4, §5.2–§5.5,
§6.1–§6.3, §8, §9, §12.4) and `adr/0031-the-bond-day-and-the-first-entry.md` (Owed, C2).

## Where the branch stands (measured 2026-10-05)

- `09ea440`, four commits ahead of `main @ 7fab874`, not pushed, no pull request.
- `./gradlew build --rerun-tasks`: 205 tasks executed, **873 tests, 0 failed** (gratitude
  184, bond 265, identity 179, common:security 83, common:web 64, app 39, breach-corpus 29,
  notification 18, common:core 7, common:testing 3, common:events 2).
- `scripts/smoke.sh` has **not** been run on this branch. V14 has been applied to no
  database but Testcontainers'.
- Three read-only reviews of `09ea440` (concurrency; privacy and contract; spec
  conformance): no blocker, no leak, no deadlock. Their findings are the tasks below.
- Codex's two mutation proofs (day lock removed; redaction removed) are its claims. Task 7
  repeats both.

## Global constraints

- Never commit to `main`; the owner merges. The pull request is opened as a **draft**.
- Conventional Commits, subject at most 88 characters; `core.hooksPath` is `.githooks`.
- Test first: write the test, watch it fail for the stated reason, then change the code.
- A concurrency test synchronises on a lock held or a statement blocked, never on a sleep.
- Entry text never reaches a log, an exception message or a second column.
- `contracts/openapi.json` is generated: run the command `OpenApiContractTest` prints.
- V14 is unmerged and must not be applied to the shared development database (`moyi`).
- "Verified" means executed and seen. Say what was run and what was only read.
- When the spec and the code disagree, the spec is amended in this pull request, dated,
  citing ADR-0032 — never a test bent to fit.

## Review focus

Conditions the spec implies, no test exercised at `09ea440`, most likely to hurt a couple:

1. A couple paired under C1 whose first shared day is still `SUSPENDED`, and whose first
   request after the deploy is a `PATCH` or a `POST` — the reveal must survive that request
   being refused. (Task 1)
2. A member back-filling a day from before their partner joined — it must stay private
   whether or not a row already existed. (Task 2)
3. A bond with `revealTimeLocal` set — both write before the time and the day must read
   `PENDING_REVEAL`, with both entries locked. (Task 5)
4. An author who deletes before the reveal and writes again — count, status and the
   partner's view at each step. (Task 5)
5. An unkeyed `PATCH` with a multi-megabyte body — bounded, not parsed. (Task 4)

---

### Task 1: the joining-day reconcile commits on its own

**Finding:** concurrency F2, spec B6. On a C1-era joining day (`SUSPENDED`, two entries) a
first `PATCH` reconciles, reveals, then throws `ENTRY_IMMUTABLE`, and the rollback takes the
reveal with it; a `POST` that ends `409 DAY_CLOSED` does the same. Spec §12.4: "the first
gratitude operation … reconciles".

**Files:** `service/ChangeEntry.kt`, `service/SubmitEntry.kt`,
`web/EntryChangesTest.kt`.

- [x] Test `a refused first PATCH still leaves an elapsed joining day revealed`: build the
      legacy state as `the first read after pairing…` does; `PATCH` Ada's entry as the first
      operation; expect `409 ENTRY_IMMUTABLE`; then, with no `GET`, assert
      `bond_days.status = 'REVEALED'`, both `entries.revealed_at` set, one `DayRevealed`.
      Run it: red on `SUSPENDED`.
- [x] Same for `DELETE` (expect `204`, day `REVEALED`) and for a `POST` onto that day
      (expect `409`, day `REVEALED`), and for a `POST` replay and a keyed-`PATCH` replay.
- [x] `ChangeEntry.change`: before the main transaction, read the entry, resolve the
      membership unlocked, check the author, then `joining.beforeRead(membership)`.
      `SubmitEntry.submit`: `access.membershipOf` then `joining.beforeRead` before
      `transactions.execute`. Keep the in-transaction `underBondLock` calls: they are what
      holds the lock order when the pre-step lost a race.
- [x] Green; commit `fix(gratitude): the joining-day reconcile commits before the write`.

### Task 2: a day from before the pairing opens `SUSPENDED`

**Finding:** spec B2. `SubmitEntry.openAndLock` picks the opening status from
`awaitingSecondMember` now. After pairing, an `intendedAt` on D−1 with no row opens `OPEN`
and two back-fills reveal it; with a row it is `SUSPENDED` and private. Spec §12.4: earlier
days "remain private and excluded".

**Files:** `service/SubmitEntry.kt`, `domain/BondDay.kt` (only if the rule belongs there),
`web/EntryChangesTest.kt` or a new `web/JoiningDayTest.kt`.

- [x] Test `a day from before the pairing stays private, row or no row`: Cara creates on
      D−1; Eve accepts on D; both post with `intendedAt` on D−1. Assert D−1 is `SUSPENDED`,
      no `revealed_at`, no `DayRevealed`, each reads only their own entry. Second case: Cara
      wrote on D−1 while pending. Red on the first case (`REVEALED`).
- [x] `openAndLock`: open `SUSPENDED` when `awaitingSecondMember`, or when `activeSince` is
      not before `window.endsAt`.
- [x] Same file, the positive cases the review found unpinned — `the second member's first
      entry on the joining day resumes it and reveals` (no hand `UPDATE`); `pairing on a day
      one wrote on makes it PARTIAL, and an empty one OPEN`. Mutation: remove the
      `underBondLock` call before `openAndLock`; the first must go red.
- [x] Green; commit `fix(gratitude): a day before the pairing opens SUSPENDED, row or not`.

### Task 3: the reveal reads entries fresh

**Finding:** concurrency F1 (latent). `RevealDay.apply` reads through `findForDay`, which
returns a managed pre-lock instance; `EntryStore.update` then writes every column from it.

**Files:** `infra/database/EntryStore.kt`, `service/RevealDay.kt`, a new
`infra/database/RevealFreshReadTest.kt`.

- [x] Test: in one transaction `entries.find(id)`; from a second connection commit
      `UPDATE entries SET text = 'new'`; lock the day; `reveal.apply`; assert the row's text
      is `'new'` and `revealed_at` is set. Red.
- [x] `EntryStore.findForDayFresh` (refresh each managed entity); `RevealDay.apply` uses it.
- [x] Test for the unpinned guard: `EntryStore.update` never clears or moves `revealed_at`.
- [x] Green; commit `fix(gratitude): the reveal re-reads each entry under the day lock`.

### Task 4: one answer per rule on the entry routes

**Findings:** privacy S1–S6, N3/B7, N5; spec B9.

**Files:** `service/GratitudeErrors.kt`, `service/ChangeEntry.kt`, `service/SubmitEntry.kt`,
`web/EntryChangesController.kt`, `web/EntriesController.kt`, `domain/Entry.kt`,
`common/web/.../idempotency/IdempotencyInterceptor.kt` (+ its filter and test),
`contracts/.../OpenApiConfiguration.kt`, `app/build.gradle.kts`,
`app/src/test/.../FlywayMigrationTest.kt`, `OpenApiContractTest.kt`.

- [x] Extend `entry routes hide another author…`: add a partner who has left, a partner on
      an archived bond, the keyed `PATCH`, and compare headers apart from the rate-limit
      counters. Add `an author who has left gets 409, never 404`. Mutation: swap the author
      check and the archived check in `ChangeEntry`; expect red.
- [x] `EntryNotFoundException` replaces the eight copies of the literal; one
      `ChangeEntry.authorOf(userId, entryId, lock)` replaces the three find → membership →
      author sequences. `Entry.isErased` is used by `canBeReadBy` and `reveal`.
- [x] Test `an unkeyed PATCH over 1 MiB is 413`: red. The interceptor and its filter bound
      the body for every `@Idempotent` handler; only preparing the key depends on the
      header. `IdempotencyInterceptor.requestOrNull(http)` replaces the controller's own
      header read. `common:web` test: `an optional key is skipped when absent, enforced when
      present, refused when malformed`.
- [x] Contract: `PatchEntryRequest.text` is `string`, not nullable; the optional-key
      operations are a named set, not a string comparison; the `submitEntry` description
      regains "missing or malformed". Assertions in `OpenApiContractTest`. Regenerate.
- [x] `ENTRY_IMMUTABLE`'s detail becomes "This entry can no longer be edited." (true for a
      revealed and for an erased entry).
- [x] `FlywayMigrationTest` requires V1–V14 and asserts `outbox_deliveries`' key and its
      partial index; `app` names `projects.common.events`.
- [x] Green; commit per bullet group (`refactor`, `fix(web)`, `fix(contracts)`, `test`).

### Task 5: pin what is built

**Findings:** spec table rows 2, 3, 5, 6, 12, 13, 17, 21, 33 (tests owed 1–9, 14–16).

**Files:** `domain/RevealTransitionTest.kt`, `domain/BondDayTest.kt`,
`web/RevealGateTest.kt`, `web/EntryChangesTest.kt`, `web/EntriesEndpointTest.kt`.

- [x] `a timed reveal holds both entries in PENDING_REVEAL until the bond's time` (HTTP;
      status, no stamps, no event, each partner entry exactly the locked shape).
- [x] `a reveal time already passed reveals at once` (day and both entries share one
      `revealed_at`; one `DayRevealed` at that instant).
- [x] `once both have written each reads the other's words` (no hand `UPDATE`). Mutation:
      make `RevealDay` skip the entry update.
- [x] `revealWhenDue` across a DST gap (Europe/London 2026-03-29 01:30) and an overlap
      (2026-10-25 01:30), and on a westward-extended day.
- [x] `withoutEntry`, parameterised: `PARTIAL(1)→OPEN(0)`, `PENDING_REVEAL(2)→PARTIAL(1)`,
      `SUSPENDED(n)→SUSPENDED(n−1)`; settled days return the same instance.
- [x] `deleting on a PENDING_REVEAL day returns it to PARTIAL and a rewrite follows the
      reveal rule`; `after a post-reveal delete the author cannot write that day again`
      (`409 DAY_CLOSED`); `a deleted entry is the wide tombstone to both after reveal`;
      `a pre-reveal delete shows the partner exactly this` (pins today's shape — see the
      owner's questions); `PATCH is allowed while PENDING_REVEAL`.
- [x] `a refused submission leaves no outbox row` (BR-2 `409`, media `422`).
- [x] `a constraint failure during the reveal never exposes either member's words` (a probe
      CHECK the reveal `UPDATE` violates; `500`; neither canary in body or output).
- [x] `an ended bond's entries cannot be edited or deleted` — pins `409 BOND_ARCHIVED` as
      built, for the member who left and the one who stayed.
- [x] Commit `test(gratitude): pin the timed reveal, deletion and the outbox's atomicity`.

### Task 6: the smoke run, on a database of its own

**Files:** `scripts/smoke.sh`.

- [x] `MOYI_DB` (default `moyi`): the script creates the database if absent, boots the jar
      with `--spring.datasource.url` pointing at it, and uses it in every `psql` call.
      Header comment says why: an unmerged migration is smoke-tested without touching the
      shared database.
- [x] Add a log grep for the edited text; a probe that a timed bond reads `PENDING_REVEAL`.
- [x] `./gradlew :app:bootJar`, then `MOYI_DB=moyi_c2_smoke PORT=18090 scripts/smoke.sh
      --no-build`. Record probes passed/failed. Fix what it finds, test first.
- [x] Confirm `moyi`'s `flyway_schema_history` still ends at 13. Drop `moyi_c2_smoke`.
- [x] Commit `chore(smoke): a database of its own, and the reveal on the wire`.

### Task 7: prove the guards, then the record

**Files:** `adr/0032-the-reveal-and-the-first-events.md` (new), `adr/0031-…md`, the spec,
`docs/learning-log.md`, `README.md`, `CLAUDE.md`, the KDoc the spec review lists in its
section C, `docs/handover/2026-10-04-c2-reveal.md` (removed from the branch; its content
moves to the untracked `.claude/HANDOVER.md`).

- [ ] Mutations, each run and restored: remove `days.lockRow` in `BondDayStore.lockAndFind`
      (`a second submission waits on the day lock…` red); rethrow raw in `ChangeEntry` and in
      `SubmitEntry` (the two canary tests red); remove the sort in `GetToday` (`delete frees
      the live slot…` red); remove `?:` in `EntryStore.update` (Task 3's test red).
- [ ] ADR-0032: the reveal under the day lock and why the bond lock makes the simultaneous
      test green without it (the owner-approved two-test split); the timed-reveal rule; the
      joining-day reconcile — who triggers it, that a read may now take the bond lock and
      extend a day, that it runs on an ended bond, its place in the lock order; `PATCH` and
      `DELETE` semantics (`404` for a non-author, soft erase, `409` on an ended bond,
      optional key on `PATCH`, none on `DELETE`); the bucket (20 a day, every attempt
      counts, replays included); the event shapes and why the payload is UUID-only; the
      redaction helper; `GetToday`'s selection rule. **Owed, C3:** the `PENDING_REVEAL`
      sweep and its need for `revealTimeLocal` without a caller; a `closedAt` guard on
      `resumeJoiningDay`; the closer's one-day-at-a-time rule now protects three paths.
      **Owed, C5:** no event for an edit or an erasure; `last_error` may hold a message.
- [ ] ADR-0031: the six C2 Owed bullets marked discharged, citing ADR-0032; decision 17
      ("a read never takes the bond lock") amended.
- [ ] Spec: dated amendments at §3.1, §5.2, §5.4, §5.5, §6.3, §9, §12.4.
- [ ] The KDoc sweep (thirty stale statements, listed by the review with file and line).
- [ ] `README.md` and `CLAUDE.md` name `common:events`. Learning-log entry.
- [ ] Commit `docs: ADR-0032, and the spec says what C2 built`.

### Task 8: the pull request

- [ ] `./gradlew build --rerun-tasks`; record the counts.
- [ ] One fresh whole-branch review of `09ea440..HEAD`; fix what it finds.
- [ ] Push; open a **draft** pull request against `main` from the template, labelled
      `breaking-api-change` (`ENTRY_IMMUTABLE` is a new `ErrorCode`). Body: what and why, the
      concept brief, the Figma alignment table, the ten questions answered, the checklist.
- [ ] Copy ADR-0032 and the amended ADR-0031 to `../documents/adr/`; correct doc 06
      (`entries:create`) and doc 07 (`outbox_deliveries`) on the corpus branch.
- [ ] Update `.claude/HANDOVER.md`.

## Questions that are the owner's (built as stated, pinned by a test, easy to turn)

1. **Deleting your own entry after the bond has ended** is `409 BOND_ARCHIVED` (ADR-0028:
   archived is read-only). Until C5 there is then no way to withdraw words an ex-partner
   can still read.
2. **A pre-reveal delete is visible to the partner**: `partnerEntry` becomes
   `{authorMemberId, status: REMOVED}` and the day steps back to `OPEN`. FR-064 forbids
   disclosing activity; ADR-0031 decision 10 ruled the shape before it was reachable.
3. **A replay spends a token** of the 20-a-day bucket, so the twenty-first retry of one key
   in a day is `429`, not the replay.
