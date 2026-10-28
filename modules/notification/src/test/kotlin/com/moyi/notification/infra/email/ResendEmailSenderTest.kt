package com.moyi.notification.infra.email

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException

/**
 * The adapter is tested by the HTTP it produces and how it reads what comes
 * back — the two things that would break if Resend changed — against
 * [MockRestServiceServer] bound to the real `RestClient`. Mocking the client
 * would test that we called a mock.
 */
internal class ResendEmailSenderTest {
    private val builder: RestClient.Builder = RestClient.builder()
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val sender =
        ResendEmailSender.create(
            properties(apiKey = "re_test_key", from = "Moyi <hello@moyi.test>"),
            builder,
        )

    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val spring = LoggerFactory.getLogger("org.springframework") as Logger
    private val captured = ListAppender<ILoggingEvent>()

    @AfterEach
    fun detach() {
        root.detachAppender(captured)
        spring.level = null
    }

    @Test
    fun `sends the message the way Resend expects and reports the provider id`() {
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer re_test_key"))
            .andExpect(header(ResendEmailSender.IDEMPOTENCY_KEY_HEADER, "verification-token-1"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.from").value("Moyi <hello@moyi.test>"))
            .andExpect(jsonPath("$.to[0]").value("ada@example.com"))
            .andExpect(jsonPath("$.subject").value("Verify your address"))
            .andExpect(jsonPath("$.text").value("Open https://moyi.test/verify?token=abc"))
            .andExpect(jsonPath("$.html").value("<p>Open</p>"))
            .andRespond(withSuccess("""{"id":"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794"}""", MediaType.APPLICATION_JSON))

        val delivery =
            sender.send(
                EmailMessage(
                    to = "ada@example.com",
                    subject = "Verify your address",
                    text = "Open https://moyi.test/verify?token=abc",
                    html = "<p>Open</p>",
                    idempotencyKey = "verification-token-1",
                ),
            )

        delivery shouldBe EmailDelivery.Accepted("49a3999c-0ce1-4ea6-ab68-afcd6dc2e794")
        server.verify()
    }

    @Test
    fun `omits html and the idempotency header when the message has neither`() {
        // `"html": null` is not the same as no `html` — Resend validates the
        // field it is given. And an absent idempotency key must not become a
        // literal "null" header, which the provider would happily honour as a
        // key shared by every message.
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andExpect(headerDoesNotExist(ResendEmailSender.IDEMPOTENCY_KEY_HEADER))
            .andExpect(jsonPath("$.html").doesNotExist())
            .andRespond(withSuccess("""{"id":"x"}""", MediaType.APPLICATION_JSON))

        sender.send(message()).shouldBeInstanceOf<EmailDelivery.Accepted>()
        server.verify()
    }

    @Test
    fun `a 4xx is a permanent rejection`() {
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andRespond(
                withStatus(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"statusCode":403,"name":"validation_error","message":"The moyi.test domain is not verified"}"""),
            )

        val delivery = sender.send(message()).shouldBeInstanceOf<EmailDelivery.Rejected>()

        delivery.reason shouldContain "403"
    }

    @Test
    fun `a 429 is transient, not a rejection, even though it is a 4xx`() {
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

        val delivery = sender.send(message()).shouldBeInstanceOf<EmailDelivery.Unavailable>()

        delivery.reason shouldContain "429"
    }

    @Test
    fun `a 5xx is transient`() {
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        // The status is asserted so this cannot pass by the provider being
        // unreachable — which is exactly how it passed the first time it ran.
        sender.send(message()).shouldBeInstanceOf<EmailDelivery.Unavailable>().reason shouldContain "503"
    }

    @Test
    fun `an unreachable provider is transient and does not escape as an exception`() {
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andRespond(withException(IOException("Connection refused")))

        val delivery = sender.send(message()).shouldBeInstanceOf<EmailDelivery.Unavailable>()

        delivery.reason shouldContain "unreachable"
    }

    @Test
    fun `a 2xx that is not JSON is transient and does not escape as an exception`() {
        // A proxy, a captive portal or a maintenance page answering 200 with
        // HTML for the API. The first version let the client's decode failure
        // escape `send`, which broke the one promise the port makes.
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andRespond(withSuccess("<html><body>Service temporarily unavailable</body></html>", MediaType.TEXT_HTML))

        sender.send(message()).shouldBeInstanceOf<EmailDelivery.Unavailable>().reason shouldContain "unexpectedly"
    }

    @Test
    fun `a 2xx whose JSON does not decode is transient too`() {
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andRespond(withSuccess("""{"id": ["not", "a", "string"]}""", MediaType.APPLICATION_JSON))

        sender.send(message()).shouldBeInstanceOf<EmailDelivery.Unavailable>()
    }

    @Test
    fun `with Spring's HTTP client at TRACE, neither the address nor the link's token reaches a log line`() {
        // DefaultRestClient prints an object body whole at DEBUG ("Writing [...]"),
        // and this body is the recipient and a verification link. The sender
        // hands the client bytes, which no converter writes and none prints.
        captured.start()
        root.addAppender(captured)
        spring.level = Level.TRACE
        // Distinctive, so a hit is this send's, and plainly fake, so gitleaks does not take it for a key.
        val token = "the-link-token-under-test"
        server
            .expect(requestTo("https://api.resend.test/emails"))
            .andExpect(jsonPath("$.to[0]").value("ada@example.com"))
            .andExpect(jsonPath("$.text").value("Open https://moyi.test/verify?token=$token"))
            .andRespond(withSuccess("""{"id":"x"}""", MediaType.APPLICATION_JSON))

        sender
            .send(
                EmailMessage(
                    to = "ada@example.com",
                    subject = "Verify your address",
                    text = "Open https://moyi.test/verify?token=$token",
                    html = "<p><a href=\"https://moyi.test/verify?token=$token\">Open</a></p>",
                ),
            ).shouldBeInstanceOf<EmailDelivery.Accepted>()
        server.verify()

        val lines = captured.list.map { it.formattedMessage }
        // The client did log at DEBUG ("Reading to [...]" for the answer): this test saw it write.
        lines.any { it.startsWith("Reading to") } shouldBe true
        lines.filter { line -> "ada@example.com" in line || token in line }.shouldBeEmpty()
    }

    @Test
    fun `refuses to build without an API key, and says which property`() {
        val failure = shouldThrow<IllegalStateException> { ResendEmailSender.create(properties(apiKey = null, from = "x <y@z>"), builder) }

        failure.message shouldContain "moyi.notification.email.resend.api-key"
        failure.message shouldContain "LOG"
    }

    @Test
    fun `refuses to build without a sender address`() {
        val failure = shouldThrow<IllegalStateException> { ResendEmailSender.create(properties(apiKey = "re_x", from = " "), builder) }

        failure.message shouldContain "moyi.notification.email.from"
    }

    private fun message() = EmailMessage(to = "ada@example.com", subject = "s", text = "t")

    private fun properties(
        apiKey: String?,
        from: String?,
    ) = EmailProperties(
        provider = EmailProperties.Provider.RESEND,
        from = from,
        resend = EmailProperties.Resend(apiKey = apiKey, baseUrl = "https://api.resend.test"),
    )
}
