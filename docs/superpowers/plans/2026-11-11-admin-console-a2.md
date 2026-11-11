# Admin console A2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task is sized for one implementer pass and one independent review pass.

**Goal:** A `MODERATOR` decides the report queue and the quarantine queue in the switched-off admin console, through the database functions `scripts/moyi` already calls, with each decision audited under the admin's id, irreversible ones behind a fresh second factor, and held-back media viewable only where the runbook allows.

**Revised twice on 2026-11-11** after an independent review and a re-review (both FIX FIRST): locks in the media port (Task 1), a block action, export-time disclosure with no `disclosed_to` column (Tasks 3, 7), lookup purposes, step-up enforced in the service; then the export path without a transaction, a disclosure default that never shows views, report-first share locks for the view, and bounded lock waits. Spec §16 lists every change.

**Architecture:** Two admin-only ports, `gratitude.api.ReportModeration` and `media.api.QuarantineModeration`, each implemented in its own module's `infra` by `JdbcClient` calls to the existing functions, each `MANDATORY` so it joins the console's transaction. `modules/admin` gains `ModerationConsole` (service, `@PreAuthorize("hasRole('MODERATOR')")`), three controllers, plain-form Thymeleaf pages, a step-up check, and `AdminAccessDisclosure` implementing `export.api.AdminAccessLog`. Migration V61 widens `audit_log`'s action `CHECK` (17 actions) and nothing else; disclosure is derived when the export is built.

