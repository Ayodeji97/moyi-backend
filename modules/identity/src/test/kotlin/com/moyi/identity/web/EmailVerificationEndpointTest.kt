package com.moyi.identity.web

import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.notification.api.EmailDelivery
import com.moyi.notification.api.EmailMessage
import com.moyi.notification.api.EmailSender
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * FR-002 end to end, through the real context: register, read the link out
 * of the email the listener sent, present the token, and check the rows.
 *
 * The one bean replaced is the [EmailSender], with a recorder — which is
 * exactly the fake doc 25 D7 promised the port would make possible. The
 * listener, the `AFTER_COMMIT` binding, the async executor and the composer
 * are all the real ones, which is why the email is awaited rather than
 * asserted: it arrives on another thread after the response has gone.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(EmailVerificationEndpointTest.RecordingEmailConfiguration::class)
internal class EmailVerificationEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired dataSource: DataSource,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, consent_records, credentials, users CASCADE")
        emails.sent.clear()
    }

    @Test
    fun `registering sends one verification email, after the commit, with a link that verifies the account`() {
        register()

        val email = awaitOneEmail()
        email.to shouldBe "ada@example.com"
        email.idempotencyKey.shouldNotBeNull()
        // The stored row is the hash, not the secret in the link.
        val secret = secretIn(email)
        jdbc.queryForObject("SELECT token_hash FROM verification_tokens", String::class.java) shouldNotContain secret
        jdbc.queryForObject("SELECT status FROM users", String::class.java) shouldBe "PENDING_VERIFICATION"

        val response = verify(secret)

        response.status shouldBe 200
        response.contentAsString shouldBe ""
        jdbc.queryForObject("SELECT status FROM users", String::class.java) shouldBe "ACTIVE"
        jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users", Boolean::class.java) shouldBe true
        jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM verification_tokens", Boolean::class.java) shouldBe true
    }

    @Test
    fun `a duplicate registration sends nothing to the existing account`() {
        // ADR-0015's duplicate path publishes no event. The one email is the
        // first registration's.
        register()
        awaitOneEmail()

        register(email = "ADA@Example.com")

        // Enough time for a wrongly-published event to have been delivered.
        Thread.sleep(SETTLE.toMillis())
        emails.sent shouldHaveSize 1
    }

    @Test
    fun `presenting a token twice is 410 the second time`() {
        register()
        val secret = secretIn(awaitOneEmail())
        verify(secret).status shouldBe 200

        val second = verify(secret)

        second.status shouldBe 410
        second.contentAsString shouldContain "\"code\":\"VERIFICATION_TOKEN_EXPIRED\""
        // The client's copy for this state, verbatim from states.md §1.
        second.contentAsString shouldContain "work once"
        second.contentAsString shouldNotContain secret
    }

    @Test
    fun `an expired token is 410 and changes nothing`() {
        register()
        val secret = secretIn(awaitOneEmail())
        jdbc.update("UPDATE verification_tokens SET expires_at = now() - interval '1 second'")

        val response = verify(secret)

        response.status shouldBe 410
        jdbc.queryForObject("SELECT status FROM users", String::class.java) shouldBe "PENDING_VERIFICATION"
        jdbc.queryForObject("SELECT consumed_at IS NULL FROM verification_tokens", Boolean::class.java) shouldBe true
    }

    @Test
    fun `a token that was never issued is 422, not 500, and is not echoed`() {
        val response = verify("not-a-token-we-issued-but-perfectly-well-formed")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"code\":\"VERIFICATION_TOKEN_INVALID\""
        response.contentAsString shouldNotContain "well-formed"
    }

    @Test
    fun `a blank or oversized token is a validation failure`() {
        verify(" ").status shouldBe 422
        verify("x".repeat(MAX_TOKEN_LENGTH + 1)).let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        }
    }

    @Test
    fun `two concurrent presentations of one token verify exactly once`() {
        // The PR template's "runs twice concurrently?" as a test. Both threads
        // read the token as live; the conditional UPDATE lets one through.
        register()
        val secret = secretIn(awaitOneEmail())
        val ready = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val outcomes =
            try {
                List(2) {
                    executor.submit<Int> {
                        ready.await()
                        verify(secret).status
                    }
                }.also { ready.countDown() }.map { it.get() }
            } finally {
                executor.shutdown()
            }

        outcomes shouldContainExactlyInAnyOrder listOf(200, 410)
        jdbc.queryForObject("SELECT status FROM users", String::class.java) shouldBe "ACTIVE"
    }

    @Test
    fun `resending for a pending account sends a second, different link that also works`() {
        register()
        val first = awaitOneEmail()

        val response = resend()

        response.status shouldBe 202
        response.contentAsString shouldBe ""
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize 2 }
        val second = emails.sent.last()
        secretIn(second) shouldNotBe secretIn(first)
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens", Int::class.java) shouldBe 2

        // Either link works — the first is not invalidated by asking for the second.
        verify(secretIn(first)).status shouldBe 200
        // And using one retires the other: it is deleted, so it is now "not recognised".
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens", Int::class.java) shouldBe 1
        verify(secretIn(second)).status shouldBe 422
    }

    @Test
    fun `resending for an unknown or already-verified address is 202 with no email`() {
        register()
        verify(secretIn(awaitOneEmail())).status shouldBe 200
        emails.sent.clear()

        val unknown = resend(email = "nobody@example.com")
        val verified = resend(email = "ada@example.com")

        unknown.status shouldBe 202
        verified.status shouldBe 202
        unknown.contentAsString shouldBe verified.contentAsString
        Thread.sleep(SETTLE.toMillis())
        emails.sent shouldHaveSize 0
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens WHERE consumed_at IS NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `resending for a malformed address is a validation failure, which reveals nothing`() {
        resend(email = "not-an-address").status shouldBe 422
    }

    private fun awaitOneEmail(): EmailMessage {
        await().atMost(WAIT).untilAsserted { emails.sent shouldHaveSize 1 }
        return emails.sent.single()
    }

    /** The secret is whatever follows `?token=` on the link line of the plain-text body. */
    private fun secretIn(email: EmailMessage): String =
        Regex("""token=([A-Za-z0-9_-]+)""").find(email.text)?.groupValues?.get(1)
            ?: error("no verification link in the email body")

    private fun register(email: String = "ada@example.com"): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/register") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "email": "$email",
                      "password": "correct horse battery",
                      "displayName": "Ada",
                      "locale": "en",
                      "acceptedTermsVersion": "2026-09-01",
                      "over18": true
                    }
                    """.trimIndent()
            }.andReturn()
            .response

    private fun verify(token: String): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/verify-email") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"token": "$token"}"""
            }.andReturn()
            .response

    private fun resend(email: String = "ada@example.com"): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/auth/resend-verification") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email": "$email"}"""
            }.andReturn()
            .response

    @TestConfiguration
    class RecordingEmailConfiguration {
        @Bean
        @Primary
        fun recordingEmailSender(): RecordingEmailSender = RecordingEmailSender()
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(5)

        /** How long to wait for an email that must *not* arrive before concluding it will not. */
        val SETTLE: Duration = Duration.ofMillis(500)

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}

/** The fake doc 25 D7 promised: records what was asked of it, reports success. */
internal class RecordingEmailSender : EmailSender {
    val sent: MutableList<EmailMessage> = Collections.synchronizedList(mutableListOf())

    override fun send(message: EmailMessage): EmailDelivery {
        sent.add(message)
        return EmailDelivery.Accepted("recorded-${sent.size}")
    }
}
