# ADR-0058 — Account deletion: asked for with the password, cancellable for 14 days, carried out by one event every module erases from

**Status:** Accepted · **Date:** 2026-11-09 · **Deciders:** Daniel, for FR-008, FR-085, doc 06 §3.2
and doc 07 §7; decisions 1, 2, 3, 6 (the address) and 7 to 10 (the details of each module's share)
were made without him, as defaults issue #125 left to the backend, and are **his to overrule**
(Open questions, below).

## Context

FR-008 (MUST): "A user MUST be able to request account deletion; honoured within 30 days with a
14-day cancellation window." FR-085 lists a transactional email for "deletion confirmation". Doc 06
§3.2 has `POST /me/deletion-request` ("`202`, 14-day cancellation window") and `DELETE` ("Cancel").
Doc 07 §2 sketches `deletion_requests(id, user_id, requested_at, execute_after, cancelled_at,
executed_at)`, and §7 says how: **anonymise rather than cascade**; the user row becomes `DELETED`
with its personal data nulled; the entries the person wrote are content-erased and kept as
tombstones with `author_deleted_account = true` (BR-10), so the partner's streak survives; what the
partner wrote stays. Doc 09 §5 repeats the windows and names no re-authentication rule. Apple
requires in-app deletion where accounts can be created (doc 20).

Six ADRs had left work here: ADR-0030 (a member who left keeps their copy's fate through account
deletion), ADR-0036 (a member's favourites), ADR-0037 (their reactions), ADR-0050 (erasure through
`EraseEntry`, so the media goes too), ADR-0053 (a deleted reporter's details) and ADR-0057 decision
8 (their support tickets, emptied).

The shape of the code decides one thing before any rule does. `identity` owns the account, but
`bond`, `gratitude` and `support` depend on `identity`, and `media` is a leaf: `identity` cannot call
any of them. Ending a bond had the same problem in the other direction, and was solved with the
outbox (ADR-0035).

## Decision

**1. The request asks for the current password again** (`{"password": …}`). A session is not
enough for the one thing that cannot be undone; an unlocked phone in someone else's hand is the
case. Wrong is `401 INVALID_CREDENTIALS` **without** `WWW-Authenticate` (the session is fine; the
client must not sign out on it); absent, null or blank is `422 VALIDATION_FAILED` naming
`password`. It is verified outside every transaction, as login's is, and **a wrong one is not
counted towards the sign-in lock** (ADR-0020's `failed_attempts`): a thief holding a session could
otherwise lock the owner out of the sign-in they need to cancel. Doc 09 is silent. *Owner may
overrule.*

**2. Nothing else changes when it is asked for.** `users.status` is **not** set to
`PENDING_DELETION`, which doc 07 §2 lists: `User.canAuthenticate` refuses that status, so the person
could not sign in to cancel, and the status they had (verified or not) would be lost on a cancel.
The request is its row in `deletion_requests` (V52). The person signs in and uses the app as before,
and **their other sessions are not ended**: a thief who has the password could otherwise sign the
owner out everywhere, and the owner would have nothing to cancel from. The email (decision 5) is how
the owner learns of it. *Owner may overrule.*

**Not built, and a departure from the corpus: doc 04 §8.2's pause.** Doc 04 says a pending
deletion is "identical to suspension: the Bond pauses, days are excluded from the streak, the
partner is told the Bond is paused." Suspension (§8.1, FR-113) is not built: no `SUSPENDED_MEMBER`
sub-state, no excluded days, no "paused" answer. Building it for this would be most of a slice of
its own, and it is a product question whether it should run here at all (a person who can still
write, per decision 2, has no reason to pause their partner). So during the 14 days both members'
bond goes on as it was. Listed as an open question, and doc 04 §8.2 is stale until it is answered.

**3. The two windows, and one `404`.** `cancellableUntil = requestedAt + 14 days`;
`deleteBy = requestedAt + 30 days` (FR-008's bound, derived and never stored);
`DeletionRequest.canBeCancelledAt` is strictly before `cancellableUntil`, and the job's
`execute_after <= now` is its exact complement. **After `cancellableUntil` the request cannot be
cancelled**; the job carries it out within the hour. `DELETE` is `204`, or one `404 NOT_FOUND` for
"nothing pending", "already cancelled" and "too late", byte for byte the same: the person can do
nothing about any of them. Repeating the `POST` while one is pending is the same request: `202` with
the same three dates and no second email (V52's partial unique index makes two at once one row; the
user's row lock serialises them first). *Owner may overrule* the "no cancel after 14 days" reading.

**4. The pending request is a field on `GET /me`**, `deletionRequest: {requestedAt,
cancellableUntil, deleteBy} | null`, the same shape the `202` answers. Not a `GET
/me/deletion-request`: the client reads `/me` on every start, a reinstall during the 14 days must
draw the pending state and its cancel from that read, and a second route would be a second round
trip on every start for a state almost nobody is in. Additive for a generated client (oasdiff: no
breaking change). Not on `UserResponse`, which sign-in and refresh send (ADR-0056 decision 5's
reason).

**5. Email: one at request, none at completion.** FR-085's "deletion confirmation" is sent when a
request is made, once per request (keyed by the request's id), to a **confirmed** address only
(ADR-0057 decision 6's rule: an unconfirmed address may be somebody else's). It says when it will
happen, that the person can still sign in and cancel, where (the account section's "Delete my
account"), that their words become blank placeholders in the shared archive, and what to do if they
did not ask. It goes the way the security notices go (`SendSecurityNotice`, after commit, async),
with the same accepted gap: a process death between the commit and the send loses the email, not
the request. **No email at completion**: the corpus names one email, and at completion the address is
the thing being erased; sending one would mean keeping it past the erasure. *Owner may overrule.*

**6. Carrying it out: identity's half, one transaction per account.** An hourly ShedLocked job
(`scheduling.AccountDeletionJob`, `0 41 * * * *`, batches of 50, 1,000 a run) calls
`identity.api.AccountDeletions.executeDue`. For each due request, under the user's row lock, the
request is read again (a cancel that committed meanwhile wins) and then, in one commit:

- the `users` row stays (doc 07 §7: anonymise, don't cascade) and becomes `DELETED` with
  `deleted_at`; `email` becomes `deleted-<id>@deleted.invalid`; `email_verified_at` and
  `avatar_media_id` are nulled; `display_name` becomes a placeholder nobody sees, because
  `UserDirectory` now answers **nothing** for a deleted account (as for an unknown id), so a
  partner's archive says "Former member";
- **the address is not replaced by a hash of it**, which doc 07 §7 says. A hash of an address is
  the address to anyone holding a list of addresses, and nothing here reads it back. The old address
  is free to be registered again. *Owner may overrule* (a keyed hash would be the alternative, if
  something needs to recognise a returning address);
- `credentials`, `refresh_tokens`, `devices`, `verification_tokens` and `consent_records` are
  deleted for the id, `tokens_invalid_before` is set, and `notification`'s push tokens are forgotten
  (`PushTokens.forgetAllOf`): every session and every way back in ends;
- `deletion_requests.executed_at` is set, and **`AccountDeleted {userId}` is published** to the
  outbox.

One account that fails does not stop the run: it is logged by id and exception class and taken again
next run, its transaction having committed nothing.

**7. `bond`'s share: every bond the person is still in ends as their leaving would end it**
(`EndBond.endEveryBondOf`, consumer `bond.account-deletion`). A leave, not a block: nothing stops the
partner meeting a new account of the same person, and the partner is told nothing a leave would not
tell them (doc 26 §2.1). The bond is archived, its live invite revoked and its open proposals
cancelled, as on a leave; a bond that never had a second member is archived; a bond in its
cooling-off keeps `PENDING_DELETION` (both agreed to destroy it, `Bond.end`). No withdrawal is
recorded (BR-10a is a different erasure, with `author_deleted_account` false). Member rows stay.
*Owner may overrule* (doc 04 §8.2 says only "the Bond transitions to `ARCHIVED`").

**8. `gratitude`'s share** (consumer `gratitude.account-deletion`), in every bond the person was
ever a member of, open or ended, bonds in id order and each under its lock (bond, day, entry):

- every live entry they wrote is erased **through `EraseEntry`**, the one routine (ADR-0050 Owed):
  words, search projection and vectors, prompt, the bookmarks and reaction on it, and its media go;
  a settled day keeps its status, so the partner's streak and milestones stand (BR-10, ADR-0041);
  a day still being written steps back, as on any erasure;
- every entry of theirs, erased now or before, gets `author_deleted_account = true` (BR-10);
- every bookmark they made and every reaction they left on the partner's entries are deleted
  (ADR-0036 and ADR-0037's Phase 5 items). A withdrawal keeps the withdrawer's reactions (ADR-0037
  decision 9) because it must be discreet; nothing here needs to be;
- their reports keep their reason, target, status and `reporter_member_id` (a member row's id,
  which no longer names a person), and **lose their `detail`** (ADR-0053's Owed item). A report
  about somebody else is still somebody's to decide. *Owner may overrule.*

What the partner wrote is the partner's and is not touched (doc 07 §7).

**9. `media`'s share** (consumer `media.account-deletion`): every asset the person owns that is not
already deleted, on an entry or on none, becomes `DELETED` and detached, with one `MediaReleased`
each, so `media.purge` deletes the objects after the commit, retrying until it can (ADR-0050
decision 2). Evidence holds and open reports keep their objects until a person decides, as on every
other deletion (ADR-0051, ADR-0053); the purge knows that rule and this does not restate it.
`media` is a leaf, so the event type is spelled there rather than imported.

**10. `support`'s share** (consumer `support.account-deletion`): every open ticket the person sent
is closed with its subject and body emptied, as an operator's close empties it, and logged
`CLOSED` by `account-deletion` (ADR-0057 decision 8). `user_id` is kept: it names the anonymised
account.

**11. Rate limit: `account:deletion:user`, five an hour per user**, greedy (one back every twelve
minutes), spent before the password is read, wrong passwords included. It is what bounds the
guesses a held session can make here, since decision 1 keeps them off the sign-in lock.

**12. Safe to re-run after a crash.** identity's share is one transaction per account; it commits
whole or not at all, and the next run takes it again. Each other share is an outbox consumer,
delivered at least once and retried until it succeeds, and each is idempotent: nothing live is left
to erase, the flag is already set, the rows to delete are gone, nothing open is left to close. None
needs another to have run first: the member rows the erasure reads are never removed.

## Consequences

**Positive:** FR-008 is end to end, with Apple's in-app requirement met. Six ADRs' owed items are
discharged. The erasure goes through the routines the other erasures use (`EraseEntry`, the media
purge, the operator's ticket close), so it cannot come to differ from them. A partner's archive,
days and streak survive, which is what BR-10 exists for.

**Negative:**

- Doc 04 §8.2's pause is not built (decision 2): during the 14 days the bond runs as before.
- A thief with the password can ask for the deletion; the owner has 14 days and an email (to a
  confirmed address only) to notice. An unconfirmed account gets no email at all.
- The request email can be lost to a process death after commit (decision 5's accepted gap).
- `gratitude`'s share is one delivery transaction, as the withdrawal's is (about twenty thousand
  entries within the dispatcher's sixty seconds; past that it would fail on every retry, and the cure
  is batches).
- Kept, by id only: `bond_members` and `blocks` rows (the partner's safety, and the tombstones'
  authorship); the notification ledger (`notifications`: ids, categories and times, no text);
  `idempotency_keys` (reaped within a day, ADR-0031); `entry_report_log` and `media_moderation_log`
  (the operators' record). Deleted data survives in the nightly backups for their 30 days
  (ADR-0043), which NFR-052 allows; a restore must re-apply the deletions carried out since the
  backup it restores (doc 07 §8), and `deletion_requests.executed_at` is the list.
- Not built and not in scope: FR-009 export before deletion (no export exists); the admin console's
  view of deletions.

**Neutral:** `scheduling` now depends on `identity`'s `api`. `users.status` `PENDING_DELETION` stays
in the CHECK and the enum, unused. The contract gains two routes and one nullable field; oasdiff
reports no breaking change.

## Alternatives considered

- **`users.status = PENDING_DELETION` during the window** (doc 07 §2). Rejected by decision 2: it
  stops sign-in, so the person cannot cancel, and it loses the status a cancel should restore.
  Right if the owner rules that a pending account may not be used, with a sign-in that answers only
  "cancel".
- **Revoking every other session at request time.** Rejected by decision 2's thief case. Right if the
  owner prefers the lost-phone case (the request made from the owner's other device, the phone's
  session ended) over the stolen-password case.
- **One orchestrator calling every module in one transaction.** Impossible without a cycle (identity
  is depended on by three of them), and one transaction across every module's rows would hold every
  lock at once. The outbox is how this code base already crosses that boundary.
- **A `GET /me/deletion-request`.** Rejected by decision 4. Right if `/me` grows heavy enough that
  clients stop reading it on start.
- **An email at completion.** Rejected by decision 5. Right if the owner wants one: the address would
  be read before the erasure, in the same run, and sent after the commit.

## Open questions (the owner's)

1. Password required, and wrong passwords kept off the sign-in lock (decision 1)?
2. Sign-in kept and other sessions left alone during the 14 days (decision 2)?
3. Doc 04 §8.2's pause: build it (with suspension), or amend doc 04 to "the bond goes on"?
4. No cancel after 14 days; carried out within the hour (decision 3)?
5. No completion email (decision 5)?
6. The address replaced by `deleted-<id>@deleted.invalid`, not a hash (decision 6)?
7. A leave, not a block, for each bond (decision 7)?
8. Reports keep their reporter's member id and lose only the detail (decision 8)?

## Revisit when

Suspension is built (decision 2's pause becomes cheap); the export (FR-009) exists (offer it before
the request, as the block confirm does); a person reports losing an account they did not delete; or
the `gratitude` delivery for one account approaches its sixty seconds.