**Tech Stack:** as A1: Kotlin 2.4, Spring Boot 4.1.2, Spring Security 7.1.2, Thymeleaf, Postgres 18, Flyway, JUnit 6 + kotest + MockMvc + Testcontainers. No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-11-admin-console-a2-design.md`. Read it in full, and A1's spec §6–§8, before Task 1. Where this plan and the spec disagree, the spec wins: stop and report.

## Global Constraints

- **No database function changes.** Every decision calls V36/V38/V39/V48's functions as they are. If a function turns out not to do what the spec says, stop and report; do not write a new one.
- Actor string passed to every function: `"admin:" + accountId` (one constant-built helper, `ModerationActor.of(id)`).
- Every console method: `@PreAuthorize("hasRole('MODERATOR')")`, `@Transactional` (default isolation: READ COMMITTED), and its `audit_log` row through A1's `AuditTrail` (`MANDATORY`).
- Never in a log, exception message, metric tag, flash message or audit `metadata`: entry text, the reporter's `detail`, the moderator's note (it goes only in `justification` and the functions' own logs), an email, a storage key, a URL.
- Notes: required, 1–400 code points after trim.
- Step-up window: 5 minutes, session attribute `admin.secondFactorAt` (an `Instant`), set by sign-in's TOTP step and by `stepUp`. The **service** applies the rule (`StepUp.isFresh(secondFactorAt, clock.instant())`); controllers pass the attribute through, never a boolean.
- Every guard the console adds around a media function is taken **under the asset's row lock** in the port: `FOR UPDATE` for delete and block, `FOR SHARE` for the view. The view also share-locks the reports holding the asset, **report first, then asset**: the order `entry_report_uphold`, `_reclassify` and `_dismiss` already use, so no lock cycle is possible.
- **Console ports are `MANDATORY`; export reads are not.** The export build holds no transaction (`PrepareExports`), so `QuarantineModeration.ownedBy` is `SUPPORTS` and `AdminAccessDisclosure.accessesTo` is `@Transactional(readOnly = true)`.
- **Every concurrency test waits with a deadline** (a few seconds, polling `pg_locks`/`pg_stat_activity`) and **fails** on timeout. That way a mutation that removes a lock goes red instead of hanging.
- Migration **V61** in `modules/admin/src/main/resources/db/migration/`. Re-check `main` for a V61 before Task 3; if one has landed, take the next free number and say so in the PR.
- No admin route in `contracts/openapi.json`; `OpenApiContractTest` passes with no change.
- Commits: Conventional Commits, subject ≤ 88 characters; `git config core.hooksPath .githooks` in the worktree.
- Environment: another worktree serves a phone on ports 8080/5432/6379/9000. Never `./gradlew --stop`, never kill java/gradle, never touch the default compose project. The smoke runs in an isolated compose project with its own ports.

## Review Focus

1. **Nothing a member wrote leaks.** The unique-marker test (Task 6) and the log-capture test (Task 6) are the proof; a reviewer reads every template and every `metadata` map.
2. **The view rule and the evidence rule cannot be bypassed or raced.** `BLOCKED`, `evidence_held` and priority/consent reports are refused under the row lock, not by hiding a link. Pinned in Task 1 (the `pg_locks` races), Task 5 (service) and Task 6 (HTTP).
3. **Atomicity.** A decision and its audit row commit together; the injected-failure test (Task 5) proves rollback of the function's effects.
4. **Stale and concurrent decisions** give one success and one `REFUSED`, synchronised on `pg_locks`, never a sleep (Task 2, Task 6).
5. **Console ≡ script.** Task 7's twin-database cross-check.
6. **Nothing tips anyone off.** Disclosure is decided at export time; a view followed by a block, any report-driven act, and `SAFETY`/`LEGAL` lookups are absent from every export (Task 7).

---

### Task 1: `media.api.QuarantineModeration`, the locked guards, and the media half of the view rule

**Files:**
- Create: `modules/media/src/main/kotlin/com/moyi/media/api/QuarantineModeration.kt` (interface + DTOs + outcome enums)
- Create: `modules/media/src/main/kotlin/com/moyi/media/infra/database/QuarantineModerationStore.kt` (internal, `JdbcClient`)
- Create: `modules/media/src/main/kotlin/com/moyi/media/domain/ModeratorReview.kt` (pure: `allows(verdict, evidenceHeld, status, purged, heldByReport)`; `purged_at` is not on `MediaAsset`, `MediaAssetStore` reads it, so the rule takes facts, not the entity)
- Modify: `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt` (add the new types to A1's admin-only rule)
- Test: `modules/media/src/test/kotlin/com/moyi/media/infra/QuarantineModerationTest.kt`, `QuarantineModerationRaceTest.kt`, `domain/ModeratorReviewTest.kt`, `QuarantineOutcomeNamesTest.kt`

**Interfaces (produces):** every implementation method `@Transactional(propagation = MANDATORY)`:
- `queue(actor): List<QuarantineRow>` → `SELECT * FROM media_quarantine_list(:actor)`
- `openCount(): Int` — `status = 'QUARANTINED' OR evidence_held`, the list's own predicate; writes nothing (for the home page)
- `asset(mediaId): QuarantinedAsset?` (status, verdict, reason, kind, attached, moderatedAt, evidenceHeld, purged, heldByReport, reportDriven), no log row, no lock
- `history(mediaId)`, `reportsHolding(mediaId): List<UUID>`
- `release(mediaId, actor, note): ReleaseOutcome` → `media_quarantine_release` (it checks report holds itself)
- `block(mediaId, actor, note): BlockOutcome` — `SELECT … FOR UPDATE`; any `media_report_holds` row → `REPORT_HELD`; else `media_quarantine_block`
- `delete(mediaId, actor, note): DeleteOutcome` — `SELECT moderation_verdict, evidence_held … FOR UPDATE`; `BLOCKED` or held → `EVIDENCE_SERVER_ONLY`; any report hold → `REPORT_HELD`; else `media_quarantine_delete`
- `reportsHolding(mediaId): List<UUID>` is the unlocked first read of the view (spec §7.4 step 1).
- `reviewCopy(mediaId, expectedReports: Set<UUID>): Review` runs `SELECT … FOR SHARE` on the asset (reading `purged_at`), then `ModeratorReview.allows`, then reads `media_report_holds` again. A set different from `expectedReports` → `Refused("changed")`. Otherwise it returns `Refused(reason)` or `Allowed(reportDriven, copy)`. the copy's content type is the variant's (`image/webp`, `audio/mp4` for `voice.m4a`, `audio/ogg` for `voice.ogg`), never `media_assets.content_type`; a variant missing from the store → `Refused("missing")` when opened
- `ownedBy(userId): List<UUID>` (every asset the user owns, deleted included), for Task 7. It is **`@Transactional(propagation = SUPPORTS, readOnly = true)`, not `MANDATORY`**, because the export build that calls it holds no transaction.
- `enum ReleaseOutcome { RELEASED, REMODERATED, NEVER_RELEASED, REPORT_HELD, NOT_QUARANTINED, NOT_HELD_BACK }`. `NOT_HELD_BACK` is reachable because `media_quarantine_release` can `RETURN media_give_back(...)` (V38), and that function answers it., `enum BlockOutcome { BLOCKED, ALREADY_BLOCKED, GONE, REPORT_HELD }`, `enum DeleteOutcome { DELETED, NOT_QUARANTINED, EVIDENCE_SERVER_ONLY, REPORT_HELD }`; an unknown string from SQL throws `IllegalStateException` naming the function, not the id.
- All DTOs' `toString` print ids only; `ReviewCopy` holds no key in its `toString`.

- [ ] **Step 1: Write failing tests.**
  - `ModeratorReviewTest` (pure): allowed for `QUARANTINED/QUARANTINED`; for `DELETED` held by a report with objects present; refused for `BLOCKED`, `evidence_held`, `READY/PASSED` unheld, purged.
  - `QuarantineModerationTest` (Postgres, Flyway chain): each outcome of each enum reached; `queue` returns the function's rows in order and writes one `LISTED`; `openCount` writes nothing; any console method outside a transaction throws `IllegalTransactionStateException`, while `ownedBy` outside one succeeds; `reviewCopy` refuses a blocked asset without touching the store (a fake `ObjectStore` records keys opened) and opens `display.webp` as `image/webp` for a photo whose `content_type` is `image/heic`; voice with and without `voice.m4a`; `missing` when the fake store has no object; `changed` when a hold is added between `reportsHolding` and `reviewCopy`.
  - `QuarantineModerationRaceTest` (two connections; synchronised on `pg_locks`/`pg_stat_activity.wait_event_type = 'Lock'`, never a sleep):
    1. A: `BEGIN; SELECT media_quarantine_block(id, …)` (holds the row). B: port `delete` in its own transaction, observed waiting. A commits. B returns `EVIDENCE_SERVER_ONLY`; the asset is `BLOCKED`, `evidence_held`, with no `DELETED` log row.
    2. A: `media_report_hold(entry, report, …)` held open; B: `delete` waits, then `REPORT_HELD`; B: `block` likewise `REPORT_HELD`.
    3. A: `media_report_hold` held open. B: `reportsHolding` (sees none), then `reviewCopy(id, emptySet())`, observed waiting on its `FOR SHARE` (with a deadline), then answers `Refused("changed")` once A commits.
    Every wait in this class has a deadline and fails the test on timeout. With `FOR SHARE` mutated away, race 3 never waits: it fails on the deadline, or on the outcome, and never hangs.
  - `QuarantineOutcomeNamesTest`: every `RETURN '…'` literal in `pg_get_functiondef` of `media_quarantine_release`, **`media_give_back`** (which release can return through), `_block` and `_delete` is a name in the matching enum. Port-only names are excepted and listed in the test. The check also runs in reverse.
- [ ] **Step 2: Run them and see them fail** (`./gradlew :modules:media:test --tests "*QuarantineModeration*" --tests "*ModeratorReview*" --tests "*QuarantineOutcomeNames*"`).
- [ ] **Step 3: Implement.** Check `build-logic`'s module rules allow `admin → media`; if they need a line, add it here.
- [ ] **Step 4: Mutations** (each red, then restored): drop `FOR UPDATE` from `delete` (race 1 goes red); drop the `REPORT_HELD` check (race 2); drop `FOR SHARE` (race 3); drop the `BLOCKED` clause from `ModeratorReview.allows`; remove `MANDATORY`.
- [ ] **Step 5: Run `:modules:media:test` and `:app:test --tests "*ArchitectureTest*"`; all pass.**
- [ ] **Step 6: Commit** `feat(media): an admin-only port over the quarantine functions, guarded under lock`

### Task 2: `gratitude.api.ReportModeration`, the stale check, and the gratitude half of the view rule

**Files:**
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/api/ReportModeration.kt`
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/infra/database/ReportModerationStore.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Report.kt` (`ReportReason.mediaReviewable`)
- Modify: `ArchitectureTest.kt` (admin-only rule; plus: `modules/admin` imports from `com.moyi.gratitude.api` and `com.moyi.media.api` only the A2 ports and their DTOs)
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/infra/ReportModerationTest.kt`, `ReportModerationConcurrencyTest.kt`, `ReportOutcomeNamesTest.kt`, `ReportReasonTest.kt` (extend)

