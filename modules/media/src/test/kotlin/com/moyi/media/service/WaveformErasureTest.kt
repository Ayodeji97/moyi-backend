package com.moyi.media.service

import com.moyi.common.testing.MutableClock
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import com.moyi.media.infra.database.MediaAssetStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Issue #165, ADR-0061: a voice note's waveform goes with the note. Every way an asset
 * becomes `DELETED` (the owner's delete, an entry's erasure, account deletion, the
 * reaper, the operator's delete) leaves no waveform on the row, by V57's trigger; and
 * the CHECK beside it refuses a deletion that would keep one, should the trigger go.
 * The same context as [MediaAccountDeletionTest].
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
@Import(ProcessingInterleavingTest.Doubles::class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
internal class WaveformErasureTest(
    @Autowired dataSource: DataSource,
    @Autowired private val store: MediaAssetStore,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactions: TransactionTemplate,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val ada = UUID.randomUUID()

    @BeforeEach
    fun start() {
        rows.clear()
        clock.set(MediaTestApplication.START)
    }

    @AfterEach
    fun clear() {
        rows.clear()
    }

    @Test
    fun `the owner's delete leaves no waveform`() {
        val voice = voiceWithWaveform()

        store.markDeleted(voice, now()) shouldBe true

        waveformOf(voice) shouldBe null
    }

    @Test
    fun `an entry's erasure leaves no waveform on its voice note`() {
        val entry = UUID.randomUUID()
        val voice = voiceWithWaveform(attachedTo = entry)

        store.releaseAllOf(entry, now()) shouldBe listOf(voice)

        waveformOf(voice) shouldBe null
    }

    @Test
    fun `account deletion leaves no waveform on any of the person's voice notes, and touches nobody else's`() {
        val loose = voiceWithWaveform()
        val attached = voiceWithWaveform(attachedTo = UUID.randomUUID())
        val someoneElses = voiceWithWaveform(owner = UUID.randomUUID())

        store.releaseAllOwnedBy(ada, now()).toSet() shouldBe setOf(loose, attached)

        waveformOf(loose) shouldBe null
        waveformOf(attached) shouldBe null
        waveformOf(someoneElses)?.size shouldBe 64
    }

    @Test
    fun `the reaper leaves no waveform`() {
        val voice = voiceWithWaveform(createdAt = now().minus(Duration.ofDays(3)))

        store.reap(now(), 10) shouldBe listOf(voice)

        waveformOf(voice) shouldBe null
    }

    @Test
    fun `the operator's delete of a held-back voice note leaves no waveform`() {
        val voice = voiceWithWaveform()
        jdbc.update(
            "UPDATE media_assets SET status = 'QUARANTINED', moderation_verdict = 'QUARANTINED', moderated_at = now() WHERE id = ?",
            voice,
        )

        jdbc.queryForObject("SELECT media_quarantine_delete(?, 'test-operator', 'a test')", String::class.java, voice) shouldBe "DELETED"

        waveformOf(voice) shouldBe null
    }

    /** The backstop: should the trigger be dropped, a deletion that keeps a waveform is refused rather than kept. */
    @Test
    fun `without the trigger, a deletion that would keep a waveform is refused by the CHECK`() {
        val voice = voiceWithWaveform()

        val refused =
            shouldThrow<DataIntegrityViolationException> {
                transactions.executeWithoutResult { status ->
                    status.setRollbackOnly()
                    jdbc.execute("ALTER TABLE media_assets DISABLE TRIGGER media_assets_waveform_goes_with_deletion")
                    store.markDeleted(voice, now())
                }
            }

        refused.message shouldContain "media_assets_deleted_waveform_check"
        rows.statusOf(voice) shouldBe "READY"
        waveformOf(voice)?.size shouldBe 64
    }

    @Test
    fun `only a voice note has a waveform, and only of 64 bytes`() {
        val voice = voiceWithWaveform()
        val photo = rows.insert(owner = ada, createdAt = now())

        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE media_assets SET waveform = ? WHERE id = ?", ByteArray(64), photo)
        }.message shouldContain "media_assets_waveform_check"
        shouldThrow<DataIntegrityViolationException> {
            jdbc.update("UPDATE media_assets SET waveform = ? WHERE id = ?", ByteArray(63), voice)
        }.message shouldContain "media_assets_waveform_check"
    }

    private fun voiceWithWaveform(
        owner: UUID = ada,
        attachedTo: UUID? = null,
        createdAt: Instant = now(),
    ): UUID {
        val voice = rows.insert(owner = owner, kind = "VOICE", createdAt = createdAt, attachedTo = attachedTo, withVariants = false)
        jdbc.update("UPDATE media_assets SET duration_ms = 4200, waveform = ? WHERE id = ?", ByteArray(64) { (it * 4).toByte() }, voice)
        waveformOf(voice)?.size shouldBe 64
        return voice
    }

    private fun waveformOf(id: UUID): ByteArray? =
        jdbc.queryForObject("SELECT waveform FROM media_assets WHERE id = ?", ByteArray::class.java, id)

    private fun now(): Instant = clock.instant()
}
