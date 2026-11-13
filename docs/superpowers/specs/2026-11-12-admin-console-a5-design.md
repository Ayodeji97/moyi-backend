# Admin console A5: reading a reported entry's words (ADMIN, FR-112)

**Status:** Draft, 2026-11-12. The owner ruled four product questions in chat on 2026-11-12
(§3). A builder check read it before the owner did; its answers are in §15. every other decision the corpus did not already make is marked *(builder's call)*.

**Requirements served:** FR-112 ("Viewing entry content MUST require an elevated role, a typed
justification, and MUST be immutably audit-logged and surfaced to the user in their data export"),
with its export half deliberately deferred by ruling 2 (§3, §9). FR-116 (every admin act audited).
T-10 ("Admin abuses access to read entries") is the threat.

**Corpus this builds on:** the A1 spec §11 ("A5: content access with typed justification (FR-112),
which needs the export from Phase 5 week 4"), ADR-0055 (sign-in, roles, append-only `audit_log`),
the A2 spec and ADR-0063 (the report pages, step-up, the stale-page gate, `Disclosure`, ruling 2:
"once the console is switched on, entry text is no longer read at `psql`"; A5 before A3), ADR-0053
(the report route, V39, V48), ADR-0059 decision 8 (`AdminAccessLog`), doc 09 §4 (roles: "`ADMIN`
(+ content access with justification)"), and `ops/moderation/takedown.md` §3.

## 1. Why this slice

A report about words (harassment, a threat) is decided today from the reporter's own detail. When
the detail is not enough, the runbook lets the operator read the entry at `psql`, with the reason
written only in the report's note. That read is recorded nowhere an auditor would look, is tied to
no second factor, and stops entirely when the console is switched on (ADR-0063 ruling 2). Without
A5 the console goes on with no way to read the words at all.

A5 gives an `ADMIN` one narrow door: from an open report about words, read **that one entry's
text**, after typing why and proving a second factor, with an immutable audit row written in the
same transaction as the read. Nothing else in the console ever shows a word an entry holds.

## 2. Goals, non-goals, anti-goals

**Goals**
- From an open report's page, an `ADMIN` can read the reported entry's text and its date.
- Each read needs a typed justification and a TOTP code entered **in the same request**; there is
  no window in which a second read is free.
- Each read that reaches the data is one `ENTRY_TEXT_READ` row in `audit_log` naming the admin,
  the entry, the report and the justification, beside the `ADMIN_STEP_UP` row its code wrote (as
  in A2). The text is shown only if the read's row commits.
- The report's own history (`entry_report_log`) says its words were read, and by whom, so a
  moderator and `moyi reports show` see it.
- The words never reach a log line, a metric, a flash message, an audit row, a URL, the browser's
  cache or history, or any page but the one response that shows them.

**Non-goals**
- Reading any entry not named by an open report about words (ruling 1). No search, no lookup by
  id, no browsing a member's entries, no partner's entry, no other day (ruling 3).
- Legal orders: they stay a server procedure under the runbook's section 5 (ruling 1).
- Media: A2's review copy is unchanged, and this page shows none (ruling 3).
- Taking words down. There is no takedown of words in v1 (ADR-0053 question 3); the report is still
  decided with A2's dismiss / reclassify / uphold.
- The export's release review of withheld rows (ruling 2): owed, as for A2.

**Anti-goals (a failure even if it works)**
- A second way to the same words: a GET URL, a cached page, a back button, a log, a template that
  renders the text elsewhere.
- A `MODERATOR` or `SUPPORT` reaching the text by any route, including a crafted POST.
- An export that tells an author their partner reported them while the report is open.

## 3. The owner's rulings (2026-11-12)

1. **Scope: reported entries only.** The console opens only the entry named by an open report
   about words, from that report's page. Legal orders stay a server procedure.
2. **Disclosure: withheld while the report is open.** A report on an entry always comes from the
   author's partner (a bond has two members), so disclosing the read at once would tell the author
   who reported them. Report-driven reads are withheld from the export, as A2's ruling 1 withholds
   report-driven acts; withheld rows are owed a review for release once the case closes, and the
   legal check (ADR-0063 Owed) confirms the policy. This departs from FR-112's "MUST be surfaced";
   ADR-0064 records the departure and why.
3. **Context: that entry's text and date only.** Not the partner's entry for the day, not other
   days, no media.
4. **No hold on the text.** An open report does not keep the entry's words from its author's
   erasure or withdrawal (unlike media, ADR-0053 8a). The author's right to erase wins; the admin
   then sees `ERASED` and decides from the reporter's detail. v1 has no takedown of words, and a
   threat is the reporter's to take to the police.

Already settled before A5: `ADMIN` only (doc 09 §4); typed justification and immutable audit
(FR-112, FR-116); the second factor (ADR-0055).

## 4. What "a report about words" is *(builder's call)*

The code has no such flag (`Report.kt` reasons carry only `mediaReviewable` and `priority`). A5
defines it in one place, `gratitude.domain`, next to the reasons:

**A report's entry may be read when the report is `OPEN`, its target is `ENTRY` (not `MEMBER`), and
its reason is `HARASSMENT`, `VIOLENCE_OR_SELF_HARM` or `OTHER`.**

- `NON_CONSENSUAL` and `MINOR_SEXUAL_CONTENT` are about images. A report with those reasons whose
  detail shows it is about words is reclassified first (A2's reclassify, audited with old and new
  reasons), then read. This keeps a read off any report that might be child sexual abuse material,
  where the runbook's rule is "do not view".
- Whether the report also holds media does not matter: an entry with a photo can harass in words.
- A `MEMBER` report names no entry; there is nothing to read.

## 5. The page and the flow

On `GET /admin/reports/{id}` (A2's page), when the viewer has `ADMIN` and §4 allows it, A2's
hint line ("The entry's words are not shown here…") is replaced by a form **Read the entry's
words**: a justification (1 to 400 characters, A2's `ModerationNote` rules), a TOTP code, the
hidden `expectedReason` (A2's stale-page field), CSRF. For a `MODERATOR` the hint stays and says
an `ADMIN` can read them. If §4 does not allow it, the page says why (member report, image reason:
reclassify first, not open).

`POST /admin/reports/{id}/entry-text`, in this order *(builder's call, each step before the next)*:

1. **Role.** `@PreAuthorize("hasRole('ADMIN')")` on the service method (and the controller
   method). A `MODERATOR` gets the admin 403 page; nothing is read and **no audit row is written**
   *(builder's call)*: the refusal happens in Spring Security before any of our code runs, the
   justification is not yet validated, and a `MODERATOR` is never shown the form. A hand-made POST
   is the only way there, and it is tested to read nothing.
2. **Justification.** Validated before anything else is touched; an invalid one re-renders the
   form, 422, no audit row (nothing was attempted against data).
3. **Second factor, every time, in its own transaction** *(builder's call, as A2's
   `decideGated`)*. The controller proves the typed code with `AdminSignIn.stepUp` (its row lock,
   lockout and `totp_last_step` replay CAS), which commits its own `ADMIN_STEP_UP` row, before the
   read's transaction starts. So a failed read never un-spends the code, and the admin's row lock
   is not held across the read. **The session's `secondFactorAt` window is not consulted and not
   extended**: the service is told only "proved in this request", so a code fresh for a block five
   minutes ago does not open the words. A wrong code is 401 with the form, `ADMIN_STEP_UP`
   `REFUSED` (as A2), nothing read.
4. **In one transaction:** the report gate `SELECT reason … WHERE id = ? AND status = 'OPEN' FOR
   SHARE` (A2's gate, shared rather than exclusive: a read does not decide the report, but a
   decision waits for it and a read waits for a decision); then §4's rule; then the entry's text
   and its `bond_days` date (`ERASED` if the text is null **or its author has withdrawn from the
   bond**, read as the member read path reads it, `EntryReading`'s withdrawn authors, under the
   same locks: the outbox erases a withdrawn author's text later, and the admin must not read in
   that gap what the partner can no longer read); then the `entry_report_log` row; then the
   `audit_log` row; then commit.
5. **Render** only after the commit returns. If the commit fails, the admin sees an error page and
   nothing of the text.

**Outcomes** (one closed enum, `EntryTextOutcome`; each a `metadata.result` on the row):

| Outcome | When | Page |
|---|---|---|
| `SHOWN` | §4 allows it and the entry has text | the text, the date, the report id |
| `ERASED` | the author erased the entry (text is null) or withdrew from the bond | "Its author erased this entry. There are no words to read." |
| `NOT_OPEN` | decided meanwhile | A2's "already decided" |
| `STALE` | reason changed since the page was loaded | A2's stale page |
| `NOT_ABOUT_WORDS` | `MEMBER` target, or an image reason | the reason, with "reclassify first" for an image reason |

`ERASED` and the refusals in this table are still audited (`SUCCEEDED` for `ERASED`: the act
completed and read nothing; `REFUSED` for the rest). The role refusal of step 1 is not (it never
reaches this code).

**The response that shows the text** *(builder's call)*:
- It is the POST's own response (no redirect to a GET), so the words have no URL, cannot be
  bookmarked, and are not re-fetched by reload without a fresh code (the browser re-POSTs, which
  needs a new TOTP step: the replay CAS refuses the old one).
- `Cache-Control: no-store`, `Pragma: no-cache`, `Referrer-Policy: no-referrer` (A1's headers
  plus `no-store` on this response only).
- `th:text` only (escaped); a test plants `<script>` and HTML in the text.
- The text is never put in the model under a name another fragment could render; the template is
  its own (`admin/entry-text.html`), not a fragment of `report.html`.

The edit question does not arise: a member can report only an entry they were shown (`ReportEntry`,
`canBeReportedBy`), and a shown entry is revealed and no longer editable (`Entry.isEditable`). So
the text read is the text reported, unless its author erased it (`ERASED`).

## 6. Where the code lives

- **`gratitude.api`**: a new port `ReportedEntryText` with one method,
  `read(reportId: UUID, expectedReason: String): ReportedEntryRead`, `@Transactional(propagation =
  MANDATORY)` like every A2 port. `ReportedEntryRead` is a sealed result carrying, for `SHOWN`, the
  entry id, its bond-day date and the text as a type whose `toString` is redacted (reuse
  `EntryText`'s pattern; never a raw `String` field on a data class). The gate follows A2's
  private `ReportModerationStore.gate` (hard-coded `FOR UPDATE`), given a lock-mode parameter or a
  `FOR SHARE` sibling; §4's rule is a function in
  `gratitude.domain` (one place).
  **Locks: the report `FOR SHARE`, then the entry `FOR SHARE`, and no bond lock** *(added while
  building, plan Task 1)*. The withdrawn authors are read through `BondAccess.closingViewOf`,
  which takes no lock, after the entry's lock. The reason is a deadlock: the reporter's own
  account erasure (`EraseDeletedAccount`) takes the bond's lock and then `UPDATE entry_reports SET
  detail = NULL` on the same report, so a read that held the report's share lock and then waited
  for the bond would close a cycle with it. Read after the entry's lock, the view sees any
  withdrawal committed before that statement, as `Reader` reads it after the entry is loaded.
  `ReportedEntryTextRaceTest` holds both orders, including the one that would deadlock.
- **`admin.service`**: `ContentAccess.readReportedEntry(…)`, `@PreAuthorize("hasRole('ADMIN')")`,
  `@Transactional`; it validates, takes the step-up the controller already proved (it never
  steps up itself, so the proof's own transaction is not joined to the read's), calls the port,
  writes the audit row, returns. The caller of `AdminSignIn.stepUp` is the controller, before
  this method is entered (§5 step 3; A2's `ReportsController` → `decideGated` pattern).
- **`admin.web`**: one POST on `ReportsController` (method-level `ADMIN` over the class's
  `MODERATOR`), and the GET's model gains `canReadWords` and `whyNot`.
- **`ArchitectureTest`**: `ReportedEntryText` joins `MODERATED_APIS`; a new rule that no class in
  `admin` other than `ContentAccess` depends on it.

## 7. Audit, V63 and V64

- **V63** (`modules/gratitude/.../V63__gratitude_report_words_read.sql`): widen
  `entry_report_log_action_check` with `WORDS_READ`. The port writes that row (actor
  `admin:<accountId>`, no note: the justification lives only in `audit_log`) for `SHOWN` and
  `ERASED`, never for a refusal. The report page's history and `moyi reports show` print it
  ("words read by admin:…"); the CLI's outcome list learns the action.
- **V64** (`modules/admin/.../V64__admin_entry_text_read.sql`): A2's pattern, drop and re-add
  `audit_log_action_check` with `ENTRY_TEXT_READ` added. Nothing else. `AuditActionCheckTest` holds
  enum and SQL together; a migration test like `V61MigrationTest` for each.
- **The row:** `action = ENTRY_TEXT_READ`, `target_type = ENTRY`, `target_id = <entry id>` (for
  `NOT_ABOUT_WORDS` on a `MEMBER` report, `target_type = REPORT`, `target_id = <report id>`: there
  is no entry), `justification = <the typed text>`, `metadata = {"result": <outcome>, "reportId":
  <id>, "reportDriven": true}`, outcome as §5, `ip_hash` as A1.
- The justification is the admin's words about why, never the entry's words. A test asserts the
  marker text of the entry is in no audit column.
- **Metric:** `moyi.admin.content.read{outcome}`. **Log:** one INFO line per attempt with the
  admin id, report id and outcome, never the justification or the text.

## 8. Concurrency

- Read vs a decision on the same report: the gate's `FOR SHARE` against the decision's `FOR
  UPDATE`. A read that waited sees `NOT_OPEN`; a decision that waited proceeds after the read
  commits. Both orders are tested, synchronised on the lock (never a sleep).
- Read vs the author's erasure: the erasure locks the entry row; the read takes `FOR SHARE` on the
  entry too *(builder's call)*, so it reads either the text or the tombstone, never half.
- Two admins reading at once: both may succeed; each writes its own row.

## 9. Disclosure in the export

- `Disclosure.shows` gains an explicit case: `ENTRY_TEXT_READ` is **withheld** (ruling 2), whatever
  its outcome or metadata. Every A5 read is report-driven by construction, so there is no shown
  case to write yet. The exhaustive `DisclosureTest` grows with it.
- `AdminAccessRows` reads `USER` and `MEDIA` targets; it is **not** widened to `ENTRY` in A5,
  because nothing would be shown. The release review (owed) widens it when it decides what a closed
  case may show. The spec says so, so the next builder does not think it was forgotten.
- The export's one sentence is unchanged; it already covers "access … a safety review requires us
  not to disclose".
- A test: an export built after a `SHOWN` read lists nothing for it, and its bytes equal an export
  built before the read.
- **ADR-0064** records ruling 2 as a departure from FR-112's "MUST", with the reason (the reporter
  is always the partner), the condition (open case), and what is owed (release review, legal check).

## 10. Security

Inherited from A1/A2: separate chain and session, allowlisted host, CSRF, the role hierarchy, TOTP
with lockout and replay CAS. Added by A5:
- Per-read second factor with no window (§5 step 3).
- The text only in the POST response, `no-store`, its own template, escaped.
- The runbook (`takedown.md` §3) is amended: once the console is on, the words are read only here;
  until then, the `psql` rule stands unchanged.

## 11. Tests and mutation targets

- Role: `MODERATOR` and `SUPPORT` refused (403 page, no text, no `ENTRY_TEXT_READ` row), including
  a hand-made POST.
- Justification: empty, 401 characters, NUL → 422, no row, no read.
- Second factor: missing → the code field; wrong → 401, `ADMIN_STEP_UP` `REFUSED`; a code used for
  a block a moment ago → refused (replay CAS); a fresh `secondFactorAt` without a code → refused.
- Each outcome of §5, from a real report through `ReportEntry` (not inserted rows), including an
  image-reason report refused and then read after a reclassify, and `ERASED` for a withdrawn
  author whose text the outbox has not erased yet.
- A failed read transaction (forced) leaves the `ADMIN_STEP_UP` row committed and the code spent.
- `entry_report_log` gains `WORDS_READ` for `SHOWN` and `ERASED`, and nothing for a refusal.
- The words: `ModerationPrivacyTest` extended. The marker appears in exactly one response body and
  in no log output, audit column, metric, flash, other page, or `toString`. Escaping of
  `<script>`. The response headers.
- Concurrency: both orders of §8, on locks.
- Export: §9's byte-equality test.
- Mutation targets: remove the role check; reuse the step-up window; write the audit row after the
  render; drop `NOT_ABOUT_WORDS` for image reasons; `FOR SHARE` → no lock. Each must turn a test
  red.
- Smoke: the console is off in the smoke run, as for A2; `/admin/reports/{id}/entry-text` answers
  what any unknown path answers.

## 12. State map

Rows in a new `docs/state-map/data/admin.json` region or in the report rows *(builder's call:
follow where A2's console rows sit; there are none yet, so the builder decides and says so)*: one
per outcome of §5, with its test as evidence.

## 13. Operations

`ops/admin/README.md`: the new metric; reading the words is `ADMIN` only, so inviting a
`MODERATOR` does not give it. `takedown.md` §3 as §10 says.

## 14. Owed after A5

- The release review of withheld rows (A2 ruling 1, A5 ruling 2), with the legal check.
- A3 (suspension), then A4 (jobs, flags, support inbox).

## 15. What the builder check changed

A fresh reader planned from the draft and asked four questions:
1. **A withdrawn author's text still in the row** (the outbox erases it later): now `ERASED`, read
   as the member path reads it (§5 step 4). Whether a report should hold the text against erasure
   went to the owner: no (ruling 4).
2. **Where a role refusal's audit row is written**: nowhere; Spring refuses before our code (§5
   step 1).
3. **Whether the report's own history records the read**: yes, `WORDS_READ` in `entry_report_log`
   (V63), so the migration is no longer admin-only.
4. **Which transaction the step-up runs in**: its own, before the read's, as in A2; a read is two
   audit rows (§2, §5 step 3).
