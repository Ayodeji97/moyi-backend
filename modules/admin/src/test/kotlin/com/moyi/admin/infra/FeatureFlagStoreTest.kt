package com.moyi.admin.infra

import com.moyi.common.core.FeatureFlag
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `feature_flags` (V67) and the one [com.moyi.common.core.FeatureFlags] (ADR-0067 decisions 8, 10,
 * 11): a flag with no row is its default, a row wins, every read goes to the table, and two changes
 * of one flag are serialised by the advisory lock, synchronised on `pg_locks`, never a sleep.
 */
@SpringBootTest
internal class FeatureFlagStoreTest(
    @Autowired private val store: FeatureFlagStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var admin: UUID

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM feature_flags")
        admin = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO admin_accounts (id, email, role, credentials_changed_at, status) VALUES (?, ?, 'ADMIN', now(), 'ACTIVE')",
            admin,
            "flags-$admin@example.com",
        )
    }

    @Test
    fun `with no row every flag is its default, which is on`() {
        FeatureFlag.entries.forEach { store.isOn(it) shouldBe it.defaultOn }
        FeatureFlag.entries.all { it.defaultOn } shouldBe true
        store.states().map { it.flag to it.on } shouldContainExactlyInAnyOrder FeatureFlag.entries.map { it to true }
        store.states().all { it.changedAt == null && it.changedBy == null } shouldBe true
    }

    @Test
    fun `a written row wins, and every read sees the table as it is now`() {
        val at = Instant.parse("2026-11-14T10:00:00Z")
        transactions.executeWithoutResult { store.write(FeatureFlag.DAILY_REMINDERS, false, at, admin) }
        store.isOn(FeatureFlag.DAILY_REMINDERS) shouldBe false
        store.isOn(FeatureFlag.ON_THIS_DAY_PUSHES) shouldBe true

        // Written behind the store's back: no cache stands between the table and the next read.
        jdbc.update("UPDATE feature_flags SET enabled = true WHERE key = 'DAILY_REMINDERS'")
        store.isOn(FeatureFlag.DAILY_REMINDERS) shouldBe true

        val state = store.states().single { it.flag == FeatureFlag.DAILY_REMINDERS }
        state.changedAt shouldBe at
        state.changedBy shouldBe admin
    }

    @Test
    fun `the lock and the write join a transaction or refuse to run`() {
        shouldThrow<IllegalTransactionStateException> { store.lockAndRead(FeatureFlag.AI_EMBEDDINGS) }
        shouldThrow<IllegalTransactionStateException> { store.write(FeatureFlag.AI_EMBEDDINGS, false, Instant.now(), admin) }
    }

    @Test
    fun `a second change of one flag waits for the first, and then reads what the first wrote`() {
        val firstHolds = CountDownLatch(1)
        val firstMayCommit = CountDownLatch(1)
        val first =
            CompletableFuture.runAsync {
                transactions.executeWithoutResult {
                    store.lockAndRead(FeatureFlag.ON_THIS_DAY_PUSHES) shouldBe true
                    store.write(FeatureFlag.ON_THIS_DAY_PUSHES, false, Instant.now(), admin)
                    firstHolds.countDown()
                    firstMayCommit.await(DEADLINE.seconds, TimeUnit.SECONDS) shouldBe true
                }
            }
        firstHolds.await(DEADLINE.seconds, TimeUnit.SECONDS) shouldBe true
        val second = CompletableFuture.supplyAsync { transactions.execute { store.lockAndRead(FeatureFlag.ON_THIS_DAY_PUSHES) } }

        awaitAdvisoryWaiter()
        second.isDone shouldBe false
        firstMayCommit.countDown()
        first.get(DEADLINE.seconds, TimeUnit.SECONDS)
        second.get(DEADLINE.seconds, TimeUnit.SECONDS) shouldBe false
    }

    @Test
    fun `a change of one flag does not wait for a change of another`() {
        val firstHolds = CountDownLatch(1)
        val firstMayCommit = CountDownLatch(1)
        val first =
            CompletableFuture.runAsync {
                transactions.executeWithoutResult {
                    store.lockAndRead(FeatureFlag.DAILY_REMINDERS)
                    firstHolds.countDown()
                    firstMayCommit.await(DEADLINE.seconds, TimeUnit.SECONDS) shouldBe true
                }
            }
        firstHolds.await(DEADLINE.seconds, TimeUnit.SECONDS) shouldBe true
        try {
            transactions.execute { store.lockAndRead(FeatureFlag.AI_EMBEDDINGS) } shouldBe true
        } finally {
            firstMayCommit.countDown()
            first.get(DEADLINE.seconds, TimeUnit.SECONDS)
        }
    }

    /** Until a backend waits on an advisory lock of the flags' space, or the deadline fails the test. */
    private fun awaitAdvisoryWaiter() {
        val deadline = System.nanoTime() + DEADLINE.toNanos()
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND classid = ?",
                    Int::class.java,
                    FeatureFlagStore.LOCK_SPACE,
                )!!
            if (waiting > 0) return
            Thread.onSpinWait()
        }
        error("no change waited on the flag's lock within $DEADLINE")
    }

    private companion object {
        val DEADLINE: Duration = Duration.ofSeconds(10)
    }
}
