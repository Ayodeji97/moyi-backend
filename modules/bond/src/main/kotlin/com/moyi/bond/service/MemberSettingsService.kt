package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.Member
import com.moyi.bond.domain.MemberSettings
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.MemberStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `GET` and `PUT /bonds/{bondId}/members/me/settings` (doc 06 §3.3,
 * `states.md` §8).
 *
 * **No `If-Match` here, and that is a decision rather than an omission**
 * (ADR-0029): the row belongs to one member and nobody else can write it, so
 * there is no update for a concurrent writer to lose. Requiring a condition
 * where nothing can conflict is ceremony, and ceremony teaches clients to send
 * headers they do not mean.
 *
 * `me` is the only member this can name — there is no member id in the route —
 * so "never show the other member's settings" is not a check that could be
 * forgotten but the absence of a way to ask.
 */
@Service
internal class MemberSettingsService(
    private val bonds: BondStore,
    private val members: MemberStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Reads on an archived bond too: `states.md` §9 keeps the record open to
     * both members, and a reminder setting is part of what they can see.
     *
     * @throws BondNotFoundException the caller's membership went away between the guard and here
     */
    @Transactional(readOnly = true)
    fun of(membership: Membership): Member = memberIn(bondOf(membership), membership)

    /**
     * Replaces all five fields (a `PUT`).
     *
     * @throws BondArchivedException the bond has ended — writes stop, reads do
     *   not (ADR-0028)
     */
    @Transactional
    fun replace(
        membership: Membership,
        settings: MemberSettings,
    ): Member {
        val bond = bondOf(membership)
        if (!bond.isOpen) throw BondArchivedException()
        val updated = memberIn(bond, membership).withSettings(settings)
        members.update(updated)
        // The bond, and nothing else. Not the user, not the values: when
        // somebody is asleep is personal data (doc 18 §5).
        log.info("Member settings updated in bond {}", membership.bondId.value)
        return updated
    }

    /**
     * The bond, or the 404 the guard would have given had the membership gone
     * away in between. Two of these rather than one long method because detekt
     * counts `throw`s per function, and because "which of the two is missing"
     * is a genuinely different question each time.
     */
    private fun bondOf(membership: Membership): Bond =
        bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()

    private fun memberIn(
        bond: Bond,
        membership: Membership,
    ): Member = bond.memberOf(membership.userId) ?: throw BondNotFoundException()
}
