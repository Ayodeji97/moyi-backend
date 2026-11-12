# ADR-0063 — The admin console (A2): the report queue and quarantined media behind the console, the same functions underneath, and an export that discloses only what cannot become a tip-off

**Status:** Accepted, provisional where marked · **Date:** 2026-11-12 · **Deciders:** made without
the owner where marked *(builder's call)*, his to revisit; the six owner questions of the spec were
ruled by Daniel on 2026-11-11 (below). · **Amends:** ADR-0055 (A2 done; the user lookup becomes a
POST with a purpose), ADR-0059 decision 8 (implemented), ADR-0051 and ADR-0053 (the console now
calls their functions)

## Context

ADR-0051 (moderation, quarantine, V36) and ADR-0053 (the report route, V38, V39, V48) put every
moderation rule in a database function and gave the operator `scripts/moyi` to call them over SSH,
with `MOYI_OPERATOR` as a free-text name. The rules were right; the record of who acted was what
was typed, tied to no second factor. ADR-0055 built the console's sign-in, roles and append-only
`audit_log` (A1) and left the report queue to A2.

The spec (`docs/superpowers/specs/2026-11-11-admin-console-a2-design.md`) holds the detail. It was
reviewed twice before the build (FIX FIRST, seven findings; then six more), and each task of the
build was reviewed independently; several decisions below were made in those reviews, and the
commit that made each one says why. This record is what was built.

FR-113's first half, FR-100's "an admin queue item", FR-101's "quarantined pending review" and
FR-116 are served. FR-112 (content access, entry text) is not: it is A5's.

## Decision

### The shape

**1. The functions stay the single place the rules live.** No database function is changed and
none is added. The console adds four things around each call: a role check
(`@PreAuthorize("hasRole('MODERATOR')")` on every `ModerationConsole` method, so `ADMIN` passes
through A1's hierarchy and `SUPPORT` does not), a second factor for the irreversible acts, an
`audit_log` row in the same transaction, and an actor string that is the admin's account id,
`admin:<accountId>`, written into `entry_report_log` and `media_moderation_log` as the functions'
own `actor`. A console decision and the same decision through `scripts/moyi` have the same
effects, actor aside; `ConsoleMatchesScriptTest` decides seven decisions both ways on twin
databases and compares the rows.

**2. Two admin-only ports** *(builder's call: the admin module calling `entry_report_dismiss` itself
would reach into gratitude's schema around its `api`)*. `gratitude.api.ReportModeration` and
`media.api.QuarantineModeration`, each implemented in its own module's `infra` with `JdbcClient`
calling the function. Every decision method is `@Transactional(propagation = MANDATORY)`: it joins
the console's audited transaction or refuses to run. `QuarantineModeration.ownedBy` alone is
`SUPPORTS`, because the export build that calls it holds no transaction. ArchitectureTest lets
`modules/admin` import from `gratitude.api` and `media.api` only these two ports and their types
(wildcard imports included), and lets no other module import them. Tests that call the console's
services live in `modules/admin`, because the console is `internal` there.

- **`QuarantineModeration` returns `QuarantineDecision(outcome, reportDriven)`**: the function's
  outcome, as a closed enum, and whether a report drove it, read from the asset's row under the
  same lock as the act (spec §9.2).
- **`ReportModeration.reviewableReasons` returns `ReasonsReview { REVIEWABLE, PRIORITY_REPORT,
  CONSENT_REPORT }`**, not a boolean, so the view can audit why it refused. Priority wins: a set
  holding both a `MINOR_SEXUAL_CONTENT` and a `NON_CONSENSUAL` report is `PRIORITY_REPORT`.
- **`ReportModeration.reasons()` is the one list of report reasons**, answered from
  `ReportReason.entries` in their declared order; the reclassify form reads it, and a test holds
  each form's options to the schema's own `CHECK` on `entry_reports.reason`.
- **Outcome enums are held to the functions' `RETURN` literals both ways**, and an answer the
  enum does not know fails loudly, naming the function and never the report or the asset.

**3. Both port stores are `@Component`, not `@Repository`.** With JPA in the application,
`@Repository`'s exception translation wrapped the ports' own `IllegalStateException` and
`IllegalArgumentException` in `InvalidDataAccessApiUsageException`. Media's own tests, which have
no JPA, never saw it; a reviewer's probe did.

**4. Two refusals are answered, not thrown, so that the console's `REFUSED` audit row still
commits.** A throw inside a `MANDATORY` participant marks the console's transaction rollback-only,
and the row recording the refusal would be lost with it.
- `ReclassifyOutcome.UNKNOWN_REASON`: a reason outside the five, answered before any SQL.
- `ReviewRefusal.UNAVAILABLE`: the object store is down when a review copy is opened (audited
  `REFUSED/unavailable`, not a `500` that rolls the look back).

**5. V61 only widens `audit_log_action_check`**, to A1's six actions and A2's eleven (17).
No column: what the export discloses is derived from the row when it is built (decision 12), through
V60's `audit_log_target_idx`. It is additive; a rollback leaves rows the old code never reads.

### Concurrency

**6. Every report decision carries the reason the page showed.** The port takes the report
`FOR UPDATE` (report first, then assets, the functions' own order, so no new deadlock), and answers
`NOT_OPEN`, or `STALE` if the reason changed, without calling the function. Without it, a
`MINOR_SEXUAL_CONTENT` report reclassified to `OTHER` under moderator A's page could be blocked by
A on the old page, and a block is final.

**7. Quarantine delete and block decide under the asset's row lock** (spec §6.3, §8). In one
transaction: `SELECT … FOR UPDATE` on the asset; `BLOCKED` or `evidence_held` → the port's
`EVIDENCE_SERVER_ONLY` (delete only); any hold in `media_report_holds` → `REPORT_HELD` (delete and
block); only then the function. The first draft read the asset unlocked and then called
`media_quarantine_delete`, which matches `status = 'QUARANTINED' OR evidence_held` and clears the
hold itself, so a block committing between the read and the call would have had its fresh evidence
deleted by the console.

**8. The view takes share locks, report first, asset second** (spec §7.4): the holds read unlocked;
the holding reports `FOR SHARE` and gratitude's half of the rule; the asset `FOR SHARE`, media's
half, and the holds re-read (a different set is `changed`). Locking the asset first would have
formed a cycle with `entry_report_uphold`. Each race has a `pg_locks`/`pg_stat_activity`-synchronised
test with a deadline, so a mutated lock turns a test red instead of hanging the build.

### What a moderator may see

**9. No entry text, by any page.** A report about words is decided from the reporter's detail
(owner ruling 2). The reporter's detail is shown on the open report's page only, to `MODERATOR` and
above, and is in no log line, audit row, metric or flash.

**10. The view rule has two halves, each in the module that owns its facts** *(builder's call)*:
`ModeratorReview.allows` in `media.domain` (never `BLOCKED` or `evidence_held`, never purged, only
held back or held by a report) and `ReportReason.mediaReviewable` in `gratitude.domain` (false for
`MINOR_SEXUAL_CONTENT` and `NON_CONSENSUAL`, owner ruling 3). The console composes them.

**11. The review copy** (`GET /admin/media/{mediaId}/review`):
- **streamed through the application**, never a presigned URL that could be copied out of the page;
- served as **the variant's own type** (`display.webp` as `image/webp`; `voice.m4a` as `audio/mp4`,
  else `voice.ogg` as `audio/ogg`), never the upload's declared type; a variant missing from the
  store is the refusal `missing`;
- `Cache-Control: no-store` **comes from A1's chain writer**, not set again here; the controller adds
  `inline`, `nosniff` and `Cross-Origin-Resource-Policy: same-origin`, and the CSP gains
  `media-src 'self'`;
- its `MEDIA_VIEWED` row **commits before the first byte**. The copy is **closed** if the audit
  write fails, and on any transaction completion that is not `COMMITTED` (a commit that fails in the
  driver ends `STATUS_UNKNOWN`), so no stream outlives a look that was not recorded;
- the single-asset quarantine page answers `NOT_FOUND` for an asset that was never held (neither
  quarantined, nor evidence, nor report-held), the unknown id's own refused row;
- **a `HEAD` request also writes `MEDIA_VIEWED`.** That over-records a look that sent no bytes; it
  is accepted, because the log is meant to over-record rather than under-record (T-10);
- every refusal is the same admin `404` page, byte for byte. The refusal codes are the spec's
  (`blocked`, `priority_report`, `consent_report`, `not_held`, `purged`, `missing`, `changed`) and
  two more: **`unavailable`** (decision 4) and **`wrong_origin`**. `wrong_origin` covers a forged
  report or quarantine origin (a report's page naming a report that does not hold the asset, or the
  quarantine page for an asset a report holds); it is refused before any lock, and its row carries
  no `via` and no `reportId`, so a forged id is never written as if it had been the origin.

### The audit rows, and disclosure

**12. An uphold writes one `REPORT_UPHELD` row**, its metadata `{"result", "takedown", "media":
[{"id", "outcome"}]}`, and no `MEDIA_*` row for the assets it took down. One decision, one row
(spec §9.2); the first build wrote one `MEDIA_*` row per asset as well.

**13. Disclosure is one pure function of the row,
`Disclosure.shows(action, outcome, purpose, reportDriven, targetType)`** in `admin.domain`, and
`AdminAccessDisclosure` implements `export.api.AdminAccessLog` with it, unconditionally (a
persistence bean, ADR-0055 ruling D), `@Transactional(readOnly = true)`. Shown: a successful
`USER_LOOKED_UP` for `MEMBER_REQUEST` or `OPERATIONS` (as `ACCOUNT_LOOKED_UP`), and a successful
`MEDIA_DELETED` that no report drove (as `MEDIA_REMOVED`). Everything else is withheld, and:
- `USER_LOOKED_UP` is shown only when the target is `USER`, and `MEDIA_DELETED` only when the target
  is `MEDIA`, so a row written against the wrong kind of target is withheld;
- unknown or malformed metadata (a purpose that is not one of the four, a `reportDriven` that is not
  a JSON boolean) is withheld, never thrown: it cannot fail an export build;
- `reportDriven == null` is withheld;
- the export never carries a `justification`;
- a shown removal stays true only if it is never followed by a block. *Amended by V62
  (2026-11-12):* `media_quarantine_block` itself answers `GONE` for a `DELETED` asset that no
  report holds, purged or not, so the console and the script are held to it alike, and the port no
  longer repeats the check. The rule is "only while a report holds it", not the "refuse `DELETED`
  unless `evidence_held`" this ADR first owed: that would also have refused the report's
  `uphold … block` of what its author erased while the report held it, which the runbook promises
  to keep (takedown.md §3). `media_report_takedown` now blocks before it lets its own hold go, so
  the block sees the report.
The archive's statement is one sentence for everyone, whatever was withheld (spec §9.3).

**14. The user lookup is now `POST /admin/users`** (`q`, `purpose`, `note`, CSRF). This departs
from A1 spec §9's `GET ?q=`: with a GET, a `SAFETY`/`LEGAL` note, or the member's email, would sit
in the URL, and so in kamal-proxy's request log and the browser's history. `GET /admin/users?q=`
only pre-fills the form; it reads and audits nothing (`UserLookupTest` pins it). The purpose is one
of `MEMBER_REQUEST`, `OPERATIONS`, `SAFETY`, `LEGAL`, recorded as `metadata.purpose`; `SAFETY` and
`LEGAL` need a 1–400 character note, written only to `justification` (owner ruling 6).

### The step-up

**15. An uphold, a quarantine block and a quarantine delete need a code proven in the last five
minutes** *(builder's call)*, enforced by the service, not the controller: the irreversible
`ModerationConsole` methods take `secondFactorAt: Instant?` and apply `StepUp.isFresh` against the
injected `Clock`, throwing `StepUpRequired` before anything is read or written. Sign-in's second
factor counts.
- **`admin.secondFactorAt` is cleared by every new sign-in establishment, and only an accepted code
  writes it.** `changeSessionId()` keeps every attribute, so without the clearing an admin who
  passed only the password step in a session another admin had completed would inherit that
  admin's proof.
- **Step-up shares one `verifyCode` with sign-in**: the same row lock, lockout count and replay
  compare-and-set on `totp_last_step`, so a code is spent once across both.
- Every step-up writes `ADMIN_STEP_UP`, `SUCCEEDED` or `REFUSED`, never the code.

### The rest of the builder's calls in the spec

**16.**
- **Plain forms with POST-redirect-GET, no htmx.** Nothing needs partial updates, and a page that
  works without script is one fewer thing for the CSP and the tests. A report decision returns to
  the report's page; a quarantine decision returns to the quarantine queue, since a deleted asset
  has no page to return to. The result line is in the flash, chosen from the outcome, never the note.
- **No "overdue" flag.** The review thresholds live in `ops/monitoring/media-check.sh` as the alarm's
  configuration; the pages show each item's age in days, and the alarm stays the one thing that says
  "too old".
- **The home page's open counts come from two non-logging count methods**, so a visit to the home
  page writes no `LISTED` and no audit row.
- **A note is 1 to 400 characters** (code points, after trim, no NUL), checked before any port call:
  `entry_report_uphold` writes `left(action || ': ' || note, 500)` and `_reclassify` prefixes two
  reason names, so 400 keeps the whole note inside the log's 500-character `CHECK`, and a note that
  failed that `CHECK` would have been echoed in pgjdbc's server detail.
- **`BLOCKED` evidence is deleted only on the server** (runbook §2 step 4), as A1 requires the server
  to create an admin: a stolen session cannot end a legal hold. Blocking, which keeps evidence, is in
  the console, listed first on the quarantine page (owner ruling 4).
- **Console ≡ script cross-checks** (decision 1).

### The owner's rulings (2026-11-11), as decided

The owner adopted all six recommendations of spec §14 after asking for them to be scrutinised; the
scrutiny kept four as written and tightened two (spec §14a).

1. **Disclosure:** show only member-request and operations lookups and moderation removals, with one
   sentence for everyone. The legal checks stay open (below), and the console stays off until they
   are done. Added: a withholding is justified only while its case is open, so withheld rows are owed
   a review for release once the case closes (Owed).
2. **Reports about words** are decided from the reporter's detail; reading entry text at `psql`
   stops when the console is switched on (runbook §3.3, amended); A5 is built before A3.
3. **`NON_CONSENSUAL` and `MINOR_SEXUAL_CONTENT` media is never shown** in the console.
4. **`BLOCKED` evidence is deleted only on the server**; blocking stays in the console.
5. **One moderator decides alone**; a second pair of eyes on `BLOCK` is revisited when a second
   moderator is invited.
6. **Lookup purposes**, four, tightened: a `SAFETY` or `LEGAL` lookup requires a typed note (1–400
   characters) in `justification`, so a hidden lookup always says why and an `ADMIN` can check it.
   `MEMBER_REQUEST` and `OPERATIONS` take no note. The note is never disclosed.

## Consequences

**Positive:** every look at the queues and every decision names an account that passed two factors,
in a log nobody can edit, in the same transaction as the decision. The functions, and so the rules,
are unchanged; the script and the console cannot drift apart. A moderator can block suspected child
abuse material as evidence without the server. T-10's disclosure seam (ADR-0059 decision 8) is
filled, so an export no longer says "no access is recorded" while lookups are.

**Negative:** T-10's "access disclosed" is met only in part: views and releases, the very acts T-10
is about, are withheld by default, because a view disclosed and later followed by a block tips the
uploader off, and one withdrawn after a block tips them off by its disappearance. A false report of
non-consensual imagery removes a photo nobody looked at. A moderator could pick `SAFETY` to hide a
lookup; the note it requires is the only check while there is one operator. The step-up costs a
code for each burst of irreversible acts. A `HEAD` on a review copy is recorded as a look.

**Neutral:** production is unchanged: `MOYI_ADMIN_ENABLED` stays `"false"`, and V61 runs on deploy
and only widens a `CHECK`. `scripts/moyi` stays, as the path while the console is off and for the
acts A2 leaves on the server (holding or blocking an asset outside the queue by id; deleting
`BLOCKED` evidence).

## Alternatives considered

- **The admin module calling the functions itself.** It would reach into two modules' schemas
  around their `api`s. Rejected for the ports.
- **A `disclosed_to` column set when each row is written.** `audit_log` is append-only, so a
  disclosure could never be withdrawn; and deciding at build time and dropping rows after a block
  tips off by disappearance. Rejected for decision 13's rule over the row alone.
- **A presigned URL for the review copy.** It can be copied out of the page and used elsewhere for
  its lifetime. Rejected for streaming.
- **Deleting `BLOCKED` evidence from the console.** One act both irreversible and a legal step.
  Rejected (owner ruling 4) until a two-person rule exists.
- **A step-up on every irreversible act, no window.** The replay compare-and-set would make a
  moderator wait 30 seconds between items. Five minutes was chosen.
- **The lookup as `GET ?q=`, as A1 built it.** Puts a note or an email in proxy logs and history
  (decision 14).

## Open questions (the owner's)

1. **[legal: confirm]** whether withholding is lawful against GDPR Art. 15 (an Art. 23 restriction,
   or the national law implementing it), and whether DSA Art. 16(5) (telling the reporter the
   decision) and Art. 17 (a statement of reasons to the author of removed content) apply to the
   report flow. Owed before real members use the console; **the console stays off until then.**
2. **The export's statement.** It says access is listed "except access the law or a safety review
   requires us not to disclose". But routine views and releases are withheld too, by the default of
   ruling 1, and not because the law or a safety review requires it. Whether that wording is a fair
   notice of what is withheld is marked **[legal: confirm]** in the spec, and is the owner's to settle
   with the legal check above.
3. **The support inbox** (FR-102, FR-113's second item) has no slice; it is served by `moyi support`
   (ADR-0057). Recommended: fold it into A4, or a slice of its own after A5 (spec §15).

## Owed

- **The legal checks** of open questions 1 and 2. The console is not switched on before them.
- **Q1's release review:** withheld rows are owed a review for release once their case closes (owner
  ruling 1). Not built in A2.
- **A5** (content access with a typed justification, FR-112), before A3 (owner ruling 2); then A3,
  A4, and the `audit_log` retention job (ADR-0055, Owed).
- ~~The script path can still block a DELETED, unpurged asset after a console removal~~: closed
  by V62 (decision 13, amended).
- **The owner:** switching the console on stays as `ops/admin/README.md` says; inviting a moderator
  is `admin_invite(email, 'MODERATOR')` there.

## Revisit when

A second moderator is invited (a second pair of eyes on `BLOCK`, ruling 5; a two-person rule for
deleting evidence, ruling 4). The legal checks answer (the disclosure rule is `Disclosure.shows`, one
place). A hash list is wired, so that `BLOCKED` arrives before any human look. A5's justification
machinery exists (a justified view of a `NON_CONSENSUAL` report's media, ruling 3).
