# ADR-0030 — Two-party consent as one mechanism

**Status:** Accepted · **Date:** 2026-09-28 · **Deciders:** Daniel

## Context

Slice B5 is the last of Phase 2. It builds the two changes neither member may make alone:
moving the bond's anchor timezone (FR-027) and destroying the bond (FR-028).

They look like different features and they are the same rule. Both need the other person to
agree; both need a window after which an unanswered request stops meaning anything; both need
either member to be able to withdraw; and both have a screen in `states.md` that says so in
plain words — §8's three-step timezone flow ("Proposed. It changes once Tunde agrees, and
lapses in seven days if not") and §9's "close the box" with its 30-day cooling-off.

Two further things shape the design. **BR-6 and doc 04 §8.5** make the anchor zone special:
it decides which day an entry belongs to *for both members*, and a day already opened is never
recomputed — so a unilateral change would be a change to somebody else's past. And **doc 26
§4** says the close-the-box control must be absent on a bond ended by a block, because a
request would be a channel from the blocked party back to the blocker.

## Decision

**1. One table, `bond_proposals` (V10), for both kinds.** `kind` is `TIMEZONE_CHANGE` or
`DELETION`; `payload` carries the proposed zone and is null for a deletion. Two tables would
be two copies of one set of rules, and the second copy is where they drift apart.

**2. Lapsing is a predicate, not a state.** Nothing writes a row to say "this expired": every
read carries `expires_at > now`, and a proposal nobody answers simply stops counting.
`states.md` §8 words that for a person, and it means no scheduled job exists to go wrong.

**3. One open proposal per kind per bond**, held by a partial unique index — and the index
**cannot know that a row has lapsed**, because an index cannot ask what time it is. So
proposing closes a lapsed row of that kind first, under the bond's row lock, and then inserts:
the shape `CreateInvite` already uses for the outstanding code. This is the second of the two
decisions taken with Daniel before the slice was written. The alternative was dropping the
index and holding the invariant only in the application; keeping the index means the database
still refuses a second live proposal even if a future caller forgets the lock.

**4. Only the *other* member may confirm** (BR-6), and confirming is a **compare-and-set** on
the proposal row. Two confirmations arriving together produce one applied change, and the
loser is told there is nothing waiting — which, by the time it looked, is true.

**5. Either member may cancel, at any point.** `DELETE …/timezone` withdraws a proposal;
`DELETE …/deletion-request` withdraws a request *or* calls off a cooling-off already running.
Neither consults `isOpen`, and for the deletion that is load-bearing: `PENDING_DELETION` is
deliberately not open, so a cancel that checked would make the cooling-off impossible to
cancel — the one thing it exists for.

**6. The 30-day rule is checked twice.** A timezone change is refused when
`timezone_changed_at` is less than 30 days old, at **proposal and again at confirmation**,
because up to seven days pass in between and the window can close while a proposal waits.

**7. It answers `409 TIMEZONE_CHANGE_TOO_SOON`, not `429`.** Doc 06 §3.3 specified `429` and
is superseded: a month is not a rate limit, and a client that treated it as one would show
"please wait" and retry into the same wall. The detail names the **date** it becomes allowed,
in the bond's own zone — a fact about the caller's own bond, and a date rather than a
countdown for the reason `states.md` §9 gives about the deletion screen.

**8. While `PENDING_MEMBER` there is nobody to consent.** The creator's timezone change
applies at once (ADR-0004 expects an onboarding mistake to be correctable) and a deletion
request confirms itself. The proposal row is still written and marked confirmed by the same
member, because a bond in `PENDING_DELETION` with no record of who asked would be a state
nobody could explain later.

**9. `POST …/deletion-request` both asks and agrees**, and is idempotent for the same member:
a person tapping a button twice has not consented twice. `202`, never `200` — nothing is
destroyed when it returns; Phase 5's job reads `PENDING_DELETION` and `deletion_requested_at`
and acts.

**10. A deletion request is refused on *any* archived bond**, `409 BOND_ARCHIVED`, whether it
ended by a leave or a block. **This is a deliberate departure from the Phase 2 spec**, which
refused it only on a bond ended by a block — and that is an oracle: the blocked member would
learn which of the two happened by trying it once, which is exactly what doc 26 §2.1 forbids
and what the review of slice B3 caught in another form (the `left_at` stamp, ADR-0028). This
is the first of the two decisions taken with Daniel, and `DiscreetExitTest` and the smoke run
both compare the two refusals byte for byte. doc 26 §4's requirement is satisfied a fortiori.

