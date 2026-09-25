package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import javax.sql.DataSource

/**
 * The first inter-module port (ADR-0026): what another module may know about
 * a user. Injected and asserted through the **public interface type**, the
 * way a caller in `bond` sees it — a test that reached for `AccountDirectory`
 * directly would be testing a class rather than a contract.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
internal class AccountDirectoryTest(
    @Autowired private val directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, consent_records, credentials, users CASCADE")
    }

    @Test
    fun `a user is a summary - id, display name, whether verified - and nothing else`() {
        val ada = insertUser("ada@example.com", "Ada", verified = true)
        val bob = insertUser("bob@example.com", "Bob", verified = false)

        val summary = directory.find(ada)!!

        summary.id shouldBe ada
        summary.displayName shouldBe "Ada"
        summary.emailVerified shouldBe true
        directory.find(bob)!!.emailVerified shouldBe false
        // A display name is personal data (doc 18 §9), and a `data class`
        // prints every property — so this one does not.
        summary.toString() shouldBe "UserSummary(id=$ada)"
    }

    @Test
    fun `an unknown id is null, and findAll simply omits it`() {
        // The caller is another module asking about ids it holds; one of them
        // naming a deleted account is ordinary, not an error.
        val ada = insertUser("ada@example.com", "Ada", verified = true)
        val nobody = UUID.randomUUID()

        directory.find(nobody).shouldBeNull()

        val found = directory.findAll(listOf(ada, nobody))
        found shouldHaveSize 1
        found shouldContainKey ada
    }

    @Test
    fun `findAll of nothing is nothing, without a query`() {
        directory.findAll(emptyList()) shouldHaveSize 0
    }

    private fun insertUser(
        email: String,
        displayName: String,
        verified: Boolean,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, email_verified_at, display_name, locale, status, created_at) " +
                "VALUES (?, ?, CASE WHEN ? THEN now() END, ?, 'en', ?, now())",
            id,
            email,
            verified,
            displayName,
            if (verified) "ACTIVE" else "PENDING_VERIFICATION",
        )
        return id
    }

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
