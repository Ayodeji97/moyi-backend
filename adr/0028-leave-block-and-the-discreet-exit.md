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

**7. A bond already in `PENDING_DELETION` is untouched.** `Bond.end` changes only a bond
that is still open, so a block during B5's deletion cooling-off writes its `blocks` rows and
leaves the status — and everything else — exactly as the deletion job expects to find it.

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
