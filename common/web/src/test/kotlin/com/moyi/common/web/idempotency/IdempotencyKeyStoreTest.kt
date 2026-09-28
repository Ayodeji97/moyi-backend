package com.moyi.common.web.idempotency

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
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
    ) = IdempotencyRecord(
        id = ids.timeOrdered(),
        userId = userId,
        endpoint = "POST /probe",
        idempotencyKey = key,
        requestHash = "hash",
        responseStatus = null,
        responseBody = null,
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

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
