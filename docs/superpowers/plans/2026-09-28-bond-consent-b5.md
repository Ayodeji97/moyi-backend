# Slice B5 — Two-party consent: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The two changes neither member may make alone — moving the bond's anchor timezone and destroying the bond — become one mechanism that asks the other person, waits seven days, and forgets.

**Architecture:** One table, `bond_proposals` (V10), carrying both kinds. Every path takes the bond's row lock, reaps a lapsed proposal of that kind, then reads and writes — the rule ADR-0028 §6a settled and ADR-0029 proved. Confirming is a compare-and-set, so two confirmations cannot both apply. The timezone gets the 30-day rule; deletion gets a 30-day cooling-off either member can cancel.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Data JPA, Postgres 18 + Valkey via Testcontainers, kotest, Konsist, springdoc.

**Spec:** `docs/superpowers/specs/2026-09-24-bonds-and-invites-design.md` — §5.1 (the three response fields), §5.2 rows #13–#17, §5.3, §6.4, §7 (V10), §9 (B5 row), §11 decisions 4–7. Corpus: FR-027, FR-028, BR-6, T-09, doc 04 §8.5, doc 26 §4, `states.md` §8 and §9.

**Depends on:** B1–B3 (merged / #39) and **B4 (#40, open)** — this branch is stacked on `feat/bond-settings` and uses `Change<T>`, `IfMatch`, `MemberStore` and the archived rule. Rebase down the stack as each merges. **This is the last slice of Phase 2.**

## Global Constraints

Everything in B1–B4's plans still binds. Added or sharpened for this slice:

- **One mechanism for both kinds.** `bond_proposals` carries `TIMEZONE_CHANGE` and `DELETION`. One live proposal per `kind` per bond, 7-day expiry, confirmable only by a member **other** than the proposer, cancellable by either.
- **Two decisions taken with Daniel before writing this plan**, both departures from the spec:
  1. **A deletion request is refused on *any* archived bond** — `409 BOND_ARCHIVED` whether it ended by a leave or a block. §6.4 says "only when the Bond ended by block", and that is an oracle: the blocked party would learn which happened by trying it once, which is precisely what doc 26 §2.1 forbids and what B3's review caught in another form. The cost is that a member who left cannot start a mutual deletion; their own copy still goes with account deletion (FR-008) and they can still export (FR-009). Recorded in ADR-0030; doc 06 §3.3 and the spec are amended.
  2. **A lapsed proposal is closed lazily, when it gets in the way.** V10's partial unique index is `(bond_id, kind) WHERE confirmed_at IS NULL AND cancelled_at IS NULL`, so a lapsed row still occupies the slot even though §6.4 treats it as dead. Proposing therefore marks any non-live proposal of that kind `cancelled_at = now` under the row lock, then inserts — the shape `CreateInvite` already uses for the outstanding code. Nothing runs on a schedule (§6.4's "never reaped" holds for anything that is not in the way).
- **The 30-day rule** (FR-027): a timezone change — direct *or* confirmed — when `timezone_changed_at` is less than 30 days old is `409 TIMEZONE_CHANGE_TOO_SOON`, and the detail names the date it becomes allowed. **Checked at proposal and again at confirmation**, because seven days can pass in between. `409`, not doc 06 §3.3's `429`: a month is not a rate limit a client should render as "please wait" (spec §11 decision 4).
- **While `PENDING_MEMBER` there is nobody to consent**, so the creator's timezone change applies at once and a deletion request confirms itself (spec §11 decision 6). Both still take the 30-day rule.
- **`PENDING_DELETION` is not open** (`Bond.isOpen`), so every other bond-scoped write is `409 BOND_ARCHIVED` during the cooling-off — and `DELETE …/deletion-request` must therefore **not** check `isOpen`, or the cooling-off could not be cancelled, which is the one thing it exists for.
- **Cancelling returns the status to `ACTIVE`, or `ARCHIVED` if any member has `leftAt`** (§6.4). A bond does not come back to life because a deletion was called off.
- **ADR-0028's obligation lands here:** ending a bond must cancel any live proposal. `EndBond` has had nowhere to do that until this table existed; it is this slice's to wire and to test.
- **BR-6 / doc 04 §8.5, written down so Phase 3 cannot forget it:** "effective from the next Bond-day, never retroactively" has no meaning in Phase 2 because there are no Bond-days. The column simply changes. Phase 3's day opener reads the zone when it opens a day and never recomputes a `bond_days` row.
- **Three new `ErrorCode` values** (`PROPOSAL_PENDING`, `PROPOSAL_NEEDS_OTHER_MEMBER`, `TIMEZONE_CHANGE_TOO_SOON`), so **B5 is a breaking change and carries the `breaking-api-change` label**.
- **Five new routes go in `BondCrossTenantTest.fixtures`** — let the suite fail once first, naming them.
- Commit trailer:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH
  ```
- Work from `/Users/danzucker/Desktop/Learning/project_base_learning/Gratitude/moyi-backend/.worktrees/feat-bond-consent` (branch `feat/bond-consent`, based on `feat/bond-settings`).
- **Not in this slice:** the actual deletion (Phase 5's job reads `PENDING_DELETION` and `deletion_requested_at`); notifications about a pending proposal (Phase 4); anything to do with Bond-days.

---

## File structure

**bond domain** — `Proposal.kt` (new: `Proposal`, `ProposalKind`, the TTL), `Bond.kt` (`withAnchorTimezone`, `requestDeletion`, `cancelDeletion`, `deletionScheduledFor`, `nextTimezoneChangeAt`, the two intervals).

**bond infra** — `V10__bond_proposals.sql`, `BondProposalEntity.kt`, `BondRepositories.kt` (+`BondProposalRepository`), `BondMappers.kt` (+proposal), `ProposalStore.kt` (new).

**bond service** — `ChangeTimezone.kt` (propose / confirm / cancel), `RequestDeletion.kt` (request / cancel), `BondErrors.kt` (+3), `BondViews.kt` (+ live proposals, batched), `EndBond.kt` (cancel live proposals).

**bond web** — `BondTimezoneController.kt`, `BondDeletionController.kt`, `ChangeTimezoneRequest.kt`, `BondResponse.kt` (+`deletionScheduledFor`, `pendingTimezoneChange`, `pendingDeletionRequest`).

**common:web** — `ErrorCode.kt` (+3).

**bond test** — `BondTest.kt`, `ProposalPersistenceTest.kt` (new), `BondTimezoneEndpointTest.kt` (new), `BondDeletionEndpointTest.kt` (new), `ProposalRaceTest.kt` (new), `BondCrossTenantTest.kt` (+5), `DiscreetExitTest.kt` (+ the deletion-request case).

**contracts / app / scripts / docs** — `OpenApiConfiguration.kt`, `OpenApiContractTest.kt`, `contracts/openapi.json`, `scripts/smoke.sh`, `adr/0030-two-party-consent-as-one-mechanism.md`, `docs/learning-log.md`, `.claude/HANDOVER.md`, corpus on `docs/bonds-phase-2`.

---

### Task 1: V10, and the proposal in the database

**Files:**
- Create: `modules/bond/src/main/resources/db/migration/V10__bond_proposals.sql`, `modules/bond/src/main/kotlin/com/moyi/bond/infra/database/BondProposalEntity.kt`, `.../ProposalStore.kt`, `modules/bond/src/main/kotlin/com/moyi/bond/domain/Proposal.kt`
- Modify: `BondRepositories.kt`, `BondMappers.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/infra/database/ProposalPersistenceTest.kt` (new)

**Interfaces:**
- Produces: `ProposalKind.{TIMEZONE_CHANGE, DELETION}`; `Proposal(id, bondId, kind, payload, proposedByMemberId, proposedAt, expiresAt, confirmedByMemberId, confirmedAt, cancelledAt)` with `isLive(now)`; `ProposalId`; `ProposalStore.insert`, `.findLive(bondId, kind, now)`, `.findLiveOf(bondIds, now)`, `.closeLapsed(bondId, kind, now)`, `.confirm(id, memberId, now)`, `.cancel(id, now)`, `.cancelLiveOf(bondId, now)`.

- [ ] **Step 1: Write the migration.** `V10__bond_proposals.sql`, in V9's commenting style — every column and constraint explaining itself:

```sql
-- Two-party consent — FR-027 (timezone), FR-028 (deletion), doc 04 §8.5, ADR-0030.
--
-- One table for both, because they are one mechanism: somebody proposes, the
-- other member confirms within seven days, either may cancel, and a proposal
-- nobody answers simply lapses. Two tables would be two copies of that rule.

