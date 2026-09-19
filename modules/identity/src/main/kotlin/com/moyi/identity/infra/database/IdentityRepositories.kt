package com.moyi.identity.infra.database

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.Repository
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
