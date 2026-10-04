# ADR-0028 — Leave, block, and the discreet exit

**Status:** Accepted · **Date:** 2026-09-27 · **Deciders:** Daniel

## Context

Slice B3 builds the two endpoints that end a bond: `POST /bonds/{bondId}/leave` (FR-026)
and `POST /bonds/{bondId}/block` (FR-029). They are the last thing a member can do to a
bond before Phase 3 gives them entries to write in it, and the first place this system has
to be careful about *what a refusal tells the person refused*.

Three requirements set the shape.

**FR-026 wants leaving to be unilateral and quiet.** No consent, no notification. T-09
names the reason: a person leaving an unsafe relationship must be able to leave without a
message arriving on the other phone.

**FR-029 wants blocking to prevent any further contact or invitation between two
accounts** — and doc 07 §2 scopes a block to the bond it was made in, because FR-025
permits three bonds and nothing stops the same two people sharing two of them.

**Doc 26 §2.1 is the constraint that binds the other two together:** *"blocking must be
indistinguishable from leaving, from the other side."* Doc 26 calls the moment a block is
discovered the point where retaliation risk peaks. So the blocked person must not be able
to tell they were identified as a threat rather than simply left — not from a status, not
from a notification, not from a field, not from a different word, and (the part that is
easy to miss) not from a **counter**.

`states.md` §9 adds the read rule and OQ-09 left it open: does an archived bond stay
readable for the member who left? It does, and building it is what settled the question.

## Decision

**1. One service, two entry points.** `EndBond.leave` and `EndBond.block` share every
statement that matters — the bond's row lock, the `left_at`, the archive, the revoked
invite, the silence. Block adds the `blocks` rows and omits one check. The
indistinguishability doc 26 requires is therefore a property of *one implementation*,
rather than something two implementations have to keep agreeing about as later slices
touch them.

**2. Leave refuses an archived bond; block does not — and on an archived bond a block
changes nothing visible at all.** `leave` is `409 BOND_ARCHIVED` on a bond that has already
ended. `block` is accepted there, because blocking somebody who left first is precisely the
case FR-029 exists for. On such a bond it writes the `blocks` rows and **`Bond.end` returns
the aggregate unchanged** — no `left_at` for the blocker, no status change, no version bump.

That last clause is the whole of doc 26 §2.1 in one line, and it was nearly wrong. The
first implementation stamped the blocker's `left_at`. The member who left keeps read access
to the archive (`states.md` §9), `MemberResponse` shows every member's `leftAt`, and block is
the *only* mutation an archived bond accepts — so a `leftAt` appearing on the other member
could mean exactly one thing, and the person reading it is the person T-09 says must not be
told. **Found by the Codex review bot on PR #39**, after a test suite that compared two
bonds ended by the same member and therefore never looked at the asymmetric order.
`DiscreetExitTest` now has that case: Bea leaves, Ada blocks, and Bea's `GET /bonds/{id}` is
byte-identical before and after, `ETag` included.

Leaving the blocker unstamped costs nothing. FR-025 counts memberships in *open* bonds, so
no slot is held; the record of the block is the `blocks` row, which no response exposes; and
`left_at` keeps one clear meaning — **it is set only by ending a bond that was still
open, and only for the caller.**

**3. A block is one row per other member, current or left**, written with `INSERT … ON
CONFLICT DO NOTHING`. Someone who walked away is still someone this account does not want
to meet again. The upsert is not tidiness: FR-029 permits repeating the call, so a
check-then-insert would turn two concurrent blocks into a constraint violation the caller
sees as a 500.

**4. Archived means read-only for both members, the one who left included** (OQ-09
settled, `states.md` §9 confirmed). Both keep `GET /bonds/{bondId}` and stay in each
other's `GET /bonds`. Every other bond-scoped write is `409 BOND_ARCHIVED` — including
`DELETE /bonds/{bondId}/invites/{inviteId}`, which previously answered the `404` of a
necessarily-dead invite. That `404` was true and said less than the member is entitled to,
and it made the rule "every write on an archived bond is `BOND_ARCHIVED`" nearly rather
than actually true.

