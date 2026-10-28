package com.moyi.notification.api

import java.util.UUID

/**
 * Where a device's push token is kept, and the only way it is changed
 * (ADR-0044, spec §2.1).
 *
 * The route that registers a token is `identity`'s, because the device is
 * identity's: a device row is made at sign-in and a session names it
 * (ADR-0025). The token is kept here, because `notification` is a leaf and can
 * read nothing of identity's; `identity → notification` is the direction the
 * module graph already allows. So identity calls this on registration, on its
 * removal, and on every way a session ends.
 *
 * Every function joins the caller's transaction, so a token is forgotten in
 * the same commit that ends the session it belonged to.
 */
interface PushTokens {
    /**
     * Makes [registration]'s token the one its device holds, replacing any
     * token that device held before.
     *
     * **A token names one installation of the app, so it is held by one device
     * row.** If another device row held it, that row loses it here. When that
     * row is the same person's, it was an earlier sign-in from the same phone,
     * which ADR-0025 could not tell apart until tokens existed; its id is
     * returned so the caller can fold it (ADR-0025, "Revisit when"). When it is
     * somebody else's, the phone has changed hands without a sign-out, and the
     * token simply moves: the earlier owner's pushes must not reach it. Their
     * device id is not returned; it is nothing to the caller.
     */
    fun register(registration: PushTokenRegistration): PushTokenRegistered

    /** Forgets the token [deviceId] holds, if it holds one. */
    fun forget(deviceId: UUID)

    /** Forgets every token of [userId]'s devices: every session of theirs has ended. */
    fun forgetAllOf(userId: UUID)
}

/** One device's token. [platform] is the device's, as identity recorded it at sign-in. */
data class PushTokenRegistration(
    val deviceId: UUID,
    val userId: UUID,
    val platform: PushPlatform,
    val token: PushToken,
)

/** What a registration displaced: the same person's other device rows that held this token until now. */
data class PushTokenRegistered(
    val foldedDeviceIds: Set<UUID>,
)

/** Doc 04's device platforms, the same three identity's devices are checked against. */
enum class PushPlatform {
    ANDROID,
    IOS,
    WEAR,
}

/**
 * A push token, as the app's push provider gave it to the phone.
 *
 * **A secret.** Whoever holds it can make that phone show a notification, so
 * it is never logged (doc 11:25) and [toString] does not print it. The shape
 * checked here is the one rule for what a token may be: the route that accepts
 * one runs this check and reports its answer (CLAUDE.md: a rule lives in one
 * place), and the table's CHECK holds the same bound.
 */
@JvmInline
value class PushToken(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH) { "a push token must be 1 to $MAX_LENGTH characters" }
        require(value.none(Char::isWhitespace)) { "a push token has no whitespace in it" }
    }

    override fun toString(): String = "PushToken(<redacted>)"

    companion object {
        /** The CHECK in V29. FCM documents no maximum; tokens seen are about 160 characters. */
        const val MAX_LENGTH = 1024
    }
}
