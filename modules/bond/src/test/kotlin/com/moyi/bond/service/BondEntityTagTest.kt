package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

internal class BondEntityTagTest {
    private val now = Instant.parse("2026-09-28T20:00:00Z")
    private val lagos = RegionZone.of("Africa/Lagos")

    @Test
    fun `two views that differ only in how a proposal ended have different entity tags`() {
        // A proposal closing moves no bond row and no live proposal, so without `closedProposals` in the
        // digest a client holding the old tag is answered 304 and never learns it ended.
        val creator = UserId(UUID.randomUUID())
        val bond =
            Bond.create(
                BondId(UUID.randomUUID()),
                MemberId(UUID.randomUUID()),
                BondDraft(creator, BondType.COUPLE, "Us", lagos, null, null),
                now,
            )
        val member = bond.members.single()
        val view = BondView(bond = bond, members = listOf(MemberView(member, "Ada")), me = member, invite = null)
        val ended =
            Proposal
                .timezoneChange(ProposalId(UUID.randomUUID()), bond.id, RegionZone.of("Europe/London"), member.id, now)
                .let {
                    it.copy(
                        cancelledAt = now.plusSeconds(1),
                        cancelledBy = member.id,
                        cancelCause = com.moyi.bond.domain.CancelCause.MEMBER,
                    )
                }

        view.copy(closedProposals = listOf(ended)).entityTag() shouldNotBe view.entityTag()
    }
}
