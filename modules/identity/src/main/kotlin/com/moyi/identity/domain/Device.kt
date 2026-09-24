package com.moyi.identity.domain

import java.time.Instant
import java.util.UUID

/** Doc 04's enum for [Device.platform]. Text plus a CHECK in the table, like every other status. */
internal enum class DevicePlatform {
    ANDROID,
    IOS,
    WEAR,
}

/**
 * What a sign-in said about the client it came from (doc 07 §2, FR-007).
 *
 * Created at login, one row per sign-in for now: without a push token or an
 * installation id there is nothing to say two sign-ins from one phone are
 * one device, and inventing a fingerprint to guess would be worse than two
 * rows. `pushToken` and `revokedAt` are doc 07's columns for Phase 3; nothing
 * fills them yet (ADR-0025).
 */
internal data class Device(
    val id: UUID,
    val userId: UserId,
    val platform: DevicePlatform,
    val appVersion: String,
    val osVersion: String,
    val lastSeenAt: Instant,
    val pushToken: String? = null,
    val revokedAt: Instant? = null,
) {
    init {
        require(appVersion.length in 1..MAX_VERSION_LENGTH) { "appVersion must be 1 to $MAX_VERSION_LENGTH characters" }
        require(osVersion.length in 1..MAX_VERSION_LENGTH) { "osVersion must be 1 to $MAX_VERSION_LENGTH characters" }
    }

    companion object {
        /** The CHECK in V8. A version string longer than this is not a version string. */
        const val MAX_VERSION_LENGTH = 40
    }
}

/** How a client describes itself at sign-in. The service's type; the request DTO maps onto it. */
internal data class DeviceDescription(
    val platform: DevicePlatform,
    val appVersion: String,
    val osVersion: String,
)

/**
 * One of a person's sign-ins that is still live (FR-007): a refresh-token
 * family that has neither been revoked nor run out, with the device that
 * started it. [id] is the family id — opaque, and what `DELETE
 * /auth/sessions/{id}` names. [current] is whether the access token that
 * asked belongs to this family.
 */
internal data class Session(
    val id: UUID,
    val device: Device?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val current: Boolean,
)
