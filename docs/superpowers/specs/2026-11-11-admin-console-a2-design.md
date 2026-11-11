# Admin console A2: the report queue and quarantined media (MODERATOR)

**Status:** Draft, written 2026-11-11 without the owner. Daniel asked for the backend work to
continue autonomously. Every decision the corpus did not already make is marked *(builder's
call)*. The questions only the owner can answer are in §14, each with a recommended answer.

**Requirements served:** FR-113's first half ("view the report queue, resolve reports"), FR-100's
"Reports create an admin queue item", FR-101's "failures are quarantined pending review", and
FR-116 (every admin write audited). T-08 (illegal imagery: "quarantine + review queue") and T-10
(an admin reads what members wrote) are the threats. FR-112 (content access) is **not** served:
it is A5's (§2).

**Corpus this builds on:** the A1 spec (`2026-11-06-admin-console-a1-design.md`, §11: "A2: the
report queue and quarantined media (`MODERATOR`), wrapping ADR-0051's and ADR-0053's functions so
each decision carries the operator's id"), ADR-0055 and its owner rulings of 2026-11-11, ADR-0051
(moderation, quarantine, V36), ADR-0053 (the report route, V38, V39, V48), ADR-0059 decision 8
(the export's `AdminAccessLog` seam), ADR-0062 (kamal-proxy trusted by name), doc 09 §4 (roles:
"`MODERATOR` (+ report queue, quarantined media), `ADMIN` (+ content access with
justification)"), and `ops/moderation/takedown.md`, which this slice moves from `psql` into the
console without changing a single rule.

## 1. Why this slice, and what it changes

Today every look at the report queue and every moderation decision is `scripts/moyi` on the
server, which calls a database function with `MOYI_OPERATOR` as a free-text name. The rules are
right (they live in the functions, tested against a migrated database), but the record of who
acted is whatever name was typed, and nothing ties it to a second factor.

A2 puts the same functions behind the console. **The functions stay the single place where the
rules live.** The console adds four things around each call: a role check, a second factor for
the irreversible ones, an `audit_log` row in the same transaction, and an actor string that is
the admin's account id rather than a typed name. It adds no new moderation rule, with one
exception that is a privacy rule, not a moderation rule: what a moderator may *see* (§7).

## 2. Goals, non-goals, anti-goals

**Goals**
- A `MODERATOR` (or `ADMIN`) sees the open reports, priority first, and the quarantine queue, and
  decides each item with the same outcomes `moyi reports` and `moyi media quarantine` give today.
- Every decision is in `audit_log` with the admin's id, the note as `justification`, the
  function's outcome, and it commits with the decision or not at all.
- The log rows the functions write themselves (`entry_report_log`, `media_moderation_log`) name
  the admin as `admin:<accountId>`, so the module logs and the audit log agree on who acted.
- A moderator can look at a held-back photo or voice note **when the runbook says to look**, and
  is refused, by the server, when it says never to (child sexual abuse material).
- Each such look is audited, and it is disclosed to the person whose media it was, in their data
  export (T-10, ADR-0059 decision 8), unless the law's tipping-off concern applies (§9, §14 Q1).
- Two moderators deciding one item at once cannot both succeed, and neither can decide on a page
  that has gone stale under them (§8).

**Non-goals (other slices)**
- **Entry text.** No page in A2 shows a word of an entry. A report about words is decided from
  the reporter's own detail (§7.3). Reading the words is content access, FR-112: `ADMIN`, a typed
  justification, disclosed in the export. That is A5.
- The support inbox (FR-102, FR-113's second item; `moyi support`, ADR-0057). See §15: no slice in
  the A1 spec's list owns it yet.
- Suspension (A3), job status and flags (A4), audit retention.
- Holding or blocking an asset by id (`moyi media quarantine hold|block`): the path for a report
  that arrived by email. It needs the support inbox to arrive from, so it stays on the script.
- Deleting `BLOCKED` evidence after the authority's report (runbook §2 step 4). It stays on the
  server (§6.3, §14 Q4).
- htmx. The A2 pages are plain forms with POST-redirect-GET and work with no script at all
  *(builder's call: nothing here needs partial updates, and a page that works without script is
  one fewer thing for the CSP and the tests)*.

**Anti-goals: it fails even if it works when any of these happen**
- A console page, a log line, an exception message, a metric label or an audit row carries entry
  text, the reporter's detail (outside the one page that shows it to the moderator), a storage
  key, a presigned URL, or an email.
- A `BLOCKED` asset, or one held by an open `MINOR_SEXUAL_CONTENT` report, is ever served to a
  browser, by any route, to any role.
- A decision takes effect without its audit row, or its audit row exists without the decision.
- A moderator's decision differs in any effect from the same decision made with `scripts/moyi`.
- `SUPPORT` reaches any of it.
- The console becomes reachable in production by this slice: it ships switched off, as A1 did.

## 3. Shape

- **Module:** `modules/admin` gains the service, controllers and pages. It reaches the two
  domain modules only through their `api` packages, as every module does.
- **Two new admin-only ports**, in the shape of A1's `UserAdminLookup`/`BondAdminLookup`, and
  added to the same ArchitectureTest rule so only `modules/admin` may import them:
  - `gratitude.api.ReportModeration`: the report queue, wrapping V39/V48's `entry_report_*`.
  - `media.api.QuarantineModeration`: the quarantine queue, wrapping V36/V38's
    `media_quarantine_*`, plus the review copy of an asset (§7).
  Each is implemented in its own module's `infra` with `JdbcClient` calling the function, and
  each method is `@Transactional(propagation = MANDATORY)`: it joins the console's audited
  transaction or refuses to run, as `MediaReports.holdFor` does for the report route. *(Builder's
  call: the admin module calling `entry_report_dismiss` itself would be reaching into gratitude's
  schema around its `api`, which `CLAUDE.md` forbids.)*
- **Dependencies:** `modules/admin` gains `gratitude`, `media` and `export` (for the disclosure,
  §9). None of them depends on `admin`, so no cycle; the build's module rules are checked in
  plan Task 1.
- **Isolation level:** `media_quarantine_delete` and `media_publish` refuse to run above READ
  COMMITTED, because they write the outbox's two inserts. The console's transactions use the
  default, which is Postgres's READ COMMITTED; a test pins it (plan Task 5), so a later
  `@Transactional(isolation = …)` cannot silently break every delete.

## 4. Roles

`@PreAuthorize("hasRole('MODERATOR')")` on every `ModerationConsole` method, the service, not
only the URL. Through A1's `RoleHierarchy`, `ADMIN` passes and `SUPPORT` does not. A `SUPPORT`
session gets A1's admin `403` page on every A2 route, and the service refuses even if a
controller forgot its check (a test calls the service directly with a `SUPPORT` context).

No action in A2 is `ADMIN`-only. The one that would deserve it, deleting held evidence, is not
in the console at all (§6.3).

## 5. Pages and flows

All pages are server-rendered Thymeleaf under A1's chain: allowlist first, both factors, CSRF on
every POST, `Cache-Control: no-store`, `th:text` only. The navigation gains "Reports" and
"Quarantine" for a moderator; `home.html` shows each queue's open count and how many are overdue.

### 5.1 Reports

- `GET /admin/reports`: the open reports, from `entry_report_list`. Columns, in the function's
  order: priority, received, reason, target (`ENTRY` or `MEMBER`), how many assets it holds,
  whether a detail exists, and **overdue** (open longer than `REPORT_REVIEW_DAYS`, 2, the alarm's
  threshold, read from one constant the page and `ops/monitoring/media-check.sh`'s default both
  name in a comment). A priority row is marked `DO NOT VIEW — runbook §2` in words, not colour
  alone (NFR-100). Entry and member ids are shown; nothing else about either.
- `GET /admin/reports/{id}`: one report, from `entry_report_read`, which logs the `READ`. Shown:
  reason, target, entry id or reported member id, bond id, received, status, **the reporter's
  detail** (§7.2), each held asset (id, kind, status, verdict, and whether it may be viewed, §7),
  and the report's history from `entry_report_log` (when, who, what, note). An unknown id, or a
  value that is not a UUID, is the admin `404` page and writes no `READ`.
- The decision forms on that page (each a POST, each with a required note of 1 to 400 characters,
  and each carrying the reason the page was rendered with, §8):
  - **Dismiss** → `entry_report_dismiss`.
  - **Reclassify** to another of the five reasons → `entry_report_reclassify`.
  - **Uphold, delete** → `entry_report_uphold(…, 'DELETE', …)`. Not offered on a
    `MINOR_SEXUAL_CONTENT` report (the function would answer `RECLASSIFY_FIRST`).
  - **Uphold, block** → `entry_report_uphold(…, 'BLOCK', …)`. Offered on every report; on a
    priority report it is the only uphold, labelled "Block as evidence, then runbook §2".
  - A report that holds nothing (a report of words, a member report) shows one "Uphold" button
    that records the decision: `DELETE` for a non-priority report, `BLOCK` for a priority one,
    exactly what runbook §3.3 tells the operator to type today.
- Each POST redirects to the report's page with a one-line result in the flash scope, for example
  "Dismissed. 1 photo given back." or "Already decided by someone else." The result text is chosen
  from the function's outcome, never built from the note.

The 400-character note *(builder's call)*: `entry_report_uphold` writes `left(action || ': ' ||
note, 500)` and `entry_report_reclassify` prefixes two reason names; 400 keeps the whole note
inside the 500 the log's `CHECK` allows, so nothing the moderator wrote is cut.

### 5.2 Quarantine

- `GET /admin/quarantine`: from `media_quarantine_list`. Columns in the function's order:
  verdict, media id, kind, status, reason, attached, held since, open reports holding it, and
  overdue (`QUARANTINE_REVIEW_DAYS`, 3). A `BLOCKED` row reads `DO NOT VIEW — runbook §2`; a row
  held by a report links to that report and says it is decided there.
- `GET /admin/quarantine/{mediaId}`: the asset's row (no log of its own: the list's `LISTED` and
  the audit row cover the look), the reports holding it, its history from
  `media_moderation_log`, and the "View" link when §7 allows.
- Decision forms (POST, required note, as above):
  - **Release** → `media_quarantine_release`. Offered when the verdict is `QUARANTINED` and no
    open report holds it.
  - **Delete** → `media_quarantine_delete`. Offered when the verdict is `QUARANTINED`. **Never for
    `BLOCKED`** (§6.3).

### 5.3 The review copy

`GET /admin/media/{mediaId}/review` streams the bytes a moderator needs to decide (§7). It is the
only A2 route that serves member content.

## 6. Exactly what each action calls

The console's actor string is `admin:<accountId>` (42 characters, inside both logs' 1–100
`CHECK`). Every decision is one transaction: the port call (which runs the function) and the
`audit_log` row.

### 6.1 The table

| Console action | Port method | Database function | Outcomes (as the function returns them) | Audit `action` | Step-up (§10.2) |
|---|---|---|---|---|---|
| Report queue | `ReportModeration.queue` | `entry_report_list(actor)` (logs `LISTED`) | rows | `REPORT_QUEUE_VIEWED` | no |
| Report page | `ReportModeration.read` | `entry_report_read(id, actor)` (logs `READ`) + a plain read of `entry_report_log` for the history | rows, or none | `REPORT_READ` | no |
| Dismiss | `ReportModeration.dismiss` | `entry_report_dismiss(id, actor, note)` → `media_report_release` | `DISMISSED`, `NOT_OPEN`; console's `STALE` | `REPORT_DISMISSED` | no |
| Reclassify | `ReportModeration.reclassify` | `entry_report_reclassify(id, reason, actor, note)` | `RECLASSIFIED`, `SAME_REASON`, `NOT_OPEN`; `STALE` | `REPORT_RECLASSIFIED` | no |
| Uphold | `ReportModeration.uphold` | `entry_report_uphold(id, 'DELETE'\|'BLOCK', actor, note)` → `media_report_takedown` → `media_quarantine_delete` / `_block` / `media_report_let_go` | per asset: `DELETED`, `BLOCKED`, `ALREADY_BLOCKED`, `BLOCKED_KEPT`, `GONE`, `NOT_QUARANTINED`; or `NOT_OPEN`, `RECLASSIFY_FIRST`; `STALE` | `REPORT_UPHELD` | **yes** |
| Quarantine queue | `QuarantineModeration.queue` | `media_quarantine_list(actor)` (logs `LISTED`) | rows | `QUARANTINE_QUEUE_VIEWED` | no |
| Release | `QuarantineModeration.release` | `media_quarantine_release(id, actor, note)` → maybe `media_give_back` | `RELEASED`, `REMODERATED`, `NEVER_RELEASED`, `REPORT_HELD`, `NOT_QUARANTINED` | `MEDIA_RELEASED` | no |
| Delete | `QuarantineModeration.delete` | `media_quarantine_delete(id, actor, note)` | `DELETED`, `NOT_QUARANTINED`; console's `EVIDENCE_SERVER_ONLY` | `MEDIA_DELETED` | **yes** |
| View | `QuarantineModeration.reviewCopy` | none (a read of `media_assets` and the object store) | a copy, or refused (§7) | `MEDIA_VIEWED` | no |
| Step-up | `AdminSignIn.stepUp` | none (A1's TOTP check and step CAS) | accepted, refused | `ADMIN_STEP_UP` | — |

No function changes. No new function. The ports return the function's outcome strings as a
closed Kotlin enum per function, and an outcome the enum does not know fails loudly (a test
holds each enum to the strings in the function's source, as `AuditActionCheckTest` does for V60).

### 6.2 What `STALE` is

The console's own refusal: the report's reason is no longer the one the moderator's page showed
(§8). Checked under the report's row lock, before the function is called.

### 6.3 Why `BLOCKED` evidence cannot be deleted from the console *(builder's call; §14 Q4)*

`media_quarantine_delete` on a `BLOCKED` asset ends a legal hold and destroys what may be the
only copy of evidence the runbook says to keep until the authority directs otherwise. It is the
one act in the queue that is both irreversible and a legal step. Requiring the server for it, as
A1 requires the server to create an admin, means a stolen session, however obtained, cannot do
it. The console says where the step is ("runbook §2 step 4, on the server") and the service
refuses it (`EVIDENCE_SERVER_ONLY`, audited `REFUSED`) even if a form is forged.

## 7. What a moderator sees, and what they never see

Privacy is the product's promise, so this section is the slice's centre.

### 7.1 Never

- **Entry text.** The ports return no text and no type that carries it. The ArchitectureTest
  rule gains a clause: `modules/admin` may import from `gratitude.api` only `ReportModeration` and
  its own DTOs, so no entry-reading type can be reached by accident.
- **Who anyone is.** No email, no display name, no user id of a reporter or an author. The pages
  show bond, entry, member and media ids. If a moderator needs the account (a law-enforcement
  request), that is the `SUPPORT` lookup, audited separately.
- **A storage key or a URL.** The review copy is streamed through the app (§7.4), never a
  presigned link that could be copied out of the page and used elsewhere for its lifetime.
- **`BLOCKED` media, and media held by an open `MINOR_SEXUAL_CONTENT` report**, by any route.

### 7.2 The reporter's detail

Shown on the report's page only, to `MODERATOR` and above, because it is written to the person
who reviews (ADR-0053 decision 7: "the operator's and nobody else's"). Reading it is logged twice:
`entry_report_read` writes `READ` in `entry_report_log`, and the console writes `REPORT_READ` in
`audit_log`. It is never in a log line, an audit row, a metric or a flash message. Every decision
empties it (the functions do; V39's `CHECK` holds it), so a decided report's page shows "Emptied
when decided."

### 7.3 Reports about words

A report whose reason or detail is about what the partner wrote (harassment, a threat) holds no
media, and A2 shows no entry text. The moderator decides from the reporter's detail, as the
member report (ADR-0053 decision 14) is already decided. The page says so plainly: "The entry's
words are not shown here. Reading them is content access (FR-112), which needs the `ADMIN` role
and a typed justification." This differs from runbook §3.3, which lets the operator read the
words at `psql` "only to decide a report that names them". §14 Q2 asks the owner to close that
path once the console is on, and §15 records the disagreement.

### 7.4 Media: when a moderator may look

The rule has two halves, each living in the module that owns its facts *(builder's call; both
halves from the runbook)*:

- **Media's half**, `MediaAsset.reviewableByModerator` in `media.domain`: never when the verdict
  is `BLOCKED` or `evidence_held` is set; only when its objects still exist (`purged_at` is null);
  and only when it is held back (`status = 'QUARANTINED'`) or held by an open report (a deleted
  asset under a report's legal hold, ADR-0053 decision 8a, which the runbook's §3.3 asks the
  operator to look at).
- **Gratitude's half**, `ReportReason.mediaReviewable` in `gratitude.domain`, beside `priority`:
  false for `MINOR_SEXUAL_CONTENT` (runbook §2 and §3.1: never viewed, at any step) and for
  `NON_CONSENSUAL` (runbook §3.2: decided on the reporter's word; looking at someone's intimate
  image to confirm it was shared without consent is the harm itself, §14 Q3).
- **The console composes them:** an asset may be viewed only if media's half says so **and**
  every open report holding it has a reviewable reason. The asset's own row and the reports
  holding it are read in the view's transaction, so a priority report filed a second earlier
  refuses the view.

What is served:
- A photo: its **display variant** (`display.webp`, 1280 px, the variant moderation looked at),
  never the full one.
- A voice note: its AAC copy (`voice.m4a`, ADR-0060) when it has one, else the Opus
  (`voice.ogg`), so it plays in every browser the operator might use.

Headers: the stored content type, `Content-Disposition: inline`, `Cache-Control: no-store`,
`X-Content-Type-Options: nosniff`, `Cross-Origin-Resource-Policy: same-origin`, and A1's CSP,
which gains `media-src 'self'` so the `<audio>` element can play (A1's `default-src 'none'` would
block it). The image is shown in an `<img>` on the quarantine or report page, not opened as a
download.

**The audit row is written and committed before the first byte is sent.** If the write fails,
nothing is streamed. If the stream fails after the commit, the row stands: the log over-records
a look rather than under-records one (T-10). A refused view writes `MEDIA_VIEWED/REFUSED` with the
reason (`blocked`, `priority_report`, `consent_report`, `not_held`, `purged`) and serves the admin
`404` page, the same page for every refusal, so a forged id learns nothing.

## 8. Concurrency: two moderators, one item

The functions are already safe against each other: `entry_report_dismiss` updates only an `OPEN`
row; `entry_report_uphold` and `_reclassify` lock the report `FOR UPDATE`; the media functions
lock the asset; a report filed while another is dismissed is serialised on the asset rows (V38,
`MediaReportHoldsTest`). So two moderators clicking "Dismiss" on one report give one `DISMISSED`
and one `NOT_OPEN`, and the console shows the second "Already decided by someone else", audited
`REFUSED` with `{"result":"NOT_OPEN"}`.

What the functions cannot see is a decision made on a stale page: moderator B reclassifies a
`HARASSMENT` report to `MINOR_SEXUAL_CONTENT` while moderator A, still on the old page, clicks
"Uphold, delete". The function would refuse that (`RECLASSIFY_FIRST`), but the reverse case
(`MINOR_SEXUAL_CONTENT` reclassified to `OTHER`, then A blocks on the old page) would succeed, and
a block is final. So every report decision carries the reason the page showed. The port locks the
report row (`SELECT … FOR UPDATE`, status `OPEN`), compares, and returns `STALE` without calling
the function if it changed. The lock is taken first and the function's own `FOR UPDATE` on the
same row in the same transaction is a no-op, so the order (report, then assets) is the one the
functions already use and no new deadlock is possible.

Quarantine decisions need no such token: their only precondition that can change under the page
is "held by a report", and `media_quarantine_release` already answers `REPORT_HELD`.

A double-click by one moderator is the same race and gets the same answer.

## 9. The audit rows, and what is disclosed

### 9.1 V61, in `modules/admin`

- `audit_log_action_check` is dropped and recreated with A1's six actions plus `REPORT_QUEUE_VIEWED`,
  `REPORT_READ`, `REPORT_DISMISSED`, `REPORT_RECLASSIFIED`, `REPORT_UPHELD`,
  `QUARANTINE_QUEUE_VIEWED`, `MEDIA_VIEWED`, `MEDIA_RELEASED`, `MEDIA_DELETED`, `ADMIN_STEP_UP`.
  `AuditActionCheckTest` holds it to `AuditAction` as before.
- `audit_log` gains `disclosed_to uuid NULL`: the user this row is shown to in their data export,
  or null when it is shown to nobody. Indexed `(disclosed_to, occurred_at) WHERE disclosed_to IS
  NOT NULL`. Adding a nullable column rewrites no row and fires no row trigger, so the append-only
  trigger is untouched and existing rows stay as written.
- No other table changes. V61 is the next free version: `main` is at V60 (4c32486), and no open
  branch carries V61.

### 9.2 What each row holds

| Field | Value |
|---|---|
| `actor_type`, `actor_id` | `ADMIN`, the account id |
| `target_type`, `target_id` | `REPORT` and the report id; `MEDIA` and the media id; null for a queue view |
| `justification` | the moderator's note, for a decision; null otherwise |
| `outcome` | `SUCCEEDED` when the function did what was asked; `REFUSED` for `NOT_OPEN`, `STALE`, `SAME_REASON`, `RECLASSIFY_FIRST`, `NEVER_RELEASED`, `REPORT_HELD`, `NOT_QUARANTINED`, `EVIDENCE_SERVER_ONLY` and a refused view |
| `metadata` | `{"result": "<outcome>"}`, plus for an uphold `{"takedown": "DELETE"\|"BLOCK", "media": [{"id", "outcome"}]}`, for a reclassify `{"from", "to"}` reasons, for a view `{"via": "REPORT"\|"QUARANTINE", "reportId"?}` or the refusal's reason |
| `disclosed_to` | §9.3 |
| `ip_hash` | as A1 |

Never in `metadata`: the reporter's detail, the note (it is the `justification` column), an
email, a storage key, a byte count of anything a member wrote.

### 9.3 Disclosure in the data export *(§14 Q1 for the policy; the mechanism is the builder's)*

ADR-0059 decision 8 left `export.api.AdminAccessLog` for the admin module to implement "when it
keeps its log". A1 keeps one and did not implement it (§15), so today an export would say "No
administrator access to your account or to what you wrote is recorded" while lookups are
recorded. A2 implements it, unconditionally (it is a persistence bean, ADR-0055 ruling D), as
`AdminAccessDisclosure`: the rows `WHERE disclosed_to = :user ORDER BY occurred_at`, mapped to
`AdminAccess(at, action, justification = null)`.

Who a row is disclosed to is decided once, when it is written, by a pure function
`Disclosure.of(…)` in `admin.domain` *(recommended policy, §14 Q1)*:
- `USER_LOOKED_UP/SUCCEEDED`: to the user looked up. (A1's `UserLookup` is changed to pass it.)
- `MEDIA_VIEWED`, `MEDIA_RELEASED`, `MEDIA_DELETED` with `SUCCEEDED`, and each asset an
  `REPORT_UPHELD` took down: to the asset's owner, **unless** the asset was `BLOCKED`, or held by
  an open `MINOR_SEXUAL_CONTENT` report, when the row is disclosed to nobody (runbook §2: "do not
  tell the uploader", tipping off).
- Everything else: nobody. Queue views name no one; a report's read and decision are about the
  reporter's words and the report, not anyone's account or content.

The export shows a fixed action word (`ACCOUNT_LOOKED_UP`, `MEDIA_REVIEWED`, `MEDIA_RELEASED`,
`MEDIA_REMOVED`) and **no justification**: the moderator's note is written to the operator's
record, not to the member, and may name a report. `AdminAccess.justification` stays for A5's
typed justification, which is written to be shown.

An uphold taking down several assets writes one `REPORT_UPHELD` row (the decision) and, for each
asset whose owner is told, one `MEDIA_DELETED` row with `disclosed_to` set. *(Builder's call: one
row per person disclosed to keeps `disclosed_to` a single column.)*

## 10. Security

### 10.1 Inherited from A1, unchanged

Allowlist first; both factors; CSRF synchroniser token on every POST; `SameSite=Strict`
`Path=/admin` cookie; `no-store`; `th:text` only (A1's `TemplateSafetyTest` covers the new
templates without change); admin error pages with no stack trace.

### 10.2 Step-up for the irreversible acts *(builder's call)*

An uphold (delete or block) and a quarantine delete need a TOTP code proven within the last
**5 minutes**. Sign-in's second factor counts. Otherwise the form shows a code field, and the
POST checks it through A1's own path (`AdminSignIn.stepUp`: the same lockout count, the same
replay CAS on `totp_last_step`, an `ADMIN_STEP_UP` audit row), sets the session attribute
`admin.secondFactorAt`, and then decides. A wrong code decides nothing and re-renders the form
with the note kept.

Why: these acts destroy a member's photo or start a legal process, and a session can be left open
on an allowlisted machine for up to 8 hours. Five minutes lets a moderator clear several items
after one code without waiting for the next 30-second step, which the replay CAS would otherwise
force.

### 10.3 Other

- Decisions are POST only; a GET never changes state, including the review copy.
- The review copy's id is parsed as a UUID before anything is read; a bad value is the `404` page.
- The note is free text from an operator: escaped on output, length-checked, never logged.
- No new secret and no new configuration property.

## 11. State machines

### 11.1 A report (`entry_reports.status`)

```
          reclassify (RECLASSIFIED; reason changes, detail kept)
             ┌─────┐
             ▼     │
  (route) ─► OPEN ─┴─ dismiss ──────────► DISMISSED   detail emptied; media_report_release
              │                                       gives back what only it held
              └──── uphold DELETE|BLOCK ► UPHELD      detail emptied; media_report_takedown
                                                      (MINOR_SEXUAL_CONTENT: BLOCK only)
```
`DISMISSED` and `UPHELD` are final. Every arrow writes `entry_report_log` (the function) and
`audit_log` (the console).

### 11.2 A held-back asset, as the console moves it

```
READY ──moderation──► QUARANTINED/QUARANTINED (EXTREME_VIOLENCE, UNMODERATABLE)
READY ──report hold─► QUARANTINED/QUARANTINED (REPORTED, heldFrom)
any   ──block───────► QUARANTINED/BLOCKED, evidence held (or DELETED/BLOCKED, held)

QUARANTINED/QUARANTINED ──release──► READY/PASSED, or READY/no verdict + MediaReady (REMODERATED)
                         └─(refused while an open report holds it: REPORT_HELD)
QUARANTINED/QUARANTINED ──delete───► DELETED + MediaReleased (objects purged)
QUARANTINED/BLOCKED ──────delete───► server only (runbook §2 step 4); console refuses
held by a report ──dismiss──► given back as heldFrom says (or stays, if another report holds it)
held by a report ──uphold───► DELETED (or BLOCKED; BLOCKED_KEPT if already blocked)
```
These are V36/V38's transitions; the console adds none.

## 12. Tests, cross-checks and mutation targets

**Port tests** (`gratitude`, `media`; real Postgres, the full Flyway chain): each port method
against each outcome its function can return; the outcome enums held to the function source;
`STALE` when the reason changed; `MANDATORY` refuses a call outside a transaction.

**Console tests** (`app`, through HTTP, A1's driver and ruling B):
- Roles: `SUPPORT` gets `403` on every A2 route and the service refuses a direct call; `MODERATOR`
  and `ADMIN` get `200`.
- Each decision writes exactly one `audit_log` row with the admin id and the note as
  justification, and the module log names `admin:<id>`.
- **Atomicity:** a test-only trigger that makes the `audit_log` insert raise; the dismiss is rolled
  back (the report is still `OPEN`, the media still held) and the page is the error page.
- **Concurrency:** two sessions dismiss one report; synchronised on the second blocking on the
  report's row lock (observed in `pg_locks`, never a sleep); exactly one `DISMISSED`, one
  `REFUSED/NOT_OPEN`.
- **Stale page:** reclassify in one session, then uphold with the old reason in another: `STALE`,
  nothing taken down.
- **What is never shown:** a report on an entry whose text is a unique marker; neither the list,
  the report page, the quarantine pages nor any audit row contains the marker. The reporter's
  detail is on the report page and in no log line (`OutputCaptureExtension`) and no audit row.
- **The view rule:** served for a moderation quarantine and a `VIOLENCE_OR_SELF_HARM` report;
  refused (the same `404` bytes) for `BLOCKED`, `evidence_held`, a priority report, a
  `NON_CONSENSUAL` report, a released asset and a purged one; the refusal is audited. A priority
  report filed after the page rendered refuses the view.
- **Headers** on the review copy: `no-store`, `nosniff`, `inline`, CORP, and no `Location` or URL
  to the store anywhere in the page.
- **Step-up:** an uphold 6 minutes after sign-in without a code decides nothing; with a correct
  code it decides and writes `ADMIN_STEP_UP`; a replayed code is refused and counts toward A1's
  lockout.
- **Disclosure:** an export after a view of the author's quarantined photo lists `MEDIA_REVIEWED`
  for the author and nothing for the partner; after a view under a priority report, nothing for
  anyone; `justification` is null in the archive.

**Cross-checks** *(builder's call; the point of a console wrapping functions is that it changes
nothing)*:
- **Console ≡ script.** For each decision, one database is decided through the console and a
  twin through the function as `scripts/moyi` calls it; the `media_assets` rows,
  `media_report_holds`, the outbox events and the report rows compare equal, the actor aside.
- The report list the page renders equals `entry_report_list`'s rows in order; the same for the
  quarantine list.
- ArchitectureTest: `modules/admin` imports from `gratitude.api` and `media.api` only the two
  ports and their DTOs.

**Mutation targets** (break, watch red, restore; each named in the plan):
the `@PreAuthorize`; the step-up check; `ReportReason.mediaReviewable` for priority;
`reviewableByModerator`'s `BLOCKED` clause; the stale-reason comparison; the audit write in a
decision; `MANDATORY` on a port; the console's `EVIDENCE_SERVER_ONLY` refusal; `no-store` on the
review copy; `Disclosure.of`'s blocked exclusion; the `admin:` actor prefix.

## 13. Operations

- **Smoke** (`scripts/smoke.sh`, the A1 admin section extended; the local profile has the console
  on and media storage on MinIO): file an entry report with a photo through the API; sign in as an
  invited `MODERATOR`; the report is in `/admin/reports`; its page shows the detail; the photo's
  review copy is `image/webp` with `no-store`; dismiss without a code works; a second dismiss says
  "Already decided"; `entry_report_log` names `admin:<id>`; a priority report's photo answers the
  `404` page and writes `MEDIA_VIEWED/REFUSED`; an uphold without a recent code asks for one; a
  `SUPPORT` session gets `403`; a POST without CSRF is refused. The export section's
  `adminAccess.logKept` expectation changes from `False` to `True` (§9.3), and one export after a
  console view lists `MEDIA_REVIEWED`.
- **Metrics:** `moyi_admin_moderation_decision_total{queue, decision, outcome}` and
  `moyi_admin_media_review_total{outcome}` (`served`, `refused`). The open-report and quarantine
  alarms stay in `ops/monitoring/media-check.sh`, unchanged: they count rows, not who decides.
- **Logs:** one INFO line per decision: admin id, report or media id, the decision, the outcome.
  Never the note or the detail.
- **Runbooks:** `ops/moderation/takedown.md` §1 and §3 gain the console's path beside the script's
  ("in the console: Reports → the report → …"), and §3.3's "read the words" line points at §14 Q2's
  ruling. `ops/admin/README.md` gains "inviting a moderator" (`admin_invite(email, 'MODERATOR')`).
  `scripts/moyi` stays: it is the path while the console is off, and for the acts A2 leaves on the
  server.
- **ADR-0063** records the builder's calls above.
- **Rollout:** nothing changes in production. `MOYI_ADMIN_ENABLED` stays `"false"`; V61 runs on
  deploy and only widens a `CHECK` and adds a nullable column. The owner switches the console on
  by `ops/admin/README.md`, which already requires ADR-0062's deploy first; A2 adds nothing to
  that list.
- **Rollback:** V61 is additive. Rolling the code back leaves rows with the new actions, which the
  old `AuditAction` enum cannot map; the old code never reads them (A1 reads no audit rows), so
  this is safe until A5's disclosure reads them, by which time V61's code is the code.

## 14. Questions only the owner can answer, with recommendations

1. **What is disclosed to a member in their export.** Recommended: a lookup of their account, and
   a moderator's view, release or removal of their own photo or voice note, each as a fixed word
   with the date and no reason or note; never anything about `BLOCKED` media or a
   `MINOR_SEXUAL_CONTENT` report (tipping off). The cost: while moderation is off in production,
   "a moderator reviewed your photo" tells the author a report was made, the same cost ADR-0053
   question 2 already accepts for `NOT_SHARED`. If the owner rules that question the other way
   (a report hold shown `READY` to its author), report-driven views should be withheld too, and
   `Disclosure.of` is the one place that changes. **[legal: confirm]** whether DSA Art. 17's
   statement of reasons applies to a removal here; if it does, a removal needs a reason sent to
   the author, which is more than this discloses.
2. **Reports about words until A5.** Recommended: decide them from the reporter's detail in the
   console; once the console is switched on, stop reading entry text at `psql` (runbook §3.3's
   permission), because it is content access with no justification and no disclosure; and build
   A5 next, before A3, so the gap is short.
3. **No viewing of `NON_CONSENSUAL`-reported media.** Recommended: refuse it, as built, and keep
   runbook §3.2's "take the reporter's word". The cost: a false NCII report removes a photo
   unseen. The alternative is a view behind a typed justification, which is A5's machinery.
4. **`BLOCKED` evidence deleted only on the server.** Recommended: yes, as built (§6.3), until a
   second operator exists, when a two-person rule in the console might replace it.
5. **One moderator decides alone.** Recommended: yes for now (there is one operator). Revisit a
   second pair of eyes on `BLOCK` when a second moderator is invited.

## 15. Where the sources disagree, or leave a gap

- **A1 keeps an audit log but does not implement `AdminAccessLog`**, which ADR-0059 decision 8
  says the admin module implements "when it keeps its log". With the console on, an export would
  say no access is recorded while lookups are. Harmless today (the console is off in production);
  A2 closes it (§9.3), and the smoke's `logKept False` expectation changes.
- **Runbook §3.3 lets the operator read an entry's words at `psql`** to decide a report;
  FR-112 and doc 09 §4 make reading entry content `ADMIN`-only, justified and disclosed. A2 keeps
  words out of the console and asks the owner (§14 Q2).
- **The support inbox has no slice.** FR-113 lists it beside the report queue, and the A1 spec's
  non-goals name it, but its §11 assigns A2 to A5 without it. It is already served by `moyi
  support` (ADR-0057). Recommended: fold it into A4, or a slice of its own after A5.
- **A1's CSP has no `media-src`.** `default-src 'none'` blocks `<audio>`; A2 adds
  `media-src 'self'` (§7.4).
- **Doc 05's module tree puts the report queue in `admin/`.** The tables and functions are
  `gratitude`'s and `media`'s (ADR-0053); the console reads them through ports. No change to the
  build; recorded so a later reader is not misled.
