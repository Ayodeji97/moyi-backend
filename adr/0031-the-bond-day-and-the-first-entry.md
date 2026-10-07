# ADR-0031 — The Bond-day, and the first entry

**Status:** Accepted · **Date:** 2026-09-29 · **Deciders:** Daniel
**Amended:** 2026-10-03 (the C1 rework, then the final whole-branch review) · 2026-10-05 (C2: the Owed list discharged, decision 17's read rule qualified — ADR-0032; C3: its Owed list discharged — ADR-0033)

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

**It was then built a second time.** The spec was revised on 2026-09-30 while PR #46 was still a
draft, and five things the first build had decided were wrong against the revision: how a zone
change is deferred (§3.1), where the bond lock is taken (§2.1), what an idempotency record
stores and when it commits (§5.4), what BR-1 keys on (§4), and when BR-3a is rechecked (§6.1.3).
Ten more tasks rebuilt them. Decisions 3, 8, 10 and 11 below are rewritten to say what shipped,
not what the first build shipped; decisions 12 to 21 are new; the last section says, decision by
decision, what changed and why. Where a decision below names a ruling (P1 to P10, R1 to R3), it
is one made during that rework: R1 to R3 by the plan, the rest by the build's own ledger.

**And then it was read whole.** Ten per-task reviews had passed the rework. Three reviewers
then read the entire branch at once and found no critical defect and several real ones, each
of which either crossed task boundaries or sat in first-build code no rework task had
reopened. Decisions 22 to 25 are what that review ruled (P11, P12 and two more), and the
spec was amended in the same change so that it and this ADR tell one story: every departure
from the spec recorded here is now marked in the spec, dated, citing the decision.

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
The same mechanism now covers the two types the membership carries with it,
`BondAnchorTimeline` and `BondDayBounds` (decision 16), and it is why `gratitude` could not use
either of them in a unit test (also decision 16).

**2. `bond_days` opens lazily, through a single `INSERT ... ON CONFLICT DO NOTHING`, not an
entity `save()`.** The first write to reach a bond and a date opens the row; every later one —
including the reveal job and, eventually, the close job — finds the same one. `BondDayId` is
minted by the caller before the insert, from the injected `IdGenerator`, and discarded by
whichever caller loses the race along with the row it never wrote — the same shape
`BlockStore`'s own `insertIfAbsent` already used. No `BondDay.toEntity` exists at all: nothing
in this slice's own code ever calls one, and carrying it would be exactly the speculative code
doc 12's review checklist asks not to write. Why the unique index under that `ON CONFLICT` is
still doing work now that every submitter queues on the bond lock first is decision 18.

**3. The bond owns a timeline of which zone decides dates, and a Bond-day stores the span that
timeline gave it. The zone string on the row is a snapshot; it is not what defers a change.**
The first version of this decision defended the opposite. It said the day's own copy of
`anchor_timezone`, written once when the row is lazily opened, is what makes "an existing day is
never recomputed" true by construction. That is true of a day that has a row. BR-6 asks for more:
an approved change takes effect from the *next* Bond-day. B5's `ChangeTimezone.confirm` writes
`bonds.anchor_timezone` the instant consent completes, and on a day nobody has written to yet
there is no `bond_days` row to have copied anything onto. So the first write after a
confirmation opened *today* under the new zone, and the change took effect mid-day, on exactly
the day BR-6 protects. A string copied at lazy row creation cannot say "from tomorrow", because
the thing that would have to remember it does not exist yet. The spec's revision says so in as
many words: "copying a string at lazy row creation is insufficient".

What shipped instead:

- **`bond_anchor_intervals` (V13, owned by `modules:bond`)** — one row per span of UTC time over
  which one IANA zone was *effective*: `zone`, `first_label`, `effective_from`, `effective_to`.
  Exactly one row per bond is open (`effective_to IS NULL`, held by the partial unique index
  `bond_anchor_intervals_open_key`); contiguity is the domain's invariant, checked in
  `AnchorTimeline`'s `init`. V13 backfills one open interval for every existing bond, from its
  `created_at`, in its current zone. `CreateBond` seeds the same row for a new one.
- **`bonds.anchor_timezone` is the zone the bond *requests*; the timeline is the zone that
  *decides dates*.** They differ for up to one logical day after a change. `confirm` still
  writes the request at once, so `GET /bonds/{id}` reflects consent immediately (B5's behaviour,
  unchanged), and then schedules the **deferred handoff**: `AnchorTimeline.handoffFor` picks the
  end of the current logical day, and `AnchorIntervalStore.scheduleHandoff` closes the open
  interval there and opens the next one. Every handoff is scheduled under the bond's row lock.
- **`bond_days.starts_at` and `ends_at` are the authority on which instants belong to a day.**
  The span is taken from the timeline when the row is opened and persisted
  (`BondDayStore.openOrGet`). A zone id cannot describe a day a handoff clipped, merged
  (decision 12) or skipped (decision 14), so the pair of instants is what a day *is*.
  `bond_days.anchor_timezone` stays, as the zone in force at `starts_at`, for display and
  audit; nothing decides a date from it. `ZoneId.of` still re-validates it on the way out of
  the database.
- **`DayAssignment` takes the calendar's day whole.** It asks "which day contains this instant"
  and files the entry on the window it is given; it computes no midnight of its own. `GetToday`
  asks the same calendar, so a read and a write agree about what today is for the whole of a
  deferred change. Reading `anchorTimezone` in either place would bring the defect back.
- **`first_label` is on the interval from its creation (P1), and it is what keeps the modules
  apart (P6).** The handoff must not land on a label the bond has already used. The plan's
  first draft answered "which labels are used" by reading `gratitude`'s `bond_days` from
  `bond`, across the module boundary. With `first_label` stored and the intervals contiguous,
  the used labels are the run from the first interval's `first_label` to today's, and
  `AnchorTimeline.usedLabelsUpTo` answers it inside `bond` with no read of another module's
  table. `AnchorInterval`'s `init` requires `first_label` to be the date at `effective_from` in
  its own zone, so the run cannot be wrong by construction.

V12 was edited in place to add the two columns and V11 to change shape (decision 8). Both were
unmerged, which is the one case doc 07 §1's forward-only rule allows.

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

**Amendment (F4, whole-branch review): nothing in this slice ever writes a Bond-day back out
of `SUSPENDED`, and that is an explicit obligation this ADR hands to whichever slice adds the
reveal, not a gap this decision leaves unnoticed.** Walk it through: Ada creates a bond and
writes on day D — the row opens `SUSPENDED`. Bea accepts the invite at 14:00 the same day and
writes too; `GetToday` still reports `SUSPENDED` to her, because the row exists and its stored
status is what `GetToday` returns. `membership.awaitingSecondMember` is already false by then
(the bond became `ACTIVE` when she accepted) and is only consulted for a day that has no row.
Her write runs `withEntry()` against the same row a second time, which — correctly, per the
decision above — keeps it `SUSPENDED` rather than promoting it to `PARTIAL`. The day now carries
`entry_count = 2` and `status = SUSPENDED`, both members' words present, and the bond itself no
longer awaiting anyone. Nothing in this branch, or in the state machine C2's reveal will read,
ever moves a day off `SUSPENDED` again: C2 will look for `PARTIAL`/`PENDING_REVEAL` to decide
what is due for reveal, and C3's close job's own partial index (`bond_days_open_idx`, V12)
deliberately excludes `SUSPENDED` — built to leave a genuinely-still-suspended day alone. If the
slice that adds the reveal does not also add the transition that un-suspends a day once its bond
stops awaiting a second member, **the couple's first shared day — the one this whole slice exists
to let them write on together — never reveals, and both of their first entries stay locked to
each other permanently.** This slice deliberately does not build that transition: inventing it
here, without the reveal's own state machine in view, risks contradicting it. `BondDay.withEntry`'s
own KDoc carries the same note, for the reader who lands there instead of here.

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
`RevealGateTest`'s first case pins the wire shape byte for byte against exactly this. The rework
added a second type built on the same reasoning, `ErasedEntryResponse`, for an entry withdrawn
before it was ever revealed (decision 10).

**8. An `Idempotency-Key` is locked, reserved, used and completed in the same transaction as the
write it guards, and what it records is the result's identity, never a body.** The first version
of this decision reserved the row in the interceptor's `preHandle`, ran the handler, and
completed the row in `afterCompletion`: three transactions. It also stored the whole response
body in `idempotency_keys.response_body`, in the clear, and flagged that column as a liability
for Phase 5. Both are gone.

- **`IdempotentExecution.once(request) { … }` runs inside the caller's transaction** and refuses
  to run without one. In order: a transaction-scoped **nonblocking** advisory lock on
  `(user_id, key)` (`pg_try_advisory_xact_lock`, namespace 3, so it cannot collide with `bond`'s
  own per-user advisory lock in namespace 2), where `false` is
  `409 IDEMPOTENCY_KEY_IN_FLIGHT` at once; the lookup; then the reservation insert, the
  caller's block, and the completion. They commit or roll back as one. The
  three-transaction shape could crash between the first and the third
  and leave a committed reservation with no result (a `409` for 24 hours, for a request that
  never happened), or between the second and third and leave a committed entry under a key that
  would run it again. `IdempotencyInterceptor` keeps only the header contract: it refuses a
  missing or malformed key (decision 25), buffers and fingerprints the request (decision 24),
  and hands the handler an `IdempotentRequest`.
- **The record stores `result_id` and `result_kind`, plus the status and the two replay
  headers.** There is no body column, so there is no second copy of anybody's words to encrypt,
  clear or forget. `result_kind` is `CHECK (… IN ('ENTRY'))` and is kept as a schema enumeration
  of what the table may point at: a stray kind would make a replay's re-read unroutable, and
  the database is the last place that can refuse it. The cost is one migration per new
  idempotent resource kind. A second `CHECK` pairs the two columns, so a result is named by
  both or by neither.
- **`once` returns `IdempotentOutcome` (P5):** `value`, `resultId`, `resultKind`, `status`,
  `wasReplayed`, and the stored `etag`/`location`. The plan named two incompatible return
  shapes; this is the ruling, with three deviations accepted in review: `ResultKind` is an enum
  rather than a string, the outcome carries the two headers, and `once` takes an
  `IdempotentRequest`. Its constructor is `internal` to `common:web`, so a handler in another
  module cannot build one; inside `common:web` the interceptor is the only code that does.
  The block must return an `IdempotentResult`, whose `resultId` and `kind` are required, so an
  idempotent endpoint that forgets to name what it produced does not compile. The controller sets
  `Idempotency-Replayed: true` from `wasReplayed`.
- **On a replay `value` is null and the caller re-reads the result. A replay is authorised as a
  read, not as the original write** (spec §5.4: "after current authorization and
  deletion/withdrawal checks"). `SubmitEntry.replay` applies exactly the rule `GetToday`
  applies: `membershipOf` must succeed, and neither `hasLeft` nor `isOpen` refuses, because
  `states.md` §9 keeps an ended bond readable to both former members. A member whose bond was
  archived after their first attempt gets the original `201` and their entry, not
  `409 BOND_ARCHIVED`, which would tell the client that a write which succeeded had failed. A
  first draft of this ruling refused a member who had left; that was a third, stricter read
  rule that existed nowhere else, and it was withdrawn. Two guards are the replay's own: the
  entry must be in the bond the path names, and its author must be the caller, so a key answers
  only the member whose request it recorded. What is then rendered is BR-1's answer
  (decision 10), so an entry erased since comes back as its tombstone. There is no stored copy
  to answer from, which is what makes erasure beat replay. An unchanged entry reproduces the
  first response exactly: `SubmitEntry` truncates its clock reading to microseconds once, so a
  `201` rendered from memory and a replay rendered from the row agree to the last digit.
- **Refusals are not recorded and not replayed.** A `4xx` is thrown out of the block and rolls
  the reservation back with the write, so the same key is free for a corrected retry at once.
  The first build stored refusals; the spec speaks only of replaying results, and a refusal has
  no result identity. A client that retries a refused request gets the current answer.
- **`request_hash` is an HMAC, not a hash (P8).** Task 6 removed the stored body and left
  `request_hash` what it had been since the first build, a plain SHA-256 of the request body.
  For `POST /entries` the body is the entry, and a short one ("thank you") is recoverable from
  that column by hashing guesses for the row's 24 hours: the hazard the task had just retired,
  still open through the hash. `request_hash` is now
  HMAC-SHA256 under the server's personal-data secret, over method, path and body, each framed
  by its length. `common:security` already depends on `common:web`, so the dependency cannot run
  the other way: `common:web` declares a port, `RequestFingerprint`, and `common:security`
  implements it (`HmacRequestFingerprint`, on `PersonalDataHasher`). There is no unkeyed
  fallback; a context without the bean fails at startup. The cost: a retry matches only while
  the secret is unchanged, so a retry across a restart on an ephemeral development secret is
  `422`, and rotating the secret refuses at most 24 hours of in-flight keys.
- **The key is `(user_id, key)`; method, concrete path and fingerprint are compared data.** A
  mismatch on any of them is `422 IDEMPOTENCY_KEY_REUSED`. The path is the raw request URI, not
  a route template (two bonds are two targets) and not a canonicalised form: a retry is
  byte-identical to what it retries, so two spellings only come from two requests, and a
  mismatch fails toward `422`. The query string is not bound; no idempotent endpoint takes one.
  The advisory lock keys on a 32-bit `hashtext`, so two different keys can collide; the only
  effect is a spurious `409`, accepted.
- **Postgres, not Redis, and 24 hours enforced at read**, both unchanged from the first build:
  doc 05 §3 keeps idempotency on Postgres so Redis stays removable, and the lookup filters on
  `expires_at`. An expired row is deleted under the same advisory lock before the key is
  reserved again. The scheduled reaper is still C3's.

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
the same shape of gap ADR-0029 §4 already found in `BondStore.update`'s own flush. The rework
met it a third time, on the bond's own row (decision 17).

**10. BR-1 keys on the entry's own `revealedAt`, never on the day's status, and an entry leaves
the service layer only as the gate's answer.** The reveal itself is still absent from C1, by
design: nothing in this slice sets `revealed_at`. The first version of this decision wrote the
gate as `memberId == authorMemberId || day.status == REVEALED || (day.status == SOLO &&
day.isClosed)`. The revised §4 rejects that: a solo day that reveals and is later frozen must
not hide words the partner has already read, and a `FROZEN`, `REVEALED` or `SOLO` status must
not reveal an entry whose own timestamp was never set. As built:

- **`Entry.canBeReadBy(reader)` takes no `BondDay`** and answers one of five things, in the
  spec's order: `NOT_A_MEMBER` when the reader's membership is of another bond, checked first; a
  tombstone when the entry is erased, for everyone, its author included; `FULL` when the reader
  wrote it or `revealedAt != null`; otherwise `LOCKED`.
- **The reader is a `Reader(memberId, bondId)`, and only `BondMembership.asReader()` builds
  one.** The brief's signature took a bare member id and three answers, which cannot express
  "membership first". `gratitude.domain` imports nothing from `bond`, so the type cannot
  restrict its own construction; an architecture test pins the one construction site, and
  `Reader` is a plain class rather than a data class so there is no generated `copy` for that
  test to miss.
- **`EntryReading` is the only form an entry is rendered from.** Its constructor is private and
  `Entry.readBy` is the one way to get one, so holding it is the evidence that the gate ran.
  It exposes the entry's id, timestamps and words only for `FULL` and for a tombstone the reader
  could once read; for a locked or never-seen entry there is no accessor that returns them.
  `TodayView` and `EntryView` carry readings, and `EntryResponse`'s constructor is private and
  accepts only a reading. `GetToday` asks the gate for both entries, the caller's own included,
  and the idempotent replay asks the same gate.
- **Either mark of an erasure is one.** `deletedAt != null` or `status == DELETED`: an erasure
  that has set one and not the other is already an erasure. Wider is safer, and it matches what
  the replay path already rendered. `Entry`'s constructor admits a missing text on either mark,
  so a half-erased row loads and renders as a tombstone rather than failing in the mapper.
- **Two tombstones, because BR-8 says the response shape is the security control.** A reader
  who could read the entry before it was erased (its author, or a partner it had been revealed
  to) gets the wide one: `EntryResponse` with `text: null` and `status: DELETED`. They already
  know when it was written; only the words go. A partner it was never revealed to was only ever
  entitled to `{authorMemberId, status}`, and a withdrawal must not hand them the id and
  timestamps the lock was hiding, so they get `ErasedEntryResponse`: the author and
  `status: REMOVED`, nothing else. This is unreachable until a `DELETE` exists. It was built
  now because the gate was being built now, and the shape is cheapest to set before any client
  reads it.
- **`REMOVED`, not `DELETED`, for the narrow one.** The ruling that created the narrow tombstone
  was about its fields; its first literal was `DELETED`, which is also the wide tombstone's, and
  a discriminator maps one value to one schema, so the discriminator had to come out. That cost
  every generated client an ad-hoc field-presence check (doc 06 §2 builds the client's sealed
  types from discriminators). `REMOVED` is a view literal, as `LOCKED` is. It tells the reader
  nothing they lacked, and it does not distinguish a delete from a withdrawal.
- **`EntryResponse.text` is nullable and required.** Nullable for the tombstone; required so a
  client can tell `"text": null` from a field that was never sent.

`RevealGateTest` asserts the whole rule, not the part C1 can reach: the states C2, C3 and the
erasure slice will produce are put in place by hand, with `UPDATE`, and each test says so. Task
8 ran 22 mutations; 18 were killed and the review confirmed the other four redundant, kept as
defence in depth.

**11. `TodayResponse.partnerEntry` is a `oneOf` of three object branches and `null`,
discriminated on `status`.** `PartnerEntryResponse` is a Kotlin `sealed interface`, and this
springdoc version reads its `permittedSubclasses` and produces the `oneOf` unaided. What it
does not add is a `discriminator`, so a generated client would have to try each shape
structurally. `OpenApiConfiguration.discriminatePartnerEntry` adds one, mapped explicitly for
every value any branch can send: `SUBMITTED`, `REVEALED` and `DELETED` to `EntryResponse`,
`LOCKED` to `LockedEntryResponse`, `REMOVED` to `ErasedEntryResponse`. An unmapped value would
fall back to naming a schema directly, and none of these names one.
`requireDiscriminatorProperty` marks every field of every branch `required`, the discriminator
among them. The field is `PartnerEntryResponse?`, `null` until the partner writes, and springdoc
gives a nullable sealed interface no null branch (it gives `myEntry`, a nullable `$ref`, one);
`admitAbsentPartnerEntry` adds `{"type": "null"}` so the contract admits the first response of
every day. All of it is asserted in `OpenApiContractTest`, including that the three branches'
`status` enums are disjoint.

**12. A westward zone change merges the day it lands in into one long day, and no label is used
twice (R3).** Doc 04 §8.5 covers only the eastward case. Moving west, the handoff instant lands
on a label the bond has already used, and opening it again would violate `bond_days`'s unique
`(bond_id, date)`. `handoffFor` pushes the handoff to the next new-zone midnight until the label
is unused and later than the current one. The day that spans the handoff keeps the label it
opened with and runs on to the successor's `effective_from`. The couple gets one fewer
opportunity to write; no day is missed, so BR-4's run is not broken and no compensating `FROZEN`
day is created. Four things the build found that the plan did not say:

- **The merge is `dayBoundsAt`'s job, not `handoffFor`'s.** `handoffFor` only chooses where the
  boundary falls. The first implementation left `dayBoundsAt` reporting the old zone's next
  natural date for instants late in the merged stretch, which is the same label the successor
  interval issues: a duplicate `(bond_id, date)`. The rule is that an interval never issues a
  label on or after its successor's `first_label`.
- **`dateAt(at)` is `dayBoundsAt(at).date`, by construction.** It was a separate calculation
  that ignored the merge, and `usedLabelsUpTo` inherited the error. Two calculations that agree
  by coincidence were replaced by one.
- **Any westward move merges the current day, not only one across the date line.** A change of
  one hour west makes a 25-hour day. Kiritimati to Pago Pago makes one of 49 hours. The plan's
  "up to ~26 hours" was wrong.
- **Rejected: labelling the new interval's first day `lastOldLabel + 1`.** It avoids the long
  day and leaves every later label one day ahead of the wall calendar for good; the archive
  would show "16 September" for a day the couple lived as the 15th.

**13. A day's stored span may be extended, later only, while the day is unsettled (P10).**
`TimezoneMatrixTest` found the defect: `BondDayStore.openOrGet` is
`INSERT … ON CONFLICT DO NOTHING`, so a row opened *before* a westward change kept the
`ends_at` it was opened with. Kiritimati to Pago Pago, agreed after an entry on the 16th: the
timeline's 16th became `[09-15T10:00Z, 09-17T11:00Z)`, the row kept `ends_at` 09-16T10:00Z, and
the 17th's row started at 09-17T11:00Z. That is a 25-hour hole between two stored days, and an
entry filed on a row whose span did not contain it, against §3.1's "intervals remain contiguous
and non-overlapping". The rule: when a write touches a day under its lock, the row's span is
brought up to what the calendar now says, before the entry is inserted
(`SubmitEntry.openAndLock`, `BondDay.extendedTo`). `ends_at` moves later or not at all;
`starts_at` and `anchor_timezone` never move; a settled day is returned untouched;
`BondDay.applyTo` restates extend-only at the last point before the row is written. BR-6's
"never recomputed" still holds for the zone snapshot, for `starts_at`, and for every settled
day. The extension is the one change §3.1 itself sanctions ("extend the handoff to the next
unused date boundary"), and it only ever makes the row agree with a decision the timeline has
already made. Because any westward move merges the current day (decision 12), this is the
ordinary westward case, not a date-line curiosity. **The accepted limit:** `GET /today` is a
pure read and extends nothing, so a row opened before a change and never written to again keeps
its shorter `ends_at`. Until C3 reconciles it, the timeline, not the column, says when such a
day ends. Nothing reads the column before C3. A window whose `starts_at` or label disagrees with
the row is refused with a `require`; neither can fire for a real user in C1, because the
timeline is append-only, handoffs are deferred to the end of the current day or later, changes
are at least 30 days apart, both sides are truncated to microseconds — **and no row is ever
opened for a day that has not begun (decision 22)**. That last clause was missing, and
without it this sentence was false: the reviewer who passed this decision accepted "rows
open only for now or the past", and the five-minute skew tolerance opened one ahead of now.

**14. An eastward change can skip a label, and C1 writes no row for it (P9).** Pago Pago to
Kiritimati, agreed mid-day: the handoff instant is already on the 17th in the new zone, so the
16th never happens for this bond. Doc 04 §8.5 makes a skipped label `FROZEN`, without consuming
a freeze, so the run is not broken. C1 does not write that row. No instant belongs to a skipped
label, so no entry can open it; `bond` cannot write `bond_days`; and nothing in C1 is a writer
that walks elapsed days. C3's close job is, and it settles the label (Owed). `handoffFor`
computes `skippedLabels` (a label already used is not skipped; the plan's version counted it,
which would have had the closer open a `FROZEN` day over a row that exists) and
`ChangeTimezone` logs only their count; they are not stored, so C3 derives them from the
timeline: the labels between the last one an interval issues and its
successor's `first_label`. The matrix test asserts what C1 does guarantee: no row for the
skipped label, the neighbouring stored spans contiguous in UTC across the gap, and an
`intendedAt` aimed at the skipped date landing on a neighbour.

**15. A `PENDING_MEMBER` bond's zone change defers through the timeline too.** While a bond has
only its creator there is nobody to ask, so `ChangeTimezone.propose` applies the change at once
(spec §11 decision 6). The first rework pass wrote `bonds.anchor_timezone` there and no
interval, and the review proposed relabelling the seed interval in place on the premise that a
pending bond has no logical days yet. That premise is false: decision 4 means a pending bond
does have `SUSPENDED` days, with the creator's entries on them, and rewriting the seed would
relabel them. One mechanism for every zone change: the early-apply path schedules the same
deferred handoff `confirm` does. The cost is that a creator's onboarding fix decides dates from
the next day boundary, not immediately.

**16. The timeline crosses the module boundary as a type `gratitude` can hold and cannot build,
and `gratitude`'s domain asks it through an interface of its own (P3, P7).** The plan sketched
`BondAnchorTimeline` as an `Any` delegate plus lambdas. The ruling (P3) was a thin public class
in `bond.api` holding the domain `AnchorTimeline`. That cannot compile: the "layers only depend
inwards" Konsist rule forbids `bond.api` importing `bond.domain`, verified with a probe. As
built, `BondAnchorTimeline` has an `internal` constructor and holds `beginsAt` and four typed
closures (`zoneIdAt`, `dateAt`, `dayBoundsAt`, `usedLabelsUpTo`) that `BondAccessAdapter`, in
`bond.service`, closes over the real timeline. What P3 was protecting holds: one implementation
of the date arithmetic, an unforgeable type, no `Any`. On the other side (P7),
`BondAnchorTimeline` and `BondDayBounds` have `internal` constructors, so `gratitude`'s unit
tests cannot construct either. `gratitude.domain` therefore owns a `fun interface BondCalendar`
returning a `DayWindow`, and the service layer adapts the bond's timeline to it in one line
(`asCalendar`). `dayAt` returns `null` for an instant before `beginsAt`, the bond's creation: an
`intendedAt` from before the bond existed is a rejected claim that falls back to the submission
instant, as BR-3a says an untrustworthy claim does, rather than a `500`.

**17. `lockMembershipOf` guards, then locks, then re-reads, and the re-read has to be forced.**
A write takes the bond's row lock inside its own transaction and before it reads anything of
the bond (§2.1), so a leave, block, deletion or zone confirmation either commits first or waits.
The method is `MANDATORY`: called without a transaction it would take a lock that is released
on return, which looks like working and protects nothing.

- **Guard first.** The amendment that briefed this said to lock before any read, on the claim
  that `ChangeTimezone`, `EndBond` and `RequestDeletion` do. They do not: they run the guard and
  then lock. Matching them keeps one discipline across bond writes and stops a non-member taking
  `FOR UPDATE` on somebody else's bond. The guard is a read of rows the lock then re-validates.
- **The re-read was answered from memory.** The guard loads the bond and its members into the
  persistence context, and Hibernate answers the post-lock read from that identity map with the
  same instances, old state and all. An entry was committed onto a bond that had been archived
  while the submission waited for the lock. `BondStore.lockBond(refreshReads = true)` refreshes
  those instances from the row now held. It is opt-in, and only `lockMembershipOf` opts in; the
  other eleven `lockBond` call sites, in merged B2 to B5 code, are not changed here.
  **Amended 2026-10-04: audited, and none of them needed it.** Each runs in a transaction that
  starts empty, because its controller's guard read is a separate, finished one; this method is
  the only caller that guards and locks in the same transaction. The per-caller table, the
  tests and the rule that now holds the property are in ADR-0028 §6b, which is where the lock
  rule for bond writes lives. The reason first recorded here for leaving them — that they all
  write the bond row, so `@Version` would catch a stale read — was wrong for eight of them.
- **The full order on a submission is key, bond, bond-day, entry.** The idempotency key's lock is
  only ever tried, never waited for, so it cannot close a wait cycle; it goes first so a second
  request under the same key is refused at once and does not queue behind the first one's bond
  lock.
- **A read never takes the bond lock.** `GetToday` and the replay use `membershipOf`. A test
  holds the bond's row and shows `today` still answers.
  *Amended 2026-10-05 (ADR-0032 decision 6):* with one exception since C2. While a bond's
  joining day is still `SUSPENDED`, the first read that meets it reconciles it, under the
  bond lock and in a transaction of its own, before the read itself begins. Once per bond.

**18. The close job takes no bond lock, so `bond_days`'s unique index stays load-bearing (R1,
ruled by Daniel).** C3's sweep handles many bonds per run and would serialise behind each bond's
row. **This contradicts spec §2.1 as written.** §2.1 says the lock order is "bond, then
bond-day, then entry on submission, editing, closing and lifecycle reconciliation", and
`BondAccess.lockMembershipOf`'s KDoc repeats that sentence. Under R1 the closer starts at the
bond-day and never holds the bond. R1 is the later ruling and it stands. Both texts were
amended on 2026-10-03, with the final review: §2.1 now says the closer is the exception, and
so does the KDoc. The consequence lands in C1. With every
submitter queued on the bond lock, two first entries no longer race for the day's row, and the
test named for that race passed with the day lock deleted. A live submission and the sweep can
still collide, so that is the race now tested: a lock-free opener holds a new day's row
uncommitted, the submission waits on the unique index and nothing else, and both end on one row.
Removing the index turns that test red; removing the bond lock does not, which is the evidence
for which mechanism it proves. The day lock stays for the same reason.

**Amended 2026-10-05 by ADR-0033:** decision 18's no-bond-lock rule is superseded for
closing an existing day. `CloseDay` takes the bond row before the day row, matching writer
order; the PR #54 race test proves pairing cannot be backdated past a close while waiting on
the bond lock. `CreateMissingDays` remains an insert-only path and relies on the unique
`(bond_id, date)` index to arbitrate a concurrent opener.

**19. `BondMembership` keeps `hasLeft`, and `activeSince` is the second-earliest `joinedAt`
(R2).** §2.1's field list omits `hasLeft`. `SubmitEntry` checks it explicitly, because the
second review of PR #41 found `RequestDeletion.cancel` assuming `isOpen` covered it, and the
spec revision predates that review. The list is treated as additive: `hasLeft` and
`awaitingSecondMember` stay, `activeSince`, `endedAt` and `anchorTimeline` are added, nothing is
renamed. `activeSince` is when the bond became active, for C3 to generate missing days from. It
is derived from the member rows, never from the bond's status: `status != PENDING_MEMBER` is not
"a second member joined", because a creator who leaves or requests deletion before anyone
accepts archives a one-member bond, and a status check would report the creator's own join as
the activation.

**20. BR-3a is asked twice, and the second time is under the day's lock.**
`DayAssignment.resolve` checks whether the claimed day is settled before any lock on it is held,
and close takes bond then day (ADR-0033), so settlement can land between this preliminary
read and the writer acquiring the bond lock.
`SubmitEntry` asks again after `lockAndFind`. If the day is settled then, an entry placed there
by its `intendedAt` is redirected once to the day containing the submission instant, so the
words are kept on a day that can still hold them; anything else is `409 DAY_CLOSED`, and so is a
redirect whose own target is settled. It does not recurse. "Settled" has one definition,
`BondDay.isSettled`: `closedAt != null`, or a closed status. `Resolution.usedIntendedAt` (P4)
says whether the client's claim was the instant used, which is what tells a back-fill from a
live write. The redirect takes a second bond-day lock while holding the first. That is safe
among submitters, who are serialised on the bond lock, and it puts an obligation on the closer
(Owed).

**21. No task ended with a red build (P2).** The plan had Task 4 break
`SubmitEntryConcurrencyTest` for Task 5 to rewrite, and Task 6 change a record's shape for Task
7 to consume. Each carried the smallest edit that kept the build green instead. Task 4's edit
left that test passing for the wrong reason, on purpose and written down, and Task 5's first
step was the mutation that showed it.

**22. A claim ahead of the server's clock is accepted and resolved at the submission instant
(P11).** Spec §6.1 let an `intendedAt` up to five minutes in the future be the candidate, to
absorb clock drift. Three tasks were each right under their own assumption and wrong
together. Task 1's `usedLabelsUpTo(now)` assumed no label beyond today's is in use. The skew
tolerance let a phone reading 00:01 at 23:57 open **tomorrow's** row, three minutes early.
Decision 13's extension assumed a row's `starts_at` always equals the timeline's. Confirm a
westward change in those three minutes and tomorrow's start moves (decision 12): the stored
row now disagrees with the timeline, and `BondDay.extendedTo` refuses every later
`POST /entries` for that day — a `500` for both members, all day. Reproduced in
`TimezoneMatrixTest` before the fix. The ruling keeps the tolerance for what it was for: a
slightly fast phone is not refused. But when `intendedAt` is after the submission instant,
the day and the stored instant come from the submission instant, and `usedIntendedAt` is
false. Both sides of five minutes now get the same answer, so `DayAssignment` draws no line
there at all. What this buys is an invariant: **no `bond_days` row exists for a day that has
not begun.** It also makes the BR-3a redirect's two day locks strictly older then newer
(decision 20). The cost: an entry sent at 23:57 from a phone that reads 00:01 is filed on
today, which is where the server says it was written. The client's `intendedAt` is also
truncated to microseconds, as the server's clock already was: a fresh `201` and its replay
must not differ, and pgjdbc *rounds*, so the last half-microsecond of a day would have been
stored as the first instant of the next.

**23. An entry's text is stored exactly as sent; NFKC and trimming only decide (P12).**
Spec §3.2 and doc 04 §7 say "NFKC-normalised before counting … stored raw and unmodified".
`EntryText.of` stored `NFKC(raw).trim()`, and its KDoc said normalisation "changes nothing a
person wrote to mean". NFKC is a lossy, one-way mapping: `…` becomes `...`, `²` becomes `2`,
`™` becomes `TM`, `ﬁ` becomes `fi`. The couple's words were being rewritten for good. No
test exercised it — deleting the `Normalizer` call left the suite green — and the file was
first-build code that no rework task reopened, which is why ten reviews did not see it. As
built now:

- **Stored: the raw string**, not normalised and not trimmed. Conservative by construction:
  raw text can be normalised later, normalised text can never be restored.
- **Blank** is judged on the NFKC-normalised, trimmed form.
- **The 500 graphemes** are counted on that same form, which is the spec's rule.
- **The 8192 octets** are measured on the stored bytes, because that is what V12's
  `entries_text_octets_check` measures, and the two must agree or an accepted text is a
  `500` at the insert. The NFKC form can be larger than the raw; it is never stored, so
  that does not matter.
- **U+0000 is refused.** Postgres `text` cannot hold it, so such a text passed every check
  and failed at the insert. The slice's bar is "FR-041 input is a `422`, never a `500`".
- **No code-point CHECK.** Spec §3.2 lists a third limit, a code-point bound above any
  reachable grapheme expansion. V12 omits it on purpose: UTF-8 spends at least one octet per
  code point, so the octet cap already implies it.

Costs: entries keep leading and trailing whitespace a client did not strip (a later slice
can trim on read). Rows a development database wrote before this hold the normalised form
and stay that way. **One product question is open, flagged to Daniel and not ruled:**
counting on the NFKC form means an iOS typographic ellipsis counts as three of the 500, so a
client's counter must normalise the same way or a member sees "fits" and gets a `422`. The
spec chose this; a test (`the 500 is counted on the NFKC form`) pins it and is the one to
change if he decides otherwise.

**24. An `@Idempotent` request's body is buffered by the interceptor, bounded, and a body
it cannot take is a `4xx`.** The caching filter used to read every `POST`/`PUT`/`PATCH` body
eagerly, on every path, and — to bound that — declined any request without a
`Content-Length`, over 1 MiB, or multipart. `IdempotencyInterceptor` then treated an
unwrapped request as a wiring bug and threw: a `500`. The reviewers reasoned this from
reading; all four triggers were reproduced with a failing test before anything changed (no
body, chunked transfer against a real Tomcat, over 1 MiB, multipart). Chunked is not exotic:
it is what a streaming HTTP client sends, and the KMP client's engines do. As built:

- **The filter reads nothing.** It wraps the request and marks it seen. The interceptor
  buffers the body itself: only once handler mapping says the route is `@Idempotent`, only
  after the caller is known, and never past 1 MiB.
- **No declared length works.** The body is read to the bound and one byte past it, which is
  how "too long" is known. Preferred over answering `411`, because a legitimate chunked
  `POST` should succeed, and the read is bounded whatever the client sends.
- **Over 1 MiB is `413`**, declared or discovered, with code `MALFORMED_REQUEST` — the code
  every other unreadable body carries. No new `ErrorCode`.
- **Multipart is Spring's own `415`.** Nothing is prepared, and `@RequestBody` has no
  converter for it.
- **A filter that never ran is still a loud failure**: the mark is how "declined" is told
  from "not registered".

A side effect worth having: a route that is not `@Idempotent` is no longer buffered at all,
so the unauthenticated-body concern the size bound was added for is gone rather than capped.
The contract documents `413` and `415` on `submitEntry`.

**25. An `Idempotency-Key` is 1 to 255 visible ASCII characters, and its `422` carries
`errors`.** The key was an unbounded `text` column, stored for 24 hours per request. It is
now bounded at the edge (`422 VALIDATION_FAILED`, with an `errors` entry naming
`Idempotency-Key`), in V11 (`idempotency_keys_key_check`) and in the contract
(`maxLength`, `pattern`). Fixed now because V11 is editable only while unmerged; afterwards
the same bound costs a migration. A missing key is the same `422` and now carries its
`errors` entry too: `ErrorCode` says `VALIDATION_FAILED` is always accompanied by one, and
this was the one place it was not. `ApiException` gained an optional `errors` list to carry
it. The cost: a client sending an exotic key gets a `422`; a UUID or a ULID is well inside.

**26. An unpaired UTF-16 surrogate in an entry's text is refused (added 2026-10-04, the C1
follow-up).** Decision 23 says the text is stored exactly as sent. A surrogate without its
partner (JSON `"\ud800"`) cannot be: it is not a character and has no UTF-8 form. Nobody
had run it. Run, it was not a `500`: the request was a `201` whose body echoed the surrogate
from memory, while the driver had written `?` (0x3F) in its place. The response and the row
disagreed, and a replay or `GET /today` would have returned the `?`. `EntryText.of` now
refuses it beside the NUL check, a `422` on `text` like the other limits. A well-formed
*pair* is one code point (any emoji outside the BMP) and is untouched; a test stores one
sent as JSON escapes and reads back its four UTF-8 bytes. No migration: a row already
written this way holds `?`, which is valid text and reads back as it is stored.

**27. A body that stops arriving on an `@Idempotent` route is a `400`, logged as a client
error (added 2026-10-04, the C1 follow-up).** Decision 24 moved the read of the body into
`IdempotencyInterceptor.preHandle`, so a client that disconnects or stalls part-way now
raises its `IOException` inside MVC. Reasoned from reading to be a `500`; run, it was half
that. Under MockMvc it was a `500 INTERNAL_ERROR`. Against a real Tomcat the exception
(`ClientAbortException`) reached `handleUnexpected` and was logged at `ERROR` with a stack
trace, but the status on the wire was already `400`: Tomcat marks the response itself when
the read fails and answers through its own error dispatch. `ReplayableHttpServletRequest`
now catches the `IOException` where it reads and throws `RequestBodyUnreadableException`
(`400 MALFORMED_REQUEST`, the code and sentence an unparseable body gets), which is logged
at `WARN` with no stack trace. Caught at the read rather than by a handler for
`IOException`, which would also have swallowed the server's own I/O faults. **Not changed:**
on a real server the body of that `400` is Boot's default error document, not this
application's problem details, because Tomcat has already put the response in its error
state. A client that aborted is no longer there to read it. A client that only stalled (a
socket timeout) is still connected and does receive that document, so this is a real hole
in the error contract, recorded under Owed.

## Consequences

- **Five new `ErrorCode` values, so this is a breaking change**: `ENTRY_ALREADY_EXISTS`,
  `DAY_CLOSED`, `MEDIA_NOT_YET_SUPPORTED`, `IDEMPOTENCY_KEY_IN_FLIGHT`, and
  `IDEMPOTENCY_KEY_REUSED` — checked against `git diff c12f91b HEAD --
  common/web/.../ErrorCode.kt` (`c12f91b` is this branch's merge-base with `main`; an
  earlier version of this line said `cdd7ac8`, which is where a stale local `main` pointed)
  rather than assumed; an earlier count in this slice's own planning said four and missed
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
- **`idempotency_keys` holds no copy of anybody's words.** The first build's `response_body`
  column, and the Phase 5 liability this ADR flagged with it, are gone. What replaced it costs a
  re-read on every replay, and a replay can differ from the first response when the entry has
  changed since. That difference is the point.
- **The row lock plus `EntityManager.refresh` pattern (decision 9) is now the third instance of
  "the lock is real and does nothing without a flush/refresh"** — ADR-0029 §4 found the write
  side of this in `BondStore.update`; C1 found the read-back side of the same fact in
  `BondDayStore.lockAndFind`, and the rework found it again in `lockMembershipOf` (decision 17).
  Any future store that locks a row before a read-modify-write needs to ask this question
  explicitly, because neither Hibernate nor the type system will ask it for you.
- **Every submission takes the bond's row lock.** Two members writing at the same moment are
  serialised, and a submission queues behind a leave, a block, a deletion request or a zone
  confirmation on the same bond. `membershipOf` and `lockMembershipOf` both load the bond's
  timeline, one extra query on every `GET /today` and `POST /entries`. It is not an N+1 today;
  it becomes one if a list endpoint ever calls it per bond.
- **A zone change costs the couple something, and which thing depends on the direction.**
  Westward: one long day (49 hours at the date line), and so one fewer chance to write.
  Eastward: a calendar label that never happens, which has no row until C3 writes it.
- **A stored `ends_at` can be stale.** A row opened before a westward change and never written
  to again keeps its shorter span (decision 13). An extending write issues two `UPDATE`s and
  bumps the row's version twice; no `ETag` is exposed on a Bond-day, so nothing observes it.
- **A row whose `starts_at` disagrees with the timeline makes every later `POST /entries` for
  that day a `500`.** `BondDay.extendedTo` refuses it rather than file an entry on a day whose
  start has moved. C1 cannot produce such a row. A later writer that opens days by any other
  route can (Owed).
- **The contract changed inside the unmerged slice.** `EntryResponse.text` is nullable;
  `ErasedEntryResponse` exists; `partnerEntry` is nullable and has three object branches;
  `Idempotency-Replayed` is a declared response header. None of it is breaking against `main`
  beyond the error codes above, because none of C1 has merged.
- **Two more things in V11 and V12 since the rework:** the key's CHECK (decision 25), and a
  plain index `entries_bond_day_idx` on `entries (bond_day_id)`. `GET /today` reads every
  entry of a day, tombstones included, and BR-2's partial unique index
  (`WHERE deleted_at IS NULL`) cannot serve that read.
- **Nothing that holds an entry's words prints them.** `SubmitEntryRequest`, `EntryDraft`
  and `EntryResponse` were data classes carrying the text as a plain `String`; each now
  prints `text=(redacted)`. One `INFO` line — bond id, a reason and a date only — records a
  BR-3a redirect, and a BR-3a fallback whose claim was too old, predates the bond or names a
  settled day, so a member's "why is my entry on that day" is answerable. *(Amended
  2026-10-04.)* A fallback whose claim was only **ahead of the server's clock** is `DEBUG`:
  that entry lands on today, where its author expects it, and a client whose clock runs fast
  would write the line on every submission. `DayAssignment.resolve` reports which of the
  four it was (`Resolution.claim`); `SubmitEntry` chooses the level from that and does not
  restate the rule.
