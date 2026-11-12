package com.moyi.gratitude.infra

import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.api.DismissOutcome
import com.moyi.gratitude.api.ReportModeration
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Two moderators, one report (A2 spec §7.4, §8; plan Task 2). In each case transaction A
 * holds the report's row with an act the port must see; the port's call in B is **observed
 * waiting for that lock** in `pg_stat_activity`, never assumed to by a sleep; A commits; B
 * must answer from what A committed.
 *
 * Every wait has a deadline and fails the test when it passes, so a mutation that removes a
 * lock turns a test red rather than hanging the build.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class ReportModerationConcurrencyTest(
    @Autowired private val dataSource: DataSource,
    @Autowired private val port: ReportModeration,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entry_report_log, entry_reports")
    }

    /**
     * The view's report lock (spec §7.4 step 2): a reclassify to `MINOR_SEXUAL_CONTENT` in
     * flight makes the view's share lock wait, and the view then reads the reason it committed.
     */
    @Test
    fun `a view's reasons wait on a reclassify to a priority reason, and then refuse`() {
        val report = report("HARASSMENT")

        val reviewable =
            whileHeld(
                "SELECT entry_report_reclassify(?, 'MINOR_SEXUAL_CONTENT', 'op', 'the detail says so')",
                report,
                waitingOn = SHARE_LOCK,
            ) {
                transactions.execute { port.reviewableReasons(listOf(report)) }
            }

        reviewable shouldBe false
    }

    /** Spec §8: a dismiss made on a page whose reason was reclassified under it is stale, and does nothing. */
    @Test
    fun `a dismiss that waits on a reclassify sees the new reason and is stale`() {
        val report = report("OTHER")

        val outcome =
            whileHeld("SELECT entry_report_reclassify(?, 'HARASSMENT', 'op', 'the detail says so')", report, waitingOn = STALE_LOCK) {
                transactions.execute { port.dismiss(report, "OTHER", ACTOR, NOTE) }
            }

        outcome shouldBe DismissOutcome.STALE
        status(report) shouldBe "OPEN|HARASSMENT"
        logged(report, "DISMISSED").shouldBeEmpty()
    }

    /** Spec §8: two moderators dismiss one report at once, through the port: one dismisses, one finds it decided. */
    @Test
    fun `two dismisses of one report give one dismissed and one not open`() {
        val report = report("OTHER")
        val firstDone = CountDownLatch(1)
        val commitFirst = CountDownLatch(1)

        val first =
            CompletableFuture.supplyAsync {
                transactions.execute {
                    port.dismiss(report, "OTHER", ACTOR, NOTE).also {
                        firstDone.countDown()
                        check(commitFirst.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "the first dismiss was never let commit" }
                    }
                }
            }
        try {
            check(firstDone.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "the first dismiss did not finish within $DEADLINE_SECONDS s" }
            val second = CompletableFuture.supplyAsync { transactions.execute { port.dismiss(report, "OTHER", OTHER_ACTOR, NOTE) } }
            awaitLockWait(STALE_LOCK)
            commitFirst.countDown()

            listOf(first.get(DEADLINE_SECONDS, TimeUnit.SECONDS), second.get(DEADLINE_SECONDS, TimeUnit.SECONDS)) shouldBe
                listOf(DismissOutcome.DISMISSED, DismissOutcome.NOT_OPEN)
        } finally {
            commitFirst.countDown()
        }
        status(report) shouldBe "DISMISSED|OTHER"
        logged(report, "DISMISSED") shouldContainExactlyInAnyOrder listOf(ACTOR)
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    /**
     * Runs [sql] in transaction A, left open; starts [act] in B; waits, with a deadline, until
     * B is seen waiting for a lock in a statement like [waitingOn]; commits A; returns B's
     * answer, again with a deadline.
     */
    private fun <T> whileHeld(
        sql: String,
        vararg args: Any,
        waitingOn: String,
        act: () -> T,
    ): T =
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            run(holder, sql, *args)
            val waiting = CompletableFuture.supplyAsync(act)
            try {
                awaitLockWait(waitingOn)
                holder.commit()
                waiting.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            } finally {
                holder.rollback() // a no-op after the commit; on a failure, frees B
            }
        }

    private fun run(
        connection: Connection,
        sql: String,
        vararg args: Any,
    ) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
            statement.executeQuery().close()
        }
    }

    /**
     * Until another backend of this database waits for a lock in a statement matching
     * [statement] (a `LIKE` pattern), or the deadline passes: then the test fails.
     */
    private fun awaitLockWait(statement: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                        "AND datname = current_database() AND pid <> pg_backend_pid() AND query LIKE ?",
                    Int::class.java,
                    statement,
                )!!
            if (waiting > 0) return
            Thread.onSpinWait()
        }
        error("no statement like '$statement' was seen waiting for a lock within $DEADLINE_SECONDS s")
    }

    private fun report(reason: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entry_reports (id, target, reported_member_id, bond_id, reporter_member_id, reason, status, created_at) " +
                "VALUES (?, 'MEMBER', ?, ?, ?, ?, 'OPEN', now())",
            id,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            reason,
        )
        return id
    }

    private fun status(id: UUID): String =
        jdbc.queryForObject("SELECT status || '|' || reason FROM entry_reports WHERE id = ?", String::class.java, id)!!

    private fun logged(
        id: UUID,
        action: String,
    ): List<String> =
        jdbc
            .queryForList("SELECT actor FROM entry_report_log WHERE report_id = ? AND action = ?", String::class.java, id, action)
            .filterNotNull()

    private companion object {
        const val ACTOR = "admin:0f0f0f0f-0000-4000-8000-0000000000ad"
        const val OTHER_ACTOR = "admin:0f0f0f0f-0000-4000-8000-0000000000ae"
        const val NOTE = "looked, decided"

        /** The port's stale check: the report's row taken `FOR UPDATE` before any function runs. */
        const val STALE_LOCK = "%FROM entry\\_reports WHERE id = % AND status = 'OPEN' FOR UPDATE%"

        /** The view's share lock on the reports holding the asset (spec §7.4 step 2). */
        const val SHARE_LOCK = "%FROM entry\\_reports WHERE id = ANY%FOR SHARE%"

        /** Every wait in this class: long enough for a loaded CI runner, short enough to fail fast. */
        const val DEADLINE_SECONDS = 10L
    }
}
