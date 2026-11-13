# ADR-0064 — The admin console (A5): an ADMIN reads one reported entry's words, after a justification and a fresh code, and the export does not say so while the case is open

**Status:** Accepted, provisional where marked · **Date:** 2026-11-13 · **Deciders:** the four product
rulings are Daniel's (2026-11-12, below); each decision marked *(builder's call)* was made without
him and is his to revisit. · **Amends:** ADR-0063 (A2: the console no longer leaves entry text to
`psql`, once it is on), ADR-0055 (A5 done; `ADMIN` gains its one act that `MODERATOR` lacks),
ADR-0059 decision 8 (the disclosure seam, again withholding)

## Context

A report about words (harassment, a threat) was decided from the reporter's own detail. When the
detail was not enough, `ops/moderation/takedown.md` §3 let the operator read the entry at `psql`,
with the reason written only in the report's note: recorded nowhere an auditor would look, tied to
no second factor. ADR-0063 ruling 2 stopped that once the console is on, and left the console with
no way to read the words at all. FR-112 says viewing entry content MUST need an elevated role and a
typed justification, MUST be immutably audit-logged and MUST be surfaced to the user in their data
export. T-10 ("Admin abuses access to read entries") is the threat.

The spec (`docs/superpowers/specs/2026-11-12-admin-console-a5-design.md`) holds the detail.

## Decision

### The door

**1. One narrow door.** From an open report about words, an `ADMIN` reads that one entry's text and
its date: `POST /admin/reports/{id}/entry-text`, with a justification (`ModerationNote`'s rule: 1 to
400 characters) and a TOTP code typed into the same request. Nothing else in the console shows a word
an entry holds: no search, no lookup by id, no partner's entry, no other day, no media
(owner rulings 1 and 3).

**2. What "a report about words" is.** One function in `gratitude.domain`, next to the reasons
(`wordsReadable`): the report is `OPEN`, its target is `ENTRY`, and its reason is `HARASSMENT`,
`VIOLENCE_OR_SELF_HARM` or `OTHER`. `NON_CONSENSUAL` and `MINOR_SEXUAL_CONTENT` are about images: a
report with those reasons whose detail shows it is about words is reclassified first (audited, old and
new reasons), then read. That keeps a read off any report that might concern child sexual abuse
material, where the runbook's rule is "do not view". The page that offers the read (`whyNot`) and the
read itself ask the same function, so they cannot differ.