- **V11 and V12 were edited in place, more than once, and V13 is new.** Any database that
  applied an earlier copy of them refuses to start on a checksum mismatch, and resetting the
  checksums does not fix it: the tables themselves have the earlier shape. The shared
  development database is one such. Its repair drops and re-applies the three migrations, is
  the human's to run, and is under Owed.
- **`GET /bonds/{bondId}/today` reports `OPEN` for an archived bond's no-row day** (whole-branch
  review; `GetToday.today`'s own no-row branch derives the reported status from
  `membership.awaitingSecondMember` alone — never from whether the bond itself has ended). Ruled a
  recorded gap, not a fix for this slice: there is no day status today that means "the bond ended"
  — inventing one now risks contradicting a later slice's own state machine for it, the same
  reasoning decision 4's amendment above gives for not inventing the un-suspend transition here.
  The write path already refuses correctly (`SubmitEntry` answers `409 BOND_ARCHIVED` regardless
  of what `GET /today` last reported), so the gap is cosmetic, not a security or data hole — a
  caller reading `OPEN` on an archived bond cannot act on it successfully. Whichever slice gives
  a bond's end a day-status meaning should close this alongside it.

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
- **Reading a Bond-day's zone from the bond's current row on every read**, rather than storing
  anything on the day. Rejected: the bond's row holds the zone it *requests*, which can change
  tomorrow, so the answer to "which zone did this day begin under" would change with it. The
  stored copy keeps that fact on the row, for display and audit. It is not what stops a day
  being redated: the persisted `starts_at`/`ends_at` are (decision 3).
