package com.moyi.media.service

import com.moyi.media.api.ExportStorage
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `media`'s share of a data export (ADR-0059 decision 6): the copy a person may have of a photo or
 * voice note, by the rule the app shows it by, and the archive's own object, kept, signed and
 * deleted under an archive's key and no other.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
@Import(ProcessingInterleavingTest.Doubles::class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
internal class ExportStorageTest(
    @Autowired private val exports: ExportStorage,
    @Autowired dataSource: DataSource,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()

    @AfterEach
    fun clear() {
        rows.clear()
    }

    @Test
    fun `a passed photo is the full variant to its owner and to the partner, read from the storage`() {
        val upload = "in/${UUID.randomUUID()}"
        val photo = rows.insert(owner = ada, createdAt = MediaTestApplication.START, upload = upload)
        putBehindTheApi("v/${upload.removePrefix("in/")}/full.webp", FULL)

        val adas = exports.copyOf(ada, photo).shouldNotBeNull()
        val beas = exports.copyOf(bea, photo).shouldNotBeNull()

        adas.extension shouldBe "webp"
        adas.open()!!.use { it.readAllBytes() } shouldBe FULL
        beas.open()!!.use { it.readAllBytes() } shouldBe FULL
    }

    @Test
    fun `a photo held back is its owner's alone, and a deleted one is nobody's`() {
        val held =
            rows.insert(
                owner = ada,
                createdAt = MediaTestApplication.START,
                status = "QUARANTINED",
                verdict = "QUARANTINED",
                withVariants = true,
            )
        val deleted = rows.insert(owner = ada, createdAt = MediaTestApplication.START, status = "DELETED", withVariants = true)
        val unmoderated = rows.insert(owner = ada, createdAt = MediaTestApplication.START, verdict = null)

        exports.copyOf(ada, held).shouldNotBeNull()
        exports.copyOf(bea, held).shouldBeNull()
        exports.copyOf(ada, deleted).shouldBeNull()
        exports.copyOf(bea, unmoderated).shouldBeNull()
        exports.copyOf(ada, unmoderated).shouldNotBeNull()
        exports.copyOf(ada, UUID.randomUUID()).shouldBeNull()
    }

    /**
     * #149 lets a photo or voice note moderation blocked before its entry was sent be attached, so an
     * author's archive can now meet one on their own entry by a new road. It carries no file of
     * either, to its author or anyone (ADR-0051 Q16), though the objects are still in the storage,
     * held as evidence; the archive's path for each is then null. A held-back voice note on the same
     * entry is its author's, so the null is the block's and not a missing object's.
     */
    @Test
    fun `a blocked photo and voice note attached to the author's entry are in nobody's archive`() {
        val entry = UUID.randomUUID()
        val photoUpload = "in/${UUID.randomUUID()}"
        val photo =
            held(rows.insert(owner = ada, createdAt = MediaTestApplication.START, upload = photoUpload, attachedTo = entry), "BLOCKED")
        putBehindTheApi("v/${photoUpload.removePrefix("in/")}/full.webp", FULL)
        putBehindTheApi("v/${photoUpload.removePrefix("in/")}/display.webp", FULL)
        val voiceUpload = "in/${UUID.randomUUID()}"
        val voice = held(voiceNote(voiceUpload, attachedTo = entry), "BLOCKED")
        putBehindTheApi("v/${voiceUpload.removePrefix("in/")}/voice.ogg", VOICE)
        val heldUpload = "in/${UUID.randomUUID()}"
        val heldVoice = held(voiceNote(heldUpload, attachedTo = entry), "QUARANTINED")
        putBehindTheApi("v/${heldUpload.removePrefix("in/")}/voice.ogg", VOICE)

        for (viewer in listOf(ada, bea)) {
            exports.copyOf(viewer, photo).shouldBeNull()
            exports.copyOf(viewer, voice).shouldBeNull()
        }
        exports
            .copyOf(ada, heldVoice)
            .shouldNotBeNull()
            .open()!!
            .use { it.readAllBytes() } shouldBe VOICE
        exports.copyOf(bea, heldVoice).shouldBeNull()
    }

    @Test
    fun `an archive is kept under a random key, downloads by its signed URL, and is gone once deleted`() {
        val file = Files.createTempFile("export", ".zip")
        Files.write(file, ARCHIVE)
        val key = exports.storeArchive(file)
        Files.delete(file)

        key shouldMatch Regex("""^x/[0-9a-f-]{36}\.zip$""")
        val url = exports.archiveUrl(key, MediaTestApplication.START, Duration.ofHours(24))
        url.toString() shouldNotContain "export"
        fetch(url) shouldBe (200 to ARCHIVE.toList())

        exports.deleteArchive(key)

        stored(key) shouldBe false
        fetch(url).first shouldBe 404
    }

    @Test
    fun `nothing but an archive's key is signed or deleted through it`() {
        val upload = "in/${UUID.randomUUID()}"
        rows.insert(owner = ada, createdAt = MediaTestApplication.START, upload = upload)
        val display = "v/${upload.removePrefix("in/")}/display.webp"
        putBehindTheApi(display, FULL)

        shouldThrow<IllegalArgumentException> { exports.deleteArchive(display) }
        shouldThrow<IllegalArgumentException> { exports.archiveUrl(upload, MediaTestApplication.START, Duration.ofHours(1)) }
        shouldThrow<IllegalArgumentException> { exports.deleteArchive("x/../$display") }
        stored(display) shouldBe true
    }

    /** A transcoded voice note of Ada's, as `media.process` leaves one, on [attachedTo]. */
    private fun voiceNote(
        upload: String,
        attachedTo: UUID,
    ): UUID {
        val id = rows.insert(owner = ada, kind = "VOICE", createdAt = MediaTestApplication.START, upload = upload, attachedTo = attachedTo)
        jdbc.update("UPDATE media_assets SET duration_ms = 4200, content_type = 'audio/mp4' WHERE id = ?", id)
        return id
    }

    /** Moderation's verdict on a READY asset: held back, and BLOCKED held as evidence by V36's trigger. */
    private fun held(
        id: UUID,
        verdict: String,
    ): UUID {
        jdbc.update(
            "UPDATE media_assets SET status = 'QUARANTINED', moderation_verdict = ?, moderated_at = now() WHERE id = ?",
            verdict,
            id,
        ) shouldBe 1
        return id
    }

    private fun fetch(url: java.net.URI): Pair<Int, List<Byte>> {
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        return response.statusCode() to (if (response.statusCode() == 200) response.body().toList() else emptyList())
    }

    private companion object {
        val FULL = "the full variant".toByteArray()
        val ARCHIVE = "PK an archive".toByteArray()
        val VOICE = "an ogg".toByteArray()
    }
}
