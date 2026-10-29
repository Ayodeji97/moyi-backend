package com.moyi.media.service

import com.moyi.media.api.AttachableKind
import com.moyi.media.api.MediaAttachments
import com.moyi.media.api.MediaLifecycle
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **An attached asset is never reaped** (spec §4 D5), whichever of the two
 * reaches the row first: the entry's claim of a photo a day on no entry, and
 * the reaper deciding it is a day on no entry. Each test holds one side's row
 * lock open, in a transaction it controls, and lets the other run against it;
 * the test synchronises on the lock itself (Postgres's own record of who is
 * waiting), never on time.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@Timeout(value = 60, unit = TimeUnit.SECONDS)
internal class AttachReapRaceTest(
    @Autowired private val attachments: MediaAttachments,
    @Autowired private val lifecycle: MediaLifecycle,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val transactions = TransactionTemplate(transactionManager)
    private val ada = UUID.randomUUID()
    private val entry = UUID.randomUUID()

    @BeforeEach
    fun clear() {
        rows.clear()
    }

    @Test
    fun `a claim that holds the photo first wins, and the reaper running meanwhile and after never deletes it`() {
        val photo = rows.insert(owner = ada, createdAt = T0)
        val claimed = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            // The entry's transaction: the claim is made, and its row lock held, until the test says commit.
            val claim =
                pool.submit(
                    Callable {
                        transactions.execute {
                            val won = attachments.claim(ada, photo, AttachableKind.IMAGE, entry)
                            claimed.countDown()
                            check(commit.await(SETTLE_SECONDS, TimeUnit.SECONDS)) { "the test never let the claim commit" }
                            won
                        }
                    },
                )
            check(claimed.await(SETTLE_SECONDS, TimeUnit.SECONDS)) { "the claim never ran" }

            // The reaper meets the claimed row while the claim is uncommitted: it passes over it, and waits for nobody.
            lifecycle.reap(LATER, LIMIT).changed shouldBe 0

            commit.countDown()
            claim.get(SETTLE_SECONDS, TimeUnit.SECONDS) shouldBe true
        } finally {
            pool.shutdownNow()
        }

        // And once the claim has committed, the photo is attached: no later run reaps it.
        lifecycle.reap(LATER, LIMIT).changed shouldBe 0
        rows.statusOf(photo) shouldBe "READY"
        rows.row(photo)["attached_entry_id"] shouldBe entry
    }

    @Test
    fun `a reaper that holds the photo first deletes it, and the claim waiting on it is refused`() {
        val photo = rows.insert(owner = ada, createdAt = T0)
        val pool = Executors.newSingleThreadExecutor()
        // The reaper's transaction, held open: its own joins this one, so its row lock lasts until the test commits.
        val reaper = transactionManager.getTransaction(DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED))
        try {
            lifecycle.reap(LATER, LIMIT).changed shouldBe 1

            val claim = pool.submit(Callable { transactions.execute { attachments.claim(ada, photo, AttachableKind.IMAGE, entry) } })
            awaitWaiterOn(photo)

            transactionManager.commit(reaper)
            claim.get(SETTLE_SECONDS, TimeUnit.SECONDS) shouldBe false
        } finally {
            if (!reaper.isCompleted) transactionManager.rollback(reaper)
            pool.shutdownNow()
        }

        rows.statusOf(photo) shouldBe "DELETED"
        rows.row(photo)["attached_entry_id"].shouldBeNull()
    }

    /**
     * Until a session is seen waiting for a lock on the claim's statement. Asked
     * on a connection of its own, outside the reaper's transaction: inside one,
     * Postgres answers `pg_stat_activity` from a snapshot taken at its first
     * read, and the wait would never be seen.
     */
    private fun awaitWaiterOn(photo: UUID) {
        val deadline = Instant.now().plus(Duration.ofSeconds(SETTLE_SECONDS))
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            val waiting = connection.prepareStatement(CLAIM_WAITING)
            while (waiting.executeQuery().use { it.next() && it.getInt(1) < 1 }) {
                check(Instant.now().isBefore(deadline)) { "the claim of $photo never waited on the reaper's lock" }
                Thread.onSpinWait()
            }
        }
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-06-01T10:00:00Z")

        /** Two days on: the photo has been a day on no entry, and is the reaper's. */
        val LATER: Instant = T0.plus(Duration.ofDays(2))
        const val LIMIT = 100
        const val SETTLE_SECONDS = 20L
        const val CLAIM_WAITING =
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                "AND query LIKE 'UPDATE media_assets SET attached_entry_id%'"
    }
}
