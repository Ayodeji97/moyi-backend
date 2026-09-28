package com.moyi.bond.infra.database

import com.moyi.bond.domain.Member
import org.springframework.stereotype.Component

/**
 * A member's own row, spoken in domain terms.
 *
 * Its own store rather than two more methods on [BondStore], and the split is
 * the same one that moved invites out in slice B2 — detekt's method limit asked
 * the question and the answer was a real boundary both times. What lives here is
 * what belongs to **one member and nobody else**: their reminder time, their
 * quiet hours, the nickname only they see (`states.md` §8). None of it is part
 * of the bond the two of them share, which is why writing it must not touch the
 * bond row or move the `ETag` the other member is holding.
 *
 * `addMember` stays in [BondStore] on purpose: it writes a member row *and* the
 * bond's status, which is an aggregate transition rather than a settings change.
 *
 * Not transactional itself — the boundary is the caller's (doc 18 §4).
 */
@Component
internal class MemberStore(
    private val members: BondMemberRepository,
) {
    /**
     * Replaces one member's settings.
     *
     * Reads the row through the bond, because that is the only finder this
     * repository has that is scoped the way doc 05 §5.5 requires: there is no
     * `findById(memberId)` here, so a caller who cannot say which bond they mean
     * cannot ask. The caller has already been through `BondAccessGuard`, so the
     * bond id it passes is one it is entitled to.
     */
    fun update(member: Member) {
        val entity =
            members.findAllByBondId(member.bondId.value).firstOrNull { it.getId() == member.id.value }
                ?: error("cannot update a member row that does not exist")
        member.applyTo(entity)
        members.saveAll(listOf(entity))
    }
}
