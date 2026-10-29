package com.moyi.media.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.MutableClock
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.api.VoiceViewStatus
import com.moyi.media.api.VoiceViews
import com.moyi.media.domain.Finding
import com.moyi.media.domain.ImageFormat
import com.moyi.media.domain.ModerationCategory
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import com.moyi.media.infra.moderation.Moderated
import com.moyi.media.infra.moderation.ModeratorUnavailableException
import com.moyi.media.infra.moderation.NoImageModeration
import com.moyi.media.infra.moderation.NoKnownImageList
import com.moyi.media.infra.moderation.NoVoiceModeration
import com.moyi.media.infra.processing.ConversionUnavailableException
import com.moyi.media.infra.processing.ImageProcessor
import com.moyi.media.infra.processing.ProcessingOff
import com.moyi.media.infra.processing.TestImages
import com.moyi.media.infra.processing.TestVoices
import com.moyi.media.infra.processing.VoiceProcessor
import com.moyi.media.web.MediaClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.sql.DataSource

/**
 * `media.moderate-image` and `media.moderate-voice` end to end (spec §4 D4,
 * ADR-0051): a photo or a voice note uploaded as a phone uploads it, made by
 * the real libvips and ffmpeg, moderated by a moderator the test steers
 * ([FakeModerators]), and shown to its owner and to anyone else as each
 * verdict decides (Q10, Q16).
 */
