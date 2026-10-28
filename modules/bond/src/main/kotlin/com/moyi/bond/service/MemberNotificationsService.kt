package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.MemberNotifications
import com.moyi.notification.api.NotificationPreferences
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `GET` and `PUT /bonds/{bondId}/members/me/notifications` (spec §4 N2, D-2,
 * ADR-0045): which kinds of push this member wants in this bond.
 *
 * **Its own resource, not three more fields on the settings `PUT`.** That
 * `PUT` replaces, and an absent field there is a cleared one (doc 06:111), so
 * adding the toggles to it would mute every category for every client that
 * does not yet send them. Here an absent toggle keeps its value.
 *
 * The same posture as [MemberSettingsService], for the same reasons: `me` is
 * the only member the route can name, there is no `If-Match` because nobody
 * else can write the row (ADR-0029), the bond lock serialises a change with
 * leave and block, and an ended bond can be read but not changed (ADR-0028).
 */
@Service
internal class MemberNotificationsService(
    private val bonds: BondStore,
    private val notifications: MemberNotifications,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Readable on an archived bond too, as the rest of a member's settings are
     * (`states.md` §9).
     *
     * @throws BondNotFoundException the membership went away between the guard and here
     */
    @Transactional(readOnly = true)
    fun of(membership: Membership): NotificationPreferences {
        bondOf(membership)
        return notifications.of(membership.memberId.value) ?: throw BondNotFoundException()
    }

    /**
     * Applies [change] to what the member has now, under the bond lock.
     *
     * @throws BondArchivedException the bond has ended: writes stop, reads do not (ADR-0028)
     */
    @Transactional
    fun change(
        membership: Membership,
        change: (NotificationPreferences) -> NotificationPreferences,
    ): NotificationPreferences {
        bonds.lockBond(membership.bondId)
        if (!bondOf(membership).isOpen) throw BondArchivedException()
        val current = notifications.of(membership.memberId.value) ?: throw BondNotFoundException()
        val updated = change(current)
        notifications.replace(membership.memberId.value, updated)
        // The bond, and nothing else: which pushes somebody wants is theirs.
        log.info("Member notification preferences updated in bond {}", membership.bondId.value)
        return updated
    }

    private fun bondOf(membership: Membership): Bond =
        bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
}
