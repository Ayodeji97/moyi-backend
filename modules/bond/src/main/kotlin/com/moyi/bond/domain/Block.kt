package com.moyi.bond.domain

import java.time.Instant

/**
 * One account preventing another from reaching it again (FR-029).
 *
 * Written by slice B3's block, and **read by B2's accept, in both
 * directions**: FR-029 says a block "prevents any further contact or
 * invitation between those accounts", and which of the two happens to be
 * holding a code is not something the rule should depend on.
 *
 * Scoped to a bond, because FR-025 permits three of them and nothing stops
 * the same two people sharing two — blocking someone in one is not a
 * statement about another (doc 07 §2).
 */
internal data class Block(
    val blockerUserId: UserId,
    val blockedUserId: UserId,
    val bondId: BondId,
    val createdAt: Instant,
) {
    init {
        require(blockerUserId != blockedUserId) { "an account cannot block itself" }
    }
}
