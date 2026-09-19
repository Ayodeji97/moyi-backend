package com.moyi.identity.infra.database

import com.moyi.identity.domain.ConsentRecord
import com.moyi.identity.domain.Credentials
import com.moyi.identity.domain.User
import org.springframework.stereotype.Component

/**
 * Writes the three rows that make up a new account.
 *
 * It exists because "create an account" is one thing and three tables, and a
 * service that holds three repositories to express one operation is holding
 * the wrong collaborators. This also keeps the entity mappers where they
 * belong: the service deals in domain objects and never imports a `toEntity`.
 *
 * Deliberately **not** transactional itself. The boundary is the caller's
 * (doc 18 §4: transaction boundaries at the service layer, explicit and
 * narrow), and it has to be, because the caller needs to survive this
 * failing — see `RegisterUser`.
 */
@Component
internal class AccountWriter(
    private val users: UserRepository,
    private val credentials: CredentialsRepository,
    private val consents: ConsentRecordRepository,
) {
    /**
     * @throws org.springframework.dao.DataIntegrityViolationException if the email is already registered —
     *   which is an expected outcome here, not a bug. See [IdentityConstraints].
     */
    fun insert(
        user: User,
        credentials: Credentials,
        consents: List<ConsentRecord>,
    ) {
        users.save(user.toEntity())
        this.credentials.save(credentials.toEntity())
        this.consents.saveAll(consents.map { it.toEntity() })
    }
}
