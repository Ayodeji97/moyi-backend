package com.moyi.identity.domain

import java.time.Instant

/**
 * The password half of an account, modelled separately from [User] because
 * the two have different access rules rather than because the table was
 * normalised: doc 07 §2 requires that `password_hash` is never selected by a
 * query outside the authentication path.
 *
 * [failedAttempts] and [lockedUntil] live here too — they are read and written
 * on exactly the same path, by the same code, under the same lock.
 */
internal data class Credentials(
    val userId: UserId,
    val passwordHash: PasswordHash,
    val algorithm: PasswordHashAlgorithm,
    val passwordUpdatedAt: Instant,
    val failedAttempts: Int,
    val lockedUntil: Instant?,
) {
    init {
        require(failedAttempts >= 0) { "failed attempts must not be negative" }
    }

    /** True while the account is locked out at [now]; `locked_until` in the past is simply expired, not cleared. */
    fun isLockedAt(now: Instant): Boolean = lockedUntil?.isAfter(now) == true
}

/**
 * An Argon2id digest — the encoded string, salt and parameters included, as
 * Spring Security's encoder produces it.
 *
 * The wrapper exists for [toString]. Doc 18 §9: never store a token or
 * password in plaintext *anywhere, including logs*. A bare `String` field
 * reaches a log the first time anything interpolates the object that holds
 * it — a `data class` `toString()`, a debug statement, an exception message —
 * and no review catches that reliably. Making the type itself refuse to print
 * removes the possibility rather than the temptation.
 *
 * A hash is not a password, so this is defence in depth rather than a secret
 * leak. It is also the cheapest possible habit to form before the slice that
 * handles the actual plaintext.
 */
@JvmInline
internal value class PasswordHash(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "password hash must not be blank" }
    }

    override fun toString(): String = "PasswordHash(redacted)"
}

/**
 * Which algorithm produced a stored hash.
 *
 * Recorded per credential, not assumed globally, so that a password hashed
 * under superseded parameters can be re-hashed on the next successful login —
 * the only moment the plaintext is available. Without this column, changing
 * Argon2id's cost parameters would mean either a flag day or a silent lie
 * about how old hashes were computed.
 */
internal enum class PasswordHashAlgorithm {
    /** ADR-0012 / NFR-046: Argon2id, m=19456 KiB, t=2, p=1. */
    ARGON2ID,
}
