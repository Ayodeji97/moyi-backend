package com.moyi.notification.infra

import com.moyi.common.testing.MutableClock
import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushSender
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A Spring context just large enough for this module: the outbox (publisher,
 * registry, dispatcher), the ledger, the push tokens and the consumer, on the
 * shared Testcontainers Postgres. `notification` is a leaf, so nothing of any
 * domain module is here, and nothing needs faking but the two edges a test
 * moves by hand: the clock, and the provider.
 *
 * The provider is a [ScriptedPushSender] rather than `LoggingPushSender`, so
 * a test can say what each phone answers and read what each phone was sent.
 * The real FCM adapter is tested by the HTTP it produces (`FcmPushSenderTest`).
 */
@SpringBootApplication(scanBasePackages = ["com.moyi.common.core", "com.moyi.common.events", "com.moyi.notification"])
internal class NotificationTestApplication {
    @Bean
    @Primary
    fun testClock(): MutableClock = MutableClock(Instant.parse("2026-10-27T18:00:00Z"))

    @Bean
    @Primary
    fun scriptedPushSender(): ScriptedPushSender = ScriptedPushSender()
}

/**
 * A [PushSender] whose answers are set per token, and which keeps every
 * message it was handed. Unscripted tokens are accepted.
 */
internal class ScriptedPushSender : PushSender {
    val sent: MutableList<PushMessage> = CopyOnWriteArrayList()
    private val answers = mutableMapOf<String, PushDelivery>()

    /** Runs as each message is sent, inside the delivery's transaction: where a test makes something happen mid-send. */
    @Volatile
    var duringSend: (PushMessage) -> Unit = {}

    fun answer(
        token: String,
        delivery: PushDelivery,
    ) {
        answers[token] = delivery
    }

    fun reset() {
        sent.clear()
        answers.clear()
        duringSend = {}
    }

    override fun send(message: PushMessage): PushDelivery {
        sent += message
        duringSend(message)
        return answers[message.token.value] ?: PushDelivery.Accepted("projects/test/messages/${sent.size}")
    }
}
