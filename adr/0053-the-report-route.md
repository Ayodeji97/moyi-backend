# ADR-0053 — The report route: an entry reported to a person, its media held back from the reporter at once, and the operator's queue in the database

**Status:** Accepted · **Date:** 2026-10-30 · **Deciders:** Daniel, for the rulings it builds on
(Q16 of 2026-10-29); decision 4 was made without him and is his to revisit.

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
was never shown without a second dispatch on a type. A member report will be a route of its own
(question 6). Doc 06's shape is the divergence this records.

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

**7. The detail is the operator's and nobody else's, and it does not outlive the decision.** It is
written by the application and never read back by it: no response, log line or exception can carry
it (`ReportsTest` captures the output and looks for it). The operator reads it through
`entry_report_read`, which logs the look. Dismissing or upholding empties it (V39:
`entry_reports_detail_kept_check`); the operator's note records what was decided and why. The
request type redacts it from `toString`.

**8. A report is dismissed, or upheld.** Dismissed: the report closes and every asset it held that
nothing else holds is **given back exactly as it was** (`media_report_release`): V38's hold now
records what it held the asset back from (`heldFrom`: the verdict, its time, what moderation found),
so a photo that had `PASSED` is `READY` and `PASSED` again with its `sensitive` flag, and one that had
no verdict yet (moderation not configured, or not yet reached) is `READY` with none and handed back
to moderation with a new `MediaReady`, never passed unseen. What moderation itself held back stays
held. Upheld: `delete` (V36's `media_quarantine_delete`, objects purged; a photo the operator
released meanwhile is held first so the delete takes it) or `block` (V36's `media_quarantine_block`,
held as evidence). **An upheld delete never takes a `BLOCKED` asset** (`BLOCKED_KEPT`): its evidence
goes only by the runbook's §2, after the report to the authority. A report of words is upheld with
nothing to take down.

**9. Idempotent and rate-limited.** `Idempotency-Key` required (doc 06:16; `ResultKind.REPORT`,
V37): the key, the report and the hold commit together, a replay is `201` with
`Idempotency-Replayed: true` and the same report, re-read and authorised as the reporter's.
`reports:create:user`, **ten a day** (ADR-0023's Bucket4j; doc 26 OQ-26c asked whether support's five
applies): a report puts a row in a person's queue and holds media back, and ten leaves room for
someone reporting several days of one partner's entries.

**10. The operator's queue is database functions, as ADR-0051's is.** V39: `entry_report_list`,
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
does for a blocked asset). What a report holds is counted there and no longer as "held back for
review": V36's hold keeps the verdict's old time, so a photo passed a week ago and reported today
would have raised that alarm at once.

**12. The runbook** (`ops/moderation/takedown.md` §3) now starts from the queue: priority first and
never viewed; non-consensual imagery deleted on the reporter's word; the rest looked at and decided.

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
error code. No event: nothing outside the report's transaction needs to know.

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
2. **The author's view of a report hold** (decision 5, T-09): `NOT_SHARED` (built, ADR-0051's one
   view) or `READY`, so the author cannot infer a report while moderation is off? Beside ADR-0051
   question 2, which asks the same of a block.
3. **The words.** Should a report hide the entry's text from the reporter too, and should a person be
   able to take text down? Neither exists; the reporter's remedy today is to leave or block.
4. **The reasons** (OQ-26b): the five names, and the copy the client shows for each (doc 26 §6's
   `strings-proposed.md` §10). And whether ten a day is right (OQ-26c).
5. **[legal: confirm]** whether a member's report of sexual content involving a minor is ground
   enough to block and report without anyone viewing it (the runbook's §3 step 1).
6. **Reporting the member** (FR-100's other half, doc 26 §1): not built.

## Owed

- **The client (moyi-app):** the report flow of doc 26 §4 (choose target, compose, sent, failed with
  input kept), the five reasons' copy, and "processing" on a reported photo read as "you reported
  this" by the client that sent it.
- **Account deletion** (not built): a deleted reporter's details in open reports, and their reports'
  `reporter_member_id`, are that change's to erase.
- **The Phase 5 console** reads `entry_reports` and `entry_report_log` and calls the same functions.

## Revisit when

The owner answers questions 1 or 2. Moderation is switched on in production (decision 5's cost
changes: a `NOT_SHARED` is then no longer only a report). The member report is built. The console
replaces the script.
