package com.moyi.common.web.idempotency

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * [IdempotencyKeyStore]'s own boundary tests — direct against a real
 * Postgres, no HTTP, no [IdempotentExecution] — for what the table and the
 * lock promise on their own: the nonblocking advisory lock, V11's shape and
 * CHECKs, the read-time expiry window, and micros.
 *
 * The old "two callers reclaiming one expired key" race test is gone with
 * the race: reclaiming now happens under the key's advisory lock (spec
 * §5.4), so two callers cannot both be in it — the lock test below is what
 * holds that, and `IdempotencyInterceptorTest`'s expiry case drives the
 * reclaim itself.
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
        jdbc.execute("TRUNCATE TABLE idempotency_keys, probe_results")
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

    /**
     * A6, at the row: `idempotency_keys_key_check` is the same bound the
     * interceptor enforces, for a writer that does not come through it.
     */
    @Test
    fun `the table itself refuses a key that is empty, over 255 characters, or not visible ASCII`() {
        val user = UUID.randomUUID()
        val now = Instant.now()

        for (key in listOf("", "k".repeat(256), "two words", "cl\u00e9", "tab\t")) {
            val refused = shouldThrow<DataIntegrityViolationException> { store.insert(record(user, key, now)) }
            refused.mostSpecificCause.message.orEmpty() shouldContain "idempotency_keys_key_check"
        }
        store.insert(record(user, "k".repeat(255), now))
        store.insert(record(user, "!~", now))
    }

    @Test
    fun `a live row is found whatever path asks, so the caller can refuse a different target`() {
        val user = UUID.randomUUID()
        val bondA = UUID.randomUUID()
        val now = Instant.now()
        store.insert(record(user, "one-key", now, path = "/api/v1/bonds/$bondA/entries"))

        val found = store.find(user, "one-key", now)

        found.shouldNotBeNull()
        found.path shouldBe "/api/v1/bonds/$bondA/entries"
        found.method shouldBe "POST"
        store.find(UUID.randomUUID(), "one-key", now).shouldBeNull()
    }

    @Test
    fun `an expired row is not found, and deleteExpired removes it and nothing live`() {
        val user = UUID.randomUUID()
        val longAgo = Instant.parse("2020-01-01T00:00:00Z")
        val now = Instant.now()
        store.insert(record(user, "old", longAgo))
        store.insert(record(user, "live", now))

        store.find(user, "old", now).shouldBeNull()
        store.deleteExpired(user, "old", now)
        store.deleteExpired(user, "live", now)

        jdbc.queryForList("SELECT idempotency_key FROM idempotency_keys", String::class.java) shouldBe listOf("live")
    }

    @Test
    fun `a result is named by id and kind together or not at all`() {
        // V11's pair CHECK (Task 6 carry 2): an id with no kind cannot be
        // routed to a re-read. Drop the constraint and these inserts succeed.
        val user = UUID.randomUUID()
        val reserved = record(user, "pair", Instant.now())
        store.insert(reserved)

        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE idempotency_keys SET result_id = ? WHERE id = ?", UUID.randomUUID(), reserved.id)
        }
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE idempotency_keys SET result_kind = 'ENTRY' WHERE id = ?", reserved.id)
        }
        val both = "UPDATE idempotency_keys SET result_id = ?, result_kind = 'ENTRY' WHERE id = ?"
        jdbc.update(both, UUID.randomUUID(), reserved.id) shouldBe 1
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
        store.insert(record(user, "k", nanos))

        val back = store.find(user, "k", nanos.plusSeconds(1))

        back.shouldNotBeNull()
        back.createdAt shouldBe nanos.truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
