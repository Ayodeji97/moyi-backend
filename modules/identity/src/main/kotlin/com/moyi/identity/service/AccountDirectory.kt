package com.moyi.identity.service

import com.moyi.identity.api.UserDirectory
import com.moyi.identity.api.UserSummary
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.AccountStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The `api` port, over the account store.
 *
 * `internal`, like everything else here: another module gets the interface
 * from `identity.api` and this class is invisible to it. `readOnly = true`
 * says the same thing to Hibernate and to the reader.
 */
@Service
internal class AccountDirectory(
    private val accounts: AccountStore,
) : UserDirectory {
    @Transactional(readOnly = true)
    override fun find(id: UUID): UserSummary? = accounts.findById(UserId(id))?.toSummary()

    @Transactional(readOnly = true)
    override fun findAll(ids: Collection<UUID>): Map<UUID, UserSummary> {
        // An empty `IN ()` is a query that cannot match; not asking is both
        // faster and clearer than asking and discarding.
        if (ids.isEmpty()) return emptyMap()
        return accounts.findAllById(ids.map(::UserId)).associate { it.id.value to it.toSummary() }
    }

    private fun User.toSummary(): UserSummary = UserSummary(id = id.value, displayName = displayName, emailVerified = isEmailVerified)
}
