package com.moyi.notification.infra.email

import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailMessage
import com.moyi.notification.api.EmailSender
import org.slf4j.LoggerFactory

/**
 * An [EmailSender] that writes the message to the log and sends nothing.
 *
 * Exists for one reason: a developer running the application locally needs
 * to read the verification link, and has no mailbox for the provider to
 * deliver to. It is selected only by `moyi.notification.email.provider: log`,
 * which only the `local` profile sets — see [EmailProperties] for why it is
 * not the default.
 *
 * It reports [EmailDelivery.Accepted], because from the caller's side that is
 * what happened: the configured provider took the message. The `providerMessageId`
 * says which provider, so a log line downstream cannot be mistaken for a real
 * delivery.
 *
 * **The recipient is still masked.** Doc 11 NFR-044 keeps addresses out of
 * logs everywhere, and a rule with a local-only exception is a rule that gets
 * copied without the exception. The domain survives — enough to tell the
 * developer they sent to the address they meant — and the body is printed in
 * full, because the body is the point.
 */
internal class LoggingEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: EmailMessage): EmailDelivery {
        log.info(
            "Email NOT sent (provider=log). To: {} | Subject: {}\n{}",
            mask(message.to),
            message.subject,
            message.text,
        )
        return EmailDelivery.Accepted(PROVIDER_MESSAGE_ID)
    }

    private fun mask(address: String): String {
        val at = address.indexOf('@')
        return if (at > 0) "***${address.substring(at)}" else "***"
    }

    private companion object {
        const val PROVIDER_MESSAGE_ID = "not-sent:log"
    }
}