**5. The `ETag` must match.** A bond's `ETag` is its row version, so a block that wrote to
the bond row one more time than a leave did would hand the other side a counter: nothing in
the JSON would differ, and the version would. `Bond.end` therefore returns an object equal
to its receiver when there is nothing to change, and `EndBond` writes only when the
aggregate actually changed. `DiscreetExitTest` asserts the `ETag`s of two identically-built
bonds — one left, one blocked — are the same, and `scripts/smoke.sh` asserts it again on the
wire.

**6. Both paths take the bond's row lock** (`SELECT … FOR UPDATE`), the lock `AcceptInvite`
and `CreateInvite` already take. Without it a leave and an accept are two transactions over
READ COMMITTED snapshots: the accept sees a free seat, the leave sees an open bond, and both
commit, leaving somebody an active member of an archived bond.

The shared application configuration disables Open Session in View. Otherwise the
access guard's entity remains cached across service transactions, and the read after
acquiring the row lock can reuse its stale version. Concurrent leave/accept and
leave/block requests then fail with an optimistic-lock exception despite holding the
lock. Local and application tests inherit this setting; `JpaTransactionScopeTest`
checks that the web interceptor is absent so a profile-only fix cannot hide the issue.

**6a. The rule the slice ended up with: any bond-scoped write that reads `isOpen` takes the
row lock first.** Added after the review of PR #39, which found the third instance of the same
shape — `RevokeInvite` read the bond as open, a concurrent leave archived it *and* revoked its
live invite, and the conditional UPDATE then matched nothing, so the caller got a `404` that
neither serial ordering produces (revoke first is `204`, leave first is `409`). Leave, block,
create-invite, accept and revoke-invite all take it now, and slice B4's `PATCH` does too. The
lock is cheap on a two-person aggregate; the class of bug it closes has now appeared three times
in two slices.

Honest note on the evidence: removing that particular lock did **not** reliably reproduce the
`404` — one failure in five runs with a single pair, none in three with four pairs, because the
window between the read and the update is tiny. The fix rests on the ordering analysis rather
than on a reproduction, and `EndBondRaceTest` says so where somebody might otherwise read it as
a proof.

**6b. Why the read under the lock is fresh, per caller (the audit ADR-0031 owed; added
2026-10-04).** Slice C1 found that `BondAccess.lockMembershipOf` took this lock and then
"re-read" a bond Hibernate still had in its persistence context, because the guard had loaded it
earlier in the same transaction (ADR-0031 decision 17). It was fixed there with
`lockBond(refreshReads = true)`, and the eleven other `lockBond` call sites were left with a
reason: *they all write the bond afterwards, so a stale read meets `@Version`.* That reason is
false, and the callers are correct anyway, for a different one.

