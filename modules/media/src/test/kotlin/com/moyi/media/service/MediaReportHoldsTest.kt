package com.moyi.media.service

import com.moyi.common.testing.MutableClock
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.api.MediaReports
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * What a member's report does to an entry's media (ADR-0053; V38): held back
 * for the report, given back exactly as it was when the report is dismissed
 * and nothing else holds it, or taken down when it is upheld. The functions
 * are called as `ReportEntry` and the operator's `scripts/moyi reports` call
 * them; `gratitude`'s `ReportCommandsTest` runs the script end to end.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@Suppress("LongParameterList") // A test's collaborators, each injected by Spring.
internal class MediaReportHoldsTest(
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val images: ImageViews,
    @Autowired private val reports: MediaReports,
    @Autowired private val transactions: TransactionTemplate,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val entry = UUID.randomUUID()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_report_holds, media_assets, media_moderation_log, outbox_deliveries, outbox_events")
        jdbc.update("DELETE FROM outbox_subscriptions WHERE consumer_id = ?", READY_CONSUMER)
        jdbc.update("DELETE FROM outbox_consumers WHERE consumer_id = ?", READY_CONSUMER)
    }

    @Test
    fun `a hold takes the entry's ready media back from the partner, remembers what it was, and needs the report's transaction`() {
        val photo = passed(sensitive = true)
        val voice = rows.insert(owner = ada, kind = "VOICE", createdAt = clock.instant(), attachedTo = entry)
        val elsewhere = passed(attachedTo = UUID.randomUUID())

        assertThrows<IllegalTransactionStateException> { reports.holdFor(entry, REPORT) }
        hold(REPORT) shouldBe 2

        listOf(photo, voice).forEach { status(it) shouldBe "QUARANTINED|QUARANTINED|REPORTED" }
        status(elsewhere) shouldBe "READY|PASSED|"
        images.shownTo(bea, listOf(photo)).getValue(photo).status shouldBe ImageViewStatus.PROCESSING
        images.shownTo(ada, listOf(photo)).getValue(photo).status shouldBe ImageViewStatus.NOT_SHARED
        jdbc.queryForObject(
            "SELECT moderation_scores -> 'heldFrom' ->> 'verdict' FROM media_assets WHERE id = ?",
            String::class.java,
            photo,
        ) shouldBe
            "PASSED"
        audit(photo) shouldContainExactly listOf("QUARANTINED|member-report|report $REPORT")
    }

    @Test
    fun `a dismissal gives a passed photo back exactly as it was, and the partner is shown it again`() {
        val photo = passed(sensitive = true)
        val before = moderation(photo)
        hold(REPORT)

        release(REPORT) shouldBe 1

        status(photo) shouldBe "READY|PASSED|"
        moderation(photo) shouldBe before
        images.shownTo(bea, listOf(photo)).getValue(photo).let {
            it.status shouldBe ImageViewStatus.READY
            it.sensitive shouldBe true
        }
        holdsOf(photo) shouldBe 0
        audit(photo).last() shouldBe "RELEASED|op-fake-ada|dismissed"
    }

    /** Never passed unseen: an asset held before moderation reached it goes back to moderation. */
    @Test
    fun `a dismissal hands a photo that had no verdict back to moderation, not to the partner`() {
        val photo = rows.insert(owner = ada, createdAt = clock.instant(), attachedTo = entry, verdict = null)
        hold(REPORT)
        // This context moderates nothing, so nothing subscribes to MediaReady: a consumer of the test's own does, and
        // is removed again, so that the delivery the release must write has somebody to be written for.
        jdbc.update("INSERT INTO outbox_consumers (consumer_id, registered_at) VALUES (?, now())", READY_CONSUMER)
        jdbc.update(
            "INSERT INTO outbox_subscriptions (consumer_id, event_type, subscribed_at) VALUES (?, 'MediaReady', now())",
            READY_CONSUMER,
        )

        release(REPORT) shouldBe 1

        status(photo) shouldBe "READY||"
        jdbc.queryForObject(
            "SELECT moderated_at IS NULL AND moderation_scores IS NULL FROM media_assets WHERE id = ?",
            Boolean::class.java,
            photo,
        ) shouldBe
            true
        images.shownTo(bea, listOf(photo)).getValue(photo).status shouldBe ImageViewStatus.PROCESSING
        val event =
            jdbc.queryForObject(
                "SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type = 'MediaReady'",
                UUID::class.java,
                photo,
            )
        jdbc.queryForList("SELECT consumer_id FROM outbox_deliveries WHERE event_id = ?", String::class.java, event) shouldBe
            listOf(READY_CONSUMER)
    }

    @Test
    fun `an asset two reports hold is given back only when both are dismissed`() {
        val photo = passed()
        hold(REPORT)
        hold(OTHER_REPORT) shouldBe 1

        release(REPORT) shouldBe 0
        status(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"

        release(OTHER_REPORT) shouldBe 1
        status(photo) shouldBe "READY|PASSED|"
    }

    /** A report never lets go of what moderation held back: dismissing it changes nothing moderation decided. */
    @Test
    fun `what moderation held back is held by the report too, and stays held back when the report is dismissed`() {
        val violent = passed()
        jdbc.update(
            "UPDATE media_assets SET status = 'QUARANTINED', moderation_verdict = 'QUARANTINED', " +
                "moderation_scores = jsonb_build_object('reason', 'EXTREME_VIOLENCE') WHERE id = ?",
            violent,
        )

        hold(REPORT) shouldBe 1
        release(REPORT) shouldBe 0

        status(violent) shouldBe "QUARANTINED|QUARANTINED|EXTREME_VIOLENCE"
        audit(violent) shouldBe emptyList()
    }

    @Test
    fun `a deleted asset is not held`() {
        val gone = rows.insert(owner = ada, status = "DELETED", createdAt = clock.instant(), attachedTo = entry)

        hold(REPORT) shouldBe 0
        holdsOf(gone) shouldBe 0
    }

    @Test
    fun `an upheld report deletes what it holds, through the operator's delete`() {
        val photo = passed()
        hold(REPORT)

        takedown(REPORT, "DELETE") shouldContainExactly listOf("$photo|DELETED")

        status(photo).substringBefore('|') shouldBe "DELETED"
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'MediaReleased'",
            Int::class.java,
            photo,
        ) shouldBe 1
        holdsOf(photo) shouldBe 0
    }

    /** A photo the operator released meanwhile is held again and deleted all the same. */
    @Test
    fun `an upheld delete takes a photo that was released meanwhile`() {
        val photo = passed()
        hold(REPORT)
        jdbc.queryForObject("SELECT media_quarantine_release(?, 'op-fake-ada', 'looked')", String::class.java, photo)

        takedown(REPORT, "DELETE") shouldContainExactly listOf("$photo|DELETED")
    }

    @Test
    fun `an upheld block holds the evidence, and a delete never takes a blocked asset's evidence`() {
        val photo = passed()
        hold(REPORT)
        hold(OTHER_REPORT)

        takedown(REPORT, "BLOCK") shouldContainExactly listOf("$photo|BLOCKED")
        status(photo) shouldBe "QUARANTINED|BLOCKED|REPORTED"
        jdbc.queryForObject("SELECT evidence_held FROM media_assets WHERE id = ?", Boolean::class.java, photo) shouldBe true

        takedown(OTHER_REPORT, "DELETE") shouldContainExactly listOf("$photo|BLOCKED_KEPT")
        status(photo) shouldBe "QUARANTINED|BLOCKED|REPORTED"
        jdbc.queryForObject("SELECT evidence_held FROM media_assets WHERE id = ?", Boolean::class.java, photo) shouldBe true
    }

    /**
     * The race V38 locks the asset's row for: a report filed while another is
     * being dismissed. The new report's hold has the row; the dismissal waits
     * for it, then sees its hold, and gives nothing back. Synchronised on the
     * dismissal being seen to wait for the lock, never on a sleep.
     */
    @Test
    fun `a dismissal that waits on a new report's hold sees it, and gives nothing back`() {
        val photo = passed()
        hold(REPORT)

        dataSource.connection.use { filing ->
            filing.autoCommit = false
            filing.prepareStatement("SELECT media_report_hold(?, ?, 'member-report')").use {
                it.setObject(1, entry)
                it.setObject(2, OTHER_REPORT)
                it.executeQuery().close()
            }
            val dismissal = CompletableFuture.supplyAsync { release(REPORT) }
            awaitLockWait()
            filing.commit()
            dismissal.get(30, TimeUnit.SECONDS) shouldBe 0
        }

        status(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"
        jdbc
            .queryForList("SELECT report_id FROM media_report_holds WHERE media_id = ?", UUID::class.java, photo)
            .shouldContainExactlyInAnyOrder(OTHER_REPORT)
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    private fun passed(
        sensitive: Boolean = false,
        attachedTo: UUID = entry,
    ): UUID {
        val id = rows.insert(owner = ada, createdAt = clock.instant(), attachedTo = attachedTo)
        jdbc.update(
            "UPDATE media_assets SET sensitive = ?, " +
                "moderation_scores = jsonb_build_object('reason', 'NONE', 'moderator', 'fake') WHERE id = ?",
            sensitive,
            id,
        )
        return id
    }

    private fun hold(report: UUID): Int = transactions.execute { reports.holdFor(entry, report) }!!

    private fun release(report: UUID): Int =
        jdbc.queryForObject("SELECT media_report_release(?, 'op-fake-ada', 'dismissed')", Int::class.java, report)!!

    private fun takedown(
        report: UUID,
        action: String,
    ): List<String> =
        jdbc
            .queryForList(
                "SELECT media_id || '|' || outcome FROM media_report_takedown(?, ?, 'op-fake-ada', 'upheld')",
                String::class.java,
                report,
                action,
            ).filterNotNull()

    private fun status(id: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, '') || '|' || " +
                "CASE WHEN moderation_scores ->> 'reason' = 'NONE' THEN '' ELSE coalesce(moderation_scores ->> 'reason', '') END " +
                "FROM media_assets WHERE id = ?",
            String::class.java,
            id,
        )!!

    /** The verdict's time and what moderation found, as text: a dismissal must give back both, unchanged. */
    private fun moderation(id: UUID): Pair<String?, String?> =
        jdbc.queryForObject(
            "SELECT moderated_at::text, moderation_scores::text FROM media_assets WHERE id = ?",
            { rs, _ -> rs.getString(1) to rs.getString(2) },
            id,
        )!!

    private fun holdsOf(id: UUID): Int =
        jdbc.queryForObject("SELECT count(*) FROM media_report_holds WHERE media_id = ?", Int::class.java, id)!!

    private fun audit(id: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor || '|' || coalesce(note, '') FROM media_moderation_log WHERE media_id = ? ORDER BY at, id",
                String::class.java,
                id,
            ).filterNotNull()

    /** Until another backend waits for a row lock: the dismissal, blocked behind the hold. */
    private fun awaitLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%media_report_release%'",
                    Int::class.java,
                )!!
            if (waiting > 0) return
            Thread.onSpinWait()
        }
        error("the dismissal never waited for the hold's lock")
    }

    private companion object {
        val REPORT: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000fa01")
        val OTHER_REPORT: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000fa02")

        /** A consumer of this test's own, subscribed to MediaReady for one test and removed after it. */
        const val READY_CONSUMER = "test.report-ready"
    }
}
