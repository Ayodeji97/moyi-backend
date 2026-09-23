package com.moyi.notification.infra.email

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailMessage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

internal class LoggingEmailSenderTest {
    private val logger = LoggerFactory.getLogger(LoggingEmailSender::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `prints the body so a developer can read the link, but masks the recipient`() {
        val delivery =
            LoggingEmailSender().send(
                EmailMessage(
                    to = "ada.lovelace@example.com",
                    subject = "Verify your address",
                    text = "Open https://moyi.test/verify?token=abc within 24 hours.",
                ),
            )

        val line = appender.list.single().formattedMessage
        line shouldContain "https://moyi.test/verify?token=abc"
        line shouldContain "Verify your address"
        // Doc 11 NFR-044, with no local-development exception: the domain is
        // enough to see the message went where it was meant to.
        line shouldContain "@example.com"
        line shouldNotContain "ada.lovelace"

        delivery.shouldBeInstanceOf<EmailDelivery.Accepted>().providerMessageId shouldBe "not-sent:log"
    }
}
