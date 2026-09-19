package com.moyi.identity.domain

import java.time.Instant
import java.util.UUID

/**
 * A person's account. The domain model, deliberately free of JPA and Spring
 * (doc 25 §6: `domain` depends on nothing) — the persistence shape lives in
 * `infra.database.UserEntity` and the two are joined by a mapper.
 *
 * A `data class` here, and emphatically *not* on the entity: `copy()` on a
 * managed entity produces a detached twin that the persistence context has
 * never heard of, which is how a "harmless" copy becomes a lost update. On an
 * immutable domain model, `copy()` is exactly the right tool.
 */
internal data class User(
    val id: UserId,
    val email: Email,
    val emailVerifiedAt: Instant?,
    val displayName: String,
    val avatarMediaId: UUID?,
    val locale: String,
    val status: UserStatus,
    val createdAt: Instant,
    val updatedAt: Instant?,
    val deletedAt: Instant?,
) {
    init {
        // Mirrors the CHECK constraints in V2. Both exist on purpose: the
        // database constraint is the one that cannot be bypassed, the domain
        // one is the one that fails in the layer that can explain itself.
        require(displayName.isNotBlank()) { "display name must not be blank" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "display name must be at most $MAX_DISPLAY_NAME_LENGTH characters"
        }
        require(locale.isNotBlank()) { "locale must not be blank" }
        require(status != UserStatus.DELETED || deletedAt != null) {
            "a deleted user must carry the time it was deleted"
        }
    }

    /** Email verification is a timestamp, not a flag — *when* is auditable and answers "how long has this been true". */
    val isEmailVerified: Boolean get() = emailVerifiedAt != null

    companion object {
        /**
         * Chosen here rather than found in a document — doc 03 FR-006 requires a display
         * name but names no limit. Flagged for Daniel in the PR and recorded in the
         * assumption register; the database CHECK in V2 carries the same number.
         */
        const val MAX_DISPLAY_NAME_LENGTH = 80
    }
}

/**
 * A user's identifier, wrapped so it cannot be passed where a bond id or a
 * media id is expected — all three are `UUID` to the compiler otherwise, and
 * an argument-order mistake between two UUIDs is invisible at every layer
 * until it reads the wrong row.
 *
 * `value class`, so this costs nothing at runtime: the JVM sees a bare UUID.
 */
@JvmInline
internal value class UserId(
    val value: UUID,
)

/**
 * An email address, validated only for shape.
 *
 * The regex is deliberately permissive. Full RFC 5322 validation is a
 * famous trap — the grammar admits quoted strings, comments and bracketed
 * IP literals — and every attempt to be strict rejects somebody's real
 * address. The only question worth answering here is "could this plausibly
 * be delivered to", and the real proof of an address is that the
 * verification email arrived.
 *
 * Note what the failure messages do not contain: the address. An exception
 * message travels into logs and sometimes into responses (doc 18 §5: the
 * failure response must not echo input), and an address is personal data.
 */
@JvmInline
internal value class Email(
    val value: String,
) {
    init {
        require(value.length in MIN_LENGTH..MAX_LENGTH) {
            "email must be between $MIN_LENGTH and $MAX_LENGTH characters"
        }
        require(SHAPE.matches(value)) { "email is not a valid address" }
    }

    private companion object {
        /** `a@b.c` is the shortest thing this accepts. */
        const val MIN_LENGTH = 5

        /** RFC 5321 §4.5.3.1.3 — the maximum length of a reverse-path or forward-path. */
        const val MAX_LENGTH = 254

        val SHAPE = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    }
}

/**
 * The lifecycle of an account. Stored as text with a CHECK constraint rather
 * than a Postgres enum — see V2 for why.
 */
internal enum class UserStatus {
    /** Registered, email not yet confirmed. Can sign in to nothing. */
    PENDING_VERIFICATION,

    /** The only state in which the account works. */
    ACTIVE,

    /** Suspended by an administrator. Sessions are revoked; the data stays. */
    SUSPENDED,

    /** Deletion requested, inside the grace period, still recoverable. */
    PENDING_DELETION,

    /** The tombstone left behind after erasure, so references do not dangle. */
    DELETED,
}
