package com.moyi.identity.api

import java.time.Instant
import java.util.UUID

/**
 * What the admin console may see of a user (admin console A1): an operator
 * looking up an account to help its owner. Admin-only: `ArchitectureTest`
 * allows no other module to import it.
 */
interface UserAdminLookup {
    fun byId(id: UUID): UserAdminView?

    /** The address is trimmed and compared case-insensitively, as the column is. */
    fun byEmail(email: String): UserAdminView?
}

/**
 * [toString] prints only the id: an address and a display name are personal
 * data that would otherwise reach a log with the object (doc 18 §9).
 */
data class UserAdminView(
    val id: UUID,
    val email: String,
    val displayName: String,
    val locale: String,
    val createdAt: Instant,
    val emailVerified: Boolean,
    /** Set only while the lock is still in force. */
    val lockedUntil: Instant?,
    /** Live refresh-token families, the count FR-007 lists. */
    val liveSessions: Int,
    val tokensInvalidBefore: Instant?,
    /** Admin console A3: the account's lifecycle status. */
    val status: AccountStatus,
    /** When the open suspension began; `null` unless the account is suspended now. */
    val suspendedSince: Instant?,
    /** When a pending deletion request will be carried out (its window's end); `null` when none is pending. */
    val deletionDueAt: Instant?,
) {
    override fun toString(): String = "UserAdminView(id=$id)"
}
