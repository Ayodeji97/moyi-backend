# ADR-0029 — Optimistic concurrency over HTTP

**Status:** Accepted · **Date:** 2026-09-28 · **Deciders:** Daniel

## Context

Slice B4 builds the three endpoints that change settings: `PATCH /bonds/{bondId}` (the bond's
name, type, reveal time and rest days) and `GET`/`PUT /bonds/{bondId}/members/me/settings`
(one member's reminder time, quiet hours and the nickname only they see).

Doc 06 §1 says one sentence about it: *"`ETag` on mutable resources; `If-Match` required for
updates to Bond settings."* The problem that sentence exists to prevent is the **lost update**:
two members open the settings screen, both change the name, and the second write erases the
first with no error for either of them to see. In a two-person product where both people are
often editing the same small object, that is not a theoretical concern.

`BondEntity` has carried `@Version` since slice B1, added for exactly this slice, and every
bond response has carried its value as an `ETag` since then. What B4 had to decide is what
*enforces* the condition, and the answer turned out not to be the one the plan assumed.

`states.md` §8 adds the other half: *"Never show the partner's notification settings or quiet
hours."*

## Decision

**1. The `ETag` is the row version, and `If-Match` is required on `PATCH /bonds/{bondId}`.**
Absent → `428 PRECONDITION_REQUIRED`. Present and not current → `412 PRECONDITION_FAILED`.
Present and current → the write proceeds, and the response carries the **new** `ETag`, because
handing back the version the client sent would give it a value its next `If-Match` would be
refused with (the defect the review of PR #38 found on accept).

**2. The bond's row lock is what makes that check mean anything.** Comparing a version and then
writing is a read-check-write, and over a READ COMMITTED snapshot two callers can both read
version 0, both be satisfied, and both commit. So `UpdateBond` takes `SELECT … FOR UPDATE` on
the bond first — the same lock `AcceptInvite`, `CreateInvite` and `EndBond` take — and the read,
the check and the write are one serialised decision.

**3. Three mechanisms, each doing something different**, which is worth stating because this ADR
was drafted twice with it wrong:

| | What it does |
|---|---|
| `@Version` on the row | *Is* the `ETag`. Under genuine concurrency it also fires as a backstop — each transaction loads its own copy, so the loser's `UPDATE … WHERE version = ?` finds no row — but it surfaces as a **500**, which is not an answer any client asked for. It cannot catch a stale aggregate handed to the store in a later transaction at all, because the store re-reads the row before writing. |
| `If-Match` | Turns "somebody else changed this" into a `412` the client can explain to a person. |
| the row lock | Makes the check atomic, so the `412` is what a loser actually receives instead of the 500. |

Removing the lock and running `BondSettingsRaceTest` produces `[200, 500]` on two callers and
loses three of seven on eight — that is the evidence for this table, and the reason all three
stay.

**4. The write flushes.** `@Version` is incremented at flush and `EntityManager.find` answers
from the persistence context without one, so a service that writes and then re-reads to build
its response sees the *old* version. That is how the first implementation returned `ETag: "0"`
after a successful patch. `BondStore.update` uses `saveAndFlush`; its KDoc says why, because
"simplifying" it back to `save` breaks something no compiler will mention.

**5. A patch that changes nothing writes nothing**, so the version does not move and the other
member's `ETag` stays valid. Found by the smoke script. An **empty** patch is a `422` rather
than a successful no-op, because "change these zero things" is a client mistake worth naming.

**6. Member settings take no `If-Match`, deliberately.** The row belongs to one member and
nobody else can write it, so there is no update for a concurrent writer to lose. Requiring a
condition where nothing can conflict is ceremony, and ceremony teaches clients to send headers
they do not mean. The contract says so by documenting no `412` on that operation.

**7. `PUT`, not `PATCH`, for member settings** — five small fields on one screen, which a client
always holds in full. It replaces: an absent field is cleared. The single exception is
`reminderTimezone`, which keeps its current value when absent, because silently resetting
somebody's zone as a side effect of editing a nickname is the quiet damage doc 04 §6 warns about.
`reminderTimeLocal` is required, so a `PUT` that omits the one field a member cannot be without
is told so rather than set back to 20:00.

**8. `PATCH` carries `type` and refuses `anchorTimezone`.** The type is unilateral (ADR-0013 §8,
`states.md` §8) and does **not** move `max_members`: FR-021 puts the limit on the row, every v1
type seats two, and a type that ever seats a different number needs a rule about the members
already in the bond. The anchor zone is two-party and once per 30 days (FR-027), which is slice
B5's `PATCH /bonds/{bondId}/timezone`; a body naming it here is a `422` rather than a silently
dropped field, because ignoring it would report success for a change that never happened.

**9. Two deliberate departures from RFC 9110 §13.1.1**, both in `IfMatch`'s KDoc:

- **`If-Match: *` is `428`**, not a match. The RFC says `*` matches any current representation,
  so a compliant server performs the write — but the point of requiring the condition is to
  prevent a lost update, and `*` asks to skip exactly that check. Honouring it would make doc 06
  §1's requirement decorative. This is knowingly non-compliant; a client that means to overwrite
  re-reads and sends the version it saw.
- **An unparseable validator matches nothing**, producing `412` rather than a `400` about the
  header's shape. The asymmetry is the whole argument: a false "does not match" costs a client
  one retry, and a false "matches" is a write lost in silence.

A weak validator (`W/"3"`) never matching is *not* a departure — `If-Match` uses strong
comparison.

**10. The guard runs before the condition is parsed.** A `428` or a `412` to a non-member would
confirm that the bond is real, which is T-02's oracle delivered to the one caller who must not
have it. A malformed *body*, by contrast, is a `422` for member and non-member alike, and that
discloses nothing: the answer depends only on what the caller sent, and a member sending the
same body gets the same answer. Both are asserted.

## Consequences

- **Two new `ErrorCode` values, so this is a breaking change** and the PR carries
  `breaking-api-change` (ADR-0024's 2026-09-24 amendment): doc 06 §2 makes the codes an
  exhaustive sealed class on the client, so a new value is a compile error there.
- **`IfMatch` and the two exceptions live in `common:web`**, not in the bond module — they are
  protocol concerns, and B5's proposals and Phase 3's entries will want them.
- **B5 inherits all of it**: the timezone proposal and the deletion request are bond-scoped
  writes, so they need the archived rule, and any of them that changes the bond row needs the
  lock and the condition.
- **Phase 3's entries will need the same treatment** if an entry ever becomes editable, and the
  decision to make then is whether an entry's `ETag` is its own row version or the bond's.
- **`Change<T>` is the answer to `PATCH`'s one real ambiguity** — an omitted field keeps its
  value, a field sent as `null` is cleared — and it is in the domain, so B5 can use it without
  reinventing the distinction. On the wire that distinction is `Optional<T>`, and the container
  form of a constraint (`Optional<@Pattern String>`) **compiles and does not run**, which cost a
  500 until a test caught it.
- **A member's settings write does not invalidate the bond's `ETag`.** That is a property a
  future "load the aggregate, change a member, save the aggregate" refactor would quietly break,
  so it is asserted at both the persistence and the endpoint level.

## Alternatives considered

- **A `version` field in the request body.** Rejected: HTTP has a place for this, and a body
  field would be a second source of truth for the same number — one more thing to drift.
- **Last write wins.** Rejected; it is the bug, and in a two-person product both people are
  editing the same small object.
- **`@Version` alone, with the 500 mapped to a 412 by advice.** Rejected: it works only under
  genuine concurrency (it cannot see a stale `ETag` from ten minutes ago), it turns an ordinary
  edit conflict into an exception path, and it gives the client no way to *avoid* the conflict
  by checking first.
- **The row lock alone, without `If-Match`.** Rejected: it prevents corruption and permits a
  lost update — the second writer's values simply win, which is the behaviour doc 06 §1 forbids.
- **`If-Match` on the member-settings `PUT` too**, for consistency. Rejected as ceremony (§6).
- **A `PATCH` for member settings.** Rejected: it would need `Change<T>` for five fields to say
  what a `PUT` says by existing, and the client always has all five.
- **Dropping `leftAt`-style cross-field validation into the edge only.** Rejected: the quiet-hours
  pair rule is in the aggregate *and* at the edge, because V9 permits either column alone and the
  edge cannot ask the domain without building a `Member`.

## Revisit when

- B5 adds `bond_proposals` — a proposal is a bond-scoped write and needs the same rules.
- Phase 3 makes an entry editable, and the `ETag` question above needs answering.
- A client ever needs to change the anchor zone and a member setting in one round trip, which
  this design deliberately does not offer.
