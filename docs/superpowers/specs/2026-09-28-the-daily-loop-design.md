# Phase 3 — the daily loop

**Status:** approved · **Date:** 2026-09-28 · **Author:** Claude, with Daniel
**Covers:** doc 15 §4 Phase 3 — Bond-days, entries, the reveal, the close job, streaks,
the archive, search and prompts. Six slices, C1–C6, ending at **M3**.

Read `docs/superpowers/specs/2026-09-24-bonds-and-invites-design.md` first if you have not:
this document assumes the Bond, its members, the access guard and `Membership` exist.

## 1. Goal and non-goals

**The goal, in one sentence:** two people each write one thing a day, and neither reads the
other's until both have written.

Everything difficult in this phase falls out of that sentence. The entry belongs to a day, the
day belongs to a *Bond's* timezone rather than a writer's, the day closes on its own, and the
run of closed days is a streak two people share. Phases 0–2 built who you are and who you are
paired with; nothing built so far does the thing the product exists for.

**In scope (FR-040 – FR-050, FR-060 – FR-065, FR-070 – FR-076, FR-090 – FR-093):**

- `bond_days` and `entries`, with server-side day assignment (BR-3) and the offline-intent
  rule (BR-3a).
- The reveal: synchronous on the second entry, and at day close for a lone one (FR-063).
- The per-timezone close job, every fifteen minutes, correct across DST and 45-minute offsets.
- Streaks, freezes and Strict mode, with a deterministic `recalculate`.
- The archive feed, per-caller favourites, reactions, full-text search and prompts.
- `Idempotency-Key`, specified in doc 06 §1 since the corpus was written and never built.
- The transactional outbox: written from the first state change, polled from C5.

**Out of scope, and where it goes instead:**

- **Media.** `entries` carries `image_media_id` and `voice_media_id` from V12, and the API
  **refuses a non-null value with `422`** until Phase 4 builds the media aggregate — the same
  shape `PatchBondRequest` uses to refuse `anchorTimezone` (ADR-0029 §8). A field that is
  silently ignored reports success for a change that did not happen; FR-044a's ownership check
  is the reason it cannot simply be stored either.
- **Push notifications.** C2 writes `EntrySubmitted` and `DayRevealed` to the outbox. Nothing
  consumes them until Phase 4. FR-061's "within 60 seconds" is Phase 4's obligation, and the
  event that makes it possible is this phase's.
- **Milestone cards (FR-075).** The milestone *dates* are derivable from the streak state and
  `GET /bonds/{id}/milestones` is in C6; the rendered shareable card is Phase 4.
- **The 100-prompt library.** C6 builds the table, the selection rule and the endpoints, and
  ships with a small seed. The library itself is editorial work doc 15 budgets at ~6 h and is
  Daniel's, not the implementation's.
- **Encryption at rest (D-13).** Phase 5. `entries.text_search` exists in V12 so that the
  Phase 5 migration is additive rather than a rewrite of the search path.

**OQ-02 is decided: no cut.** Doc 15 §6 puts the lean-v1 question at the Phase 2 checkpoint,
which is today. The cut buys six weeks against a February 2027 application date, and the
roadmap it was measured against has Phase 2 ending 13 December 2026 — it ended on 28 September,
about eleven weeks early, so the six weeks are already banked. Three of the five cut items
(search, freezes and Strict mode, reactions) are also the Phase 3 items with the most
engineering in them. Full scope.

## 2. The modules

Four modules gain code; `common:events` is new. `bond` also gains the public access port below.

| Module | What lands here |
|---|---|
| `modules/gratitude` | Bond-days, entries, the reveal, streaks, prompts, search. The phase's centre. |
| `modules/scheduling` | The close job: the fifteen-minute trigger, the ShedLock guard, the timezone selection. Doc 05 §2.1 puts it here and it stays here. |
| `common:events` | **New.** `outbox_events`, the publishing port and the poller. |
| `common:web` | `Idempotency-Key` — doc 05 §2.1 already lists "idempotency filter" here. |

### 2.1 How `gratitude` asks `bond` a question

`gratitude` needs four facts about a Bond on every write: is the caller a member, is the Bond
open, what is its anchor zone, and does it have a `revealTimeLocal`. ADR-0026 made
`bond.service.Membership` a value **only `BondAccessGuard` can construct**, held by two Konsist
rules, and that property is the thing worth preserving across the boundary rather than
re-deriving.

So `bond` grows its second `api` port, `bond.api.BondAccess`:

```kotlin
// in modules/bond, package com.moyi.bond.api
class BondMembership internal constructor(
    val bondId: UUID,
    val memberId: UUID,
    val userId: UUID,
    val anchorTimezone: String,
    val revealTimeLocal: LocalTime?,
    val strictMode: Boolean,
    val isOpen: Boolean,
    val isAwaitingPartner: Boolean,
    val activeSince: Instant?,
    val endedAt: Instant?,
)

interface BondAccess {
    /** The caller's membership, or a NotFoundException — never a 403 (doc 06 §2, T-02). */
    fun membershipOf(userId: UUID, bondId: UUID): BondMembership

    /** Requires an existing transaction; locks the bond and re-reads membership/state. */
    fun lockMembershipOf(userId: UUID, bondId: UUID): BondMembership
}
```

**The `internal` constructor is the whole mechanism.** Kotlin's `internal` is scoped to the
Gradle module, so `gratitude` can hold a `BondMembership`, read it, and pass it down — and
cannot forge one. That is ADR-0026's guarantee carried across a module boundary by the
compiler, with no Konsist rule to write and none to forget. `UserDirectory` did not need this
because a display name is not an authorisation decision; this is.

`gratitude` depends on `modules:bond` with `implementation`, not `api`, exactly as `bond`
depends on `identity`. No foreign key crosses the boundary: `entries.bond_id` and
`entries.author_member_id` are ids, not references.

