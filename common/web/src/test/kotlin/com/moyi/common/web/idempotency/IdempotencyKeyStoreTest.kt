package com.moyi.common.web.idempotency

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * [IdempotencyKeyStore]'s own boundary tests — direct against a real
 * Postgres, no HTTP, no interceptor — for one narrow case
 * [IdempotencyInterceptorTest] cannot reach deterministically: two callers
 * racing to reclaim the *same* expired row (review round 2, Important #N1).
 *
 * `IdempotencyInterceptorTest`'s own concurrency test proves the ordinary
 * in-flight race (a live reservation); this one is specifically about the
 * expiry-reclaim path `reclaimExpired` added on top of Ruling B, which has
 * its own, separate race window (the `DELETE` and the `INSERT` are two
 * statements, not one atomic operation).
 */
@SpringBootTest(classes = [IdempotencyTestApplication::class])
class IdempotencyKeyStoreTest(
    @Autowired private val store: IdempotencyKeyStore,
    @Autowired dataSource: DataSource,
    @Autowired private val transactionManager: PlatformTransactionManager,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE idempotency_keys")
    }

    private fun record(
        userId: UUID,
        key: String,
        createdAt: Instant,
        path: String = "/probe",
    ) = IdempotencyRecord(
        id = ids.timeOrdered(),
        userId = userId,
        method = "POST",
        path = path,
        idempotencyKey = key,
        requestHash = "hash",
        responseStatus = null,
        resultId = null,
        resultKind = null,
        responseEtag = null,
        responseLocation = null,
        createdAt = createdAt,
        expiresAt = createdAt.plus(Duration.ofHours(24)),
    )

    @Test
    fun `two callers reclaiming the same expired key at once never throw, and exactly one wins`() {
        val userId = UUID.randomUUID()
        val key = UUID.randomUUID().toString()
        val longAgo = Instant.parse("2020-01-01T00:00:00Z")
        store.reserve(record(userId, key, longAgo)) shouldBe null

        val now = Instant.now()
        val a = record(userId, key, now)
        val b = record(userId, key, now)

        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = Collections.synchronizedList(mutableListOf<IdempotencyRecord?>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())

        listOf(a, b).forEach { candidate ->
            pool.submit {
                ready.countDown()
                go.await()
                try {
                    results.add(store.reserve(candidate))
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        go.countDown()
        pool.shutdown()
        pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        // Before the fix (review round 2, Important #N1): the loser's second
        // INSERT re-collided with the winner's fresh row and threw a fresh,
        // uncaught DataIntegrityViolationException from inside reserve()'s
        // own catch block — this is exactly what `errors` being empty rules
        // out.
        errors shouldBe emptyList()
        results shouldHaveSize 2
        results.count { it == null } shouldBe 1
        results.count { it != null } shouldBe 1
    }

    @Test
    fun `the same key against a different concrete path hands the first row back for refusal`() {
        val user = UUID.randomUUID()
        val bondA = UUID.randomUUID()
        val bondB = UUID.randomUUID()
        store.reserve(record(user, "one-key", Instant.now(), path = "/api/v1/bonds/$bondA/entries")) shouldBe null

        val second = store.reserve(record(user, "one-key", Instant.now(), path = "/api/v1/bonds/$bondB/entries"))

        second.shouldNotBeNull()
        second.path shouldBe "/api/v1/bonds/$bondA/entries"
        second.method shouldBe "POST"
    }

    @Test
    fun `the advisory lock is nonblocking, one holder at a time, and free after commit`() {
        val user = UUID.randomUUID()
        val tx = TransactionTemplate(transactionManager)
        val pool = Executors.newSingleThreadExecutor()
        try {
            fun fromSecondSession(): Boolean =
                pool.submit<Boolean> { tx.execute { store.lockFor(user, "k") } }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            tx.execute {
                store.lockFor(user, "k") shouldBe true
                // Refused, not queued: this call returns while the first holds.
                fromSecondSession() shouldBe false
            }
            fromSecondSession() shouldBe true
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `no column of the table can hold request or response text`() {
        val columns =
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'idempotency_keys'",
                String::class.java,
            )
        columns shouldNotContain "response_body"
        columns shouldContainAll listOf("result_id", "result_kind", "method", "path", "request_hash")
    }

    @Test
    fun `timestamps are persisted at micros so what was reserved reads back equal`() {
        val user = UUID.randomUUID()
        val nanos = Instant.parse("2026-10-01T10:00:00.123456789Z")
        store.reserve(record(user, "k", nanos)) shouldBe null

        val back = store.reserve(record(user, "k", nanos.plusSeconds(1)))

        back.shouldNotBeNull()
        back.createdAt shouldBe nanos.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
