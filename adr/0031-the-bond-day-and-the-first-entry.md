# ADR-0031 — The Bond-day, and the first entry

**Status:** Accepted · **Date:** 2026-09-29 · **Deciders:** Daniel

## Context

Slice C1 is Phase 3's first: `POST /bonds/{bondId}/entries` and `GET /bonds/{bondId}/today`
(spec §3.1, §4, §5.1, §5.4, §6.1, §6.2, §7). It gives a bond a calendar it did not have before
— the Bond-day, opened lazily on the first write a member makes for a date — and gives a
member their first words on it, gated by BR-8 the moment anybody else tries to read them
early.

Three things shape the design before a line of code does. **BR-2** makes "one entry per
member per Bond-day" a database fact, not an application check, because a check-then-insert
races the very thing it is trying to prevent. **BR-8** makes a locked entry's wire shape a
security control: `{authorMemberId, status: LOCKED}`, nothing else, ever, ratified by a test
doc 12 calls the most important file in the repository. And **doc 04 §8.3a**, amended
2026-09-28 by the Phase 3 design's own §12.4, resolves a contradiction its first draft did not
survive contact with BR-2: it once said a Bond-day is "not created or evaluated" while its bond
waits for a second member, and `02` J1 — the creator may write before their partner joins —
guarantees a write that needs exactly the row that rule says must not exist.

C1 does not reveal anything. `PENDING_REVEAL`, `PATCH`/`DELETE /entries/{id}`, and the outbox
are C2's; the close job is C3's; streak and prompt in the `today` payload are C4's and C6's.
This ADR is the record for what nine tasks decided while building the part that does ship —
the bond-day, the first entry, and the gate around reading somebody else's.

## Decision

**1. `bond.api.BondMembership`'s constructor is `internal`, and it replaces two Konsist rules,
not restates them.** ADR-0026 made `bond.service.Membership` a value only `BondAccessGuard`
could construct, held in place by two Konsist rules (a layer-import rule and a construction
rule) — conventions a reviewer has to remember to keep checking. `BondMembership` is public so
`gratitude` can hold one and read it, and `internal` at the constructor so nothing outside
`bond` can mint one: Kotlin's `internal` is scoped to the Gradle module, so the guarantee
crosses the module boundary as a compiler error instead of a lint rule. A previous attempt at
closing the testing gap this created — a public `BondMembership.forTesting` factory — was
reverted for reopening exactly the forgeability the constructor exists to prevent; the actual
fix was widening `GratitudeTestApplication`'s component scan to `com.moyi.bond` (decision 9),
which wires the real `BondAccessAdapter` without ever letting `gratitude`'s own source name it.

**2. `bond_days` opens lazily, through a single `INSERT ... ON CONFLICT DO NOTHING`, not an
entity `save()`.** The first write to reach a bond and a date opens the row; every later one —
including the reveal job and, eventually, the close job — finds the same one. `BondDayId` is
minted by the caller before the insert, from the injected `IdGenerator`, and discarded by
whichever caller loses the race along with the row it never wrote — the same shape
`BlockStore`'s own `insertIfAbsent` already used. No `BondDay.toEntity` exists at all: nothing
in this slice's own code ever calls one, and carrying it would be exactly the speculative code
doc 12's review checklist asks not to write.

**3. A Bond-day stores its own copy of the anchor timezone, and that copy never moves.**
`bond_days.anchor_timezone` is written once, at open, and `BondDay.applyTo` — the one place a
day's row is written again — does not copy it, deliberately: a day already opened is never
recomputed, so a later change to the bond's own anchor zone (FR-027's proposal, ADR-0030) must
never reach back and silently redate an entry somebody already wrote against the zone that was
current when they wrote it. `ZoneId.of` re-validates the stored string on the way out of the
database too, the same call `bond.domain.RegionZone.of` makes, so a zone the JDK's tzdb has
since dropped fails loudly rather than filing somebody's day under the wrong date.

**4. `SUSPENDED`, not `OPEN`, for the day a `PENDING_MEMBER` bond's creator writes into — and
that resolves doc 04 §8.3a's own contradiction, not just this slice's ambiguity.** §8.3a's
first draft said a Bond-day is "not created or evaluated" while its bond waits for a second
member; `02` J1 guarantees the creator may write before the invitee joins; and `entries` hangs
off `bond_day_id` under BR-2's own unique index, so a write with nowhere to land is not
optional. The two clauses cannot both hold if the row is absent. §8.3a's amendment settles it
the way its own mechanism already worked for ordinary suspension (§8.1): the row **is**
created, with `status = SUSPENDED`, so the close job and the streak walk skip it by status
without either manufacturing a `SOLO` day or burning a freeze before the product has worked
once. `BondDay.withEntry()` keeps a `SUSPENDED` day `SUSPENDED` on every later write to it,
rather than flipping it to `PARTIAL` the way an ordinary day's second entry does — it is its
own mechanism, not `OPEN` wearing a different name, and treating it as the latter would put it
back in a walk that is built to skip it.

