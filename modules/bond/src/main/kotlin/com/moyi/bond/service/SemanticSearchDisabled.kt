package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.common.events.OutboxEvent
import java.time.Instant

/**
 * A member turned the bond's semantic search off (FR-204, ADR-0054).
 *
 * `bond` cannot call `gratitude`, which depends on it, so this is how the bond's
 * vectors get deleted: the same transaction that sets the column false appends
 * this, and `gratitude`'s consumer removes the rows. References are ids only;
 * nothing a person wrote travels in an outbox payload.
 */
internal object SemanticSearchDisabled {
    const val TYPE = "SemanticSearchDisabled"
    const val BOND_ID = "bondId"

    /**
     * Takes the [Bond], as `MemberJoined.of` takes the member: a `bond.service`
     * function with a bare `BondId` and no `Membership` is one a controller could
     * reach past the guard, and `ArchitectureTest` refuses it.
     */
    fun of(
        bond: Bond,
        at: Instant,
    ): OutboxEvent =
        OutboxEvent(
            aggregateType = "Bond",
            aggregateId = bond.id.value,
            eventType = TYPE,
            references = mapOf(BOND_ID to bond.id.value),
            occurredAt = at,
        )
}