Writes use `lockMembershipOf` with transaction propagation `MANDATORY` in the same
transaction as the gratitude mutation, and keep the lock until commit. A membership value is
a snapshot, not a durable authorization grant. The lock order is **bond, then bond-day, then
entry** on submission, editing, closing and lifecycle reconciliation. This serializes writes
with leave, block, deletion and timezone confirmation; checking `isOpen` before taking a
separate day lock would allow a write to commit after the bond ended. The port also exposes
activation/end intervals and the effective anchor timeline to the closer through public DTOs,
so gratitude does not query bond's private tables.

### 2.2 Why `scheduling` does not own the transitions

The close job is two different things wearing one name. Deciding *which Bonds have crossed
midnight* is about clocks, locks and a fifteen-minute trigger. Deciding *what a crossing does
to a day* is the domain's rule and belongs beside the rule the synchronous path uses, or the
two will drift — and doc 05 §5.1 is explicit that "the job is the source of truth if they ever
disagree", which is only safe if they cannot.

So `scheduling` calls `gratitude.api.DayCloser`:

```kotlin
interface DayCloser {
    /** Closes every Bond-day in this zone whose date is already past. Idempotent. */
    fun closeElapsedDays(zone: ZoneId): CloseResult
}
```

`scheduling` knows about ShedLock and the clock. `gratitude` knows what `PARTIAL` becomes.

## 3. Domain model

### 3.1 `BondDay` — the aggregate

`BondDay` is the aggregate root for a day, and the row that holds the lock every transition
takes. Immutable Kotlin, `require`d invariants, flat entity and hand mapper — the `Bond`
pattern from ADR-0026, unchanged.

```
id · bondId · date (LocalDate, in the anchor zone) · status
entryCount · revealedAt? · closedAt? · anchorTimezone · startsAt · endsAt · version
```

**`anchorTimezone` is copied onto the row, and that is deliberate.** ADR-0030 and BR-6 require
that an approved timezone change take effect from the *next* Bond-day and that existing rows
are never recomputed. If the day read the zone from the Bond at query time, every historical
day would silently move the moment the anchor changed. Copying it at creation is what makes
"never recomputed" true by construction rather than by everybody remembering, and it is what
`Bond.withAnchorTimezone`'s KDoc already promises a Phase 3 author.

A timezone snapshot alone does not defer a mid-day change. C1 also persists the day's
UTC interval `[startsAt, endsAt)` and a bond-owned effective anchor timeline. Confirmation
records the requested zone immediately, as B5 does today, but day assignment keeps the
previous effective zone until the end of the current logical day, even when no row has yet
been opened. Under the bond lock, resolve that interval before creating any day. At the
handoff, skipped calendar labels become `FROZEN` without consuming a freeze; an already-used
label is not opened a second time (extend the handoff to the next unused date boundary).
Intervals remain contiguous and non-overlapping. Historical/offline instants use the
recorded interval, never the bond's latest requested zone. This requires extending B5's
confirmation path and the public timeline port in C1; copying a string at lazy row creation
is insufficient. Tests cover both forward and backward date-line changes mid-day.