CREATE TABLE bond_proposals (
    id                     uuid        PRIMARY KEY,
    bond_id                uuid        NOT NULL REFERENCES bonds (id) ON DELETE CASCADE,
    kind                   text        NOT NULL,
    -- What is being proposed, as text because only one kind has a payload: an
    -- IANA zone id for TIMEZONE_CHANGE, NULL for DELETION. A jsonb column would
    -- invite a second field nobody has specified.
    payload                text,
    proposed_by_member_id  uuid        NOT NULL REFERENCES bond_members (id) ON DELETE CASCADE,
    proposed_at            timestamptz NOT NULL,
    -- Seven days (FR-027). A lapsed proposal is not reaped on a schedule; it is
    -- ignored by every read (`expires_at` is in the predicate) and closed
    -- lazily when a new proposal of the same kind needs its slot (ADR-0030).
    expires_at             timestamptz NOT NULL,
    confirmed_by_member_id uuid        REFERENCES bond_members (id) ON DELETE CASCADE,
    confirmed_at           timestamptz,
    cancelled_at           timestamptz,

    CONSTRAINT bond_proposals_kind_check CHECK (kind IN ('TIMEZONE_CHANGE', 'DELETION')),
    CONSTRAINT bond_proposals_payload_length_check CHECK (payload IS NULL OR char_length(payload) BETWEEN 1 AND 64),
    -- A confirmation is a member and a time, together or not at all.
    CONSTRAINT bond_proposals_confirmed_pair_check
        CHECK ((confirmed_at IS NULL) = (confirmed_by_member_id IS NULL)),
    -- Confirmed or cancelled, never both: the two are the two ways it ends.
    CONSTRAINT bond_proposals_one_ending_check CHECK (confirmed_at IS NULL OR cancelled_at IS NULL)
);

-- One open proposal per kind per bond (FR-027, FR-028). Partial, so a bond can
-- have any number of *closed* ones — the history of what was asked and what
-- came of it, which doc 26 §4 will want if a support question ever arrives.
--
-- Note what it does NOT know: a lapsed proposal is still "open" by this
-- index's definition, because an index cannot ask what time it is. That is
-- precisely why proposing closes a lapsed one first (ADR-0030).
CREATE UNIQUE INDEX bond_proposals_open_kind_key
    ON bond_proposals (bond_id, kind)
    WHERE confirmed_at IS NULL AND cancelled_at IS NULL;

-- Reading a bond with its pending proposals: the query on the front of
-- `GET /bonds` and `GET /bonds/{id}`.
CREATE INDEX bond_proposals_bond_id_idx ON bond_proposals (bond_id);
```

- [ ] **Step 2: Write the domain type** in `modules/bond/src/main/kotlin/com/moyi/bond/domain/Proposal.kt`:

```kotlin
package com.moyi.bond.domain

import java.time.Duration
import java.time.Instant

/**
 * What kind of thing is being proposed.
 *
 * Both are changes FR-027 and FR-028 say one member may not make alone: moving
 * the anchor timezone decides which day an entry belongs to for *both* of them,
 * and deleting the bond destroys what the other person wrote.
 */
internal enum class ProposalKind { TIMEZONE_CHANGE, DELETION }

/**
 * A change one member has asked for and the other has not yet agreed to
 * (FR-027, FR-028, ADR-0030).
 *
 * **One type for both kinds**, because the rules are the same rules: seven days
 * to answer, only the *other* member may confirm, either may cancel, and an
 * unanswered one lapses rather than nagging. [payload] is the only thing that
 * differs — a zone id for a timezone change, nothing for a deletion.
 *
 * Lapsing is expressed as a predicate rather than a state: [isLive] asks the
 * clock, and nothing writes a row to say "this expired". `states.md` §8 words
 * it for a person — *"lapses in seven days if not"* — and that is exactly what
 * a `null` `confirmedAt` past `expiresAt` means.
 */
