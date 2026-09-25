package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.Invite
import com.moyi.bond.domain.InviteCodes
import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.MemberId
import com.moyi.common.core.IdGenerator
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * A new bond with the invite that will bring its second member.
 *
 * A factory in the pattern's proper sense: the aggregate needs three things
 * from outside itself — ids, a code, and the time — and gathering them here
 * is what lets [Bond.create] stay a pure function of its inputs, testable
 * without Spring, a clock or a random source.
 *
 * All three ids are time-ordered (UUID v7). None of them is a secret, so the
 * creation time a v7 embeds is not something to hide, and inserts land at the
 * right-hand edge of the index instead of scattering. Doc 06 §1 reserves v4
 * for entry and media ids, where BR-8 suppresses exactly that metadata.
 */
@Component
internal class BondFactory(
    private val ids: IdGenerator,
    private val codes: InviteCodes,
    private val clock: Clock,
) {
    fun create(draft: BondDraft): NewBond {
        val now = clock.instant()
        val bond = Bond.create(BondId(ids.timeOrdered()), MemberId(ids.timeOrdered()), draft, now)
        val invite = Invite.issue(InviteId(ids.timeOrdered()), bond.id, codes.next(), bond.members.single().id, now)
        return NewBond(bond, invite)
    }
}

/** A bond and its first invite, which are created together and inserted together. */
internal data class NewBond(
    val bond: Bond,
    val invite: Invite,
)