**Eight statuses, not five** (doc 04 §3; see §12.1 — doc 07's DDL lists five and is stale):

| Status | Means | Counts toward the streak |
|---|---|---|
| `OPEN` | No entry yet | — day is not closed |
| `PARTIAL` | One entry. Doc 04 §6.1: the partner may infer this, deliberately | — |
| `PENDING_REVEAL` | Both entries in, waiting for `revealTimeLocal` (FR-062) | — |
| `REVEALED` | Both entries readable | **yes** |
| `SOLO` | Closed with one entry, auto-unlocked (FR-063) | no — breaks, or spends a freeze |
| `EMPTY` | Closed with none | no — breaks, or spends a freeze |
| `SUSPENDED` | Excluded from evaluation (§8.1, §8.2, §8.3a) | neither extends nor breaks |
| `FROZEN` | A freeze was consumed (BR-5) or a date was skipped by a zone change (§8.5) | **yes** |

### 3.2 `Entry`

```
id · bondDayId · bondId · authorMemberId · text · imageMediaId? · voiceMediaId?
voiceDurationMs? · promptId? · status · createdAt · intendedAt · updatedAt
revealedAt? · deletedAt? · authorDeletedAccount · searchVector
```

`status ∈ {SUBMITTED, REVEALED, DELETED}`. There is no server-side `DRAFT` (doc 04 §3): the
server sees an entry for the first time when it is submitted.

**`EntryText` is a value type, and it is the only place graphemes are counted.** FR-041 wants
three independent limits and names the trap: a 4,000-code-point backstop is *not* looser than
500 graphemes, because one ZWJ family emoji is ten code points, so 500 legitimately typed
characters can exceed it.

```kotlin
@JvmInline
value class EntryText private constructor(val value: String) {
    companion object {
        const val MAX_GRAPHEMES = 500
        const val MAX_OCTETS = 8192
        fun of(raw: String): EntryText { /* NFKC, trim, count with BreakIterator */ }
    }
}
```

1. **500 grapheme clusters**, counted with `BreakIterator.getCharacterInstance()` in the
   application. This is the number FR-041 means and the only one a person could hit.
2. **`octet_length(text) <= 8192`** at request validation *and* as a database CHECK. Graphemes
   are unbounded in byte length; without this a single entry can reach megabytes and flow into
   `text_search`, the stored `tsvector`, the outbox payload and the 256 KB response cap.
3. **A code-point CHECK set above any reachable grapheme expansion** — see §12.2, which is why
   doc 07's `char_length(text) <= 4000` has to go.

Text is NFKC-normalised before counting, as passwords are (FR-001), and stored raw and
unmodified thereafter (doc 04 §7: no lossy preprocessing at write time).

### 3.3 `StreakState` and `StreakEvent`

`StreakState` is a projection, one row per Bond, and `streak_events` is an append-only log of
every change to it — which is what makes "why did my streak break?" answerable at a support
desk rather than by reasoning. Both land in C4.

## 4. The reveal gate

FR-060's acceptance clause calls this "the single most important test in the system", and doc
12 §3 calls the file that holds it "the single most important test file in the repository".
It is worth being precise about what it forbids and what it deliberately permits.

**BR-1 — `canRead(M, E)`** first requires membership in E's bond. A deleted or withdrawn
entry returns a tombstone for everyone. Otherwise content is readable when `E.author == M`
or `E.revealedAt != null`. Reveal sets the entry's timestamp transactionally, both on the
second submission and on a closed `SOLO` day. That timestamp is monotonic: applying a freeze
to a solo day or later suspending the bond must not hide previously revealed words. A
`FROZEN` status alone does not reveal an entry. An author can still read their own live entry
on a `SUSPENDED` day. The same gate applies to archive, search, favourites and replayed
responses, not just `today`.

**BR-8 — a locked entry serialises to `{authorMemberId, status: LOCKED}` and nothing else.**
No length, no `createdAt`, no `hasImage`, no media presence flag. The response shape *is* the
security control, so it is built as a distinct type — `LockedEntryResponse` — rather than as a
`RevealedEntryResponse` with nulled fields. A type that cannot carry the text cannot leak it by
someone adding a field in six months.

**What is deliberately shared:** `bondDay.status`. Doc 04 §6.1 settles this — a member who has
not written can infer from `PARTIAL` that their partner has, and J2's "Waiting for Tunde" state
depends on it. The reveal-gate test asserts the converse too: that the status *is* present. What
stays forbidden (FR-064, T-09) is everything about *reading, presence or activity* — whether the
partner opened the app, when they were last seen, whether they read your entry.

**The cache rule.** Doc 12 §3 names cache poisoning as a reveal-gate bypass no authorisation
layer sees: prime `today` as A, read as B before B has written, and B gets A's content. So:
**no response containing another member's entry is ever cached under a bond-scoped key.**
`GET /today` is not cached in Phase 3 at all; when it is, the key carries the member id.

## 5. The API

### 5.1 `GET /bonds/{bondId}/today`

Doc 06 §3.4 fixes the shape and calls it the most carefully designed response in the API. It is
the home screen in one call: `bondDay`, `myEntry`, `partnerEntry` (locked or not), `partner`,
`streak`, `prompt`. C1 ships it with `streak` and `prompt` absent; C4 and C6 fill them in.

### 5.2 Endpoints

| Slice | Method | Path | Notes |
|---|---|---|---|
| C1 | POST | `/bonds/{bondId}/entries` | `Idempotency-Key` **required**. Server assigns the day (BR-3/BR-3a). `409 ENTRY_ALREADY_EXISTS` |
| C1 | GET | `/bonds/{bondId}/today` | §5.1 |
| C2 | PATCH | `/entries/{entryId}` | Author only, pre-reveal only. `409 ENTRY_IMMUTABLE` after |
| C2 | DELETE | `/entries/{entryId}` | Author only. Post-reveal leaves a tombstone |
| C4 | GET | `/bonds/{bondId}/streak` | Detail plus the calendar heatmap payload |
| C5 | GET | `/bonds/{bondId}/days` | `limit`, `cursor`, `favourites`. `ETag` |
| C5 | GET | `/bonds/{bondId}/days/{date}` | One day, both entries |
| C5 | PUT/DELETE | `/entries/{entryId}/favourite` | Idempotent. `409 ENTRY_NOT_REVEALED` before reveal |
| C5 | POST/DELETE | `/entries/{entryId}/reactions[/{type}]` | A fixed set (FR-048) |
| C6 | GET | `/bonds/{bondId}/search` | `q`, `limit`, `cursor`. Revealed entries in this Bond only |
| C6 | GET | `/bonds/{bondId}/on-this-day` | Same date, prior years |
| C6 | GET | `/bonds/{bondId}/milestones` | Achieved and next |
| C6 | GET | `/prompts/daily`, `/prompts` | Locale-aware, avoids recent repeats |

**`/entries/{entryId}` is not bond-scoped in its path, and that is a decision.** The entry id
carries the Bond, so the guard loads the entry, reads its `bondId`, and calls
`BondAccess.membershipOf` — a non-member, an unknown id and a value that is not a UUID all get
one byte-identical `404`, exactly as T-02 requires of the bond routes. The alternative,
`/bonds/{bondId}/entries/{entryId}`, makes the client pass a fact the server must then check
for agreement, which is a second way to be wrong.

### 5.3 New `ErrorCode` values (`common:web`)

`ENTRY_ALREADY_EXISTS` (409) · `ENTRY_IMMUTABLE` (409) · `ENTRY_NOT_REVEALED` (409) ·
`DAY_CLOSED` (409) · `IDEMPOTENCY_KEY_REUSED` (422) · `MEDIA_NOT_YET_SUPPORTED` (422, C1 only,
removed by Phase 4).

Every one of these makes its slice a **breaking API change** under ADR-0024's 2026-09-24
amendment — doc 06 §2 generates the codes into the client as an exhaustive sealed class, so an
addition is source-breaking there. Each slice carries the `breaking-api-change` label.

### 5.4 Idempotency (FR-049, doc 06 §1)

Doc 06 §1 has specified this since the corpus was written and nothing has built it. It is due
now because `POST /entries` is the first endpoint that *requires* it.

- Keyed on **`userId` + key**, storing the HTTP method, canonical concrete path (including
  bond/entry ids), request-body hash and response metadata for **24 hours**. Route templates
  are not sufficient: two bonds are two different targets.
- A replay returns the original status and stable result identity with **`Idempotency-Replayed: true`**,
  after current authorization and deletion/withdrawal checks. Store result ids, not an extra
  retained copy of entry text. Render current tombstones when content has since been erased;
  byte-for-byte replay never overrides erasure. Unchanged resources reproduce the original response.
- The same key against a different endpoint or a different body is **`422 IDEMPOTENCY_KEY_REUSED`**,
  never the wrong stored response.
- **Postgres, not Redis.** Doc 06 §1's `idem:{userId}:{endpoint}:{key}` notation implies Redis;
  doc 05 §3 says idempotency, revocation and ShedLock are "all Postgres-backed with Redis as an
  optional fast path, so Redis can be removed in one session if the memory budget bites". The
  second statement is the load-bearing one and this follows it. See §12.3.
- A request that is **in flight** under the same key gets `409` rather than a second execution:
  take a transaction-scoped nonblocking advisory lock for `(user_id, key)` before lookup,
  then insert/reserve, mutate the domain and complete the result **in one database transaction**.
  A crash rolls all three back, so there is no committed entry with an incomplete key and no
  permanently stuck reservation. The unique constraint is `(user_id, key)`; fingerprint
  comparison includes method, concrete path and body. Expired-key replacement uses the same lock.

### 5.5 Rate limiting (doc 06 §4)

`entries:create` — a per-user bucket. The global per-user bucket already applies to everything;
this adds a write-shaped one. Nothing here is per-IP: every one of these endpoints is behind the
bearer, and an attacker with a token is rate-limited as a user.

## 6. Rules, stated so they can be tested

### 6.1 Day assignment (BR-3, BR-3a, FR-045)

1. The candidate instant is `intendedAt` when the client sent one, else the submission instant.
2. `intendedAt` is **refused as the candidate** — and the submission instant used instead — if
   it is more than **5 minutes in the future**, more than **36 hours in the past**, or falls on
   a Bond-day that is **already settled** (`closedAt != null`, including `FROZEN` and
   elapsed `SUSPENDED`), or already `REVEALED` before midnight.
3. Resolve the candidate against the persisted effective anchor intervals (§3.1), then find
   or create its day under the bond lock. This avoids needing a `bondDay` before choosing one.
   The client never names the date. Recheck closed/revealed state under the day lock; if a
   close raced with offline assignment, redirect once to the submission-time day.

**Why `EMPTY` is in that list**, spelled out because it was missing from the corpus's first
draft and the reason is not obvious: BR-10 makes a closed day's status authoritative and BR-1
requires an actual reveal transition for partner content. Two entries back-filled onto a closed
`EMPTY` day would satisfy neither clause — permanently unreadable by either member, with no
transition able to release them, contributing nothing to the streak. Silently swallowing words
is the exact harm BR-3a exists to prevent.

**Test:** a Bond anchored on `Africa/Lagos`; Tunde submits at 23:30 UTC on the 14th; the entry
lands on Bond-day **2026-09-15**. This is doc 04 §6's worked example and it is a test, not a
comment.

### 6.2 Submission (FR-040, FR-041, FR-049, BR-2)

- **At most one entry per member per day** — a unique index on
  `(bond_day_id, author_member_id) WHERE deleted_at IS NULL`, and BR-2 says "enforced by a
  unique index, not by application logic alone". A second attempt is `409 ENTRY_ALREADY_EXISTS`,
  produced by catching the constraint, not by checking first.
- With the bond lock already held, the Bond-day row is created lazily: `INSERT … ON CONFLICT (bond_id, date) DO NOTHING`, then
  `SELECT … FOR UPDATE`. Two first-entries racing produce one row. This is B2's invite-creation
  pattern (ADR-0027) and the reason it is that shape rather than a check-then-insert.
- A write to a **non-open** Bond (including `PENDING_DELETION`) is `409 BOND_ARCHIVED` (BR-9, ADR-0028's rule).
- A write to a **closed** day — reachable only through `intendedAt`, and rule 6.1.2 already
  redirects it — is `409 DAY_CLOSED` if it ever arrives another way.

### 6.3 The reveal (FR-060 – FR-063, FR-065)

Under the Bond-day's row lock, in the same transaction that persists the entry:

| Before | Entry count after | `revealTimeLocal` | After |
|---|---|---|---|
| `OPEN` | 1 | — | `PARTIAL` |
| `PARTIAL` | 2 | absent, or already passed | `REVEALED`, both entries `REVEALED`, `revealedAt` set |
| `PARTIAL` | 2 | in the future | `PENDING_REVEAL` |
| `PENDING_REVEAL` | 2 | passed (the job's second sweep) | `REVEALED` |

- **`PENDING_REVEAL` is a distinct status and not a `PARTIAL` that waits.** FR-062 says why:
  doc 04 §6.1 defines `PARTIAL` as "the partner has written and you have not", so leaving both-
  submitted days in `PARTIAL` would make the day status lie to both members.
- **The second sweep is not optional.** Between the synchronous submit path and midnight there
  is no other mechanism, so without it a `PENDING_REVEAL` day would simply never reveal. It runs
  in the same fifteen-minute job.
- **Reveal is gated on text only** (FR-061). Media becomes visible to the non-author when it is
  `READY` and has passed moderation — Phase 4's problem, and the reason the gate is not "the
  entry is complete".
- **Idempotent, and asserted under real concurrency.** Doc 05 §5.1 requires a test that fires
  both submissions at once and asserts **exactly one `DayRevealed` event**. It exists in C2 and
  it fails when the lock is removed, which is the only reason to believe the lock is doing the
  work — the lesson ADR-0029 paid for with `@Version`.

### 6.4 The close (FR-063, doc 05 §5.2)

Every fifteen minutes, not hourly: IANA has 45-minute offsets (`Asia/Kathmandu` +5:45,
`Pacific/Chatham` +12:45) and an hourly job closes those Bonds up to 45 minutes late.

The job has two inputs: recorded activation intervals for gap creation, and **all existing
unsettled days**, regardless of the bond's current status or current requested zone.

1. **Upsert missing elapsed dates in active intervals.** Generate candidate date labels from
   activation through the earlier of the interval's end and now, using the effective anchor
   timeline, and anti-join against `bond_days`. Do not start at the greatest existing date:
   a lazy row created today must not hide several missing days before it. Use bounded SQL
   ranges and batches of at most **400 missing days**, commit completed batches, and resume
   from the remaining gaps on the next run. Alert on a large backlog but keep draining it.
   Never manufacture `EMPTY` dates in pending-member, suspension, deletion or archived
   intervals; gaps before an interval ended still need closure.
2. **Transition existing elapsed days using their stored `endsAt`.** `OPEN → EMPTY`,
   `PARTIAL → SOLO` with the live entry revealed, and `PENDING_REVEAL → REVEALED`.
   Set `closedAt` idempotently, including on elapsed `SUSPENDED` rows without revealing
   previously private suspended entries. Already revealed content remains readable.
   Current `ARCHIVED` or `PENDING_DELETION` status cannot strand an earlier partial or
   pending-reveal day. The timed-reveal sweep also scans existing rows in their snapshot
   zone independently of the current bond status.
3. **Evaluate the streak** for changed bonds (§6.5), from C4 onward.

**It derives gaps and unfinished transitions rather than remembering the last scheduler
run.** Missing-row queries must cover interior gaps as well as trailing ones. Bond and day
locks plus status predicates make retries idempotent. A persisted optimization watermark is
allowed only if it advances atomically after verifying every earlier eligible date is settled;
the greatest observed row is never such a watermark.

**Two counters, because one cannot tell the difference between healthy and stopped** (doc 11):
`gratitude_close_job_last_success_timestamp` is set on **every** run, including the many that
find no zone crossing midnight, so it never goes stale on a quiet interval; and
`gratitude_bonds_closed_total` proves the job is doing work rather than merely running, with a
separate alert if it stays flat for more than 25 hours.

**ShedLock on Postgres** — see §12.3.

**The timezone test matrix** (doc 04 §6) is not a suggestion: a DST spring-forward day (23 h), a
DST fall-back day (25 h), `Asia/Kathmandu`, `Pacific/Chatham`, a Bond whose members are ≥12 h
apart, and a member crossing the date line.

### 6.5 Streaks (BR-4, BR-5, BR-6, FR-070 – FR-076)

- **BR-4.** `currentStreak` is the maximal run of days where `status ∈ {REVEALED, FROZEN}`,
  ending at the latest eligible day (today, if complete, otherwise the latest elapsed
  non-suspended day). `SUSPENDED` days are **skipped** both when finding that endpoint and
  walking the run — they neither extend it nor break it
  (§8.1, §8.2, §8.3a). `longestStreak` is never decreased (FR-071).
- **BR-5 — freezes accrue incrementally and this is not a formula.** Each complete day increments
  `freezeProgress`; reaching 14 resets progress and banks a freeze (cap 2) **only if the Bond
  is not in Strict mode at that moment**. A threshold reached in Strict mode resets progress
  without banking; switching Strict mode off does not replay past thresholds. It is explicitly *not* recomputed as
  `floor(totalCompleteDays / 14) - freezesConsumed`: that formula retroactively grants freezes
  for days spent in Strict mode the instant Strict mode is switched off, which contradicts
  FR-073's "toggling Strict mode never alters past days".
- Outside Strict mode, a banked freeze is consumed on the next missed day, which becomes
  `FROZEN`; no banked freeze means the day remains `SOLO`/`EMPTY` and breaks the run.
  Strict mode never consumes a freeze. Persist the applied strict-mode and freeze events
  alongside day outcomes so recalculation never substitutes today’s setting for past decisions.
- **BR-6 / §8.5.** A date skipped outright by an approved anchor change (`Africa/Lagos` →
  `Pacific/Kiritimati` loses one) is `FROZEN`, not missed. Because the change is mutually
  confirmed (FR-027, ADR-0030), neither member can end a shared streak alone — a correctness fix
  and a T-09 mitigation in the same rule.
- **FR-074 — `recalculate` is deterministic and recomputes from the `bond_days` timeline, not
  from entries.** BR-10 is the reason: a day's status is authoritative once closed and is never
  recomputed from entries, which is exactly what preserves the surviving partner's
  `longestStreak` through the other person's account deletion. See §12.4 on FR-074's wording.
- **FR-076 — no loss-aversion copy, and the system never tells one member that the other has
  not written.** That is a rule about what the API may emit, so it is a rule this phase can
  break: no endpoint returns "your partner has not written yet" as a message, and no outbox
  event carries one.

**Property-based tests belong here and nowhere else in the phase.** Generate a random timeline
of day statuses, apply the rules, and assert the invariants: `longestStreak` never decreases;
`recalculate` is a fixed point; a `SUSPENDED` run of any length leaves the streak unchanged;
Strict mode never alters a past day.

### 6.6 The archive, favourites and search (FR-090 – FR-093, FR-050)

- Days with revealed entries, including subsequently frozen solo days, newest first,
  cursor-paginated. ETags cover the caller-specific rendered response, including favourites,
  tombstones and visibility; a bond-row version alone is insufficient.
- **Favourites are computed per caller and the partner's are never present in any response —
  not as a count, not as an aggregate, not as an `anyFavourited` flag.** An aggregate over two
  people discloses the other one, which is the read receipt FR-064 forbids. Doc 04 makes this
  structural: `entry_favourites` is keyed by `memberId` rather than being a flag on `Entry`,
  because `Entry` is shared and any field on it is readable by both. The privacy property holds
  by construction, not by an access rule someone can forget.
- Only a `REVEALED` entry can be favourited — you can keep only what you can see. Deleting an
  entry cascades to its favourites: a favourite is never a reason to retain content the author
  removed.
- Search is `tsvector` + GIN over **revealed entries in the caller's own Bond only**, which is
  one more surface the cross-tenant suite must cover.

### 6.7 Withdrawal on block (FR-029a, doc 26 §5.1)

**Decided 2026-09-28: the destructive reading.** BR-10's erasure is total. Withdrawing content-
erases the blocker's own entries — text and media references nulled, `status = DELETED`, the rows
retained as tombstones — and there is one copy, so it is gone for both people. `states.md` §9's
"each member keeps read access to their own archive" is **qualified to exclude withdrawn
entries**, and doc 26's confirm copy says plainly that the words go permanently, for both. The
confirm flow **offers an export first** (FR-009), so keeping your words is a real option rather
than a promise the schema cannot keep.

Doc 26 §5.1 chose the destructive reading as its working assumption for a good reason and it
holds: if we assume destruction and are wrong, someone read a warning that was scarier than
necessary; if we assume retention and are wrong, someone blocking an abuser silently loses years
of their own writing.

**Mechanically, it goes through the outbox, and that is forced rather than chosen.** Blocking
lives in `bond`; entries live in `gratitude`; `gratitude` already depends on `bond`, so a direct
call back would be a module cycle. `block` writes a `BondBlocked { withdrawEntries }` event in
its own transaction and `gratitude` consumes it — which is what a transactional outbox is for,
and is the first real consumer in the system. It lands in **C5**, with the poller.

Withdrawal must suppress reads **as soon as the block transaction commits**, even if the
poller is stopped. Persist a withdrawal marker in bond in that transaction and expose it
through the public read-access port. All gratitude content reads, search and idempotency
replays consult that marker before returning content. The consumer later erases text, media
references, search data, favourites and any derived content caches transactionally and
idempotently. Until C5 provides this complete path, withdrawal must be rejected as unsupported,
not acknowledged and silently deferred. No outbox payload contains entry text or credentials.

Bond-day statuses are **not** recomputed afterwards. BR-10 again: the timeline is authoritative,
and the surviving member's streak history is not a thing the blocker gets to rewrite.

## 7. Data

Migration versions are global — one Flyway history across modules — so they are allocated in
slice order. `V10` is the last one Phase 2 uses.

| Slice | Version | Owner | Tables |
|---|---|---|---|
| C1 | `V11__common_idempotency_keys.sql` | `common:web` | `idempotency_keys` |
| C1 | `V12__gratitude_bond_days_and_entries.sql` | `modules:gratitude` | `bond_days`, `entries` |
| C1 | `V13__bond_anchor_intervals.sql` | `modules:bond` | `bond_anchor_intervals` (§3.1's effective-zone timeline) |
| C2 | `V14__common_outbox_events.sql` | `common:events` | `outbox_events`, `outbox_deliveries` |
| C3 | `V15__scheduling_shedlock.sql` | `modules:scheduling` | `shedlock` |
| C4 | `V16__gratitude_streaks.sql` | `modules:gratitude` | `streak_states`, `streak_events` |
| C5 | `V17__gratitude_reactions_and_favourites.sql` | `modules:gratitude` | `reactions`, `entry_favourites` |
| C6 | `V18__gratitude_prompts.sql` | `modules:gratitude` | `prompts`, `prompt_impressions` |

C1 owns V11–V13. The first draft of this table gave C1 two versions and C2 `V13`; see §12.5.

Doc 07 §2 carries the column lists and this document does not restate them, with four
exceptions recorded in §12 because doc 07 is wrong about them.

**Indexes that are correctness, not performance:**

- `bond_days` **unique `(bond_id, date)`** — the constraint the whole phase rests on.
- `entries` **unique `(bond_day_id, author_member_id) WHERE deleted_at IS NULL`** — BR-2.
- `reactions` unique `(entry_id, member_id, type)`; `entry_favourites` unique `(entry_id, member_id)`.
- `idempotency_keys` unique `(user_id, key)`; method, concrete path and body hash are compared data.
- `outbox_deliveries` unique `(event_id, consumer_id)` for independent consumer acknowledgements.

**Indexes that are performance:** `bond_days (bond_id, date DESC)` for the archive feed, the
hottest read; `bond_days (status, date) WHERE status IN ('OPEN','PARTIAL','PENDING_REVEAL')`, a
partial index so the close job's scan stays small; a GIN index on `entries.search_vector`;
`entry_favourites (member_id, created_at DESC)` for the favourites filter; and
`outbox_deliveries (consumer_id, next_attempt_at) WHERE processed_at IS NULL`, partial so it stays small as the
table grows.

## 8. The outbox

`common:events` owns `outbox_events`, a publishing port, and — from C5 — a poller that runs
every two seconds with `FOR UPDATE SKIP LOCKED`.

**Events are written from C2, and the poller arrives in C5.** Doc 04 §7's "OutboxEvent from day
one" is about the *write* side: every meaningful state change is recorded transactionally
alongside the state it changed, so that Phase 4's notifications and Phase 10's feature
extraction read a complete history rather than one that starts when someone remembered. Building
a poller with nothing to poll would be the other failure — the mechanism that is present, looks
right, and is not load-bearing, which is the lesson ADR-0029 already cost a slice.

Phase 3 writes: `EntrySubmitted`, `DayRevealed`, `DayClosed`, `StreakBroken`, `StreakExtended`,
`MilestoneReached`. C5 adds the first consumer (§6.7). `gratitude_outbox_pending` is the gauge
that says the poller is stuck.

Delivery state belongs to `(eventId, consumerId)`, not a global `processed_at` on the event.
The withdrawal consumer must not mark events as consumed for later notifications or analytics.
Register each consumer with an explicit starting position; retain immutable, content-free
events for the promised history, and backfill delivery rows when a new consumer is registered.
Each handler and its delivery acknowledgement commit together; retries are at least once and
handlers deduplicate by event id. External effects require their own idempotent delivery step.
The pending gauge is per consumer and counts due registered deliveries, not events with no
consumer yet. These guarantees apply across process restarts and concurrent pollers.

## 9. Testing

Beyond the project's standing bar — TDD, Testcontainers, 80% JaCoCo, Konsist, mutation-testing
the load-bearing assertions:

| What | Why it is named here |
|---|---|
| **The reveal-gate matrix** | Every day status × caller state, asserting a locked entry is exactly `{authorMemberId, status: LOCKED}` and that `bondDay.status` **is** present. Doc 12: the most important test file in the repository. A failure is a P0 (doc 11). |
| **Cache poisoning** | Prime `today` as A, read as B before B has written, assert none of A's content. Doc 12 names it as a bypass no authorisation layer sees. |
| **Concurrent submission** | Both members submit at once; exactly one `DayRevealed`. Must fail with the lock removed. |
| **The timezone matrix** | Spring forward, fall back, Kathmandu, Chatham, members ≥12 h apart, a date-line crossing. |
| **`intendedAt` back-fill** | Refuse settled `EMPTY`, `FROZEN` and elapsed `SUSPENDED`; race assignment against close. |
| **Close-job idempotency** | Run twice; test interior missing dates before a newer lazy row, backlog over 400 dates, and ended bonds with pending reveal. |
| **Consent and lifecycle races** | Submit versus leave/block/deletion/zone confirmation; lock order prevents post-end writes or duplicate date labels. |
| **Idempotency recovery** | Same key across bonds/routes is 422; concurrent requests execute once; crash before commit rolls back both entry and key; replay after erasure contains no old text. |
| **Reveal persistence** | A revealed solo entry stays readable after freezing; joining on the current suspended day resumes it; prior suspended days stay private. |
| **Withdrawal and outbox** | With poller stopped, committed withdrawal immediately hides content; independent consumers and crash retries do not lose events. |
| **Streak properties** | Randomised timelines; the four invariants in §6.5. |
| **Cross-tenant** | The existing route-driven suite picks up every new endpoint automatically (ADR-0026), and a route added without a fixture fails the build. |
| **Grapheme counting** | A ZWJ family emoji, a flag, a combining sequence; 500 accepted only within the independent 8192-byte cap; 501 refused; large emoji strings exercise the byte cap. |

## 10. Slices

| Slice | What | Ends at |
|---|---|---|
| **C1** | `modules/gratitude`, `bond.api.BondAccess`, `Idempotency-Key` in `common:web`, V11–V13, the `BondDay` aggregate, `POST /entries` with BR-3/BR-3a and BR-2, `GET /today` without streak or prompt | One person can write, and reads only their own |
| **C2** | The reveal under the row lock, `PENDING_REVEAL`, BR-7 immutability, `PATCH`/`DELETE /entries/{id}`, the reveal-gate matrix, the outbox table and its writes | **Two people see each other's words** |
| **C3** | `modules/scheduling`, the fifteen-minute job, ShedLock, `DayCloser`, `SOLO`/`EMPTY`/second sweep, the timezone matrix, the two counters | The loop runs without anyone submitting |
| **C4** | Streaks, freezes, Strict mode, `FROZEN`, `recalculate`, `GET /streak`, property tests | **M3** — the loop is complete |
| **C5** | The outbox poller, withdrawal on block (§6.7), the archive feed, per-caller favourites, reactions | The archive exists |
| **C6** | Search, prompts and `prompt_impressions`, on-this-day, milestones | Phase 3 closes |

**M3 is "the loop runs live for a week between two real people via the HTTP API"** and it needs
two things beyond code, both named in doc 15 §4 and neither of them free: a Bruno or Insomnia
collection, and a small CLI harness — there is no app yet, and doc 15 records that pretending
otherwise was an error in its first draft. They are built in C4.

## 11. Figma alignment (`states.md`)

| Endpoint | Screen |
|---|---|
| `GET /today` | §3 *Today — the whole product*, and §3a's ambient-surface candidate (out of v1) |
| `POST /entries` | §4 *Compose* |
| the reveal | §5 *Reveal — the one animation* |
| `GET /days`, `/days/{date}` | §6 *Archive* |
| `GET /streak`, `/milestones` | §7 *Streak* |
| withdrawal on block | §9 *Ending*, whose retained-access line §6.7 qualifies |
| `PATCH`/`DELETE /entries/{id}` | §4 and §10 *Failure, offline and sync* |
| search, `/prompts`, `/on-this-day` | **Gaps** — record them in `states.md` before C6 |

## 12. Corpus corrections this phase forces

Each of these is a document being brought in line with a decision made elsewhere, or a
contradiction between two documents that code will have to resolve one way or the other. The initial
corpus amendments are on the Gratitude branch `docs/phase-3-daily-loop`; the review
clarifications in this specification must also be carried into the relevant corpus and ADRs
when implementing each slice.

### 12.1 Doc 07's `bond_days.status` lists five of the eight statuses

The DDL comment reads `OPEN|PARTIAL|REVEALED|SOLO|EMPTY`. Doc 04 §3 defines eight, and the three
missing ones are each required by a rule stated elsewhere: `PENDING_REVEAL` by FR-062 (which
also explains why it cannot be a waiting `PARTIAL`), `SUSPENDED` by doc 04 §8.1–8.3a, and
`FROZEN` by BR-5 and §8.5. **Doc 04 is right; doc 07 is stale.** The migration writes eight.

### 12.2 Doc 07's `entries` CHECK is the constraint FR-041 explicitly rejects

The DDL carries `check (char_length(text) <= 4000)` described as a backstop. FR-041 names this
exact number as the first draft's error: 4,000 code points allows only 8 per grapheme, and one
ZWJ family emoji is 10, so 500 legitimately typed characters can trip it. **The CHECK is
`octet_length(text) <= 8192` plus a code-point bound set above any reachable expansion.**

### 12.3 Doc 05 contradicts itself about where ShedLock lives

§5.2 says "ShedLock over Redis ensures only one instance runs the job during a rolling deploy".
§3's stack table says idempotency, revocation and ShedLock are "all Postgres-backed with Redis
as an optional fast path, so Redis can be removed in one session if the memory budget bites".
**Postgres wins**, because the second statement is a property of the architecture that the first
would quietly revoke: a lock on Redis makes Redis load-bearing for correctness, and ADR-0023
already established that Redis fails *open* here — which is safe for rate limits and unsafe for
a lock. Doc 06 §1's `idem:` key notation goes the same way, for the same reason.

### 12.4 Doc 04 §8.3a cannot be built as written — and this is the phase's oracle

§8.3a says Bond-days "are not created or evaluated while a Bond is `PENDING_MEMBER`", to stop
J1's guarantee (the creator writes before the partner joins) from manufacturing a run of `SOLO`
days that burns freezes and breaks a streak before the product has worked once.

The rule is right and the mechanism is impossible. An entry hangs off `entries.bond_day_id`, and
BR-2's unique index is `(bond_day_id, author_member_id)` — so an entry written while
`PENDING_MEMBER` has nowhere to live if the day does not exist. §8.3a and J1 cannot both hold as
written.

**Resolution: the row is created, with `status = SUSPENDED`.** §8.3a itself says "the mechanism
is the same one §8.1 already uses for suspension", and that mechanism is a status, not an
absence. So the day exists and holds the entry; the author reads their own words under BR-1's
first clause; the close job leaves it alone; and the streak walk skips it, so those days neither
extend nor break anything. On the joining day, the first gratitude operation or close sweep reconciles the current
`SUSPENDED` row under the bond/day locks: zero entries becomes `OPEN`, one becomes `PARTIAL`,
and two follow the reveal rule. Use the recorded activation instant to distinguish this day
from earlier suspended days, which remain private and excluded. The streak begins on the
first day both members exist, which is what §8.3a was protecting. Doc 04 §8.3a is amended to say so.

### 12.5 `bond_days` needs a column doc 07 does not give it

Doc 07's `bond_days` has no `anchor_timezone`. BR-6 and ADR-0030 require that a zone change
never recompute an existing day, and the only way to make that structural rather than
remembered is for the day to carry the zone it was opened in (§3.1). Without the column, every
historical day silently moves the first time the anchor does — the Phase 2 defect class in a new
place: a rule that is true only while nobody exercises the path that breaks it. The column is
added, `NOT NULL`, with the same 64-character bound `bonds.anchor_timezone` carries. V12 also
records `starts_at`/`ends_at`; C1 adds the bond-owned effective-zone timeline described in §3.1
with its own coordinated global migration version before allocating later slices.

**§7's version table was stale, and is corrected (2026-10-03).** The paragraph above says the
timeline takes "its own coordinated global migration version before allocating later slices",
and §7 went on allocating `V13` to C2's outbox as though it did not. The timeline is
`V13__bond_anchor_intervals.sql`, owned by `modules:bond` and built in C1, so C1 owns V11–V13
and every later slice moves up one: C2 `V14`, C3 `V15`, C4 `V16`, C5 `V17`, C6 `V18`. Nothing
had been built against the old numbers. ADR-0031 records what the timeline is and why a column
could not do its job.

### 12.6 FR-074's "from the entry log" means the Bond-day timeline

FR-074 requires streak state to be "recomputable deterministically from the entry log alone".
BR-10 says a Bond-day's status "is never recomputed from entries; it is authoritative once
closed", and says in the same breath that this is "what makes FR-074's `recalculate` safe to run
afterwards". They agree on intent and disagree on wording, and the wording matters to whoever
implements C4: **`recalculate` reads `bond_days`.** Recomputing from entries would undo exactly
the tombstone protection BR-10 exists for. FR-074's phrasing is corrected.

### 12.7 `common:events` is a module doc 05 §2.1 does not list

The outbox has a table, a port, a poller and — from Phase 4 — two consumers in different modules.
Doc 05 §2.1 lists `analytics/` as "outbox consumer, event store", which is the Phase 10 reader,
not the writer every module needs. A `common/events` sibling is added to the tree.

### 12.8 Decisions recorded, not corrections

- **OQ-02 is closed: no cut** (§1). Doc 15 §6 and doc 21 amended.
- **Doc 26 §5.1 is answered: the destructive reading** (§6.7). Doc 26, BR-10's wording,
  `states.md` §9 and doc 21 amended.

## 13. Decisions made here, for the ADRs to carry

Each slice's ADR carries the ones it builds. Listed so that no decision reaches code without a
document, and so that a reviewer can check the list rather than reconstruct it.

1. `bond.api.BondMembership` has an **`internal` constructor**, so the compiler — not a Konsist
   rule — carries ADR-0026's guarantee across the module boundary (§2.1).
2. The close job **derives interior gaps and elapsed unfinished days**, including on ended
   bonds; the greatest existing date is not a completion watermark (§6.4).
3. The job's **orchestration is in `scheduling` and its transitions are in `gratitude`**, so the
   synchronous path and the job cannot state the same rule twice (§2.2).
4. `bond_days` **copies the anchor zone onto the row**, which is what makes BR-6's "never
   recomputed" structural (§3.1).
5. `PENDING_REVEAL` is a real status with a **second sweep in the same fifteen-minute job**,
   because nothing else exists between submit and midnight (§6.3).
6. **Idempotency and ShedLock are Postgres-backed**, keeping Redis removable (§5.4, §12.3).
7. A locked entry is a **distinct response type**, not a nulled revealed one (§4).
8. The **outbox is written from C2 and polled from C5**, because a poller with nothing to poll
   is a mechanism that looks load-bearing and is not (§8).
9. **Withdrawal travels through the outbox**, because a direct call would be a module cycle —
   and it is the system's first real consumer (§6.7).
10. `PENDING_MEMBER` days exist as **`SUSPENDED`**, which is the only reading under which §8.3a
    and J1 both hold (§12.4).
11. Media ids are **refused with `422` until Phase 4** rather than stored and ignored (§1).
12. `/entries/{entryId}` is **not bond-scoped in its path**; the guard derives the Bond from the
    entry (§5.2).
