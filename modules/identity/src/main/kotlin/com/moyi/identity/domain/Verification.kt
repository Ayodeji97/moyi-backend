package com.moyi.identity.domain

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Why a token exists, and how long one lives. The lifetime belongs to the
 * purpose because the requirements state it that way: FR-002 gives email
 * verification 24 hours, FR-004 gives password reset 1 hour. A constant per
 * purpose, not a setting — these are requirements, not tuning. One value
 * today; FR-004 and FR-005 add theirs, and the `verification_tokens` CHECK
 * widens with them. Stored as text, so the enum name is the contract.
 */
internal enum class VerificationPurpose(
    val ttl: Duration,
) {
    /** FR-002: "Token TTL 24h". */
    EMAIL_VERIFICATION(Duration.ofHours(EMAIL_VERIFICATION_TTL_HOURS)),

    /** FR-004: a password-reset link is valid for one hour. */
    PASSWORD_RESET(Duration.ofHours(PASSWORD_RESET_TTL_HOURS)),
}

/** File-level rather than in a companion: an enum entry cannot read its own companion while it is being constructed. */
private const val EMAIL_VERIFICATION_TTL_HOURS = 24L
private const val PASSWORD_RESET_TTL_HOURS = 1L

/**
 * The secret half of a verification token: what goes in the email, and the
 * only thing a person ever presents. It is never stored — see [TokenHash].
 *
 * Deliberately not shape-checked beyond "not blank". What a person presents
 * is whatever their mail client handed them, and the right answer to a
 * mangled token is "not recognised", reached by hashing it and finding
 * nothing — not a 500 from a `require` about length. The *generator* is
 * where the shape is guaranteed.
 *
 * `toString` is redacted: this is a bearer credential, and the class will be
 * inside an event that somebody logs.
 */
@JvmInline
internal value class VerificationSecret(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a verification secret must not be blank" }
    }

    /**
     * The lookup key. Unsalted SHA-256 is the right hash *for this input and
     * no other*: the secret is 256 bits of CSPRNG output, so there is no
     * dictionary to precompute and nothing a salt would defend against. A
     * password gets Argon2id precisely because its input is guessable. Being
     * able to say why the two differ is the interview question this type
     * exists to answer.
     */
    fun hash(): TokenHash = TokenHash(sha256Hex(value))

    override fun toString(): String = "VerificationSecret(redacted)"

    private companion object {
        fun sha256Hex(input: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** What the database holds: the digest of a [VerificationSecret], 64 lowercase hex characters. */
@JvmInline
internal value class TokenHash(
    val value: String,
) {
    init {
        require(HEX_64.matches(value)) { "a token hash is 64 lowercase hex characters" }
    }

    private companion object {
        val HEX_64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Produces secrets. A port, like [PasswordHasher], so that the domain can say
 * what a secret must be — unguessable, URL-safe — without owning a
 * `SecureRandom`, and so tests can issue a known one.
 */
internal interface TokenGenerator {
    fun verificationSecret(): VerificationSecret
}

/**
 * A verification token as the system knows it: whose, for what, until when,
 * and whether it has been spent. It never holds the secret — only its hash —
 * so a `VerificationToken` read back from the database cannot be turned into
 * a link.
 */
internal data class VerificationToken(
    val id: UUID,
    val userId: UserId,
    val purpose: VerificationPurpose,
    val tokenHash: TokenHash,
    val expiresAt: Instant,
    val consumedAt: Instant?,
) {
    /** Presentable: not yet consumed, and not yet expired. Both halves of FR-002's "24h, single-use". */
    fun isLive(now: Instant): Boolean = consumedAt == null && expiresAt.isAfter(now)

    companion object {
        /** A fresh, unspent token for [purpose], expiring [VerificationPurpose.ttl] after [now]. */
        fun issue(
            id: UUID,
            userId: UserId,
            purpose: VerificationPurpose,
            secret: VerificationSecret,
            now: Instant,
        ): VerificationToken =
            VerificationToken(
                id = id,
                userId = userId,
                purpose = purpose,
                tokenHash = secret.hash(),
                expiresAt = now.plus(purpose.ttl),
                consumedAt = null,
            )
    }
}

/**
 * Raised inside the transaction that issued a token, for a listener that runs
 * after it commits. Carries everything the email needs so the listener reads
 * nothing back — including the plaintext [secret], which is the one moment it
 * exists outside the email. The generated `toString` would print it;
 * [VerificationSecret]'s own redaction is what keeps it out of a log line.
 */
internal data class VerificationRequested(
    val userId: UserId,
    val email: Email,
    val displayName: String,
    val locale: String,
    val purpose: VerificationPurpose,
    val tokenId: UUID,
    val secret: VerificationSecret,
    val expiresAt: Instant,
)

/** The presented token matches nothing. A typo, a truncated link, or a guess. */
internal class VerificationTokenInvalidException : RuntimeException("The verification token is not recognised")

/**
 * The presented token was real but is no longer presentable: it expired, or it
 * was already used. One exception for both because the person's recovery is
 * the same — ask for a new one — and `states.md` §1 draws one state for it.
 */
internal class VerificationTokenExpiredException : RuntimeException("The verification token has expired or was already used")

internal class PasswordResetTokenInvalidException : RuntimeException("The password reset token is not recognised")

internal class PasswordResetTokenExpiredException : RuntimeException("The password reset token has expired or was already used")
