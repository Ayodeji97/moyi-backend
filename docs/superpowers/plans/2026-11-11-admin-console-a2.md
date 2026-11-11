# Admin console A2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Each task is sized for one implementer pass and one independent review pass.

**Goal:** A `MODERATOR` decides the report queue and the quarantine queue in the switched-off admin console, through the database functions `scripts/moyi` already calls, with each decision audited under the admin's id, irreversible ones behind a fresh second factor, and held-back media viewable only where the runbook allows.

**Architecture:** Two admin-only ports, `gratitude.api.ReportModeration` and `media.api.QuarantineModeration`, each implemented in its own module's `infra` by `JdbcClient` calls to the existing functions, each `MANDATORY` so it joins the console's transaction. `modules/admin` gains `ModerationConsole` (service, `@PreAuthorize("hasRole('MODERATOR')")`), three controllers, plain-form Thymeleaf pages, a step-up check, and `AdminAccessDisclosure` implementing `export.api.AdminAccessLog`. Migration V61 widens `audit_log`'s action `CHECK` and adds `disclosed_to`.

**Tech Stack:** as A1: Kotlin 2.4, Spring Boot 4.1.2, Spring Security 7.1.2, Thymeleaf, Postgres 18, Flyway, JUnit 6 + kotest + MockMvc + Testcontainers. No new dependency.

**Spec:** `docs/superpowers/specs/2026-11-11-admin-console-a2-design.md`. Read it in full, and A1's spec §6–§8, before Task 1. Where this plan and the spec disagree, the spec wins: stop and report.

## Global Constraints

