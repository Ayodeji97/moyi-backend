package com.moyi.admin.service

import com.moyi.admin.domain.AdminPassword
import com.moyi.admin.domain.AdminStatus
import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.infra.AdminAccountEntity
import com.moyi.admin.infra.AdminAccountRepository
import com.moyi.admin.infra.AdminInvitationEntity
import com.moyi.admin.infra.AdminInvitationRepository
import com.moyi.admin.infra.QrCode
import com.moyi.admin.infra.Totp
import com.moyi.admin.infra.TotpSecretCipher
import com.moyi.common.security.ClientAddress
import com.moyi.identity.api.PasswordHashing
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * The first visit, by invitation (spec §6). [begin] shows a fresh TOTP secret and stores it,
 * sealed, in the still-`INVITED` account, replacing any earlier one, so the page can be
 * reloaded. [complete] checks the password policy and one current code; only a correct code
 * makes the account `ACTIVE`, stores the password, spends the code's step and the invitation,
 * and writes `ADMIN_ENROLLED`. Any refusal changes nothing. Enrolment does not sign anyone in.
 *
 * An invalid, expired or used token, or one whose account is no longer `INVITED`, is one and
 * the same answer: [begin] gives `null`, [complete] gives [EnrolmentResult.InvalidLink].
 */
@Service
@ConditionalOnAdminConsole
internal class AdminEnrolment(
    private val accounts: AdminAccountRepository,
    private val invitations: AdminInvitationRepository,
    private val passwords: PasswordHashing,
    private val audit: AuditTrail,
    private val clock: Clock,
    private val properties: AdminProperties,
) {
    private val totp = Totp(clock)

    // Lazily: AdminConfiguration validates the key at startup, with a message that names the
    // property, and this bean may be built before it is.
    private val cipher by lazy { TotpSecretCipher(checkNotNull(properties.keyBytes()) { "moyi.admin.totp-key is not valid base64" }) }
    private val issuer = properties.issuer

    /** A fresh secret for the invited account, or `null` when the link is not valid. */
    @Transactional
    fun begin(token: String): EnrolmentPage? {
        val (_, account) = openInvitation(token, forUpdate = false) ?: return null
        val secret = totp.newSecret()
        account.totpSecretEnc = cipher.encrypt(secret)
        return page(account, secret)
    }

    /** The secret [begin] stored, shown again beside a refusal, so what was scanned still holds. */
    @Transactional(readOnly = true)
    @Suppress("ReturnCount") // No valid link, or no secret yet: nothing to show again.
    fun current(token: String): EnrolmentPage? {
        val (_, account) = openInvitation(token, forUpdate = false) ?: return null
        val sealed = account.totpSecretEnc ?: return null
        return page(account, cipher.decrypt(sealed))
    }

    @Transactional
    @Suppress("ReturnCount") // One return per refusal, in the order the page reports them.
    fun complete(
        token: String,
        password: String,
        confirm: String,
        code: String,
        from: ClientAddress?,
    ): EnrolmentResult {
        val (invitation, account) = openInvitation(token, forUpdate = true) ?: return EnrolmentResult.InvalidLink
        if (password != confirm) return EnrolmentResult.PasswordMismatch
        val chosen = AdminPassword.of(password) ?: return EnrolmentResult.PasswordTooShortOrLong
        if (passwords.isBreached(chosen.value)) return EnrolmentResult.PasswordBreached
        // Hashed before the code is spent: identity's shape rules (UTF-16 length, bytes after
        // NFKC) can still refuse what AdminPassword's code-point count let through.
        val hash =
            try {
                passwords.hash(chosen.value)
            } catch (_: IllegalArgumentException) {
                return EnrolmentResult.PasswordTooShortOrLong
            }
        val sealed = account.totpSecretEnc ?: return EnrolmentResult.WrongCode
        val step = totp.matchingStep(cipher.decrypt(sealed), code) ?: return EnrolmentResult.WrongCode
        if (accounts.casTotpStep(account.getId(), step) != 1) return EnrolmentResult.WrongCode

        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        account.apply {
            passwordHash = hash
            // What the compare-and-set just wrote; the entity is flushed whole, so it must agree.
            totpLastStep = step
            status = AdminStatus.ACTIVE
            activatedAt = now
            credentialsChangedAt = now
            failedAttempts = 0
            lockedUntil = null
        }
        invitation.consumedAt = now
        audit.record(
            action = AuditAction.ADMIN_ENROLLED,
            outcome = AuditOutcome.SUCCEEDED,
            actorId = account.getId(),
            targetType = TARGET_ADMIN_ACCOUNT,
            targetId = account.getId().toString(),
            clientAddress = from,
        )
        return EnrolmentResult.Done
    }

    @Suppress("ReturnCount") // One return per reason a link is not valid; each is the same answer.
    private fun openInvitation(
        token: String,
        forUpdate: Boolean,
    ): Pair<AdminInvitationEntity, AdminAccountEntity>? {
        val hash = tokenHash(token) ?: return null
        val invitation =
            (if (forUpdate) invitations.findForUpdateByTokenHash(hash) else invitations.findByTokenHash(hash))
                ?.takeIf { it.consumedAt == null && clock.instant().isBefore(it.expiresAt) }
                ?: return null
        val account = accounts.findById(invitation.adminId).orElse(null)?.takeIf { it.status == AdminStatus.INVITED } ?: return null
        return invitation to account
    }

    private fun page(
        account: AdminAccountEntity,
        secret: ByteArray,
    ) = EnrolmentPage(
        email = account.email,
        qrDataUri = QrCode.pngDataUri(totp.otpauthUri(secret, account.email, issuer)),
        secretBase32 = totp.base32(secret),
    )

    private companion object {
        const val TOKEN_BYTES = 32
        const val TARGET_ADMIN_ACCOUNT = "ADMIN_ACCOUNT"

        /** `admin_invite` stores the SHA-256 of the 32 raw bytes, and hands out their base64url. */
        fun tokenHash(token: String): ByteArray? {
            val raw = runCatching { Base64.getUrlDecoder().decode(token) }.getOrNull()
            return raw?.takeIf { it.size == TOKEN_BYTES }?.let { MessageDigest.getInstance("SHA-256").digest(it) }
        }
    }
}

/** What the enrolment page shows. Every field is a secret or personal, so none is printed. */
internal data class EnrolmentPage(
    val email: String,
    val qrDataUri: String,
    val secretBase32: String,
) {
    override fun toString() = "EnrolmentPage(<redacted>)"
}

internal sealed interface EnrolmentResult {
    data object Done : EnrolmentResult

    data object InvalidLink : EnrolmentResult

    data object PasswordMismatch : EnrolmentResult

    data object PasswordTooShortOrLong : EnrolmentResult

    data object PasswordBreached : EnrolmentResult

    data object WrongCode : EnrolmentResult
}