- **The copied zone string alone, as the whole deferral mechanism.** Built, defended by the
  first version of decision 3, and replaced: it cannot defer a change on a day that has no row.
- **Reading `bond_days` from `bond` to learn which labels are used.** Rejected (P6): a
  cross-module read. `first_label` on the interval answers it inside `bond`.
- **Relabelling after a westward change** (`lastOldLabel + 1`) instead of one long day. Rejected
  (decision 12): every later label is a day ahead of the wall calendar for good.
- **Rewriting the seed interval for a `PENDING_MEMBER` bond's zone change.** Rejected (decision
  15): the bond already has `SUSPENDED` days with entries on them.
- **Writing the `FROZEN` row for a skipped label in C1**, on the first open after a handoff.
  Rejected (decision 14) in favour of C3, which walks elapsed days anyway. If that is wrong, the
  cost is one insert added to C1 before merge.
- **Recomputing a row's span on every read, or never touching it.** The first redates settled
  days; the second leaves a hole in the stored calendar. Decision 13 is the narrow middle.
- **`BondAnchorTimeline` holding the domain object, or an `Any` and lambdas.** The first cannot
  compile under the layer rule; the second is untestable and unreadable (decision 16).
- **Locking the bond before the guard.** Rejected (decision 17): it is not what the existing
  services do, and it lets a non-member lock a bond that is not theirs.
