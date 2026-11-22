# Shared-setting pushes: tell the other member when something you share changes

**Status:** Draft, 2026-11-16. Issue #205. The owner's asks in the issue are fixed (§3, A); every
other ruling (§3, B) is the lead's, made for the owner and marked "*lead ruling 2026-11-16, owner
may revise*"; each says what it costs if it is wrong. ✱ marks a departure from the wording of the
issue, the corpus or the brief.

**Requirements served:** FR-027, FR-028, FR-204 (the three two-party proposals), FR-081 (a toggle
per category), FR-082 as amended by ADR-0068 and here (the budget), FR-076, doc 09 T-09 and doc 26
§2.1 (nothing in a push says anything the member could not already learn; leaving and blocking
create no event and are indistinguishable from the other side), FR-084 and T-14 (no content in a
push).

**Corpus this builds on:** ADR-0030 (two-party consent, the proposal table, lapsing as a predicate),
ADR-0044 (the ledger, the payload, decision 9), ADR-0045 (toggles, quiet hours held in the ledger,
the budget by reservation), ADR-0046 (which events push: decision 4's gate, decision 7's
`PROPOSAL_OPENED`, decision 9's copy table), ADR-0054 (semantic search: off is one member's, on is
a proposal), ADR-0068 (the nudges, `PushBudget.decide`'s order and decision 19, the supersede).
The client's request is moyi-app `docs/backend-contract-gaps.md` row 46 (moyi-app#113, merged),
whose client half already notes each shared change on Today when the app is opened.

## 1. Why this slice

When one member changes something both share, the other learns it only by opening the app. The
owner wants them told: "I propose a new time zone; my partner accepts or declines; I should be
told." Today the only proposal push is `PROPOSAL_OPENED`, to the member who did not propose
(ADR-0046 decision 7). Nothing is published on an answer, a withdrawal, or a `PATCH /bonds/{id}`.

And a proposal's ending is not recorded well enough to say *how* it ended. Read for this spec:

- `bond_proposals` (V10) has `confirmed_by_member_id`, `confirmed_at`, `cancelled_at`, and no
  record of who cancelled. One `cancelled_at` covers five causes: the proposer withdrawing
  (`DELETE …/timezone`, `…/deletion-request`, `…/semantic-search`), the other member declining (the
  same three routes: "either may cancel"), turning semantic search off (`UpdateBond` →
  `cancelLive`), a lapsed row closed when a new proposal needs its slot (`closeLapsed`), and the
  bond ending (`EndBond.end` → `cancelLiveOf`, for leave, block and account deletion alike).
- `GET /bonds` shows only **live** proposals (`BondViews` → `ProposalStore.findAllLiveOf`). A
  proposal that ends simply disappears from the response, so **recording who cancelled is not
  enough on its own**: the app has no field to read it from (§3 B14).
- `NotifyBondMembers` (`bond.notify`) consumes `MemberJoined` and `ProposalOpened`, gates on
  `PushAudience.takesWrites` (`BondStatus.isOpen`: `PENDING_MEMBER` or `ACTIVE`), and asks one push
  per other member under a key per fact.
- `PushBudget.decide` (ADR-0068): cross-Bond cap, cross-Bond leaving room, per-Bond cap of four,
  then decision 19's line, which only a soft nudge (`leaveFreeAcrossBonds > 0`) obeys.
- `PushRequests.request` supersedes only for a `Nudge` (`Nudge.supersedes`, by key).

## 2. Goals, non-goals, anti-goals

**Goals**
- The other member is pushed, once per burst of changes, when a shared setting changes, when their
  proposal is accepted or declined, or when the proposal waiting on them is withdrawn.
- Their own toggle, `sharedSettings`, on by default.
- Each proposal's ending says who ended it and how: `ACCEPTED`, `DECLINED`, `WITHDRAWN`, `LAPSED`,
  `BOND_ENDED`; the bond response carries the latest ending of each kind.
- A non-reveal push, of any category, never takes the slot a Bond's reveal needs.

**Non-goals:** pushing what changed or its new value; a push to the actor's other devices; a push
when a proposal is made (`PROPOSAL_OPENED` already is one); per-member settings (reminder time,
quiet hours, toggles: those are not shared); a cooldown on proposals (ADR-0046 Open).

**Anti-goals (a failure even if it works):** any push, or any change to any push, caused by a leave
or a block; a proposal closed by a leave or a block recorded as *declined* or *withdrawn* by
anyone (that would tell the other side who ended it, doc 26 §2.1); a push naming who changed what,
the setting, its value or the Bond's name; five pushes for five taps; a pile of held changes
released together in the morning; a push about a Bond that has ended, arriving after it ended.

## 3. The rulings

Each: the decision; why; what it costs if wrong.

### A. The owner's (issue #205, fixed)

1. **A new push category, `SHARED_SETTING_CHANGED`, to the other member** when a shared Bond
   setting changes, a proposal is answered (accepted or declined), or a proposal is withdrawn.
2. **Its own toggle in the notification preferences, on by default.**
3. **Counted in FR-082's budget like any other non-reveal push**: the per-Bond remainder and the
   cross-Bond cap.
4. **Never for leaving or blocking** (T-09).
5. **Who cancelled a proposal is recorded**, so that the app can tell declined from withdrawn.
6. **T-09 copy**: no entry text, no names, no Bond name.

### B. The lead's (2026-11-16; the owner may revise each)

7. **What a shared setting is** (*lead ruling 2026-11-16, owner may revise*). The bond's own fields
   one member may change alone, through `PATCH /bonds/{id}`: `name`, `type`, `revealTimeLocal`,
   `strictMode`, and `semanticSearchEnabled` turned off. ✱ **Also calling off a deletion already
   agreed** (`DELETE …/deletion-request` during `PENDING_DELETION`): the issue does not name it, but
   the app's row 46 lists that route, and "the box is no longer closing" is the most consequential
   shared change there is. It is pushed as a setting change.
   **Not shared, so never pushed:** the member's own reminder time, zone, quiet hours and toggles;
   the anchor zone changed directly while the bond has one member (`ChangeTimezone.propose` on
   `PENDING_MEMBER`: there is no one to tell); a scheduled zone handoff taking effect at the end of
   a day (nobody acted then); an invite made or revoked.
   **A `PATCH` that changes no value pushes nothing** (the bond before and after compared): it still
   moves the version, as today, but tells nobody. *Cost:* none; a client that re-sends the same name
   on every save does not spam the partner.
8. **A proposal being made does not push `SHARED_SETTING_CHANGED`** (*lead ruling 2026-11-16, owner
   may revise*). The issue lists answered and withdrawn only, and rightly: making one already pushes
   `PROPOSAL_OPENED` to the same person, once, under its own toggle (ADR-0046 decision 7). A second
   push for the same fact would be two pushes from one tap, and would make the `proposal` toggle a
   switch that turns off only one of them. *Cost:* none.
9. **Every proposal transition, and who is told** (*lead ruling 2026-11-16, owner may revise*).
   The rule is one: **every member who has not left, except the member who acted**. With two
   members that is:

   | Transition | Route (actor) | `change` | Told |
   |---|---|---|---|
   | made | `PATCH …/timezone`, first `POST …/deletion-request`, first `POST …/semantic-search` (proposer) | none here; `PROPOSAL_OPENED` (ruling 8) | the other member |
   | accepted | `POST …/timezone/confirm`, second member's `POST …/deletion-request` or `POST …/semantic-search` (the other member) | `PROPOSAL_ACCEPTED` | the proposer |
   | declined | `DELETE …/timezone`, `…/deletion-request`, `…/semantic-search` by the member who did not propose | `PROPOSAL_DECLINED` | the proposer |
   | withdrawn | the same three `DELETE`s by the proposer | `PROPOSAL_WITHDRAWN` | the other member |
   | turned off while asked on | `PATCH /bonds/{id}` `semanticSearchEnabled: false` with a live `SEMANTIC_SEARCH_ON` request (defensive: the request cannot be live while search is on) | one `SETTING` push for the `PATCH`; the request is recorded declined or withdrawn by the actor | the other member |
   | lapsed (seven days) | none: a predicate, no write (ADR-0030) | nobody told | nobody |
   | closed by the bond ending | `POST …/leave`, `POST …/block`, account deletion (`EndBond.end`) | nobody told (A4) | nobody |
   | lone member's own deletion | first `POST …/deletion-request` on `PENDING_MEMBER` (confirms itself) | nobody else exists | nobody |

   **Lapsing pushes nothing**: no one acted, both were shown the seven days, and a push when the
   other member did not answer is the "partner hasn't" notification T-09 rules out; ADR-0030 chose
   "an unanswered one lapses rather than nagging". **One push per request**: an acceptance that also
   moves a setting (a zone change, search on) is one `PROPOSAL_ACCEPTED`, not that and a `SETTING`.
   *Cost:* none known.
10. **A proposal's ending calls off its opening push still owed** (*lead ruling 2026-11-16, owner may
    revise*). Today a `PROPOSAL_OPENED` held by quiet hours is still sent in the morning after the
    proposal was answered or withdrawn in the night: `SendPush` does not ask whether it is still
    live, and ADR-0046 decision 7 checks liveness only when the push is asked for. So when a proposal
    is accepted, declined or withdrawn, the consumer calls off `proposal:{proposalId}:{userId}` for
    every member, `REQUESTED` or `HELD` (reason `proposal_closed`). **A withdrawal that called off
    the other member's opening push asks for nothing more**: they were never told of the proposal,
    so telling them it was withdrawn is noise. *Cost:* a withdrawal handled before its own opening
    push was ever asked for (the outbox does not promise order; a two-second window) is pushed as
    `PROPOSAL_WITHDRAWN` about a proposal the member was not pushed about. Recorded, not fixed.
