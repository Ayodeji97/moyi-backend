package com.moyi.bond.domain

import java.time.Duration
import java.time.Instant

/**
 * What kind of thing is being proposed (FR-027, FR-028, FR-204).
 *
 * Each is a change the corpus says one member may not make alone: moving the
 * anchor timezone decides which day an entry belongs to for **both** of them
 * (doc 04 §6), deleting the bond destroys what the other person wrote, and
 * turning semantic search on sends what both of them wrote to an embedding
 * provider (ADR-0054). Turning it *off* is one member's call and no proposal.
 */
internal enum class ProposalKind { TIMEZONE_CHANGE, DELETION, SEMANTIC_SEARCH_ON }

/**
 * Why a proposal was called off, as V70 stores it (issue #205, ADR-0069 decision 6).
 *
 * Three causes, not six outcomes: who called it off is a second column, and whether that was
 * a withdrawal or a refusal is read from it by [Proposal.outcome], never stored.
 */
internal enum class CancelCause {
    /** A member called it off; [Proposal.cancelledBy] names them. */
    MEMBER,

    /** Nobody answered within [Proposal.TTL], and something then closed the row. */
    LAPSED,

    /**
     * The bond ended (`EndBond`) while it was open. Names nobody: the leaver or blocker named here
     * would turn "declined" into a way to learn who left (T-09, doc 26 §2.1).
     */
    BOND_ENDED,
}

/** How a proposal ended, for the member reading it (spec B14). Derived by [Proposal.outcome] alone. */
internal enum class ProposalOutcome { ACCEPTED, DECLINED, WITHDRAWN, LAPSED, BOND_ENDED, UNRECORDED }

