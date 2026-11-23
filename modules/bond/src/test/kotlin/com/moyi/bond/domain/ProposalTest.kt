package com.moyi.bond.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * How a proposal ended, read in the one place that reads it (issue #205, ADR-0069 decision 6).
 *
 * Declined and withdrawn are not stored: a `MEMBER` cancel by the proposer is a withdrawal, by
 * anybody else a refusal. A proposal closed by the bond ending names nobody, because naming the
 * leaver or blocker would let the other member learn which of the two it was (T-09, doc 26 §2.1).
 */
internal class ProposalTest {
    private val now = Instant.parse("2026-11-16T20:00:00Z")
    private val bondId = BondId(UUID.randomUUID())
    private val proposer = MemberId(UUID.randomUUID())
    private val other = MemberId(UUID.randomUUID())
    private val open = Proposal.deletion(ProposalId(UUID.randomUUID()), bondId, proposer, now)
    private val later = now.plus(Duration.ofHours(1))

    private fun cancelled(
        cause: CancelCause?,
        by: MemberId?,
    ) = open.copy(cancelledAt = later, cancelCause = cause, cancelledBy = by)

    @Test
    fun `a confirmed proposal is accepted, closed by the member who confirmed it`() {
        val accepted = open.copy(confirmedAt = later, confirmedByMemberId = other)

        accepted.outcome shouldBe ProposalOutcome.ACCEPTED
        accepted.closedBy shouldBe other
    }

    @Test
    fun `the proposer calling it off is a withdrawal, the other member a refusal`() {
        val withdrawn = cancelled(CancelCause.MEMBER, proposer)
        val declined = cancelled(CancelCause.MEMBER, other)

        withdrawn.outcome shouldBe ProposalOutcome.WITHDRAWN
        withdrawn.closedBy shouldBe proposer
        declined.outcome shouldBe ProposalOutcome.DECLINED
        declined.closedBy shouldBe other
    }

    @Test
    fun `a lapse and a bond ending name nobody`() {
        val lapsed = cancelled(CancelCause.LAPSED, null)
        val ended = cancelled(CancelCause.BOND_ENDED, null)

        lapsed.outcome shouldBe ProposalOutcome.LAPSED
        lapsed.closedBy.shouldBeNull()
        ended.outcome shouldBe ProposalOutcome.BOND_ENDED
        ended.closedBy.shouldBeNull()
    }

    @Test
    fun `a row cancelled before anybody recorded why is unrecorded, not a guess`() {
        val unrecorded = cancelled(null, null)

        unrecorded.outcome shouldBe ProposalOutcome.UNRECORDED
        unrecorded.closedBy.shouldBeNull()
    }

    @Test
    fun `a row still open, as the ended-read hands over only once it has lapsed, reads lapsed`() {
        // The domain asks no clock: an open row means "lapsed" only because the read that
        // returns ended proposals returns an open one only past its expiry.
        open.outcome shouldBe ProposalOutcome.LAPSED
        open.closedBy.shouldBeNull()
    }

    @Test
    fun `the requires mirror V70's CHECKs`() {
        // A member is named exactly when a member called it off.
        shouldThrow<IllegalArgumentException> { cancelled(CancelCause.MEMBER, null) }
        shouldThrow<IllegalArgumentException> { cancelled(CancelCause.BOND_ENDED, other) }
        shouldThrow<IllegalArgumentException> { cancelled(CancelCause.LAPSED, proposer) }
        shouldThrow<IllegalArgumentException> { cancelled(null, other) }
        // A cause only on a cancelled row.
        shouldThrow<IllegalArgumentException> { open.copy(cancelCause = CancelCause.LAPSED) }
        shouldThrow<IllegalArgumentException> { open.copy(cancelCause = CancelCause.MEMBER, cancelledBy = other) }
    }
}
