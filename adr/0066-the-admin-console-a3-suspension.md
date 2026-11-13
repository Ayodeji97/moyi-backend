# ADR-0066 — The admin console (A3): an ADMIN suspends an account, and every bond it is in pauses for the partner

**Status:** Accepted, provisional where marked · **Date:** 2026-11-13 · **Deciders:** the owner asked
for A3 to be built without stopping for questions, so every decision the corpus did not already make
is a ruling made by the lead for the owner and **his to overrule**; each says what it costs if wrong
(spec §3). · **Amends:** ADR-0055 (`ADMIN` gains a second act `MODERATOR` lacks), ADR-0058 (spans
outlive an erasure; doc 04 §8.2 is still owed, and now cheap), ADR-0034 (a second kind of pause,
judged by overlap rather than at a day's end)

## Context

FR-113 is a MUST and the console could not suspend: the only way to stop a member was to erase
them. The schema has carried `users.status = 'SUSPENDED'` since V2 and `User.canAuthenticate`
already refused it, but nothing set it, and doc 04 §8.1's pause (the bond's `SUSPENDED_MEMBER`
sub-state, a read-only partner, `SUSPENDED` days, "told it is paused, nothing about why", resume on
lift) had no code.

The spec (`docs/superpowers/specs/2026-11-13-admin-console-a3-design.md`) holds the detail, the
plan (`docs/superpowers/plans/2026-11-13-admin-console-a3.md`) the build. This record is what was
decided, including what changed while it was built.

## Decision

✱ marks a departure from the brief's starting rulings or from doc 04's wording.

### Who acts, and how

1. **`ADMIN` only.** A suspension signs a person out and stops their innocent partner writing:
   reach beyond one item, which doc 09 §4 gives to `ADMIN`. *Cost:* with one operator, none.
