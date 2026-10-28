package com.moyi.identity.service

import com.moyi.common.web.NotFoundException
import com.moyi.identity.domain.DevicePlatform
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.DeviceStore
import com.moyi.identity.infra.database.RefreshTokenStore
import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushToken
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * `PUT` and `DELETE /api/v1/me/devices/current/push-token` (spec N1, ADR-0044).
 *
 * **The device is the session's.** Devices are made at sign-in, not registered
 * separately (ADR-0025), so the route names no device: it is the one the
 * caller's session was started from, found through the access token's `sid`.
 * This replaces doc 06's `POST /me/devices` and `DELETE /me/devices/{id}`.
 *
 * **The token is kept by `notification`** ([PushTokens]), which is a leaf and
 * cannot read devices; identity tells it, in identity's transaction.
 *
 * **Under the per-user sessions lock**, as every session mutation is
 * (ADR-0021): a registration that raced a logout could otherwise read the
 * session as live, wait, and give a token to a session the logout had just
 * ended, after the logout forgot it.
 */
@Service
internal class DevicePushTokens(
    private val sessions: RefreshTokenStore,
    private val devices: DeviceStore,
    private val pushTokens: PushTokens,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    /**
     * Gives the current session's device [token], replacing what it held.
     * The same person's other device rows that held the token are folded
     * (ADR-0025): they were this phone, signed in before.
     *
     * @throws NoCurrentDeviceException the session has ended, or began without describing a device
     */
    fun register(
        userId: UserId,
        sessionId: UUID?,
        token: PushToken,
    ) {
        transactions.executeWithoutResult {
            val now = clock.instant()
            sessions.lockSessionsOf(userId)
            val deviceId = currentDeviceOf(userId, sessionId)
            val device = devices.find(deviceId) ?: throw NoCurrentDeviceException()
            val registered = pushTokens.register(PushTokenRegistration(deviceId, userId.value, device.platform.forPush(), token))
            devices.fold(registered.foldedDeviceIds, userId, now)
        }
    }

    /**
     * Forgets the current session's device token, if it has one. Answers the
     * same whether or not there was one: the caller asked for a state, and it holds.
     *
     * @throws NoCurrentDeviceException the session has ended, or began without describing a device
     */
    fun forget(
        userId: UserId,
        sessionId: UUID?,
    ) {
        transactions.executeWithoutResult {
            sessions.lockSessionsOf(userId)
            pushTokens.forget(currentDeviceOf(userId, sessionId))
        }
    }

    /** The device of the caller's session, while that session is live. */
    private fun currentDeviceOf(
        userId: UserId,
        sessionId: UUID?,
    ): UUID =
        sessionId
            ?.let { sid -> sessions.findLiveOf(userId, clock.instant()).firstOrNull { it.familyId == sid } }
            ?.deviceId
            ?: throw NoCurrentDeviceException()

    private fun DevicePlatform.forPush(): PushPlatform =
        when (this) {
            DevicePlatform.ANDROID -> PushPlatform.ANDROID
            DevicePlatform.IOS -> PushPlatform.IOS
            DevicePlatform.WEAR -> PushPlatform.WEAR
        }
}

/**
 * The caller's session has no device to hold a token: it has ended (an access
 * token outlives its session by up to fifteen minutes, doc 09 §3), or the
 * sign-in did not describe a device (ADR-0025 decision 3). One answer for both:
 * there is no current device, so the resource is not there.
 */
internal class NoCurrentDeviceException :
    NotFoundException("This session has no device: sign in again, describing the device, to register a push token.")