**3. The step order is fixed, and each step precedes the next** *(builder's call)*: the role
(`@PreAuthorize`, before any of our code); the justification (`ModerationNote`'s own check, so a
refused one spends no code and writes no `ADMIN_STEP_UP`); the code, proved in this request by
`SecondFactor.proveNow`, which commits its own `ADMIN_STEP_UP` row; then the read. The session's
`secondFactorAt` window is not consulted and not extended: a code fresh for a block five minutes ago
does not open the words, and a repeat read needs a new code (the replay compare-and-set refuses the
old one).

**4. The controller is outside the transaction.** The POST is deliberately not `@Transactional`, so
the step-up commits before `ContentAccess` opens the read's transaction: a read that fails never
un-spends the code, and the admin's row lock is not held across the read. The proof is a
`FreshStepUp` that only a fresh code produces; it is handed to that one call and stored nowhere.
`ContentAccess` never steps up itself.

**5. One transaction for the read and its two records.** The gratitude port (`ReportedEntryText`,
`MANDATORY`) reads and writes the report's `WORDS_READ` row (V63, actor `admin:<accountId>`, no note);
`ContentAccess` writes the `audit_log` row (`ENTRY_TEXT_READ`, V64) with the justification. Neither the
words nor the record of reading them commits without the other, and the response renders only after the
commit returns.

**6. Five outcomes, one closed enum** (`EntryTextOutcome`, each a `metadata.result` and a tag of
`moyi.admin.content.read`): `SHOWN` and `ERASED` complete (`SUCCEEDED`, a `WORDS_READ` row each);
`NOT_OPEN`, `STALE` and `NOT_ABOUT_WORDS` are refused (`REFUSED`, nothing in the report's log). A
role refusal never reaches this code and writes no row.

**7. `NO_WORDS` was dropped.** The build plan proposed a sixth outcome for a reported entry that is
not erased but has no text. It was never reachable: the domain forbids it (`Entry.kt`'s `init`: only an
erased entry has no text), and the spec has no such outcome, so it was not added. Instead the store
treats the breach as what it is: a row that is not erased and holds no text throws
`IllegalStateException` (naming the entry, never a word), the transaction rolls back, nothing is shown
and no `WORDS_READ` is written. One test pins it on a directly nulled row. Cost if wrong: if media-only
entries are ever added, that throw surfaces as a `500` on a report read until an outcome is added for
them; loud, not leaky.

**8. The response that shows the words** is the POST's own, never a redirect, so the words have no URL
and are not re-fetched by a reload without a fresh code. `Cache-Control: no-store`, `Pragma: no-cache`,
`Referrer-Policy: no-referrer`; `th:text` only; its own template, never a fragment of `report.html`.
The words are held in `ReportedWords`, whose `toString` is redacted and which is equal only to itself.
They reach no log line, metric tag, exception message, flash, audit column or URL.

### Concurrency

**9. The lock order is the report `FOR SHARE`, then the entry `FOR SHARE`, and no bond lock.** A
decision waits for a read, and a read waits for a decision; an erasure waits for a read, and a read
waits for an erasure and then reads the tombstone. Whether the author has withdrawn from the bond
(shown as `ERASED`, because the outbox erases the text later and the admin must not read in that gap
what the partner can no longer read) is asked of `BondAccess.closingViewOf`, which takes no lock, after
the entry's lock.

The reason there is no bond lock is a deadlock. The reporter's own account erasure takes the bond's
lock and then empties the report's detail (`UPDATE entry_reports SET detail = NULL`). A read that
held the report's share lock and then waited for the bond would close a cycle with it.
`ReportedEntryTextRaceTest` holds both orders, including the one that would deadlock.

**10. `closingViewOf` has one request caller, and says so.** Its KDoc forbade its unlocked view for any
decision. The admin's read is the one documented exception: reached only from an `ADMIN`-only method
after a second factor, never from a member's route, asked after the entry's lock, so a withdrawal
committed before that statement is seen and a later one is ordered after the read. The KDoc and
`ArchitectureTest`'s allow-list name it.

### The owner's rulings (2026-11-12), as decided

1. **Scope: reported entries only.** Legal orders stay a server procedure (runbook §5).
2. **Disclosure: withheld while the report is open. This is a departure from FR-112's "MUST be
   surfaced to the user in their data export".** *The reason:* a report on an entry always comes from
   the author's partner (a bond has two members), so telling the author at once that an admin read
   their entry would tell them who reported them, and a reporter who fears a partner's retaliation is
   exactly the person the report route exists for. `Disclosure.shows` withholds `ENTRY_TEXT_READ`
   whatever its outcome, target or metadata, and `AdminAccessRows` is not widened to `ENTRY`; a test
   shows an export built after a `SHOWN` read is byte-equal to one built before it. *The condition:*
   the withholding is justified only while the case is open. The row is still immutable, and still
   there. *What is owed:* a review for release once the case closes (the same debt as ADR-0063 Q1), and
   the legal check (GDPR Art. 15 and an Art. 23 restriction) that ADR-0063 already requires before the
   console goes on.
3. **Context: that entry's text and its date only.** Not the partner's entry, not other days, no media.
4. **No hold on the text.** An open report does not keep the entry's words from their author's erasure
   or withdrawal (unlike media, ADR-0053 8a): the author's right to erase wins. The admin then sees
   `ERASED` and decides from the reporter's detail. v1 has no takedown of words (ADR-0053 question 3).

### Records

**11. V63** (gratitude) widens `entry_report_log_action_check` with `WORDS_READ` and does nothing else.
**V64** (admin) widens `audit_log_action_check` with `ENTRY_TEXT_READ`, A2's drop-and-re-add pattern;
`AuditActionCheckTest` holds the enum and the SQL together. The report page's history is the report's whole log, so it lists the row too; `moyi reports show` prints one `words read
by <actor> at <time>` line for each `WORDS_READ` row: who and when, never the words and never the
reason given.

**12. Copy.** The read has its own two lines rather than A2's decision-form ones, which were false for
it: "Enter a code from your authenticator app. Every read needs a new one." and "Write why you need to
read them: 1 to 400 characters."

## Consequences

**Positive:** the console can be switched on without a hole in `psql`, and every read of an entry's
words names an account that passed two factors in that request, with its stated reason, in a log
nobody can edit, in the same transaction as the read. A moderator or support account cannot reach the
words by any route, including a hand-made POST.

**Negative:** T-10's "access disclosed to the user" is not met while a case is open, by choice (ruling
2); until the release review is built it is not met after it either. A justification is a typed
sentence, and one operator reads alone; the audit row is the only check. A code is spent on every read,
so reading two reports costs two 30-second steps.

**Neutral:** production is unchanged: the console stays off, and V63 and V64 only widen `CHECK`s.

## Alternatives considered

- **Telling the author at once.** Names the reporter. Rejected for withholding while open (ruling 2).
- **Showing the words on the report's GET.** A URL, a cache entry and a back button. Rejected for the
  POST's own response.
- **A step-up window for reading, as A2 has for irreversible acts.** A window would let a code opened
  for one entry open others. Rejected for a code per read.
- **A bond lock, to read the withdrawal under it.** Closes a cycle with the reporter's account erasure
  (decision 9). Rejected.
- **A `NO_WORDS` outcome.** Unreachable (decision 7). Rejected.

## Owed

- **The release review** of withheld `ENTRY_TEXT_READ` rows once their case closes (as ADR-0063 Q1),
  which widens `AdminAccessRows` to `ENTRY`.
- **The legal checks** of ADR-0063's open questions, before the console is switched on. Nothing here
  changes that.

## Revisit when

A second admin exists (a second pair of eyes on a read). Media-only entries are added (decision 7's
throw). The legal check answers (`Disclosure.shows` is the one place the rule lives).
