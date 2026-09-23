package com.moyi.identity.infra.database

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.domain.VerificationToken
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * The token table against a real Postgres: the constraint names the service
 * relies on, and the two conditional statements whose whole value is what
 * the database does when the condition is false.
 */
@SpringBootTest
internal class VerificationTokenPersistenceTest(
    @Autowired private val tokens: VerificationTokenStore,
    @Autowired private val users: UserRepository,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()

    @AfterEach
    fun clearTables() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, consent_records, credentials, users CASCADE")
    }

    @Test
    fun `consume spends a live token exactly once`() {
        val user = insertUser()
        val secret = VerificationSecret("live")
        insertToken(user.id, secret, issuedAt = NOW)

        // Two presentations of one token: the first wins, the second finds the
        // predicate false. This is the compare-and-set that a read-then-write
        // in Kotlin cannot give — the same call, made twice, must answer
        // differently, and the database is what makes it so.
        inTransaction { tokens.consume(secret.hash(), NOW.plusSeconds(1)) } shouldBe true
        inTransaction { tokens.consume(secret.hash(), NOW.plusSeconds(2)) } shouldBe false

        jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM verification_tokens", Boolean::class.java) shouldBe true
    }

    @Test
    fun `consume refuses an expired token, and an unknown one`() {
        val user = insertUser()
        val secret = VerificationSecret("stale")
        insertToken(user.id, secret, issuedAt = NOW.minus(TTL).minusSeconds(1))

        inTransaction { tokens.consume(secret.hash(), NOW) } shouldBe false
        inTransaction { tokens.consume(VerificationSecret("never-issued").hash(), NOW) } shouldBe false
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens WHERE consumed_at IS NOT NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `deleteLive removes only the person's other unspent tokens`() {
        val ada = insertUser()
        val grace = insertUser(email = "grace@example.com")
        val spent = VerificationSecret("spent")
        insertToken(ada.id, spent, issuedAt = NOW)
        insertToken(ada.id, VerificationSecret("waiting-1"), issuedAt = NOW)
        insertToken(ada.id, VerificationSecret("waiting-2"), issuedAt = NOW)
        insertToken(grace.id, VerificationSecret("someone-else"), issuedAt = NOW)
        inTransaction { tokens.consume(spent.hash(), NOW) } shouldBe true

        val removed = inTransaction { tokens.deleteLive(ada.id, VerificationPurpose.EMAIL_VERIFICATION) }

        removed shouldBe 2
        // The consumed one stays (it is the record of the verification), and
        // another person's token is not ours to touch.
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM verification_tokens WHERE user_id = ?", Int::class.java, grace.id.value) shouldBe 1
    }

    @Test
    fun `two tokens cannot share a digest, and the constraint has the name the code expects`() {
        val user = insertUser()
        insertToken(user.id, VerificationSecret("same"), issuedAt = NOW)

        val violation = shouldThrow<DataIntegrityViolationException> { insertToken(user.id, VerificationSecret("same"), issuedAt = NOW) }

        violation.violates(IdentityConstraints.VERIFICATION_TOKEN_HASH_UNIQUE) shouldBe true
    }

    @Test
    fun `deleting a user takes their tokens with them`() {
        val user = insertUser()
        insertToken(user.id, VerificationSecret("orphan-to-be"), issuedAt = NOW)

        jdbc.update("DELETE FROM users WHERE id = ?", user.id.value)

        jdbc.queryForObject("SELECT count(*) FROM verification_tokens", Int::class.java) shouldBe 0
    }

    private fun insertUser(email: String = "ada@example.com"): User {
        val user =
            User(
                id = UserId(ids.timeOrdered()),
                email = Email(email),
                emailVerifiedAt = null,
                displayName = "Ada",
                avatarMediaId = null,
                locale = "en",
                status = UserStatus.PENDING_VERIFICATION,
                createdAt = NOW,
                updatedAt = null,
                deletedAt = null,
                tokensInvalidBefore = null,
            )
        inTransaction { users.save(user.toEntity()) }
        return user
    }

    private fun insertToken(
        userId: UserId,
        secret: VerificationSecret,
        issuedAt: Instant,
    ) = inTransaction {
        tokens.insert(
            VerificationToken.issue(
                id = ids.timeOrdered(),
                userId = userId,
                purpose = VerificationPurpose.EMAIL_VERIFICATION,
                secret = secret,
                now = issuedAt,
            ),
        )
    }

    private fun <T> inTransaction(block: () -> T): T = transactions.execute { block() }!!

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-23T12:00:00Z")
        val TTL: Duration = VerificationPurpose.EMAIL_VERIFICATION.ttl

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
