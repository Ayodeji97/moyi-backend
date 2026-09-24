package com.moyi.identity.service

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.infra.database.AccountStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class RequestPasswordReset(
    private val accounts: AccountStore,
    private val verification: RequestVerification,
) {
    /**
     * `202` always (doc 06 §3.1, FR-004): an unknown address, a suspended
     * account and a pending one all return normally; only an account that
     * could sign in (FR-002 includes the unverified — a person who forgot the
     * password before verifying is otherwise locked out of their own address)
     * gets a token and an email. Same non-disclosure rule as `ResendVerification`.
     */
    @Transactional
    fun request(email: Email) {
        val user = accounts.findByEmail(email)
        if (user != null && user.canAuthenticate) {
            verification.request(user, VerificationPurpose.PASSWORD_RESET)
        }
    }
}