/**
 * A change one member has asked for and the other has not yet agreed to
 * (FR-027, FR-028, BR-6, ADR-0030).
 *
 * **One type for every kind, because the rules are one set of rules:** seven
 * days to answer, only the *other* member may confirm, either may cancel, and
 * an unanswered one lapses rather than nagging. [payload] is the only thing
 * that differs — a zone id for a timezone change, nothing for a deletion or
 * for turning semantic search on.
 *
 * Lapsing is a **predicate, not a state**: the clock is asked at read time, in
 * `BondProposalRepository.findLive`, and nothing ever writes a row to say "this
 * expired". The predicate is stated **there and only there** (its complement,
 * `findLatestEndedOf`, is written beside it and says so) — an unused copy of
 * it here read well and was a second definition of one rule, which is the shape
 * of every drift defect this module has had. `states.md` §8 words it for a
 * person — *"lapses in seven days if not"* — and a `confirmedAt` still null
 * past [expiresAt] is exactly that sentence. The one place the absence of a
 * state costs something is V10's partial unique index, which cannot ask what
 * time it is; proposing closes a lapsed row when it needs the slot.
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
    val cancelledBy: MemberId?,
    val cancelCause: CancelCause?,
) {
    init {
        // Each mirrors a CHECK in V10, for the reason V9 gives: the database
        // constraint is the one that cannot be bypassed, this one is the one
        // that fails in a layer able to explain itself.
        require((confirmedAt == null) == (confirmedByMemberId == null)) {
            "a confirmation is a member and a time, together or not at all"
        }
        require(confirmedAt == null || cancelledAt == null) {
            "a proposal is confirmed or cancelled, never both"
        }
        require(kind != ProposalKind.TIMEZONE_CHANGE || payload != null) {
            "a timezone proposal carries the zone it proposes"
        }
        require(expiresAt.isAfter(proposedAt)) { "a proposal expires after it is made" }
        // And these mirror V70's.
        require(cancelCause == null || cancelledAt != null) { "a cause only on a cancelled proposal" }
        require((cancelledBy != null) == (cancelCause == CancelCause.MEMBER)) {
            "a member is named exactly when a member called it off, never for a bond that ended"
        }
    }

    /**
     * How it ended: the one place declined and withdrawn are told apart (issue #205).
     *
     * **Meaningful only for an ended proposal**: one confirmed or cancelled, or one handed over
     * by `ProposalStore.findLatestEndedOf`. The domain asks no clock, so a row neither confirmed
     * nor cancelled reads [ProposalOutcome.LAPSED], which is true exactly when that read returned
     * it (it returns an open row only past [expiresAt]); for a live proposal the value means
     * nothing and no caller asks.
     *
     * A row cancelled before V70 whose cause could not be known reads
     * [ProposalOutcome.UNRECORDED], never a guess: a wrong "declined" is worse than "unknown".
     */
    val outcome: ProposalOutcome
        get() =
            when {
                confirmedAt != null -> {
                    ProposalOutcome.ACCEPTED
                }

                cancelledAt == null -> {
                    ProposalOutcome.LAPSED
                }

                else -> {
                    when (cancelCause) {
                        CancelCause.MEMBER -> {
                            if (cancelledBy == proposedByMemberId) ProposalOutcome.WITHDRAWN else ProposalOutcome.DECLINED
                        }

                        CancelCause.LAPSED -> {
                            ProposalOutcome.LAPSED
                        }

                        CancelCause.BOND_ENDED -> {
                            ProposalOutcome.BOND_ENDED
                        }

                        null -> {
                            ProposalOutcome.UNRECORDED
                        }
                    }
                }
            }

    /**
     * When it ended, for an ended proposal. **A lapse is dated by its expiry, whether or not
     * something has since closed the row**: `closeLapsed` stamps `cancelledAt = now`, and an
     * entry whose date (and so its ETag) jumped then would read as changed when nothing was.
     */
    val closedAt: Instant
        get() = if (outcome == ProposalOutcome.LAPSED) expiresAt else requireNotNull(confirmedAt ?: cancelledAt)

    /** The member who confirmed it or called it off; nobody for a lapse, a bond ending, or an unrecorded cause. */
    val closedBy: MemberId? get() = confirmedByMemberId ?: cancelledBy

    /**
     * The zone this proposes.
     *
     * `RegionZone.of` re-validates on the way out of the payload, as the
     * mappers do for the stored anchor: a zone the JDK's tzdb has since dropped
     * fails loudly here rather than silently filing somebody's day under the
     * wrong date (doc 04 §6).
     */
    fun proposedZone(): RegionZone {
        check(kind == ProposalKind.TIMEZONE_CHANGE) { "only a timezone proposal names a zone" }
        return RegionZone.of(requireNotNull(payload) { "a timezone proposal carries the zone it proposes" })
    }

    companion object {
        /** FR-027: seven days to answer, and `states.md` §8 says so on the pending screen. */
        val TTL: Duration = Duration.ofDays(7)

        /**
         * The factories build the object directly rather than through a shared
         * private helper: detekt's parameter limit is six and the helper needed
         * seven, and a data class's own constructor is exempt precisely because
         * named arguments are readable at any width.
         */
        fun timezoneChange(
            id: ProposalId,
            bondId: BondId,
            zone: RegionZone,
            proposedBy: MemberId,
            now: Instant,
        ): Proposal =
            Proposal(
                id = id,
                bondId = bondId,
                kind = ProposalKind.TIMEZONE_CHANGE,
                payload = zone.id,
                proposedByMemberId = proposedBy,
                proposedAt = now,
                expiresAt = now.plus(TTL),
                confirmedByMemberId = null,
                confirmedAt = null,
                cancelledAt = null,
                cancelledBy = null,
                cancelCause = null,
            )

        fun deletion(
            id: ProposalId,
            bondId: BondId,
            proposedBy: MemberId,
            now: Instant,
        ): Proposal =
            Proposal(
                id = id,
                bondId = bondId,
                kind = ProposalKind.DELETION,
                payload = null,
                proposedByMemberId = proposedBy,
                proposedAt = now,
                expiresAt = now.plus(TTL),
                confirmedByMemberId = null,
                confirmedAt = null,
                cancelledAt = null,
                cancelledBy = null,
                cancelCause = null,
            )

        /** FR-204: one member asks for semantic search back on; the other's agreement applies it. */
        fun semanticSearchOn(
            id: ProposalId,
            bondId: BondId,
            proposedBy: MemberId,
            now: Instant,
        ): Proposal =
            Proposal(
                id = id,
                bondId = bondId,
                kind = ProposalKind.SEMANTIC_SEARCH_ON,
                payload = null,
                proposedByMemberId = proposedBy,
                proposedAt = now,
                expiresAt = now.plus(TTL),
                confirmedByMemberId = null,
                confirmedAt = null,
                cancelledAt = null,
                cancelledBy = null,
                cancelCause = null,
            )
    }
}
