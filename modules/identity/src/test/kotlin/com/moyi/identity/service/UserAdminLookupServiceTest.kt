package com.moyi.identity.service

import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.AccountStatus
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.UserAdminLookup
import com.moyi.identity.domain.DeletionRequest
import com.moyi.identity.domain.UserId
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.database.DeletionRequestStore
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/** The admin console's view of a user, through the public port the way `admin` sees it. */
@SpringBootTest(classes = [IdentityTestApplication::class])
internal class UserAdminLookupServiceTest(
    @Autowired private val lookup: UserAdminLookup,
    @Autowired private val suspension: AccountSuspension,
    @Autowired private val requests: DeletionRequestStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute(
            "TRUNCATE TABLE account_suspensions, deletion_requests, push_tokens, refresh_tokens, devices, verification_tokens, " +
                "consent_records, credentials, users CASCADE",
        )
    }

    @Test
    fun `an address is found however it is typed, and an unknown id or address is null`() {
        val ada = insertUser("ada@example.com", "Ada")

        val found = lookup.byEmail("  Ada@Example.COM ")!!

        found.id shouldBe ada
        found.email shouldBe "ada@example.com"
        found.emailVerified shouldBe true
        found.locale shouldBe "en"
        lookup.byId(ada)!!.id shouldBe ada
        lookup.byId(UUID.randomUUID()).shouldBeNull()
        lookup.byEmail("nobody@example.com").shouldBeNull()
        lookup.byEmail("not an address").shouldBeNull()
    }

    @Test
    fun `live sessions count live families, and an ended one stops counting`() {
        val ada = insertUser("ada@example.com", "Ada")
        lookup.byId(ada)!!.liveSessions shouldBe 0
        val first = insertSession(ada)
        insertSession(ada)
        lookup.byId(ada)!!.liveSessions shouldBe 2

        jdbc.update("DELETE FROM refresh_tokens WHERE family_id = ?", first)

        lookup.byId(ada)!!.liveSessions shouldBe 1
    }

    @Test
    fun `a lock shows only while it is in force`() {
        val ada = insertUser("ada@example.com", "Ada")
        jdbc.update(
            "INSERT INTO credentials (user_id, password_hash, algorithm, password_updated_at, failed_attempts, locked_until) " +
                "VALUES (?, 'x', 'ARGON2ID', now(), 5, now() + interval '1 hour')",
            ada,
        )
        lookup.byId(ada)!!.lockedUntil!!.isAfter(java.time.Instant.now()) shouldBe true

        jdbc.update("UPDATE credentials SET locked_until = now() - interval '1 hour'")

        lookup.byId(ada)!!.lockedUntil.shouldBeNull()
    }

    @Test
    fun `an active account shows ACTIVE with no suspension and no deletion due`() {
        val ada = insertUser("ada@example.com", "Ada")

        val view = lookup.byId(ada)!!

        view.status shouldBe AccountStatus.ACTIVE
        view.suspendedSince.shouldBeNull()
        view.deletionDueAt.shouldBeNull()
    }

    @Test
    fun `a suspended account shows since when, and lifted it shows neither`() {
        val ada = insertUser("ada@example.com", "Ada")
        transactions.execute { suspension.suspend(ada) }
        val since = jdbc.queryForObject("SELECT suspended_at FROM account_suspensions WHERE user_id = ?", Timestamp::class.java, ada)!!

        val view = lookup.byId(ada)!!

        view.status shouldBe AccountStatus.SUSPENDED
        view.suspendedSince shouldBe since.toInstant()

        transactions.execute { suspension.lift(ada) }

        lookup.byId(ada)!!.let {
            it.status shouldBe AccountStatus.ACTIVE
            it.suspendedSince.shouldBeNull()
        }
    }

    @Test
    fun `a pending deletion shows the instant the job carries it out, and a cancelled one does not`() {
        val ada = insertUser("ada@example.com", "Ada")
        val request = DeletionRequest.open(UUID.randomUUID(), UserId(ada), Instant.now().truncatedTo(ChronoUnit.MICROS))
        requests.insert(request)

        lookup.byId(ada)!!.deletionDueAt shouldBe request.cancellableUntil

        requests.cancel(request, Instant.now())

        lookup.byId(ada)!!.deletionDueAt.shouldBeNull()
    }

    @Test
    fun `the view prints its id and neither the address nor the name`() {
        val ada = insertUser("ada@example.com", "Ada Lovelace")

        val printed = lookup.byId(ada)!!.toString()

        printed shouldBe "UserAdminView(id=$ada)"
        printed shouldNotContain "ada@example.com"
        printed shouldNotContain "Lovelace"
    }

    private fun insertUser(
        email: String,
        displayName: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, email_verified_at, display_name, locale, status, created_at) " +
                "VALUES (?, ?, now(), ?, 'en', 'ACTIVE', now())",
            id,
            email,
            displayName,
        )
        return id
    }

    private fun insertSession(userId: UUID): UUID {
        val family = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO refresh_tokens (id, user_id, family_id, token_hash, issued_at, expires_at) " +
                "VALUES (?, ?, ?, ?, now(), now() + interval '30 days')",
            UUID.randomUUID(),
            userId,
            family,
            UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
        )
        return family
    }

    private companion object {
        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
