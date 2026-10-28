# ADR-0046 — Push: which events are told, to whom, and which never are

**Status:** Accepted · **Date:** 2026-10-28 · **Deciders:** Daniel

## Context

Phase 4 slice N3 (spec `2026-10-27-phase-4-media-and-notifications-design.md` §4 N3, D-12,
D-13, D-14; the owner's rulings Q6, Q8, Q9 and Q13) is the last of the notification slices:
the consumers that decide **who is told what**. N1 (ADR-0044) built the transport and the
ledger and left the decision to the modules that hold the facts; N2 (ADR-0045) built each
member's toggles, quiet hours and budget, and the daily reminder. Nothing asked for a push
from a fact until now.

Six things made it non-obvious.

**The SOLO unlock has no `DayRevealed`** (D-12). `RevealDay` publishes it only for a day both
wrote on. A day that closes with one entry opens that entry to the partner (FR-063) inside
`CloseDay`, which publishes `DayClosed` for every close but a suspended one: `REVEALED`,
`SOLO`, `EMPTY`, `FROZEN`, and a `PENDING_REVEAL` the closer withheld because the bond had
separated (ADR-0035 question 9). And a `SOLO` day whose bond separated before it ended keeps
its entry its author's (ADR-0033 decision 9): it closes `SOLO`, and nothing opens.

**No event says a partner joined** (D-13), and **none says a proposal is waiting** (Q13).
`AcceptInvite`, `ChangeTimezone.propose` and `RequestDeletion.request` published nothing.

**"Both members or neither"** (ADR-0041 H-M5) against a member's own mute, night and budget
(D-14). Q9 ruled: the decision is shared, and a member's own settings hold back only their
copy. N2 put those settings on the push (`PushRecipient`); the consumers still had to read
them for both members from `bond`, which `gratitude` cannot reach but through `bond.api`.

**An ended bond.** ADR-0041 (Owed) and ADR-0040:141: the evaluation writes `MilestoneReached`
for an ended bond's last days too, and nothing may be pushed for a bond that takes no writes
(doc 02:144).

**Events that must never push.** ADR-0034 decision 11 (`:240`): no consumer subscribes to
`StreakBroken`. `StreakExtended` is the same hazard turned round: a push that a streak went on
is the countdown pressure doc 02 rules out. ADR-0037 decision 10: no push for a reaction,
which publishes no event at all.

**On this day is weekly, opt-in and private** (FR-092, ADR-0039 Q-O2), and only worth sending
when `GET /bonds/{id}/on-this-day` would show the member something.

## Decision

**1. Which events push, to whom, and under which key.**

| Event (publisher) | Consumer | Category | Told | Dedupe key | `stale_after` |
|---|---|---|---|---|---|
| `DayRevealed` (`RevealDay`) | `gratitude.notify` | `REVEAL` | both members | `reveal:{bondDayId}:{userId}` | end of the bond-day after the day's |
| `DayClosed` (`CloseDay`), day `SOLO` with its lone entry opened | `gratitude.notify` | `SOLO_UNLOCK` | the member who did not write | `solo:{bondDayId}:{userId}` | end of the bond-day after the day's |
| `MilestoneReached` (`EvaluateStreaks`) | `gratitude.notify` | `MILESTONE` | both members | `milestone:{bondDayId}:{userId}` | the event + 24 h |
| `MemberJoined` (**new**, `AcceptInvite`) | `bond.notify` | `PARTNER_JOINED` | the member who was already there | `joined:{bondId}:{userId}` | the event + 24 h |
| `ProposalOpened` (**new**, `ChangeTimezone`, `RequestDeletion`) | `bond.notify` | `PROPOSAL_OPENED` | the member who did not propose | `proposal:{proposalId}:{userId}` | the event + 24 h |
| none; a timer (`OnThisDayJob`, `scheduling`) | `SendOnThisDayPushes` | `ON_THIS_DAY` | the member who opted in, alone, at most one in seven days | `on-this-day:{memberId}:{bondDate}` | end of the bond-day |

The keys are the spec's, the user id ending each of a Bond's keys as it writes them (a
bond-day and a proposal belong to one bond, so a user id names one member there). Gratitude's
live in one place, `gratitude.domain.PushKeys`, beside N2's `ReminderKey`. The bond-day after
a day is the bond's own (its anchor timeline, asked at the day's end), so a zone change
between them is honoured, and a reveal held overnight by quiet hours still goes.

