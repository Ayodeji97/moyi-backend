package com.moyi.identity.service

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.infra.database.AccountStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class RequestPasswordReset(
    private val accounts: AccountStore,
    private val verification: RequestVerification,
) {
    /** Always returns normally for unknown and non-active addresses. */
    @Transactional
    fun request(email: Email) {
        val user = accounts.findByEmail(email)
        if (user?.status == UserStatus.ACTIVE) {
            verification.request(user, VerificationPurpose.PASSWORD_RESET)
        }
    }
}