- **Reserving the key in the interceptor, and storing the response body.** Both built, both
  replaced (decision 8).
- **A plain SHA-256 for `request_hash`.** In the first build, kept through Task 6, replaced in
  Task 7 (P8).
- **Replaying as the original write**, so an archived bond answers a retry `409`. Rejected
  (decision 8): it reports a write that succeeded as one that failed.
- **Recording refusals under the key.** The first build did. Rejected: it pins a client to an
  answer that a corrected request should be free of.
- **One tombstone shape for every reader, and `DELETED` as the narrow tombstone's literal.**
  Rejected (decision 10): the first discloses to a partner what BR-8 withheld, and the second
  costs the discriminator.
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
  never produce anything else. Rejected (decision 10): C2's reveal and C3's solo-day unlock
  both work by setting `revealed_at`, and each would have had to discover that the response
  ignores it. Asking `Entry.canBeReadBy` the real question now means setting the timestamp is
  all either slice has to do, and it is what lets `RevealGateTest`'s mutation proof mean
  something.

## Owed

What this slice knowingly leaves for a later one. Each is an obligation, with the slice it falls
on. None of them is built here.

**C2, the reveal.** *All six discharged on 2026-10-05; ADR-0032 is the record. In order:
the un-suspend is its decisions 6 and 7 (and it found that a day from before the pairing with
no row opened `OPEN`); the bucket is decision 11; the redacted rethrow is decision 13;
`DELETE` setting both marks is decision 8; `GetToday`'s deterministic choice is decision 14;
`revealed_at` in the reveal's own transaction is decisions 1 and 2. The bullets are kept as
written, for what they asked.*

