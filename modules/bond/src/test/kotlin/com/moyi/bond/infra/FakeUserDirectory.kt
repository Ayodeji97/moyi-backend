package com.moyi.bond.infra

import com.moyi.identity.api.UserDirectory
import com.moyi.identity.api.UserSummary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The identity port, in memory.
 *
 * This module's test context does not boot identity (see
 * [BondTestApplication]): what bond needs of a user is exactly what the port
 * says, so the port is what a test supplies. The real adapter is tested where
 * it lives — `AccountDirectoryTest` in identity — and the two are wired
 * together in `app`, which is where a mismatch between them would surface.
 *
 * Deliberately not a mock. A mock would assert that the code *called* the
 * directory; this lets the test assert what the code did with the answer,
 * which is the thing that can actually be wrong.
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
