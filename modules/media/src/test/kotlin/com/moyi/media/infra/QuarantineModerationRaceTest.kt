package com.moyi.media.infra

import com.moyi.common.testing.MutableClock
import com.moyi.media.api.BlockOutcome
import com.moyi.media.api.DeleteOutcome
import com.moyi.media.api.QuarantineModeration
import com.moyi.media.api.Review
import com.moyi.media.api.ReviewRefusal
import io.kotest.matchers.collections.shouldBeEmpty
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
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The races the port's guards are taken under a row lock for (A2 spec §6.3, §7.4, §8; plan
 * Task 1). In each, transaction A holds the asset's row with an act the guard must see; the
 * port's call in B is **observed waiting for that lock** in `pg_stat_activity`, never assumed
 * to by a sleep; A commits; B must answer from what A committed.
 *
 * Every wait has a deadline and fails the test when it passes, so a mutation that removes a
 * lock turns a test red rather than hanging the build.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
internal class QuarantineModerationRaceTest(
    @Autowired private val dataSource: DataSource,
    @Autowired private val port: QuarantineModeration,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val clock: MutableClock,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val entry = UUID.randomUUID()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_report_holds, media_assets, media_moderation_log, outbox_deliveries, outbox_events")
    }

    /** Race 1: the evidence race. A block commits while the console's delete waits; the evidence stays. */
    @Test
    fun `a delete that waits on a block sees the block and refuses, and the evidence stays`() {
        val photo = quarantined()

        val outcome =
            whileHeld("SELECT media_quarantine_block(?, 'op', 'csam')", photo) {
                transactions.execute { port.delete(photo, ACTOR, NOTE) }.outcome
            }

        outcome shouldBe DeleteOutcome.EVIDENCE_SERVER_ONLY
        state(photo) shouldBe "QUARANTINED|BLOCKED|true"
        logged(photo, "DELETED").shouldBeEmpty()
    }

    /** Race 2: a report filed while the console's delete waits; the delete leaves it to the report. */
    @Test
    fun `a delete that waits on a report's hold sees the hold and refuses`() {
        val photo = quarantined(attachedTo = entry)

        val outcome =
            whileHeld("SELECT media_report_hold(?, ?, 'member-report')", entry, REPORT) {
                transactions.execute { port.delete(photo, ACTOR, NOTE) }.outcome
            }

        outcome shouldBe DeleteOutcome.REPORT_HELD
        state(photo) shouldBe "QUARANTINED|QUARANTINED|false"
        logged(photo, "DELETED").shouldBeEmpty()
    }

    /** Race 2, for the block: a report's asset is blocked in the report's uphold, not from the quarantine page. */
    @Test
    fun `a block that waits on a report's hold sees the hold and refuses`() {
        val photo = quarantined(attachedTo = entry)

        val outcome =
            whileHeld("SELECT media_report_hold(?, ?, 'member-report')", entry, REPORT) {
                transactions.execute { port.block(photo, ACTOR, NOTE) }.outcome
            }

        outcome shouldBe BlockOutcome.REPORT_HELD
        state(photo) shouldBe "QUARANTINED|QUARANTINED|false"
        logged(photo, "BLOCKED").shouldBeEmpty()
    }

    /**
     * Race 4, the removal race (spec §9.3): a console delete commits while the block waits. The
     * block must see the DELETED row it waited for and answer `GONE`, not block what the export
     * may already list as removed. V36's function alone would block it until the purge runs.
     */
    @Test
    fun `a block that waits on a delete sees the deletion and answers gone`() {
        val photo = quarantined()

        val outcome =
            whileHeld("SELECT media_quarantine_delete(?, 'op', 'gore')", photo) {
                transactions.execute { port.block(photo, ACTOR, NOTE) }.outcome
            }

        outcome shouldBe BlockOutcome.GONE
        state(photo) shouldBe "DELETED|QUARANTINED|false"
        logged(photo, "BLOCKED").shouldBeEmpty()
    }

    /**
     * Race 3: a report filed while the view waits. The view's unlocked first read saw no
     * report; its share lock waits for the hold; the holds it then reads are not the ones it
     * was asked about, so it refuses, and the moderator reloads.
     */
    @Test
    fun `a view that waits on a report's hold sees the hold and refuses it as changed`() {
        val photo = quarantined(attachedTo = entry)

        val review =
            whileHeld("SELECT media_report_hold(?, ?, 'member-report')", entry, REPORT, waitingOn = SHARE_LOCK) {
                transactions.execute {
                    val seen = port.reportsHolding(photo).toSet()
                    seen.shouldBeEmpty()
                    port.reviewCopy(photo, seen)
                }
            }

        review shouldBe Review.Refused(ReviewRefusal.CHANGED)
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    /**
     * Runs [sql] in transaction A, left open; starts [act] in B; waits, with a deadline, until
     * B is seen waiting for a row lock; commits A; returns B's answer, again with a deadline.
     */
    private fun <T> whileHeld(
        sql: String,
        vararg args: Any,
        waitingOn: String = ANY_PORT_STATEMENT,
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

    private fun quarantined(attachedTo: UUID? = null): UUID {
        val id = rows.insert(status = "QUARANTINED", createdAt = clock.instant(), attachedTo = attachedTo, verdict = "QUARANTINED")
        jdbc.update("UPDATE media_assets SET moderation_scores = '{\"reason\":\"EXTREME_VIOLENCE\"}' WHERE id = ?", id)
        return id
    }

    private fun state(id: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, '') || '|' || evidence_held FROM media_assets WHERE id = ?",
            String::class.java,
            id,
        )!!

    private fun logged(
        id: UUID,
        action: String,
    ): List<String> =
        jdbc
            .queryForList("SELECT actor FROM media_moderation_log WHERE media_id = ? AND action = ?", String::class.java, id, action)
            .filterNotNull()

    private companion object {
        const val ACTOR = "admin:0f0f0f0f-0000-4000-8000-0000000000ad"
        const val NOTE = "looked, decided"
        val REPORT: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000fc01")

        /** The port's lock or one of the functions it calls: a delete or a block, waiting. */
        const val ANY_PORT_STATEMENT = "%media\\_%"

        /** The view's share lock on the asset, and nothing else (race 3). */
        const val SHARE_LOCK = "%FROM media_assets WHERE id =%FOR SHARE%"

        /** Every wait in this class: long enough for a loaded CI runner, short enough to fail fast. */
        const val DEADLINE_SECONDS = 10L
    }
}
