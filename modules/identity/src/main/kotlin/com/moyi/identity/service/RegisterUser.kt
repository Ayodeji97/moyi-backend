package com.moyi.identity.service

import com.moyi.common.core.IdGenerator
import com.moyi.identity.domain.ConsentDocument
import com.moyi.identity.domain.ConsentRecord
import com.moyi.identity.domain.Credentials
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.infra.database.AccountWriter
import com.moyi.identity.infra.database.IdentityConstraints
import com.moyi.identity.infra.database.violates
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

/**
 * Registers an account (FR-001, FR-011).
 *
 * Two decisions shape this class, and both are visible in its structure.
 *
 * **It never says whether the address was already registered.** ADR-0015:
 * the caller gets the same outcome either way, so registration is not an
 * oracle for "does this person have an account here". That is why there is no
 * pre-flight `existsByEmail` and no thrown conflict — the insert is attempted
 * and a unique-constraint violation is absorbed.
 *
 * **The hash is computed outside the transaction, and always.** Outside,
 * because Argon2id deliberately takes ~150 ms of CPU and memory, and doing
 * that while holding a pooled database connection converts a cost into an
 * outage — the pool empties under load that the database itself could
 * trivially serve. Always, because skipping it on the duplicate path would
 * make that path ~150 ms faster and hand back through timing exactly what the
 * identical response withholds (T-18).
 */
@Service
internal class RegisterUser(
    private val accounts: AccountWriter,
    private val hasher: PasswordHasher,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val transactions: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun register(command: RegistrationCommand) {
        val passwordHash = hasher.hash(command.password)
        val now = clock.instant()

        val user =
            User(
                id = UserId(ids.timeOrdered()),
                email = command.email,
                emailVerifiedAt = null,
                displayName = command.displayName,
                avatarMediaId = null,
                locale = command.locale,
                // FR-002: the account exists but can do almost nothing until
                // the address is confirmed. The email that would move it out
                // of this state arrives with the verification slice.
                status = UserStatus.PENDING_VERIFICATION,
                createdAt = now,
                updatedAt = null,
                deletedAt = null,
            )

        try {
            // An explicit boundary rather than @Transactional on this method,
            // for two reasons that both matter. The hash above must be outside
            // it. And a @Transactional method cannot catch its own constraint
            // violation and return successfully: the exception has already
            // marked the transaction rollback-only, so the commit that follows
            // fails with UnexpectedRollbackException. The catch has to sit
            // outside the boundary, which means the boundary has to be
            // something you can see.
            transactions.executeWithoutResult {
                accounts.insert(
                    user = user,
                    newCredentials =
                        Credentials(
                            userId = user.id,
                            passwordHash = passwordHash,
                            algorithm = hasher.algorithm,
                            passwordUpdatedAt = now,
                            failedAttempts = 0,
                            lockedUntil = null,
                        ),
                    newConsents = consentRecordsFor(user, command, now),
                )
            }
        } catch (violation: DataIntegrityViolationException) {
            // Only this one constraint is an expected outcome. Treating every
            // integrity violation as "already registered" would report a
            // genuine bug — a NOT NULL breach, a bad foreign key — to the user
            // as a successful sign-up, and to us as nothing at all.
            if (!violation.violates(IdentityConstraints.USERS_EMAIL_UNIQUE)) throw violation

            // Logged without the address: knowing the *rate* of duplicate
            // registrations is operationally useful, knowing whose is not, and
            // doc 18 §9 keeps personal data out of logs regardless.
            log.info("Registration attempted for an address that already exists; responding as success (ADR-0015)")
        }
    }

    /**
     * FR-011's three rows. One version string covers all three because the
     * user accepted one form, on one screen, at one moment — `06`'s request
     * carries a single `acceptedTermsVersion` for exactly that reason.
     */
    private fun consentRecordsFor(
        user: User,
        command: RegistrationCommand,
        now: java.time.Instant,
    ): List<ConsentRecord> =
        ConsentDocument.entries.map { document ->
            ConsentRecord(
                id = ids.timeOrdered(),
                userId = user.id,
                document = document,
                version = command.acceptedTermsVersion,
                acceptedAt = now,
                ipHash = null,
                userAgentHash = null,
            )
        }
}

/**
 * What the service needs to register someone, with no HTTP in it — the web
 * layer's request DTO is a separate type that maps onto this one, so that
 * changing the wire format cannot quietly change the service's contract
 * (doc 18 §4: no entity, and no request object, crosses a layer it does not
 * belong to).
 */
internal data class RegistrationCommand(
    val email: Email,
    /**
     * The domain type, not a `String`. Two reasons, and the second is the one
     * that matters: a value that has already been normalised and checked
     * cannot arrive here invalid, and `Password.toString()` redacts — so the
     * plaintext cannot be printed by this class's own generated `toString`,
     * by a log statement that interpolates it, or by an exception message
     * that embeds it.
     */
    val password: Password,
    val displayName: String,
    val locale: String,
    val acceptedTermsVersion: String,
)
