package com.moyi.notification.infra.push

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushSender
import com.moyi.notification.api.PushToken
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.util.UUID

/**
 * The wiring, as `EmailSenderConfigurationTest` holds email's (ADR-0017
 * decision 3): FCM is the default, and an environment without its credentials
 * does not start; the logger has to be asked for by name.
 */
internal class PushSenderConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    HttpClientAutoConfiguration::class.java,
                    ImperativeHttpClientAutoConfiguration::class.java,
                    RestClientAutoConfiguration::class.java,
                ),
            ).withBean(Clock::class.java, Clock::systemUTC)
            .withUserConfiguration(PushSenderConfiguration::class.java)

    @Test
    fun `with no configuration at all, the application refuses to start and names the missing properties`() {
        runner.run { context ->
            context.startupFailure.shouldBeInstanceOf<Throwable>()
            rootCauseMessage(context.startupFailure!!) shouldContain "moyi.notification.push.fcm.private-key-pem"
        }
    }

    @Test
    fun `with a service account, FCM is wired`() {
        runner
            .withPropertyValues(
                "moyi.notification.push.fcm.project-id=moyi-test",
                "moyi.notification.push.fcm.client-email=push@moyi-test.iam.gserviceaccount.com",
                "moyi.notification.push.fcm.private-key-pem=${FcmPushSenderTest.PRIVATE_KEY_PEM}",
            ).run { context ->
                context.getBean(PushSender::class.java).shouldBeInstanceOf<FcmPushSender>()
                val fcm = context.getBean(PushProperties::class.java).fcm
                fcm.baseUrl shouldBe "https://fcm.googleapis.com"
                fcm.tokenUri shouldBe "https://oauth2.googleapis.com/token"
            }
    }

    @Test
    fun `the log provider has to be asked for by name, and sends nothing and prints no token`() {
        runner
            .withPropertyValues("moyi.notification.push.provider=log")
            .run { context ->
                val sender = context.getBean(PushSender::class.java)
                sender.shouldBeInstanceOf<LoggingPushSender>()

                val logger = LoggerFactory.getLogger(LoggingPushSender::class.java) as Logger
                val captured = ListAppender<ILoggingEvent>().apply { start() }
                logger.addAppender(captured)
                try {
                    val bond = UUID.randomUUID()
                    sender.send(PushMessage(PushToken("secret-device-token"), PushCategory.MILESTONE, bond)) shouldBe
                        PushDelivery.Accepted(LoggingPushSender.PROVIDER_MESSAGE_ID)
                    val line = captured.list.single().formattedMessage
                    line shouldContain "MILESTONE"
                    line shouldContain bond.toString()
                    line shouldNotContain "secret-device-token"
                } finally {
                    logger.detachAppender(captured)
                }
            }
    }

    private fun rootCauseMessage(failure: Throwable): String = generateSequence(failure) { it.cause }.last().message.orEmpty()
}
