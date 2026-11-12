package com.moyi.app.admin

import com.moyi.app.admin.ModerationRig.Companion.NOTE
import com.moyi.app.admin.ModerationRig.Companion.result
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.sql.DriverManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Two moderators, one report (A2 spec §8; plan Task 6, Review Focus 4): both dismiss it at once,
 * through HTTP, in two sessions. One is told "Dismissed.", the other "Already decided by someone
 * else.", and the audit log has one `SUCCEEDED` and one `REFUSED` row.
 *
 * Synchronised on what happened, never a sleep: a third connection, outside the application's
 * pool, holds the report's row; each POST is seen in `pg_stat_activity` waiting on that lock (the
 * port's stale check, `FOR UPDATE`) before the holder lets go, so the second is blocked before the
 * first can commit. Each wait has a deadline and fails the test when it passes, so a lock removed
 * by a mutation turns this red instead of hanging the build.
 */
class ModerationConcurrencyTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ModerationRig(mockMvc, jdbc, clock)

    @Test
    fun `two sessions dismissing one report get one Dismissed and one Already decided, audited SUCCEEDED and REFUSED`() {
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "OTHER")
        val first = rig.session()
        val second = rig.session()
        val form = mapOf("expectedReason" to "OTHER", "note" to NOTE)
        val postgres = PostgresIntegrationTest.postgres

        val results =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { holder ->
                holder.autoCommit = false
                try {
                    holder.prepareStatement("SELECT 1 FROM entry_reports WHERE id = ? FOR UPDATE").use {
                        it.setObject(1, report)
                        it.executeQuery().close()
                    }
                    val one = CompletableFuture.supplyAsync { rig.post(first, "/admin/reports/$report/dismiss", form) }
                    awaitWaiting(1)
                    val two = CompletableFuture.supplyAsync { rig.post(second, "/admin/reports/$report/dismiss", form) }
                    awaitWaiting(2)
                    holder.rollback()
                    listOf(one, two).map { it.get(DEADLINE_SECONDS, TimeUnit.SECONDS) }
                } finally {
                    holder.rollback()
                }
            }

        results.map { it.result() } shouldContainExactlyInAnyOrder listOf("Dismissed.", "Already decided by someone else.")
        rig.reportStatus(report) shouldBe "DISMISSED|OTHER"
        rig.assetState(photo) shouldBe "READY|PASSED|false"
        (
            rig.audited(
                rig.adminOf(first),
            ) + rig.audited(rig.adminOf(second))
        ).map { "${it.head}|${it.metadata}" } shouldContainExactlyInAnyOrder
            listOf(
                """REPORT_DISMISSED|SUCCEEDED|$report|{"result": "DISMISSED"}""",
                """REPORT_DISMISSED|REFUSED|$report|{"result": "NOT_OPEN"}""",
            )
    }

    /** Until [count] backends of this database wait for a lock in the port's stale check, or the deadline fails the test. */
    private fun awaitWaiting(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                        "AND datname = current_database() AND pid <> pg_backend_pid() AND query LIKE ?",
                    Int::class.java,
                    STALE_CHECK,
                )!!
            if (waiting >= count) return
            Thread.onSpinWait()
        }
        error("fewer than $count dismisses were seen waiting on the report's row lock within $DEADLINE_SECONDS s")
    }

    private companion object {
        const val DEADLINE_SECONDS = 10L

        /** The port's stale check, the report's row `FOR UPDATE` (spec §8). `_` escaped: it is LIKE's any-character. */
        const val STALE_CHECK = "%SELECT reason FROM entry\\_reports WHERE id = %FOR UPDATE%"
    }
}