internal data class Proposal(
    val id: ProposalId,
    val bondId: BondId,
    val kind: ProposalKind,
    val payload: String?,
    val proposedByMemberId: MemberId,
    val proposedAt: Instant,
    val expiresAt: Instant,
    val confirmedByMemberId: MemberId?,
    val confirmedAt: Instant?,
    val cancelledAt: Instant?,
) {
    init {
        require((confirmedAt == null) == (confirmedByMemberId == null)) {
            "a confirmation is a member and a time, together or not at all"
        }
        require(confirmedAt == null || cancelledAt == null) { "a proposal is confirmed or cancelled, never both" }
        require(kind != ProposalKind.TIMEZONE_CHANGE || payload != null) { "a timezone proposal carries the zone it proposes" }
    }

    /** Open, and not yet lapsed. The only kind of proposal any read acts on. */
    fun isLive(now: Instant): Boolean = confirmedAt == null && cancelledAt == null && expiresAt.isAfter(now)

    /** The zone a [ProposalKind.TIMEZONE_CHANGE] proposes. */
    fun proposedZone(): RegionZone {
        check(kind == ProposalKind.TIMEZONE_CHANGE) { "only a timezone proposal has a zone" }
        return RegionZone.of(requireNotNull(payload))
    }

    companion object {
        /** FR-027: seven days to answer. `states.md` §8 says so on the pending screen. */
        val TTL: Duration = Duration.ofDays(7)

        fun timezoneChange(
            id: ProposalId,
            bondId: BondId,
            zone: RegionZone,
            proposedBy: MemberId,
            now: Instant,
        ): Proposal = open(id, bondId, ProposalKind.TIMEZONE_CHANGE, zone.id, proposedBy, now)

        fun deletion(
            id: ProposalId,
            bondId: BondId,
            proposedBy: MemberId,
            now: Instant,
        ): Proposal = open(id, bondId, ProposalKind.DELETION, null, proposedBy, now)

        private fun open(
            id: ProposalId,
            bondId: BondId,
            kind: ProposalKind,
            payload: String?,
            proposedBy: MemberId,
            now: Instant,
        ): Proposal =
            Proposal(
                id = id,
                bondId = bondId,
                kind = kind,
                payload = payload,
                proposedByMemberId = proposedBy,
                proposedAt = now,
                expiresAt = now.plus(TTL),
                confirmedByMemberId = null,
                confirmedAt = null,
                cancelledAt = null,
            )
    }
}
```

  Add `ProposalId` to `Ids.kt` next to the others, in the same shape (`@JvmInline value class ProposalId(val value: UUID)`).

- [ ] **Step 3: Write the entity**, `BondProposalEntity.kt`, modelled on `BondInviteEntity` (`Persistable`, `@Transient new`, `equals`/`hashCode` on the id, a `toString` that carries no payload — a proposed zone is not a secret, but the habit is doc 18 §5's and the entity has nothing else worth printing).

- [ ] **Step 4: Write the repository** in `BondRepositories.kt`, on the bare `Repository` marker like its neighbours:

```kotlin
/** Proposals (FR-027, FR-028). Every finder is scoped to a bond; there is no "all proposals". */
internal interface BondProposalRepository : Repository<BondProposalEntity, UUID> {
    fun save(proposal: BondProposalEntity): BondProposalEntity

    /** The open, unlapsed proposal of one kind, if there is one. */
    @Query(
        """
        SELECT p FROM BondProposalEntity p
         WHERE p.bondId = :bondId
           AND p.kind = :kind
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
           AND p.expiresAt > :now
        """,
    )
    fun findLive(bondId: UUID, kind: ProposalKind, now: Instant): BondProposalEntity?

    /**
     * Every live proposal of the listed bonds, for the response assembler.
     * Batched over a list rather than called per bond: `GET /bonds` returns up
     * to three and this is the query that would otherwise be the N+1 — the same
     * reason `findAllLiveByBondIdIn` exists for invites.
     */
    @Query(
        """
        SELECT p FROM BondProposalEntity p
         WHERE p.bondId IN :bondIds
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
           AND p.expiresAt > :now
        """,
    )
    fun findAllLiveByBondIdIn(bondIds: Collection<UUID>, now: Instant): List<BondProposalEntity>