- **Un-suspend a paired bond's day** (decision 4's amendment), by spec §12.4's actual rule:
  on the joining day, the first gratitude operation or close sweep reconciles the
  `SUSPENDED` row under the bond and day locks — **zero entries becomes `OPEN`, one becomes
  `PARTIAL`**, two follow the reveal rule — using `activeSince` to tell the joining day from
  earlier suspended days, which stay private. **This includes joining-day rows that elapse
  before C2 ships**: they are already `SUSPENDED` with entries on them, and a reconcile that
  only looks at today would leave them locked for good. It lives in `gratitude`; `bond`
  cannot write `bond_days`, so it cannot happen "when the membership is created".
- **The `entries:create` per-user bucket** (spec §5.5). Not built in C1: both routes sit
  under the global authenticated bucket (120/min), and BR-2 caps real writes at one per
  member per day, so the bucket would bound only refused attempts. If that is wrong, a
  member can make 120 refused submissions a minute until C2.
- **Rethrow a non-BR-2 constraint violation carrying only the constraint's name.** C2 adds
  the first `UPDATE` of `entries`. Postgres reports a CHECK violation on an update as
  "Failing row contains (…)", with the row's text in it; `SubmitEntry`'s catch rethrows
  anything that is not BR-2 unchanged, and `handleUnexpected` logs it at `ERROR` with its
  message. From C2 on that path can put an entry's words in a log.
