package com.moyi.admin.service

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.domain.AdminStatus
import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.infra.AdminAccountEntity
import com.moyi.admin.infra.AdminAccountRepository
import com.moyi.admin.infra.Totp
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.common.security.ClientAddress
import com.moyi.identity.api.PasswordHashing
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The two sign-in steps and sign-out (spec §6, §8). Each step is one transaction holding its
 * bookkeeping and its audit row together. A refusal is a returned value, never an exception,
 * so it commits: the failure count, the lock and the `REFUSED` row all stay written.
 *
 * **Lockout.** Five consecutive failures, password and code counted together, lock the
 * account for fifteen minutes. While locked, every attempt is refused without being counted,
 * and a locked account with the right password gets the same answer as a wrong password, so
 * the lock confirms nothing. Only a completed sign-in (both factors) resets the count: if the
 * password step reset it, anyone holding the password could try codes four at a time forever.
 *
 * **Timing.** An unknown email, or an account that cannot sign in, still costs one Argon2
 * verification (`matchesDummy`), so how long a refusal takes does not say whether the
 * account exists (T-18).
 *
 * **Replay.** A code's time step is spent through [AdminAccountRepository.casTotpStep], a
 * compare-and-set in the database, so the same step is accepted once even when two requests
 * carry it at the same moment (RFC 6238 §5.2).
 *
 * Each `ADMIN_SIGN_IN` row also moves `moyi.admin.sign_in{outcome}` (Prometheus:
 * `moyi_admin_sign_in_total`). Nothing here ever logs or records an email, a password or a code.
 */
@Service
@ConditionalOnAdminConsole
internal class AdminSignIn(
    private val accounts: AdminAccountRepository,
    private val passwords: PasswordHashing,
    private val audit: AuditTrail,
    private val clock: Clock,
    private val properties: AdminProperties,
    meters: MeterRegistry,
) {
    private val totp = Totp(clock)

    // Lazily: AdminConfiguration validates the key at startup, with a message that names the
    // property, and this bean may be built before it is.
    private val cipher by lazy { TotpSecretCipher(checkNotNull(properties.keyBytes()) { "moyi.admin.totp-key is not valid base64" }) }
    private val succeeded: Counter = meters.counter(METRIC, "outcome", "succeeded")
    private val refused: Counter = meters.counter(METRIC, "outcome", "refused")

    @Transactional
    @Suppress("ReturnCount") // One return per answer: cannot sign in, refused, accepted.
    fun password(
        email: String,
        password: String,
        from: ClientAddress?,
    ): PasswordStep {
        val account = accounts.findByEmail(email)
        val hash = account?.passwordHash
        if (account == null || hash == null || account.status != AdminStatus.ACTIVE) {
            passwords.matchesDummy(password)
            recordRefusal(account?.getId(), STEP_PASSWORD, if (account == null) "unknown_account" else "not_active", from)
            return PasswordStep.Refused
        }
        // Verified even when locked, so a locked account answers as slowly as an open one.
        val matches = passwords.matches(password, hash)
        val reason =
            when {
                isLocked(account) -> "locked"
                !matches -> "wrong_password".also { countFailure(account) }
                else -> return PasswordStep.Accepted(account.getId(), account.credentialsChangedAt)
            }
        recordRefusal(account.getId(), STEP_PASSWORD, reason, from)
        return PasswordStep.Refused
    }

    @Transactional
    @Suppress("ReturnCount") // One return per reason a code is refused, then the acceptance.
    fun totp(
        adminId: UUID,
        code: String,
        from: ClientAddress?,
    ): TotpStep {
        val account = accounts.findById(adminId).orElse(null)
        val sealed = account?.totpSecretEnc
        if (account == null || sealed == null || account.status != AdminStatus.ACTIVE) {
            recordRefusal(account?.getId(), STEP_TOTP, "not_active", from)
            return TotpStep.Refused
        }
        if (isLocked(account)) {
            recordRefusal(adminId, STEP_TOTP, "locked", from)
            return TotpStep.Refused
        }
        val step = totp.matchingStep(cipher.decrypt(sealed), code)
        // The step is spent here or not at all; a code at or before the last spent step is a replay.
        val reason =
            when {
                step == null -> "wrong_code"
                accounts.casTotpStep(adminId, step) != 1 -> "replayed_code"
                else -> null
            }
        if (reason != null) {
            countFailure(account)
            recordRefusal(adminId, STEP_TOTP, reason, from)
            return TotpStep.Refused
        }
        accounts.clearFailures(adminId)
        audit.record(
            action = AuditAction.ADMIN_SIGN_IN,
            outcome = AuditOutcome.SUCCEEDED,
            actorId = adminId,
            targetType = null,
            targetId = null,
            clientAddress = from,
        )
        succeeded.increment()
        return TotpStep.Accepted(account.role)
    }

    @Transactional
    fun signOut(
        adminId: UUID,
        from: ClientAddress?,
    ) {
        audit.record(
            action = AuditAction.ADMIN_SIGN_OUT,
            outcome = AuditOutcome.SUCCEEDED,
            actorId = adminId,
            targetType = null,
            targetId = null,
            clientAddress = from,
        )
    }

    private fun isLocked(account: AdminAccountEntity): Boolean = account.lockedUntil?.isAfter(clock.instant()) == true

    private fun countFailure(account: AdminAccountEntity) {
        accounts.recordFailure(account.getId(), MAX_FAILURES, lockUntil())
    }

    private fun lockUntil(): Instant = clock.instant().plus(LOCK_DURATION)

    /** The `REFUSED` row: the step and why, never what was typed. An unknown email has no actor. */
    private fun recordRefusal(
        actorId: UUID?,
        step: String,
        reason: String,
        from: ClientAddress?,
    ) {
        audit.record(
            action = AuditAction.ADMIN_SIGN_IN,
            outcome = AuditOutcome.REFUSED,
            actorId = actorId,
            targetType = null,
            targetId = null,
            metadata = mapOf("step" to step, "reason" to reason),
            clientAddress = from,
        )
        refused.increment()
    }

    private companion object {
        const val METRIC = "moyi.admin.sign_in"
        const val MAX_FAILURES = 5
        val LOCK_DURATION: Duration = Duration.ofMinutes(15)
        const val STEP_PASSWORD = "password"
        const val STEP_TOTP = "totp"
    }
}

internal sealed interface PasswordStep {
    /** [credentialsChangedAt] as read from the row: the session guard compares the row with it. */
    data class Accepted(
        val adminId: UUID,
        val credentialsChangedAt: Instant,
    ) : PasswordStep

    data object Refused : PasswordStep
}

internal sealed interface TotpStep {
    data class Accepted(
        val role: AdminRole,
    ) : TotpStep

    data object Refused : TotpStep
}
