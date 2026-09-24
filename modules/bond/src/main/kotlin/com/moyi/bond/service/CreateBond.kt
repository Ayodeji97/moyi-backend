package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.InviteStore
import com.moyi.identity.api.UserDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `POST /bonds` (FR-020): a verified user's new bond, waiting for its second
 * member, with the invite that will bring them (doc 06 §3.3).
 *
 * Two checks, in this order and for a reason. Verification first (FR-002),
 * because it is a fact already in hand and an unverified caller should pay for
 * nothing further. Then the FR-025 count, under a per-user advisory lock —
 * without the lock, two concurrent creates both count two bonds, both decide
 * there is room, and the user ends with four.
 */
@Service
internal class CreateBond(
    private val users: UserDirectory,
    private val bonds: BondStore,
    private val invites: InviteStore,
    private val factory: BondFactory,
    private val views: BondViews,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws EmailNotVerifiedException the caller has not confirmed their address (FR-002), or no longer exists
     * @throws BondLimitReachedException the caller already has three open bonds (FR-025)
     */
    @Transactional
    fun create(draft: BondDraft): BondView {
        val creator = users.find(draft.creator.value)
        if (creator == null || !creator.emailVerified) throw EmailNotVerifiedException()

        bonds.lockBondsOf(draft.creator)
        if (bonds.countOpenBondsOf(draft.creator) >= Bond.MAX_OPEN_BONDS_PER_USER) throw BondLimitReachedException()

        val (bond, invite) = factory.create(draft)
        // One transaction, two stores: the bond and the invite that will bring
        // its second member are created together (doc 04 §2's aggregate), and
        // it is this boundary that makes that atomic rather than a shared class.
        bonds.insert(bond)
        invites.insert(invite)
        // Ids only. The name is the couple's words and the code is a
        // credential until it is spent (doc 18 §9).
        log.info("Bond {} created by user {}", bond.id.value, draft.creator.value)
        return views.of(bond, draft.creator)
    }
}