@SpringBootTest(classes = [MediaTestApplication::class, FakeModerators::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList", "LargeClass") // A test's collaborators, each injected by Spring; one test per verdict path.
internal class ModerateMediaTest(
    @Autowired mvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val images: ImageViews,
    @Autowired private val voices: VoiceViews,
    @Autowired private val moderator: FakeImageModerator,
    @Autowired private val knownImages: FakeKnownImages,
    @Autowired private val voiceModerator: FakeVoiceModerator,
    @Autowired private val moderation: Moderation,
    @Autowired private val imageProcessor: ImageProcessor,
    @Autowired private val voiceProcessor: VoiceProcessor,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val client = MediaClient(mvc, tokens)
    private val json = JsonMapper.builder().build()
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()

    @TempDir
    lateinit var work: Path

    @AfterEach
    fun clear() {
        moderator.reset()
        knownImages.reset()
        voiceModerator.reset()
        jdbc.execute("TRUNCATE TABLE media_assets, media_moderation_log, idempotency_keys, outbox_deliveries, outbox_events")
    }

    /**
     * The test database is shared by every context in the run, and the outbox's
     * subscriptions are the database's: once this context has subscribed the two
     * moderation consumers, every `MediaReady` a later context publishes would be
     * owed to consumers no other context runs, and the tests that check nothing
     * is left undelivered would find it (as they did). This context is the only
     * one that moderates, so its subscriptions leave with it.
     */
    @AfterAll
    fun unsubscribe() {
        jdbc.update("DELETE FROM outbox_deliveries WHERE consumer_id IN (?, ?)", ModerateImages.ID, ModerateVoices.ID)
        jdbc.update("DELETE FROM outbox_subscriptions WHERE consumer_id IN (?, ?)", ModerateImages.ID, ModerateVoices.ID)
    }

    @Test
    fun `a photo with nothing found passes, and its partner is shown it, not blurred, moderated on its display variant`() {
        val photo = readyPhoto(TestImages.jpeg(2400, 1600))
        bea.sees(photo).status shouldBe ImageViewStatus.PROCESSING // ready, not yet moderated

        moderateAll()

        row(photo)["moderation_verdict"] shouldBe "PASSED"
        row(photo)["status"] shouldBe "READY"
        val seen = bea.sees(photo)
        seen.status shouldBe ImageViewStatus.READY
        seen.url.shouldNotBeNull()
        seen.sensitive shouldBe false
        // Spec §4 D4: the display variant (1280 px), as a JPEG, never the full one (2048 px) or the upload.
        moderator.seen shouldHaveSize 1
        ImageFormat.of(moderator.seen.single()) shouldBe ImageFormat.JPEG
        ImageIO.read(ByteArrayInputStream(moderator.seen.single())).width shouldBe 1280
        audit(photo) shouldBe listOf("PASSED|moderation:fake|NONE")
        scores(photo) shouldContain "\"knownImages\": \"fake-list\""
    }

    /** Q16: adult nudity between members is allowed, and blurred until tapped. */
    @Test
    fun `adult nudity passes, sensitive, so both are shown it and told to blur it`() {
        moderator.answer = { Moderated.Found(listOf(Finding(ModerationCategory.ADULT_NUDITY, 97.5))) }
        val photo = readyPhoto(TestImages.jpeg(400, 300))

        moderateAll()

        row(photo)["moderation_verdict"] shouldBe "PASSED"
        row(photo)["sensitive"] shouldBe true
        bea.sees(photo).status shouldBe ImageViewStatus.READY
        bea.sees(photo).sensitive shouldBe true
        ada.sees(photo).sensitive shouldBe true
        json.readTree(client.get(ada, photo.toString()).contentAsString)["sensitive"].asBoolean() shouldBe true
        quarantinedCount("IMAGE", "QUARANTINED") shouldBe 0.0
    }

    /** Q16 and Q10: extreme violence is held back; the author sees it "not shared", the partner "processing", never "removed". */
    @Test
    fun `extreme violence is quarantined, not shared to its author and processing to the partner, counted and audited`(
        output: CapturedOutput,
    ) {
        moderator.answer = { Moderated.Found(listOf(Finding(ModerationCategory.EXTREME_VIOLENCE, 91.0))) }
        val photo = readyPhoto(TestImages.jpeg(400, 300))
        val before = quarantinedCount("IMAGE", "QUARANTINED")

        moderateAll()

        row(photo)["status"] shouldBe "QUARANTINED"
        row(photo)["moderation_verdict"] shouldBe "QUARANTINED"
        row(photo)["evidence_held"] shouldBe false
        bea.sees(photo).status shouldBe ImageViewStatus.PROCESSING
        bea.sees(photo).url.shouldBeNull()
        val toAda = ada.sees(photo)
        toAda.status shouldBe ImageViewStatus.NOT_SHARED
        toAda.url.shouldNotBeNull() // Q10: the author still sees their own photo
        json.readTree(client.get(ada, photo.toString()).contentAsString)["status"].asString() shouldBe "QUARANTINED"
        quarantinedCount("IMAGE", "QUARANTINED") shouldBe before + 1
        audit(photo) shouldBe listOf("QUARANTINED|moderation:fake|EXTREME_VIOLENCE")
        output.out shouldContain "Media $photo moderated: QUARANTINED (EXTREME_VIOLENCE)"
        output.out shouldNotContain toAda.url.toString()
        output.out shouldNotContain storageKeyOf(photo).removePrefix("in/")
    }

    /** Q16: a known image is blocked, and the classifier is not asked; nobody, the author included, is given it. */
    @Test
    fun `a known image is blocked before the classifier is asked, and given to nobody`() {
        knownImages.answer = { true }
        val photo = readyPhoto(TestImages.jpeg(400, 300))
        val before = quarantinedCount("IMAGE", "BLOCKED")

        moderateAll()

        row(photo)["moderation_verdict"] shouldBe "BLOCKED"
        row(photo)["status"] shouldBe "QUARANTINED"
        row(photo)["evidence_held"] shouldBe true // Q16: kept for the report, whoever deletes it
        moderator.seen.shouldBeEmpty()
        bea.sees(photo).status shouldBe ImageViewStatus.PROCESSING
        val toAda = ada.sees(photo)
        toAda.status shouldBe ImageViewStatus.NOT_SHARED
        toAda.url.shouldBeNull()
        json.readTree(client.get(ada, photo.toString()).contentAsString)["url"].isNull shouldBe true
        quarantinedCount("IMAGE", "BLOCKED") shouldBe before + 1
        audit(photo) shouldBe listOf("BLOCKED|moderation:fake|KNOWN_IMAGE")
    }

    /**
     * The list is given what a vendor may match on (review of #113): the JPEG
     * a perceptual matcher reads, and the SHA-256 of the bytes the phone
     * uploaded, which an exact list matches, kept after the original is gone.
     */
    @Test
    fun `the known-image list is given the display JPEG and the upload's own SHA-256`() {
        val photo = readyPhoto(TestImages.jpeg(400, 300))

        moderateAll()

        val candidate = knownImages.seen.single()
        candidate.uploadSha256 shouldBe jdbc.queryForObject("SELECT checksum FROM media_assets WHERE id = ?", String::class.java, photo)
        ImageFormat.of(candidate.jpeg) shouldBe ImageFormat.JPEG
        candidate.toString() shouldNotContain candidate.uploadSha256
    }

    /** What gave the verdict is kept with it: the vendor's model version (review of #113). */
    @Test
    fun `the moderator's model version is kept with the verdict`() {
        moderator.answer = { Moderated.Found(emptyList(), modelVersion = "7.0") }
        val photo = readyPhoto(TestImages.jpeg(400, 300))

        moderateAll()

        scores(photo) shouldContain "\"modelVersion\": \"7.0\""
    }

    /**
     * Review of #113: a conversion that runs out of time is the machine's, not
     * the photo's. It is thrown, the delivery is retried, and the photo is never
     * held back for it.
     */
    @Test
    fun `a moderation copy that runs out of time is an outage, thrown, and gives no verdict`() {
        val photo = readyPhoto(TestImages.jpeg(400, 300))
        val slow =
            object : ImageProcessor by imageProcessor {
                override fun jpegForModeration(
                    webp: Path,
                    workDir: Path,
                ): Path = throw ConversionUnavailableException("ran out of time")
            }
        val event = ReceivedEvent(UUID.randomUUID(), "Media", photo, "MediaReady", mapOf("mediaId" to photo), clock.instant())

        assertThrows<ConversionUnavailableException> { ModerateImages(moderation, slow, moderator, knownImages).handle(event) }

        row(photo)["moderation_verdict"].shouldBeNull()
        row(photo)["status"] shouldBe "READY"
    }

    @Test
    fun `CSAM from a classifier is blocked too`() {
        moderator.answer = { Moderated.Found(listOf(Finding(ModerationCategory.CSAM, 60.0))) }
        val photo = readyPhoto(TestImages.jpeg(400, 300))

        moderateAll()

        row(photo)["moderation_verdict"] shouldBe "BLOCKED"
        audit(photo) shouldBe listOf("BLOCKED|moderation:fake|CSAM_CLASSIFIER")
    }

    @Test
    fun `a photo the moderator refuses is held back for a person, never passed and never failed`() {
        moderator.answer = { Moderated.Refused("InvalidImageFormatException") }
        val photo = readyPhoto(TestImages.jpeg(400, 300))

        moderateAll()

        row(photo)["status"] shouldBe "QUARANTINED"
        row(photo)["moderation_verdict"] shouldBe "QUARANTINED"
        audit(photo) shouldBe listOf("QUARANTINED|moderation:fake|UNMODERATABLE")
        bea.sees(photo).status shouldBe ImageViewStatus.PROCESSING
    }

    /**
     * Spec §4 D4: "vendor down → retry with backoff, partner keeps seeing
     * processing". An outage fails nothing and spends nothing: the delivery is
     * retried until the vendor answers.
     */
    @Test
    fun `a moderator outage is retried with backoff, the partner sees processing meanwhile, and nothing is failed`() {
        var calls = 0
        moderator.answer = {
            calls++
            if (calls <= 3) throw ModeratorUnavailableException("timed out") else Moderated.Found(emptyList())
        }
        val photo = readyPhoto(TestImages.jpeg(400, 300))

        repeat(3) { attempt ->
            moderateAll()
            row(photo)["moderation_verdict"].shouldBeNull()
            row(photo)["status"] shouldBe "READY"
            ada.sees(photo).status shouldBe ImageViewStatus.READY // open for the author
            bea.sees(photo).status shouldBe ImageViewStatus.PROCESSING // closed for the partner
            moderationDelivery(photo) shouldBe "${attempt + 1}|pending"
            clock.advance(Duration.ofMinutes(1)) // past the backoff
        }
        moderateAll()

        calls shouldBe 4
        row(photo)["moderation_verdict"] shouldBe "PASSED"
        bea.sees(photo).status shouldBe ImageViewStatus.READY
        moderationDelivery(photo) shouldBe "4|done"
    }

    @Test
    fun `a known-image list outage is retried as a moderator's is`() {
        var calls = 0
        knownImages.answer = {
            calls++
            if (calls == 1) throw ModeratorUnavailableException("list unreachable") else false
        }
        val photo = readyPhoto(TestImages.jpeg(400, 300))

        moderateAll()
        row(photo)["moderation_verdict"].shouldBeNull()
        clock.advance(Duration.ofMinutes(1))
        moderateAll()

        row(photo)["moderation_verdict"] shouldBe "PASSED"
    }

    /** ADR-0050 owed: a photo already demoted keeps its display variant, which is what is moderated. */
    @Test
    fun `a photo already at tier 2 is moderated on the display variant it still has`() {
        moderator.answer = { throw ModeratorUnavailableException("down") }
        val photo = readyPhoto(TestImages.jpeg(2400, 1600))
        moderateAll()
        jdbc.update(
            "UPDATE media_assets SET tier = 2, variants = jsonb_path_query_array(variants, '$[*] ? (@.variant == \"DISPLAY\")') " +
                "WHERE id = ?",
            photo,
        )
        admin.deleteObject { it.bucket(BUCKET).key("v/${storageKeyOf(photo).removePrefix("in/")}/full.webp") }
        moderator.answer = { Moderated.Found(emptyList()) }
        clock.advance(Duration.ofMinutes(1))

        moderateAll()

        row(photo)["moderation_verdict"] shouldBe "PASSED"
        bea.sees(photo).width shouldBe 1280
    }

    /**
     * No lock is held while the moderator is asked: the owner deletes the photo
     * from inside the moderator's call (a request of its own, which would wait
     * for ever on a lock this delivery held), and the verdict that comes back
     * is recorded nowhere.
     */
    @Test
    fun `a photo deleted while it is moderated is given no verdict`() {
        val photo = readyPhoto(TestImages.jpeg(400, 300))
        moderator.answer = {
            // Another thread, so another transaction: were the asset's row locked by this delivery, it would wait here.
            val deleted = CompletableFuture.supplyAsync { client.delete(ada, photo.toString()).status }
            deleted.get(30, TimeUnit.SECONDS) shouldBe 204
            Moderated.Found(listOf(Finding(ModerationCategory.EXTREME_VIOLENCE, 99.0)))
        }

        moderateAll()

        row(photo)["status"] shouldBe "DELETED"
        row(photo)["moderation_verdict"].shouldBeNull()
        audit(photo).shouldBeEmpty()
        moderationDelivery(photo) shouldBe "1|done" // nothing to record is not a failure: the delivery is done
    }

    /** The owner's ruling on FR-061 (ADR-0049 decision 7): a voice note is moderated, on its Opus, and heard per verdict. */
    @Test
    fun `a voice note that passes is played to the partner, moderated on its Opus`() {
        val voice = readyVoice()
        bea.hears(voice).status shouldBe VoiceViewStatus.PROCESSING

        moderateAll()

        row(voice)["moderation_verdict"] shouldBe "PASSED"
        bea.hears(voice).status shouldBe VoiceViewStatus.READY
        bea.hears(voice).durationMs.shouldNotBeNull()
        String(voiceModerator.seen.single().copyOf(4)) shouldBe "OggS"
        moderator.seen.shouldBeEmpty()
        audit(voice) shouldBe listOf("PASSED|moderation:fake-voice|NONE")
    }

    @Test
    fun `a voice note held back is not shared to its author and still processing to the partner, and counted`() {
        voiceModerator.answer = { Moderated.Found(listOf(Finding(ModerationCategory.EXTREME_VIOLENCE, 95.0))) }
        val voice = readyVoice()
        val before = quarantinedCount("VOICE", "QUARANTINED")

        moderateAll()

        row(voice)["status"] shouldBe "QUARANTINED"
        bea.hears(voice).status shouldBe VoiceViewStatus.PROCESSING
        bea.hears(voice).url.shouldBeNull()
        ada.hears(voice).status shouldBe VoiceViewStatus.NOT_SHARED
        ada.hears(voice).url.shouldNotBeNull()
        quarantinedCount("VOICE", "QUARANTINED") shouldBe before + 1
    }

    @Test
    fun `a voice-moderation outage is retried, and the partner hears nothing meanwhile`() {
        var calls = 0
        voiceModerator.answer = {
            calls++
            if (calls == 1) throw ModeratorUnavailableException("down") else Moderated.Found(emptyList())
        }
        val voice = readyVoice()

        moderateAll()
        row(voice)["moderation_verdict"].shouldBeNull()
        bea.hears(voice).status shouldBe VoiceViewStatus.PROCESSING
        clock.advance(Duration.ofMinutes(1))
        moderateAll()

        bea.hears(voice).status shouldBe VoiceViewStatus.READY
    }

    /**
     * Fail closed (ADR-0051 decision 1): with `none`, or where nothing is
     * processed, neither consumer subscribes, so nothing gets a verdict and no
     * partner sees anything; `none` never passes.
     */
    @Test
    fun `with no moderator, or no processing, neither consumer subscribes to anything`() {
        ModerateImages(moderation, imageProcessor, NoImageModeration(), NoKnownImageList()).eventTypes.shouldBeEmpty()
        ModerateImages(moderation, ProcessingOff(), moderator, knownImages).eventTypes.shouldBeEmpty()
        ModerateVoices(moderation, voiceProcessor, NoVoiceModeration()).eventTypes.shouldBeEmpty()
        ModerateImages(moderation, imageProcessor, moderator, knownImages).eventTypes shouldBe setOf("MediaReady")
        ModerateVoices(moderation, voiceProcessor, voiceModerator).eventTypes shouldBe setOf("MediaReady")
    }

    private fun readyPhoto(bytes: ByteArray): UUID {
        val mediaId = UUID.fromString(client.upload(ada, bytes))
        processAll() // media.process: READY, and MediaReady published
        row(mediaId)["status"] shouldBe "READY"
        return mediaId
    }

    private fun readyVoice(): UUID {
        val mediaId = UUID.fromString(client.upload(ada, TestVoices.wav(2.0), declaredType = "audio/wav", kind = "VOICE"))
        processAll()
        row(mediaId)["status"] shouldBe "READY"
        return mediaId
    }

    private fun processAll() {
        // Only media.process's deliveries are due as the upload completes; moderation's are published by it.
        dispatcher.dispatchDue(clock.instant(), 1)
    }

    private fun moderateAll() {
        dispatcher.dispatchDue(clock.instant(), BUDGET)
    }

    private fun UUID.sees(photo: UUID) = images.shownTo(this, listOf(photo)).getValue(photo)

    private fun UUID.hears(voice: UUID) = voices.heardBy(this, listOf(voice)).getValue(voice)

    private fun row(id: UUID): Map<String, Any?> =
        jdbc.queryForMap("SELECT status, moderation_verdict, sensitive, moderated_at, evidence_held FROM media_assets WHERE id = ?", id)

    private fun scores(id: UUID): String =
        jdbc.queryForObject("SELECT moderation_scores::text FROM media_assets WHERE id = ?", String::class.java, id)!!

    private fun storageKeyOf(id: UUID): String =
        jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?", String::class.java, id)!!

    private fun audit(id: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor || '|' || coalesce(note, '') FROM media_moderation_log WHERE media_id = ? ORDER BY at, id",
                String::class.java,
                id,
            ).filterNotNull()

    /** The moderation delivery of [id]'s `MediaReady`: its attempts, and whether it is done. */
    private fun moderationDelivery(id: UUID): String =
        jdbc.queryForObject(
            """
            SELECT d.attempts || '|' || CASE WHEN d.processed_at IS NULL THEN 'pending' ELSE 'done' END
            FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
            WHERE e.aggregate_id = ? AND e.event_type = 'MediaReady' AND d.consumer_id = 'media.moderate-image'
            """.trimIndent(),
            String::class.java,
            id,
        )!!

    private fun quarantinedCount(
        kind: String,
        verdict: String,
    ): Double =
        meters
            .find(Moderation.QUARANTINED)
            .tags("kind", kind, "verdict", verdict)
            .counter()
            ?.count() ?: 0.0

    private companion object {
        const val BUDGET = 20
    }
}