**5. `BondDayId` is a v7; `EntryId` is a v4 — different UUID versions on purpose, and each for
a different clause of doc 06 §1.** A Bond-day is not a secret: nothing in `states.md` asks a
caller to hide when one opened, no route puts a `BondDayId` where a stranger could guess at it,
and the creation time a v7 embeds is what gives `bond_days_bond_date_key` and
`bond_days_feed_idx` their index locality — free to have, worth having. An entry is the
opposite case: BR-8 locks a submitted entry's content until reveal, and a v7 id would leak, in
the id itself and independent of anything BR-1 gates, the one fact BR-8 exists to suppress —
when it was written. `EntryId` is `IdGenerator.opaque()`'s v4, which never carries a timestamp
to begin with, so there is nothing in the id for that leak to find.

**6. `imageMediaId`/`voiceMediaId` are refused with `422 MEDIA_NOT_YET_SUPPORTED`, never stored
and silently dropped.** Phase 4 has not built anywhere for either to go yet. Accepting the field
and dropping it on the floor would be a response promising something this deployment cannot
keep; refusing it is a fact about *this deployment*, not about whether the id the caller sent is
shaped like a UUID; `entries.text`/`image_media_id`/`voice_media_id` stay nullable columns
carried at `NULL` from the first row, the same way `bond.domain.Bond` has carried
`timezoneChangedAt` and `deletionRequestedAt` unset since long before the slices that ever set
them.

**7. `LockedEntryResponse` is a distinct type, not `EntryResponse` with its fields nulled out —
and that distinctness is BR-8's actual enforcement mechanism, not a style preference.** A
nulled-out `EntryResponse` *can carry* the text, the timestamps, whether there is an image — it
just happens not to today. The day somebody adds a field to `EntryResponse` six months from now
and forgets that a nulled instance of it is also a locked entry, that field leaks on every
locked entry there is, silently, because nothing about the type says it must not.
`LockedEntryResponse` cannot leak that field: there is no path from "add a field to the revealed
shape" to "it appears here" for a compiler to miss, because there is no such path to forget.
`RevealGateTest`'s first case pins the wire shape byte for byte against exactly this.

**8. `Idempotency-Key` is reserved — a row written, under a unique constraint — before the
handler runs, not after, and the record is Postgres, not Redis.** A row inserted on the way in
is what makes a concurrent retry a `409` rather than a second execution of `SubmitEntry`;
writing it afterwards would leave the exact window the header exists to close, since two
identical requests would both find nothing reserved and both run the handler, with the
second's own `INSERT` racing only the first request's own write of its result — too late to
stop anything. `idempotency_keys` (V11) is Postgres rather than the Redis a naive reading of
doc 06 §1's own key shape (`idem:{userId}:{endpoint}:{key}`) suggests, because doc 05 §3 puts
idempotency, revocation and ShedLock all on Postgres so Redis stays removable in one session —
the Phase 3 design §12.3 settles it the same way for both this and ShedLock. The request body
is hashed, never stored (doc 18 §9); the *response* body is stored in the clear, so a replay can
return exactly what the first attempt returned, bounded to Ruling B's 24-hour `expires_at`
window and cleared once slice C3's reaper lands. **That column is a known, flagged liability
ahead of Phase 5:** `entries.text` is not encrypted yet, but Phase 5 encrypts it at rest, and
`idempotency_keys.response_body` will still hold that same entry's text in plain text for up to
a day afterward unless Phase 5's own migration also touches this table. V11's own comment
names this so that work finds it by reading the migration, not by an audit.

**9. The Bond-day's row is locked before its `entryCount`/`status` are read for the update, and
the lock alone was not enough — `EntityManager.refresh` is the second half of the fix.**
`BondDayStore.lockAndFind` takes `SELECT ... FOR UPDATE` on the day's row, the same lock
`bond`'s own read-modify-write writes already hold (ADR-0028's lock rule), then reads the row
back. The first version of this method took the lock and still returned a stale row under
concurrency: `openOrGet`'s own `findByBondIdAndDate` moments earlier had already loaded the same
entity into the transaction's persistence context, so the later `findById` inside `lockAndFind`
answered from that identity map rather than from the database — the *same stale Java object*,
lock or no lock. `SubmitEntryConcurrencyTest` failed with a `500` even with the lock correctly
wired in, for this reason. `entityManager.refresh(entity)` is what forces Hibernate to
re-populate the already-loaded object from the row this transaction now holds exclusively,
rather than serving its own first-level cache. Without it, the lock is real and does nothing —
the same shape of gap ADR-0029 §4 already found in `BondStore.update`'s own flush.

