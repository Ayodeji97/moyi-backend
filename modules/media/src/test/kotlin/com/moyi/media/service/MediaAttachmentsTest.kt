package com.moyi.media.service

import com.moyi.common.core.IdGenerator
import com.moyi.media.api.AttachableKind
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.api.MediaAttachments
import com.moyi.media.api.VoiceViewStatus
import com.moyi.media.api.VoiceViews
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import com.moyi.media.web.HeldRow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `media.api` as `gratitude` will call it (spec §4 D2): FR-044a's claim, which
 * doc 12:56 makes a test category of its own because the cross-tenant suite
 * cannot see a cross-*user* leak, and how a photo is shown to its owner and to
 * anybody else (FR-101).
 */
@SpringBootTest(classes = [MediaTestApplication::class])
internal class MediaAttachmentsTest(
    @Autowired private val attachments: MediaAttachments,
    @Autowired private val images: ImageViews,
    @Autowired private val voices: VoiceViews,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val ids: IdGenerator,
    @Autowired private val dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val entry = UUID.randomUUID()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_assets")
    }

    @Test
    fun `an owner's ready photo, attached to nothing, is claimed for the entry`() {
        val photo = asset(ada)

        claim(ada, photo) shouldBe true

        attachedTo(photo) shouldBe entry
    }

    @Test
    fun `FR-044a - another user's photo, a v7 id, an id that names nothing, and one already attached are each refused`() {
        val beas = asset(bea)
        claim(ada, beas) shouldBe false
        attachedTo(beas).shouldBeNull()

        // Media ids are v4 (doc 06:14). A v7 id, the kind bonds and users carry, names no asset.
        val v7 = ids.timeOrdered()
        v7.version() shouldBe 7
        claim(ada, v7) shouldBe false
        claim(ada, UUID.randomUUID()) shouldBe false

        val attached = asset(ada)
        val other = UUID.randomUUID()
        claim(ada, attached, entryId = other) shouldBe true
        claim(ada, attached) shouldBe false
        attachedTo(attached) shouldBe other
    }

    @Test
    fun `FR-044a - a photo that is not ready, a deleted one, and a voice note are not claimed as a photo`() {
        for (status in listOf("PENDING_UPLOAD", "UPLOADED", "PROCESSING", "FAILED", "QUARANTINED")) {
            claim(ada, asset(ada, status = status)) shouldBe false
        }
        claim(ada, asset(ada, status = "DELETED")) shouldBe false
        claim(ada, asset(ada, kind = "VOICE")) shouldBe false
    }

    @Test
    fun `a claim outside a transaction is refused, so it can never commit apart from its entry`() {
        val photo = asset(ada)

        shouldThrow<IllegalTransactionStateException> { attachments.claim(ada, photo, AttachableKind.IMAGE, entry) }
        attachedTo(photo).shouldBeNull()
    }

    @Test
    fun `two entries racing for one photo have one winner`() {
        val photo = asset(ada)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results =
                HeldRow(dataSource, photo.toString()).use { held ->
                    val first = pool.submit(Callable { claim(ada, photo, entryId = UUID.randomUUID()) })
                    val second = pool.submit(Callable { claim(ada, photo, entryId = UUID.randomUUID()) })
                    awaitWaiters(2)
                    held.release()
                    listOf(first, second).map { it.get(SETTLE_SECONDS, TimeUnit.SECONDS) }
                }
            results shouldContainExactlyInAnyOrder listOf(true, false)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a release detaches only from the entry it is attached to`() {
        val photo = asset(ada)
        claim(ada, photo) shouldBe true

        transactions.executeWithoutResult { attachments.release(ada, photo, UUID.randomUUID()) }
        attachedTo(photo) shouldBe entry
        transactions.executeWithoutResult { attachments.release(bea, photo, entry) }
        attachedTo(photo) shouldBe entry
        transactions.executeWithoutResult { attachments.release(ada, photo, entry) }
        attachedTo(photo).shouldBeNull()
    }

    @Test
    fun `its owner is shown a ready photo with a signed URL, and anybody else is shown it processing`() {
        val photo = asset(ada)

        val toAda = images.shownTo(ada, listOf(photo)).getValue(photo)
        toAda.status shouldBe ImageViewStatus.READY
        (toAda.width to toAda.height) shouldBe (2048 to 1536)
        toAda.url.toString() shouldContain "X-Amz-Signature="
        toAda.toString() shouldNotContain toAda.url.toString()

        val toBea = images.shownTo(bea, listOf(photo)).getValue(photo)
        toBea.status shouldBe ImageViewStatus.PROCESSING
        toBea.url.shouldBeNull()
        toBea.width.shouldBeNull()
    }

    @Test
    fun `a photo that passed moderation is shown to anybody, and a deleted one or an unknown id to nobody`() {
        val passed = asset(ada, verdict = "PASSED")
        images.shownTo(bea, listOf(passed)).getValue(passed).status shouldBe ImageViewStatus.READY

        images.shownTo(ada, listOf(asset(ada, status = "DELETED"), UUID.randomUUID())).shouldBeEmpty()
    }

    @Test
    fun `a ready voice note is claimed as a voice note, never as a photo, and a photo never as a voice note`() {
        val voice = voiceNote(ada)
        val photo = asset(ada)

        claim(ada, voice, kind = AttachableKind.IMAGE) shouldBe false
        claim(ada, photo, kind = AttachableKind.VOICE) shouldBe false
        claim(bea, voice, kind = AttachableKind.VOICE) shouldBe false
        claim(ada, voiceNote(ada, status = "PROCESSING"), kind = AttachableKind.VOICE) shouldBe false
        attachedTo(voice).shouldBeNull()

        claim(ada, voice, kind = AttachableKind.VOICE) shouldBe true
        attachedTo(voice) shouldBe entry
        claim(ada, voice, kind = AttachableKind.VOICE, entryId = UUID.randomUUID()) shouldBe false
    }

    /**
     * FR-061, the owner's ruling: voice notes are moderated. Its owner
     * hears a ready voice note with a signed URL and its length; anybody else
     * hears it still processing, with neither.
     */
    @Test
    fun `its owner is played a ready voice note with a signed URL and its length, and anybody else is told it is processing`() {
        val voice = voiceNote(ada)

        val toAda = voices.heardBy(ada, listOf(voice)).getValue(voice)
        toAda.status shouldBe VoiceViewStatus.READY
        toAda.durationMs shouldBe 4_200
        toAda.url.toString() shouldContain "/voice.ogg?"
        toAda.url.toString() shouldContain "X-Amz-Signature="
        toAda.toString() shouldNotContain toAda.url.toString()

        val toBea = voices.heardBy(bea, listOf(voice)).getValue(voice)
        toBea.status shouldBe VoiceViewStatus.PROCESSING
        toBea.url.shouldBeNull()
        toBea.durationMs.shouldBeNull()
    }

    @Test
    fun `a voice note not yet transcoded is processing to everyone, and a deleted one or a photo is played to nobody`() {
        val waiting = voiceNote(ada, status = "PROCESSING")
        val heard = voices.heardBy(ada, listOf(waiting)).getValue(waiting)
        heard.status shouldBe VoiceViewStatus.PROCESSING
        heard.url.shouldBeNull()
        heard.durationMs.shouldBeNull()

        voices.heardBy(ada, listOf(voiceNote(ada, status = "DELETED"), UUID.randomUUID())).shouldBeEmpty()
        val photo = asset(ada)
        voices.heardBy(ada, listOf(photo)).getValue(photo).status shouldBe VoiceViewStatus.PROCESSING
    }

    private fun claim(
        owner: UUID,
        mediaId: UUID,
        entryId: UUID = entry,
        kind: AttachableKind = AttachableKind.IMAGE,
    ): Boolean = transactions.execute { attachments.claim(owner, mediaId, kind, entryId) }!!

    /** A voice note as `media.process` leaves one, written directly: its Opus is at the key derived from its upload's. */
    private fun voiceNote(
        owner: UUID,
        status: String = "READY",
    ): UUID {
        val id = ids.opaque()
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, duration_ms,
                                      created_at, deleted_at)
            VALUES (?, ?, 'VOICE', ?, 'audio/mp4', 1000, 'Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=', ?, ?, now(),
                    CASE WHEN ? = 'DELETED' THEN now() END)
            """.trimIndent(),
            id,
            owner,
            "in/${UUID.randomUUID()}",
            status,
            4_200.takeIf { status == "READY" },
            status,
        )
        return id
    }

    private fun attachedTo(mediaId: UUID): UUID? =
        jdbc.queryForObject("SELECT attached_entry_id FROM media_assets WHERE id = ?", UUID::class.java, mediaId)

    /** A processed asset, as `media.process` leaves one, written directly. */
    private fun asset(
        owner: UUID,
        status: String = "READY",
        kind: String = "IMAGE",
        verdict: String? = null,
    ): UUID {
        val id = ids.opaque()
        val random = UUID.randomUUID()
        val variants =
            """[{"variant":"FULL","key":"v/$random/full.webp","width":2048,"height":1536,"bytes":180000},""" +
                """{"variant":"DISPLAY","key":"v/$random/display.webp","width":1280,"height":960,"bytes":75000}]"""
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, variants,
                                      moderation_verdict, moderated_at, created_at, deleted_at)
            VALUES (?, ?, ?, ?, 'image/jpeg', 1000, 'Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=', ?, ?::jsonb, ?, CASE WHEN ?::text IS NOT NULL THEN now() END, now(),
                    CASE WHEN ? = 'DELETED' THEN now() END)
            """.trimIndent(),
            id,
            owner,
            kind,
            "in/$random",
            status,
            variants,
            verdict,
            verdict,
            status,
        )
        return id
    }

    private fun awaitWaiters(count: Int) {
        val deadline = Instant.now().plus(Duration.ofSeconds(15))
        while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Int::class.java)!! < count) {
            check(Instant.now().isBefore(deadline)) { "expected $count lock waiters on the held row" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val SETTLE_SECONDS = 20L
    }
}
