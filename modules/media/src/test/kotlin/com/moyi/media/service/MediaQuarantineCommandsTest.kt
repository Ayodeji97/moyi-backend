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
import io.kotest.matchers.string.shouldNotContain
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

        out shouldContain "$violent\tIMAGE\tQUARANTINED\tQUARANTINED\tEXTREME_VIOLENCE\tf"
        out shouldContain "$blocked\tIMAGE\tQUARANTINED\tBLOCKED\tKNOWN_IMAGE\tf"
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
        status(passed) shouldBe "READY|null"
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
        moyi("list").out shouldContain "$blocked\tIMAGE\tDELETED\tBLOCKED\tKNOWN_IMAGE"

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

    /** FR-044a: only a READY asset is claimed, so what was held back before it was attached is refused with the one refusal. */
    @Test
    fun `a held-back asset is not attached to an entry`() {
        val violent = held("QUARANTINED", "EXTREME_VIOLENCE")
        val blocked = held("BLOCKED", "KNOWN_IMAGE")
        for (id in listOf(violent, blocked)) {
            transactions.execute { attachments.claim(ada, id, AttachableKind.IMAGE, UUID.randomUUID()) } shouldBe false
        }
    }

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
