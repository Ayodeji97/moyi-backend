package com.moyi.identity.infra.security

import com.moyi.common.security.TokenRevocation
import com.moyi.common.security.TokenRevocations
import com.moyi.identity.infra.database.UserRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `common:security`'s [TokenRevocations] port, answered from
 * `users.tokens_invalid_before`.
 *
 * This runs on every authenticated request, so it reads two columns and not
 * the row — `UserRepository.findRevocation` is a projection. A user who has
 * been erased (the row is gone) is reported as `null`, which the validator
 * turns into a refused token: an access token must not outlive its account
 * by up to fifteen minutes just because nothing bumped a column the row no
 * longer has.
 *
 * Not `@Transactional`: one read, auto-commit, no state to keep consistent
 * with anything else.
 */
@Component
internal class UserTokenRevocations(
    private val users: UserRepository,
) : TokenRevocations {
    override fun forUser(userId: UUID): TokenRevocation? = users.findRevocation(userId)?.let { TokenRevocation(it.tokensInvalidBefore) }
}
