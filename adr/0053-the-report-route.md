# ADR-0053 — The report route: an entry reported to a person, its media held back from the reporter at once, and the operator's queue in the database

**Status:** Accepted · **Date:** 2026-10-30 · **Deciders:** Daniel, for the rulings it builds on
(Q16 of 2026-10-29); decision 4 was made without him and is his to revisit. · **Amended:**
2026-10-30, by the independent review of #118 (the coordinating agent's rulings P1, P2-a to P2-d,
P3): decisions 8a, 8b, 8c and 13 added, 7, 8, 10 and 11 qualified. **Amended** 2026-11-08, for #127
(FR-100's member half, question 6): decision 14 added; decisions 1, 3 and 9 qualified; question 6
answered.

## Context

FR-100: "Any member MUST be able to report the other member or a specific entry. Reports create an
admin queue item." Doc 26 designed the screens (report from the archive's day view; a reason and a
detail; "a person will read it, the other member is not told"), doc 06 §3.7 sketched
`POST /reports {targetType, targetId, reason, detail}`, doc 07 a `reports` table, and doc 26 left
three questions open: the reason values (OQ-26b), a rate limit (OQ-26c), and, through ADR-0036
question 5, when the route comes ("a later phase's"). ADR-0051 built moderation and quarantine and
left the route as its open question 6, with the runbook's §3 starting from an email and the
operator's `hold` and `block` functions waiting for it (`media_quarantine_hold`, reason `REPORTED`).

The owner's ruling Q16 (2026-10-29) binds this: non-consensual imagery is met by "withdraw/erase,
**a report path to human review and takedown**, and help text about screenshots"; adult nudity
between members is allowed (`PASSED`, `sensitive`); child sexual abuse material is always blocked,
reported, never released. The owner has **not** ruled whether a reported item is hidden from the
reporter at once.

The rules that shape everything here: a partner reaches media only through an entry they were shown
(ADR-0048 decision 5, ADR-0051 decision 4); anything a caller was never shown is `404`, never `403`;
reporting is silent (doc 26 §2.2: "no notification to the reported member, ever, in any state");
and the reporter's words and the entry's words never reach a log.

This is the entry half of FR-100. Reporting the **member** (doc 26's other target) is not built
(question 6).

## Decision

**1. `POST /api/v1/entries/{entryId}/report`, `{"reason": …, "detail": …}`, `201` with
`{id, entryId, reason}`.** Entry-scoped, the shape of the reaction and favourite routes beside it
(ADR-0037 decision 1), not doc 06's `POST /reports {targetType, targetId}`: the one target built is
an entry, its gate is the entry's, and an entry path keeps the one `404` for everything the caller
was never shown without a second dispatch on a type. A member report is a route of its own
(decision 14). Doc 06's shape is the divergence this records.

**2. Who may report what is the read gate's answer** (`Entry.canBeReportedBy`, beside
`canBeReactedToBy` and with its order):

| The entry, as the gate answers for the caller | Answer |
|---|---|
| the partner's, read in full | `201`; its photo and voice note held back (decision 4) |
| the partner's, a tombstone (deleted, or withdrawn) | `201`; nothing left to hold |
| the caller's own, in any state | `409 REPORT_ON_OWN_ENTRY` (new code; a breaking change to the error enum) |
| locked, erased before it was shown, another bond's, no such id, not a UUID | the one `404` |

A tombstone is reportable because doc 26 §4 (amended by ADR-0036 question 5) puts the report on the
archive's day view, which carries the id of an entry read in full **or as a tombstone**: what was
shown before the erasure may be what is reported. **An ended bond does not refuse a report**: a
report is never contact, and a member who has just blocked their partner may need it most. The body
is judged before the entry is looked at (a stranger with a bad body gets `422`, as a member does).

