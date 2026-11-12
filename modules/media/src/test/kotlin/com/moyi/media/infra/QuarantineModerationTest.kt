package com.moyi.media.infra

import com.moyi.common.testing.MutableClock
import com.moyi.media.api.BlockOutcome
import com.moyi.media.api.DeleteOutcome
import com.moyi.media.api.QuarantineDecision
import com.moyi.media.api.QuarantineModeration
import com.moyi.media.api.ReleaseOutcome
import com.moyi.media.api.Review
import com.moyi.media.api.ReviewRefusal
import com.moyi.media.infra.database.QuarantineModerationStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * The admin console's port over the quarantine functions (A2 spec §6, §7.4; plan Task 1):
 * each outcome of each function reached through it, the guards it adds, the copy a moderator
 * is shown, and its refusal to run outside the console's transaction. The races the guards
 * are taken under a lock for are [QuarantineModerationRaceTest]'s.
 */
@SpringBootTest(classes = [MediaTestApplication::class, QuarantineModerationTest.Storage::class])
internal class QuarantineModerationTest(
    @Autowired dataSource: DataSource,
    @Autowired private val port: QuarantineModeration,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val store: RecordingObjectStore,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val entry = UUID.randomUUID()

    @TestConfiguration
    class Storage {
        @Bean
        @Primary
        fun recordingObjectStore(): RecordingObjectStore = RecordingObjectStore()
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_report_holds, media_assets, media_moderation_log, outbox_deliveries, outbox_events")
        store.reset()
    }

    // --- the queue ---------------------------------------------------------------------------------------------------

    @Test
    fun `the queue is the function's rows in its order, and the look is logged once`() {
        val later = quarantined(after = Duration.ofMinutes(2))
        val first = quarantined(after = Duration.ZERO)
        val blocked = blocked(after = Duration.ofMinutes(1))
        passed()

        val queue = tx { port.queue(ACTOR) }

        queue.map { it.mediaId } shouldContainExactly listOf(first, blocked, later)
        queue.map { it.verdict } shouldContainExactly listOf("QUARANTINED", "BLOCKED", "QUARANTINED")
        queue.first().reason shouldBe "EXTREME_VIOLENCE"
        queue.first().reports shouldBe 0
        logged("LISTED") shouldContainExactly listOf(ACTOR)
    }

    @Test
    fun `the open count is the list's predicate and writes nothing`() {
        quarantined()
        blocked()
        passed()
        deletedEvidence()

        tx { port.openCount() } shouldBe 3
        jdbc.queryForObject("SELECT count(*) FROM media_moderation_log", Int::class.java) shouldBe 0
    }

    @Test
    fun `an asset, its history and the reports holding it`() {
        val photo = quarantined(attachedTo = entry)
        hold(REPORT)

        val asset = tx { port.asset(photo) }!!
        asset.status shouldBe "QUARANTINED"
        asset.heldByReport shouldBe true
        asset.reportDriven shouldBe true
        asset.purged shouldBe false
        asset.toString() shouldBe "QuarantinedAsset(mediaId=$photo)"
        tx { port.asset(UUID.randomUUID()) } shouldBe null
        tx { port.reportsHolding(photo) } shouldContainExactly listOf(REPORT)
        tx { port.reportsHolding(UUID.randomUUID()) }.shouldBeEmpty()
        tx { port.release(photo, ACTOR, NOTE) }.outcome shouldBe ReleaseOutcome.REPORT_HELD
        val history = tx { port.history(photo) }
        history.map { "${it.action}|${it.actor}|${it.note}" } shouldContainExactly listOf("RELEASE_REFUSED|$ACTOR|$NOTE")
        history.single().toString() shouldNotContain NOTE
    }

    @Test
    fun `the console's methods refuse to run outside a transaction, and the export's read runs without one`() {
        val id = quarantined()
        val calls: List<() -> Any?> =
            listOf(
                { port.queue(ACTOR) },
                { port.openCount() },
                { port.asset(id) },
                { port.history(id) },
                { port.reportsHolding(id) },
                { port.release(id, ACTOR, NOTE) },
                { port.block(id, ACTOR, NOTE) },
                { port.delete(id, ACTOR, NOTE) },
                { port.reviewCopy(id, emptySet()) },
            )
        calls.forEach { call -> assertThrows<IllegalTransactionStateException> { call() } }
        jdbc.queryForObject("SELECT count(*) FROM media_moderation_log", Int::class.java) shouldBe 0
        status(id) shouldBe "QUARANTINED|QUARANTINED"

        val owner = UUID.randomUUID()
        val kept = rows.insert(owner = owner, createdAt = clock.instant())
        val gone = rows.insert(owner = owner, status = "DELETED", createdAt = clock.instant())
        port.ownedBy(owner) shouldContainExactlyInAnyOrder listOf(kept, gone)
        port.ownedBy(UUID.randomUUID()).shouldBeEmpty()
    }

    // --- release -----------------------------------------------------------------------------------------------------

    @Test
    fun `release answers each of the function's outcomes`() {
        val moderated = quarantined()
        decide { port.release(moderated, ACTOR, NOTE) } shouldBe QuarantineDecision(ReleaseOutcome.RELEASED, false)
        status(moderated) shouldBe "READY|PASSED"

        // An operator's hold of an asset moderation never saw goes back to moderation.
        val unseen = rows.insert(createdAt = clock.instant(), verdict = null)
        jdbc.queryForObject("SELECT media_quarantine_hold(?, 'op', 'email report')", String::class.java, unseen)
        decide { port.release(unseen, ACTOR, NOTE) } shouldBe QuarantineDecision(ReleaseOutcome.REMODERATED, true)

        decide { port.release(blocked(), ACTOR, NOTE) } shouldBe QuarantineDecision(ReleaseOutcome.NEVER_RELEASED, false)

        val held = quarantined(attachedTo = entry)
        hold(REPORT)
        decide { port.release(held, ACTOR, NOTE) } shouldBe QuarantineDecision(ReleaseOutcome.REPORT_HELD, true)
        status(held) shouldBe "QUARANTINED|QUARANTINED"

        decide { port.release(passed(), ACTOR, NOTE) } shouldBe QuarantineDecision(ReleaseOutcome.NOT_QUARANTINED, false)
        decide { port.release(UUID.randomUUID(), ACTOR, NOTE) }.outcome shouldBe ReleaseOutcome.NOT_QUARANTINED

        // `media_give_back`'s refusal, which release returns through: a row whose hold names no report reason.
        val odd = quarantined()
        jdbc.update(
            "UPDATE media_assets SET moderation_scores = '{\"reason\":\"EXTREME_VIOLENCE\",\"heldFrom\":{\"verdict\":null}}' " +
                "WHERE id = ?",
            odd,
        )
        decide { port.release(odd, ACTOR, NOTE) }.outcome shouldBe ReleaseOutcome.NOT_HELD_BACK

        logged("RELEASED") shouldContainExactly listOf(ACTOR, ACTOR)
    }

    // --- block -------------------------------------------------------------------------------------------------------

    @Test
    fun `block holds a quarantined asset as evidence, and says what was already blocked or gone`() {
        val photo = quarantined()
        decide { port.block(photo, ACTOR, NOTE) } shouldBe QuarantineDecision(BlockOutcome.BLOCKED, false)
        status(photo) shouldBe "QUARANTINED|BLOCKED"
        evidenceHeld(photo) shouldBe true

        decide { port.block(photo, ACTOR, NOTE) }.outcome shouldBe BlockOutcome.ALREADY_BLOCKED
        decide { port.block(purged(), ACTOR, NOTE) }.outcome shouldBe BlockOutcome.GONE
        decide { port.block(UUID.randomUUID(), ACTOR, NOTE) }.outcome shouldBe BlockOutcome.GONE
        logged("BLOCKED") shouldContainExactly listOf(ACTOR)
    }

    @Test
    fun `block refuses an asset an open report holds, without calling the function`() {
        val photo = quarantined(attachedTo = entry)
        hold(REPORT)

        decide { port.block(photo, ACTOR, NOTE) } shouldBe QuarantineDecision(BlockOutcome.REPORT_HELD, true)

        status(photo) shouldBe "QUARANTINED|QUARANTINED"
        evidenceHeld(photo) shouldBe false
        logged("BLOCKED").shouldBeEmpty()
    }

    @Test
    fun `block after a console delete answers gone, without calling the function, before the purge runs`() {
        val photo = quarantined()
        decide { port.delete(photo, ACTOR, NOTE) }.outcome shouldBe DeleteOutcome.DELETED
        status(photo) shouldBe "DELETED|QUARANTINED"

        // Spec §9.3: the removal may already be in an export; a block now would make it a tip-off.
        // V36's function would accept this row (DELETED, purged_at still null); the port must not.
        decide { port.block(photo, ACTOR, NOTE) } shouldBe QuarantineDecision(BlockOutcome.GONE, false)

        status(photo) shouldBe "DELETED|QUARANTINED"
        evidenceHeld(photo) shouldBe false
        logged("BLOCKED").shouldBeEmpty()
    }

    // --- delete ------------------------------------------------------------------------------------------------------

    @Test
    fun `delete takes a quarantined asset and hands it to the purge`() {
        val photo = quarantined()

        decide { port.delete(photo, ACTOR, NOTE) } shouldBe QuarantineDecision(DeleteOutcome.DELETED, false)

        status(photo) shouldBe "DELETED|QUARANTINED"
        rows.events(photo, "MediaReleased") shouldBe 1
        logged("DELETED") shouldContainExactly listOf(ACTOR)
    }

    @Test
    fun `delete answers not quarantined for what is not held back`() {
        decide { port.delete(passed(), ACTOR, NOTE) }.outcome shouldBe DeleteOutcome.NOT_QUARANTINED
        decide { port.delete(UUID.randomUUID(), ACTOR, NOTE) }.outcome shouldBe DeleteOutcome.NOT_QUARANTINED
    }

    @Test
    fun `delete never takes blocked evidence, held back or already deleted to its owner`() {
        val held = blocked()
        val deleted = deletedEvidence()

        decide { port.delete(held, ACTOR, NOTE) }.outcome shouldBe DeleteOutcome.EVIDENCE_SERVER_ONLY
        decide { port.delete(deleted, ACTOR, NOTE) }.outcome shouldBe DeleteOutcome.EVIDENCE_SERVER_ONLY

        status(held) shouldBe "QUARANTINED|BLOCKED"
        evidenceHeld(held) shouldBe true
        evidenceHeld(deleted) shouldBe true
        logged("DELETED").shouldBeEmpty()
        rows.events("MediaReleased") shouldBe 0
    }

    @Test
    fun `delete refuses an asset an open report holds, without calling the function`() {
        val photo = quarantined(attachedTo = entry)
        hold(REPORT)

        decide { port.delete(photo, ACTOR, NOTE) } shouldBe QuarantineDecision(DeleteOutcome.REPORT_HELD, true)

        status(photo) shouldBe "QUARANTINED|QUARANTINED"
        logged("DELETED").shouldBeEmpty()
    }

    // --- the review copy ---------------------------------------------------------------------------------------------

    @Test
    fun `a photo is shown as its display variant, as webp, whatever the upload declared`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo = quarantined(upload = upload)
        jdbc.update("UPDATE media_assets SET content_type = 'image/heic' WHERE id = ?", photo)
        store.put(variant(upload, "display.webp"), DISPLAY_BYTES)

        val allowed = tx { port.reviewCopy(photo, emptySet()) }.shouldBeInstanceOf<Review.Allowed>()

        allowed.reportDriven shouldBe false
        allowed.copy.contentType shouldBe QuarantineModerationStore.WEBP
        allowed.copy.use { it.content.readBytes() } shouldBe DISPLAY_BYTES
        store.opened shouldContainExactly listOf(variant(upload, "display.webp"))
        allowed.copy.toString() shouldNotContain upload.removePrefix("in/")
    }

    @Test
    fun `a voice note is played as its AAC copy when it has one, else its Opus`() {
        val withAac = "in/${UUID.randomUUID()}"
        val aac = quarantined(kind = "VOICE", upload = withAac)
        jdbc.update("UPDATE media_assets SET aac_key = ? WHERE id = ?", variant(withAac, "voice.m4a"), aac)
        store.put(variant(withAac, "voice.m4a"), AAC_BYTES)
        store.put(variant(withAac, "voice.ogg"), OPUS_BYTES)
        val opusOnly = "in/${UUID.randomUUID()}"
        val opus = quarantined(kind = "VOICE", upload = opusOnly)
        store.put(variant(opusOnly, "voice.ogg"), OPUS_BYTES)

        tx { port.reviewCopy(aac, emptySet()) }.shouldBeInstanceOf<Review.Allowed>().copy.use {
            it.contentType shouldBe "audio/mp4"
            it.content.readBytes() shouldBe AAC_BYTES
        }
        tx { port.reviewCopy(opus, emptySet()) }.shouldBeInstanceOf<Review.Allowed>().copy.use {
            it.contentType shouldBe "audio/ogg"
            it.content.readBytes() shouldBe OPUS_BYTES
        }
    }

    @Test
    fun `a held asset whose variant is not in the store is refused as missing`() {
        tx { port.reviewCopy(quarantined(), emptySet()) } shouldBe Review.Refused(ReviewRefusal.MISSING)
    }

    @Test
    fun `a storage that cannot be reached refuses the look as unavailable instead of throwing`() {
        val id = quarantined()
        store.unavailable = true
        tx { port.reviewCopy(id, emptySet()) } shouldBe Review.Refused(ReviewRefusal.UNAVAILABLE)
        store.opened.size shouldBe 1
    }

    @Test
    fun `blocked, deleted evidence, unheld, purged and unknown assets are refused without touching the store`() {
        val reported = purged(attachedTo = entry)
        jdbc.update("INSERT INTO media_report_holds (media_id, report_id) VALUES (?, ?)", reported, REPORT)

        tx { port.reviewCopy(blocked(), emptySet()) } shouldBe Review.Refused(ReviewRefusal.BLOCKED)
        tx { port.reviewCopy(deletedEvidence(), emptySet()) } shouldBe Review.Refused(ReviewRefusal.BLOCKED)
        tx { port.reviewCopy(passed(), emptySet()) } shouldBe Review.Refused(ReviewRefusal.NOT_HELD)
        tx { port.reviewCopy(reported, setOf(REPORT)) } shouldBe Review.Refused(ReviewRefusal.PURGED)
        tx { port.reviewCopy(UUID.randomUUID(), emptySet()) } shouldBe Review.Refused(ReviewRefusal.NOT_HELD)

        store.opened.shouldBeEmpty()
    }

    @Test
    fun `a deleted asset a report still holds is shown, report-driven, while its objects exist`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo = quarantined(attachedTo = entry, upload = upload)
        hold(REPORT)
        jdbc.update("UPDATE media_assets SET status = 'DELETED', deleted_at = now(), attached_entry_id = NULL WHERE id = ?", photo)
        store.put(variant(upload, "display.webp"), DISPLAY_BYTES)

        val allowed = tx { port.reviewCopy(photo, setOf(REPORT)) }.shouldBeInstanceOf<Review.Allowed>()

        allowed.reportDriven shouldBe true
        allowed.copy.close()
    }

    @Test
    fun `a report filed between the unlocked read and the locked one refuses the look as changed`() {
        val photo = quarantined(attachedTo = entry)
        store.put(variant(uploadOf(photo), "display.webp"), DISPLAY_BYTES)

        val decided =
            tx {
                val seen = port.reportsHolding(photo).toSet()
                hold(REPORT) // filed after the unlocked read; the locked re-read sees it
                port.reviewCopy(photo, seen)
            }

        decided shouldBe Review.Refused(ReviewRefusal.CHANGED)
        store.opened.shouldBeEmpty()
        // And a report decided in between: the set shrinks, also changed.
        tx { port.reviewCopy(photo, setOf(REPORT, OTHER_REPORT)) } shouldBe Review.Refused(ReviewRefusal.CHANGED)
    }

    // --- the names ---------------------------------------------------------------------------------------------------

    @Test
    fun `an answer the enum does not know fails loudly, naming the function and not the asset`() {
        val asset = UUID.randomUUID()
        val failure =
            assertThrows<IllegalStateException> {
                QuarantineModerationStore.outcomeOf<DeleteOutcome>("media_quarantine_delete", "SOMETHING_NEW")
            }
        failure.message!! shouldContain "media_quarantine_delete"
        failure.message!! shouldContain "SOMETHING_NEW"
        failure.message!! shouldNotContain asset.toString()
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST") // execute hands back the block's own value, null included
    private fun <T> tx(block: () -> T): T = transactions.execute { block() } as T

    private fun <O : Enum<O>> decide(block: () -> QuarantineDecision<O>): QuarantineDecision<O> = tx(block)

    private fun quarantined(
        after: Duration = Duration.ZERO,
        attachedTo: UUID? = null,
        kind: String = "IMAGE",
        upload: String = "in/${UUID.randomUUID()}",
    ): UUID {
        val id =
            rows.insert(
                status = "QUARANTINED",
                kind = kind,
                createdAt = clock.instant().plus(after),
                attachedTo = attachedTo,
                upload = upload,
                verdict = "QUARANTINED",
            )
        jdbc.update("UPDATE media_assets SET moderation_scores = '{\"reason\":\"EXTREME_VIOLENCE\"}' WHERE id = ?", id)
        return id
    }

    private fun blocked(after: Duration = Duration.ZERO): UUID =
        rows.insert(status = "QUARANTINED", createdAt = clock.instant().plus(after), verdict = "BLOCKED")

    private fun passed(): UUID = rows.insert(createdAt = clock.instant())

    /** Deleted to its owner, its objects kept as evidence. */
    private fun deletedEvidence(): UUID = rows.insert(status = "DELETED", createdAt = clock.instant(), verdict = "BLOCKED")

    private fun purged(attachedTo: UUID? = null): UUID {
        val id = rows.insert(status = "DELETED", createdAt = clock.instant(), verdict = "PASSED")
        jdbc.update("UPDATE media_assets SET purged_at = now(), last_entry_id = ? WHERE id = ?", attachedTo, id)
        return id
    }

    /** A member's report of [entry], as the report route files it, committed on a connection of its own. */
    private fun hold(report: UUID) {
        jdbc.queryForObject("SELECT media_report_hold(?, ?, 'member-report')", Int::class.java, entry, report)
    }

    private fun uploadOf(id: UUID): String =
        jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?", String::class.java, id)!!

    private fun variant(
        upload: String,
        file: String,
    ) = "v/${upload.removePrefix("in/")}/$file"

    private fun status(id: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, '') FROM media_assets WHERE id = ?",
            String::class.java,
            id,
        )!!

    private fun evidenceHeld(id: UUID): Boolean =
        jdbc.queryForObject("SELECT evidence_held FROM media_assets WHERE id = ?", Boolean::class.java, id)!!

    private fun logged(action: String): List<String> =
        jdbc
            .queryForList("SELECT actor FROM media_moderation_log WHERE action = ? ORDER BY at, id", String::class.java, action)
            .filterNotNull()

    private companion object {
        const val ACTOR = "admin:0f0f0f0f-0000-4000-8000-0000000000ad"
        const val NOTE = "looked, decided"
        val REPORT: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000fb01")
        val OTHER_REPORT: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000fb02")
        val DISPLAY_BYTES = byteArrayOf(1, 2, 3)
        val AAC_BYTES = byteArrayOf(4, 5)
        val OPUS_BYTES = byteArrayOf(6)
    }
}
