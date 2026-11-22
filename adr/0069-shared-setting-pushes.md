# ADR-0069 — Shared-setting pushes, how a proposal ended, and a budget line for every non-reveal push

**Status:** Proposed · **Date:** 2026-11-16 · **Deciders:** the owner asked for the push, its
toggle, its place in the budget, its silence on leave and block, and a record of who cancelled
(issue #205, 2026-11-16); the lead ruled the rest for him, **his to overrule**, each with its cost
(spec §3 B, every one marked *lead ruling 2026-11-16, owner may revise*). · **Amends:** ADR-0030
(a cancel's cause and member), ADR-0044 decision 9 (`data.change`, a tag), ADR-0045 decision 1 (a
seventh toggle), ADR-0046 decisions 1, 4, 7 and 9, ADR-0068 decisions 7 and 19, FR-081, FR-082

## Context

When one member changes something both share, or answers or withdraws a proposal, the other learns
it only by opening the app (moyi-app row 46; the app's client half already notes each change on
Today when opened). Only the opening of a proposal pushes (`PROPOSAL_OPENED`, ADR-0046 decision 7).

Reading the code for this found four things the issue did not say:

- **A proposal's ending is not recorded well enough to say how it ended.** One `cancelled_at`
  covers a withdrawal, a decline, a lapse closed lazily, semantic search turned off, and the bond
  ending by leave, block or account deletion. And `GET /bonds` shows only live proposals, so even
  a recorded canceller would have no field to reach the app through.
- **ADR-0046 decision 4's gate ("takes writes") would silence the deletion agreement**, since the
  agreement is what moves the bond to `PENDING_DELETION`.
- **A push held by quiet hours outlives the facts that made it.** A held `PROPOSAL_OPENED` is sent
  in the morning after the proposal was answered; a held push of any kind is sent after its Bond
  ended in the night, which makes a leave conspicuous (T-09).
- **ADR-0068 decision 19's defect is not the nudges' alone.** Once a Bond's reserved slot is spent
  by a reveal-class push of the day before, any non-reveal push can take the last slot that day's
  reveal needs; decision 19 stops only soft nudges. The new category would add a fifth hole to four.

The spec (`docs/superpowers/specs/2026-11-16-shared-setting-push-design.md`) holds the detail, the
tables and the worked cases; this record is what was decided.

## Decision

✱ marks a departure from the issue's wording or the corpus.

1. **`SHARED_SETTING_CHANGED`**, a new non-reveal category (owner), governed by a seventh toggle,
   **`sharedSettings`, on by default** (owner), stored in `notification_prefs` like the others, so
   no member row needs a migration.
2. **What pushes, to whom** (lead). A shared setting is what one member may change alone through
   `PATCH /bonds/{id}` (name, type, reveal time, strict mode, semantic search off) ✱ and calling
   off an agreed deletion. A `PATCH` that changes no value pushes nothing. Proposals: **accepted**
   and **declined** go to the proposer, **withdrawn** to the other member; **made** pushes nothing
   new (`PROPOSAL_OPENED` already does, under its own toggle); **lapsed** pushes nothing (nobody
   acted, and a push for an unanswered proposal is the nag ADR-0030 refused); **closed by the bond
   ending** pushes nothing (owner, T-09). One rule: every member who has not left, never the
   actor. One push per request: an acceptance that moves a setting is one `PROPOSAL_ACCEPTED`.
3. **An ending calls off the proposal's opening push still owed** (lead): for every member, reason
   `proposal_closed`. A withdrawal that called off the other member's opening push asks for nothing:
   they were never told of it.
4. ✱ **This category's gate is "the Bond has not ended"** (lead): `PENDING_MEMBER`, `ACTIVE` or
   `PENDING_DELETION` (`BondStatus.hasEnded` false, beside `isOpen`). Every other category keeps
   ADR-0046 decision 4. A countdown holds only mutual knowledge; leave and block publish nothing in
   it either.
5. **Ending a Bond calls off every push of that Bond still owed** (lead): `EndBond.end`, the one path
   of leave, block and account deletion, calls `Notifications.cancelBond(bondId)` in its own
   transaction; every `REQUESTED` or `HELD` push of the Bond, every member and category, is
   `CANCELLED` (`bond_ended`). Nothing arrives after a leave; leave and block stay identical in the
   ledger.
6. **How a proposal ended: two columns, the reading derived** (owner asked; lead shaped). V70 adds
   `bond_proposals.cancelled_by_member_id` and `cancel_cause` (`MEMBER`, `LAPSED`, `BOND_ENDED`).
   Withdrawn and declined are **not stored**: a `MEMBER` cancel by the proposer is withdrawn, by
   the other member declined (`Proposal.outcome`, the one place). **A proposal closed by the bond
   ending names no member**: naming the leaver or blocker would make "declined" a way to learn who
   left (doc 26 §2.1). Rows cancelled before V70 are backfilled where the cause is certain
   (`cancelled_at >= expires_at` is a lapse; `cancelled_at = archived_at` an ending) and are
   otherwise `UNRECORDED`.
7. **The category coalesces** (lead): asked for with `not_before` two minutes on; when recorded, it
   cancels every other owed push of the category to the same member in the same Bond
   (`superseded`), takes the **earliest** `not_before` of what it cancelled (no starvation), and
   becomes `SEVERAL` if any of them carried a different change. Quiet hours hold it, never drop it;
   at most one is released. A re-ask supersedes nothing (ADR-0068 decision 14's rule). This is a
   second supersede beside ADR-0068 decision 7's: by group, where the nudges' is by key, because
   each change is its own fact and needs its own key.
8. ✱ **`closedProposals` on the bond** (lead): the latest ended proposal of each kind, at most
   three, `{id, kind, proposedByMemberId, proposedAt, outcome, closedByMemberId, closedAt}`,
   `outcome` one of `ACCEPTED`, `DECLINED`, `WITHDRAWN`, `LAPSED`, `BOND_ENDED`, `UNRECORDED`;
   the two pending responses that lacked one gain `id`. Additive; **not a breaking change** under
   the repository's oasdiff check. The entity tag digests it.
9. **Words and payload** (lead). One title key, `push_shared_setting_changed_title`; body keys
   `push_shared_setting_changed_body` (`SETTING`, `SEVERAL`), `…_accepted_body`, `…_declined_body`,
   `…_withdrawn_body`. ✱ Per-change keys now, where ADR-0068 deferred per-nudge keys: no installed
   app has any string for a new category, so the release that adds one adds four. No name, no Bond
   name, no setting, no value, no who. `data: {bondId, kind, change}`; `change` only on this
   category. `android.notification.tag` = `SHARED_SETTING_CHANGED:{bondId}` (a later change replaces
   the earlier in the shade, as `apns-collapse-id` already does); channel `shared_setting_changed`.
10. ✱ **ADR-0068 decision 19 applies to every non-reveal push** (the final review's option b;
    lead). `PushBudget.decide`'s line becomes `!revealClass && reservedSpentToday &&
    sentInBondToday + 1 >= 4` → `DROPPED_LEAVING_ROOM`, in the same place in the order. Once a
    Bond's reserved slot is spent today, no non-reveal push takes its last slot. Across Bonds the
    category leaves nothing free (as milestones do); beside the nudges it is first come, with no
    precedence (the app's Today note carries what a dropped push would).
11. **Events, keys, delivery** (lead). `BondSettingsChanged {bondId, memberId}` and `ProposalClosed
    {bondId, proposalId, memberId}`, ids only, published in the actor's transaction through the
    outbox, consumed by `bond.notify` (`NotifyBondMembers`), which reads the proposal row for its
    outcome. Nothing is published by `EndBond`, a lapse, or a lone member's self-confirmed
    deletion. Key `shared:{eventId}:{userId}`; stale after 24 hours.
12. ✱ **Two migrations, V70 (`bond`) and V71 (`notification`: the category's CHECK and
    `shared_change`)**, not one. V69 is PR #210's. Both additive.
13. **Metrics** (lead): `bond.notify.shared_changes{change, outcome}`; `notification.push.settled`
    gains `settling` (a hold by the push's own `not_before`, so `held` stays quiet-hours volume);
    `notification.push.cancelled` gains `proposal_closed` and `bond_ended`. Bounded tags, no ids;
    ids in new log lines only at DEBUG.
14. **Turning semantic search off pushes like any setting** (lead): a consent withdrawal, but the
    partner sees it on opening anyway, and the push says neither what nor who.

## Consequences

**Positive:** the other member hears of an answer, a withdrawal or a shared change within about
three minutes, once per burst; the app can say declined or withdrawn; a leave or block still makes
no event, now also for a push held before it; an answered proposal's held opening push no longer
arrives; the day's reveal is no longer lost to any non-reveal push after a reveal-class push of the
day before, of any category.

**Negative:** every shared-setting push is two to three minutes late. **Nudge 1 loses its
per-Bond hardness** (ADR-0068 decision 19 kept it): after a midday solo-unlock with three sent, the
reminder is dropped and the reveal kept; and after the day's own reveal took the reserved slot, a
fourth push of any kind that day is dropped. On a three-nudge day a setting push is dropped. A
reveal held overnight is not pushed if the partner leaves before morning. A withdrawal can, within
the outbox's ordering window, reach a member never pushed the proposal. Two concurrent deliveries
can leave two owed setting pushes for one member. `dropped_leaving_room` now counts any category.

**Neutral:** the cross-Bond case of ADR-0068 decision 5 stays open for this category, as for
milestones. Proposal cancellers before V70 read `UNRECORDED`.

## Rulings while it was built

Each is the lead's, the owner's to revise; the cost of each is named.

- **C1: coalescing is a `PushCategory` property.** The domain imports nothing (`ArchitectureTest`), so
  `Coalescing` could not read `api`. `PushCategory.coalesces` says which categories settle; `SETTLE`
  stays in `notification.domain.Coalescing`; `merged(own, cancelled, several)` is generic over the
  domain's own enum. Cost: none.
- **Locks in id order, and a bond index (Task 2 review).** `cancelOwedOfBond`, `cancelOwedInGroup` and
  `cancelOwed` lock the rows they cancel in id order: two of them, or a leave beside a coalescing
  request, would otherwise deadlock, and a leave failing on a deadlock is visible to the member.
  V71 (unmerged, edited in place) adds the partial index `notifications_bond_owed_idx
  (bond_id) WHERE status IN ('REQUESTED','HELD')`, so `cancelBond` does not scan the ledger; an
  `EXPLAIN` test pins it. Cost: one more index on a small table.
- **A lapsed proposal's `closedAt` is its `expiresAt` (Task 6 review).** One shared function serves
  `BondResponse` and `BondEntityTag`, so the value is stable across `closeLapsed`, which stamps a
  later `cancelled_at`; a value that changed with no event would be a client-visible bug.
  `UNRECORDED` reads `closedByMemberId` null. `GET /bonds` carries `closedProposals` too. Cost: a
  lapsed proposal's close time is the moment it expired, not the moment the row was swept.
- **The consumer takes the bond row lock (Task 8).** `NotifyBondMembers` locks the Bond (`FOR UPDATE`,
  `EndBond`'s lock) before it reads `hasEnded` and holds it until the delivery commits, so a leave
  cannot commit between the read and the request and leave a push its `cancelBond` could not see.
  Cost: two deliveries for one Bond serialise.
- **A withdrawal can be told about a proposal never pushed.** The recorded cost of decision 3, held in
  `BondPushesTest`, where a proposal called off before its opening push was asked is told to the
  other member as a withdrawal.

## Alternatives considered

- **Treat only `SHARED_SETTING_CHANGED` specially in the budget.** Leaves the same hole open for
  milestones, partner-joined, proposals, on-this-day and nudge 1. Rejected (10).
- **Store `DECLINED`/`WITHDRAWN` as the cause.** A second statement of "the proposer or not", which
  a CHECK would have to restate. Rejected (6).
- **Record who left as the canceller of the proposals the leave closed.** An oracle for leave versus
  block. Rejected (6).
- **Coalesce by a time-bucket dedupe key.** Splits at arbitrary boundaries and lets quiet hours pile
  up a push per bucket. Rejected (7).
- **Keep ADR-0046 decision 4's gate.** Silences the deletion agreement. Rejected (4).
- **A `SHARED_SETTING_CHANGED` push when a proposal is made.** Two pushes for one tap. Rejected (2).
- **Push the setting's name or a proposal kind in `data`.** Nothing the app needs on a tap that the
  bond does not already say. Rejected (9).

## Owed

- **The words and the channel in the app**, coordinated with a release; the owner's to approve.
- **The app**: read `closedProposals`, offer `sharedSettings`, route the new `kind`.
- **Corpus:** doc 03 FR-081 and FR-082; ADR-0046's table, which still lists `ProposalOpened` from
  two services when `EnableSemanticSearch` publishes it too.
- **The rulings above**, each the owner's to revise.

## Revisit when

A member asks why a change reached them late, or why a reminder did not come after a midday
unlock; the owner wants a proposal cooldown (ADR-0046 Open); the app wants the setting named.