11. **The gate is "the Bond has not ended", not "it takes writes"** ✱ (*lead ruling 2026-11-16,
    owner may revise*). ADR-0046 decision 4 tells nobody anything about a Bond that takes no writes,
    and `PENDING_DELETION` takes none. Under that rule the deletion agreement, the one answer that
    matters most, would reach nobody: the agreement is what puts the bond into `PENDING_DELETION`.
    For this category alone the gate is `BondStatus.hasEnded` false: `PENDING_MEMBER`, `ACTIVE` or
    `PENDING_DELETION`. Everything that can happen in a countdown is mutual knowledge (the agreement
    that started it, turning search off, calling it off); a leave or block during the countdown still
    publishes nothing, and the member who left is no longer in the audience. Every other category
    keeps decision 4 unchanged. *Cost:* one rule with two readings, written once each on
    `BondStatus` (`isOpen`, `hasEnded`).
12. **Ending a Bond calls off every push of that Bond still owed** (*lead ruling 2026-11-16, owner
    may revise*). A change made at 23:00 to a partner in quiet hours is `HELD` until 07:00. If the
    actor then leaves at 23:30, the held push would arrive in the morning and send the partner to an
    archived Bond: a conspicuous event made by a leave, which T-09 forbids, at the moment doc 26
    calls the riskiest. The same is true today of a held reveal, milestone or reminder. So
    `EndBond.end`, in the leave's or block's own transaction, calls
    `Notifications.cancelBond(bondId)`: every `REQUESTED` or `HELD` push of the Bond, every member,
    every category, becomes `CANCELLED` (reason `bond_ended`). Leave and block take the one path,
    so they remain indistinguishable in the ledger as on the screen. *Cost:* a reveal held overnight
    for a day both wrote is not pushed if the partner leaves before morning; the member sees the day
    on opening. That is ADR-0046's existing "a Bond that ends between the fact and the consumer
    tells nobody", extended to a push held past the end.