- **`DELETE /entries/{id}` must set both `status = DELETED` and `deleted_at`.** The BR-2 index is
  partial on `deleted_at IS NULL`; a status-only erasure still occupies the slot and its author
  can never write that day again.
- **`GetToday` must pick the live row deterministically.** Once withdraw-then-rewrite is
  possible, one author has two rows on a day, and `GetToday` takes `firstOrNull` over an
  unordered list. The ledger records this against "the erasure slice (C5)"; spec §10 gives the
  first `DELETE` to C2, which reaches the state first. Whichever slice first sets `deleted_at`
  owns it.
- **Set `entries.revealed_at` in the reveal's own transaction**, and never clear it. BR-1 reads
  nothing else.

**C3, the close job.** *All discharged on 2026-10-05; ADR-0033 is the record. Close locks
bond then day (amendment to decision 18); decision 5 documents the clock margin. The closer-facing accessor: decision 14.
Every window from the timeline, and a stale `ends_at` reconciled first: decisions 3 and 4.
A skipped label `FROZEN`: decision 6. One day at a time: decision 3. Missing days from
`activeSince`: decision 6. The reaper: decision 13. The two added on 2026-10-05 — the sweep
out of `PENDING_REVEAL`, and a joining day reconciled before it is closed — are decisions 3
and 7. The bullets are kept as written, for what they asked.*

