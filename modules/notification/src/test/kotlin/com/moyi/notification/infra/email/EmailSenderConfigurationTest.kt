package com.moyi.notification.infra.email

import com.moyi.notification.api.EmailSender
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The wiring, under the three configurations that matter. The first is the
 * one this test exists for: an environment that forgot the key must not
 * start, because an application that starts without a way to send email
 * looks healthy from every dashboard and fails only for users.
 */
internal class EmailSenderConfigurationTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    HttpClientAutoConfiguration::class.java,
                    ImperativeHttpClientAutoConfiguration::class.java,
                    RestClientAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(EmailSenderConfiguration::class.java)

    @Test
    fun `with no configuration at all, the application refuses to start and names the missing property`() {
        runner.run { context ->
            context.startupFailure.shouldBeInstanceOf<Throwable>()
            rootCauseMessage(context.startupFailure!!) shouldContain "moyi.notification.email.resend.api-key"
        }
    }

    @Test
    fun `with a key and a sender, the real provider is wired`() {
        runner
            .withPropertyValues(
                "moyi.notification.email.from=Moyi <hello@moyi.test>",
                "moyi.notification.email.resend.api-key=re_test",
            ).run { context ->
                context.getBean(EmailSender::class.java).shouldBeInstanceOf<ResendEmailSender>()
            }
    }

    @Test
    fun `the log provider has to be asked for by name`() {
        runner
            .withPropertyValues("moyi.notification.email.provider=log")
            .run { context ->
                context.getBean(EmailSender::class.java).shouldBeInstanceOf<LoggingEmailSender>()
                // And the properties bound with defaults for everything else.
                context.getBean(EmailProperties::class.java).resend.baseUrl shouldBe "https://api.resend.com"
            }
    }

    private fun rootCauseMessage(failure: Throwable): String = generateSequence(failure) { it.cause }.last().message.orEmpty()
}
