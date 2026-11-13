# Admin console A5 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task is sized for one implementer pass and one independent review pass.

**Goal:** From an open report about words, an `ADMIN` reads that one entry's text and date, after a typed justification and a TOTP code proved in the same request, with an `ENTRY_TEXT_READ` audit row and a `WORDS_READ` report-log row committed before the words are rendered, and nothing of the words anywhere else.

**Architecture:** One admin-only port, `gratitude.api.ReportedEntryText`, implemented in `gratitude.infra` with `JdbcClient` (report `FOR SHARE`, then entry `FOR SHARE`, then the bond's withdrawn members, then the log row), `MANDATORY` so it joins the console's transaction. `admin.service.ContentAccess` (`ADMIN`) takes a step-up proof that only a fresh code can produce, calls the port and writes the audit row. One POST on A2's `ReportsController` renders its own template with `no-store`. V63 widens `entry_report_log`'s action check, V64 widens `audit_log`'s; ADR-0064 records the export deviation.

**Tech Stack:** as A2: Kotlin 2.4, Spring Boot 4.1.2, Spring Security 7.1.2, Thymeleaf, Postgres 18, Flyway, JUnit 6 + kotest + MockMvc + Testcontainers. No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-12-admin-console-a5-design.md` (owner-approved 2026-11-13, PR #190). Read it in full, and A2's spec §6 and §10, before Task 1. Where this plan and the spec disagree, the spec wins: stop and report.

## Global Constraints

- Actor string for `entry_report_log`: `ModerationActor.of(accountId)` (`"admin:" + id`), as A2.
- The service method: `@PreAuthorize("hasRole('ADMIN')")`, `@Transactional` (READ COMMITTED). The port: `@Transactional(propagation = MANDATORY)`. The audit row through A1's `AuditTrail.record` (`MANDATORY`).
- Justification: A2's `ModerationNote` rules (1–400 code points after trim, no NUL), validated by `ModerationNote`'s own check, never restated.
- **The step-up for a read is never the session window.** A blank code is "code needed", whatever `admin.secondFactorAt` says, and a proved code does **not** set or extend `admin.secondFactorAt` (spec §5 step 3).
- Never in a log, exception message, metric tag, flash, redirect, audit column, `entry_report_log` column or `toString`: the entry's text or the justification (the justification goes only in `audit_log.justification`).
- **Lock order of the read: report (`FOR SHARE`), then entry (`FOR SHARE`). No bond lock.** Withdrawn authors are read with `BondAccess.closingViewOf(bondId)` (no lock), after the entry lock. Reason: the reporter's own erasure takes the bond lock and then `UPDATE entry_reports SET detail = NULL` (`EntryReports.kt:63`); a read holding the report and waiting on the bond would close a cycle. A withdrawal commits before its erasure; a read that runs between them sees it in `closingViewOf` (it reads committed state per statement), which is what `EntryReading` needs.
- Migrations: **V63** in `modules/gratitude/src/main/resources/db/migration/`, **V64** in `modules/admin/src/main/resources/db/migration/`. Re-check `main` before Task 1; if a number has landed, take the next free and say so in the PR.
- No admin route in `contracts/openapi.json`; `OpenApiContractTest` passes unchanged.
- Every concurrency test waits on `pg_locks`/`pg_stat_activity` with a deadline and **fails** on timeout; never a sleep.
- Commits: Conventional Commits, subject ≤ 88 characters; `git config core.hooksPath .githooks` in the worktree.
- Environment: another worktree may serve a phone on 8080/5432/6379/9000. Never `./gradlew --stop`, never kill java/gradle, never touch the default compose project. Smoke runs in its own compose project and ports.

## Review Focus

1. **A second way to the words** (spec anti-goal 1): the text in any response but the POST's, in a redirect, a flash, the GET model, the log, the metric, an audit or report-log column. Pinned by Task 4's marker test and log capture; the reviewer reads every template the controller can render.
2. **The window reused** as a step-up: a blank code with a fresh `admin.secondFactorAt` must ask for the code and read nothing; a proved code must leave `secondFactorAt` untouched. Pinned in Task 3 (service: the proof type) and Task 4 (HTTP).
3. **A media-only entry** (text null, status not `DELETED`, author not withdrawn): the spec's table would call it `ERASED`, which tells the admin something false. This plan adds `NO_WORDS` (Task 1); the spec's §5 table is amended in the same PR. Tested in Task 1.
4. **Deadlock with an erasure**: the read against (a) the author's entry erasure and (b) the reporter's erasure of their report's detail, both orders, on locks; neither may deadlock (Task 1).
5. **A role refusal that reads**: a `MODERATOR`/`SUPPORT` hand-made POST with a valid code and justification reads nothing, writes no `ENTRY_TEXT_READ` and no `WORDS_READ`, and does not spend the code (Task 4).

---

### Task 1: V63, the rule, and the port `ReportedEntryText`

**Files:**
- Create: `modules/gratitude/src/main/resources/db/migration/V63__gratitude_report_words_read.sql` (drop and re-add `entry_report_log_action_check` with `WORDS_READ`; nothing else)
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Report.kt` (the rule, beside `ReportReason`)
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/api/ReportedEntryText.kt` (interface, `ReportedEntryRead`, `ReportedWords`)
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/infra/database/ReportedEntryTextStore.kt` (internal)
- Modify: `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt` (add the four types (`ReportedEntryText`, `ReportedEntryRead`, `ReportedWords`, `WordsRefusal`) to `A2_MODERATION_TYPES`, the list the spec calls `MODERATED_APIS`; add the rule that only `ContentAccess` in `admin` depends on `ReportedEntryText`)
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/WordsReadableTest.kt`, `.../infra/ReportedEntryTextTest.kt`, `.../infra/ReportedEntryTextRaceTest.kt`, `.../infra/V63MigrationTest.kt`

**Interfaces (produces):**
- `internal fun ReportReason.wordsReadable(target: String, status: String): WordsReadable` in `Report.kt`, with `enum class WordsReadable { YES, NOT_OPEN, MEMBER_REPORT, IMAGE_REASON }`: `YES` iff status `OPEN`, target `ENTRY`, reason in `HARASSMENT`, `VIOLENCE_OR_SELF_HARM`, `OTHER`. Express "image reason" as `!mediaReviewable` only if that set is exactly `NON_CONSENSUAL` + `MINOR_SEXUAL_CONTENT`; otherwise list the three readable reasons explicitly. The test enumerates every `ReportReason` × target × status, so a reason added later fails it until classified.
- `interface ReportedEntryText { fun read(reportId: UUID, expectedReason: String, actor: String): ReportedEntryRead; fun whyNot(reportId: UUID): WordsRefusal? }`, both `@Transactional(propagation = MANDATORY)`. `whyNot` reads the report unlocked, writes nothing, and answers null when the rule says `YES` (the GET's form), else `enum class WordsRefusal { NOT_OPEN, MEMBER_REPORT, IMAGE_REASON }` (in `gratitude.api`, mapped from the domain's `WordsReadable`; an unknown id is `NOT_OPEN`). The page asks the same rule the read applies, never a copy.
- `sealed interface ReportedEntryRead { val entryId: UUID? }` with `Shown(entryId: UUID, date: LocalDate, words: ReportedWords)`, `Erased(entryId)`, `NoWords(entryId)`, `NotOpen(entryId: UUID?)`, `Stale(entryId)`, `NotAboutWords(entryId: UUID?, reason: String, imageReason: Boolean)`. `entryId` is null for a `MEMBER` report and for an unknown report id (which answers `NotOpen(null)`).
- `class ReportedWords(val value: String)` — not a data class; `toString() = "ReportedWords(redacted)"`; `equals`/`hashCode` by identity.

- [ ] **Step 1: Write the failing tests.**
  - `V63MigrationTest` (as `V61MigrationTest`): after V63 a `WORDS_READ` row inserts; `'WORDS_RED'` fails the check; the five old actions still insert.
  - `WordsReadableTest`: the full enumeration above (and `whyNot` agrees with `read` for each), with `NON_CONSENSUAL`/`MINOR_SEXUAL_CONTENT` → `IMAGE_REASON`, `MEMBER` → `MEMBER_REPORT`, status `DISMISSED`/`UPHELD` → `NOT_OPEN`.
  - `ReportedEntryTextTest`, every report made through `ReportEntry` / `ReportMember` (not inserted rows), inside a test transaction:
    - `shown`: a revealed entry reported for `HARASSMENT` → `Shown` with the entry's text and its `bond_days.date`; one `entry_report_log` row `WORDS_READ`, actor `admin:<id>`, note null.
    - `erased`: the author deleted it → `Erased`, one `WORDS_READ` row.
    - `withdrawn author, text not yet erased` (withdraw through bond's service, do not run the outbox) → `Erased`, and the row's `text` is still non-null in the table.
    - `no words`: a media-only entry → `NoWords`, one `WORDS_READ` row.
    - `not open` (dismissed first), `stale` (expected `OTHER`, actual `HARASSMENT`), `member report`, `image reason` (`NON_CONSENSUAL`), `unknown id` → the matching variant and **no** `entry_report_log` row.
    - `image reason, reclassified, then read` → `Shown`.
    - `ReportedWords.toString()` does not contain the marker.
    - Called outside a transaction → `IllegalTransactionStateException`.
  - `ReportedEntryTextRaceTest` (two connections, `pg_locks` deadline): (1) a read holding its share lock makes `entry_report_dismiss` wait; on read commit the dismiss proceeds. (2) A dismiss holding `FOR UPDATE` makes the read wait; it then answers `NotOpen`. (3) The author's entry erasure (gratitude's erase path) holding the entry lock makes the read wait; the read then answers `Erased`. (4) The reporter's detail erasure (`EntryReports` line 63 path) and a read, both orders: both complete, no `40P01`.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:gratitude:test --tests "*ReportedEntryText*" --tests "*WordsReadable*" --tests "*V63Migration*"` → compile errors / FAIL.
- [ ] **Step 3: Write V63, the rule, the port and the store.** The store: (1) `SELECT reason, status, target, entry_id, bond_id FROM entry_reports WHERE id = :id FOR SHARE` (no status filter, so `NotOpen` can carry `entryId`); none → `NotOpen(null)`. (2) `status != 'OPEN'` → `NotOpen`; `reason != expectedReason` → `Stale`; `wordsReadable` not `YES` → `NotAboutWords`. (3) `SELECT e.text, e.status, e.author_member_id, d.date FROM entries e JOIN bond_days d ON d.id = e.bond_day_id WHERE e.id = :entry FOR SHARE OF e`. (4) `Erased` if status `DELETED` or the author is in `bondAccess.closingViewOf(bondId)?.withdrawnMemberIds` (a null view, a bond gone, is `Erased`); else `NoWords` if text null; else `Shown`. (5) For `Shown`, `Erased`, `NoWords` only: insert the `WORDS_READ` row (`ids.timeOrdered()`, `clock.instant()`).
- [ ] **Step 4: Run them and see them pass.** Same command → PASS. Then `./gradlew :app:test --tests "com.moyi.app.ArchitectureTest"` → PASS.
- [ ] **Step 5: Mutation checks** (revert each after): `FOR SHARE` removed from the report query → race (1) fails at its deadline; the withdrawn check removed → the withdrawn test fails; `IMAGE_REASON` mapped to `YES` → `WordsReadableTest` fails.
- [ ] **Step 6: Amend the spec's §5 table** with `NO_WORDS` ("This entry has no words, only a photo or a voice note.", audited `SUCCEEDED`), and §6's note that the store reads withdrawn authors without a bond lock and why.
- [ ] **Step 7: Commit.** `feat(gratitude): read a reported entry's words for the console (V63)`

### Task 2: V64, `ENTRY_TEXT_READ`, and the export keeps it withheld

**Files:**
- Create: `modules/admin/src/main/resources/db/migration/V64__admin_entry_text_read.sql` (A2's pattern: drop and re-add `audit_log_action_check` with `ENTRY_TEXT_READ`)
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/domain/AuditAction.kt` (`ENTRY_TEXT_READ`)
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/domain/Disclosure.kt` (an explicit `action == ENTRY_TEXT_READ -> null` branch before the others, with a KDoc line naming ruling 2)
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/infra/V64MigrationTest.kt`, `AuditActionCheckTest` (unchanged code, must pass), `domain/DisclosureTest.kt`, `app/src/test/kotlin/com/moyi/app/admin/AdminAccessDisclosureTest.kt`

**Interfaces:** produces `AuditAction.ENTRY_TEXT_READ`. `AdminAccessRows` is **not** changed (spec §9).

- [ ] **Step 1: Write the failing tests.** `V64MigrationTest`: an `ENTRY_TEXT_READ` row inserts; every older action still does. `DisclosureTest`: `ENTRY_TEXT_READ` is withheld for every outcome × target type × metadata the exhaustive table already walks, including `reportDriven = false`. `AdminAccessDisclosureTest`: an export of the author built after an `ENTRY_TEXT_READ` row (`SUCCEEDED`, `target_type = ENTRY`) has bytes equal to one built before it.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:admin:test --tests "*V64Migration*" --tests "*Disclosure*"` → FAIL.
- [ ] **Step 3: Add V64, the enum value and the branch.**
- [ ] **Step 4: Run them, and `AuditActionCheckTest`, and see them pass.**
- [ ] **Step 5: Mutation check:** delete the `Disclosure` branch and make `shows` return a disclosure for `ENTRY` targets → `DisclosureTest` fails. Revert.
- [ ] **Step 6: Commit.** `feat(admin): ENTRY_TEXT_READ in the audit log, withheld from the export (V64)`

### Task 3: `ContentAccess`, and a step-up proof that only a fresh code makes

**Files:**
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/ContentAccess.kt`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/FreshStepUp.kt`
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/web/ModerationPages.kt` (`SecondFactor.proveNow`)
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/service/ContentAccessServiceTest.kt`

**Interfaces:**
- Consumes: `ReportedEntryText.read` (Task 1), `AuditAction.ENTRY_TEXT_READ` (Task 2), `AuditTrail.record`, `ModerationActor.of`, `ModerationNote`.
- Produces:
  - `class FreshStepUp internal constructor(val adminId: UUID, val at: Instant)` in `admin.service`; its only constructor call outside tests is in `SecondFactor.proveNow`. A Konsist line in `ArchitectureTest`: no `FreshStepUp(` call in `admin.web` except `ModerationPages.kt`.
  - `SecondFactor.proveNow(adminId, code, from): Fresh` with `sealed interface Fresh { data object Missing; data object Refused; class Proved(val stepUp: FreshStepUp) }`. Blank code → `Missing` without reading the session. Else `signIn.stepUp(adminId, code.trim(), from)`: `Accepted` → `Proved`, **without** setting `SECOND_FACTOR_AT`; `Refused` → `Refused`.
  - `ContentAccess.readReportedEntry(reportId: UUID, expectedReason: String, justification: String, stepUp: FreshStepUp, adminId: UUID, from: ClientAddress?): EntryTextResult`, `@PreAuthorize("hasRole('ADMIN')")`, `@Transactional`. `EntryTextResult` = the port's result plus `outcome: EntryTextOutcome` (`SHOWN, ERASED, NO_WORDS, NOT_OPEN, STALE, NOT_ABOUT_WORDS`). Throws `InvalidNote` (A2's) before any read for a bad justification; throws `IllegalArgumentException` if `stepUp.adminId != adminId`.
  - The row: `ENTRY_TEXT_READ`, outcome `SUCCEEDED` for `SHOWN`/`ERASED`/`NO_WORDS`, `REFUSED` otherwise; target `ENTRY`/entry id, or `REPORT`/report id when `entryId` is null; `justification` = trimmed justification; `metadata = {"result": outcome.name, "reportId": id, "reportDriven": true}`.
  - `ContentAccess.whyNot(reportId: UUID): WordsRefusal?`, `@PreAuthorize("hasRole('MODERATOR')")`, `@Transactional(readOnly = true)`: the GET's answer, no audit row (A2's report page already writes `REPORT_READ`).
  - Metric `moyi.admin.content.read` tagged `outcome`; one INFO line `content read admin={} report={} outcome={}`.

