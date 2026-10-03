package com.moyi.common.web.idempotency

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Spec §5.4's one transaction — lock, reserve, mutate, complete — driven
 * directly, no HTTP: [IdempotentExecution.once] inside a [TransactionTemplate],
 * the way `SubmitEntry` calls it, with `probe_results` (a test-only table,
 * `V11_1`) standing in for the domain write.
 *
 * Each test names the mechanism it exists to catch. Removing that mechanism
 * makes the test fail on an assertion, not hang: every wait is bounded and
 * every "did not queue" is observed through `pg_blocking_pids`, not inferred
 * from a sleep.
 */
@SpringBootTest(classes = [IdempotencyTestApplication::class])
class IdempotentExecutionTest(
    @Autowired private val execution: IdempotentExecution,
    @Autowired dataSource: DataSource,
    @Autowired transactionManager: PlatformTransactionManager,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val tx = TransactionTemplate(transactionManager)
    private val pool = Executors.newFixedThreadPool(2)
    private val user = UUID.randomUUID()
    private val runs = AtomicInteger(0)

    @AfterEach
    fun clear() {
        // The pool first, bounded: a call still parked must not race the truncate.
        pool.shutdownNow()
        check(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "a worker thread outlived the test" }
        jdbc.execute("TRUNCATE TABLE idempotency_keys, probe_results")
    }

    private fun request(
        key: String,
        path: String = PATH,
        method: String = "POST",
        fingerprint: String = FINGERPRINT,
    ) = IdempotentRequest(userId = user, key = key, method = method, path = path, fingerprint = fingerprint)

    /** The domain write: one `probe_results` row, named back as the result. */
    private fun insertProbe(label: String = "first"): IdempotentResult<String> {
        runs.incrementAndGet()
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO probe_results (id, label) VALUES (?, ?)", id, label)
        return IdempotentResult(value = label, resultId = id, kind = ResultKind.ENTRY, status = CREATED)
    }

    private fun once(
        request: IdempotentRequest,
        block: () -> IdempotentResult<String>,
    ): IdempotentOutcome<String> = checkNotNull(tx.execute { execution.once(request, block) })

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    @Test
    fun `a crash after the mutation rolls back the key as well as the write, and the retry then succeeds`() {
        // Commit the reservation in its own transaction (the old preHandle
        // shape) and the key survives the crash: the retry below is then a
        // 409 in flight, or a replay of a write that never happened.
        shouldThrow<IllegalStateException> {
            once(request("crash-key")) {
                insertProbe()
                error("boom, after the domain write and before commit")
            }
        }

        count("SELECT count(*) FROM idempotency_keys") shouldBe 0
        count("SELECT count(*) FROM probe_results") shouldBe 0

        val retried = once(request("crash-key")) { insertProbe() }

        retried.wasReplayed shouldBe false
        count("SELECT count(*) FROM idempotency_keys WHERE result_id IS NOT NULL") shouldBe 1
        count("SELECT count(*) FROM probe_results") shouldBe 1
    }

    @Test
    fun `a refusal inside the block leaves no key behind, so a corrected retry under the same key runs`() {
        // A 4xx is thrown, not returned, so it rolls back like a crash: the
        // corrected retry is a fresh request, not 422 IDEMPOTENCY_KEY_REUSED
        // against a body the server refused.
        shouldThrow<IllegalArgumentException> {
            once(request("refused-key", fingerprint = "bad-body")) { throw IllegalArgumentException("a 4xx") }
        }

        val corrected = once(request("refused-key", fingerprint = "good-body")) { insertProbe() }

        corrected.wasReplayed shouldBe false
        runs.get() shouldBe 1
    }

    @Test
    fun `a concurrent second request under the same key is refused at once, not queued`() {
        // Use the blocking pg_advisory_xact_lock, or drop the lock and rely on
        // the unique index, and the second call parks behind the first's
        // transaction: it shows up in pg_blocking_pids and is not done.
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holderPid = AtomicInteger(0)
        val first =
            pool.submit<IdempotentOutcome<String>> {
                once(request("k")) {
                    // Published only after the lock and the reservation exist:
                    // the block runs after both.
                    holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                    inside.countDown()
                    release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    insertProbe()
                }
            }
        inside.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true

        val second = pool.submit<IdempotentOutcome<String>> { once(request("k")) { insertProbe("second") } }
        awaitDoneOrBlockedBy(holderPid.get(), second)

        second.isDone shouldBe true
        val failure = shouldThrow<ExecutionException> { second.get() }
        (failure.cause is IdempotencyKeyInFlightException) shouldBe true

        release.countDown()
        first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).wasReplayed shouldBe false
        count("SELECT count(*) FROM probe_results") shouldBe 1
        runs.get() shouldBe 1
    }

    @Test
    fun `a replay returns the first result's identity and never re-runs the block`() {
        val created = once(request("replay-key")) { insertProbe() }

        val replayed = once(request("replay-key")) { error("must not re-run") }

        replayed.wasReplayed shouldBe true
        // No stored copy of what was produced: the caller re-reads by id.
        replayed.value.shouldBeNull()
        replayed.resultId shouldBe created.resultId
        replayed.resultKind shouldBe ResultKind.ENTRY
        replayed.status shouldBe CREATED
    }

    @Test
    fun `the same key with a different method, path or fingerprint is refused as reuse`() {
        once(request("reuse-key")) { insertProbe() }

        shouldThrow<IdempotencyKeyReusedException> { once(request("reuse-key", path = "/other")) { insertProbe() } }
        shouldThrow<IdempotencyKeyReusedException> { once(request("reuse-key", method = "PUT")) { insertProbe() } }
        shouldThrow<IdempotencyKeyReusedException> { once(request("reuse-key", fingerprint = "other")) { insertProbe() } }
        runs.get() shouldBe 1
    }

    @Test
    fun `the advisory lock is only worth taking inside a transaction, so once refuses to run outside one`() {
        shouldThrow<IllegalStateException> { execution.once<String>(request("no-tx")) { insertProbe() } }
        runs.get() shouldBe 0
    }

    @Test
    fun `the completed row records the result identity, status and headers the block returned`() {
        val created =
            once(request("headers-key")) {
                insertProbe().let {
                    IdempotentResult(it.value, it.resultId, it.kind, it.status, etag = "\"3\"", location = "/probe/3")
                }
            }

        val row = jdbc.queryForMap("SELECT * FROM idempotency_keys WHERE idempotency_key = 'headers-key'")
        row["result_id"] shouldBe created.resultId
        row["result_kind"] shouldBe "ENTRY"
        (row["response_status"] as Number).toInt() shouldBe CREATED
        row["response_etag"] shouldBe "\"3\""
        row["response_location"] shouldBe "/probe/3"
        row["request_hash"] shouldBe FINGERPRINT

        val replayed = once(request("headers-key")) { error("must not re-run") }
        replayed.etag shouldBe "\"3\""
        replayed.location shouldBe "/probe/3"
    }

    /** Until [waiter] is done, or genuinely parked behind [holderPid] — the house wait, never a sleep. */
    private fun awaitDoneOrBlockedBy(
        holderPid: Int,
        waiter: Future<*>,
    ) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            waiter.isDone ||
                count("SELECT count(*) FROM pg_stat_activity WHERE $holderPid = ANY(pg_blocking_pids(pid))") > 0
        }
    }

    private companion object {
        const val PATH = "/api/v1/probe/entries"
        const val FINGERPRINT = "fingerprint-of-the-first-body"
        const val CREATED = 201
        const val TIMEOUT_SECONDS = 10L
    }
}
