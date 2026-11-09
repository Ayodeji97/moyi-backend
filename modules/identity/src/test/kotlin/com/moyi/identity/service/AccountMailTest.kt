package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.AccountMail
import com.moyi.identity.api.AccountMailOutcome
import com.moyi.identity.api.AccountMessage
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import com.moyi.identity.web.RecordingEmailSender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import javax.sql.DataSource

/**
 * `identity.api.AccountMail` (ADR-0057 decision 6): another module writes to the account holder, and
 * identity alone knows the address. Asserted through the public interface, as `support` sees it.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@Import(AccountMailTest.RecordingEmailConfiguration::class)
internal class AccountMailTest(
    @Autowired private val mail: AccountMail,
    @Autowired private val emails: RecordingEmailSender,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        emails.sent.clear()
        jdbc.execute("TRUNCATE TABLE verification_tokens, consent_records, credentials, users CASCADE")
    }

    @Test
    fun `a verified account holder is written to at their address, with the caller's words and key`() {
        val ada = insertUser("ada@example.com", verified = true)

        mail.sendToAccountHolder(ada, MESSAGE) shouldBe AccountMailOutcome.ACCEPTED

        emails.sent shouldHaveSize 1
        val sent = emails.sent.single()
        sent.to shouldBe "ada@example.com"
        sent.subject shouldBe MESSAGE.subject
        sent.text shouldBe MESSAGE.text
        sent.idempotencyKey shouldBe MESSAGE.idempotencyKey
    }

    @Test
    fun `an address never confirmed is not written to (ADR-0015, ADR-0057 decision 6)`() {
        val bob = insertUser("bob@example.com", verified = false)

        mail.sendToAccountHolder(bob, MESSAGE) shouldBe AccountMailOutcome.NOT_VERIFIED

        emails.sent.shouldBeEmpty()
    }

    @Test
    fun `an id that names nobody is told so, and nothing is sent`() {
        mail.sendToAccountHolder(UUID.randomUUID(), MESSAGE) shouldBe AccountMailOutcome.NO_ACCOUNT
        emails.sent.shouldBeEmpty()
    }

    @Test
    fun `a message prints its subject and key, never its text`() {
        MESSAGE.toString() shouldNotContain MESSAGE.text
    }

    private fun insertUser(
        email: String,
        verified: Boolean,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, email_verified_at, display_name, locale, status, created_at) " +
                "VALUES (?, ?, CASE WHEN ? THEN now() END, 'Someone', 'en', ?, now())",
            id,
            email,
            verified,
            if (verified) "ACTIVE" else "PENDING_VERIFICATION",
        )
        return id
    }

    @TestConfiguration
    class RecordingEmailConfiguration {
        @Bean
        @Primary
        fun recordingEmailSender(): RecordingEmailSender = RecordingEmailSender()
    }

    private companion object {
        val MESSAGE = AccountMessage("We have your message", "Hello.\n\nA body nobody should log.", null, "support-ack-1")

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
