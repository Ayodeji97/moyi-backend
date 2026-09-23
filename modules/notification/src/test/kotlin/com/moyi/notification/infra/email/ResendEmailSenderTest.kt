package com.moyi.notification.infra.email

import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailMessage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
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