The cost is real and worth stating: **a member who has left cannot start a mutual deletion.**
What they keep is their own copy's fate — account deletion (FR-008) destroys it — and export
(FR-009). If that ever proves too blunt, the answer is a one-sided deletion of the caller's
own data, not a request that travels to the other person.

**11. Ending a bond cancels every open proposal** — ADR-0028's obligation, which that ADR
recorded and could not discharge because this table did not exist. Without it a confirmation
arriving after a leave would try to move the anchor zone of a bond that has ended.

**12. No `If-Match` on any of these five routes.** ADR-0029's condition protects a blind
overwrite of fields the client last read; a proposal is a fresh intent about one named value,
and requiring the bond's `ETag` here would make the three-step flow fail whenever anything
else about the bond had moved in between — including the other member's own reminder time.
Every path still takes the bond's **row lock** (ADR-0028 §6a), which is what makes each
read-then-conditional-write one decision.

## Consequences

- **Three new `ErrorCode` values** (`PROPOSAL_PENDING`, `PROPOSAL_NEEDS_OTHER_MEMBER`,
  `TIMEZONE_CHANGE_TOO_SOON`), so this is a breaking change and the PR carries the label
  (ADR-0024's amendment). `BondResponse`'s three new fields are additive.
- **Phase 5 owns the deletion itself.** It reads `PENDING_DELETION` and
  `deletion_requested_at`; nothing in Phase 2 destroys a bond, and the cooling-off has no
  timer — the date is derived, and a bond simply sits there until something reads it.
- **Phase 3 inherits BR-6.** "Effective from the next Bond-day, never retroactively" has no
  meaning yet because there are no Bond-days; the day opener must read the anchor zone when it
  opens a day and must never recompute one that exists. `Bond.withAnchorTimezone`'s KDoc says
  so where a Phase 3 author will read it.
- **Phase 4 owes the notification** a pending proposal implies. Nothing tells the other member
  a change is waiting; they see it when they open the app, which is consistent with T-09 but
  is not what FR-027's screen assumes forever.
- **The proposal rows are the audit trail.** Closed proposals accumulate and nothing deletes
  them, which is deliberate: doc 26 §4 would want to know what was asked and what came of it.
- **`bond_proposals` is a delta from doc 07 §2**, which describes the rules without naming a
  table. The corpus is amended.

## Alternatives considered

- **Two tables, one per kind.** Rejected: the rules are one set of rules, and the duplicate
  would be where they diverge. The cost of one table is a nullable `payload`.
- **A `status` column instead of the lapse predicate.** Rejected: it needs something to write
  `EXPIRED`, which means a scheduled job, which is a moving part that can be down. A predicate
  cannot be down.
- **Reaping lapsed proposals on a schedule.** Rejected for the same reason, and the lazy close
  in §3 costs one `UPDATE` on the rare path where a lapsed row is in the way.
- **`410 Gone` for a lapsed proposal.** Rejected: it says the proposal was once real, which is
  the FR-024 lesson from B2. Never made, already answered, cancelled and lapsed are one `404`.
- **Allowing the proposer to confirm after a delay** ("if they do not answer in seven days, it
  applies"). Rejected outright: FR-027 is consent, and consent that arrives by default is not
  consent. A lapsed proposal is a proposal that failed.
- **A separate `POST …/deletion-request/confirm`.** Rejected: `states.md` §9 draws one button,
  and a second route would be a second way to say the same thing — with its own idempotency
  question to get wrong.
- **Refusing the deletion request only on a blocked bond**, as the spec had it. Rejected as an
  oracle (§10).

## Revisit when

- Phase 3 opens its first Bond-day and BR-6 stops being theoretical.
- Phase 4 adds notifications and has to decide whether a pending proposal is worth a push.
- Phase 5 writes the deletion job, and needs to decide what a cooling-off that expired while
  the job was down should do.
- Somebody asks for a one-sided deletion of their own data on an archived bond, which §10
  deliberately does not offer.