| Call site | In the persistence context before the lock | Decides, after the lock, on | Writes `bonds`? |
|---|---|---|---|
| `AcceptInvite.accept` | the invite (and the caller's account); not the bond, not a member | already a member, `hasRoom`, blocks; then spends the code by compare-and-set | yes |
| `UpdateBond.patch` | nothing | `isOpen`, `If-Match` | yes |
| `EndBond.leave` | nothing | `canBeEnded` | yes; **not** during a cooling-off, where only `left_at` moves |
| `EndBond.block` (same `lockAndLoad`) | nothing | the member rows, one `blocks` row per other member | as `leave` |
| `ChangeTimezone.propose` | nothing | `isOpen`, the 30-day rule, `PENDING_MEMBER`, a live proposal | **only** with one member |
| `ChangeTimezone.confirm` | nothing | `isOpen`, the proposal, the 30-day rule | yes |
| `ChangeTimezone.cancel` | nothing | the proposal (a query, then a compare-and-set); never the bond | **no** |
| `MemberSettingsService.replace` | nothing | `isOpen`, the caller's member row | **no** |
| `RevokeInvite.revoke` | nothing | `isOpen` | **no** |
| `CreateInvite.forBond` | nothing | `isOpen`, `hasRoom` | **no** |
| `RequestDeletion.request` | nothing | status, `isOpen`, a live proposal, how many active members | **only** when the cooling-off starts |
| `RequestDeletion.cancel` | nothing | the caller still active, the proposal, status | only when already counting down |

Nine of the twelve rows do not write the bond row on at least one path, so `@Version` has
nothing to compare; and a leave during a cooling-off does not move the version at all, so
`RequestDeletion.cancel` would not be caught even where it does write. What keeps the ten
member-scoped call sites correct is that **`lockBond` is the first statement of a transaction
that starts with an empty persistence context**: the controller's guard read ran in its own
`readOnly` transaction, which is over, and the service receives a `Membership` value and no
entity. `AcceptInvite` has no guard and does load something first, the invite, and is correct
because the only decision taken on that copy is a compare-and-set that asks "still live?" in the
`UPDATE` itself.

So none of them takes `refreshReads = true`, and none should: there is nothing in the context to
refresh. `BondLockFreshReadTest` holds it — one request per call site, sent through its
controller, observed queued behind a transaction that holds the bond's row lock and then commits
what a real leave, accept, revoke or confirmation would. What each failure looked like with the
bond loaded one line above `lockBond`:

- **Wrong answer, nothing failing** (the bond row is not written): on a bond that had ended,
  propose answered `200`, create-invite `201`, a first deletion request `202` and member
  settings `200`; deletion-cancel answered `204` to a member who had left. Revoke and confirm
  answered the `404` neither serial ordering gives. (The statuses are what was observed; each
  test stops at its first failed assertion, so the rows behind them were not inspected.)
- **`500`** (the bond row is written and `@Version` objects): patch, leave, block, the
  one-member propose. `@Version` did catch these, as an optimistic-lock failure the client is
  shown as a server error — a backstop, not an answer.
- **`AcceptInvite`** answered `200` for an archived bond, in the test that leaves the code
  live; and `200` for a revoked code once the compare-and-set's result was ignored.
- **`ChangeTimezone.cancel`** needed two mutations at once — the proposal read before the lock
  *and* the compare-and-set weakened. Either alone stays green: that path is safe twice over.

Two tests were written and not kept: a new invite, and a deletion request, each queued behind an
*accept*. Both stayed green with the bond stale. They decide on how many member rows there are,
and a row another transaction **inserted** is new to the persistence context, so a query returns
it fresh; only rows that were *updated* hide behind the identity map.

The property is now held on purpose rather than by default. `open-in-view` is `false` and
`JpaTransactionScopeTest` holds that (decision 6); `ArchitectureTest` now refuses any import of a
transaction API in a module's `web` layer; and `BondLockFreshReadTest` goes red when either is
undone — eleven of its fourteen tests with `open-in-view` on, and both of
`BondInvitesController`'s when that controller was made `@Transactional`, the one controller
tried. **A new caller that runs the guard and takes the lock in one transaction is not covered
by any of this** and must pass `refreshReads = true`, as `lockMembershipOf` does; a `gratitude`
service calling `BondAccess.membershipOf` before `lockMembershipOf` is that shape, and is why
the port refreshes.

Three more ways to join the guard's transaction to the service's are **not** held by anything:

- **A transaction opened around the handler from outside a domain module** — a filter or an
  interceptor in `common:web`, `common:security` or `app`. The architecture rule is scoped to
  the domain modules' `web` layers and would not see it.
- **A transactional base class or meta-annotation declared in another layer** and used by a
  controller. The rule reads the imports of files in `web`, and the import would be elsewhere.
- **A hand-registered `OpenEntityManagerInViewFilter`.** `JpaTransactionScopeTest` asserts that
  no `OpenEntityManagerInViewInterceptor` bean exists; the filter is a different class and
  would pass it.

By reasoning, not by a run: `BondLockFreshReadTest` should go red for any of them that reached
the bond controllers in the bond test context, and would not for one wired only in `app`.

**An option, not built.** `lockBond` could refuse at runtime when the bond is already managed in
the persistence context and `refreshReads` is false, which would close every gap above at the
one place they all pass through. It is a production behaviour change — a new way for a bond
write to fail — so it was not built here, and it is the owner's decision.

**Found by the audit and not changed.** `AcceptInvite.accept` reads `now` before it waits for
the lock, so an invite that expires while the accept is queued is still accepted: `consume`
compares `expires_at` with that earlier instant. It predates this audit, the window is the
length of the wait, and it is not a stale persistence context; it is written here so that it is
not rediscovered as new.

**7. A bond already in `PENDING_DELETION` keeps its status.** `Bond.end` leaves the status
alone there, so a block during B5's deletion cooling-off writes its `blocks` rows and the
deletion job still finds what it expects.

**Amended 2026-09-28 (slice B5, ADR-0030 §4a).** This decision originally said the bond was
*untouched*, and that was wrong in a way the review of PR #41 found: the membership was left
alone too, so blocking during a cooling-off recorded the block and nothing else — and the other
member's cancel then returned the bond to `ACTIVE` with the blocker inside it. `end` now stamps
`left_at` in `PENDING_DELETION` and keeps only the *status*; `leave` is permitted there as well,
because refusing one while permitting the other would make them distinguishable, which is what
§2.1 forbids and what this ADR exists to prevent.

**8. Nothing anywhere says "block".** No response body, no error code, no log line. The
service logs "a member ended bond {id}" for both paths and names no user (doc 18 §5).

## Consequences

- **This is not a breaking API change** — the first Phase 2 slice that is not. No new
  `ErrorCode`: `BOND_ARCHIVED` arrived with B2. The generated contract gains two paths and
  one `409` on an existing operation, and nothing is removed or renamed, so a client
  generated from the previous document still compiles. The PR carries no
  `breaking-api-change` label; the oasdiff job's verdict is the check, not this paragraph.
- **B4 inherits the archived rule.** Its `PATCH /bonds/{bondId}` and settings `PUT` must
  answer `409 BOND_ARCHIVED`, and they get it from `Bond.isOpen` the same way.
- **B5 has two obligations here.** Ending a bond must also cancel any live proposal —
  `bond_proposals` does not exist yet, so `EndBond` has nowhere to do it; the note is in
  the plan and in this ADR rather than in a `TODO`. And `PENDING_DELETION` is preserved by
  decision 7, which B5 should test rather than assume.
- **FR-029a — withdrawing your entries on a block — is Phase 3**, when there are entries to
  withdraw. Doc 26 §5.1's open question (whether withdrawal destroys the author's own copy)
  is still Daniel's to answer.