**Interfaces (produces):** methods `MANDATORY`:
- `queue(actor): List<ReportRow>` → `entry_report_list`
- `openCount(): Int` (`status = 'OPEN'`; writes nothing, for the home page)
- `read(reportId, actor): ReportCase?` → `entry_report_read` (rows folded into one case with its held media), plus `history(reportId)` from `entry_report_log`
- `dismiss(reportId, expectedReason: String, actor, note): DismissOutcome` (`DISMISSED, NOT_OPEN, STALE`)
- `reclassify(reportId, expectedReason, newReason, actor, note): ReclassifyOutcome` (`RECLASSIFIED, SAME_REASON, NOT_OPEN, STALE`); an unknown reason is refused by `ReportReason.of` before SQL
- `uphold(reportId, expectedReason, takedown: Takedown /* DELETE, BLOCK */, actor, note): UpholdResult` = `NotOpen | ReclassifyFirst | Stale | Done(media: List<Pair<UUID, String>>)`
- `reviewableReasons(reportIds: Collection<UUID>): Boolean` runs `SELECT reason FROM entry_reports WHERE id = ANY(:ids) AND status = 'OPEN' FOR SHARE`. It returns true if every open report among them has `mediaReviewable`. The share lock makes a concurrent `entry_report_reclassify` (which takes `FOR UPDATE`) wait for the view's commit, or the view wait for it.
- `isPriority(reportId)` is not needed: `ReportRow.priority` and `ReportCase.priority` carry it, read from the function.
- Stale check: `SELECT reason FROM entry_reports WHERE id = :id AND status = 'OPEN' FOR UPDATE`; absent → `NOT_OPEN` without calling the function (the function would say the same); different → `STALE`; equal → call the function.
- `ReportCase.toString` never prints `detail`.

