package com.moyi.bond.domain

import java.time.Duration
import java.time.Instant

/**
 * What kind of thing is being proposed (FR-027, FR-028).
 *
 * Both are changes the corpus says one member may not make alone: moving the
 * anchor timezone decides which day an entry belongs to for **both** of them
 * (doc 04 §6), and deleting the bond destroys what the other person wrote.
 */
internal enum class ProposalKind { TIMEZONE_CHANGE, DELETION }

/**
 * A change one member has asked for and the other has not yet agreed to
 * (FR-027, FR-028, BR-6, ADR-0030).
 *
 * **One type for both kinds, because the rules are one set of rules:** seven
 * days to answer, only the *other* member may confirm, either may cancel, and
 * an unanswered one lapses rather than nagging. [payload] is the only thing
 * that differs — a zone id for a timezone change, nothing for a deletion.
 *
 * Lapsing is a **predicate, not a state**: [isLive] asks the clock, and nothing
 * ever writes a row to say "this expired". `states.md` §8 words it for a
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
    }

    /** Open, and not yet lapsed — the only kind of proposal any read acts on. */
    fun isLive(now: Instant): Boolean = confirmedAt == null && cancelledAt == null && expiresAt.isAfter(now)

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
         * Both factories build the object directly rather than through a shared
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
            )
    }
}
