package com.moyi.gratitude.infra

import com.moyi.identity.api.UserDirectory
import com.moyi.identity.api.UserSummary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The identity port, in memory — `com.moyi.bond.infra.FakeUserDirectory`'s
 * precedent, copied rather than shared: it is `internal` to `bond`, so
 * `gratitude`'s tests cannot see it and need their own.
 *
 * This module's test context does not boot identity (see
 * [GratitudeTestApplication]): what this module needs of a user is exactly
 * what the port says, so the port is what a test supplies.
 */
internal class FakeUserDirectory : UserDirectory {
    private val users = ConcurrentHashMap<UUID, UserSummary>()

    /** A user who has confirmed their address, so FR-002 lets them create and join. */
    fun verified(displayName: String): UUID = add(displayName, verified = true)

    /** Registered, not yet verified: may sign in, may not create or join a bond. */
    fun unverified(displayName: String): UUID = add(displayName, verified = false)

    fun clear() = users.clear()

    override fun find(id: UUID): UserSummary? = users[id]

    override fun findAll(ids: Collection<UUID>): Map<UUID, UserSummary> = ids.mapNotNull { users[it] }.associateBy { it.id }

    private fun add(
        displayName: String,
        verified: Boolean,
    ): UUID {
        val id = UUID.randomUUID()
        users[id] = UserSummary(id, displayName, verified)
        return id
    }
}
