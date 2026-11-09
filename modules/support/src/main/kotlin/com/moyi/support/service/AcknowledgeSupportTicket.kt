package com.moyi.support.service

import com.moyi.identity.api.AccountMail
import com.moyi.identity.api.AccountMessage
import com.moyi.support.domain.SupportTicketFiled
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * FR-102's acknowledgement email: after a ticket commits, its sender is told, at their account's
 * address, that it arrived (ADR-0057 decision 5). `AFTER_COMMIT` and `@Async`, as identity's
 * `SendSecurityNotice` is, with the same accepted gap: a process death between the commit and the
 * send loses the email, and the ticket, which is what matters, is kept.
 *
 * **It never repeats what was written**, neither the subject nor the body: an email passes through a
 * provider, a mailbox and whoever else reads that mailbox, and the words are the sender's to the
 * operator alone. It says the message arrived, gives its reference, and says why the words are not in it.
 *
 * Transactional, not marketing: one message, about a thing the person just did, with nothing to
 * unsubscribe from. Keyed by the ticket, so a provider retry cannot deliver it twice.
 */
@Component
internal class AcknowledgeSupportTicket(
    private val mail: AccountMail,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(filed: SupportTicketFiled) {
        val outcome = mail.sendToAccountHolder(filed.userId, acknowledgement(filed))
        log.info("Support ticket {} acknowledgement: {}", filed.ticketId, outcome)
    }

    companion object {
        const val SUBJECT = "We have your message"

        /** The email, from the ticket's id alone: nothing the sender wrote can reach it. */
        internal fun acknowledgement(filed: SupportTicketFiled): AccountMessage {
            val paragraphs =
                listOf(
                    "Thank you for writing to Moyi. Your message has arrived, and a person will read it and reply to this address.",
                    "Your reference is ${filed.ticketId}.",
                    "We do not repeat what you wrote in this email, so that it stays between you and us.",
                )
            return AccountMessage(
                subject = SUBJECT,
                text = "Hello,\n\n" + paragraphs.joinToString("\n\n"),
                html = "<p>Hello,</p>" + paragraphs.joinToString("") { "<p>$it</p>" },
                idempotencyKey = "support-ack-${filed.ticketId}",
            )
        }
    }
}
