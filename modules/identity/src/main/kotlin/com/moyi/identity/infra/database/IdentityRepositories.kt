package com.moyi.identity.infra.database

import com.moyi.identity.domain.VerificationPurpose
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.UUID

/**
 * Users. A full [JpaRepository], because the account lifecycle genuinely uses
 * the breadth of it — find, save, delete, exists.
 *
 * `findByEmail` needs no `LOWER(...)`: the column is `citext`, so the
 * comparison is case-insensitive in the database, which is also what makes
 * the unique index case-insensitive. Doing it in Kotlin instead would produce
 * a query that cannot use the index and a constraint that still lets
 * `Ada@example.com` and `ada@example.com` both register.
 */
internal interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?

    fun existsByEmail(email: String): Boolean

    /** See [UserRevocationRow]. `null` when there is no such user. */
    @Query(
        """
        SELECT new com.moyi.identity.infra.database.UserRevocationRow(u.id, u.tokensInvalidBefore)
          FROM UserEntity u
         WHERE u.id = :id
        """,
    )
    fun findRevocation(id: UUID): UserRevocationRow?
}

/**
 * Credentials. **Exactly two methods, and that is the point** — doc 07 §2
 * requires that `password_hash` is never selected by a query that is not the
 * authentication path, and the way to guarantee that is to make no other
 * query exist.
 *
 * Note the supertype: Spring Data's bare [Repository] marker, which declares
 * nothing. Extending `JpaRepository` here would inherit `findAll()`,
 * `findAllById()`, `getReferenceById()` and a dozen more, each of them a
 * supported way to pull every password hash in the system into memory. The
 * two methods below are declared by hand and implemented by Spring Data from
 * the same base class; the difference is only in what is *reachable*.
 */
internal interface CredentialsRepository : Repository<CredentialsEntity, UUID> {
    fun findById(id: UUID): CredentialsEntity?

    fun save(credentials: CredentialsEntity): CredentialsEntity

    /** Records one failed attempt without allowing concurrent requests to lose an increment. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE CredentialsEntity c
           SET c.failedAttempts = CASE
                   WHEN c.lockedUntil IS NOT NULL AND c.lockedUntil <= :now THEN 1
                   ELSE c.failedAttempts + 1
               END,
               c.lockedUntil = CASE
                   WHEN c.lockedUntil IS NOT NULL AND c.lockedUntil <= :now THEN NULL
                   WHEN c.failedAttempts + 1 >= :threshold THEN :lockedUntil
                   ELSE c.lockedUntil
               END
         WHERE c.id = :id
           AND (c.lockedUntil IS NULL OR c.lockedUntil <= :now)
        """,
    )
    fun recordFailedAttempt(
        id: UUID,
        now: Instant,
        threshold: Int,
        lockedUntil: Instant,
    ): Int

    /** Clears the counter only when the account was not locked by another request. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE CredentialsEntity c
           SET c.failedAttempts = 0,
               c.lockedUntil = NULL
         WHERE c.id = :id
           AND (c.lockedUntil IS NULL OR c.lockedUntil <= :now)
        """,
    )
    fun clearFailedAttempts(
        id: UUID,
        now: Instant,
    ): Int
}

internal interface RefreshTokenRepository : Repository<RefreshTokenEntity, UUID> {
    fun save(token: RefreshTokenEntity): RefreshTokenEntity

    fun findByTokenHash(tokenHash: String): RefreshTokenEntity?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshTokenEntity t
           SET t.rotatedAt = :now,
               t.replacedBy = :replacementId
         WHERE t.id = :id
           AND t.rotatedAt IS NULL
           AND t.revokedAt IS NULL
           AND t.expiresAt > :now
        """,
    )
    fun rotate(
        id: UUID,
        replacementId: UUID,
        now: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshTokenEntity t
           SET t.revokedAt = :now
         WHERE t.familyId = :familyId
           AND t.revokedAt IS NULL
        """,
    )
    fun revokeFamily(
        familyId: UUID,
        now: Instant,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshTokenEntity t
           SET t.revokedAt = :now
         WHERE t.userId = :userId
           AND t.revokedAt IS NULL
        """,
    )
    fun revokeAllForUser(
        userId: UUID,
        now: Instant,
    ): Int
}

/**
 * Consent records. One method, because writing is the only thing this slice
 * does with them — the export path (FR-009) adds its own read when it exists.
 *
 * On the bare [Repository] marker for the same reason as
 * [CredentialsRepository]: what a repository *cannot* do is part of its
 * design, and `findAll()` over a table of personal-data attestations is not
 * a query anything should be able to reach for by accident.
 */
internal interface ConsentRecordRepository : Repository<ConsentRecordEntity, UUID> {
    fun saveAll(records: Iterable<ConsentRecordEntity>): List<ConsentRecordEntity>
}

/**
 * Verification tokens. On the bare [Repository] marker, and every method is
 * one the verification path actually makes — there is no `findAll()` over a
 * table of credential digests.
 *
 * The two `@Modifying` queries are the interesting part. [consume] is a
 * compare-and-set written in SQL: it flips `consumed_at` **only if** the row
 * is still live, and reports through its return value whether it was this
 * caller who spent it. Two requests presenting the same token at the same
 * moment both read it as live; only one of them gets `1` back here, because
 * the database serialises the two `UPDATE`s and the second finds the
 * `consumed_at IS NULL` predicate no longer true. A read-then-write in Kotlin
 * — load, check `isLive`, save — has no such guarantee, and would let both
 * through.
 *
 * `clearAutomatically` on both: a bulk JPQL update bypasses the persistence
 * context, so an entity loaded earlier in the same transaction would still
 * say `consumedAt == null` after the row was flipped. Clearing the context
 * makes the next read go to the database, which is the only place the truth
 * now is.
 */
internal interface VerificationTokenRepository : Repository<VerificationTokenEntity, UUID> {
    fun save(token: VerificationTokenEntity): VerificationTokenEntity

    /** Purpose-scoped on purpose: the table is shared, and a reset token must never satisfy a verification lookup. */
    fun findByTokenHashAndPurpose(
        tokenHash: String,
        purpose: VerificationPurpose,
    ): VerificationTokenEntity?

    /** @return `1` if this call consumed the token; `0` if it was already consumed, expired, or absent. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE VerificationTokenEntity t
           SET t.consumedAt = :now
         WHERE t.tokenHash = :tokenHash
           AND t.consumedAt IS NULL
           AND t.expiresAt > :now
        """,
    )
    fun consume(
        tokenHash: String,
        now: Instant,
    ): Int

    /**
     * Retires a person's other outstanding tokens for a purpose once one of
     * them has done its job. Deleted rather than consumed: doc 07 §7 keeps
     * tokens only until "consumption or expiry", and these were neither —
     * they are simply moot.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        DELETE FROM VerificationTokenEntity t
         WHERE t.userId = :userId
           AND t.purpose = :purpose
           AND t.consumedAt IS NULL
        """,
    )
    fun deleteLive(
        userId: UUID,
        purpose: VerificationPurpose,
    ): Int
}

/**
 * The two columns the access-token verifier needs, and nothing else. A
 * projection rather than `findById`, because this query runs once per
 * authenticated request and the row carries an email address and a display
 * name that request has no use for.
 */
internal data class UserRevocationRow(
    val id: UUID,
    val tokensInvalidBefore: Instant?,
)
