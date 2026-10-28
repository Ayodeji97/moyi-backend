package com.moyi.media.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.MutableClock
import com.moyi.media.domain.ImageFormat
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import com.moyi.media.infra.database.MediaAssetStore
import com.moyi.media.infra.processing.TestImages
import com.moyi.media.infra.processing.VipsImageProcessorTest
import com.moyi.media.infra.processing.VipsImageProcessorTest.Companion.containsBytes
import com.moyi.media.web.MediaClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource

/**
 * `media.process` end to end (spec §4 D2): a photo uploaded as a phone uploads
 * it, the outbox's own dispatcher delivering `MediaUploaded`, the real libvips,
 * MinIO. Each refusal the pipeline makes of bytes it did not make is here,
 * with what it leaves behind.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList") // A test's collaborators, each injected by Spring.
internal class ProcessUploadsTest(
    @Autowired mvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val events: EventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val store: MediaAssetStore,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val client = MediaClient(mvc, tokens)
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()
    private val ada = UUID.randomUUID()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_assets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    @Test
    fun `an uploaded photo becomes ready, two WebP variants without metadata, the upload deleted and a URL for its owner`() {
        val mediaId = client.upload(ada, TestImages.jpegWithGps(width = 400, height = 300))
        val upload = storageKeyOf(mediaId)
        stored(upload) shouldBe true
        val timedBefore = readyTimings()

        processAll()

        val row = jdbc.queryForMap("SELECT status, width, height, processing_attempts FROM media_assets WHERE id = ?::uuid", mediaId)
        row["status"] shouldBe "READY"
        // Orientation 6: 400 wide by 300 high is shown 300 by 400.
        (row["width"] to row["height"]) shouldBe (300 to 400)
        row["processing_attempts"] shouldBe 1
        // The original bytes, GPS and all, are not kept (spec §4 D2: "delete in/…").
        stored(upload) shouldBe false
        stored(variantOf(upload, "full")) shouldBe true
        stored(variantOf(upload, "display")) shouldBe true

        val view = json.readTree(client.get(ada, mediaId).contentAsString)
        view["status"].asString() shouldBe "READY"
        view["width"].asInt() shouldBe 300
        view["height"].asInt() shouldBe 400
        val image =
            http.send(HttpRequest.newBuilder(URI.create(view["url"].asString())).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        image.statusCode() shouldBe 200
        ImageFormat.of(image.body()) shouldBe ImageFormat.WEBP
        VipsImageProcessorTest.webpChunks(image.body()).filter { it in VipsImageProcessorTest.METADATA_CHUNKS }.shouldBeEmpty()
        image.body().containsBytes("Exif".toByteArray()) shouldBe false

        // Doc 11:49's histogram, one more photo in it.
        readyTimings() shouldBe timedBefore + 1
    }

    private fun readyTimings(): Long =
        meters
            .find(ProcessUploads.DURATION)
            .tags("kind", "IMAGE", "outcome", "ready")
            .timer()
            ?.count() ?: 0

    @Test
    fun `a PNG declared as a JPEG fails, and neither the upload nor any variant is kept`(output: CapturedOutput) {
        val mediaId = client.upload(ada, TestImages.png(64, 48), declaredType = "image/jpeg")
        val upload = storageKeyOf(mediaId)

        processAll()

        statusOf(mediaId) shouldBe "FAILED"
        stored(upload) shouldBe false
        stored(variantOf(upload, "full")) shouldBe false
        output.out shouldContain "Media $mediaId failed: NOT_THE_DECLARED_FORMAT"
    }

    @Test
    fun `a file that is none of the four formats fails, whatever it was declared as`(output: CapturedOutput) {
        val mediaId = client.upload(ada, TestImages.gif(64, 48), declaredType = "image/png")

        processAll()

        statusOf(mediaId) shouldBe "FAILED"
        output.out shouldContain "Media $mediaId failed: NOT_AN_ALLOWED_FORMAT"
    }

    @Test
    fun `a decompression bomb fails on its header`(output: CapturedOutput) {
        val bomb = Files.readAllBytes(TestImages.pngBomb(30_000, 30_000, Files.createTempFile("bomb-", ".png")))
        val mediaId = client.upload(ada, bomb, declaredType = "image/png")

        processAll()

        statusOf(mediaId) shouldBe "FAILED"
        output.out shouldContain "Media $mediaId failed: TOO_MANY_PIXELS"
    }

    /**
     * The storage reports no digest for an object it was not sent with one (as
     * MinIO does for a PUT behind the API), so completion judges by length,
     * and bytes of the declared length that are not the declared bytes reach
     * the pipeline. It reads the digest itself. ADR-0047 left open whether R2
     * enforces the signed checksum; this is what holds if it does not.
     */
    @Test
    fun `bytes of the declared length that are not the declared bytes fail on their digest`(output: CapturedOutput) {
        val declared = TestImages.jpeg(64, 48, seed = 1)
        val other = declared.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        val intent = client.intent(ada, declared)
        val mediaId = intent["mediaId"].asString()
        putBehindTheApi(storageKeyOf(mediaId), other)
        client.complete(ada, mediaId).status shouldBe 200

        processAll()

        statusOf(mediaId) shouldBe "FAILED"
        output.out shouldContain "Media $mediaId failed: CHECKSUM_MISMATCH"
    }

    @Test
    fun `more bytes than were declared are never read past the declared length`(output: CapturedOutput) {
        val declared = TestImages.jpeg(64, 48)
        val intent = client.intent(ada, declared)
        val mediaId = intent["mediaId"].asString()
        putBehindTheApi(storageKeyOf(mediaId), declared + ByteArray(1024))
        uploadedBehindTheApi(mediaId)

        processAll()

        statusOf(mediaId) shouldBe "FAILED"
        output.out shouldContain "Media $mediaId failed: LONGER_THAN_DECLARED"
    }

    @Test
    fun `an upload deleted before it is processed is skipped, and nothing is made of it`() {
        val mediaId = client.upload(ada, TestImages.jpeg(64, 48))
        val upload = storageKeyOf(mediaId)
        client.delete(ada, mediaId).status shouldBe 204
        // As if the owner's delete could not reach the storage: the upload is still there.
        putBehindTheApi(upload, TestImages.jpeg(64, 48))

        processAll()

        jdbc.queryForMap("SELECT status, processing_attempts FROM media_assets WHERE id = ?::uuid", mediaId) shouldBe
            mapOf("status" to "DELETED", "processing_attempts" to 0)
        stored(variantOf(upload, "full")) shouldBe false
    }

    /**
     * The rule is [com.moyi.media.domain.MediaAsset.processing]'s
     * (`MediaAssetTest` holds it); this is its backstop in the statement that
     * starts an attempt, which moves only an upload or an unfinished attempt.
     */
    @Test
    fun `an attempt never starts on a deleted, ready or failed asset`() {
        for (status in listOf("DELETED", "READY", "FAILED")) {
            val mediaId = client.upload(ada, TestImages.jpeg(16, 16))
            val deletedAt = if (status == "DELETED") "now()" else "NULL"
            jdbc.update("UPDATE media_assets SET status = ?, deleted_at = $deletedAt WHERE id = ?::uuid", status, mediaId)

            store.startProcessing(UUID.fromString(mediaId)) shouldBe false
            statusOf(mediaId) shouldBe status
        }
    }

    @Test
    fun `a voice note is left as it is, for D3`() {
        val voice = "RIFF....WAVEfmt fake voice bytes".toByteArray()
        val mediaId = client.upload(ada, voice, declaredType = "audio/wav", kind = "VOICE")

        processAll()

        jdbc.queryForMap("SELECT status, processing_attempts FROM media_assets WHERE id = ?::uuid", mediaId) shouldBe
            mapOf("status" to "UPLOADED", "processing_attempts" to 0)
    }

    @Test
    fun `the same event delivered again changes nothing`() {
        val mediaId = client.upload(ada, TestImages.jpeg(64, 48))
        processAll()
        statusOf(mediaId) shouldBe "READY"

        republish(mediaId)
        processAll()

        jdbc.queryForMap("SELECT status, processing_attempts FROM media_assets WHERE id = ?::uuid", mediaId) shouldBe
            mapOf("status" to "READY", "processing_attempts" to 1)
    }

    @Test
    fun `nothing is logged of where a photo is kept or how it is reached`(output: CapturedOutput) {
        val mediaId = client.upload(ada, TestImages.jpeg(64, 48))
        val upload = storageKeyOf(mediaId)

        processAll()
        val view = json.readTree(client.get(ada, mediaId).contentAsString)

        output.out shouldNotContain upload.removePrefix("in/")
        output.out shouldNotContain view["url"].asString()
    }

    private fun processAll() {
        dispatcher.dispatchDue(clock.instant(), BUDGET)
    }

    private fun republish(mediaId: String) {
        transactions.executeWithoutResult {
            val id = UUID.fromString(mediaId)
            val references = mapOf(CompleteUpload.MEDIA_ID to id)
            events.publish(OutboxEvent(CompleteUpload.AGGREGATE, id, CompleteUpload.MEDIA_UPLOADED, references, clock.instant()))
        }
    }

    /** UPLOADED and its event, as a completion would have left it, for bytes completion would have refused. */
    private fun uploadedBehindTheApi(mediaId: String) {
        jdbc.update("UPDATE media_assets SET status = 'UPLOADED' WHERE id = ?::uuid", mediaId)
        republish(mediaId)
    }

    private fun statusOf(mediaId: String): String =
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId)!!

    private fun storageKeyOf(mediaId: String): String =
        jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId)!!

    private fun variantOf(
        upload: String,
        label: String,
    ) = "v/${upload.removePrefix("in/")}/$label.webp"

    private companion object {
        const val BUDGET = 20
    }
}
