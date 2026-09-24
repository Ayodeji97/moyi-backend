package com.moyi.identity.infra.database

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.domain.Email
import com.moyi.identity.domain.RefreshToken
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.domain.VerificationSecret
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The one property of the refresh-token table that a single-threaded test
 * cannot see: a family revocation and a concurrent rotation must not
 * interleave. Two real transactions on two threads, against a real Postgres.
 */
@SpringBootTest
internal class RefreshTokenPersistenceTest(
    @Autowired private val tokens: RefreshTokenStore,
    @Autowired private val users: UserRepository,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()

    @AfterEach
    fun clearTables() {
        jdbc.execute("TRUNCATE TABLE refresh_tokens, consent_records, credentials, users CASCADE")
    }

    @Test
    fun `a revocation waits for a concurrent rotation to commit, and then revokes what it inserted`() {
        // The race Codex found in PR #32. Transaction A is a rotation: it
        // holds the sessions lock, inserts a successor, and commits a little
        // later. Transaction B is a revocation of the same user's sessions,
        // started while A is open. Without the lock, B's UPDATE takes its
        // snapshot before A commits, never sees the successor, and reports
        // the family revoked while one live token survives. With the lock, B
        // blocks until A commits and then revokes the successor too.
        val user = insertUser()
        val family = UUID.randomUUID()
        val rotationHoldsLock = CountDownLatch(1)
        val revocationAttempted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val rotation =
                executor.submit {
                    transactions.executeWithoutResult {
                        tokens.lockSessionsOf(user.id)
                        rotationHoldsLock.countDown()
                        // Keep the transaction open long enough for B to arrive
                        // and, if the lock is absent, to run its UPDATE.
                        revocationAttempted.await(5, TimeUnit.SECONDS)
                        Thread.sleep(SETTLE_MILLIS)
                        tokens.insert(token(user.id, family, VerificationSecret("successor")))
                    }
                }
            val revocation =
                executor.submit<Int> {
                    rotationHoldsLock.await(5, TimeUnit.SECONDS)
                    transactions.execute {
                        revocationAttempted.countDown()
                        tokens.lockSessionsOf(user.id)
                        tokens.revokeAllForUser(user.id, NOW)
                        countRevoked(user.id)
                    }!!
                }
            rotation.get(10, TimeUnit.SECONDS)

            // The revocation saw and revoked the token the rotation inserted.
            revocation.get(10, TimeUnit.SECONDS) shouldBe 1
        } finally {
            executor.shutdownNow()
        }
        jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `the lock is released at commit, so the next holder proceeds`() {
        val user = insertUser()

        transactions.executeWithoutResult { tokens.lockSessionsOf(user.id) }
        // Would hang here if the first call had leaked the lock past its transaction.
        transactions.executeWithoutResult { tokens.lockSessionsOf(user.id) }
    }

    private fun countRevoked(userId: UserId): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NOT NULL",
            Int::class.java,
            userId.value,
        )!!

    private fun token(
        userId: UserId,
        familyId: UUID,
        secret: VerificationSecret,
    ) = RefreshToken.issue(
        id = ids.timeOrdered(),
        userId = userId,
        familyId = familyId,
        secret = secret,
        now = NOW,
        deviceInfo = null,
    )

    private fun insertUser(): User {
        val user =
            User(
                id = UserId(ids.timeOrdered()),
                email = Email("ada@example.com"),
                emailVerifiedAt = NOW,
                displayName = "Ada",
                avatarMediaId = null,
                locale = "en",
                status = UserStatus.ACTIVE,
                tokensInvalidBefore = null,
                createdAt = NOW,
                updatedAt = null,
                deletedAt = null,
            )
        transactions.executeWithoutResult { users.save(user.toEntity()) }
        return user
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-23T12:00:00Z")
        const val SETTLE_MILLIS = 300L

        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)
    }
}