**3. Five reasons, and an optional detail.** `NON_CONSENSUAL`, `MINOR_SEXUAL_CONTENT`,
`VIOLENCE_OR_SELF_HARM`, `HARASSMENT`, `OTHER` (`ReportReason`, written once; V39's `CHECK` and the
contract's two enums are read from it). Doc 26 §6 proposed four values that mixed *what* was
reported with *why* ("a photo or voice note"); here the entry is what, with its media, and the
reason is why, in the cases Q16 names a person must act on, plus T-09's "how they are treating me"
as `HARASSMENT`. `detail` is the reporter's own words to the reviewer: optional, not blank, at most
500 code points (`ReportDetail`, which the edge asks and V39's `CHECK` backs), never a word of the
entry.

**4. What was reported is held back from the reporter at once** (the decision the owner has not
made; question 1). In the report's transaction, every photo and voice note attached to the entry
is held (`media_report_hold`, V38), by ADR-0051's own hold: `QUARANTINED`, reason `REPORTED`. The
reporter is then shown them as `PROCESSING`, the partner's view of anything held back (ADR-0051
decision 4, Q10: "processing to the partner, never removed"). Why at once, and why this is the safe
reading: **in a bond of two, the only person a hold hides anything from is the reporter**, who asked.
Holding costs nobody else anything and cannot be used against the author; not holding leaves
non-consensual imagery, or worse, on the screen of the person who said they did not want to see it,
for as long as the queue takes. The alternative (shown until a person decides) is kept as the
owner's to choose.

- `MINOR_SEXUAL_CONTENT` is held exactly the same way and **never blocked by the route**: a report
  is a claim, a block is final and irreversible (V36's trigger), and Q16's "always blocked" is
  satisfied by a person's `uphold … block` on review (decision 10). It is marked for priority review
  instead: first in the operator's list, never to be viewed (`DO NOT VIEW — runbook §2`), and an
  alarm at once (decision 11).
- The entry's **words are not hidden** (question 3): there is no mechanism that hides one entry's text
  from one reader, and building one is a second read gate. A report of words holds nothing.
- Each report holds the media **for itself**: two reports of one entry are two rows in
  `media_report_holds`, and an asset is given back only when no open report holds it.

**5. What the author is shown: what ADR-0051 shows of anything held back, and nothing more.** Their
photo and voice note are `NOT_SHARED`, still with the URL (decision 4 of ADR-0051). No push, no
event, no field, no error code tells them a report exists or who made it. The response's only new
thing to them is the status, which moderation also gives. **The accepted cost:** while moderation
is `none` in production, nothing is ever held back by moderation, so a `NOT_SHARED` there can only
come from a report, and in a bond of two the author can work out who made it. That is T-09's
retaliation risk (doc 09 §2; doc 26 §2.1's "the moment retaliation risk peaks"). It is the price of
decision 4 and of keeping ADR-0051's single owner view, and it is put to the owner (question 2): the
alternative is that a report hold is shown to its author as `READY` (a lie to the author, and a
second rule for what "held back" shows).

**6. What the reporter is told: that the report was received.** `{id, entryId, reason}`; never what
was decided. The client's acknowledgement says a person will read it and the other member is not
told (doc 26 §2.2). The reporter is not shown "reported" on the entry; they are shown its media as
processing.

**7. The detail is the operator's and nobody else's, and it does not outlive the decision in the
live database.** It is written by the application and never read back by it: no response, log line
or exception can carry it (`ReportsTest` captures the output and looks for it). The operator reads
it through `entry_report_read`, which logs the look. Dismissing or upholding empties it (V39:
`entry_reports_detail_kept_check`); the operator's note records what was decided and why. The
request type redacts it from `toString`. **Emptied is not yet gone everywhere** (review of #118,
P3): the old row version stays in the table's pages as a dead tuple until autovacuum reclaims it
(minutes to hours, readable only with the database's files), and the nightly encrypted backups
keep it for their 30 days (ADR-0043), re-applied by a restore. The Privacy Policy says so (Owed).

**8. A report is dismissed, or upheld.** Dismissed: the report closes and every asset it held that
nothing else holds is **given back exactly as it was** (`media_report_release`): V38's hold now
records what it held the asset back from (`heldFrom`: the verdict, its time, what moderation found),
so a photo that had `PASSED` is `READY` and `PASSED` again with its `sensitive` flag, and one that had
no verdict yet (moderation not configured, or not yet reached) is `READY` with none and handed back
to moderation with a new `MediaReady`, never passed unseen. What moderation itself held back stays
what moderation decided. Upheld: `delete` (V36's `media_quarantine_delete`, objects purged) or
`block` (V36's `media_quarantine_block`, held as evidence). **An upheld delete never takes a `BLOCKED`
asset** (`BLOCKED_KEPT`): its evidence goes only by the runbook's §2, after the report to the
authority. A report of words is upheld with nothing to take down.

**8a. What an open report holds is kept, whoever deletes it: a legal hold** (review of #118, P1).
Deletion stays the author's right in the API: their `DELETE` of the entry, a withdrawal at the
bond's end, an edit that lets a photo go and then their `DELETE` of it, the reaper. Each makes the
asset `DELETED` to everyone, as before. But while any open report holds it (`media_report_holds`),
`media.purge`, the owner's best-effort delete, the reaper and the demotion spare its objects, as they
spare `evidence_held`. The decision hands them to the purge: a dismissal, or an upheld delete,
publishes `MediaReleased` once nothing else holds it (`media_report_let_go`). Without this an author
could make a reported photo, child abuse material included, unrecoverable within seconds of being
told nothing, and `uphold … block` would answer `GONE`. A report on an entry already erased finds its
media by **`last_entry_id`** (kept by a trigger whenever `attached_entry_id` is cleared) and holds what
is not yet purged; what is purged is gone. **Bounded** by the review alarms (decision 11): a report
open two days alarms, a priority one at once, so erased media is not kept for longer than the
operator takes to decide. One window is left: a purge that read the asset before a report's hold
committed deletes its objects (it holds no lock while it reaches the storage, ADR-0047 decision 2);
that is a report made within the seconds after an erasure. **The Privacy Policy says that erased
media under an open report is kept until a person decides** (Owed).

**8b. A dismissal gives back only what a report held back** (P2-a). The hold records which report
took it (`heldFrom.report`); the operator's own hold (`moyi media quarantine hold`, for a report that
came by email) records none, and no dismissal ever gives it back.

**8c. The operator's release respects both** (P2-b; V38 replaces V36's `media_quarantine_release`).
It is refused while an open report holds the asset (`REPORT_HELD`, logged `RELEASE_REFUSED`): the
report is decided with `moyi reports`. And an asset held before moderation reached it is never made
`PASSED` by a release: it goes back to moderation (`REMODERATED`). The operator's list gains a column,
the open reports holding each row, and such a row says to decide it in the reports queue.

**9. Idempotent and rate-limited.** `Idempotency-Key` required (doc 06:16; `ResultKind.REPORT`,
V37): the key, the report and the hold commit together, a replay is `201` with
`Idempotency-Replayed: true` and the same report, re-read and authorised as the reporter's.
`reports:create:user`, **ten a day** (ADR-0023's Bucket4j; doc 26 OQ-26c asked whether support's five
applies): a report puts a row in a person's queue and holds media back, and ten leaves room for
someone reporting several days of one partner's entries.

**10. The operator's queue is database functions, as ADR-0051's is.** A
`MINOR_SEXUAL_CONTENT` report is never upheld by `delete` (P2-c): it is blocked, or first
**reclassified** (`moyi reports reclassify`, logged `RECLASSIFIED` with the old and new reasons and
why) when the reporter's own detail shows the reason was wrong. V39: `entry_report_list`,
`_read`, `_dismiss`, `_uphold`, each writing its own row in **`entry_report_log`** (who looked at the
queue, who read a report, who decided what, with their note), the sibling of `media_moderation_log`,
which keeps what became of the media. `scripts/moyi reports list|show|dismiss|uphold` names which to
run (`MOYI_OPERATOR` required, a note on every decision). Gratitude's functions call media's
(`media_report_release`, `media_report_takedown`, `media_report_holding`), never the reverse, as the
build's dependency runs; media knows a report only as an id. The race the functions are written for,
a report filed while another of the same entry is dismissed, is closed by locking each asset's row
first in both (`MediaReportHoldsTest`, synchronised on the dismissal waiting for the lock).

**11. The alarm.** `ops/monitoring/media-check.sh` fails while a report has been open more than
`REPORT_REVIEW_DAYS` (2 by default), and at once while a `MINOR_SEXUAL_CONTENT` report is open (as it
does for a blocked asset). What a report holds is no longer counted as "deleted, not purged after a
day" either: decision 8a keeps it on purpose, and this alarm bounds how long. What a report holds is counted there and no longer as "held back for
review": V36's hold keeps the verdict's old time, so a photo passed a week ago and reported today
would have raised that alarm at once.

**12. The runbook** (`ops/moderation/takedown.md` §3) now starts from the queue: priority first and
never viewed; non-consensual imagery deleted on the reporter's word; the rest looked at and decided.

**13. The rate limiter makes its connection again when the factory restarts** (P2-d, found by this
slice's rate-limit test). `RedisRateLimiter` built its Lettuce connection once, in a `lazy`; a
`LettuceConnectionFactory` stop and start (a `SmartLifecycle`; Spring 7's test framework pauses
cached contexts, and production may stop a lifecycle) shuts the old client down, and the limiter kept
a closed connection and failed open for the rest of the process, loud only in its WARN. It now checks
the connection before each use and rebuilds it when it is closed or the factory's client has changed
(`RedisRateLimiterTest`). ADR-0023's fail-open policy is unchanged.

**14. The member report (#127, question 6): `POST /api/v1/bonds/{bondId}/members/other/report`, the
same `ReportRequest`, `201` with `{id, reason}`.** Bond-scoped, with no member id in the path, as
`/members/me/settings` has none: a bond has two members, and the caller can only mean the other.

- **Who may: any member of the bond, about the other member row, in every state.** The bond's
  guard first; then the other row (`BondAccess.partnerMemberIdOf`, the row `partnerDisplayNameOf`
  reads), whether that member has left or blocked or been blocked. Not refused on an ended bond,
  after a block, after either has left, or during a deletion countdown, for decision 2's reason: a
  report is never contact. A bond the caller is not in, an unknown id, a value that is not a UUID,
  **and a bond that has never had a second member** are the one `404`, the same bytes: the last is
  thrown by `bond` itself (`BondNotFoundException`), where the others are made. The body is judged
  before the bond is looked at (`422` to a stranger as to a member).
- **The reasons are the same five** (decision 3; OQ-26b). Doc 26 §6's "How they're treating me",
  T-09's reason, is `HARASSMENT`, as decision 3 already said; it is the reason this route exists.
  The others still mean something of a person: `NON_CONSENSUAL` (they are sharing intimate imagery
  of the reporter elsewhere), `VIOLENCE_OR_SELF_HARM` (threats, or a fear for them), and
  `MINOR_SEXUAL_CONTENT` (a report about a child's safety, kept priority and first in the queue).
  A second set was considered and refused: it would be a second enum for a client to map, a breaking
  change to add, and a reason a person could not choose from the day view. The copy for each, as
  the client shows it for a person rather than an entry, is still question 4's.
- **It holds nothing.** Decision 4's hold is of an entry's media; a person has no photo. Nothing in
  `media_report_holds`, nothing `QUARANTINED`, and so **nothing the reported member can read
  changes**: no push, no event, no field, no `ETag` (doc 26 §2.2; `MemberReportsTest` and the smoke
  compare the reported member's `GET /bonds/{id}` and `GET /today` bytes and `ETag` before and
  after). Decision 5's accepted cost does not arise here: there is no `NOT_SHARED` to infer from.
- **Stored in the same table, as a second target** (V46): `entry_reports.target` is `ENTRY` or
  `MEMBER`; a member report names `reported_member_id` (a `bond_members` id, never a user id) and no
  entry, a `CHECK` holds one or the other. One queue, one log, one priority order, the same alarm
  (decision 11 counts every open row) and the same commands (`moyi reports list|show|dismiss|uphold`,
  whose list and show now carry the target and the member). The table keeps its name: renaming it
  would mean recreating every function that reads it, for a word. A member report is decided as a
  report of words is (decision 8): dismissed, or upheld with nothing to take down; the remedy is
  the reporter's block, and whatever the operator does outside the API.
- **Idempotent, and the same bucket** (decision 9): `Idempotency-Key` required (`ResultKind.REPORT`,
  a replay re-reads the report and is authorised as its reporter's); `reports:create:user`, ten a
  day **across both routes**. One allowance because one person reads both: two buckets would let one
  account put twenty a day in front of them. Doc 06 §4 names no bucket for either.
- `detail` is decision 7's: written, never read back by the application, emptied by the decision.

## Consequences

**Positive.** A member can report what was shared with them, and it leaves their screen at once.
A person decides every report; child sexual abuse material is never blocked by a claim and never
released once blocked. A dismissal gives back exactly what was taken, and a report never lets go of
what moderation decided. The reporter's words are read by one person, logged as read, and deleted
once decided.

**Negative.** While moderation is off, a `NOT_SHARED` tells the author a report was made (decision
5, question 2). The words of a reported entry stay visible. The queue is SQL functions until the
Phase 5 console. `ErrorCode` gains `REPORT_ON_OWN_ENTRY`: a breaking change for a generated client
(the `breaking-api-change` label).

**Neutral.** V37 (`ResultKind.REPORT`), V38 (`media_report_holds`, `heldFrom` in V36's hold, three
functions), V39 (`entry_reports`, `entry_report_log`, four functions). One route, one bucket, one
error code. No event: nothing outside the report's transaction needs to know. Decision 14 adds V46 (`target`, `reported_member_id`, and
`entry_report_list`/`_read` made again with them), a second route on the same bucket, and
`BondAccess.partnerMemberIdOf`; no error code, no event. Additive to the contract.

## Alternatives considered

- **Doc 06's `POST /reports {targetType, targetId}`.** One route for two targets, each with its own
  gate and its own `404`; built when the second target is (decision 1).
- **Shown to the reporter until a person decides.** Leaves what was reported on the reporter's
  screen for days; hides nothing from anybody else either way (decision 4).
- **Auto-block on `MINOR_SEXUAL_CONTENT`.** A false report would make a person's photo final,
  unreleasable evidence and set off a law-enforcement report; Q16's block is a person's.
- **A report hold that is `READY` to its author.** Hides the report from the author; puts a second
  meaning on "held back" and lies to the owner of the photo (question 2).
- **Keeping the detail after the decision.** Nothing needs it once the note records why; it is what
  a frightened person wrote about the person they share a journal with.
- **One hold, not one per report.** Dismissing either of two reports would give the media back while
  the other is open.

## Open questions (the owner's)

1. **Hidden from the reporter at once** (decision 4)? Built: yes, every report, every reason. The
   alternative is shown until a person decides, perhaps for some reasons only.
2. **The author's view of a report hold** (decision 5, T-09; still open after the review of #118,
   which recommended `READY` to the author until decided): `NOT_SHARED` (built, ADR-0051's one
   view) or `READY`, so the author cannot infer a report while moderation is off? Beside ADR-0051
   question 2, which asks the same of a block.
3. **The words.** Should a report hide the entry's text from the reporter too, and should a person be
   able to take text down? Neither exists; the reporter's remedy today is to leave or block.
4. **The reasons** (OQ-26b): the five names, and the copy the client shows for each (doc 26 §6's
   `strings-proposed.md` §10). And whether ten a day is right (OQ-26c).
5. **[legal: confirm]** whether a member's report of sexual content involving a minor is ground
   enough to block and report without anyone viewing it (the runbook's §3 step 1).
6. **Reporting the member** (FR-100's other half, doc 26 §1): **answered 2026-11-08, decision 14**
   (#127). Still the owner's: whether a member report should ever hold or hide anything (today it
   cannot: there is nothing of a person to hold), and whether the operator needs an action on an
   account (none exists in v1; upholding records the decision only).

## Owed

- **The client (moyi-app):** the report flow of doc 26 §4 (choose target, compose, sent, failed with
  input kept), the five reasons' copy, and "processing" on a reported photo read as "you reported
  this" by the client that sent it.
- **Account deletion** (not built): a deleted reporter's details in open reports, and their reports'
  `reporter_member_id`, are that change's to erase.
- **The owner, for the documents this changes (the corpus is read-only to a slice):** the Privacy
  Policy and doc 07 §7's retention table say that (a) a photo or voice note under an open report is
  kept, whoever deletes it, until a person decides, which the review alarms bound to days (decision
  8a); (b) the reporter's detail is deleted when the report is decided, and survives in the 30-day
  backups and briefly as a dead tuple (decision 7); (c) a report's reason, its entry and who made it
  are kept as the record of what was decided.
- **The Phase 5 console** reads `entry_reports` and `entry_report_log` and calls the same functions.

## Revisit when

The owner answers questions 1 or 2. Moderation is switched on in production (decision 5's cost
changes: a `NOT_SHARED` is then no longer only a report). The console replaces the script. An
account-level action exists for an operator to take on an upheld member report.