2. **Two routes, each its own act:** `POST /admin/users/{userId}/suspend` and `…/unsuspend`, offered
   on the A1 lookup result. Order, each step before the next: role → id → justification
   (`ModerationNote`; refused, the form again with `422` and no code spent) → code (proved in this
   request by `SecondFactor.proveNow`, its `ADMIN_STEP_UP` row committed on its own) → the act, in
   `SuspensionConsole`'s transaction with its audit row → a `303` to `GET /admin/users?q=<id>` with
   one line, never the note. The controller is not `@Transactional` (A5's reason). A code per act.
3. ✱ **Idempotent, and every attempt that reaches the account is audited.** Suspending a suspended
   account, or lifting one that is not, changes nothing and is still an `ACCOUNT_SUSPENDED`
   (`ACCOUNT_UNSUSPENDED`) row, `REFUSED`, `metadata.result = ALREADY_SUSPENDED` (`NOT_SUSPENDED`).
   A spent code beside no act would read as a gap in the record. *Cost:* one row per mistaken click.
4. **Who can be suspended:** `ACTIVE` and `PENDING_VERIFICATION`. `DELETED` (and `PENDING_DELETION`,
   which nothing writes) is `GONE`; an id that names nobody is `NO_SUCH_ACCOUNT`, answered with the
   404 page and audited without a target. An account with a pending deletion request can be
   suspended, and the page says when the deletion runs.
5. ✱ **Lifting returns the status the account earned:** `ACTIVE` if the address is verified, else
   `PENDING_VERIFICATION`. No "prior status" is stored, so none can drift. A verification recorded
   while suspended counts.
6. **The member effect, in one transaction in `identity`:** the sessions advisory lock, then the
   `users` row lock (ADR-0058 decision 6's order); `status = SUSPENDED`; `tokens_invalid_before` =
   the next whole second; every refresh family revoked; every push token forgotten; a span opened in
   `account_suspensions` (V65). Lifting takes the same two locks, sets the earned status and closes
   the span. Lifting restores no session. `now` is read after both locks.
7. ✱ **Sign-in re-checks the account under the sessions lock and mints the access token inside
   that transaction** (`LoginUser`). Otherwise a sign-in whose password check straddled the
   suspension's commit would leave a live refresh family after the revocation. The re-read refuses
   with the same `InvalidCredentialsException` when the account can no longer authenticate or its
   password hash is no longer the one just verified (which closes the same race against a reset).

### The paused bond

8. ✱ **"Paused" is derived, never stored on the bond** (doc 04's `SUSPENDED_MEMBER` sub-state, as a
   boolean, not a `BondStatus`). A bond is paused when it is open and a member who has not left holds
   an open span. `bond` asks `identity` through a read port, `AccountStanding`, which only `bond`
   imports. Suspension and lifting are one write each, both in `identity`; no bond, day or entry row
   or lock is touched. A new `BondStatus` value would break every generated client's exhaustive
   `when`. *Cost:* one indexed read per membership resolution, bond view and closing view.
9. **Read-only for entries, and only for entries.** `409 BOND_PAUSED` on submitting an entry (late
   entries and offline drafts included), editing one (which is how media is attached) and `PUT` of a
   reaction. Everything else still works: reads, search, archive, export, erasing one's own entry,
   removing one's own reaction, bookmarks, reports, settings, leaving, blocking, bond settings and
   proposals. A partner is never kept from leaving or reporting. An idempotent replay of a write
   accepted before the pause answers as it did. *Cost:* a proposal can lapse unanswered.
10. **The signal:** `paused: boolean`, required, on `BondResponse` and `TodayResponse`, in the
    bond's ETag digest; no reason, date or member. Today's `prompt` is `null` while paused. No push
    for the pause or its end; the daily reminder is not sent in a paused bond, on-this-day still is.
    *Cost:* a partner who never opens the app is not told.
11. **Invites:** an invite into a bond whose creator is suspended answers, on resolve and on accept,
    exactly what a revoked invite answers. It is not revoked, and works again after the lift.
12. **The suspended member is told nothing.** Doc 06's identical `401` stands, ADR-0020's gap is
    unchanged, no email is sent. Whether DSA Art. 17 needs a statement of reasons is owed (below).
13. **Doc 04 §8.2 stays unbuilt** (ADR-0058 decision 2). It is now cheap: a pending deletion request
    would be a second source for decision 8's derivation and decision 16's day rule. Pausing the
    partner of someone who is still signed in and writing is the owner's product call.
14. **No operator route.** No SQL function and no `moyi` command: one would restate decision 6 in a
    second place. `scripts/moyi`'s `write` prints `409 BOND_PAUSED` as it prints any refusal.
15. **A deletion request already pending is carried out on time, suspended or not.** The right to
    erasure is the person's. The span outlives the erasure (dates and an anonymised id).

### Days

16. ✱ **A day any part of whose writable window fell inside a suspension span is not one anybody
    missed.** The writable window is `[startsAt, endsAt + LateEntryGrace)`, on the day's bounds after
    `extendedTo` (a westward zone change lengthens it). `OPEN`/`PARTIAL` close `SUSPENDED`, not
    `EMPTY`/`SOLO`: no lone entry is unlocked, no `DayClosed`, the streak walk skips it, no freeze is
    spent. `REVEALED` stays `REVEALED`. A missing day that overlaps is written `SUSPENDED`. **Overlap,
    not the day's end** (the called-off deletion's rule, ADR-0034's `wasPausedAt`): nobody but an
    `ADMIN` makes a span, and the lifted member is told nothing, so the day of the lift must not be
    one they can miss. *Cost:* the day of a suspension and the day of its lift can never break a
    streak, nor extend it unless both write.
    **Amended by the controller, 2026-11-13:** the first text judged `[startsAt, endsAt)`. A late
    entry is refused while paused, so a span that begins in the grace stops the day being finished
    as surely as one inside it; the grace is part of the window, in `CloseDay`, `CreateMissingDays`
    and `EvaluateStreaks` alike, from the one `LateEntryGrace` bean.
17. **A reveal that falls due inside a span is withheld**: the day closes `PENDING_REVEAL`, nothing
    stamped, `COMPLETE` (ADR-0035 question 9's treatment of an ended bond). Judged at
    `revealFallsDueAt`, never at the run's time. **A reveal caused by the partner's own second entry
    after the lift is allowed** (controller's ruling): `SubmitEntry` reveals at once even if the
    reveal time fell inside the span; it is the partner's act, not time passing.

The decision for a day is stable from the end of its grace: spans only ever begin at now, so none can
reach back over a window that has ended.

### Records and disclosure

- **V65** (`identity`): `account_suspensions` (one open span per user by a partial unique index; no
  actor and no reason, which are the admin's `audit_log` row). **V66** (`admin`): `audit_log`'s action
  check gains `ACCOUNT_SUSPENDED` and `ACCOUNT_UNSUSPENDED`.
- The row: `target_type = USER`, `justification` = the note, `metadata = {result, statusBefore,
  deletionPending}`. Metric `moyi.admin.suspension{action, result}`; one INFO log line per act with
  ids and the result, never the note.
- **The export withholds both actions, whatever the outcome** (`Disclosure.shows`, an explicit case).
  A suspension is usually report-driven, and in a bond of two the reporter is the partner. The
  release review owed for withheld rows (A2, A5) now covers these too.

### Plan rulings on the spec

1. **The date shown and returned is `cancellableUntil`, not `deleteBy`.** `ExecuteAccountDeletions`
   carries a request out from `execute_after` = `cancellableUntil` (day 14); `deleteBy` (day 30) is
   only the outer bound. The field is `deletionDueAt`.
2. **Erasure leaves a suspended account's span open.** Closing it at erasure would unpause the
   partner's bond between the erasure's commit and the outbox consumer that ends the bond. The
   invariant tested is "`SUSPENDED` ⇔ open span" over live accounts.
3. **The code prompt's copy is new** (`CODE_NEEDED_FOR_ACT`: "Enter a code from your authenticator
   app. Each suspension, and each lift, needs a new one."): A2's "This cannot be undone" is false
   here.
4. **`BOND_PAUSED` sits immediately after `BOND_ARCHIVED` where that check already is.** In
   `ReactToEntry` that is after `REACTION_ON_OWN_ENTRY`/`ENTRY_IMMUTABLE`; spec §9's precedence holds
   relative to `BOND_ARCHIVED` and is not reordered against conflicts that already precede it.

### The controller's rulings while it was built

- **Verifying an email and applying a password reset never overwrite a suspension.** Both read the
  user before the locks and saved the entity back, which could write `ACTIVE` (or an older
  `tokens_invalid_before`) over a suspension that committed in between. `VerifyEmail` now flips the
  status in one conditional `UPDATE … WHERE status = 'PENDING_VERIFICATION'` and never writes
  `tokens_invalid_before`; `ApplyPasswordReset` takes the sessions lock before its read and keeps
  `tokens_invalid_before` with `GREATEST`. Each has a race test.
- **The overlap window includes the late-entry grace** (decision 16's amendment).
- **Known limit, accepted:** a legacy C1 joining day (a row written before the bond became two)
  whose reveal fell due inside a span that was lifted before the day was first read can be revealed
  by that first read. `ReconcileJoiningDay` withholds while the bond is paused, but cannot see a
  span already closed; a guarded API for it is not worth its cost. *Cost:* one legacy day revealed
  after a lift.
- **A reveal triggered by the partner's own second entry after a lift is allowed** (decision 17).
- **The suspension form's code-field hint is the implementer's wording, awaiting the owner's
  approval:** "Each suspension, and each lift, needs a new code." The justification field is labelled
  "Justification" (`DecisionForm.Wording.SUSPENSION`).

## Consequences

**Positive:** FR-113 is met. A suspension ends every session at once, and no sign-in, refresh, reset
link or verification that straddles it brings the account back. The partner keeps everything they had
but new entries, edits and reactions, and loses no streak and no freeze to days they could not write.
Neither member is told why, and an invite prober learns nothing.

**Negative:** a mistaken suspension costs a couple the exchange of any reveal that fell due inside it
(decision 17). A partner who never opens the app is not told their bond is paused. The suspended
member cannot tell a suspension from a forgotten password (decision 12). A reset link issued before
the suspension still works (Owed).

**Neutral:** production is unchanged until the console is switched on (`ops/admin/README.md`). The
contract gains `BOND_PAUSED` (a new response enum value: breaking under oasdiff, so the pull request
carries `breaking-api-change`) and two required booleans.

## Alternatives considered

- **A `BondStatus` value `PAUSED`.** Breaks every client's exhaustive `when`, and needs a
  cross-module write at both edges. Rejected for the derived boolean (decision 8).
- **Reusing `bond_write_pauses` (V19).** It records a stretch the bond refused writes, written when
  it closes; a suspension is an account's, open while it lasts, and `identity` cannot write `bond`'s
  tables. Rejected; the close job asks both questions through `BondClosingView`.
- **The day's end, as for a called-off deletion.** Would let the day of the lift be missed by a
  member who was never told. Rejected for overlap (decision 16).
- **An operator command.** A second copy of decision 6. Rejected (decision 14).

## Owed (the owner decides)

- **The DSA Art. 17 notice:** whether a statement of reasons must be sent to the suspended member.
  It joins the legal checks the console already waits on (ADR-0063); if it must, that slice adds an
  email.
- **Decision 17's withheld reveal:** whether a mistaken suspension may cost a couple that exchange,
  or whether a lift should release reveals withheld by a span.
- **Decision 15:** whether a suspended person's pending deletion should run, or wait for a decision.
- **Doc 04 §8.2** (a pending deletion pauses the bond): now cheap (decision 13); the owner's call.
- **Corpus amendments** in the documents repository: doc 04 §8.1 (the sub-state is a derived
  `paused`; overlap; withheld reveals), doc 06 (`BOND_PAUSED`, `paused`), doc 07
  (`account_suspensions`).
- **moyi-app:** the paused state's screen and copy, `BOND_PAUSED` on a queued draft (keep the draft
  and say why nothing sent), and `paused` in the generated client.
- **A suspension does not yet invalidate outstanding password-reset links.** One issued before the
  suspension still replaces a suspended account's password for the rest of its hour (the account
  stays suspended, and a reset never writes the status). The suspension should revoke them.
- **The release review** of withheld audit rows (A2, A5, and now A3).

## Revisit when

A suspension needs an end date or a reason taxonomy; a second operator joins (a second pair of eyes
on a suspension); the legal check answers; doc 04 §8.2 is ruled on.
