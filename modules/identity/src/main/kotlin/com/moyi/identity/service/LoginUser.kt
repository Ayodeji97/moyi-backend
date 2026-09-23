package com.moyi.identity.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.IssuedAccessToken
import com.moyi.identity.domain.Credentials
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.infra.database.AccountStore
import org.springframework.stereotype.Service

/**
 * Authenticates a user and issues the access-token half of the login response.
 *
 * Unknown addresses deliberately run a dummy Argon2 verification. Returning the
 * same 401 for an unknown address, a wrong password, and a non-active account
 * keeps both the response and the timing from becoming an account oracle.
 */
@Service
internal class LoginUser(
    private val accounts: AccountStore,
    private val passwords: PasswordHasher,
    private val accessTokens: AccessTokenIssuer,
    private val refreshTokens: RefreshTokens,
) {
    fun login(command: LoginCommand): LoginResult {
        val email = runCatching { Email(command.email.trim()) }.getOrNull()
        val user = email?.let(accounts::findByEmail)
        val credentials = user?.let { accounts.findCredentials(it.id) }
        val passwordMatches =
            if (credentials == null) {
                passwords.matchesDummy(command.password)
            } else {
                passwords.matches(command.password, credentials.passwordHash)
            }

        val authenticatedUser = authenticatedUser(user, credentials, passwordMatches)
        if (authenticatedUser == null) {
            throw InvalidCredentialsException()
        }

        return LoginResult(
            user = authenticatedUser,
            accessToken = accessTokens.issue(authenticatedUser.id.value),
            refreshToken = refreshTokens.issue(authenticatedUser.id, command.deviceInfo),
        )
    }

    private fun authenticatedUser(
        user: User?,
        credentials: Credentials?,
        passwordMatches: Boolean,
    ): User? = user?.takeIf { passwordMatches && credentials != null && it.status == UserStatus.ACTIVE }
}

internal data class LoginCommand(
    val email: String,
    val password: String,
    val deviceInfo: String?,
)

internal data class LoginResult(
    val user: User,
    val accessToken: IssuedAccessToken,
    val refreshToken: IssuedRefreshToken,
)

internal class InvalidCredentialsException : RuntimeException("The credentials were not accepted")
