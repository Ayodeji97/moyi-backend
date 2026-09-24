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

    /**
     * Records one failed attempt and applies `LockoutPolicy` in the same
     * statement, so two concurrent wrong passwords both count and neither can
     * lose the other's increment or the lock it earned.
     *
     * Native SQL, because the policy is arithmetic JPQL cannot express:
     * `1 min × 2^(failures − 5)`, capped at 60. The `WHERE` refuses to count
     * an attempt made while a lock is in force, so a lock cannot be extended by
     * hammering it; the counter is **not** reset when a lock expires — that is
     * what makes the next lock longer (T-03's "exponential backoff"). Only a
     * successful sign-in resets it, in [clearFailedAttempts].
     *
     * `LockoutPolicyTest` pins the Kotlin statement of the rule and
     * `UserPersistenceTest` pins this SQL to the same numbers, because a rule
     * written twice drifts twice.
     *
     * @return `1` if the attempt was counted; `0` if the account is currently locked (or does not exist).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
        UPDATE credentials
           SET failed_attempts = failed_attempts + 1,
               locked_until = CASE
                   WHEN failed_attempts + 1 >= 5
                       THEN CAST(:now AS timestamptz)
                            + make_interval(mins => LEAST(60, CAST(power(2, failed_attempts + 1 - 5) AS int)))
                   ELSE locked_until
               END
         WHERE user_id = :id
           AND (locked_until IS NULL OR locked_until <= CAST(:now AS timestamptz))
        """,
    )
    fun recordFailedAttempt(
        id: UUID,
        now: Instant,
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

    fun findById(id: UUID): RefreshTokenEntity?

    /**
     * Serialises every mutation of one user's sessions for the rest of the
     * current transaction, and is the reason [revokeFamily] and
     * [revokeAllForUser] can be trusted.
     *
     * Without it there is a race the tests could not see and the Codex review
     * of PR #32 could: a family revocation is one `UPDATE` over the rows that
     * exist when its snapshot is taken (READ COMMITTED), while a concurrent
     * rotation of the current successor inserts a *new* row in its own
     * transaction. If that insert commits after the revocation's snapshot,
     * the family is "revoked" and one live token survives it — in the hands
     * of whoever was refreshing, which after a reuse may be the thief.
     *
     * A transaction-scoped advisory lock keyed on the user id closes it:
     * rotation, logout, logout-all and reset all take the same lock first, so
     * the insert either commits before the revocation's snapshot or waits
     * until after the revocation commits and finds its own token already
     * rotated. Per user rather than per family so there is one lock and no
     * ordering to get wrong; a user has a handful of concurrent sessions, so
     * the contention is nil. Released automatically at commit or rollback.
     *
     * Namespaced with `1` in the two-key form so a future advisory lock for
     * something else cannot collide with it by accident.
     */
    @Query(nativeQuery = true, value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(1, hashtext(CAST(:userId AS text)))) AS held")
    fun lockSessions(userId: UUID): Int

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

    /**
     * FR-007: a family scoped to its owner. The `userId` predicate is the
     * authorisation — a family id that is not the caller's matches no row,
     * and no row is the 404 doc 06 §2 requires, never a 403 that confirms
     * the id exists. `0` also for a family that is not a *live* session: one
     * already revoked, or one whose last token has expired. Without the
     * `EXISTS`, a family that had merely expired still had unrevoked rows to
     * match, and a stale id from a client's cache got a 204 for a session the
     * list had stopped showing (Codex's P2 on #35). Every row of a live family
     * is revoked, rotated ones included, so the chain reads as ended.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE RefreshTokenEntity t
           SET t.revokedAt = :now
         WHERE t.familyId = :familyId
           AND t.userId = :userId
           AND t.revokedAt IS NULL
           AND EXISTS (
               SELECT 1 FROM RefreshTokenEntity live
                WHERE live.familyId = t.familyId
                  AND live.rotatedAt IS NULL
                  AND live.revokedAt IS NULL
                  AND live.expiresAt > :now
           )
        """,
    )
    fun revokeFamilyOf(
        userId: UUID,
        familyId: UUID,
        now: Instant,
    ): Int

    /** The live tokens of one user — exactly one per live family, so this *is* the sessions list. */
    @Query(
        """
        SELECT t FROM RefreshTokenEntity t
         WHERE t.userId = :userId
           AND t.rotatedAt IS NULL
           AND t.revokedAt IS NULL
           AND t.expiresAt > :now
        """,
    )
    fun findLiveByUserId(
        userId: UUID,
        now: Instant,
    ): List<RefreshTokenEntity>

    /** When each of a user's families began: the first token's `issuedAt`. */
    @Query(
        """
        SELECT new com.moyi.identity.infra.database.FamilyStart(t.familyId, MIN(t.issuedAt))
          FROM RefreshTokenEntity t
         WHERE t.userId = :userId
         GROUP BY t.familyId
        """,
    )
    fun familyStartsOf(userId: UUID): List<FamilyStart>
}

/** A family and the moment it was created (JPQL constructor projection). */
internal data class FamilyStart(
    val familyId: UUID,
    val createdAt: Instant,
)

/** Devices (V8). No `findAll()`: a device row names a person's phone. */
internal interface DeviceRepository : Repository<DeviceEntity, UUID> {
    fun save(device: DeviceEntity): DeviceEntity

    fun findAllByUserId(userId: UUID): List<DeviceEntity>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE DeviceEntity d SET d.lastSeenAt = :now WHERE d.id = :id")
    fun touch(
        id: UUID,
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
     *
     * **Live means live.** The first version deleted every unconsumed row,
     * expired ones included, so a stale expired link presented afterwards
     * answered 422 "not recognised" instead of the contracted 410 "expired",
     * and its row was gone before the reaper's turn. The `expiresAt` predicate
     * is what makes the name true; expired rows are the reaper's (doc 07 §7).
     * Raised by the Codex review on PR #29, 2026-09-23.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        DELETE FROM VerificationTokenEntity t
         WHERE t.userId = :userId
           AND t.purpose = :purpose
           AND t.consumedAt IS NULL
           AND t.expiresAt > :now
        """,
    )
    fun deleteLive(
        userId: UUID,
        purpose: VerificationPurpose,
        now: Instant,
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