    /**
     * Closes any **lapsed** proposal of this kind, so the partial unique index
     * stops holding its slot (ADR-0030).
     *
     * `cancelled_at` rather than a third ending: from the system's point of view
     * a lapsed proposal is one nobody will ever confirm, and this is the moment
     * it stopped being considered. Nothing runs on a schedule — a proposal is
     * closed only when a new one needs the slot.
     *
     * @return how many were closed; zero or one, and a number rather than a
     *   boolean because two would mean the index was not doing its job.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondProposalEntity p
           SET p.cancelledAt = :now
         WHERE p.bondId = :bondId
           AND p.kind = :kind
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
           AND p.expiresAt <= :now
        """,
    )
    fun closeLapsed(bondId: UUID, kind: ProposalKind, now: Instant): Int

    /**
     * Confirms a proposal, if it is still live — the compare-and-set that makes
     * two simultaneous confirmations produce one applied change, the same shape
     * as `BondInviteRepository.consume` and for the same reason.
     *
     * @return `1` if this call confirmed it, `0` if somebody else did, or it was
     *   cancelled or lapsed in the meantime.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondProposalEntity p
           SET p.confirmedAt = :now,
               p.confirmedByMemberId = :memberId
         WHERE p.id = :id
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
           AND p.expiresAt > :now
        """,
    )
    fun confirm(id: UUID, memberId: UUID, now: Instant): Int

    /** Cancels one live proposal. `0` if it was already closed — one answer for every way. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondProposalEntity p
           SET p.cancelledAt = :now
         WHERE p.id = :id
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
        """,
    )
    fun cancel(id: UUID, now: Instant): Int

    /**
     * Cancels every live proposal of a bond — what ADR-0028 requires when a bond
     * ends, and the obligation that ADR waited for this table to exist.
     *
     * @return how many were cancelled, for the log line and for the test.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE BondProposalEntity p
           SET p.cancelledAt = :now
         WHERE p.bondId = :bondId
           AND p.confirmedAt IS NULL
           AND p.cancelledAt IS NULL
        """,
    )
    fun cancelLiveOf(bondId: UUID, now: Instant): Int
}
```

  Note `cancel` deliberately has **no** `expires_at` predicate: cancelling something that has lapsed is a no-op the caller need not be told about, and `states.md` §8's pending screen may well be showing it.

- [ ] **Step 5: Write `ProposalStore`**, in domain terms, mirroring `InviteStore` (each method one line over the repository, the KDoc carrying the reason). Then the mappers: `BondProposalEntity.toDomain()` and `Proposal.toEntity()` (insert-only, like the others; the state changes are all conditional `UPDATE`s).

- [ ] **Step 6: Write the persistence test**, `ProposalPersistenceTest.kt`, as its own class beside `MemberPersistenceTest` (constructor under detekt's limit: `proposals`, `bonds`, `transactions`, `dataSource`). Cases:

```kotlin
    @Test
    fun `a proposal round-trips, and lapses by the clock rather than by a write`()
    @Test
    fun `V10 permits one open proposal per kind and refuses a second`()      // DataIntegrityViolationException
    @Test
    fun `a confirmed and a cancelled proposal free the slot`()
    @Test
    fun `closing a lapsed proposal frees its slot, and leaves a live one alone`()
    @Test
    fun `confirming succeeds exactly once`()                                  // two calls, 1 then 0
    @Test
    fun `a lapsed proposal cannot be confirmed, and can still be cancelled`()
    @Test
    fun `cancelling every live proposal of a bond leaves the closed ones alone`()
    @Test
    fun `V10 refuses a confirmation with no member, a proposal both confirmed and cancelled, and an unknown kind`()
```

  Each asserts against the database rather than the store's return value where the constraint is the point.

- [ ] **Step 7: Run.** `./gradlew :modules:bond:test --tests '*ProposalPersistenceTest'`. `ddl-auto: validate` means the context will not start if the entity and V10 disagree — which is the mapping test.

- [ ] **Step 8: Commit.**

```bash
git add modules/bond
git commit -m "feat(bond): V10 bond_proposals, one table for both kinds of consent (FR-027, FR-028)" -m "One mechanism, because the rules are one set of rules: seven days to answer, only the other member confirms, either cancels, and an unanswered proposal lapses by the clock rather than by a write. The partial unique index holds one open proposal per kind and deliberately cannot tell that a row has lapsed, which is why proposing closes a lapsed one first." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 2: The domain — the 30-day rule, and the two transitions

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/domain/Bond.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/domain/BondTest.kt`

**Interfaces:**
- Produces: `Bond.withAnchorTimezone(zone: RegionZone, now: Instant): Bond`; `Bond.mayChangeTimezoneAt(now: Instant): Boolean`; `Bond.nextTimezoneChangeAt: Instant?`; `Bond.requestDeletion(now: Instant): Bond`; `Bond.cancelDeletion(): Bond`; `Bond.deletionScheduledFor: Instant?`; `Bond.TIMEZONE_CHANGE_INTERVAL`, `Bond.DELETION_COOLING_OFF`.

- [ ] **Step 1: Write the failing tests** in `BondTest`:

```kotlin
    @Test
    fun `the anchor zone can be changed once, and not again for thirty days`() {
        val bond = create()
        val london = RegionZone.of("Europe/London")

        val moved = bond.withAnchorTimezone(london, now)

        moved.anchorTimezone shouldBe london
        moved.timezoneChangedAt shouldBe now
        moved.mayChangeTimezoneAt(now) shouldBe false
        moved.mayChangeTimezoneAt(now.plus(Duration.ofDays(29))) shouldBe false
        moved.mayChangeTimezoneAt(now.plus(Duration.ofDays(30))) shouldBe true
        moved.nextTimezoneChangeAt shouldBe now.plus(Duration.ofDays(30))
        shouldThrow<IllegalStateException> { moved.withAnchorTimezone(bond.anchorTimezone, now) }
    }

    @Test
    fun `a bond that has never moved its zone may move it at once`() {
        val bond = create()

        bond.timezoneChangedAt.shouldBeNull()
        bond.mayChangeTimezoneAt(now) shouldBe true
        bond.nextTimezoneChangeAt.shouldBeNull()
    }

    @Test
    fun `an archived bond's zone cannot be changed`() {
        val archived = create().let { it.leave(it.members.single().id, now) }

        shouldThrow<IllegalStateException> { archived.withAnchorTimezone(RegionZone.of("Europe/London"), now) }
    }

    @Test
    fun `requesting deletion starts a thirty-day cooling-off`() {
        val joined = create().let { it.accept(joiner(it)) }

        val pending = joined.requestDeletion(now)

        pending.status shouldBe BondStatus.PENDING_DELETION
        pending.deletionRequestedAt shouldBe now
        pending.deletionScheduledFor shouldBe now.plus(Duration.ofDays(30))
        // Not open, so every other write is refused during it (ADR-0028, ADR-0029).
        pending.isOpen shouldBe false
    }

    @Test
    fun `cancelling a deletion returns an active bond to active`() {
        val joined = create().let { it.accept(joiner(it)) }

        val cancelled = joined.requestDeletion(now).cancelDeletion()

        cancelled.status shouldBe BondStatus.ACTIVE
        cancelled.deletionRequestedAt.shouldBeNull()
        cancelled.deletionScheduledFor.shouldBeNull()
        cancelled.isOpen shouldBe true
    }

    @Test
    fun `cancelling a deletion does not bring back a bond somebody had left`() {
        // §6.4: the status returns to ARCHIVED if any member has left. A bond
        // does not come back to life because a deletion was called off.
        val joined = create().let { it.accept(joiner(it)) }
        val left = joined.leave(joined.members.first { it.role == MemberRole.OWNER }.id, now)

        val cancelled = left.copy(status = BondStatus.PENDING_DELETION, deletionRequestedAt = now).cancelDeletion()

        cancelled.status shouldBe BondStatus.ARCHIVED
        cancelled.deletionRequestedAt.shouldBeNull()
    }

    @Test
    fun `a bond with no deletion pending has nothing to cancel`() {
        shouldThrow<IllegalStateException> { create().cancelDeletion() }
    }
```

- [ ] **Step 2: Run them and watch them fail.** `./gradlew :modules:bond:test --tests '*BondTest'`.

- [ ] **Step 3: Implement**, in `Bond`, below `update`:

```kotlin
    /**
     * Whether the anchor zone may move (FR-027's once-per-30-days rule).
     *
     * A bond that has never moved it may move it at once — the column is null
     * until the first change, and ADR-0004 expects an onboarding mistake to be
     * correctable.
     */
    fun mayChangeTimezoneAt(now: Instant): Boolean =
        timezoneChangedAt?.plus(TIMEZONE_CHANGE_INTERVAL)?.isAfter(now)?.not() ?: true

    /** When the zone may next move, or `null` if it may move now. The `409`'s detail names this date. */
    val nextTimezoneChangeAt: Instant? get() = timezoneChangedAt?.plus(TIMEZONE_CHANGE_INTERVAL)

    /**
     * Moves the anchor zone (FR-027).
     *
     * **This is not "the timezone setting".** Doc 04 §6 calls the distinction
     * between the bond's anchor and a member's own reminder zone the single most
     * misunderstood thing in the system: this one decides *which day an entry
     * belongs to*, for both members, which is why FR-027 makes it two-party and
     * rate-limits it to once a month. The member's zone is B4's settings row and
     * changes freely.
     *
     * BR-6 and doc 04 §8.5: "effective from the next Bond-day, never
     * retroactively". In Phase 2 there are no Bond-days, so the column simply
     * changes; Phase 3's day opener reads the zone when it opens a day and never
     * recomputes an existing `bond_days` row. Written here because this is the
     * method a Phase 3 author will read.
     */
    fun withAnchorTimezone(
        zone: RegionZone,
        now: Instant,
    ): Bond {
        check(isOpen) { "a bond that has ended cannot move its anchor zone" }
        check(mayChangeTimezoneAt(now)) { "the anchor zone may move at most once every 30 days" }
        return copy(anchorTimezone = zone, timezoneChangedAt = now)
    }

