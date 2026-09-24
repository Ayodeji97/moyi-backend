package com.moyi.bond.infra.database

import com.moyi.bond.domain.Block
import com.moyi.bond.domain.UserId
import com.moyi.common.core.IdGenerator
import org.springframework.stereotype.Component

/**
 * Blocks, spoken in domain terms.
 *
 * Its own store rather than a corner of [BondStore], because a block is not
 * part of the Bond aggregate: it is a statement by one account about another
 * that happens to have been made in a bond, and it outlives that bond. Slice
 * B3 writes them; B2 asks the one question at accept.
 */
@Component
internal class BlockStore(
    private val blocks: BlockRepository,
    private val ids: IdGenerator,
) {
    fun insert(block: Block) {
        blocks.save(block.toEntity(ids.timeOrdered()))
    }

    /**
     * Is there a block in **either direction** between this user and any of
     * these? Empty [others] is `false` without a query — a bond with no other
     * members cannot have a block in it.
     */
    fun existsBetween(
        userId: UserId,
        others: Collection<UserId>,
    ): Boolean {
        if (others.isEmpty()) return false
        return blocks.existsBetween(userId.value, others.map { it.value })
    }
}