- **Take the bond lock before the day lock when closing an existing day** (ADR-0033
  amendment). The unique `(bond_id, date)` index remains necessary for `CreateMissingDays`,
  which inserts absent rows without first locking each bond.
- **Add a closer-facing accessor to `bond.api.BondAccess`.** `membershipOf` and
  `lockMembershipOf` both take a `userId` and run the membership guard; the close job has no
  caller. It needs the timeline and the lifecycle instants (`activeSince`, `endedAt`) for a
  bond id alone, read-only. Spec §2.1 is amended to say so. (The lock-order sentence there,
  and `lockMembershipOf`'s KDoc, were amended on 2026-10-03 and are no longer owed.)
- **Take every day's window from the timeline, never from a zone's natural midnight.** A row
  opened with a `starts_at` the timeline disagrees with makes every later `POST /entries` for
  that day a `500` (`BondDay.extendedTo`'s `require`; the same note is on its KDoc). The first
  day of a bond is the easy one to get wrong: it starts at the bond's creation, not at midnight.
- **Reconcile a stale `ends_at` from the timeline before settling a day** (decision 13), with the
  same extend-only rule. Closing on the stored value closes a merged day about 25 hours early,
  and every write in those hours becomes `409 DAY_CLOSED`.
- **Settle a skipped label `FROZEN` without consuming a freeze** (decision 14), deriving the
  skipped labels from the timeline. Nothing stores them.
- *Added 2026-10-05 by ADR-0032 (its own "Owed, C3" has the reasons):* **the second sweep
  out of `PENDING_REVEAL`**, which needs `revealTimeLocal` on the closer-facing accessor
  below; and **reconcile a joining day under its lock before stamping `closedAt` on it**.
  The two-days rule in the next bullet now protects three paths, not only the redirect.
- **Never hold two days of one bond at once, or take them older first.** The BR-3a redirect
  holds the settled day's lock and then today's (decision 20). Since decision 22 a claim is
  never ahead of the submission instant, so those two locks are always older then newer;
  the wrinkle an earlier version of this bullet recorded, a redirect onto the older day, is
  gone.
- **Generate missing days from `activeSince`, not from `created_at`** (decision 19).
- **The reaper for `idempotency_keys`** (ShedLock). Until then an expired row is only removed
  when its key is used again.

**The deploy slice.**

- **A `lock_timeout` outside test configuration.** Only the test `application.yml` files set
  one. In production a stuck bond write parks every submission for that bond with no bound.
  Not a correctness defect; a thing to decide before real traffic.

**C5, withdrawal on block.** *Discharged 2026-10-07, ADR-0035 decision 12. A replay is
built from `Entry.canBeReadBy`, which treats a withdrawn author's entry as erased from the
ending's commit. `WithdrawalReadTest` names it: a replay of the withdrawer's `POST` key and of
their `PATCH` key answers `Idempotency-Replayed: true` with no text, with no dispatcher run
and the rows asserted still whole.*

- **A replay must consult the withdrawal marker from the moment a block commits** (spec §6.7).
  Between the block's commit and the consumer's erasure, a replay must show no text. This
  follows from replaying as a read (decision 8): the read rule must already know about a
  withdrawal that has been decided and not yet applied. C5's test list must name it. Until C5
  there is no withdrawal path, so nothing leaks today.

**Whoever is next to touch them.**

- **The first idempotent endpoint that takes a query string** must decide whether it is part of
  the target, and bind it if so. A new idempotent resource kind adds a `ResultKind` value and a
  migration for the `CHECK`, together.
- **`GET /today` reports `OPEN` for an archived bond's no-row day**, and has no `partner` field
  (both under Consequences).
- **The shared development database must be repaired by hand, and a checksum reset is not
  the repair.** It applied earlier copies of V11, V12 and V13. Setting their checksums to `NULL`
  only stops Flyway refusing to start; it re-runs nothing. V11 and V12 changed table *shape* in
  place (the response body column went and `result_id`/`result_kind` arrived; `bond_days` gained
  `starts_at`/`ends_at`), so the application would start and then fail on columns that are not
  there. The procedure, for the development database only:
  `DROP TABLE IF EXISTS entries, bond_days, idempotency_keys, bond_anchor_intervals CASCADE;`
  `DELETE FROM flyway_schema_history WHERE version IN ('11','12','13');`
  then start the application so Flyway re-applies V11 to V13 (V13 backfills the timeline for
  the bonds that exist). It destroys that database's entries, days and idempotency keys; users,
  bonds and invites are untouched. **It was executed once, on 2026-10-03**, against a
  development database whose history read 1 to 9, 11, 12 (V10 had never been applied there,
  and V13 never had, so the `DELETE` removed two rows). It dropped 4 days, 4 entries and 8
  idempotency keys of smoke data and left 24 users and 26 bonds. On the next start Flyway
  applied V10, V11, V12 and V13 in order. One run on one database is what that proves.
- **`scripts/smoke.sh` was run against the rework on 2026-10-03**, on the jar built from
  `b08b385` (the final review's fixes included), after the repair above: **350 probes passed,
  0 failed**, the gratitude section and the deferred-handoff section among them. **Run again
  on 2026-10-04 for the C1 follow-up**, on the jar built from `0397200`, with eleven probes
  added (the `Idempotency-Key` contract, the media refusal, the author's own `today`,
  `ALREADY_MEMBER`, an unpaired surrogate, typographic text stored as sent): **361 passed,
  0 failed.** Anything committed after `0397200` that is not documentation has not been
  smoke-tested until the script is run again.
- **A read failure on an `@Idempotent` route answers `400` with the framework's default error
  body, not RFC 9457** (decision 27). Tomcat error-dispatches once the read of the body
  fails, so on a real server the response is Boot's default error document. It is the one
  known hole in "every error is problem details". Observed by hand on 2026-10-04, not
  asserted by a test: `IdempotencyRealServerTest` asserts the status only. Not assigned to
  a slice, and not ruled on.
- **Two product questions about `EntryText` are open and not ruled** (decision 23 has the
  first): whether the 500 should be counted on the raw text rather than the NFKC form, and
  whether an entry made only of zero-width characters should be refused. The C1 follow-up
  left both alone on purpose.
- **V13's backfill is covered twice.** `AnchorIntervalBackfillTest` migrates a database to
  just before V13, inserts bonds in every state and in the zones where a date is easiest to
  get wrong, runs V13, and loads each timeline through the application's own loader; the
  backfill was correct for every case. And on 2026-10-03 it ran for real over the 26 bonds in
  the development database (16 `PENDING_MEMBER`, 6 `ARCHIVED`, 4 `ACTIVE`; `Africa/Lagos` and
  `Europe/London`): every bond got one open interval starting at its `created_at`, and no
  `first_label` disagreed with its zone.

## Revisit when

- *(Done 2026-10-05, ADR-0032. `RevealGateTest` keeps its hand-made states on purpose — they
  include states no request can produce — and `RevealTest` drives the real transitions beside it.)*
  C2 adds `PENDING_REVEAL`/`REVEALED` and the outbox — it sets `revealed_at`, `FULL` becomes
  reachable for a partner for the first time, and `RevealGateTest`'s hand-made states are
  replaced by real transitions without narrowing what C1 already asserts.
- *(Done 2026-10-05, ADR-0033.)* C3 adds the close job and `SOLO` — everything under Owed for C3 falls due, and so does the
  reaper that clears `idempotency_keys` on schedule (ShedLock).
- A second idempotent endpoint arrives — `IdempotentOutcome`'s `etag`/`location` get their first
  reader, and `ResultKind` its second value.
- Somebody proposes a second zone change inside one logical day, or drops FR-027's thirty days —
  decision 13's argument that the `require`s cannot fire rests on changes being far apart.
- Daniel answers decision 23's open question — whether the 500 are counted on the NFKC form
  or on the text as typed. One test pins the current answer.
- Phase 4 builds somewhere for `imageMediaId`/`voiceMediaId` to go, and `422
  MEDIA_NOT_YET_SUPPORTED` needs to become an actual write path rather than a refusal.
- Phase 5 encrypts `entries.text` — `idempotency_keys` no longer holds a copy to encrypt
  (decision 8), but `request_hash` is keyed by the personal-data secret, so that work's key
  rotation plan has to count it.
- `bond.api.BondAccess` gains a way to name the other member, and `GET /today`'s `partner` field
  (this ADR's own gap against doc 06 §3.4) can finally be built instead of documented as missing.

## Rework amendment: what the first build got wrong

The decisions above are written as they now stand. This section is the difference, for a reader
who knew the 2026-09-29 version.

| Decision | 2026-09-29 | Now |
|---|---|---|
| 3 | The zone string copied at lazy row creation is what makes a day never recomputed | A bond-owned timeline defers the change; the row stores its span; the string is a snapshot |
| 8 | Reserve before the handler, complete after; store the response body | One transaction; store result identity; keyed fingerprint; replay is a read; refusals not kept |
| 10 | The gate reads the day's status; one tombstone | The gate reads the entry's `revealedAt`; `EntryReading`; two tombstones |
| 11 | `oneOf` of two, discriminated | Three object branches and `null`, discriminated |
| 12–21 | — | New: the westward merge, the span extension, the skipped label, the pending bond's change, the port, the lock, the close job's lock, the membership's fields, the BR-3a recheck, the green build |
| 22–25 | — | New, from the final whole-branch review: a future claim resolves at submission; text stored raw; a bounded body and no `500` for an unbuffered one; a bounded key |

Decisions 1, 2, 4, 5, 6, 7 and 9 are unchanged except for a pointer to the decision that
extends them. The numbers were kept because KDocs across the branch cite them.

**What the whole-branch review found that ten per-task reviews did not** is in decisions 22
and 23, and both have the same shape: nothing was wrong inside any one task. Decision 22 is
three tasks each correct under an assumption another task broke. Decision 23 is a file the
rework never opened, with a KDoc that said the opposite of what the code did.

**Three mechanisms stopped being what they were named for, and each was found by removing it.**
The concurrency test that proved "two first entries produce one row" passed with the day lock
deleted once the bond lock was in front of it (decision 18). The "re-read under the lock" read
nothing (decision 17). And §2.1's field list, followed literally, would have deleted `hasLeft`,
the check the second review of PR #41 had just added (decision 19). `docs/learning-log.md` has
the longer account.

**Where the ledger and the code disagree, the code is what shipped.** The ledger's P3 says
`BondAnchorTimeline` holds the domain object; it holds closures (decision 16, and the ledger's
own later amendment). Its P5 gives `resultKind` as a string; it is an enum. Its first replay
ruling refuses a member who has left; the code, and the refinement that followed, do not. Its
first tombstone ruling gives the narrow shape `DELETED`; the code says `REMOVED`. The plan's R3
says a merged day is "up to ~26 hours"; the code makes one of 49. The ledger counts "~10" other
`lockBond` callers; there are eleven call sites.