- **No database function changes.** Every decision calls V36/V38/V39/V48's functions as they are. If a function turns out not to do what the spec says, stop and report; do not write a new one.
- Actor string passed to every function: `"admin:" + accountId` (one constant-built helper, `ModerationActor.of(id)`).
- Every console method: `@PreAuthorize("hasRole('MODERATOR')")`, `@Transactional` (default isolation: READ COMMITTED), and its `audit_log` row through A1's `AuditTrail` (`MANDATORY`).
- Never in a log, exception message, metric tag, flash message or audit `metadata`: entry text, the reporter's `detail`, the moderator's note (it goes only in `justification` and the functions' own logs), an email, a storage key, a URL.
- Notes: required, 1–400 code points after trim.
- Step-up window: 5 minutes, session attribute `admin.secondFactorAt` (an `Instant`), set by sign-in's TOTP step and by `stepUp`.
- Migration **V61** in `modules/admin/src/main/resources/db/migration/`. Re-check `main` for a V61 before Task 3; if one has landed, take the next free number and say so in the PR.
- No admin route in `contracts/openapi.json`; `OpenApiContractTest` passes with no change.
- Commits: Conventional Commits, subject ≤ 88 characters; `git config core.hooksPath .githooks` in the worktree.
- Environment: another worktree serves a phone on ports 8080/5432/6379/9000. Never `./gradlew --stop`, never kill java/gradle, never touch the default compose project. The smoke runs in an isolated compose project with its own ports.

## Review Focus

1. **Nothing a member wrote leaks.** The unique-marker test (Task 6) and the log-capture test (Task 6) are the proof; a reviewer reads every template and every `metadata` map.
2. **The view rule cannot be bypassed.** `BLOCKED`, `evidence_held` and priority/consent reports are refused by the service, not by hiding a link. Pinned in Task 5 (service) and Task 6 (HTTP).
3. **Atomicity.** A decision and its audit row commit together; the injected-failure test (Task 5) proves rollback of the function's effects.
4. **Stale and concurrent decisions** give one success and one `REFUSED`, synchronised on `pg_locks`, never a sleep (Task 2, Task 6).
5. **Console ≡ script.** Task 7's twin-database cross-check.

---

### Task 1: `media.api.QuarantineModeration` and the media half of the view rule

**Files:**
- Create: `modules/media/src/main/kotlin/com/moyi/media/api/QuarantineModeration.kt` (interface + DTOs + outcome enums)
- Create: `modules/media/src/main/kotlin/com/moyi/media/infra/database/QuarantineModerationStore.kt` (internal, `JdbcClient`)
- Modify: `modules/media/src/main/kotlin/com/moyi/media/domain/MediaAsset.kt` (`reviewableByModerator`)
- Modify: `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt` (add the new types to A1's admin-only rule)
- Test: `modules/media/src/test/kotlin/com/moyi/media/infra/QuarantineModerationTest.kt`, `MediaAssetReviewableTest.kt`, `QuarantineOutcomeNamesTest.kt`

**Interfaces (produces):**
- `interface QuarantineModeration` — every method `@Transactional(propagation = MANDATORY)` in the implementation:
  - `fun queue(actor: String): List<QuarantineRow>` → `SELECT * FROM media_quarantine_list(:actor)`
  - `fun asset(mediaId: UUID): QuarantinedAsset?` (status, verdict, reason, kind, attached, moderatedAt, ownerUserId, evidenceHeld, purged, reviewable), no log row
  - `fun history(mediaId: UUID): List<ModerationLogRow>` (from `media_moderation_log`, oldest first)
  - `fun reportsHolding(mediaId: UUID): List<UUID>` (from `media_report_holds`)
  - `fun release(mediaId: UUID, actor: String, note: String): ReleaseOutcome` → `media_quarantine_release`
  - `fun delete(mediaId: UUID, actor: String, note: String): DeleteOutcome` → `media_quarantine_delete`
  - `fun reviewCopy(mediaId: UUID): ReviewCopy?` — `null` unless `reviewableByModerator`; a photo's display variant, a voice note's `voice.m4a` else `voice.ogg`; locks nothing.
- `enum ReleaseOutcome { RELEASED, REMODERATED, NEVER_RELEASED, REPORT_HELD, NOT_QUARANTINED }`, `enum DeleteOutcome { DELETED, NOT_QUARANTINED }`; an unknown string throws `IllegalStateException` naming the function, not the id.
- `ReviewCopy(contentType: String, open: () -> InputStream?)`, `toString` with no key.
- All DTOs' `toString` print ids only.

- [ ] **Step 1: Write failing tests.**
  - `MediaAssetReviewableTest` (pure): reviewable for `QUARANTINED/QUARANTINED`; for `DELETED` with an open report hold and objects present; not for `BLOCKED`, `evidence_held`, `READY/PASSED` with no hold, purged.
  - `QuarantineModerationTest` (Postgres, Flyway chain): each `ReleaseOutcome` and `DeleteOutcome` reached through the real function; `queue` returns `media_quarantine_list`'s rows in order and writes one `LISTED`; calling any method outside a transaction throws `IllegalTransactionStateException`; `reviewCopy` is `null` for a blocked asset and opens the display key for a quarantined photo (a fake `ObjectStore` records which key was opened).
  - `QuarantineOutcomeNamesTest`: reads `pg_get_functiondef` of `media_quarantine_release` and `_delete` and asserts every quoted `RETURN '…'` literal is an enum name, and the reverse.
- [ ] **Step 2: Run them and see them fail** (`./gradlew :modules:media:test --tests "*QuarantineModeration*" --tests "*Reviewable*" --tests "*QuarantineOutcomeNames*"`).
- [ ] **Step 3: Implement.** Check `build-logic`'s module rules allow `admin → media`; if they need a line, add it here.
- [ ] **Step 4: Mutation:** drop the `BLOCKED` clause from `reviewableByModerator`, see the test go red, restore. Remove `MANDATORY`, see the outside-transaction test go red, restore.
- [ ] **Step 5: Run `:modules:media:test` and `:app:test --tests "*ArchitectureTest*"`; all pass.**
- [ ] **Step 6: Commit** `feat(media): an admin-only port over the quarantine functions`

### Task 2: `gratitude.api.ReportModeration`, the stale check, and the gratitude half of the view rule

**Files:**
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/api/ReportModeration.kt`
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/infra/database/ReportModerationStore.kt`
- Modify: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Report.kt` (`ReportReason.mediaReviewable`)
- Modify: `ArchitectureTest.kt` (admin-only rule; plus: `modules/admin` imports from `com.moyi.gratitude.api` and `com.moyi.media.api` only the A2 ports and their DTOs)
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/infra/ReportModerationTest.kt`, `ReportModerationConcurrencyTest.kt`, `ReportOutcomeNamesTest.kt`, `ReportReasonTest.kt` (extend)

**Interfaces (produces):** methods `MANDATORY`:
- `queue(actor): List<ReportRow>` → `entry_report_list`
- `read(reportId, actor): ReportCase?` → `entry_report_read` (rows folded into one case with its held media), plus `history(reportId)` from `entry_report_log`
- `dismiss(reportId, expectedReason: String, actor, note): DismissOutcome` (`DISMISSED, NOT_OPEN, STALE`)
- `reclassify(reportId, expectedReason, newReason, actor, note): ReclassifyOutcome` (`RECLASSIFIED, SAME_REASON, NOT_OPEN, STALE`); an unknown reason is refused by `ReportReason.of` before SQL
- `uphold(reportId, expectedReason, takedown: Takedown /* DELETE, BLOCK */, actor, note): UpholdResult` = `NotOpen | ReclassifyFirst | Stale | Done(media: List<Pair<UUID, String>>)`
- `reviewableReasons(reportIds: Collection<UUID>): Boolean` — true if every **open** report among them has `mediaReviewable`
- `isPriority(reportId)` is not needed: `ReportRow.priority` and `ReportCase.priority` carry it, read from the function.
- Stale check: `SELECT reason FROM entry_reports WHERE id = :id AND status = 'OPEN' FOR UPDATE`; absent → `NOT_OPEN` without calling the function (the function would say the same); different → `STALE`; equal → call the function.
- `ReportCase.toString` never prints `detail`.

- [ ] **Step 1: Write failing tests.**
  - `ReportReasonTest`: `mediaReviewable` false for `MINOR_SEXUAL_CONTENT` and `NON_CONSENSUAL`, true for the other three.
  - `ReportModerationTest`: every outcome above through the real functions; dismiss gives back a held photo exactly as `media_report_release` does; uphold `DELETE` on a priority report is `ReclassifyFirst` and writes nothing; `read` of an unknown id returns `null` and writes no `READ`; a member report reads with its member id and no media; `STALE` writes nothing to `entry_report_log`.
  - `ReportModerationConcurrencyTest`: thread A reclassifies inside an open transaction holding the row; thread B's `dismiss` blocks (assert via `pg_locks` / `pg_stat_activity.wait_event_type = 'Lock'`); A commits; B returns `STALE`. Second case: two dismisses with the same reason; exactly one `DISMISSED`, one `NOT_OPEN`.
  - `ReportOutcomeNamesTest`: as Task 1, for `entry_report_dismiss`, `_reclassify`, `_uphold` and `media_report_takedown`'s outcomes.
- [ ] **Step 2: Run and see them fail.** **Step 3: Implement.**
- [ ] **Step 4: Mutation:** remove the reason comparison, see the stale test go red; flip `mediaReviewable` for priority, see red; restore both.
- [ ] **Step 5: `:modules:gratitude:test` and the ArchitectureTest pass.**
- [ ] **Step 6: Commit** `feat(gratitude): an admin-only port over the report queue functions`

### Task 3: V61, the new audit actions, `disclosed_to`, and `Disclosure.of`

**Files:**
- Create: `modules/admin/src/main/resources/db/migration/V61__admin_moderation_audit.sql`
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/domain/AuditAction.kt`
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/domain/Disclosure.kt`
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/infra/AuditLogEntity.kt`, `service/AuditTrail.kt` (`disclosedTo: UUID? = null`)
- Test: `modules/admin/src/test/kotlin/com/moyi/admin/infra/V61MigrationTest.kt`, `AuditActionCheckTest.kt` (unchanged code, must still pass), `domain/DisclosureTest.kt`

**Interfaces (produces):**
- `AuditAction` gains `REPORT_QUEUE_VIEWED, REPORT_READ, REPORT_DISMISSED, REPORT_RECLASSIFIED, REPORT_UPHELD, QUARANTINE_QUEUE_VIEWED, MEDIA_VIEWED, MEDIA_RELEASED, MEDIA_DELETED, ADMIN_STEP_UP`.
- `object Disclosure { fun of(action: AuditAction, outcome: AuditOutcome, subject: UUID?, withheld: Boolean): UUID? }` — spec §9.3; `withheld` is true for a blocked asset or one under an open priority report.
- `fun exportWord(action: AuditAction): String?` beside it (`ACCOUNT_LOOKED_UP`, `MEDIA_REVIEWED`, `MEDIA_RELEASED`, `MEDIA_REMOVED`; `null` for the rest).
- V61: `ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check; ALTER TABLE audit_log ADD CONSTRAINT audit_log_action_check CHECK (action IN (…16…));` `ALTER TABLE audit_log ADD COLUMN disclosed_to uuid;` `CREATE INDEX audit_log_disclosed_idx ON audit_log (disclosed_to, occurred_at) WHERE disclosed_to IS NOT NULL;`

- [ ] **Step 1: Failing tests.** `V61MigrationTest`: an A1 row written before V61 survives unchanged (migrate to V60, insert, migrate to V61, compare); each new action inserts; `'NOPE'` is refused; `UPDATE … SET disclosed_to` still raises `audit_log is append-only`. `DisclosureTest`: the table in spec §9.3, both sides of `withheld`, `REFUSED` never disclosed.
- [ ] **Step 2: Run, fail, implement, pass.** **Step 3: Mutation:** drop `withheld` from `Disclosure.of`, see red, restore.
- [ ] **Step 4: Commit** `feat(admin): V61 audit actions for moderation and a disclosed_to column`

### Task 4: Step-up

**Files:**
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/service/AdminSignIn.kt` (`stepUp`), `web/AdminAuthController.kt` (set `admin.secondFactorAt` on the TOTP step), `web/AdminAuthorities.kt` (the attribute name)
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/web/StepUp.kt` (reads the attribute, checks the 5-minute window against the `Clock`)
- Test: `app/src/test/kotlin/com/moyi/app/admin/AdminStepUpTest.kt`

**Interfaces (produces):** `AdminSignIn.stepUp(adminId, code, from): TotpStep` sharing `totp`'s private verification (one code path: refactor `totp` into `verifyCode(…)` used by both; do not copy it). Success writes `ADMIN_STEP_UP/SUCCEEDED`; failure counts toward the lockout and writes `ADMIN_STEP_UP/REFUSED`. `StepUp.isFresh(session, now): Boolean`.

- [ ] **Step 1: Failing tests:** fresh at sign-in + 4 min 59 s, stale at + 5 min 1 s (`MutableClock`); a correct code refreshes the window and spends the step (the same code again is refused as a replay); five wrong codes lock the account exactly as five wrong sign-in codes do; A1's `AdminSignInTest` still passes untouched.
- [ ] **Step 2: Run, fail, implement, pass.** **Step 3: Mutation:** make `isFresh` always true; Task 6's step-up test must go red once written — note it in the PR and run it there.
- [ ] **Step 4: Commit** `feat(admin): a five-minute step-up on the second factor for irreversible acts`

### Task 5: `ModerationConsole`, the service

**Files:**
- Modify: `modules/admin/build.gradle.kts` (`projects.modules.gratitude`, `projects.modules.media`)
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/ModerationConsole.kt`, `ModerationActor.kt`, `ModerationNote.kt` (the 1–400 rule, once)
- Test: `app/src/test/kotlin/com/moyi/app/admin/ModerationConsoleServiceTest.kt`

**Interfaces (produces):** `@Service @ConditionalOnAdminConsole`, every method `@PreAuthorize("hasRole('MODERATOR')") @Transactional`:
- `reportQueue(adminId, from)`, `report(reportId, adminId, from)`, `dismiss(…, expectedReason, note)`, `reclassify(…)`, `uphold(…, takedown, note, stepUpFresh: Boolean)`, `quarantineQueue(…)`, `quarantined(mediaId, …)`, `release(…)`, `delete(…, stepUpFresh)`, `openForReview(mediaId, via: ReportId?, adminId, from): ReviewCopy?`.
- `uphold`/`delete` with `stepUpFresh = false` throw `StepUpRequired` before any read or write (the controller turns it into the code field).
- `delete` on a `BLOCKED` asset returns `EVIDENCE_SERVER_ONLY` without calling the port, audited `REFUSED`.
- `openForReview`: `asset.reviewable && reports.reviewableReasons(media.reportsHolding(id))`; writes `MEDIA_VIEWED` (`SUCCEEDED` or `REFUSED` with the reason) with `disclosedTo = Disclosure.of(…)`; returns the copy only after the row is written; the transaction commits when the method returns, before the controller streams.
- Each decision writes its audit row (spec §9.2), and for each asset an uphold took down whose owner is told, a `MEDIA_DELETED` row with `disclosed_to` (spec §9.3).
- Metrics `moyi.admin.moderation.decision{queue,decision,outcome}` and `moyi.admin.media.review{outcome}`.

- [ ] **Step 1: Failing tests** (Spring context, admin enabled, security context set per role):
  - a `SUPPORT` context is refused (`AccessDeniedException`) on every method, before any `LISTED`/`READ` row is written;
  - each decision's audit row: actor id, justification = note, outcome, metadata keys exactly as spec §9.2, no `detail` and no note in `metadata`;
  - **atomicity:** with a test-only trigger raising on `audit_log` insert, `dismiss` throws and the report is still `OPEN`, its media still `QUARANTINED`, and `entry_report_log` has no `DISMISSED`;
  - the transaction is READ COMMITTED (`SHOW transaction_isolation` from inside a console call via a test hook) and `delete` succeeds;
  - `openForReview` refuses each spec §7.4 case and writes `REFUSED`; a refused view returns `null` with no store access (the fake store records none);
  - `delete` of a blocked asset: `EVIDENCE_SERVER_ONLY`, the asset untouched, `REFUSED` row;
  - `uphold` without step-up: `StepUpRequired`, nothing written.
- [ ] **Step 2: Run, fail, implement, pass.**
- [ ] **Step 3: Mutations** (each red, then restored): remove `@PreAuthorize` from `dismiss`; remove the audit call from `release`; remove the `EVIDENCE_SERVER_ONLY` check; drop the reports half from `openForReview`; build the actor without the `admin:` prefix (a test asserts `entry_report_log.actor = 'admin:<id>'`).
- [ ] **Step 4: Commit** `feat(admin): the moderation console service over the report and quarantine ports`

### Task 6: Pages, controllers and the review copy

**Files:**
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/web/ReportsController.kt`, `QuarantineController.kt`, `MediaReviewController.kt`
- Create templates: `templates/admin/reports.html`, `report.html`, `quarantine.html`, `quarantined.html`, `fragments/decision.html` (note field, hidden expected reason, code field when stale), `not-found.html` if A1 has none
- Modify: `templates/admin/layout.html` (nav for `MODERATOR`), `home.html` (counts, overdue), `web/AdminSecurityConfiguration.kt` (`media-src 'self'` in the CSP constant), `admin.css`
- Test: `app/src/test/kotlin/com/moyi/app/admin/ReportQueueTest.kt`, `QuarantineQueueTest.kt`, `MediaReviewTest.kt`, `ModerationPrivacyTest.kt`, `ModerationConcurrencyTest.kt`; A1's `AdminChainTest` CSP assertion updated to the new constant.

**Interfaces (produces):** routes of spec §5; POST-redirect-GET; flash result chosen from the outcome enum (a `when` over it, so a new outcome fails compilation, not a page); every refusal of a read (`unknown id`, bad UUID, refused view) renders the same admin `404` page.

- [ ] **Step 1: Failing tests** (A1's `AdminConsoleDriver`, real Postgres, MinIO-less fake store for bytes):
  - `ReportQueueTest`: list order equals `entry_report_list`; priority row shows `DO NOT VIEW — runbook §2` and no view link; report page shows the detail; each decision form works end to end and flashes the right line; a decided report shows "Emptied when decided."; a POST without CSRF is the `403` page and decides nothing; `SUPPORT` gets `403` on every route.
  - `QuarantineQueueTest`: release and delete; a `BLOCKED` row has no delete form, and a forged POST gets `EVIDENCE_SERVER_ONLY`; a report-held row has no release form and links to the report.
  - `MediaReviewTest`: bytes, `Content-Type`, `Cache-Control: no-store`, `nosniff`, `inline`, `Cross-Origin-Resource-Policy: same-origin`; refusals are byte-identical `404` pages; the page's HTML has no `http` URL other than `/admin/…`.
  - **Step-up:** at sign-in + 6 min, "Uphold, delete" re-renders with a code field and the note kept, and decides nothing; with a correct code it decides.
  - `ModerationPrivacyTest`: an entry whose text is `MARKER-<uuid>`; reported; every A2 page, every audit row and all captured log output searched for the marker: none. The reporter's detail (`DETAIL-<uuid>`) appears on the report page only, never in logs, audit rows or the flash.
  - `ModerationConcurrencyTest`: two admin sessions dismiss one report; the second POST observed blocked on the row lock before the first commits; one "Dismissed", one "Already decided by someone else"; two audit rows, `SUCCEEDED` and `REFUSED`.
- [ ] **Step 2: Run, fail, implement, pass.** A1's `TemplateSafetyTest` passes over the new templates; prove it still bites with a temporary `th:utext` in `report.html`.
- [ ] **Step 3: Mutations:** remove `no-store` from the review copy (red); make the step-up always fresh (Task 4's note; red here); restore.
- [ ] **Step 4: Commit** `feat(admin): report and quarantine pages for moderators, with a no-store review copy`

### Task 7: Disclosure in the export, and the console ≡ script cross-check

**Files:**
- Modify: `modules/admin/build.gradle.kts` (`projects.modules.export`)
- Create: `modules/admin/src/main/kotlin/com/moyi/admin/service/AdminAccessDisclosure.kt` (implements `export.api.AdminAccessLog`; **not** `@ConditionalOnAdminConsole`, ADR-0055 ruling D)
- Modify: `modules/admin/src/main/kotlin/com/moyi/admin/service/UserLookup.kt` (pass `disclosedTo = user.id` on a hit)
- Modify: `modules/export/src/main/kotlin/com/moyi/export/api/AdminAccessLog.kt` (KDoc only: the admin module now implements it)
- Test: `app/src/test/kotlin/com/moyi/app/admin/AdminAccessDisclosureTest.kt`, `ConsoleMatchesScriptTest.kt`; update any export test asserting `logKept = false` in the app context

**Interfaces (produces):** `accessesTo(userId)` = `SELECT occurred_at, action FROM audit_log WHERE disclosed_to = :u ORDER BY occurred_at, id`, mapped through `exportWord`, `justification = null`.

- [ ] **Step 1: Failing tests.**
  - `AdminAccessDisclosureTest`: after a lookup of A and a view of A's quarantined photo, A's export lists `ACCOUNT_LOOKED_UP` and `MEDIA_REVIEWED` with `justification: null`; B's export lists nothing; after a refused view under a priority report, nothing for anyone; `logKept` is `true` with the console off too.
  - `ConsoleMatchesScriptTest`: two databases (two schemas, or two Testcontainers databases) seeded identically; for dismiss, reclassify, uphold delete, uphold block, release and delete, one decided through the console and the twin by `SELECT entry_report_…(…)` / `media_quarantine_…(…)` as `scripts/moyi` does (copy the SQL from the script's `ops_sql` lines, citing them); compare `media_assets` (status, verdict, moderation_scores, evidence_held, deleted_at null-ness), `media_report_holds`, `entry_reports` and the outbox event types; actors and ids excepted.
- [ ] **Step 2: Run, fail, implement, pass.**
- [ ] **Step 3: Commit** `feat(admin): disclose admin access to a member's account and media in their export`

### Task 8: Operations, the record and the smoke run

**Files:**
- Create: `adr/0063-the-admin-console-a2-moderation.md` (every *(builder's call)* in the spec; the owner questions of §14 as open questions)
- Modify: `adr/0055-…md` (Owed: A2 done), `adr/0059-the-data-export.md` (decision 8: implemented by A2), `adr/0051-…md` and `adr/0053-…md` (Revisit when / Owed: the console now calls the functions)
- Modify: `ops/moderation/takedown.md` (§1, §3: the console path beside the script; §3.3: words, pending §14 Q2), `ops/admin/README.md` (inviting a moderator; what stays on the server)
- Modify: `scripts/smoke.sh` (spec §13; the export's `logKept` expectation `False` → `True`)
- Modify: `docs/learning-log.md` only if something went wrong worth recording

- [ ] **Step 1: `./gradlew build`.** Expected: BUILD SUCCESSFUL; record the test count from the JUnit XML.
- [ ] **Step 2: Smoke in an isolated compose project** (its own project name and ports; never the default `moyi-backend` project or ports 8080/5432/6379/9000). Expected: `0 failed`; the A2 checks of spec §13 listed by name in the PR with their counts.
- [ ] **Step 3: `scripts/state-map-check --strict`** is clean (admin pages are not API operations).
- [ ] **Step 4: Commit** `docs(admin): ADR-0063, the runbooks and the smoke section for moderation`
- [ ] **Step 5: PR body** lists what was run (build, smoke, counts) and what was only read, the owner questions of spec §14 with their recommendations, and states that production is unchanged (`MOYI_ADMIN_ENABLED` stays `"false"`).