**2. Which events never push, and the two pins.** `StreakBroken`, `StreakExtended`, and
nothing for a reaction. `ArchitectureTest` (`no event consumer subscribes to a streak breaking
or going on`) fails if the file of any class implementing `EventConsumer` under `src/main`
spells `"StreakBroken"` or `"StreakExtended"` as a string literal, which is how a consumer
names what it subscribes to; KDoc names them in backticks to say why not. `DayPushesTest`
pins `gratitude.notify`'s set to exactly `DayRevealed`, `DayClosed` and `MilestoneReached`,
its id and `StartFrom.NOW`, and that no consumer bean in the context names a streak or a
reaction event. **The rule that bites is the run-time one** (review of #100):
`EventSubscriptionsTest`, in `app`, asks every `EventConsumer` bean of the whole application
context for its `eventTypes` and fails on any starting with `Streak`. The Konsist rule reads
files, so a type named through a constant written in another file passes it; that mutation was
run, and the Konsist rule stayed green while the run-time test went red. The Konsist rule stays
as the early warning.

**3. Both consumers start from `NOW`** (ADR-0041 Owed, ADR-0035 Owed): `BEGINNING` would push
every reveal, milestone and join ever recorded on the first deploy.

**4. The decision is the Bond's, and is made once** (H-M5, Q9, D-14). Every consumer first
asks `bond.api.PushAudiences.audienceOf(bondId)`, which answers in one read whether the Bond
takes writes and who its members are, each with their `PushRecipient` (zone, quiet hours,
toggles) as they stand now. **If the Bond takes no writes, nobody is told anything**: ended,
left, blocked or counting down to deletion. Otherwise a push is asked for every member the
row of the table names, and each member's toggle, quiet hours and budget decide their copy
alone, in `notification` (ADR-0045 decision 3). "Takes writes" is `BondStatus.isOpen`, which
`Bond.isOpen` now asks too: one rule, readable from a status alone.

`PushAudiences` lives in a **new file** in `bond.api`, implemented by `PushAudiencesAdapter`,
because a peer change (PR #97) is editing `BondAccess`, `BondAccessAdapter` and `BondViews`.
It is unguarded, like `ReminderRoster` and the closer's view: it answers an event or a timer,
never a request. What `ArchitectureTest` enforces is narrower than "only the consumers": its
two method names join the list of unguarded reads, and the rule lets **any** file in
`WORK_WITH_NO_CALLER` name any of them (the close job's three steps, the withdrawal's consumer,
the reminder sweep, and now `NotifyDayMembers` and `SendOnThisDayPushes`). It is a text match
on the names, and it is **not applied inside `bond`**, where `NotifyBondMembers` calls
`audienceOf` and nothing stops a `bond` controller from doing the same.
`ReminderCandidate.recipient()` is the one place a member row becomes a `PushRecipient`, for
the reminder roster and the audience alike.

**5. The SOLO unlock (Q8, D-12).** `DayClosed` pushes only when the closed day's status is
`SOLO` **and** its one live entry carries `revealedAt`: the stamp the close writes exactly
when the entry opened to the partner, and only then. The push goes to the members of the Bond
other than that entry's author. Every other status is told nothing by its close: `REVEALED`
was told by its `DayRevealed` (both events are written when a `PENDING_REVEAL` day reveals at
its end), and `EMPTY`, `FROZEN` and a withheld `PENDING_REVEAL` opened nothing. Its own
category and key (`SOLO_UNLOCK`, `push_solo_unlock_*`), worded as an opening and never as a
miss (FR-076, T-09): the same register as the reveal. It counts as reveal-class in the budget
and is governed by the `reveal` toggle (ADR-0045 decision 1).

**6. Partner joined (D-13).** `MemberJoined {bondId, memberId}` is published by
`AcceptInvite` in the join's transaction, beside the member row it describes. The joiner's
member id is carried so that the consumer knows whom not to tell; the joiner knows.

**7. The pending proposal (Q13).** `ProposalOpened {bondId, proposalId, proposerId}` is
published wherever a proposal row is made **that waits on the other member**:
`ChangeTimezone.propose` on a bond of two (alone, the creator's change applies at once and no
row is made), and `RequestDeletion.request`'s first ask on a bond of two (alone, the request
confirms itself in the same transaction and waits on nobody, so nothing is published).
`EndBond` makes no proposal; it cancels the live ones (ADR-0028). `proposerId` is the
proposer's **member** id, as the proposal row records it. The push goes to the other member
only, **once per proposal**: the key is the proposal's, and nothing anywhere asks again; no
reminder exists. **A proposal no longer live when the consumer runs is told to nobody**
(answered, cancelled or lapsed in the two seconds between): it waits on nobody any more.
Liveness is asked of `ProposalStore.findAllLiveOf`, the one predicate that says what "live" is
(ADR-0030). Its toggle is N2's `proposal`.

The events reach the publishing services through `BondSupport`, which the three already
share; detekt's constructor limit would otherwise have refused `RequestDeletion` a seventh
parameter. The edits to `AcceptInvite` and `ChangeTimezone` are one line each, plus the
field.

**8. On this day (FR-092): swept daily, sent at most once in seven days, only when the
endpoint would answer.**

- `OnThisDayJob` (`scheduling`) runs every quarter-hour, ShedLock `on-this-day-pushes`, and
  asks `gratitude.api.OnThisDayPushes.pushDue` about the hour that ended on the last
  quarter-hour boundary (the reminder job's design: a lost tick is made up by the next, and
  the key makes the overlap ask nothing twice).
- `bond` answers who is due: members who opted in (`onThisDay`, off by default), in a Bond
  that takes writes, not left, whose **12:00 in their reminder zone** fell in the window, with
  the bond-day it fell in (`PushAudiences.onThisDayDueBetween`, through the same
  `ReminderTime` rule as the reminder).
- `gratitude` asks **`GetDays.onThisDay`** with the member's own membership: the service that
  answers `GET /on-this-day`, not a second query that could drift from it, nor the controller
  or its response type (which PR #97 changes). It holds every rule of that answer, nothing on a
  bond that takes no writes from the member included. Empty, nothing is asked.
- **Private**: the member's own answer holds only what they can already read, the other
  member's opt-in is never read, and the push carries a key and the Bond's id.
- **At most one in seven days, counted from the ledger** (ruling on the review of #100). Before
  the archive is read, `notification.api.Notifications.hasLiveOrSent(userId, bondId,
  ON_THIS_DAY, now - 7 days)` asks whether a push of this kind was asked for this member in the
  last seven days and is `SENT` or still owed (`REQUESTED`, `HELD`). One that reached nobody
  (`EXPIRED`, `DROPPED_BUDGET`, `SUPPRESSED`, `CANCELLED`, `FAILED_PERMANENT`) does not spend
  the allowance. Ids and an instant in, a yes or no out: `notification` stays a leaf and learns
  nothing of why. V31 indexes the ledger on `(user_id, category, created_at)` for it.
- **The key is one per member per bond-day**, `on-this-day:{memberId}:{bondDate}`, so the
  sweep's overlapping windows ask for nothing twice.

  **Why not the ISO-week key first built**: it made the key the week's bound, and a key is
  spent by the request, not by the delivery. The independent review showed the failure: a
  member whose quiet hours run from 11:30 to 00:30 has her midday push held, then expired at
  the bond-day's end, and the week's key was gone: she was never told that week, however many
  memories it held, though she had opted in. A calendar week also allowed a push on a Sunday
  and another on the Monday. **The spec says weekly; this is a rolling seven days**, a
  deviation recorded for that reason.
- **A midday swept after its bond-day ended asks for nothing** (review of #100). The window
  looks back an hour, and in a member zone far from the bond's anchor, midday can fall in the
  last hour of a bond-day: swept after its midnight, `GetDays.onThisDay` answers for the next
  day, and a push asked under the ended day's key and deadline would expire at once. The clock
  is read after the answer, so a day not over then was not over when the answer was read.

**The spec says "a weekly sweep"; this sweeps daily.** A memory is a date in an earlier year:
a sweep run on one day a week would look at one date in seven and could never tell a member
about the other six. "Weekly" is kept where FR-092 puts it, on the push. **Midday** is ours:
away from the evening reminder and the reveal, which share the Bond's three a day (FR-082).
The owner may move it (Open).

**9. The words: keys only (Q6), and the copy table.** No push carries text, a name or a Bond's
name; the phone shows `push_<category>_title` and `push_<category>_body` (ADR-0044 decision 9)
from the apps' own string tables. The keys this slice makes reachable, with suggested English
for the owner to replace:

| Key stem | Title (suggested) | Body (suggested) | Never |
|---|---|---|---|
| `push_reveal` | Your day is open | Today's words are ready to read. | who wrote first or last |
| `push_solo_unlock` | Today's words are open | Something was written for you today. | that anybody did not write; "only", "missed", "alone" |
| `push_milestone` | A milestone | You've reached a milestone together. | a count, a streak, "don't break it" |
| `push_partner_joined` | You're both here | Your Moyi is ready for two. | the joiner's name |
| `push_proposal_opened` | A change to look at | A change to your Moyi is waiting for you. | what is proposed, or by whom |
| `push_on_this_day` | On this day | A memory from this day is waiting. | the partner, or what was written |
| `push_reminder` (N2) | A moment for today | A moment to write, when you're ready. | the partner, whether they wrote |

`copy.md`'s notification table (design system, outside this repository) gives the reminder as
"A moment for Tunde?", which names the partner; Q6 rules that out on a lock screen. Recorded
for the owner (Open).

**10. Records.** ADR-0044 decision 2 said the push-token lock's namespace `2` was free; bond's
"bonds of a user" lock (FR-025) uses it too. Amended there in this change (ADR-0045 Owed).

## Consequences

**Positive.** Every push FR-080 lists is now requested by the module that holds its fact, and
each is one push per member per fact however often the fact is delivered. An ended Bond is
told nothing, for every kind. Nothing about a member's push depends on what the other member
did alone (H-M1, FR-076): the reveal and the milestone go to both; the solo push goes to the
one member to whom something opened, and says only that it opened. The M4 path is whole:
the second entry to a `SENT` row per member and a payload of a token, a category and an id.

**Negative.** A Bond that ends between the fact and the consumer (two seconds) tells nobody,
even though the fact happened while it was open. A push asked for during a deletion countdown
that is later called off is never asked again. A proposal answered within two seconds is told
to nobody, and the outbox's ordering is not promised, so a reveal's push can come after a
later milestone's. A member row whose zone or toggles cannot be read (both are validated on
write, so only a hand-edited row) makes `audienceOf` throw, and that delivery is retried and
counted failing **for both members**, not only the one whose row is wrong.

**The cost of the on-this-day sweep.** Every quarter-hour it reads the whole active-member
roster (`reminderCandidates()`, the reminder job's scan of every member of every open bond,
which it already runs every five minutes) and keeps the opted-in members whose midday fell in
the window. For each of those it asks the ledger one indexed question. Only a member with no
push sent or owed in the last seven days goes on to the expensive read, `GetDays.onThisDay`,
which loads that member's candidate days and their entries in full to learn whether the answer
is empty: at most once a day per such member, and in practice once a week for a member with a
memory most days. The roster scan is the known cost at scale: a few thousand rows at doc 07's
planned size, read four times an hour here and twelve by the reminder; a SQL prefilter on the
zone's current offset is the step if it shows (ADR-0045, Alternatives).

**Neutral.** `notification.api.Notifications` gains one read (`hasLiveOrSent`) and V31 one
index. `bond`'s test context now scans `notification`, as `gratitude`'s does since N2.
`gratitude`'s test context replaces the logging push provider with one that keeps what it is
handed. `BondSupport` carries the outbox.

## Alternatives considered

- **A `DaySoloUnlocked` event from `CloseDay`** instead of reading `DayClosed` and the day.
  Rejected: D-12 assumed `DayClosed` and the day's status, the close already writes the stamp
  that decides it, and a second event would be a second statement of when a lone entry opens.
- **Decide "takes writes" from the event**, as of the fact. Rejected: the event carries ids
  only (ADR-0035), and the rule written (ADR-0041 Owed) is to ask the Bond.
- **Read the on-this-day answer with a narrower existence query.** Rejected for now: it would
  restate `GetDays.onThisDay`'s rules (the bond's today, readability, withdrawals,
  tombstones), and the first drift would be a push promising a memory the screen does not show.
  The step if the sweep is seen in the job's duration.
- **Push a proposal whatever became of it.** Rejected: a push about a change already answered
  sends the member to a screen with nothing on it.
- **A weekly cron for on-this-day.** Rejected in decision 8.

## Open (for the owner)

- **The cross-Bond cap** (ADR-0045 Open) is unchanged here.
- **The on-this-day time**: midday in the member's reminder zone, a default of ours.
- **The words** in decision 9 are suggestions, and `copy.md`'s reminder line names the partner.
- **A proposal opened and called off, again and again.** Each new proposal is a new push to the
  partner, so a member who proposes and cancels repeatedly can push the other up to FR-082's
  per-Bond bound: two a day from the remainder, since `PROPOSAL_OPENED` is not reveal-class
  (ADR-0045 decision 5), and within the six a day across Bonds. The routes carry no bucket of
  their own, only the global per-user one (120 a minute). Whether that is acceptable, or
  proposals want a cooldown or a cap of their own, is the owner's question.

## Owed

- **The words in the apps' string tables** (Phase 8), and the table above carried into the
  design system's `copy.md`, which lives outside this repository.
- **Metrics and the span** (ADR-0044 Owed): `gratitude_push_sent_total{category,status}`.
- **M4 itself**: a real Android phone (Q12's harness) receiving exactly one push at reveal.

## Revisit when

A push is wanted for an event this table does not list. The on-this-day sweep shows in the
job's duration. The owner rules on the on-this-day time or the words.
