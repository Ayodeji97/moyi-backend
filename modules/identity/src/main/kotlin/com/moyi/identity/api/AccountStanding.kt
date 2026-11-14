package com.moyi.identity.api

import java.time.Instant
import java.util.UUID

/**
 * Whether, and when, accounts were suspended (admin console A3, spec §6 and ruling 8): what
 * `bond` derives a bond's pause from, and what the close job judges a day by. Read-only, takes
 * no lock: a caller holding a bond lock never waits on identity. `ArchitectureTest` lets only
 * `bond` (and `identity`) import it in main code.
 *
 * An empty input is an empty answer, with no query.
 */
interface AccountStanding {
    /** Those of [userIds] that are suspended now: that hold an open span. */
    fun suspendedAmong(userIds: Collection<UUID>): Set<UUID>

    /** Every span, open or closed, of each of [userIds], in no promised order. */
    fun suspensionsOf(userIds: Collection<UUID>): List<SuspensionSpan>
}

/** A stretch [userId] was suspended: from [from], until [to], or still when [to] is `null`. */
data class SuspensionSpan(
    val userId: UUID,
    val from: Instant,
    val to: Instant?,
)
