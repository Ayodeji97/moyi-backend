package com.moyi.admin.infra

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

internal interface AdminAccountRepository : JpaRepository<AdminAccountEntity, UUID> {
    /**
     * Native, with the cast spelled out: a Hibernate `String` parameter is a
     * varchar, PostgreSQL then compares `citext` as text, and the lookup turns
     * case-sensitive without an error (identity's `CitextType` explains the
     * failure at length). `CAST(:email AS citext)` keeps the comparison in
     * `citext`, and the unique index usable.
     */
    @Query("SELECT * FROM admin_accounts WHERE email = CAST(:email AS citext)", nativeQuery = true)
    fun findByEmail(email: String): AdminAccountEntity?

    /**
     * Spends a TOTP time step exactly once: the row changes only if [step] is
     * later than the last one accepted, so a replayed or older code finds the
     * predicate false and gets 0. A compare-and-set in the database, which a
     * read-then-write in Kotlin cannot give two concurrent requests.
     */
    @Modifying
    @Query(
        "UPDATE AdminAccountEntity a SET a.totpLastStep = :step " +
            "WHERE a.id = :id AND (a.totpLastStep IS NULL OR a.totpLastStep < :step)",
    )
    fun casTotpStep(
        id: UUID,
        step: Long,
    ): Int

    /**
     * Counts one failed sign-in step (spec §6: password and code count together), in one
     * statement so two failures at once cannot both read the same count. The [maxFailures]th
     * consecutive failure locks the account until [lockUntil] and starts the count again, so
     * a lock that has run out is not re-armed by a single later mistake.
     */
    @Modifying
    @Query(
        "UPDATE admin_accounts SET " +
            "failed_attempts = CASE WHEN failed_attempts + 1 >= :maxFailures THEN 0 ELSE failed_attempts + 1 END, " +
            "locked_until = CASE WHEN failed_attempts + 1 >= :maxFailures THEN :lockUntil ELSE locked_until END " +
            "WHERE id = :id",
        nativeQuery = true,
    )
    fun recordFailure(
        id: UUID,
        maxFailures: Int,
        lockUntil: Instant,
    ): Int

    /** A completed sign-in: the count of consecutive failures starts again from nothing. */
    @Modifying
    @Query("UPDATE admin_accounts SET failed_attempts = 0, locked_until = NULL WHERE id = :id", nativeQuery = true)
    fun clearFailures(id: UUID): Int

    /**
     * Enrolment's fresh secret, written alone and only to an account still `INVITED`: 0 when
     * the account was enrolled, disabled or re-invited meanwhile. A whole-entity flush here
     * would write back a stale snapshot of every other column.
     */
    @Modifying
    @Query("UPDATE admin_accounts SET totp_secret_enc = :sealed WHERE id = :id AND status = 'INVITED'", nativeQuery = true)
    fun replaceInvitedSecret(
        id: UUID,
        sealed: String,
    ): Int
}
