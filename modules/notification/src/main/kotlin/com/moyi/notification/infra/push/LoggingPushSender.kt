package com.moyi.notification.infra.push

import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushSender
import org.slf4j.LoggerFactory

/**
 * A [PushSender] that writes one line and sends nothing: for local
 * development, the tests, and an environment with no Firebase project yet.
 * Selected only by `moyi.notification.push.provider: log`.
 *
 * It reports [PushDelivery.Accepted], because that is what the configured
 * provider did, and its message id says which provider it was, so a `SENT`
 * row made by it cannot be mistaken for a real delivery.
 *
 * **The token is not printed**, not even here. A rule with a local-only
 * exception gets copied without the exception (ADR-0017's note on
 * `LoggingEmailSender`), and a token is a secret wherever it is. What is
 * printed is what a developer needs: the category and the Bond.
 */
internal class LoggingPushSender : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(message: PushMessage): PushDelivery {
        log.info("Push NOT sent (provider=log): category={} bond={}", message.category, message.bondId)
        return PushDelivery.Accepted(PROVIDER_MESSAGE_ID)
    }

    internal companion object {
        const val PROVIDER_MESSAGE_ID = "not-sent:log"
    }
}