- [ ] **Step 1: Write the failing tests** (`ContentAccessServiceTest`, a real database, A2's `ModerationConsoleServiceTest` setup):
  - `shown writes one ENTRY_TEXT_READ row`: target, `justification`, metadata exactly as above; and the `WORDS_READ` row exists.
  - each refusal → `REFUSED`, the right target, no `WORDS_READ`.
  - `invalid justification` (empty, 401 code points, NUL) → `InvalidNote`, no row of either kind, the port not called (a spy port).
  - `a MODERATOR` → `AccessDeniedException`, no row, port not called. `SUPPORT` the same.
  - `forced failure after the audit row` (a test `AuditTrail` decorator that throws after `record`) → neither row committed, and the `ADMIN_STEP_UP` row and spent `totp_last_step` from a `proveNow` run before the call **are** committed.
  - `proveNow`: blank code with `SECOND_FACTOR_AT` set a second ago → `Missing`; a valid code → `Proved`, and `SECOND_FACTOR_AT` unchanged (still unset when it was unset); the same code again → `Refused`.
  - The marker text of the entry is in no column of `audit_log` and no `entry_report_log` column; the log capture holds neither marker nor justification.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:admin:test --tests "*ContentAccess*"` → FAIL.
- [ ] **Step 3: Implement `FreshStepUp`, `proveNow` and `ContentAccess`.**
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Mutation checks:** remove `@PreAuthorize` → the MODERATOR test fails; make `proveNow` return `Proved` from a fresh window on a blank code → its test fails; give the port's implementation `REQUIRES_NEW` instead of `MANDATORY` → the forced-failure test fails (the `WORDS_READ` row survives the rollback). Revert each.
- [ ] **Step 6: Commit.** `feat(admin): ContentAccess reads a reported entry's words with a fresh code`

### Task 4: The page, the POST and the privacy proof

**Files:**
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/web/ReportsController.kt` (POST `/admin/reports/{id}/entry-text`; the GET model gains `canReadWords: Boolean` and `whyNot: String?`)
- Create: `modules/admin/src/main/resources/templates/admin/entry-text.html`
- Modify: `modules/admin/src/main/resources/templates/admin/report.html` (line 29's hint becomes the form for an `ADMIN` when `canReadWords`, the "an ADMIN can read them" hint for a `MODERATOR`, the `whyNot` line otherwise)
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/web/EntryTextControllerTest.kt`, `app/src/test/kotlin/com/moyi/app/admin/ModerationPrivacyTest.kt`

**Interfaces:** consumes `ContentAccess.readReportedEntry`, `SecondFactor.proveNow`, and `ContentAccess.whyNot` for the GET: `canReadWords = whyNot == null && the viewer is ADMIN`; `whyNot` maps to the copy below.

The POST: `@PreAuthorize("hasRole('ADMIN')")` on the method; malformed id → `notFound()`; `Missing` → the form, 200, code field shown, error `CODE_NEEDED`; `Refused` → the form, 401, `WRONG_CODE`; `InvalidNote` → the form, 422; `SHOWN` → `entry-text.html`, 200, model keys `words`, `date`, `reportId` only; `ERASED`/`NO_WORDS` → `entry-text.html` with the spec's sentence and no `words`; `NOT_OPEN`/`STALE` → A2's existing pages and messages; `NOT_ABOUT_WORDS` → the report page with `whyNot`. The response for every outcome sets `Cache-Control: no-store`, `Pragma: no-cache`, `Referrer-Policy: no-referrer`. Never a redirect, never a flash attribute.

Copy (exact): form button "Read the entry's words"; justification label "Why you need to read them"; `ERASED`: "Its author erased this entry. There are no words to read."; `NO_WORDS`: "This entry has no words, only a photo or a voice note."; image reason: "This report's reason is about images. If its detail is about words, reclassify it first."; member report: "This report is about a member, not an entry. There are no words to read."; `MODERATOR` hint: "The entry's words are not shown here. An admin can read them, with a reason and a code."

- [ ] **Step 1: Write the failing tests** (`EntryTextControllerTest`, MockMvc + the admin chain + CSRF):
  - Every row of the outcome list above: status, view, headers.
  - `MODERATOR` and `SUPPORT` POST with a valid code and justification → the admin 403 page, no `ENTRY_TEXT_READ`, no `WORDS_READ`, and the code still usable afterwards (prove it with one more step-up that is `Accepted`).
  - A fresh session window and a blank code → the code form, nothing read.
  - A code used for a block a moment before → 401, nothing read.
  - The GET for an `ADMIN` on a readable report shows the form and no words; for a `MODERATOR` the hint and no form.
  - `ModerationPrivacyTest`: a unique marker plus `<script>alert(1)</script><b>x</b>` as an entry's text. After a `SHOWN` read, the marker is in exactly one response body (escaped: `&lt;script&gt;`), and in no other page the console serves (home, queue, report GET before and after, history), no captured log line, no metric tag, no audit or report-log column.
- [ ] **Step 2: Run them and see them fail.** `./gradlew :modules:admin:test --tests "*EntryText*"` and `./gradlew :app:test --tests "*ModerationPrivacyTest*"` → FAIL.
- [ ] **Step 3: Implement the POST, the GET model change and the two templates** (`th:text` only; `entry-text.html` is a page of its own, not a fragment of `report.html`).
- [ ] **Step 4: Run them and see them pass.**
- [ ] **Step 5: Mutation checks:** remove the method-level `@PreAuthorize` *and* the service's → the role test fails; drop `no-store` → the header test fails; swap `th:text` for `th:utext` → the escaping test fails. Revert each.
- [ ] **Step 6: Commit.** `feat(admin): the page that reads a reported entry's words, POST only, no-store`

### Task 5: Script, runbooks, state map, ADR-0064, and the whole branch

**Files:**
- Modify: `scripts/moyi` (`reports show` prints the report's `WORDS_READ` lines from `entry_report_log` as `words read by <actor> at <at>`; the outcome list learns `WORDS_READ`)
- Create: `adr/0064-the-admin-console-a5-content-access.md` (ruling 2 as a departure from FR-112's "MUST", the reason, the condition, what is owed; rulings 1, 3, 4; the lock order and why no bond lock; `NO_WORDS`)
- Modify: `ops/admin/README.md` (the metric; reading words is `ADMIN` only), `ops/moderation/takedown.md` §3 (once the console is on, the words are read only there; until then the `psql` rule stands)
- Modify: `docs/state-map/data/` — A2 left no console rows; put one row per outcome in a new `admin.json` region, each with its test as evidence, and say so in the PR
- Modify: `docs/learning-log.md` if anything in Tasks 1–4 went wrong in a way worth keeping

- [ ] **Step 1:** Script change; run it against a local database with one report read through the console's port in a test fixture, and see the line. (If the script has a test harness, add the case there.)
- [ ] **Step 2:** ADR, runbooks, state map; `./gradlew :app:test --tests "*StateMap*"` (or the state-map check CI runs) → PASS.
- [ ] **Step 3:** `./gradlew build` → all green; record the counts.
- [ ] **Step 4:** `scripts/smoke.sh` in an isolated compose project → 0 failures; `POST /admin/reports/<id>/entry-text` on the API host answers what an unknown path answers.
- [ ] **Step 5:** Commit. `docs(admin): ADR-0064, runbooks and state map for A5 content access`
- [ ] **Step 6:** Open the PR (owner merges, or I merge under the 2026-11-11 merge authority after independent review + build + smoke + CI).
