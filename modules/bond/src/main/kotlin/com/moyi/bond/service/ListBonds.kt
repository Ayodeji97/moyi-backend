package com.moyi.bond.service

import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.BondStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `GET /bonds` (doc 06 §3.3): every bond the caller holds a membership row in,
 * left or not, newest first.
 *
 * No guard here, and none needed: "my bonds" is scoped by the caller's own id
 * rather than by a bond id, so there is nothing for a caller to name that is
 * not already theirs. That is also why this is the one bond-scoped-looking
 * service function that takes a [UserId] — the Konsist rule is about
 * functions naming a *bond*.
 *
 * Doc 06 promises streak and today's status on this list as well. They arrive
 * with Phase 3, as additional fields on the same response.
 */
@Service
internal class ListBonds(
    private val bonds: BondStore,
    private val views: BondViews,
) {
    @Transactional(readOnly = true)
    fun forUser(userId: UserId): List<BondView> = views.ofAll(bonds.findAllByMember(userId), userId)
}