- [ ] **Step 1: Write failing tests.**
  - `ReportReasonTest`: `mediaReviewable` false for `MINOR_SEXUAL_CONTENT` and `NON_CONSENSUAL`, true for the other three.
  - `ReportModerationTest`: every outcome above through the real functions; dismiss gives back a held photo exactly as `media_report_release` does; uphold `DELETE` on a priority report is `ReclassifyFirst` and writes nothing; `read` of an unknown id returns `null` and writes no `READ`; a member report reads with its member id and no media; `STALE` writes nothing to `entry_report_log`.
  - `ReportModerationConcurrencyTest` (every wait bounded, fails on timeout):
    - A reclassify to `MINOR_SEXUAL_CONTENT` held open: `reviewableReasons` waits, then returns false. thread A reclassifies inside an open transaction holding the row; thread B's `dismiss` blocks (assert via `pg_locks` / `pg_stat_activity.wait_event_type = 'Lock'`); A commits; B returns `STALE`. Second case: two dismisses with the same reason; exactly one `DISMISSED`, one `NOT_OPEN`.
  - `ReportOutcomeNamesTest`: as Task 1, for `entry_report_dismiss`, `_reclassify`, `_uphold` and `media_report_takedown`'s outcomes.
- [ ] **Step 2: Run and see them fail.** **Step 3: Implement.**
- [ ] **Step 4: Mutation:** remove the reason comparison, see the stale test go red; flip `mediaReviewable` for priority, see red; restore both.
- [ ] **Step 5: `:modules:gratitude:test` and the ArchitectureTest pass.**
- [ ] **Step 6: Commit** `feat(gratitude): an admin-only port over the report queue functions`

### Task 3: V61, the new audit actions, and `Disclosure.shows`

