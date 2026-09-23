package com.moyi.identity.service

import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.AccountStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The signed-in user's own record.
 *
 * `readOnly = true`: a hint to Hibernate to skip dirty checking, and a
 * statement to the reader that this path writes nothing.
 */
@Service
internal class GetProfile(
    private val accounts: AccountStore,
) {
    /**
     * @throws AuthenticatedUserMissingException if the id from a valid token
     *   matches no row. The verifier already rejects tokens for users that do
     *   not exist, so this is a race with a deletion — and the right answer
     *   to "you were deleted between the filter and here" is still a 401.
     */
    @Transactional(readOnly = true)
    fun byId(id: UserId): User = accounts.findById(id) ?: throw AuthenticatedUserMissingException()
}

internal class AuthenticatedUserMissingException : RuntimeException("The authenticated user no longer exists")
