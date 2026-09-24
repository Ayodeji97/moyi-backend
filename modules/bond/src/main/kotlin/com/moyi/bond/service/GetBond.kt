package com.moyi.bond.service

import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BondStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `GET /bonds/{bondId}` (doc 06 §3.3).
 *
 * Takes a [Membership] rather than a bond id: by the time this runs the guard
 * has already said yes, and the parameter is the proof.
 *
 * It loads the bond a second time, after the guard's own load. That is two
 * queries where a cleverer arrangement would use one, and it is deliberate for
 * now (ADR-0026): the guard and the service stay independent, which is what
 * lets every later bond-scoped service take the same argument without
 * knowing how the guard got it. The p95 on real hardware is what decides
 * whether it stays that way.
 */
@Service
internal class GetBond(
    private val bonds: BondStore,
    private val views: BondViews,
) {
    @Transactional(readOnly = true)
    fun view(membership: Membership): BondView {
        // Between the guard and here, a concurrent request could have removed
        // the caller's membership. A 404 is then the correct answer, not a
        // 500 — they are, at this instant, not a member.
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        return views.of(bond, membership.userId)
    }
}
