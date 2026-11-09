package com.moyi.identity.service

import com.moyi.identity.api.AccountExports
import com.moyi.identity.api.ExportedAccount
import com.moyi.identity.api.ExportedConsent
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.ConsentStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** [AccountExports]: the user row and its consent records, nothing derived. */
@Service
internal class ExportAccount(
    private val accounts: AccountStore,
    private val consents: ConsentStore,
) : AccountExports {
    @Transactional(readOnly = true)
    override fun accountOf(userId: UUID): ExportedAccount? {
        val user = accounts.findById(UserId(userId))?.takeUnless { it.status == UserStatus.DELETED } ?: return null
        return ExportedAccount(
            id = user.id.value,
            email = user.email.value,
            emailVerifiedAt = user.emailVerifiedAt,
            displayName = user.displayName,
            locale = user.locale,
            status = user.status.name,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            consents =
                consents
                    .findAll(UserId(userId))
                    .sortedBy { it.acceptedAt }
                    .map { ExportedConsent(it.document.name, it.version, it.acceptedAt) },
        )
    }
}
