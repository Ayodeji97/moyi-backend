package com.moyi.identity.service

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.domain.CodeHash
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.domain.VerificationCodeHasher
import com.moyi.identity.domain.VerificationPurpose
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.domain.VerificationToken
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.database.UserRepository
import com.moyi.identity.infra.database.VerificationTokenStore
import com.moyi.identity.infra.database.toEntity
import com.moyi.identity.infra.security.HmacVerificationCodeHasher
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.awaitility.Awaitility.await
import org.hibernate.SessionFactory
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
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The code check against a real Postgres (#135, ADR-0018 amended 2026-11-08): that the
 * attempt count is a count under concurrency, and that a refusal costs the same work
 * whatever its reason.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@Import(VerificationCodeCheckTest.CountingHasherConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class VerificationCodeCheckTest(
    @Autowired private val check: VerificationCodeCheck,
    @Autowired private val tokens: VerificationTokenStore,
    @Autowired private val users: UserRepository,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val hasher: CountingHasher,
    @Autowired entityManagerFactory: EntityManagerFactory,
    @Autowired private val dataSource: DataSource,
) : IntegrationTest() {
    private val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()
    private val pool = Executors.newSingleThreadExecutor()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE verification_tokens, consent_records, credentials, users CASCADE")
        pool.shutdownNow()
    }

    /**
     * Four wrong codes are in; a fifth is being counted by another request that holds the row.
     * The right code, arriving meanwhile, must wait for that count and then find the code
     * burned. Without the row lock it reads four, and the right code gets a sixth try.
     */
    @Test
    fun `an attempt waits for the one counting before it, and then sees its count`() {
        val user = insertUser()
        val token = insertCoded(user.id, attempts = VerificationToken.MAX_WRONG_CODES - 1)

        val outcome =
            dataSource.connection.use { holder ->
                holder.autoCommit = false
                holder.createStatement().use {
                    it.execute("SELECT 1 FROM verification_tokens WHERE id = '${token.id}' FOR UPDATE")
                    it.executeUpdate("UPDATE verification_tokens SET code_attempts = code_attempts + 1 WHERE id = '${token.id}'")
                }
                val attempt =
                    pool.submit(
                        Callable {
                            transactions.execute {
                                check.check(user.email, VerificationPurpose.EMAIL_VERIFICATION, CODE, NOW)
                            }
                        },
                    )
                try {
                    await().atMost(Duration.ofSeconds(10)).until {
                        jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'",
                            Int::class.java,
                        )!! >= 1
                    }
                } finally {
                    holder.commit()
                }
                attempt.get(15, TimeUnit.SECONDS)
            }

        outcome.shouldBeNull()
        jdbc.queryForObject("SELECT code_attempts FROM verification_tokens", Int::class.java) shouldBe
            VerificationToken.MAX_WRONG_CODES + 1
    }

    @Test
    fun `the right code is accepted, and a wrong one is counted`() {
        val user = insertUser()
        insertCoded(user.id)

        inTransaction { check.check(user.email, VerificationPurpose.EMAIL_VERIFICATION, VerificationCode("000001"), NOW) }.shouldBeNull()
        inTransaction { check.check(user.email, VerificationPurpose.EMAIL_VERIFICATION, CODE, NOW) }.shouldNotBeNull()
        inTransaction { check.check(user.email, VerificationPurpose.PASSWORD_RESET, CODE, NOW) }.shouldBeNull()
        jdbc.queryForObject("SELECT code_attempts FROM verification_tokens", Int::class.java) shouldBe 1
    }

    /**
     * The timing half of the one answer, counted rather than timed (ADR-0020's lesson: a
     * stopwatch test is satisfied by noise). No account, no code held, and a wrong code each
     * hash once and prepare the same statements.
     */
    @Test
    fun `an unknown address, an account with no code and a wrong code do the same work`() {
        val coded = insertUser()
        insertCoded(coded.id)
        val uncoded = insertUser(email = "grace@example.com")
        val wrong = VerificationCode("999999")

        val work =
            listOf(Email("nobody@example.com"), uncoded.email, coded.email).map { email ->
                statistics.clear()
                hasher.calls.set(0)
                inTransaction { check.check(email, VerificationPurpose.EMAIL_VERIFICATION, wrong, NOW) }.shouldBeNull()
                hasher.calls.get() to statistics.prepareStatementCount
            }

        // One lookup of the address, one locking SELECT, one UPDATE; one HMAC.
        work.distinct() shouldBe listOf(1 to 3L)
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

    private fun insertCoded(
        userId: UserId,
        attempts: Int = 0,
    ): VerificationToken {
        val id = ids.timeOrdered()
        val token =
            VerificationToken
                .issue(
                    id = id,
                    userId = userId,
                    purpose = VerificationPurpose.EMAIL_VERIFICATION,
                    secret = VerificationSecret("secret-$id"),
                    codeHash = hasher.hash(id, VerificationPurpose.EMAIL_VERIFICATION, CODE),
                    codeTtl = Duration.ofMinutes(15),
                    now = NOW.minusSeconds(60),
                ).copy(codeAttempts = attempts)
        inTransaction { tokens.insert(token) }
        return token
    }

    private fun <T> inTransaction(block: () -> T): T? = transactions.execute { block() }

    /** The real hasher, counted. */
    internal class CountingHasher(
        private val real: VerificationCodeHasher,
    ) : VerificationCodeHasher {
        val calls = AtomicInteger()

        override fun hash(
            tokenId: UUID,
            purpose: VerificationPurpose,
            code: VerificationCode,
        ): CodeHash = real.hash(tokenId, purpose, code).also { calls.incrementAndGet() }
    }

    @TestConfiguration
    class CountingHasherConfiguration {
        @Bean
        @Primary
        fun countingHasher(real: HmacVerificationCodeHasher): CountingHasher = CountingHasher(real)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-08T12:00:00Z")
        val CODE = VerificationCode("424242")

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