**10. The reveal is absent from this slice, deliberately, and `TodayResponse.partnerEntry`'s
own design already anticipates it rather than hard-coding "always locked."** No status C1
assigns — `OPEN`, `PARTIAL`, `SUSPENDED` — ever satisfies BR-1's second or third clause, so
every `partnerEntry` this slice ever produces is `LockedEntryResponse`. `Entry.canBeReadBy`
is still written for BR-1's full three-clause rule, not for the subset C1 can reach:
`memberId == authorMemberId || day.status == REVEALED || (day.status == SOLO && day.isClosed)`,
with the second and third clauses unreachable today and left in place for C2 and C3 to make
reachable without this method changing at all. **Verified by mutation, not assumed:** flipping
`canBeReadBy` to `= true` fails exactly two `RevealGateTest` cases — *"a locked entry is exactly
an author and a status, and nothing else"* and *"priming today as one member does not serve it
to the other"* — and both fail through the same mechanism, one member submitting and the other
reading `partnerEntry`; the cache-priming test's own body never touches `myEntry`, so an earlier
fix report's claim that its failure came from `myEntry`'s routing does not hold. This is recorded
here, explicitly, so C2's author reads the reveal's absence as a decision this ADR made rather
than as something nobody thought about.

**11. `TodayResponse.partnerEntry`'s `oneOf` gets an explicit `discriminator`, added in this
task.** Task 8 left the fix for this named but undone: `PartnerEntryResponse` is a Kotlin
`sealed interface` with nothing for springdoc to introspect by annotation. Running the generator
for this task found the KDoc's own fear half wrong — this springdoc version already reads a
sealed interface's `permittedSubclasses` on its own and produces
`oneOf: [EntryResponse, LockedEntryResponse]` without any customizer. What stayed missing was
narrower: no `discriminator`, so a generated client would still have to try both shapes
structurally rather than read one field. `OpenApiConfiguration.discriminatePartnerEntry` adds
one keyed on `status`, mapped explicitly for every value either branch's enum can hold
(`SUBMITTED`/`REVEALED`/`DELETED` → `EntryResponse`, `LOCKED` → `LockedEntryResponse`) — an
unmapped discriminator value falls back to naming a schema directly, and none of those four
names one, so leaving any of them out would have made the fix reintroduce ambiguity in a
different shape. Asserted in `OpenApiContractTest`.

## Consequences

