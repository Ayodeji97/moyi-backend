package com.moyi.bond.api

import java.time.Instant
import java.util.UUID

/**
 * Every bond a user holds a membership row in, for the admin console (admin
 * console A1). Facts about the bond only; never an entry, a name or a code.
 * Admin-only: `ArchitectureTest` allows no other module to import it.
 */
interface BondAdminLookup {
    /** Newest first; empty for a user in no bond. */
    fun bondsOf(userId: UUID): List<BondAdminView>
}

/** [toString] prints only the bond id. */
data class BondAdminView(
    val bondId: UUID,
    val status: String,
    val createdAt: Instant,
    /** When the bond was archived; `null` while it is open. */
    val endedAt: Instant?,
    /** The other member, or `null` while the bond waits for one. */
    val partnerUserId: UUID?,
    /** A3: the bond is paused, because a member who has not left is suspended. */
    val paused: Boolean,
) {
    override fun toString(): String = "BondAdminView(bondId=$bondId)"
}
