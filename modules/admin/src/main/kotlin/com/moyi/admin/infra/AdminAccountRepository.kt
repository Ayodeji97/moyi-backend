package com.moyi.admin.infra

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
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
}
