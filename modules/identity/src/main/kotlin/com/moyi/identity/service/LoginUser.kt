package com.moyi.identity.service

import com.moyi.identity.domain.Credentials
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.domain.User
import com.moyi.identity.infra.database.AccountStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * `POST /auth/login` (FR-003): a password against a stored Argon2id hash, then
 * an access token and a fresh refresh-token family.
 *
 * **Every refusal is the same refusal.** Wrong password, unknown address,
 * locked account, suspended account: one `InvalidCredentialsException`, one
 * 401, one body. Doc 06 §2's security rule says authentication failures must
 * be identical "whether the email exists or not", and T-18 says a visible
 * lockout is itself an enumeration oracle because only a real account can be
 * locked.
 *
 * **Every refusal costs the same.** The unknown-address path verifies against
 * a fixed dummy hash (doc 09 §3), so the ~150 ms of Argon2id is paid whether
 * or not the address exists; a locked account still has its password verified
 * and the result thrown away. The test for this is a *counter* on the hasher,
 * not a stopwatch — doc 12 §3.3 is explicit that a timing comparison is not a
 * test.
 *
 * **The verify is outside every transaction**, like the hash in `RegisterUser`:
 * ~150 ms of CPU while holding a pooled connection turns a cost into an outage.
 * The two writes — count a failure, or clear the count and issue a family —
 * are each a small explicit boundary of their own.
 *
 * FR-002: an unverified account signs in. What it may not do is create or join
 * a Bond, and that is the Bond module's rule, not this class's.
 */
@Service
internal class LoginUser(
    private val accounts: AccountStore,
    private val passwords: PasswordHasher,
    private val sessions: IssueSessionTokens,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun login(command: LoginCommand): LoginResult {
        val now = clock.instant()

        // A malformed address cannot belong to anyone; it takes the unknown
        // path rather than a 422, so the request's shape reveals nothing the
        // response would not.
        val email = runCatching { Email(command.email.trim()) }.getOrNull()
        val user = email?.let(accounts::findByEmail)
        val credentials = user?.let { accounts.findCredentials(it.id) }

        val passwordMatches =
            if (credentials == null) {
                passwords.matchesDummy(command.password)
            } else {
                passwords.matches(command.password, credentials.passwordHash)
            }

        val authenticated = user?.takeIf { credentials != null && it.admits(credentials, passwordMatches, now) }
        if (authenticated == null) {
            // Counted only for a real, sign-in-able account with a wrong
            // password. The store refuses to count during a lock, so a lock
            // cannot be extended by hammering it.
            val countable = user?.takeIf { credentials != null && it.canAuthenticate && !passwordMatches }
            countable?.let { failed -> transactions.executeWithoutResult { accounts.recordFailedLogin(failed.id, now) } }
            // The user id, when there is one, is not personal data and is what
            // a rate graph needs. Nothing about *why* is logged at INFO — the
            // reasons are exactly the distinctions the response withholds.
            log.info("Login refused{}", user?.let { " for user ${it.id.value}" } ?: "")
            throw InvalidCredentialsException()
        }

        val refreshToken =
            transactions.execute {
                accounts.clearFailedLogins(authenticated.id, now)
                sessions.newFamily(authenticated.id, command.deviceInfo, now)
            }!!
        log.info("Login succeeded for user {}", authenticated.id.value)

        return LoginResult(
            user = authenticated,
            tokens = SessionTokens(sessions.accessTokenFor(authenticated.id), refreshToken),
        )
    }

    /** Right password, a status that may sign in (FR-002), and no lock in force. */
    private fun User.admits(
        credentials: Credentials,
        passwordMatches: Boolean,
        now: Instant,
    ): Boolean = passwordMatches && canAuthenticate && !credentials.isLockedAt(now)
}

/**
 * The service's contract, with no HTTP in it. `deviceInfo` is the client's
 * free-text description of itself (`"Pixel 9 · Android 16 · 1.0.3"`); doc 07's
 * `devices` table arrives with FR-007's session list and this string moves
 * into it then — ADR-0020 records the deferral.
 */
internal data class LoginCommand(
    val email: String,
    val password: String,
    val deviceInfo: String?,
) {
    override fun toString(): String = "LoginCommand(email=<redacted>, password=<redacted>, deviceInfo=$deviceInfo)"
}

internal data class LoginResult(
    val user: User,
    val tokens: SessionTokens,
)

/** Thrown for every refusal, deliberately without a reason. The web layer maps it to `401 INVALID_CREDENTIALS`. */
internal class InvalidCredentialsException : RuntimeException("The credentials were not accepted")