**Files:**
- Create: `modules/admin/src/main/resources/db/migration/V61__admin_moderation_audit.sql`
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/domain/AuditAction.kt`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/domain/Disclosure.kt`, `LookupPurpose.kt`
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/infra/V61MigrationTest.kt`, `AuditActionCheckTest.kt` (unchanged code, must still pass), `domain/DisclosureTest.kt`

**Interfaces (produces):**
- `AuditAction` gains `REPORT_QUEUE_VIEWED, REPORT_READ, REPORT_DISMISSED, REPORT_RECLASSIFIED, REPORT_UPHELD, QUARANTINE_QUEUE_VIEWED, MEDIA_VIEWED, MEDIA_RELEASED, MEDIA_BLOCKED, MEDIA_DELETED, ADMIN_STEP_UP` (17 in all).
- `enum LookupPurpose(val disclosed: Boolean) { MEMBER_REQUEST(true), OPERATIONS(true), SAFETY(false), LEGAL(false) }`
- `object Disclosure { fun shows(action, outcome, purpose: LookupPurpose?, reportDriven: Boolean?): String? }` is a function of the row alone. It returns the export word or `null`, exactly as spec §9.3 says:
  - `USER_LOOKED_UP/SUCCEEDED` with a disclosed purpose → `ACCOUNT_LOOKED_UP`;
  - `MEDIA_DELETED/SUCCEEDED` with `reportDriven == false` → `MEDIA_REMOVED`;
  - everything else, `MEDIA_VIEWED` and `MEDIA_RELEASED` included, → `null`;
  - a lookup with no purpose (a pre-A2 row) → `null`.
- V61: `ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check; ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (…17…));` and nothing else (no column: spec §9.1).

- [ ] **Step 1: Failing tests.** `V61MigrationTest`: an A1 row written before V61 survives unchanged (migrate to V60, insert, migrate to V61, compare); each new action inserts; `'NOPE'` is refused; `UPDATE`/`DELETE` still raise `audit_log is append-only`. `DisclosureTest`: every row of spec §9.3's list on both sides: `reportDriven` true → `null`; `MEDIA_VIEWED` and `MEDIA_RELEASED` → `null` whatever their facts; `MEDIA_BLOCKED` → `null`; `REFUSED` → `null`; each `LookupPurpose`; no purpose → `null`.
- [ ] **Step 2: Run, fail, implement, pass.** **Step 3: Mutations:** let `MEDIA_VIEWED` through; drop the `reportDriven` clause; flip `SAFETY.disclosed`; each red, restored.
- [ ] **Step 4: Commit** `feat(admin): V61 audit actions for moderation, and the disclosure rule`

### Task 4: Step-up

**Files:**
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/service/AdminSignIn.kt` (`stepUp`), `web/AdminAuthController.kt` (set `admin.secondFactorAt` on the TOTP step), `web/AdminAuthorities.kt` (the attribute name)
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/web/StepUp.kt` (reads the attribute, checks the 5-minute window against the `Clock`)
- Test: `app/src/test/kotlin/com/moyi/app/admin/AdminStepUpTest.kt`

**Interfaces (produces):** `AdminSignIn.stepUp(adminId, code, from): TotpStep` sharing `totp`'s private verification (one code path: refactor `totp` into `verifyCode(…)` used by both; do not copy it). Success writes `ADMIN_STEP_UP/SUCCEEDED`; failure counts toward the lockout and writes `ADMIN_STEP_UP/REFUSED`. `object StepUp { val WINDOW = 5.minutes; fun isFresh(secondFactorAt: Instant?, now: Instant): Boolean }` in `admin.service` (the service applies it; Task 5).

- [ ] **Step 1: Failing tests:** fresh at sign-in + 4 min 59 s, stale at + 5 min 1 s (`MutableClock`); a correct code refreshes the window and spends the step (the same code again is refused as a replay); five wrong codes lock the account exactly as five wrong sign-in codes do; A1's `AdminSignInTest` still passes untouched.
- [ ] **Step 2: Run, fail, implement, pass.** **Step 3:** the mutation of `isFresh` is run in Task 5 and Task 6, where the tests that it must break exist (tracked there as named steps).
- [ ] **Step 4: Commit** `feat(admin): a five-minute step-up on the second factor for irreversible acts`

### Task 5: `ModerationConsole`, the service

**Files:**
- Modify: `modules/admin/build.gradle.kts` (`projects.modules.gratitude`, `projects.modules.media`)
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/ModerationConsole.kt`, `ModerationActor.kt`, `ModerationNote.kt` (the 1–400 rule, once)
- Test: `app/src/test/kotlin/com/moyi/app/admin/ModerationConsoleServiceTest.kt`