13. **Recording how a proposal ended: V70** (*lead ruling 2026-11-16, owner may revise*), two
    columns on `bond_proposals` (§6.1): `cancelled_by_member_id` and `cancel_cause` (`MEMBER`,
    `LAPSED`, `BOND_ENDED`). **Declined and withdrawn are not stored; they are derived**:
    `MEMBER` and `cancelled_by_member_id = proposed_by_member_id` is withdrawn, any other member is
    declined. One fact stored once, the reading in one place (`Proposal.outcome`). **A proposal
    closed by the bond ending names no member**, whoever's leave or block ended it: recording the
    leaver or blocker as the canceller would turn "declined" into a way to learn who left, and
    doc 26 §2.1 makes leave and block indistinguishable from the other side. Rows cancelled before
    V70 are backfilled where the cause is certain (§6.1) and otherwise left without one, read as
    `UNRECORDED`. *Cost:* two nullable columns and three CHECKs.
14. **The API: `closedProposals` on the bond** ✱ (*lead ruling 2026-11-16, owner may revise*). The
    issue asks only to record who cancelled, but the app cannot read a closed proposal at all
    (§1). `BondResponse` gains `closedProposals: [ClosedProposalResponse]`, **the latest ended
    proposal of each kind**, at most three, with no time bound:
    `{id, kind, proposedByMemberId, proposedAt, outcome, closedByMemberId, closedAt}`. `outcome` is
    one of `ACCEPTED`, `DECLINED`, `WITHDRAWN`, `LAPSED`, `BOND_ENDED`, `UNRECORDED`;
    `closedByMemberId` is set for the first three only; `closedAt` is when it was confirmed,
    cancelled, or (lapsed and never closed) when it expired. `PendingDeletionRequestResponse` and
    `PendingSemanticSearchRequestResponse` gain `id` (the zone change already has it), so the app
    matches a pending request to its ending by id. **Not a breaking change**: added response
    properties and new schemas only, which oasdiff's `breaking` check (`fail-on: ERR`,
    `flatten-allof`) does not flag; no `breaking-api-change` label. The entity tag digests the new
    field, since a lapse changes it with no write. *Cost:* one batched query per bond read (the
    live one already runs), and three enums the app's generated client must accept.
15. **Copy: one title key, four body keys** (*lead ruling 2026-11-16, owner may revise*). Keys only,
    rendered by the operating system (ADR-0044 decision 9, ADR-0046 decision 9). The title is
    `push_shared_setting_changed_title` for every change; the body by `change`:

    | `change` | Body key | Title (suggested) | Body (suggested) | Never |
    |---|---|---|---|---|
    | `SETTING`, `SEVERAL` | `push_shared_setting_changed_body` | Something changed | Something you share in your Moyi has changed. | which setting, its value, who |
    | `PROPOSAL_ACCEPTED` | `push_shared_setting_changed_accepted_body` | (as above) | The change you asked for was agreed. | what was asked, who agreed |
    | `PROPOSAL_DECLINED` | `push_shared_setting_changed_declined_body` | (as above) | The change you asked for won't go ahead. | "declined by", "refused", a name |
    | `PROPOSAL_WITHDRAWN` | `push_shared_setting_changed_withdrawn_body` | (as above) | A change you were asked about was called off. | who, what it was |

    ✱ **Per-change keys now, where nudges waited** (ADR-0068 Owed). Nudges reused existing keys
    already in every installed app; this category is new, so no installed app has any string for it,
    and the release that adds one string can add four. The words are the owner's. *Cost:* the app
    must ship five strings and a channel before the first push shows text (§10).
16. **`data` and the Android shade** (*lead ruling 2026-11-16, owner may revise*). `data:
    {bondId, kind, change}`, `kind` = `SHARED_SETTING_CHANGED`, `change` one of the five values of
    ruling 15. No proposal kind, no setting name, no member: the app's tap opens the Bond, whose
    `closedProposals` and settings say the rest. **`android.notification.tag` =
    `SHARED_SETTING_CHANGED:{bondId}`**, so a later change replaces the earlier one in the shade, as
    `apns-collapse-id` (`SHARED_SETTING_CHANGED:{bondId}`, unchanged rule) already does on iOS; no
    date, because the push is about no day. The channel is `shared_setting_changed` (the existing
    rule, `channelId`). *Cost:* one more category with a tag (ADR-0068 decision 8 gave one to
    `REMINDER` only).
