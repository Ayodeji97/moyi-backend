package com.moyi.notification.service

import com.moyi.notification.api.PushTokenRegistered
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import com.moyi.notification.infra.database.PushTokenStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * [PushTokens] over `push_tokens`. Every call joins the caller's transaction
 * and refuses to run outside one (`MANDATORY`): a token forgotten by a logout
 * must be forgotten in the logout's commit, and a registration that folds a
 * device row must fold it in the same one.
 */
@Service
internal class PushTokenRegistry(
    private val tokens: PushTokenStore,
    private val clock: Clock,
) : PushTokens {
    /**
     * Two statements in one transaction: the token is taken from whichever
     * other device holds it, then given to this one. In that order, because
     * `unique (token)` would refuse the second while the first still held it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    override fun register(registration: PushTokenRegistration): PushTokenRegistered {
        val folded = tokens.takeFromOthers(registration.token, registration.deviceId, registration.userId)
        tokens.put(registration.deviceId, registration.userId, registration.platform, registration.token, clock.instant())
        return PushTokenRegistered(folded)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun forget(deviceId: UUID) {
        tokens.deleteOf(deviceId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun forgetAllOf(userId: UUID) {
        tokens.deleteAllOf(userId)
    }
}