**Interfaces (produces):** `@Service @ConditionalOnAdminConsole`, every method `@PreAuthorize("hasRole('MODERATOR')") @Transactional`:
- `counts()` (both ports' `openCount`, no audit row), `reportQueue(adminId, from)`, `report(reportId, adminId, from)`, `dismiss(…, expectedReason, note)`, `reclassify(…)`, `uphold(…, takedown, note, secondFactorAt: Instant?)`, `quarantineQueue(…)`, `quarantined(mediaId, …)`, `release(…)`, `block(…, secondFactorAt)`, `delete(…, secondFactorAt)`, `openForReview(mediaId, adminId, from): ReviewCopy?`.
- `uphold`, `block` and `delete` call `StepUp.isFresh(secondFactorAt, clock.instant())` first and throw `StepUpRequired` before any read or write (the controller turns it into the code field).
- `delete`'s `EVIDENCE_SERVER_ONLY` and `REPORT_HELD` come from the port (Task 1), taken under the lock; the service only audits them `REFUSED`. The service does **not** read the asset first and decide: that was the review's check-then-act finding.
- `openForReview`: in one transaction, `media.reportsHolding(id)` (unlocked), then `reports.reviewableReasons(ids)` (`FOR SHARE` on the reports), then `media.reviewCopy(id, ids)` (`FOR SHARE` on the asset, holds re-checked). Report first, asset second (spec §7.4). writes `MEDIA_VIEWED` (`SUCCEEDED` or `REFUSED` with the reason, and `reportDriven`); returns the copy only after the row is written; the transaction commits when the method returns, before the controller streams.
- Each decision writes one audit row (spec §9.2) with `reportDriven` on every `MEDIA_*` row. No row carries a disclosure decision.
- Metrics `moyi.admin.moderation.decision{queue,decision,outcome}` and `moyi.admin.media.review{outcome}`.

- [ ] **Step 1: Failing tests** (Spring context, admin enabled, security context set per role):
  - a `SUPPORT` context is refused (`AccessDeniedException`) on every method, before any `LISTED`/`READ` row is written;
  - each decision's audit row: actor id, justification = note, outcome, metadata keys exactly as spec §9.2, no `detail` and no note in `metadata`;
  - **atomicity:** with a test-only trigger raising on `audit_log` insert, `dismiss` throws and the report is still `OPEN`, its media still `QUARANTINED`, and `entry_report_log` has no `DISMISSED`;
  - the transaction is READ COMMITTED (`SHOW transaction_isolation` from inside a console call via a test hook) and `delete` succeeds;
  - `openForReview` refuses each spec §7.4 case and writes `REFUSED`; a refused view returns `null` with no store access (the fake store records none);
  - `delete` of a blocked asset: `EVIDENCE_SERVER_ONLY`, the asset untouched, `REFUSED` row; `block` of a quarantined asset: `BLOCKED`, `evidence_held`, a `MEDIA_BLOCKED` row;
  - `uphold`, `block` and `delete` with `secondFactorAt` 5 min 1 s old, or null: `StepUpRequired`, nothing written; at 4 min 59 s they decide;
  - `counts()` writes no `LISTED` row in either module log and no audit row.
- [ ] **Step 2: Run, fail, implement, pass.**
- [ ] **Step 3: Mutations** (each red, then restored): remove `@PreAuthorize` from `dismiss`; remove the audit call from `release`; make `StepUp.isFresh` always true (Task 4's mutation, run here); drop the reports half from `openForReview`; build the actor without the `admin:` prefix (a test asserts `entry_report_log.actor = 'admin:<id>'`).
- [ ] **Step 4: Commit** `feat(admin): the moderation console service over the report and quarantine ports`

### Task 6: Pages, controllers and the review copy

**Files:**
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/web/ReportsController.kt`, `QuarantineController.kt`, `MediaReviewController.kt`
- Create templates: `templates/admin/reports.html`, `report.html`, `quarantine.html`, `quarantined.html`, `fragments/decision.html` (note field, hidden expected reason, code field when stale), `not-found.html` if A1 has none
- Modify: `templates/admin/layout.html` (nav for `MODERATOR`), `home.html` (open counts from `counts()`; no "overdue": spec §5), `web/AdminSecurityConfiguration.kt` (`media-src 'self'` in the CSP constant), `admin.css`
- Test: `app/src/test/kotlin/com/moyi/app/admin/ReportQueueTest.kt`, `QuarantineQueueTest.kt`, `MediaReviewTest.kt`, `ModerationPrivacyTest.kt`, `ModerationConcurrencyTest.kt`; A1's `AdminChainTest` CSP assertion updated to the new constant.

**Interfaces (produces):** routes of spec §5, including `POST /admin/quarantine/{mediaId}/block`; POST-redirect-GET; flash result chosen from the outcome enum (a `when` over it, so a new outcome fails compilation, not a page), with no counts in it; controllers pass the session's `admin.secondFactorAt` to the service as it is; every refusal of a read (`unknown id`, bad UUID, refused view) renders the same admin `404` page. Lists show each item's age in days.

- [ ] **Step 1: Failing tests** (A1's `AdminConsoleDriver`, real Postgres, MinIO-less fake store for bytes):
  - `ReportQueueTest`: list order equals `entry_report_list`; priority row shows `DO NOT VIEW — runbook §2` and no view link; report page shows the detail; each decision form works end to end and flashes the right line; a decided report shows "Emptied when decided."; a POST without CSRF is the `403` page and decides nothing; `SUPPORT` gets `403` on every route.
  - `QuarantineQueueTest`: release, block (listed first, with its runbook line) and delete; a `BLOCKED` row has no delete form, and a forged POST gets `EVIDENCE_SERVER_ONLY`; a report-held row has no decision form and links to the report, and forged release, block and delete POSTs each get `REPORT_HELD`.
  - `MediaReviewTest`: bytes, `Content-Type`, `Cache-Control: no-store`, `nosniff`, `inline`, `Cross-Origin-Resource-Policy: same-origin`; refusals are byte-identical `404` pages; the page's HTML has no `http` URL other than `/admin/…`.
  - **Step-up:** at sign-in + 6 min, "Uphold, delete", quarantine "Block" and "Delete" each re-render with a code field and the note kept, and decide nothing; with a correct code they decide.
  - `ModerationPrivacyTest`: an entry whose text is `MARKER-<uuid>`; reported; every A2 page, every audit row and all captured log output searched for the marker: none. The reporter's detail (`DETAIL-<uuid>`) appears on the report page only, never in logs, audit rows or the flash.
  - `ModerationConcurrencyTest`: two admin sessions dismiss one report; the second POST observed blocked on the row lock before the first commits; one "Dismissed", one "Already decided by someone else"; two audit rows, `SUCCEEDED` and `REFUSED`.
- [ ] **Step 2: Run, fail, implement, pass.** A1's `TemplateSafetyTest` passes over the new templates; prove it still bites with a temporary `th:utext` in `report.html`.
- [ ] **Step 3: Mutations** (each red, then restored): remove `no-store` from the review copy; make `StepUp.isFresh` always true (Task 4's mutation, tracked here: the step-up test above must go red); serve `media_assets.content_type` instead of the variant's (`MediaReviewTest`'s HEIC case must go red).
- [ ] **Step 4: Commit** `feat(admin): report and quarantine pages for moderators, with a no-store review copy`

### Task 7: Lookup purposes, disclosure at export time, and the console ≡ script cross-check

**Files:**
- Modify: `modules/admin/build.gradle.kts` (`projects.modules.export`)
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/AdminAccessDisclosure.kt` (implements `export.api.AdminAccessLog`; **not** `@ConditionalOnAdminConsole`, ADR-0055 ruling D)
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/service/UserLookup.kt`, `web/UserLookupController.kt`, `templates/admin/users.html` (a required purpose select; `metadata.purpose`; a required note of 1–400 characters for `SAFETY`/`LEGAL`, written to `justification`, owner ruling 6, spec §14a)
- Modify: `modules/export/src/main/kotlin/com/moyi/export/service/ArchiveWriter.kt` (the three statements become the one sentence of spec §9.3, shown whether or not anything was withheld; `NO_ADMIN_LOG` stays for no bean), `api/AdminAccessLog.kt` (KDoc: implemented by admin; some access is withheld by rule)
- Test: `app/src/test/kotlin/com/moyi/app/admin/AdminAccessDisclosureTest.kt`, `ConsoleMatchesScriptTest.kt`; A1's `UserLookupTest` (now passes a purpose); any export test asserting `logKept = false` or the old sentences

**Interfaces (produces):** `accessesTo(userId)`, read each time an export is built, no row written: lookups by `target_type = 'USER' AND target_id = :u::text` and media rows by `target_type = 'MEDIA' AND target_id = ANY(:ids)`, with `ids` the user's media ids **as text** (`target_id` is `text`). Both go through V60's `audit_log_target_idx`. Each row goes through `Disclosure.shows`; `justification = null`; oldest first. `accessesTo` is `@Transactional(readOnly = true)` and opens its own transaction, because the export build holds none.

- [ ] **Step 1: Failing tests.**
  - `AdminAccessDisclosureTest`:
    - A lookup of A for `MEMBER_REQUEST` and the removal of A's moderation-quarantined photo: A's export lists `ACCOUNT_LOOKED_UP` and `MEDIA_REMOVED`, `justification: null`. B's lists nothing.
    - **A view and a release of A's photo list nothing**, and two exports built either side of a later block of a viewed photo have byte-identical `adminAccess` (the re-review's finding 2: no tip-off by disappearance).
    - A view under a `VIOLENCE_OR_SELF_HARM` report, an uphold that took A's photo down, and an operator's `REPORTED` hold then deleted: nothing for A.
    - Lookups for `SAFETY` and `LEGAL`: nothing. Without a note they are refused (400 on the form, nothing audited as `SUCCEEDED`); with one, the audit row's `justification` holds it. Mutation: drop the note check; the refusal test goes red.
    - `logKept` is `true` with the console off too.
    - **Through `PrepareExports`, with no surrounding transaction.** The test class is **not** `@Transactional`: it calls the job's `prepareDue` as the scheduler does and asserts the export is `READY` and its archive's `adminAccess`. A test inside a test transaction would hide a `MANDATORY` method (the re-review's finding 1). Mutation: make `ownedBy` `MANDATORY`; this test goes red.
    - The `::text` binding: a seeded `MEDIA_DELETED` row on A's asset is found. Mutation: bind the ids as `uuid[]`; the query fails and the test goes red.
    - The statement is the one sentence in every case. Compare an export with withheld rows against one with none: the sentence is byte-identical.
  - `ConsoleMatchesScriptTest`: two databases (two schemas, or two Testcontainers databases), seeded identically. For dismiss, reclassify, uphold delete, uphold block, release, block and delete, decide one through the console and the twin by `SELECT entry_report_…(…)` / `media_quarantine_…(…)`, as `scripts/moyi` does. Copy the SQL from the script's `ops_sql` lines and cite them. Compare `media_assets` (status, verdict, moderation_scores, evidence_held, deleted_at null-ness), `media_report_holds`, `entry_reports` and the outbox event types. Actors and ids are excepted.
- [ ] **Step 2: Run, fail, implement, pass.**
- [ ] **Step 3: Mutations** (each red, then restored): let `Disclosure.shows` pass `MEDIA_VIEWED` (the "view lists nothing" test); drop `readOnly` `@Transactional` from `accessesTo` while `ownedBy` is `MANDATORY` (the `PrepareExports` test).
- [ ] **Step 4: Commit** `feat(admin): disclose lookups and removals in the export, never views`

### Task 8: Operations, the record and the smoke run

**Files:**
- Create: `adr/0063-the-admin-console-a2-moderation.md` (every *(builder's call)* in the spec; the owner questions of §14 as open questions)
- Modify: `adr/0055-…md` (Owed: A2 done), `adr/0059-the-data-export.md` (decision 8: implemented by A2), `adr/0051-…md` and `adr/0053-…md` (Revisit when / Owed: the console now calls the functions)
- Modify: `ops/moderation/takedown.md` (§1, §3: the console path beside the script; §3.3: words, pending §14 Q2), `ops/admin/README.md` (inviting a moderator; what stays on the server)
- Modify: `scripts/smoke.sh` (spec §13; the export's `logKept` expectation `False` → `True`; the lookup steps pass a purpose)
- Modify: `docs/learning-log.md` only if something went wrong worth recording

- [ ] **Step 1: `./gradlew build`.** Expected: BUILD SUCCESSFUL; record the test count from the JUnit XML.
- [ ] **Step 2: Smoke in an isolated compose project** (its own project name and ports; never the default `moyi-backend` project or ports 8080/5432/6379/9000). Expected: `0 failed`; the A2 checks of spec §13 listed by name in the PR with their counts.
- [ ] **Step 3: `scripts/state-map-check --strict`** is clean (admin pages are not API operations).
- [ ] **Step 4: Commit** `docs(admin): ADR-0063, the runbooks and the smoke section for moderation`
- [ ] **Step 5: PR body** lists what was run (build, smoke, counts) and what was only read, the owner questions of spec §14 with their recommendations, and states that production is unchanged (`MOYI_ADMIN_ENABLED` stays `"false"`).
