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

    /** Six digits, uniformly from 000000 to 999999 (#135). */
    fun verificationCode(): VerificationCode
}

/**
 * The short half of a verification credential: six digits a person types from the
 * email instead of opening the link (#135, ADR-0018 amended 2026-11-08). It is the
 * same credential as the link, on the same row: spending either spends both.
 *
 * Unlike [VerificationSecret], a code is guessable — a million values — so what
 * makes it safe is not its size but what surrounds it: it is accepted only with
 * the address it was sent to, for [VerificationToken.CODE_TTL_DEFAULT] at most,
 * [VerificationToken.MAX_WRONG_CODES] wrong tries burn it, and the rate limits
 * bound the tries a day (ADR-0023, amended). The shape is checked here, and only
 * here: the edge asks this constructor (`@ValidVerificationCode`).
 *
 * `toString` is redacted: it is a credential for fifteen minutes.
 */
@JvmInline
internal value class VerificationCode(
    val value: String,
) {
    init {
        require(SHAPE.matches(value)) { "must be the $LENGTH digits from the email" }
    }

    override fun toString(): String = "VerificationCode(redacted)"

    companion object {
        const val LENGTH = 6

        /** ASCII digits only: `\d` would take Arabic-Indic and other Unicode digits. */
        private val SHAPE = Regex("^[0-9]{$LENGTH}$")
    }
}

/**
 * What the database holds for a code: a keyed MAC, never the code (a million codes
 * hash in a second, so an unkeyed digest would be the code itself). Compared in
 * constant time, so how long a comparison takes says nothing about how many
 * characters matched.
 */
@JvmInline
internal value class CodeHash(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a code hash must not be blank" }
    }

    fun matches(other: CodeHash): Boolean =
        MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), other.value.toByteArray(Charsets.UTF_8))

    override fun toString(): String = "CodeHash(redacted)"

    companion object {
        /**
         * Matches no MAC this system produces (`!` is not in base64url). Compared against
         * when there is no code to compare with, so every path does one comparison.
         */
        val UNMATCHABLE = CodeHash("!".repeat(HMAC_BASE64URL_LENGTH))
        private const val HMAC_BASE64URL_LENGTH = 43
    }
}

/**
 * Keys a code to the one row and purpose it was issued for. A port, because the
 * secret is configuration (ADR-0023 decision 7) and the domain owns no key.
 */
internal interface VerificationCodeHasher {
    fun hash(
        tokenId: UUID,
        purpose: VerificationPurpose,
        code: VerificationCode,
    ): CodeHash
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
    /** The code beside the link, while this row holds one: null once a newer email retired it, and on rows from before #135. */
    val codeHash: CodeHash? = null,
    val codeExpiresAt: Instant? = null,
    /** Wrong codes presented against this row. */
    val codeAttempts: Int = 0,
) {
    init {
        require((codeHash == null) == (codeExpiresAt == null)) { "a code and its expiry come together" }
        require(codeAttempts >= 0) { "code attempts cannot be negative" }
    }

    /** Presentable: not yet consumed, and not yet expired. Both halves of FR-002's "24h, single-use". */
    fun isLive(now: Instant): Boolean = consumedAt == null && expiresAt.isAfter(now)

    /**
     * The code may still be typed: the row is live (so a spent link has spent the code), it
     * holds a code, the code's own fifteen minutes have not run out, and fewer than
     * [MAX_WRONG_CODES] wrong ones have been tried against it.
     */
    fun codeIsLive(now: Instant): Boolean =
        isLive(now) && codeHash != null && codeExpiresAt!!.isAfter(now) && codeAttempts < MAX_WRONG_CODES

    override fun toString(): String =
        "VerificationToken(id=$id, purpose=$purpose, expiresAt=$expiresAt, consumed=${consumedAt != null}, " +
            "holdsCode=${codeHash != null}, codeAttempts=$codeAttempts)"

    companion object {
        /** #135: five wrong codes burn a code. With a million codes that is a 1-in-200,000 chance per code. */
        const val MAX_WRONG_CODES = 5

        /** How long a code may be typed, unless the property says otherwise and never past the link's own expiry. */
        val CODE_TTL_DEFAULT: Duration = Duration.ofMinutes(15)

        /**
         * A fresh, unspent token for [purpose], expiring [VerificationPurpose.ttl] after [now], with
         * its code keyed to [id] by [codeHash] and typeable for [codeTtl] or until the link
         * expires, whichever is sooner.
         */
        @Suppress("LongParameterList")
        fun issue(
            id: UUID,
            userId: UserId,
            purpose: VerificationPurpose,
            secret: VerificationSecret,
            codeHash: CodeHash,
            codeTtl: Duration,
            now: Instant,
        ): VerificationToken {
            val expiresAt = now.plus(purpose.ttl)
            return VerificationToken(
                id = id,
                userId = userId,
                purpose = purpose,
                tokenHash = secret.hash(),
                expiresAt = expiresAt,
                consumedAt = null,
                codeHash = codeHash,
                codeExpiresAt = minOf(now.plus(codeTtl), expiresAt),
                codeAttempts = 0,
            )
        }

        /**
         * **The code check, the one statement of it.** Accepts [presented] only if [held] is a row
         * whose code is live and whose code is the one presented.
         *
         * Always compares once, in constant time, even when nothing is held (against
         * [CodeHash.UNMATCHABLE]) or the code is dead: so an unknown address, a burned code and
         * a wrong one do the same work, and the caller does the same work after (ADR-0018
         * amendment, question 3). The caller holds [held] under a row lock, so [codeAttempts] is
         * the count every concurrent attempt sees.
         */
        fun acceptsCode(
            held: VerificationToken?,
            presented: CodeHash,
            now: Instant,
        ): Boolean {
            val same = (held?.codeHash ?: CodeHash.UNMATCHABLE).matches(presented)
            return same && held != null && held.codeIsLive(now)
        }
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
    /** The six digits beside the link (#135); redacted in `toString` like [secret]. */
    val code: VerificationCode,
    val codeExpiresAt: Instant,
)

/** The presented token matches nothing. A typo, a truncated link, or a guess. */
internal open class VerificationTokenInvalidException(
    message: String = "The verification token is not recognised",
) : RuntimeException(message)

/**
 * The presented token was real but is no longer presentable: it expired, or it
 * was already used. One exception for both because the person's recovery is
 * the same — ask for a new one — and `states.md` §1 draws one state for it.
 */
internal class VerificationTokenExpiredException : RuntimeException("The verification token has expired or was already used")

internal open class PasswordResetTokenInvalidException(
    message: String = "The password reset token is not recognised",
) : RuntimeException(message)

internal class PasswordResetTokenExpiredException : RuntimeException("The password reset token has expired or was already used")

/**
 * `{email, code}` presented to `verify-email` was not accepted: no such account, no code
 * held, a wrong code, an expired or burned one, or one already spent. One exception for all
 * of them, on purpose: the person typed an address, so any distinction among these would say
 * whether that address has an account (ADR-0015, ADR-0018 amended 2026-11-08).
 *
 * A kind of [VerificationTokenInvalidException]: the same status and code (422
 * `VERIFICATION_TOKEN_INVALID`), with its own detail.
 */
internal class VerificationCodeRejectedException : VerificationTokenInvalidException("The verification code was not accepted")

/** The same, for `reset-password`. */
internal class PasswordResetCodeRejectedException : PasswordResetTokenInvalidException("The password reset code was not accepted")