    /** When the bond would be destroyed, if a deletion is pending (FR-028's cooling-off). */
    val deletionScheduledFor: Instant? get() = deletionRequestedAt?.plus(DELETION_COOLING_OFF)

    /**
     * Both members have asked for the bond to be destroyed (FR-028).
     *
     * `PENDING_DELETION` is deliberately **not** [isOpen]: during the cooling-off
     * the bond takes no writes but stays readable, and the one thing that still
     * works is cancelling — which is the whole point of a cooling-off, and why
     * `RequestDeletion.cancel` does not check `isOpen`.
     *
     * Nothing here destroys anything. Phase 5's job reads this status and
     * `deletion_requested_at`; until then a `PENDING_DELETION` bond is a bond
     * with a date on it.
     */
    fun requestDeletion(now: Instant): Bond {
        check(isOpen) { "a bond that has ended cannot be scheduled for deletion" }
        return copy(status = BondStatus.PENDING_DELETION, deletionRequestedAt = now)
    }

    /**
     * Either member calls the deletion off (FR-028's 30-day escape hatch).
     *
     * The status returns to `ACTIVE`, or to `ARCHIVED` if anybody has left: a
     * bond does not come back to life because a deletion was cancelled.
     */
    fun cancelDeletion(): Bond {
        check(status == BondStatus.PENDING_DELETION) { "there is no deletion to cancel" }
        val restored = if (members.any { !it.isActive }) BondStatus.ARCHIVED else BondStatus.ACTIVE
        return copy(status = restored, deletionRequestedAt = null)
    }
```

  and in the companion:

```kotlin
        /** FR-027. A month, not a rate limit — the refusal is a `409` and names the date (ADR-0030). */
        val TIMEZONE_CHANGE_INTERVAL: Duration = Duration.ofDays(30)

