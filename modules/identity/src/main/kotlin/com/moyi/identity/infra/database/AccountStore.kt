package com.moyi.identity.infra.database

import com.moyi.identity.domain.ConsentRecord
import com.moyi.identity.domain.Credentials
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import org.springframework.stereotype.Component

/**
 * Accounts, spoken in domain terms.
 *
 * It exists because "create an account" is one thing and three tables, and a
 * service that holds three repositories to express one operation is holding
 * the wrong collaborators. It also keeps the entity mappers where they
 * belong: the service deals in domain objects and never imports a `toEntity`.
 *
 * Deliberately **not** transactional itself. The boundary is the caller's
 * (doc 18 §4: transaction boundaries at the service layer, explicit and
 * narrow), and it has to be, because `RegisterUser` needs to survive
 * [insert] failing.
 *
 * Was `AccountWriter` until the verification slice needed to read and update
 * a user as well as create one; a "writer" with `findByEmail` on it would
 * have been a name that lies.
 */
@Component
internal class AccountStore(
    private val users: UserRepository,
    private val credentials: CredentialsRepository,
    private val consents: ConsentRecordRepository,
) {
    /**
     * The three rows that make up a new account. The verification token that
     * FR-002 requires is written in the same transaction by
     * `RequestVerification`, which registration and resend share; it is not
     * here because it is not only registration's.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if the email is already registered —
     *   which is an expected outcome here, not a bug. See [IdentityConstraints].
     */
    fun insert(
        user: User,
        newCredentials: Credentials,
        newConsents: List<ConsentRecord>,
    ) {
        users.save(user.toEntity())
        credentials.save(newCredentials.toEntity())
        consents.saveAll(newConsents.map { it.toEntity() })
    }

    fun findByEmail(email: Email): User? = users.findByEmail(email.value)?.toDomain()

    /** Password hashes are reachable only through the authentication path. */
    fun findCredentials(userId: UserId): Credentials? = credentials.findById(userId.value)?.toDomain()

    fun findById(id: UserId): User? = users.findById(id.value).orElse(null)?.toDomain()

    /**
     * Carries a changed [User] onto its existing row. Loads the managed entity
     * and applies the change to *it*, rather than saving a fresh entity that
     * claims to be new — see [applyTo] for the lost-update that would cause.
     */
    fun update(user: User) {
        val entity =
            users.findById(user.id.value).orElseThrow {
                IllegalStateException("cannot update a user that does not exist")
            }
        user.applyTo(entity)
        users.save(entity)
    }
}