17. **Coalescing: two minutes to settle, the newest supersedes** (*lead ruling 2026-11-16, owner may
    revise*). Five taps in a minute must not be five pushes, and a dedupe key cannot do it: each
    change is its own fact, so each needs its own key for idempotence (ruling 21). So the
    category **coalesces**, a rule of `notification`'s, keyed on the category:
    - asked for with `not_before` = now + `SETTLE` (two minutes), so the consumer holds it;
    - when the row is recorded, every other `SHARED_SETTING_CHANGED` push **to the same member in
      the same Bond** still owed (`REQUESTED` or `HELD`) is cancelled (reason `superseded`);
    - the new row takes the **earliest** `not_before` of what it cancelled, so a steady stream of
      changes is pushed every two minutes at most rather than never (no starvation), and quiet hours
      still hold it when it comes due;
    - if anything it cancelled carried a different `change`, or `SEVERAL`, the new row's `change`
      becomes `SEVERAL` (the generic words);
    - a re-ask (the key already recorded) supersedes nothing (ADR-0068 decision 14's rule).
    Five taps in a minute: one push, about two minutes after the first. The ADR-0068 supersede by
    key stays for nudges; this one is by group. *Cost:* every such push is two to three minutes
    late (the held release runs each minute); the consumer's `held` count stops being pure
    quiet-hours volume, so a settling hold is counted `settling` (ruling 22).
18. **Quiet hours: held, never dropped; superseded while held** (*lead ruling 2026-11-16, owner may
    revise*). As every non-reveal category (ADR-0045). With ruling 17 at most one is released when
    the hours end, carrying `SEVERAL` if the night held different kinds of change. `staleAfter` is
    the request + 24 hours, `NotifyBondMembers.STALE_AFTER`, as ADR-0046's other Bond pushes: a
    member whose quiet hours run longer than a day of changes is told of none. *Cost:* none new.
19. **The budget: ADR-0068 decision 19 generalised to every non-reveal push** ✱ (the final review's
    option b; *lead ruling 2026-11-16, owner may revise*). The defect, with this category:

    | Member B, one Bond, reminder 08:00; B did not write D−1, the partner did | B's sent today | reserved |
    |---|---|---|
    | 08:00 nudge 1 (hard), 11:00 nudge 2 | 2 | no |
    | 12:00 D−1 closes after its grace: `SOLO_UNLOCK` of D−1 takes the reserved slot | 3 | **spent** |
    | 13:00 the partner renames the Bond: `SHARED_SETTING_CHANGED`, remainder 2 < 3, **sent** today | 4 | spent |
    | 19:00 both write D: `REVEAL` of D finds four sent → **`DROPPED_BUDGET`, never retried** | 4 | |

    Nudge 3 at 14:00 is already held back by decision 19; the setting push is not, because the
    line asks `leaveFreeAcrossBonds > 0`. **The same defect already exists for every other
    non-reveal category** (milestone, partner joined, proposal opened, on-this-day, nudge 1), and has
    since ADR-0045: whatever spends the remainder after a reserved slot went to the day before takes
    the slot the day's reveal needs. Special-casing the new category would leave four known holes
    open. So the marker of decision 19 changes from "a soft nudge" to "not reveal-class":
    `!revealClass && reservedSpentToday && sentInBondToday + 1 >= 4` → `DROPPED_LEAVING_ROOM`. No new
    parameter. In words: **once a Bond's reserved slot is spent today, a non-reveal push leaves the
    last slot for a reveal.** Reveal-class pushes are unchanged; the line's place in `decide`'s
    order is unchanged (after the per-Bond cap). *Cost:* (a) **nudge 1 is no longer hard on this
    line** (ADR-0068 decision 19 kept it hard): on a day the reserved slot went at midday to the day
    before and three were sent, the reminder is dropped and the reveal kept; (b) after the day's own
    reveal took the reserved slot, a fourth push of any other kind that day is dropped, though in
    one zone no second reveal can come (with an anchor zone far from the reminder zone, one can);
    (c) `dropped_leaving_room` now counts any category, not only nudges.
20. **Across Bonds: like every other non-nudge push** (*lead ruling 2026-11-16, owner may revise*).
    Leave-free 0: it may take a cross-Bond slot as a milestone may. Computing "reveals owed" needs
    the user's other Bonds and their days, which the reminder roster has and `NotifyBondMembers`
    does not. *Cost:* the cross-Bond case of ADR-0068 decision 5 stays open for this category, as it
    is for milestones and partner-joined.
21. **Beside the nudges: first come, no precedence** (*lead ruling 2026-11-16, owner may revise*).
    On a three-nudge day the remainder is spent and a setting push is `DROPPED_BUDGET`; ADR-0068's
    hook (a per-Bond leave-free on nudge 3) is not taken. A dropped setting push loses no
    information: the app already notes each shared change on Today when opened (row 46, client half
    built), and the member who has not written today will open the app to write. *Cost:* on such a
    day the partner's change is learnt on opening, not from a push.
22. **Metrics and logs** (*lead ruling 2026-11-16, owner may revise*), §9.
23. **Turning semantic search off pushes like any setting** (*lead ruling 2026-11-16, owner may
    revise*). It is a withdrawal of consent (ADR-0054), and a push the other member receives
    when one turns it off could read as pressure. But the partner sees it in settings on next open
    anyway (T-09's bar: nothing the member could not already learn), the words say only that
    something shared changed (ruling 15), and `data.change` is `SETTING`, never which. *Cost:* the
    partner learns sooner that something changed, not what.
24. **Idempotence and delivery: the outbox, as every push** (*lead ruling 2026-11-16, owner may
    revise*), §4 and §7. Key `shared:{eventId}:{userId}` (80 characters): the outbox event's own id
    names the fact, and a redelivery carries the same id.
25. **No migration avoided** ✱: two are needed, not one. `bond`'s (V70, ruling 13) and
    `notification`'s (V71: the category's CHECK and the `shared_change` column, §6.2). Versions are
    one sequence; V69 is claimed by PR #210. If either lands after another branch takes V70 or V71,
    the later to merge renumbers on its branch. *Cost:* none beyond the numbering.

## 4. The flow

**Publish** (`bond`, in the actor's transaction, under the bond's row lock, beside the write):

| Service | Where | Event |
|---|---|---|
| `UpdateBond.patch` | after `bonds.update`, only if `updated != bond` | `BondSettingsChanged {bondId, memberId}` |
| `RequestDeletion.cancel` | the `PENDING_DELETION` branch, after `cancelDeletion` | `BondSettingsChanged {bondId, memberId}` |
| `RequestDeletion.cancel` | the pending-request branch, after `proposals.cancel(id, by, now)` | `ProposalClosed {bondId, proposalId, memberId}` |
| `RequestDeletion.agree` | after a successful `confirm` | `ProposalClosed` |
| `ChangeTimezone.confirm` | after a successful `confirm` | `ProposalClosed` |
| `ChangeTimezone.cancel` | after a successful `cancel` | `ProposalClosed` |
| `EnableSemanticSearch.agree` | after a successful `confirm` | `ProposalClosed` |
| `EnableSemanticSearch.cancel` | after a successful `cancel` | `ProposalClosed` |

`memberId` is the actor's member id. Nothing is published by `EndBond` (leave, block, account
deletion), `closeLapsed`, the lone member's self-confirming deletion, or `UpdateBond`'s
`cancelLive` (the `PATCH`'s own event covers it). Each event carries ids only (ADR-0035).

**Consume** (`bond.notify`, `NotifyBondMembers`, the delivery's transaction, `MANDATORY`):

The consumer takes the bond row lock (`FOR UPDATE`) before it reads `hasEnded` and holds it through the
request (§7; built in Task 8).

```
BondSettingsChanged: change = SETTING
ProposalClosed:      p = proposals.findInBond(bondId, proposalId)          // the row is the truth
                     calls off proposal:{proposalId}:{userId} for every audience member (ruling 10)
                     change = when (p.outcome) ACCEPTED → PROPOSAL_ACCEPTED
                                               DECLINED → PROPOSAL_DECLINED
                                               WITHDRAWN → PROPOSAL_WITHDRAWN
                                               else → tell nobody (cannot happen: no event for them)
audience = audiences.audienceOf(bondId) ?: return
if audience.hasEnded → count bond_ended, return                             // ruling 11
for member in audience.members, member.memberId != event.memberId:          // never the actor
    if change is PROPOSAL_WITHDRAWN and this member's opening push was just called off → skip
    notifications.request(PushRequest(userId, bondId, SHARED_SETTING_CHANGED,
        dedupeKey = "shared:${event.id}:${userId}", staleAfter = occurredAt + 24 h,
        recipient = member.recipient, bondDate = null, nudge = null, sharedChange = change))
```

**Request** (`notification`, `PushRequests.request`): for a coalescing category, `not_before` = now
+ `SETTLE`; `insertOnce`; if recorded, `cancelOwedInGroup(userId, bondId, category, except = id)`
returns the cancelled rows' `not_before` and `shared_change`; the new row's `not_before` is lowered
to the earliest, and its `shared_change` set to `SEVERAL` if any differed; then the event is
published. One transaction, the caller's.

**Send** (`SendPush`, unchanged order): expired → suppressed (`sharedSettings` off) → held (its own
`not_before`: `settling`; quiet hours: `held`) → `PushBudget.decide` (ruling 19) → send with
`PushMessage.sharedChange`.

**End** (`EndBond.end`): after `cancelLiveOf(bondId, at)` (now recording `BOND_ENDED`),
`notifications.cancelBond(bondId)`.

**Worked day** (two members, Ada and Ben; Ben's quiet hours 22:00–07:00): 21:58 Ada renames the
Bond, then 21:59 turns strict mode on: one push for Ben, `not_before` 22:00, the second supersedes
the first and keeps 22:00. At 22:00 the consumer finds quiet hours: `HELD` until 07:00. 23:10 Ben's
zone-change proposal from the afternoon (sent, not owed) is withdrawn by Ben himself: nothing is
pushed to Ben (he acted), and Ada is pushed `PROPOSAL_WITHDRAWN`. 06:59 nothing else. 07:00 Ben is
released one push with `change = SETTING`. If Ada had left at 23:30 instead, Ben's held push would
have been `CANCELLED` (`bond_ended`) and nothing would arrive in the morning.

## 5. Where the code lives

- **`notification`:**
  - `api/Notifications.kt`: `PushCategory.SHARED_SETTING_CHANGED` (not reveal-class);
    `SharedChange { SETTING, PROPOSAL_ACCEPTED, PROPOSAL_DECLINED, PROPOSAL_WITHDRAWN, SEVERAL }`;
    `PushRequest.sharedChange: SharedChange?`, **required, no default** (as `nudge`), `init`:
    present exactly for `SHARED_SETTING_CHANGED`, and never `SEVERAL` (only coalescing makes that);
    `NotificationPreferences.sharedSettings: Boolean = true`, `allows` maps the category to it;
    `CancelReason { WRITTEN, PROPOSAL_CLOSED }` and `cancel(dedupeKeys, reason)` (the KDoc already
    says a second reason needs the API first); `cancelBond(bondId): Int`.
  - `domain/Coalescing.kt` (new): `SETTLE = 2 min`, `coalesces(category)`, `merged(own, cancelled)`
    (the `SEVERAL` rule). `domain/PushBudget.kt`: ruling 19's marker and KDoc.
  - `service/PushRequests.kt`, `service/SendPush.kt` (`settling`, `PushMessage.sharedChange`),
    `infra/database/NotificationStore.kt` (`shared_change` in `NewPush`/`StoredPush`/`lock`;
    `cancelOwedInGroup`, `cancelOwedOfBond`), `api/PushSender.kt` (`PushMessage.sharedChange`),
    `infra/push/FcmPushSender.kt` (`locKeys(message)`, `data.change`, the tag).
- **`bond`:**
  - `domain/Proposal.kt`: `cancelledBy: MemberId?`, `cancelCause: CancelCause?`,
    `CancelCause { MEMBER, LAPSED, BOND_ENDED }`, `ProposalOutcome`, `outcome` (§3 B13). The V10
    parity `require`s gain the V70 CHECKs.
  - `domain/Bond.kt`: `BondStatus.hasEnded` (`ARCHIVED`, `DELETED`), beside `isOpen`.
  - `infra/database/BondProposalEntity.kt`, `ProposalMappers.kt`, `BondRepositories.kt`
    (`cancel(id, by, now)`, `closeLapsed` and `cancelLiveOf` write their cause, `cancelLive(…, by)`,
    `findLatestEndedOf(bondIds, now)`, `findInBond(bondId, id)`), `ProposalStore.kt`.
  - `service/BondSettingsChanged.kt`, `service/ProposalClosed.kt` (new, as `ProposalOpened.kt`);
    `UpdateBond`, `ChangeTimezone`, `RequestDeletion`, `EnableSemanticSearch` (§4); `EndBond`
    (`cancelBond`); `NotifyBondMembers` (§4); `BondViews`, `BondEntityTag`.
  - `api/PushAudiences.kt`: `PushAudience.hasEnded`; `PushAudiencesAdapter`.
  - `web/BondResponse.kt` (`closedProposals`, `ClosedProposalResponse`, pending `id`s),
    `web/MemberNotifications.kt` and `infra/database/MemberNotifications.kt` (`sharedSettings`).
- **`gratitude`:** `SubmitEntry` passes `CancelReason.WRITTEN`; `NotifyDayMembers`,
  `SendOnThisDayPushes`, `SendDailyReminders` pass `sharedChange = null`.
- **`contracts/openapi.json`:** regenerated (`closedProposals`, pending `id`s, `sharedSettings`).
- **`ArchitectureTest`:** unchanged; no new module edge (`bond` already reaches `notification.api`).

## 6. Data model

### 6.1 V70 (`modules/bond`): how a proposal ended

```sql
-- Issue #205 (ADR-0069). Who called a proposal off, and why it was called off. Declined and
-- withdrawn are not stored: a MEMBER cancel by the proposer is withdrawn, by anyone else declined
-- (Proposal.outcome, the one place that reads it).
ALTER TABLE bond_proposals
    ADD COLUMN cancelled_by_member_id uuid REFERENCES bond_members (id) ON DELETE CASCADE;
ALTER TABLE bond_proposals ADD COLUMN cancel_cause text;

ALTER TABLE bond_proposals ADD CONSTRAINT bond_proposals_cancel_cause_check
    CHECK (cancel_cause IS NULL OR cancel_cause IN ('MEMBER', 'LAPSED', 'BOND_ENDED'));
-- A cause only on a cancelled row. A cancelled row may have none: one cancelled before V70
-- whose cause could not be known (read as UNRECORDED).
ALTER TABLE bond_proposals ADD CONSTRAINT bond_proposals_cancel_cause_cancelled_check
    CHECK (cancel_cause IS NULL OR cancelled_at IS NOT NULL);
-- A member is named exactly when a member called it off. Never for BOND_ENDED: a leave or a
-- block must not read as anybody declining (doc 26 §2.1).
ALTER TABLE bond_proposals ADD CONSTRAINT bond_proposals_cancelled_by_check
    CHECK ((cancelled_by_member_id IS NOT NULL) = (cancel_cause IS NOT DISTINCT FROM 'MEMBER'));

-- What can be known of the rows already cancelled. A lapsed row is closed (closeLapsed) only once
-- expires_at has passed, while a member can cancel only a live row (findLive), so
-- cancelled_at >= expires_at is exactly a lapse (or a bond ended after the lapse: lapsed either way).
UPDATE bond_proposals SET cancel_cause = 'LAPSED'
 WHERE cancelled_at IS NOT NULL AND cancel_cause IS NULL AND cancelled_at >= expires_at;
-- EndBond cancels at the instant it archives (cancelLiveOf(ended.id, ended.archivedAt)).
UPDATE bond_proposals p SET cancel_cause = 'BOND_ENDED'
  FROM bonds b
 WHERE b.id = p.bond_id AND p.cancelled_at IS NOT NULL AND p.cancel_cause IS NULL
   AND b.archived_at IS NOT NULL AND p.cancelled_at = b.archived_at;
-- The rest were called off by a member before anybody recorded which: UNRECORDED.
```

`ON DELETE CASCADE` as V10's two member references. No index: the latest-ended read goes through
`bond_proposals_bond_id_idx`. Additive: the jar before it runs against the schema after it (it
writes neither column, and a NULL cause satisfies every CHECK).

### 6.2 V71 (`modules/notification`): the category and its change

```sql
-- Issue #205 (ADR-0069).
ALTER TABLE notifications DROP CONSTRAINT notifications_category_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_category_check CHECK (
    category IN ('REMINDER', 'PARTNER_JOINED', 'REVEAL', 'SOLO_UNLOCK', 'MILESTONE', 'ON_THIS_DAY',
                 'PROPOSAL_OPENED', 'SHARED_SETTING_CHANGED')
);

-- Which change a SHARED_SETTING_CHANGED push is about, and nothing for any other category. Kept
-- on the row because a held push is sent again from its row alone (V58's reason), and the words
-- the phone shows depend on it. SEVERAL is written only by coalescing (ADR-0069 decision 7).
ALTER TABLE notifications ADD COLUMN shared_change text;
ALTER TABLE notifications ADD CONSTRAINT notifications_shared_change_check CHECK (
    (shared_change IS NULL) = (category <> 'SHARED_SETTING_CHANGED')
    AND (shared_change IS NULL OR shared_change IN
         ('SETTING', 'PROPOSAL_ACCEPTED', 'PROPOSAL_DECLINED', 'PROPOSAL_WITHDRAWN', 'SEVERAL'))
);
```

The group supersede (`user_id`, `bond_id`, `category`, status owed) and `cancelBond` (`bond_id`,
status owed) need one new index: the first is served by `notifications_recipient_idx
(user_id, category, created_at)`, the second by the partial `notifications_bond_owed_idx
(bond_id) WHERE status IN ('REQUESTED', 'HELD')`, in V71. Re-adding
the category CHECK validates the table once, under a short lock; the ledger is small (ninety days,
owner ruling 2026-11-16).

## 7. Concurrency and idempotence

| Race | What decides it | Result |
|---|---|---|
| The same event delivered twice | the unique key `shared:{eventId}:{userId}` | one row; the re-ask supersedes nothing and publishes nothing |
| Two changes' requests for one member at once (two deliveries in flight) | none needed: each `UPDATE` sees only committed rows | both rows may survive: at most two pushes where one was meant; never zero |
| A supersede while the consumer sends the older push | the consumer's `FOR UPDATE` on that row; the `UPDATE` waits, then re-reads its status | sent and left alone, or still owed and cancelled; never both |
| A proposal confirmed and cancelled at once | the bond's row lock, then the compare-and-set (`confirm`, `cancel`), as today | one ending, one event |
| A leave while a setting push is owed | `cancelBond` in the leave's transaction; the consumer's row lock (the consumer takes the bond row lock before reading `hasEnded` and holds it through the request) | the push is sent before the leave commits, or cancelled; a request asked after the leave commits finds `hasEnded` |
| A change's event handled after the bond ended | `hasEnded` read in the consumer | nobody told |
| `ProposalClosed` handled before `ProposalOpened` | none (no order promised) | the opening push is never asked (not live); a withdrawal is pushed about an unpushed proposal (ruling 10's recorded cost) |

## 8. Payload and contract

`data` for this category is `{bondId, kind: "SHARED_SETTING_CHANGED", change}`; `change` is absent
on every other category (`FcmPushSender` guards it by category, a second gate after the producer's,
as `nudge`). Title and body keys by ruling 15, channel `shared_setting_changed`, collapse key and
`apns-collapse-id` `SHARED_SETTING_CHANGED:{bondId}`, `android.notification.tag` the same.

`contracts/openapi.json` gains, all additive:

```
BondResponse.closedProposals: ClosedProposalResponse[]  (required, 0..3, one per kind)
ClosedProposalResponse { id: uuid, kind: TIMEZONE_CHANGE|DELETION|SEMANTIC_SEARCH_ON,
                         proposedByMemberId: uuid, proposedAt: date-time,
                         outcome: ACCEPTED|DECLINED|WITHDRAWN|LAPSED|BOND_ENDED|UNRECORDED,
                         closedByMemberId: uuid?, closedAt: date-time }
PendingDeletionRequestResponse.id: uuid, PendingSemanticSearchRequestResponse.id: uuid
NotificationCategoriesRequest.sharedSettings: boolean?  (absent keeps the current value)
NotificationCategoriesResponse.sharedSettings: boolean  (required)
```

Stored in `bond_members.notification_prefs` (jsonb) under `sharedSettings`; a member row without
the key reads the default, so no migration and no backfill (the mechanism ADR-0045 built for this).

## 9. Observability

- `bond.notify.shared_changes{change="setting|proposal_accepted|proposal_declined|proposal_withdrawn",
  outcome="requested|already_asked|bond_ended|never_told"}` in `NotifyBondMembers`, one per member
  considered (`never_told`: a withdrawal whose opening push was called off).
- `notification.push.settled{category, nudge, outcome}` gains outcome `settling`: a hold by the
  push's own `not_before` (ruling 17), told apart from `held` (quiet hours) so that `held` stays
  quiet-hours volume. `dropped_leaving_room` now counts any non-reveal category (ruling 19).
- `notification.push.cancelled{category, nudge, reason}` gains reasons `proposal_closed` (ruling
  10) and `bond_ended` (ruling 12); `superseded` now also counts this category's coalescing.
- Bounded tags only: no user, member, bond or proposal id, no time, no setting name, in any tag.
  New log lines name ids only at DEBUG; INFO lines carry counts and kinds. The existing INFO lines
  of `UpdateBond`, `ChangeTimezone`, `RequestDeletion`, `EnableSemanticSearch` that print a bond id
  are outside this slice and unchanged (recorded, §12).

## 10. Tests and mutation targets

**Unit**
- `PushBudgetTest`: the §3 B19 table as one test (reserved spent at 12:00, three sent, a
  non-reveal push → `DROPPED_LEAVING_ROOM`, then the reveal → `REMAINDER`); the same for each
  non-reveal category, nudge 1 (`leaveFree = 0`) included (**the existing case "leaveFree 0 →
  REMAINDER" flips by this ruling; it is changed, not bent**); reserved unspent and two sent →
  a non-reveal push still `REMAINDER`; a reveal-class push with reserved spent and three sent →
  `REMAINDER`.
- `CoalescingTest` (pure): `merged(SETTING, [SETTING]) = SETTING`; `merged(SETTING,
  [PROPOSAL_DECLINED]) = SEVERAL`; `merged(PROPOSAL_ACCEPTED, []) = PROPOSAL_ACCEPTED`;
  `merged(SETTING, [SEVERAL]) = SEVERAL`; only `SHARED_SETTING_CHANGED` coalesces.
- `ProposalTest`: `outcome` for each cause and member; a `BOND_ENDED` proposal names nobody;
  the `require`s mirror the V70 CHECKs.
- `PushApiTest`: `sharedChange` required exactly for the category; `SEVERAL` refused from a caller;
  `allows(SHARED_SETTING_CHANGED)` follows `sharedSettings`, default on.

**Integration**
- `SharedSettingColumnsTest` (notification): the CHECKs refuse `shared_change` on a `REVEAL` and its
  absence on the category.
- `CoalescingTest` integration (notification, real ledger): five requests → one owed, four
  `CANCELLED` `superseded`, the owed one's `not_before` the first's; a `HELD` older one superseded
  keeps its earlier `not_before`; mixed changes → `SEVERAL`; a re-ask cancels nothing; another
  member's or another Bond's owed push is untouched; a `SENT` one is untouched.
- `CancelBondTest` (notification): every owed push of the Bond, both members, every category →
  `CANCELLED` `bond_ended`; `SENT` and other Bonds untouched.
- `ProposalPersistenceTest` (bond): each close path writes its cause and member; V70's backfill on
  rows seeded as V10 left them (lapsed, ended, member).
- `SharedSettingPushesTest` (bond, real ledger and consumer): one test per row of ruling 9's table:
  who is asked, under which key, with which `change`, and that the actor is asked nothing; a `PATCH`
  of the same value asks nothing; calling off a countdown pushes `SETTING`; the deletion agreement
  reaches the proposer in `PENDING_DELETION` (**red under the `takesWrites` gate**); a withdrawal
  while the opening push is `HELD` calls it off and asks nothing (**red without ruling 10**); an
  answer calls off the answerer's held opening push; toggle off → `SUPPRESSED`.
- `DiscreetExitTest` (bond, extended; T-09): leave and block each: no `SHARED_SETTING_CHANGED` row
  for anyone, the pending proposal `BOND_ENDED` with no member, the partner's held setting push
  `CANCELLED`, and **the partner's ledger and `GET /bonds` identical after a leave and after a
  block** (two runs compared field by field, ids aside).
- `BondSettingsEndpointTest`, `BondTimezoneEndpointTest`, `BondDeletionEndpointTest`,
  `SemanticSearchSettingTest`: `closedProposals` after each ending; `id` on the two pending
  responses; the `ETag` moves when a proposal lapses with no write.
- `BondNotificationsEndpointTest`: `sharedSettings` true by default, set, kept when absent.
- `FcmPushSenderTest`: the title key; each change's body key; `SEVERAL` → the generic body;
  `data.change` on the category only; the tag on the category and `REMINDER` only.
- `PushMetricsTest`: `settling` versus `held`; the two new cancel reasons; no tag is a UUID.

**Mutation targets (each must turn a test red):** the old marker `leaveFreeAcrossBonds > 0` in
decision 19's line; record the leaver as `cancelled_by` on `EndBond`; store the outcome instead of
deriving it (a withdrawal by the proposer read as declined); gate on `takesWrites`; tell the actor;
publish `ProposalClosed` from `EndBond`; drop the opening-push call-off; take the newest
`not_before` instead of the earliest; keep the newest `change` instead of `SEVERAL`; leave
`cancelBond` out of `EndBond`; send `data.change` on a `REVEAL`; drop the tag.

## 11. Operations

- **State map** (`docs/state-map/data`, regenerated by `scripts/state-map-generate`, checked by
  `scripts/state-map-check`; the stamp is not moved): the proposal region keeps its states (a
  cancel's cause changes no answer any endpoint gives) and its `CANCELLED` label names the three
  causes; `event:partner-cancels` becomes `event:partner-withdraws` (the proposal waiting on you)
  and `event:partner-declines` (yours), each with its push; `event:partner-confirms` gains its push;
  a new `event:partner-changes-a-setting` on the bond region (`ACTIVE` stays; `PENDING_DELETION` →
  `ACTIVE` for calling off the countdown) with its push; the leave and block rows say the proposal
  ends `BOND_ENDED`, naming nobody, and every owed push of the Bond is called off; the `GET /bonds`
  rows name `closedProposals`; the notification-preferences rows name `sharedSettings`.
- **Smoke** (`scripts/smoke.sh`, a new section after the N3 pushes, a fresh pair and bond so that no
  earlier probe's counts move): she renames → one owed row for him, `SETTING`, with
  `not_before` two minutes on (it stays `REQUESTED` until the delivery consumer holds it, `HELD`, about
  a minute and a half on in the smoke run), none for her; she toggles strict mode twice → still one owed, the
  others `CANCELLED`, `not_before` unchanged; a same-value `PATCH` → no new row; he proposes a zone
  change and she declines → `closedProposals` shows `DECLINED` by her and his owed row becomes
  `SEVERAL`; with her quiet hours set over now, he requests deletion and withdraws it → her
  opening push `CANCELLED`, no `PROPOSAL_WITHDRAWN` for her, `closedProposals` `WITHDRAWN` by him;
  he proposes again and she leaves → `BOND_ENDED` with no member, every owed row of the bond
  `CANCELLED`, no new row; one held row's `not_before` moved back by SQL, released by the job
  within 70 s, `SENT` by the logging provider, whose line carries no id. Existing probes that count
  every category in `NB_BOND` (smoke.sh:3155, :3278) are re-read: a `PATCH` in that section now
  asks a push.
- **Runbook:** none new. **Deploy:** V70 and V71 additive; deploy the backend before an app release
  that offers the toggle; the push shows text only once the app ships the five strings and the
  channel (ruling 15).

## 12. Amendments (in the build PR, and the corpus)

- **ADR-0069** (this slice), amending: ADR-0044 decision 9 (`data.change`, the category's tag);
  ADR-0045 decision 1 (a seventh toggle, `sharedSettings`); ADR-0046 decision 1 (the table's new
  rows), decision 4 (ruling 11's exception, ruling 12), decision 7 (ruling 10), decision 9 (the
  copy table); ADR-0068 decision 19 (ruling 19) and decision 7 (coalescing is a second, group
  supersede); ADR-0030 (a cancel's cause and member).
- **Documents repository:** doc 03 FR-081 (the toggle) and FR-082 ("once a Bond's reserved slot is
  spent today, no non-reveal push takes its last slot", beside ADR-0068's owed rewording).
- **Owed, outside this slice:** the existing INFO lines with bond ids (§9); ADR-0046's table, which
  lists `ProposalOpened` from `ChangeTimezone` and `RequestDeletion` only, though
  `EnableSemanticSearch` has published it since ADR-0054; the app's row 46, which says nothing is
  pushed for a search-by-meaning request (it is).

## 13. Owed, and what was settled

**Owed**
1. **The words**: the five strings of ruling 15 and the channel, in the app, coordinated with a
   release; the owner approves them.
2. **The app**: read `closedProposals` (stop saying neither), offer the `sharedSettings` toggle,
   route `data.kind = SHARED_SETTING_CHANGED` to the Bond.

**Settled, recorded as consequences**
3. Nudge 1 is no longer hard on decision 19's line (ruling 19 a).
4. Setting pushes are first come beside nudges (ruling 21).
5. A withdrawal can reach a member who was never pushed the proposal, within the outbox's ordering
   window (ruling 10).
6. Two concurrent deliveries can leave two owed setting pushes for one member (§7).