        /** FR-028, and `states.md` §9 is explicit that it differs from account deletion's 14 days. */
        val DELETION_COOLING_OFF: Duration = Duration.ofDays(30)
```

- [ ] **Step 4: Run, lint, commit.** `./gradlew :modules:bond:test --tests '*BondTest'` then `:modules:bond:ktlintCheck :modules:bond:detektMain`.

```bash
git commit -am "feat(bond): the anchor zone's 30-day rule, and the deletion cooling-off" -m "Two transitions and the arithmetic behind the two refusals. PENDING_DELETION is deliberately not isOpen — during the cooling-off the bond takes no writes and stays readable, and cancelling is the one thing that still works, which is what a cooling-off is for. Cancelling restores ACTIVE, or ARCHIVED if anybody had left: a bond does not come back to life because a deletion was called off." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 3: The three error codes, and the response that shows a pending change

**Files:**
- Modify: `common/web/.../ErrorCode.kt`, `modules/bond/.../service/BondErrors.kt`, `.../service/BondViews.kt`, `.../web/BondResponse.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondsEndpointTest.kt` (the new fields are null on a plain bond)

**Interfaces:**
- Produces: `ErrorCode.{PROPOSAL_PENDING, PROPOSAL_NEEDS_OTHER_MEMBER, TIMEZONE_CHANGE_TOO_SOON}`; `ProposalPendingException`, `ProposalNeedsOtherMemberException`, `TimezoneChangeTooSoonException(nextAllowedAt)`, `ProposalNotFoundException`; `BondView.proposals: List<Proposal>`; `BondResponse.{deletionScheduledFor, pendingTimezoneChange, pendingDeletionRequest}`.

- [ ] **Step 1: Add the codes** to `ErrorCode`, each with its KDoc:

```kotlin
    /**
     * A proposal of that kind is already open on this bond (FR-027, FR-028).
     * The client's move is to show the pending one — `states.md` §8 draws it —
     * rather than to retry. 409.
     */
    PROPOSAL_PENDING,

    /**
     * The caller tried to confirm their own proposal. Two-party consent means
     * the *other* member agrees (BR-6, doc 04 §8.5); one person clicking twice
     * is not consent. 409.
     */
    PROPOSAL_NEEDS_OTHER_MEMBER,

    /**
     * FR-027's once-per-30-days rule on the anchor zone. A `409` rather than a
     * `429` — doc 06 §3.3 said `429` and is superseded (ADR-0030): a month is
     * not a rate limit a client should render as "please wait a moment", and the
     * detail names the date it becomes allowed.
     */
    TIMEZONE_CHANGE_TOO_SOON,
```

- [ ] **Step 2: Add the exceptions** to `BondErrors.kt`. `TimezoneChangeTooSoonException` takes the date and formats it into the detail (`DateTimeFormatter.ISO_LOCAL_DATE` in the bond's anchor zone — the date a person would see, not an instant), with a KDoc saying why naming it is safe: it is a fact about the caller's own bond.

- [ ] **Step 3: Teach `BondViews` about proposals.** One more batched query beside the invite one:

```kotlin
        val proposals = this.proposals.findAllLiveOf(bonds.map { it.id }, clock.instant()).groupBy { it.bondId }
```

  and `BondView` gains `val proposals: List<Proposal>`. Keep the batching — `GET /bonds` returns up to three bonds and this is the second query that would otherwise be an N+1.

- [ ] **Step 4: Add the three response fields** to `BondResponse`, exactly as spec §5.1 names them:

```kotlin
    /** FR-028: when the bond would be destroyed, if a deletion is pending. Null otherwise. */
    val deletionScheduledFor: Instant?,
    /** FR-027: the zone change waiting for the other member, if there is one. `states.md` §8 draws it. */
    val pendingTimezoneChange: PendingTimezoneChangeResponse?,
    /** FR-028: the deletion request waiting for the other member. */
    val pendingDeletionRequest: PendingDeletionRequestResponse?,
```

  with the two nested types carrying `proposedTimezone/proposedByMemberId/proposedAt/expiresAt` and `requestedByMemberId/requestedAt/expiresAt`. **Member ids, never user ids** — the same rule the members list follows. `states.md` §8: *"The pending state names the mechanism, not the person"*; these fields let the client name the member, which is what that screen does.

- [ ] **Step 5: Assert the additive shape.** In `BondsEndpointTest`, extend the create case: the three fields are present and null. Additive fields are the one API change that is *not* breaking, and a test saying so is what makes the claim in the PR checkable.

- [ ] **Step 6: Run and commit.** `./gradlew :modules:bond:test` — everything still green, since nothing yet produces a proposal.

---

### Task 4: The timezone: propose, confirm, cancel

**Files:**
- Create: `modules/bond/.../service/ChangeTimezone.kt`, `modules/bond/.../web/BondTimezoneController.kt`, `.../web/ChangeTimezoneRequest.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondTimezoneEndpointTest.kt` (new)

**Interfaces:**
- Produces: `ChangeTimezone.propose(membership, zone): BondView`, `.confirm(membership): BondView`, `.cancel(membership)`; routes `PATCH /api/v1/bonds/{bondId}/timezone`, `POST …/timezone/confirm`, `DELETE …/timezone`; controller methods `proposeBondTimezone`, `confirmBondTimezone`, `cancelBondTimezoneChange`.

- [ ] **Step 1: Write the failing endpoint test.** Cases, each named for the rule it holds:

```
a creator alone changes the zone at once, and no proposal is recorded          (PENDING_MEMBER, 200)
an active bond records a proposal instead, and the zone does not move yet      (200, pendingTimezoneChange set)
the other member confirms, and the zone moves                                   (200, pending cleared, timezoneChangedAt set)
the proposer cannot confirm their own proposal                                  (409 PROPOSAL_NEEDS_OTHER_MEMBER)
a second proposal while one is open is refused                                  (409 PROPOSAL_PENDING)
a proposal that has lapsed is not confirmable, and a fresh one may be made      (404 then 200 — the lazy close)
either member may cancel, and then a fresh proposal is allowed                  (204, then 200)
cancelling nothing is 404                                                       (404)
a change within thirty days is refused, and the detail names the date           (409 TIMEZONE_CHANGE_TOO_SOON)
the thirty days are re-checked at confirmation, not only at proposal            (409 at confirm)
a fixed-offset zone is 422 on the field                                         (422, "anchorTimezone")
an archived bond refuses all three                                              (409 BOND_ARCHIVED / 409 / 404)
a non-member gets the same 404 on all three                                     (404)
```

  The lapsing cases need the clock moved. The bond module's context has no `MutableClock` wired — check `BondTestApplication`; if the clock is real, move `expires_at` with `jdbc.update` as `InviteOneAnswerTest` does for invites, and say in a comment that the state is built rather than simulated.

- [ ] **Step 2: Watch it fail** (405s), then write the service:

```kotlin
/**
 * `PATCH /bonds/{bondId}/timezone`, `POST …/timezone/confirm`, `DELETE …/timezone`
 * (FR-027, ADR-0030).
 *
 * **Why this one change needs both members**, when B4's `PATCH /bonds/{bondId}`
 * needs neither: the anchor zone decides which day an entry belongs to, for both
 * of them (doc 04 §6). Moving it re-slices the other person's day boundaries, and
 * doc 04 §8.5 will not recompute a day that has already been opened — so a
 * unilateral change is a change to somebody else's past.
 *
 * Every path takes the bond's row lock first, which is this module's rule
 * (ADR-0028 §6a): each of these is a read-then-conditional-write, and the three
 * of them plus a leave can interleave.
 *
 * The 30-day rule is checked **twice** — at proposal and again at confirmation —
 * because seven days may pass in between and the window can close while a
 * proposal waits.
 */
```

  `propose(membership, zone)`:
  1. `bonds.lockBond`
  2. load, `!isOpen` → `BondArchivedException`
  3. `!bond.mayChangeTimezoneAt(now)` → `TimezoneChangeTooSoonException(bond.nextTimezoneChangeAt)`
  4. `proposals.closeLapsed(bondId, TIMEZONE_CHANGE, now)` — the lazy close
  5. `proposals.findLive(...) != null` → `ProposalPendingException`
  6. if `bond.status == PENDING_MEMBER` → `bonds.update(bond.withAnchorTimezone(zone, now))`, no proposal — spec §11 decision 6, and the KDoc says why (nobody to consent)
  7. else `proposals.insert(Proposal.timezoneChange(...))`
  8. re-read and return the view

  `confirm(membership)`:
  1. lock, load, `!isOpen` → archived
  2. `proposals.findLive(bondId, TIMEZONE_CHANGE, now)` ?: `ProposalNotFoundException`
  3. `proposal.proposedByMemberId == membership.memberId` → `ProposalNeedsOtherMemberException`
  4. `!bond.mayChangeTimezoneAt(now)` → too soon
  5. `!proposals.confirm(proposal.id, membership.memberId, now)` → `ProposalNotFoundException` (lost the race = it is no longer live, which is true)
  6. `bonds.update(bond.withAnchorTimezone(proposal.proposedZone(), now))`
  7. re-read, return

  `cancel(membership)`: lock, load (no `isOpen` check — a cancel is always allowed; there is nothing to protect), find live ?: 404, `proposals.cancel(id, now)` ?: 404, log.

- [ ] **Step 3: The request type.** `ChangeTimezoneRequest(@field:NotBlank @field:ValidRegionZone val anchorTimezone: String)` — the same constraint `CreateBondRequest` uses, so the field name in the `422` matches the one the create endpoint produces.

- [ ] **Step 4: The controller.** Its own file (`/api/v1/bonds/{bondId}/timezone`), guard first on every method, API-shaped method names. `PATCH` returns `200` + `ETag` (it returns a `BondResponse`, so B4's schema-keyed `ETag` rule covers it automatically); `DELETE` is `204` with `@ResponseStatus`.

  **Decide and record:** these are bond-scoped writes that change the bond row — do they require `If-Match`? **No.** ADR-0030 says why: the conditional update in B4 protects a *blind overwrite of fields the client last read*, whereas a proposal is a fresh intent about a single named value, and requiring the `ETag` here would make the three-step flow `states.md` §8 draws fail whenever the other member's reminder time had changed in between. State it in the ADR rather than leaving it to be inferred.

- [ ] **Step 5: Run, lint, commit.**

---

### Task 5: Deletion: request, confirm, cancel

**Files:**
- Create: `modules/bond/.../service/RequestDeletion.kt`, `modules/bond/.../web/BondDeletionController.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondDeletionEndpointTest.kt` (new), `DiscreetExitTest.kt` (+1 case)

**Interfaces:**
- Produces: `RequestDeletion.request(membership): BondView`, `.cancel(membership)`; routes `POST /api/v1/bonds/{bondId}/deletion-request` → `202`, `DELETE …/deletion-request` → `204`; controller methods `requestBondDeletion`, `cancelBondDeletion`.

- [ ] **Step 1: Write the failing endpoint test:**

```
the first request is 202 and records a pending request, and nothing is deleted
repeating it as the same member is an idempotent 202 and does not confirm it
the other member's request confirms it: PENDING_DELETION, deletionScheduledFor 30 days out
a PENDING_MEMBER bond's first request confirms itself (nobody to consent)
during the cooling-off every other write is 409 BOND_ARCHIVED                  (PATCH, invites, settings)
…and the bond is still readable by both                                        (200)
either member may cancel, and an ACTIVE bond returns to ACTIVE                 (204)
cancelling returns an ARCHIVED bond to ARCHIVED, not to life
cancelling nothing is 404
an archived bond refuses the request — whichever way it ended                  (409 BOND_ARCHIVED)
a non-member gets 404 on both routes
```

- [ ] **Step 2: The discreet-exit case**, added to `DiscreetExitTest` — this is the decision Daniel took, so it gets the test that holds it:

```kotlin
    @Test
    fun `a deletion request is refused the same way on a bond that was left and one that was blocked`() {
        // The spec had this refused only on a blocked bond, which is an oracle:
        // the blocked party would learn which happened by trying it once. Daniel
        // took the decision to refuse it on *any* archived bond, so the two stay
        // indistinguishable (doc 26 §2.1, ADR-0030). This is that decision's test.
        val left = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block") }

        val afterLeave = post(left.other, "/api/v1/bonds/${left.bondId}/deletion-request")
        val afterBlock = post(blocked.other, "/api/v1/bonds/${blocked.bondId}/deletion-request")

        afterLeave.status shouldBe 409
        afterBlock.status shouldBe afterLeave.status
        normalise(afterBlock.contentAsString) shouldBe normalise(afterLeave.contentAsString)
    }
```

- [ ] **Step 3: Write the service.** `request(membership)`:
  1. lock, load
  2. `status == PENDING_DELETION` → idempotent: return the view unchanged (both have asked; asking again changes nothing)
  3. `!isOpen` → `BondArchivedException` — **whichever way it ended** (ADR-0030, the decision above)
  4. `proposals.closeLapsed(bondId, DELETION, now)`
  5. live proposal?
     - proposed by the caller → idempotent `202`, view unchanged
     - proposed by the other member → `proposals.confirm(...)`; if it fails, somebody else just confirmed, so re-read and return; then `bonds.update(bond.requestDeletion(now))`
  6. no live proposal:
     - `bond.activeMembers.size == 1` (i.e. `PENDING_MEMBER`, nobody to consent) → insert a proposal already confirmed by the caller *and* `bonds.update(bond.requestDeletion(now))`, so the history says what happened rather than leaving a bond in `PENDING_DELETION` with no record
     - else insert a live proposal; the bond is unchanged and `pendingDeletionRequest` appears in the response
  7. re-read, return the view. `202` throughout: the work is scheduled, not done (Phase 5 does it).

  `cancel(membership)`: lock, load, **no `isOpen` check**, then:
  - a live `DELETION` proposal → cancel it → `204`
  - else `status == PENDING_DELETION` → `bonds.update(bond.cancelDeletion())` → `204`
  - else `ProposalNotFoundException`

- [ ] **Step 4: The controller**, `/api/v1/bonds/{bondId}/deletion-request`, guard first, `@ResponseStatus(HttpStatus.ACCEPTED)` on the `POST` (springdoc reads the annotation, not the entity — the B1 lesson) and `NO_CONTENT` on the `DELETE`.

- [ ] **Step 5: Run, lint, commit.**

---

### Task 6: What ending a bond owes a proposal, the fixtures, and the race

**Files:**
- Modify: `modules/bond/.../service/EndBond.kt`, `modules/bond/src/test/kotlin/com/moyi/bond/web/BondEndingEndpointTest.kt`, `BondCrossTenantTest.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/ProposalRaceTest.kt` (new)

- [ ] **Step 1: Pay ADR-0028's debt.** `EndBond.end` gains `proposals.cancelLiveOf(ended.id, now)` beside the invite revocation, with the KDoc naming the ADR that promised it. A leave or block with a live proposal must leave nothing open — a confirmation arriving on an archived bond would otherwise try to move the zone of a bond that has ended.

  Test it in `BondEndingEndpointTest`: propose a zone change, leave, and the proposal is gone from the response and from the table; and confirming afterwards is a `409 BOND_ARCHIVED`.

- [ ] **Step 2: Let the cross-tenant suite fail**, read the message, then add the five fixtures:

```kotlin
            // Slice B5. The PATCH needs a body; the rest take none.
            "PATCH /api/v1/bonds/{bondId}/timezone" to Fixture(body = """{"anchorTimezone":"Europe/London"}"""),
            "POST /api/v1/bonds/{bondId}/timezone/confirm" to Fixture(),
            "DELETE /api/v1/bonds/{bondId}/timezone" to Fixture(),
            "POST /api/v1/bonds/{bondId}/deletion-request" to Fixture(),
            "DELETE /api/v1/bonds/{bondId}/deletion-request" to Fixture(),
```

- [ ] **Step 3: The race test**, `ProposalRaceTest.kt`, with `InviteRaceTest`'s `inParallel`:

```
two members confirming one proposal at the same moment - the zone moves once   (200 + 404, one confirmed row, one timezoneChangedAt)
two proposals at the same moment - exactly one is open                         (200 + 409, one live row)
a confirm and a cancel at the same moment - one wins and the other is 404      (never both)
a confirm and a leave at the same moment - never a zone moved on an archived bond
```

  Repeat each a few times (B3's lesson: a narrow window needs more than one attempt), and be honest in the comments about what a mutation actually reproduces.

- [ ] **Step 4: Mutation-check** the three load-bearing pieces, recording each result:
  1. Remove `closeLapsed` from `propose` → the lapsed-then-fresh-proposal test must fail on the unique index.
  2. Remove the `proposedByMemberId == membership.memberId` check → "the proposer cannot confirm their own proposal" must fail.
  3. Replace `proposals.confirm`'s CAS with a plain update → `ProposalRaceTest`'s double-confirm must fail (and say how reliably).

- [ ] **Step 5: Commit.**

---

### Task 7: The contract

**Files:** `contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt`, `app/src/test/kotlin/com/moyi/app/OpenApiContractTest.kt`, `contracts/openapi.json`

- [ ] **Step 1: The failing expectations.** Add the three paths to the route list; add a test:

```kotlin
    @Test
    fun `the consent endpoints document their conflicts, and the deletion request its 202`() {
        val timezone = api.paths["/api/v1/bonds/{bondId}/timezone"]!!
        timezone.patch.responses.keys shouldContainAll listOf("200", "404", "409", "422")
        timezone.delete.responses shouldContainKey "204"
        api.paths["/api/v1/bonds/{bondId}/timezone/confirm"]!!.post.responses.keys shouldContainAll listOf("200", "404", "409")
        val deletion = api.paths["/api/v1/bonds/{bondId}/deletion-request"]!!
        deletion.post.responses.keys shouldContainAll listOf("202", "404", "409")
        deletion.delete.responses shouldContainKey "204"
        // The three new codes are in the one enum a client switches on.
        api.components.schemas["ProblemDetail"]!!.properties["code"]!!.enum.map { it.toString() } shouldContainAll
            listOf("PROPOSAL_PENDING", "PROPOSAL_NEEDS_OTHER_MEMBER", "TIMEZONE_CHANGE_TOO_SOON")
    }
```

- [ ] **Step 2: Extend `CONFLICTING_OPERATIONS`** with the four operation ids that answer `409` (`proposeBondTimezone`, `confirmBondTimezone`, `requestBondDeletion`, and `cancelBondTimezoneChange` only if it can — check; it cannot, so leave it out and let the test prove it).

- [ ] **Step 3: Regenerate and read the diff.** Expected: three new paths, five operations, one request schema, two nested response schemas, three new `BondResponse` fields, three new error codes. **Nothing removed or renamed.** Then `cp app/build/openapi/openapi.json contracts/openapi.json` and re-run.

- [ ] **Step 4: Commit.**

---

### Task 8: Verify by running, then the ADR, the corpus and the PR

- [ ] **Step 1: `./gradlew build`** — green, including JaCoCo and Konsist (both new services take a `Membership`).

- [ ] **Step 2: The smoke section**, after B4's. It is the one place the three-step flow is proved end to end with two real accounts:

```
two accounts pair; A proposes Europe/London; the bond still says Africa/Lagos and shows the pending change
A confirming their own proposal is 409 PROPOSAL_NEEDS_OTHER_MEMBER
a second proposal is 409 PROPOSAL_PENDING
B confirms: 200, the zone is Europe/London, the pending field is null
a further proposal is 409 TIMEZONE_CHANGE_TOO_SOON and the detail names a date
B cancels a fresh proposal on another bond: 204, then a new one is 200
A requests deletion: 202, the bond is still ACTIVE, pendingDeletionRequest set
A repeating it is 202 and changes nothing
B requests it: 202, status PENDING_DELETION, deletionScheduledFor set 30 days out
every other write during the cooling-off is 409 BOND_ARCHIVED (PATCH, invite, settings PUT)
both members still read the bond: 200
A cancels: 204, status ACTIVE again
a stranger gets 404 on all five routes
an archived bond refuses a deletion request whichever way it ended (two probes, byte-identical)
no proposal id or zone appears in the log in a way that names a person
```

- [ ] **Step 3: Run it.** `docker compose up -d`, `./gradlew :app:bootJar`, `PORT=18086 scripts/smoke.sh --no-build`. 0 failures, record the count.

- [ ] **Step 4: Write ADR-0030** (`adr/0030-two-party-consent-as-one-mechanism.md`), carrying:
  - **Context** — FR-027, FR-028, BR-6, doc 04 §8.5, doc 26 §4, `states.md` §8/§9, and that this is the last slice of Phase 2.
  - **Decision** — one table for both kinds; the 7-day lapse as a predicate; one open proposal per kind held by a partial index; confirmation by the *other* member only, as a CAS; either may cancel; the 30-day rule checked twice and answered `409` (superseding doc 06 §3.3's `429`); `PENDING_MEMBER` self-confirms both kinds; `PENDING_DELETION` is not `isOpen` but cancelling ignores that; cancelling restores `ACTIVE` or `ARCHIVED`; ending a bond cancels live proposals (ADR-0028's debt paid); no `If-Match` on these routes and why.
  - **The two decisions taken with Daniel**, each with the reasoning: the deletion request refused on *any* archived bond (the oracle), and the lazy close of a lapsed proposal (the index cannot ask the time).
  - **Consequences** — three new error codes, so breaking; Phase 5's deletion job reads `PENDING_DELETION` + `deletion_requested_at`; Phase 3 inherits BR-6's "never retroactively" and must not recompute an opened day; Phase 4 owes the notification a pending proposal implies; a member who left cannot start a mutual deletion (and what they can do instead).
  - **Alternatives** — two tables; a status column instead of the lapse predicate; reaping on a schedule; `410` for a lapsed proposal (rejected: it says the proposal was once real, the FR-024 lesson); allowing self-confirmation after a delay.

- [ ] **Step 5: The corpus**, on `docs/bonds-phase-2`: copy the ADR; amend doc 06 §3.3's five rows (including the `429` → `409` correction and the archived-deletion decision), doc 07 §2 (the `bond_proposals` table as built), doc 00's amendment log, `states.md` §8 (the pending state is now real; the `412` gap from B4 still stands) and §9 (the cooling-off and what cancelling restores). Commit there with the trailer.

- [ ] **Step 6: The learning-log entry** — Expected / Reality / Wrong about, naming the mutation results honestly and whatever the tests corrected.

- [ ] **Step 7: `.claude/HANDOVER.md`** — the B5 row, the counts, §4 rewritten: **Phase 2 is complete**, so the next step is M3's verification and then Phase 3 (the daily loop, doc 15 §4), with the three Phase 2 leftovers named (the `412` screen gap, FR-029a's withdraw-my-entries, and the KMP consumer R-19 that has had no blocker since Phase 1).

- [ ] **Step 8: The PR** — `gh pr create --base feat/bond-settings`, labelled `breaking-api-change`, with the template's ten questions, the concept brief (two-party consent as a state machine; compare-and-set as consent; lazy versus scheduled expiry; and the market gaps: no outbox for the notification, no scheduler for the deletion job, no audit log beyond the proposal rows), and the Figma table for `states.md` §8's three-step flow and §9's cooling-off screens. **Do not merge.**

---

## Done when

1. `./gradlew build` green; `scripts/smoke.sh` green with the B5 section and its probe count recorded.
2. Every status in §5.2 rows #13–#17 has a test, including both `PENDING_MEMBER` shortcuts, both idempotent repeats, and the 30-day rule at proposal *and* confirmation.
3. `DiscreetExitTest` holds the archived-deletion decision: the refusal is byte-identical on a bond that was left and one that was blocked.
4. `ProposalRaceTest` proves a proposal is confirmed once, opened once, and never applied to a bond that has ended.
5. All five routes in `BondCrossTenantTest.fixtures`, added after watching the suite fail naming them.
6. Three mutations run and recorded in the learning log.
7. ADR-0030 here and in `../documents/adr/`; doc 06 §3.3, doc 07 §2, doc 00 and `states.md` §8/§9 amended; a learning-log entry; `.claude/HANDOVER.md` current and pointing at Phase 3.
8. PR opened, labelled, with the template — and **not merged**.
