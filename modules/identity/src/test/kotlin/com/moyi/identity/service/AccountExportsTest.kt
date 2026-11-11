package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.AccountExports
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID
import javax.sql.DataSource

/** identity's share of a data export (ADR-0059): the person's profile and consents, and nothing for an account deleted. */
@SpringBootTest(classes = [IdentityTestApplication::class])
internal class AccountExportsTest(
    @Autowired private val exports: AccountExports,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE consent_records, credentials, users CASCADE")
    }

    @Test
    fun `a person's profile and every consent they gave, oldest first`() {
        val ada = insertUser("ada@example.com", "ACTIVE")
        consent(ada, "PRIVACY", "2026-10-01", "2026-10-02T09:00:00Z")
        consent(ada, "TERMS", "2026-09-01", "2026-09-30T09:00:00Z")
        consent(insertUser("bea@example.com", "ACTIVE"), "TERMS", "2026-09-01", "2026-09-30T09:00:00Z")

        val account = exports.accountOf(ada).shouldNotBeNull()

        account.email shouldBe "ada@example.com"
        account.displayName shouldBe "Ada"
        account.consents.map { it.document to it.version } shouldBe listOf("TERMS" to "2026-09-01", "PRIVACY" to "2026-10-01")
        account.toString() shouldNotContain "ada@example.com"
    }

    @Test
    fun `a deleted account and an id that names nobody have nothing to export`() {
        val gone = insertUser("deleted-x@deleted.invalid", "DELETED")

        exports.accountOf(gone).shouldBeNull()
        exports.accountOf(UUID.randomUUID()).shouldBeNull()
    }

    private fun insertUser(
        email: String,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, email_verified_at, display_name, locale, status, created_at, deleted_at) " +
                "VALUES (?, ?, now(), 'Ada', 'en', ?, now(), CASE WHEN ? = 'DELETED' THEN now() END)",
            id,
            email,
            status,
            status,
        )
        return id
    }

    private fun consent(
        user: UUID,
        document: String,
        version: String,
        at: String,
    ) {
        jdbc.update(
            "INSERT INTO consent_records (id, user_id, document, version, accepted_at) VALUES (?, ?, ?, ?, ?::timestamptz)",
            UUID.randomUUID(),
            user,
            document,
            version,
            at,
        )
    }

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
