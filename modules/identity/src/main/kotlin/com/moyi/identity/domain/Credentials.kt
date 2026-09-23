package com.moyi.identity.domain

import java.time.Duration
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

/**
 * T-03: "account lockout with exponential backoff". The rule, stated once,
 * with the SQL that applies it atomically in `CredentialsRepository`
 * asserting the same numbers.
 *
 * Five consecutive failures lock the account for one minute; every further
 * failure after a lock expires doubles the next lock, to a one-hour cap. The
 * counter resets only on a successful sign-in, so a slow attacker who waits
 * out each lock meets a longer one every time — which is the whole difference
 * between a backoff and a fixed lockout. Attempts made *during* a lock are
 * refused without being counted, so a lock cannot be extended by hammering it.
 *
 * **The lockout is silent.** A locked account answers the same 401, in the
 * same time, as a wrong password: the Argon2id verify still runs, and the
 * result is discarded. A visible "account locked" response can only be
 * produced for a real account, which makes it an enumeration oracle — T-18
 * says this in as many words about per-account rate limits, and FR-012's
 * `429 Retry-After` is the per-IP control that will say "wait", in the
 * rate-limiting slice.
 */
internal object LockoutPolicy {
    const val MAX_FAILED_ATTEMPTS = 5
    val FIRST_LOCK: Duration = Duration.ofMinutes(1)
    val LOCK_CAP: Duration = Duration.ofHours(1)

    /** The lock a failure brings the count to [failedAttempts] earns, or null below the threshold. */
    fun lockDurationFor(failedAttempts: Int): Duration? {
        if (failedAttempts < MAX_FAILED_ATTEMPTS) return null
        val doublings = (failedAttempts - MAX_FAILED_ATTEMPTS).coerceAtMost(DOUBLINGS_TO_CAP)
        val minutes = FIRST_LOCK.toMinutes() shl doublings
        return Duration.ofMinutes(minutes).coerceAtMost(LOCK_CAP)
    }

    /** 2^6 minutes is 64, past the 60-minute cap, so six doublings is where the shift stops mattering. */
    private const val DOUBLINGS_TO_CAP = 6
}

private fun Duration.coerceAtMost(cap: Duration): Duration = if (this > cap) cap else this
