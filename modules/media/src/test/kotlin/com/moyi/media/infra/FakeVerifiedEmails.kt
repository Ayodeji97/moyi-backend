package com.moyi.media.infra

import com.moyi.common.security.VerifiedEmails
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Identity's answer, in memory. This module's tests make their users up (a
 * random id and a token), so everyone is verified unless a test says otherwise
 * with [unverified]; [clear] puts that back.
 */
internal class FakeVerifiedEmails : VerifiedEmails {
    private val unverified = ConcurrentHashMap.newKeySet<UUID>()

    /** [userId] has registered and not confirmed their address. */
    fun unverified(userId: UUID) {
        unverified.add(userId)
    }

    /** [userId] has confirmed their address. */
    fun verify(userId: UUID) {
        unverified.remove(userId)
    }

    fun clear() = unverified.clear()

    override fun isVerified(userId: UUID): Boolean = userId !in unverified
}
