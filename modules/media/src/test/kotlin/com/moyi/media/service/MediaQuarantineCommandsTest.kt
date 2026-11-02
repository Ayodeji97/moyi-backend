package com.moyi.media.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.media.api.AttachableKind
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.api.MediaAttachments
import com.moyi.media.api.MediaErasure
import com.moyi.media.api.MediaLifecycle
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The operator's quarantine queue (spec §4 D4, ADR-0051): `scripts/moyi media
 * quarantine list|release|delete`, run as the operator runs it, against this
 * test's own Postgres through `docker exec … psql` (the script's own way in on
 * the server), and what each command leaves behind: the row, the audit log,
 * and, for a deletion, `media.purge` deleting the objects. With it, what holds
 * child sexual abuse material back where no command can release it, and what
 * erasure and the reaper do with what moderation held back.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@Suppress("LongParameterList") // A test's collaborators, each injected by Spring.
internal class MediaQuarantineCommandsTest(
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val clock: MutableClock,
    @Autowired private val images: ImageViews,
    @Autowired private val erasure: MediaErasure,
    @Autowired private val lifecycle: MediaLifecycle,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val ownMedia: OwnMedia,
    @Autowired private val attachments: MediaAttachments,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_assets, media_moderation_log, outbox_deliveries, outbox_events")
    }

    @Test
    fun `list shows what is held back, with its verdict and reason and nothing else, and records who looked`() {
        val violent = held("QUARANTINED", "EXTREME_VIOLENCE")
        val blocked = held("BLOCKED", "KNOWN_IMAGE")
        val passed = rows.insert(owner = ada, createdAt = clock.instant())

        val out = moyi("list").also { it.code shouldBe 0 }.out
        val lines = out.lines()

        // Review of #113: the header names the function's columns in its order, verdict first (the eighth, V38: open reports).
        lines.first() shouldBe "verdict\tmediaId\tkind\tstatus\treason\tattached\tmoderatedAt\treports"
        val violentRow = lines.single { violent.toString() in it }
        violentRow shouldStartWith "QUARANTINED\t$violent\tIMAGE\tQUARANTINED\tEXTREME_VIOLENCE\tf\t"
        violentRow shouldNotContain "DO NOT VIEW"
        // A BLOCKED row is child sexual abuse material: never viewed, and it says so on its own line.
        val blockedRow = lines.single { blocked.toString() in it }
        blockedRow shouldStartWith "BLOCKED\t$blocked\tIMAGE\tQUARANTINED\tKNOWN_IMAGE\tf\t"
        blockedRow shouldEndWith "\tDO NOT VIEW — runbook §2"
        out shouldNotContain passed.toString()
        out shouldNotContain "in/" // no key
        audit(null) shouldContainExactly listOf("LISTED|op-fake-ada|")
    }

    @Test
    fun `an empty queue says so`() {
        moyi("list").out shouldContain "Nothing is held back."
    }

    @Test
    fun `release lets a quarantined photo reach the partner, and records who and why`() {
        val photo = held("QUARANTINED", "EXTREME_VIOLENCE")
        images.shownTo(bea, listOf(photo)).getValue(photo).status shouldBe ImageViewStatus.PROCESSING

        moyi("release", photo.toString(), "a cooking video still, not violence").let {
            it.code shouldBe 0
            it.out shouldContain "Released"
        }

        status(photo) shouldBe "READY|PASSED"
        images.shownTo(bea, listOf(photo)).getValue(photo).status shouldBe ImageViewStatus.READY
        audit(photo) shouldContainExactly listOf("RELEASED|op-fake-ada|a cooking video still, not violence")
    }

    /** Q16: child sexual abuse material is never released through the operator tool. */
    @Test
    fun `release refuses a blocked asset, changes nothing, and records the refusal`() {
        val blocked = held("BLOCKED", "KNOWN_IMAGE")

        moyi("release", blocked.toString(), "trying").let {
            it.code shouldBe 1
            it.err shouldContain "never released"
        }

        status(blocked) shouldBe "QUARANTINED|BLOCKED"
        images.shownTo(bea, listOf(blocked)).getValue(blocked).status shouldBe ImageViewStatus.PROCESSING
        audit(blocked) shouldContainExactly listOf("RELEASE_REFUSED|op-fake-ada|trying")
    }

    /** And no statement at all can, the application's or a person's at psql: the trigger of V36. */
    @Test
    fun `no statement can take a blocked verdict off, or make a blocked asset anything but held or deleted`() {
        val blocked = held("BLOCKED", "KNOWN_IMAGE")

        assertThrows<DataAccessException> {
            jdbc.update("UPDATE media_assets SET moderation_verdict = 'PASSED', status = 'READY' WHERE id = ?", blocked)
        }.message.shouldNotBeNull() shouldContain "never released"
        assertThrows<DataAccessException> { jdbc.update("UPDATE media_assets SET status = 'READY' WHERE id = ?", blocked) }
        assertThrows<DataAccessException> {
            jdbc.update("UPDATE media_assets SET moderation_verdict = NULL, moderated_at = NULL WHERE id = ?", blocked)
        }

        status(blocked) shouldBe "QUARANTINED|BLOCKED"
    }

    /** After the takedown runbook's report, the operator deletes it, and its objects go as every deleted asset's do (ADR-0050). */
    @Test
    fun `delete takes a held asset and every object it has out of the storage, and records the report's reference`() {
        val upload = "in/${UUID.randomUUID()}"
        val blocked = held("BLOCKED", "KNOWN_IMAGE", upload)
        val keys = listOf(upload) + listOf("full", "display").map { "v/${upload.removePrefix("in/")}/$it.webp" }
        keys.forEach { putBehindTheApi(it, byteArrayOf(1, 2, 3)) }

        moyi("delete", blocked.toString(), "reported: ref FAKE-0001").let {
            it.code shouldBe 0
            it.out shouldContain "Deleted"
        }
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, blocked) shouldBe "DELETED"
        // The function stamps the event with the database's own time, as psql on the server would; the test clock is not that.
        dispatcher.dispatchDue(Instant.now().plus(Duration.ofSeconds(5)), BUDGET)

        keys.forEach { stored(it) shouldBe false }
        jdbc.queryForObject("SELECT purged_at IS NOT NULL FROM media_assets WHERE id = ?", Boolean::class.java, blocked) shouldBe true
        audit(blocked) shouldContainExactly listOf("DELETED|op-fake-ada|reported: ref FAKE-0001")
    }

    @Test
    fun `a command refuses an id that is held back by nothing, and one that is not an id at all`() {
        val passed = rows.insert(owner = ada, createdAt = clock.instant())
        moyi("release", passed.toString(), "note").let {
            it.code shouldBe 1
            it.err shouldContain "names nothing held back"
        }
        moyi("delete", "in/not-an-id", "note").code shouldBe 1
        status(passed) shouldBe "READY|PASSED"
    }

    @Test
    fun `nothing runs without an operator's name or a note`() {
        val photo = held("QUARANTINED", "EXTREME_VIOLENCE")
        moyi("list", operator = "").let {
            it.code shouldBe 1
            it.err shouldContain "MOYI_OPERATOR"
        }
        moyi("release", photo.toString(), "").code shouldBe 1
        status(photo) shouldBe "QUARANTINED|QUARANTINED"
        audit(null).shouldBeEmpty()
    }

    /**
     * Q16 and the takedown runbook: child sexual abuse material is evidence.
     * Whoever deletes it (an erasure here; its owner and the reaper alike, below)
     * it is gone to them, and its objects stay in the storage, held, until the
     * operator has reported it and deletes it. A quarantined asset leaves with
     * its entry as any asset does (ADR-0050).
     */
    @Test
    fun `an erased entry's blocked asset is deleted to its owner and its objects held, until the operator deletes it`() {
        val entry = UUID.randomUUID()
        val upload = "in/${UUID.randomUUID()}"
        val blocked = held("BLOCKED", "KNOWN_IMAGE", upload, attachedTo = entry)
        val violent = held("QUARANTINED", "EXTREME_VIOLENCE", attachedTo = entry)
        putBehindTheApi(display(upload), byteArrayOf(1, 2, 3))

        transactions.execute { erasure.releaseAllOf(entry) } shouldBe 2
        purge()

        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, blocked) shouldBe "DELETED"
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, violent) shouldBe "DELETED"
        stored(display(upload)) shouldBe true // held
        purgedAt(blocked) shouldBe false
        purgedAt(violent) shouldBe true
        moyi("list").out shouldContain "BLOCKED\t$blocked\tIMAGE\tDELETED\tKNOWN_IMAGE"

        moyi("delete", blocked.toString(), "reported: ref FAKE-0002").code shouldBe 0
        purge()

        stored(display(upload)) shouldBe false
        purgedAt(blocked) shouldBe true
        moyi("list").out shouldNotContain blocked.toString()
    }

    @Test
    fun `its owner's DELETE of a blocked asset makes it gone to them, and leaves its objects held`() {
        val upload = "in/${UUID.randomUUID()}"
        val blocked = held("BLOCKED", "KNOWN_IMAGE", upload)
        putBehindTheApi(display(upload), byteArrayOf(1, 2, 3))

        ownMedia.delete(ada, blocked)
        purge()

        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, blocked) shouldBe "DELETED"
        stored(display(upload)) shouldBe true
        purgedAt(blocked) shouldBe false
    }

    /** ADR-0050 owed D4 this: an unattached quarantined asset goes after a day as a ready one does; a blocked one's objects stay. */
    @Test
    fun `the reaper takes an unattached held-back asset after a day, and a blocked one's objects stay`() {
        val violent = held("QUARANTINED", "EXTREME_VIOLENCE")
        val upload = "in/${UUID.randomUUID()}"
        val blocked = held("BLOCKED", "KNOWN_IMAGE", upload)
        putBehindTheApi(display(upload), byteArrayOf(1, 2, 3))

        lifecycle.reap(clock.instant().plus(Duration.ofHours(23)), BUDGET).changed shouldBe 0
        lifecycle.reap(clock.instant().plus(Duration.ofHours(25)), BUDGET).changed shouldBe 2
        purge()

        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, violent) shouldBe "DELETED"
        purgedAt(violent) shouldBe true
        stored(display(upload)) shouldBe true
        purgedAt(blocked) shouldBe false
    }

    /**
     * The runbook's own statement for a reported asset sets BLOCKED by hand. The
     * evidence must be held whatever that statement says of the hold: the
     * database sets it (review of #113), so the owner's later delete leaves the
     * objects where they are.
     */
    @Test
    fun `an asset blocked by hand, as the runbook does for a report, is held as evidence`() {
        val upload = "in/${UUID.randomUUID()}"
        val reported = rows.insert(owner = ada, createdAt = clock.instant(), upload = upload)
        jdbc.update("UPDATE media_assets SET moderation_verdict = 'PASSED', moderated_at = now() WHERE id = ?", reported)
        putBehindTheApi(display(upload), byteArrayOf(1, 2, 3))

        jdbc.update(
            "UPDATE media_assets SET status = 'QUARANTINED', moderation_verdict = 'BLOCKED', moderated_at = now() WHERE id = ?",
            reported,
        )
        jdbc.queryForObject("SELECT evidence_held FROM media_assets WHERE id = ?", Boolean::class.java, reported) shouldBe true
        ownMedia.delete(ada, reported)
        purge()

        stored(display(upload)) shouldBe true
        purgedAt(reported) shouldBe false
    }

    /** FR-044a: only a READY asset is claimed, so what was held back before it was attached is refused with the one refusal. */
    @Test
    fun `a held-back asset is not attached to an entry`() {
        val violent = held("QUARANTINED", "EXTREME_VIOLENCE")
        val blocked = held("BLOCKED", "KNOWN_IMAGE")
        for (id in listOf(violent, blocked)) {
            transactions.execute { attachments.claim(ada, id, AttachableKind.IMAGE, UUID.randomUUID()) } shouldBe false
        }
    }

    // The four ways a person at psql could undo a hold (review of #113), each refused by the database itself.

    @Test
    fun `attack 1 - clearing the evidence hold by hand is refused`() {
        val blocked = held("BLOCKED", "KNOWN_IMAGE")

        assertThrows<DataAccessException> { jdbc.update("UPDATE media_assets SET evidence_held = false WHERE id = ?", blocked) }
            .message
            .shouldNotBeNull() shouldContain "held as evidence"

        evidenceHeld(blocked) shouldBe true
    }

    @Test
    fun `attack 2 - deleting a held row is refused`() {
        val blocked = held("BLOCKED", "KNOWN_IMAGE")

        assertThrows<DataAccessException> { jdbc.update("DELETE FROM media_assets WHERE id = ?", blocked) }
            .message
            .shouldNotBeNull() shouldContain "never removed"

        evidenceHeld(blocked) shouldBe true
    }

    @Test
    fun `attack 3 - a row made or set BLOCKED without its hold, or showable, is held and held back anyway`() {
        val inserted = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, variants,
                                      created_at, ready_at, moderation_verdict, moderated_at, evidence_held)
            VALUES (?, ?, 'IMAGE', ?, 'image/jpeg', 1000, 'Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=', 'READY', '[]'::jsonb,
                    now(), now(), 'BLOCKED', now(), false)
            """.trimIndent(),
            inserted,
            ada,
            "in/${UUID.randomUUID()}",
        )
        val updated = rows.insert(owner = ada, createdAt = clock.instant())
        jdbc.update("UPDATE media_assets SET moderation_verdict = 'BLOCKED', status = 'READY', evidence_held = false WHERE id = ?", updated)

        for (id in listOf(inserted, updated)) {
            status(id) shouldBe "QUARANTINED|BLOCKED"
            evidenceHeld(id) shouldBe true
            images.shownTo(bea, listOf(id)).getValue(id).status shouldBe ImageViewStatus.PROCESSING
        }
    }

    @Test
    fun `attack 4 - a blocked asset deleted to its owner is never brought back`() {
        val blocked = held("BLOCKED", "KNOWN_IMAGE")
        ownMedia.delete(ada, blocked)

        assertThrows<DataAccessException> {
            jdbc.update("UPDATE media_assets SET status = 'QUARANTINED', deleted_at = NULL WHERE id = ?", blocked)
        }.message.shouldNotBeNull() shouldContain "never released"

        status(blocked) shouldBe "DELETED|BLOCKED"
        evidenceHeld(blocked) shouldBe true
    }

    /** The runbook's §3 for a member's report: hold it back, through a function, never a hand-written statement. */
    @Test
    fun `hold takes a reported ready photo from the partner, and only a ready one`() {
        val photo = rows.insert(owner = ada, createdAt = clock.instant())
        images.shownTo(bea, listOf(photo)).getValue(photo).status shouldBe ImageViewStatus.READY

        moyi("hold", photo.toString(), "report: a third person's photo, 2026-10-29").let {
            it.code shouldBe 0
            it.out shouldContain "Held back"
        }

        status(photo) shouldBe "QUARANTINED|QUARANTINED"
        images.shownTo(bea, listOf(photo)).getValue(photo).status shouldBe ImageViewStatus.PROCESSING
        audit(photo) shouldContainExactly listOf("QUARANTINED|op-fake-ada|report: a third person's photo, 2026-10-29")
        jdbc.queryForObject("SELECT moderation_scores ->> 'reason' FROM media_assets WHERE id = ?", String::class.java, photo) shouldBe
            "REPORTED"
        moyi("hold", photo.toString(), "again").let {
            it.code shouldBe 1
            it.err shouldContain "nothing ready to hold back"
        }
    }

    @Test
    fun `block holds reported material as evidence, ready, held back or deleted but not yet purged, and says what is gone`() {
        val ready = rows.insert(owner = ada, createdAt = clock.instant())
        val deleted = rows.insert(owner = ada, createdAt = clock.instant())
        ownMedia.delete(ada, deleted)
        val purged = rows.insert(owner = ada, createdAt = clock.instant())
        ownMedia.delete(ada, purged)
        purge()
        // `deleted` was purged by that run too; put it back to "not yet purged", as a report a moment after an erasure finds it.
        jdbc.update("UPDATE media_assets SET purged_at = NULL WHERE id = ?", deleted)

        moyi("block", ready.toString(), "report: CSAM, 2026-10-29").out shouldContain "Blocked and held as evidence"
        moyi("block", deleted.toString(), "report: CSAM, 2026-10-29").code shouldBe 0
        moyi("block", purged.toString(), "report: CSAM, 2026-10-29").let {
            it.code shouldBe 1
            it.err shouldContain "already purged"
        }

        status(ready) shouldBe "QUARANTINED|BLOCKED"
        status(deleted) shouldBe "DELETED|BLOCKED"
        evidenceHeld(ready) shouldBe true
        evidenceHeld(deleted) shouldBe true
        moyi("list").out.lines().single { ready.toString() in it } shouldEndWith "DO NOT VIEW — runbook §2"
        moyi("block", ready.toString(), "again").out shouldContain "Already blocked"
    }

    /**
     * Review of #113: demotion deletes a photo's full variant. One not yet
     * moderated keeps it, so that if it is then blocked (by moderation working
     * through a backlog, or by a report) the evidence is whole; one that passed
     * is demoted as before.
     */
    @Test
    fun `demotion takes only what has passed, so a backlog blocked later keeps its full variant`() {
        val longAgo = clock.instant().minus(Duration.ofDays(100))
        val backlog =
            rows.insert(
                owner = ada,
                createdAt = longAgo,
                attachedTo = UUID.randomUUID(),
                verdict = null,
                upload = "in/${UUID.randomUUID()}",
            )
        val passed = rows.insert(owner = ada, createdAt = longAgo, attachedTo = UUID.randomUUID())

        lifecycle.demote(clock.instant(), BUDGET).changed shouldBe 1

        rows.tierOf(backlog) shouldBe 1
        rows.tierOf(passed) shouldBe 2
    }

    /** And a photo blocked after its demotion was published keeps its full variant: the purge holds it (review of #113). */
    @Test
    fun `a demotion purged after the photo was blocked keeps its full variant`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo =
            rows.insert(
                owner = ada,
                createdAt = clock.instant().minus(Duration.ofDays(100)),
                attachedTo = UUID.randomUUID(),
                upload = upload,
            )
        val full = "v/${upload.removePrefix("in/")}/full.webp"
        putBehindTheApi(full, byteArrayOf(1, 2, 3))
        lifecycle.demote(clock.instant(), BUDGET).changed shouldBe 1

        moyi("block", photo.toString(), "report: CSAM, 2026-10-29").code shouldBe 0
        purge()

        stored(full) shouldBe true
    }

    private fun evidenceHeld(id: UUID): Boolean =
        jdbc.queryForObject("SELECT evidence_held FROM media_assets WHERE id = ?", Boolean::class.java, id)!!

    private fun purge() {
        dispatcher.dispatchDue(Instant.now().plus(Duration.ofDays(2)), BUDGET)
    }

    private fun display(upload: String) = "v/${upload.removePrefix("in/")}/display.webp"

    private fun purgedAt(id: UUID): Boolean =
        jdbc.queryForObject("SELECT purged_at IS NOT NULL FROM media_assets WHERE id = ?", Boolean::class.java, id)!!

    /** A READY photo, then held back by [verdict] as moderation would hold it. */
    private fun held(
        verdict: String,
        reason: String,
        upload: String = "in/${UUID.randomUUID()}",
        attachedTo: UUID? = null,
    ): UUID {
        val id = rows.insert(owner = ada, createdAt = clock.instant(), upload = upload, attachedTo = attachedTo)
        jdbc.update(
            "UPDATE media_assets SET status = 'QUARANTINED', moderation_verdict = ?, moderated_at = ?, " +
                "moderation_scores = jsonb_build_object('reason', ?::text), evidence_held = (? = 'BLOCKED') WHERE id = ?",
            verdict,
            java.sql.Timestamp.from(clock.instant()),
            reason,
            verdict,
            id,
        )
        return id
    }

    private fun status(id: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, 'null') FROM media_assets WHERE id = ?",
            String::class.java,
            id,
        )!!

    private fun audit(id: UUID?): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor || '|' || coalesce(note, '') FROM media_moderation_log " +
                    "WHERE media_id IS NOT DISTINCT FROM ?::uuid ORDER BY at, id",
                String::class.java,
                id?.toString(),
            ).filterNotNull()

    private data class Run(
        val code: Int,
        val out: String,
        val err: String,
    )

    /** `scripts/moyi media quarantine <args>`, as the operator types it, reaching psql in this test's own Postgres. */
    private fun moyi(
        vararg args: String,
        operator: String = "op-fake-ada",
    ): Run {
        val postgres = PostgresIntegrationTest.postgres
        val process =
            ProcessBuilder(listOf("bash", SCRIPT.absolutePath, "media", "quarantine") + args)
                .apply {
                    environment()["MOYI_OPERATOR"] = operator
                    environment()["MOYI_OPS_PSQL"] =
                        "docker exec -i ${postgres.containerId} psql -U ${postgres.username} -d ${postgres.databaseName}"
                }.start()
        process.outputStream.close()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "the script did not finish" }
        return Run(
            process.exitValue(),
            process.inputStream.readAllBytes().decodeToString(),
            process.errorStream.readAllBytes().decodeToString(),
        )
    }

    private companion object {
        const val BUDGET = 20

        /** From the module's directory, where Gradle runs its tests. */
        val SCRIPT = File("../../scripts/moyi")
    }
}
