package com.moyi.media.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.testing.MutableClock
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.api.MediaErasure
import com.moyi.media.api.MediaLifecycle
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
import java.time.Duration
import java.time.Instant
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
    @Autowired private val erasure: MediaErasure,
    @Autowired private val lifecycle: MediaLifecycle,
    @Autowired private val ownMedia: OwnMedia,
    @Autowired private val dispatcher: OutboxDispatcher,
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

    /**
     * Review of #118, P1: a report made after its entry was erased (a
     * withdrawal, the author's delete) still finds what it showed, through
     * `last_entry_id`, and holds what has not yet been purged, so the objects
     * stay for the decision. What is purged already holds nothing.
     */
    @Test
    fun `a report after the entry was erased holds what is not yet purged, through the entry it was last on`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo = passed(upload = upload)
        putBehindTheApi(display(upload), byteArrayOf(1, 2, 3))
        val purgedAlready = passed()
        transactions.execute { erasure.releaseAllOf(entry) } shouldBe 2
        jdbc.update("UPDATE media_assets SET purged_at = now() WHERE id = ?", purgedAlready)

        hold(REPORT) shouldBe 1

        holdsOf(photo) shouldBe 1
        holdsOf(purgedAlready) shouldBe 0
        jdbc.queryForObject(
            "SELECT attached_entry_id IS NULL AND last_entry_id = ? FROM media_assets WHERE id = ?",
            Boolean::class.java,
            entry,
            photo,
        ) shouldBe
            true
        purge()
        stored(display(upload)) shouldBe true
        purgedAt(photo) shouldBe false
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

    /** Review of #118, P2-b: the operator's release never undoes an open report's hold; the report is decided instead. */
    @Test
    fun `the operator's release is refused while a report holds it, and the refusal is recorded`() {
        val photo = passed()
        hold(REPORT)

        jdbc.queryForObject("SELECT media_quarantine_release(?, 'op-fake-ada', 'looked')", String::class.java, photo) shouldBe "REPORT_HELD"

        status(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"
        audit(photo).last() shouldBe "RELEASE_REFUSED|op-fake-ada|looked"
        takedown(REPORT, "DELETE") shouldContainExactly listOf("$photo|DELETED")
    }

    /** Review of #118, P2-b: an asset held before moderation saw it is never passed by a release, by anyone. */
    @Test
    fun `the operator's release of an asset held before moderation saw it hands it back to moderation, never passes it`() {
        val photo = rows.insert(owner = ada, createdAt = clock.instant(), attachedTo = entry, verdict = null)
        jdbc.queryForObject("SELECT media_quarantine_hold(?, 'op-fake-ada', 'by hand')", String::class.java, photo) shouldBe "HELD"

        jdbc.queryForObject("SELECT media_quarantine_release(?, 'op-fake-ada', 'looked')", String::class.java, photo) shouldBe "REMODERATED"

        status(photo) shouldBe "READY||"
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'MediaReady'",
            Int::class.java,
            photo,
        ) shouldBe
            1
    }

    /** Review of #118, P2-a: what the operator held back for something else is not a report's to give back. */
    @Test
    fun `a dismissal never gives back the operator's own hold`() {
        val photo = passed()
        jdbc.queryForObject(
            "SELECT media_quarantine_hold(?, 'op-fake-ada', 'by hand: a support email')",
            String::class.java,
            photo,
        ) shouldBe
            "HELD"
        hold(REPORT) shouldBe 1

        release(REPORT) shouldBe 0

        status(photo) shouldBe "QUARANTINED|QUARANTINED|REPORTED"
        holdsOf(photo) shouldBe 0
    }

    /**
     * Review of #118, P1, the four ways an asset is deleted while a report is
     * open: its entry erased (an author's DELETE or a withdrawal, both
     * `MediaErasure`), its owner's DELETE once an edit has let it go, and the
     * reaper. Each makes it gone to its owner, and none of them takes its
     * objects: `media.purge` and the best-effort delete spare it. The
     * dismissal hands them to the purge then.
     */
    @Test
    fun `an asset erased while a report holds it keeps its objects until the report is dismissed, then is purged`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo = passed(upload = upload)
        putBehindTheApi(display(upload), byteArrayOf(1, 2, 3))
        hold(REPORT)

        transactions.execute { erasure.releaseAllOf(entry) } shouldBe 1
        purge()

        status(photo).substringBefore('|') shouldBe "DELETED"
        stored(display(upload)) shouldBe true
        purgedAt(photo) shouldBe false

        release(REPORT) shouldBe 1
        purge()

        stored(display(upload)) shouldBe false
        purgedAt(photo) shouldBe true
    }

    @Test
    fun `an edit that lets a held asset go, then its owner's DELETE, then the reaper - none takes its objects`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo = passed(upload = upload)
        putBehindTheApi(display(upload), byteArrayOf(1, 2, 3))
        hold(REPORT)
        // What `AttachMedia` does when an entry's edit replaces its photo: detached, the entry remembered.
        jdbc.update("UPDATE media_assets SET attached_entry_id = NULL, detached_at = now() WHERE id = ?", photo)

        lifecycle.reap(clock.instant().plus(Duration.ofDays(2)), BUDGET).changed shouldBe 0
        ownMedia.delete(ada, photo)
        purge()

        status(photo).substringBefore('|') shouldBe "DELETED"
        stored(display(upload)) shouldBe true
        purgedAt(photo) shouldBe false
    }

    @Test
    fun `the reaper takes an unattached held asset only once no report holds it`() {
        val photo = passed()
        hold(REPORT)
        jdbc.update(
            "UPDATE media_assets SET attached_entry_id = NULL, detached_at = ? WHERE id = ?",
            java.sql.Timestamp.from(clock.instant()),
            photo,
        )

        lifecycle.reap(clock.instant().plus(Duration.ofDays(2)), BUDGET).changed shouldBe 0
        release(REPORT)
        lifecycle.reap(clock.instant().plus(Duration.ofDays(2)), BUDGET).changed shouldBe 1
    }

    /** A demotion published before the report keeps the full variant once the report holds the photo. */
    @Test
    fun `a demotion delivered while a report holds the photo leaves its full variant`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo = passed(upload = upload)
        putBehindTheApi(full(upload), byteArrayOf(1, 2, 3))
        jdbc.update("UPDATE media_assets SET tier = 2 WHERE id = ?", photo)
        jdbc.update(
            "INSERT INTO outbox_consumers (consumer_id, registered_at) VALUES ('media.purge', now()) ON CONFLICT DO NOTHING",
        )
        hold(REPORT)
        jdbc.update(
            "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, occurred_at) " +
                "VALUES (uuidv7(), 'Media', ?, 'MediaDemoted', jsonb_build_object('mediaId', ?::text), now())",
            photo,
            photo.toString(),
        )
        jdbc.update(
            "INSERT INTO outbox_deliveries (event_id, consumer_id, next_attempt_at) " +
                "SELECT id, 'media.purge', now() FROM outbox_events WHERE aggregate_id = ? AND event_type = 'MediaDemoted'",
            photo,
        )

        purge()

        stored(full(upload)) shouldBe true
    }

    /** Review of #118, P1: an upheld delete of an asset its author deleted meanwhile lets the waiting purge run. */
    @Test
    fun `an upheld delete of an asset its author erased meanwhile lets the purge run`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo = passed(upload = upload)
        putBehindTheApi(display(upload), byteArrayOf(1, 2, 3))
        hold(REPORT)
        transactions.execute { erasure.releaseAllOf(entry) }
        purge()
        stored(display(upload)) shouldBe true

        takedown(REPORT, "DELETE") shouldContainExactly listOf("$photo|DELETED")
        purge()

        stored(display(upload)) shouldBe false
        purgedAt(photo) shouldBe true
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
        upload: String = "in/${UUID.randomUUID()}",
    ): UUID {
        val id = rows.insert(owner = ada, createdAt = clock.instant(), attachedTo = attachedTo, upload = upload)
        jdbc.update(
            "UPDATE media_assets SET sensitive = ?, " +
                "moderation_scores = jsonb_build_object('reason', 'NONE', 'moderator', 'fake') WHERE id = ?",
            sensitive,
            id,
        )
        return id
    }

    private fun hold(report: UUID): Int = transactions.execute { reports.holdFor(entry, report) }!!

    private fun purge() {
        dispatcher.dispatchDue(Instant.now().plus(Duration.ofDays(2)), BUDGET)
    }

    private fun display(upload: String) = "v/${upload.removePrefix("in/")}/display.webp"

    private fun full(upload: String) = "v/${upload.removePrefix("in/")}/full.webp"

    private fun purgedAt(id: UUID): Boolean =
        jdbc.queryForObject("SELECT purged_at IS NOT NULL FROM media_assets WHERE id = ?", Boolean::class.java, id)!!

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

        const val BUDGET = 20

        /** A consumer of this test's own, subscribed to MediaReady for one test and removed after it. */
        const val READY_CONSUMER = "test.report-ready"
    }
}
