package com.moyi.bond.service

import com.moyi.bond.domain.UserId
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.identity.api.AccountDeletions
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * `bond`'s share of an account deletion (FR-008, ADR-0058 decision 7): every bond the person
 * is still in ends as their leaving would end it ([EndBond.endEveryBondOf]).
 *
 * An event and not a call, because identity carries the deletion out and `bond` depends on
 * identity; a call back would be a cycle. Delivered at least once and in no order with the
 * other modules' consumers of the same event, none of which needs this to have run first: the
 * member rows stay, so `gratitude` finds the person's memberships either way.
 */
@Component
internal class EndDeletedAccountBonds(
    private val endBond: EndBond,
) : EventConsumer {
    /** Never to be changed: delivery state is kept under it. */
    override val id = "bond.account-deletion"
    override val eventTypes = setOf(EVENT)
    override val startFrom = StartFrom.BEGINNING

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val userId = checkNotNull(event.references[USER_ID]) { "An $EVENT event carries no '$USER_ID'." }
        endBond.endEveryBondOf(UserId(userId))
    }

    private companion object {
        const val EVENT = AccountDeletions.EVENT
        const val USER_ID = AccountDeletions.USER_ID
    }
}