- **`left_at` means one thing only, and later slices must keep it that way:** the caller
  ended a bond that was open. Anything that stamps it for another reason re-opens the oracle
  decision 2 closes.
- **The `blocks` table now has a writer.** Its rows are read by `AcceptInvite` in both
  directions (B2), and nothing ever deletes one. If a block is ever liftable, that is a
  delete and a new decision.

## Alternatives considered

- **A distinct status or error code for a bond ended by a block.** Rejected: it is exactly
  the oracle doc 26 §2.1 and T-09 forbid, delivered to the one person who must not have it.
- **Stamping the blocker's `left_at` when they block an already-archived bond.** Rejected
  once the review showed what it discloses (decision 2). The alternative fix — dropping
  `leftAt` from `MemberResponse` — was considered and rejected as the wrong lever: the field
  is honest information about who ended the bond, and it is the *change* after the fact that
  leaks, not the field.
- **Deleting the member row on leave.** Rejected: `states.md` §9 keeps the archive readable
  for both, so "is this person a member of this bond" has to stay answerable — and FR-029's
  block check needs the person who walked away to still be findable.
- **Blocking at the account level rather than per bond.** Rejected for now: doc 07 §2 scopes
  the row to a bond, and FR-025 allows three, so blocking someone in one bond is not a
  statement about another. The `existsBetween` query ignores the bond, so the *effect* today
  is account-wide for invitations — which is what FR-029 asks for — while the *record* stays
  per bond. If a user-facing "blocked accounts" list is ever built, that asymmetry is the
  first thing to revisit.
- **Letting the leave path notify the other member** ("Amara has left"). Rejected: FR-026
  and T-09 both say silence, and a notification would also be the difference doc 26 §2.1
  forbids unless the block path sent the identical one — at which point it is a message
  nobody needs.
- **A `DELETE /bonds/{bondId}` instead of `POST …/leave`.** Rejected: nothing is deleted,
  and `DELETE` would be wrong twice over once B5 adds a real deletion request with a
  cooling-off period.

## Revisit when

- B5 lands `bond_proposals` — ending a bond must cancel a live proposal, and this ADR is
  where that obligation is recorded.
- Phase 3 gives entries, and FR-029a's withdrawal option needs a decision.
- A support tool or a user-facing list ever needs to read blocks by blocker, which no
  query here allows on purpose (T-09, T-10).
