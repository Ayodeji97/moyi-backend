package com.moyi.identity.service

import com.moyi.identity.api.AccountMail
import com.moyi.identity.api.AccountMailOutcome
import com.moyi.identity.api.AccountMessage
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.database.AccountStore
import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailMessage
import com.moyi.notification.api.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The [AccountMail] port (ADR-0057 decision 6): reads the account, and writes to its address only
 * when that address is confirmed and the account is not being deleted. Logs the outcome by user id,
 * never the address (NFR-044) and never the message's text.
 *
 * Not `@Transactional`: the one read is the store's own, and the send must not hold a connection
 * while the provider answers.
 */
@Service
internal class AccountMailer(
    private val accounts: AccountStore,
    private val emails: EmailSender,
) : AccountMail {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendToAccountHolder(
        userId: UUID,
        message: AccountMessage,
    ): AccountMailOutcome {
        val user = accounts.findById(UserId(userId))
        return when {
            user == null || user.deletedAt != null -> {
                AccountMailOutcome.NO_ACCOUNT
            }

            !user.isEmailVerified -> {
                AccountMailOutcome.NOT_VERIFIED
            }

            else -> {
                val email =
                    EmailMessage(
                        to = user.email.value,
                        subject = message.subject,
                        text = message.text,
                        html = message.html,
                        idempotencyKey = message.idempotencyKey,
                    )
                when (val delivery = emails.send(email)) {
                    is EmailDelivery.Accepted -> {
                        AccountMailOutcome.ACCEPTED
                    }

                    is EmailDelivery.Rejected -> {
                        log.warn("Account mail {} REJECTED for user {}: {}", message.idempotencyKey, userId, delivery.reason)
                        AccountMailOutcome.FAILED
                    }

                    is EmailDelivery.Unavailable -> {
                        log.warn(
                            "Account mail {} not sent for user {}, provider unavailable: {}",
                            message.idempotencyKey,
                            userId,
                            delivery.reason,
                        )
                        AccountMailOutcome.FAILED
                    }
                }
            }
        }
    }
}