- **Five new `ErrorCode` values, so this is a breaking change**: `ENTRY_ALREADY_EXISTS`,
  `DAY_CLOSED`, `MEDIA_NOT_YET_SUPPORTED`, `IDEMPOTENCY_KEY_IN_FLIGHT`, and
  `IDEMPOTENCY_KEY_REUSED` — checked against `git diff cdd7ac8 HEAD --
  common/web/.../ErrorCode.kt` (`cdd7ac8` is this branch's merge-base with `main`) rather
  than assumed; an earlier count in this slice's own planning said four and missed
  `IDEMPOTENCY_KEY_REUSED`, which `git log -S` traces to the same commit as
  `IDEMPOTENCY_KEY_IN_FLIGHT` (Task 2, `209c799`). Doc 06 §2 makes the codes an exhaustive
  sealed class on the client, so a new value is a compile error there — the PR carries
  `breaking-api-change` regardless of which count.
- **`GET /bonds/{bondId}/today` ships without the `partner` field spec §3.4's own payload
  names.** `BondMembership` carries only the caller's own facts — their own member id, their
  own user id — and names no fact about who the other member even is, so there is no id here to
  resolve a display name for through `identity.api.UserDirectory`, written or not. This is a
  known gap against doc 06 §3.4, not an oversight: inventing a shape for a field this module
  cannot populate correctly risks exactly the failure BR-8 exists to prevent elsewhere in this
  same response. Surfacing a partner's identity is `bond.api.BondAccess`'s own gap to close.
- **`app/build.gradle.kts` did not depend on `modules:gratitude` until Task 7** — three
  controller methods, tested and green, entirely absent from the real application and from the
  generated contract until that dependency line was added. `bond` picked up the same line in
  slice B1, its own first-controller task; a module can be complete and invisible, and the
  thing that makes it visible is one line nothing about the module's own tests would ever catch
  missing.
- **`gratitude`'s tests now depend on `bond`'s HTTP behaviour.** `GratitudeTestApplication`
  widens its scan to `com.moyi.bond` (decision 1) so `EntriesEndpointTest` and
  `GratitudeCrossTenantTest` can drive real `createBond`/`accept`/`leave` calls rather than a
  fake stood up to dodge them — `com.moyi.bond`'s classes are `internal` at the Kotlin level but
  ordinary public bytecode, so the scan finds and wires the real controllers without this
  module's own source ever being able to name one. The cost: adding a route to `bond` can now
  fail a test that lives in `gratitude`, and `GratitudeCrossTenantTest`'s fixture table is a
  copy of `BondCrossTenantTest`'s own, not a shared one, so the two can drift. Both are flagged
  in the files themselves for the next reader who hits either.
- **`idempotency_keys.response_body` is a second copy of an entry's text**, in the clear, for up
  to 24 hours, with no reaper yet (C3). Phase 5 encrypts `entries.text`; this table is flagged
  now, in the migration and here, so that slice meets it as a decision already on record rather
  than as an audit finding.
- **The row lock plus `EntityManager.refresh` pattern (decision 9) is now the second instance of
  "the lock is real and does nothing without a flush/refresh"** — ADR-0029 §4 found the write
  side of this in `BondStore.update`; C1 found the read-back side of the same fact in
  `BondDayStore.lockAndFind`. Any future store that locks a row before a read-modify-write needs
  to ask this question explicitly, because neither Hibernate nor the type system will ask it for
  you.

## Alternatives considered

- **A `RevealedEntryResponse` with every field but `authorMemberId` left `null`, instead of
  `LockedEntryResponse` as its own type.** Rejected (decision 7): it can carry a field the
  moment somebody adds one to the revealed shape, and BR-8's whole point is that it must not be
  able to.
- **`@OneToOne`/`.toEntity()` for `BondDay`, matching `Entry`'s own persistence shape.**
  Rejected: every row this slice ever creates goes through `insertIfAbsent`'s native
  `INSERT ... ON CONFLICT DO NOTHING`, so a `toEntity` would be dead code nothing calls —
  the two aggregates are persisted differently because they are written differently, not by
  oversight.
- **Recomputing a Bond-day's `anchorTimezone` from the bond's current row on every read**,
  rather than storing a copy. Rejected: a day already opened must never be silently redated by a
  later timezone change (decision 3) — the copy is what makes "the zone this day opened under"
  a fact the row itself carries, rather than a join that can answer differently tomorrow.
- **A public `BondMembership.forTesting` factory**, to give `gratitude`'s tests a membership
  without widening `GratitudeTestApplication`'s scan. Tried, reverted: it reopened the
  forgeability the `internal` constructor exists to prevent — any caller with the factory could
  mint a membership for a bond it was never granted, which is the whole thing decision 1 is
  supposed to close off.
- **A generic `response_headers` blob on `idempotency_keys`**, rather than the two explicit
  `response_etag`/`response_location` columns. Rejected: `ETag` and `Location` are the only two
  headers any endpoint in this codebase sets that a client cannot rebuild from the body, so
  there is nothing generic to gain and a fixed pair reads back without a parser.
- **Hard-coding `partnerEntry` to always serialise as `LockedEntryResponse`**, since C1 can
  never produce anything else. Rejected (decision 10): it would leave C2's reveal and C3's
  `SOLO` to discover, on their own, that this response type never actually reveals anything —
  asking `Entry.canBeReadBy` the real question now is what lets `RevealGateTest`'s own mutation
  proof mean something.

## Revisit when

- C2 adds `PENDING_REVEAL`/`REVEALED` and the outbox — `Entry.canBeReadBy`'s second clause
  becomes reachable for the first time, and `RevealGateTest`'s matrix extends to cover it
  without narrowing what C1 already asserts.
- C3 adds the close job and `SOLO` — the third clause becomes reachable, and the reaper that
  clears `idempotency_keys` on schedule (ShedLock) is due at the same time.
- Phase 4 builds somewhere for `imageMediaId`/`voiceMediaId` to go, and `422
  MEDIA_NOT_YET_SUPPORTED` needs to become an actual write path rather than a refusal.
- Phase 5 encrypts `entries.text` — this ADR's decision 8 is the checklist item that work needs
  to close, in `idempotency_keys` as well as in `entries`.
- `bond.api.BondAccess` gains a way to name the other member, and `GET /today`'s `partner` field
  (this ADR's own gap against doc 06 §3.4) can finally be built instead of documented as missing.
