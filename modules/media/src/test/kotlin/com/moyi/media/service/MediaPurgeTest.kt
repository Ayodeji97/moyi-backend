package com.moyi.media.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.MutableClock
import com.moyi.media.api.AttachableKind
import com.moyi.media.api.MediaAttachments
import com.moyi.media.api.MediaErasure
import com.moyi.media.api.MediaLifecycle
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import com.moyi.media.infra.processing.TestImages
import com.moyi.media.infra.storage.UnavailableObjectStore
import com.moyi.media.web.MediaClient
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `media.purge` and the erasure that feeds it (spec D-9, §4 D5, ADR-0050), end
 * to end: a photo uploaded and processed as a phone's is, the outbox's own
 * dispatcher, MinIO. Erasure is a privacy rule, so what is on trial is that the
 * objects are really gone from the storage, that a URL handed out before is
 * dead, and that a storage that cannot be reached is waited out, never given
 * up on.
 *
 * The storage is the real one behind a switch ([ProcessingInterleavingTest.SwitchableStore]),
 * the same context as that test's.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
@Import(ProcessingInterleavingTest.Doubles::class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@Suppress("LongParameterList") // A test's collaborators, each injected by Spring.
internal class MediaPurgeTest(
    @Autowired mvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val clock: MutableClock,
    @Autowired private val storage: ProcessingInterleavingTest.SwitchableStore,
    @Autowired private val erasure: MediaErasure,
    @Autowired private val attachments: MediaAttachments,
    @Autowired private val lifecycle: MediaLifecycle,
    @Autowired private val transactions: TransactionTemplate,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val client = MediaClient(mvc, tokens)
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()
    private val ada = UUID.randomUUID()
    private val entry = UUID.randomUUID()

    @BeforeEach
    fun start() {
        rows.clear()
        clock.set(Instant.parse("2026-10-28T09:00:00Z"))
    }

    @AfterEach
    fun clear() {
        storage.deletes = true
        rows.clear()
    }

    @Test
    fun `an erased entry's photo is gone from the storage once media purge runs, and a URL handed out before stops working`() {
        val photo = readyPhoto()
        val upload = storageKeyOf(photo)
        val url = urlOf(photo)
        fetch(url) shouldBe 200
        attach(photo)

        transactions.execute { erasure.releaseAllOf(entry) } shouldBe 1

        // ADR-0048 owed this: the owner's own GET of an erased photo no longer hands out a URL.
        client.get(ada, photo).status shouldBe 404
        rows.statusOf(UUID.fromString(photo)) shouldBe "DELETED"
        rows.row(UUID.fromString(photo))["attached_entry_id"].shouldBeNull()
        // Nothing has reached the storage yet: the objects go in the purge's delivery, after the commit.
        stored(variantOf(upload, "full")) shouldBe true

        deliver()

        stored(variantOf(upload, "full")) shouldBe false
        stored(variantOf(upload, "display")) shouldBe false
        stored(upload) shouldBe false
        // A URL already handed out (a bearer credential for up to 25 hours) reaches nothing now.
        fetch(url) shouldBe 404
        rows.row(UUID.fromString(photo))["purged_at"].shouldNotBeNull()
        undelivered().shouldBeEmpty()
    }

    @Test
    fun `an erasure released twice releases nothing the second time, and only what is attached to that entry`() {
        val photo = UUID.fromString(readyPhoto())
        val elsewhere = UUID.fromString(readyPhoto())
        attach(photo.toString())
        attach(elsewhere.toString(), entryId = UUID.randomUUID())

        transactions.execute { erasure.releaseAllOf(entry) } shouldBe 1
        transactions.execute { erasure.releaseAllOf(entry) } shouldBe 0

        rows.events(photo, MediaReleases.MEDIA_RELEASED) shouldBe 1
        rows.statusOf(elsewhere) shouldBe "READY"
        rows.events(elsewhere, MediaReleases.MEDIA_RELEASED) shouldBe 0
    }

    @Test
    fun `an erasure outside a transaction is refused, so it can never commit apart from the entry's`() {
        val photo = readyPhoto()
        attach(photo)

        shouldThrow<IllegalTransactionStateException> { erasure.releaseAllOf(entry) }

        rows.statusOf(UUID.fromString(photo)) shouldBe "READY"
    }

    /**
     * The privacy rule: a storage that cannot be reached fails the purge's
     * delivery, which the outbox backs off and offers again, past its
     * fifteen-minute cap and on, with no last attempt; the objects go the
     * first time the storage answers.
     */
    @Test
    fun `a purge the storage refuses is retried, past the backoff's cap, until the storage answers`() {
        val photo = readyPhoto()
        val upload = storageKeyOf(photo)
        attach(photo)
        storage.deletes = false
        transactions.execute { erasure.releaseAllOf(entry) }

        repeat(FAILED_TRIES) { tried ->
            deliver().failed shouldBe 1
            val delivery = purgeDelivery()
            delivery["attempts"] shouldBe tried + 1
            delivery["processed_at"].shouldBeNull()
            (delivery["last_error"] as String) shouldEndWith ".ObjectStoreUnavailableException"
            // Due again: never dropped, only put off.
            clock.set((delivery["next_attempt_at"] as Timestamp).toInstant())
        }
        // Past the cap, the wait stays fifteen minutes (ADR-0035): there is no last attempt.
        stored(variantOf(upload, "full")) shouldBe true
        rows.row(UUID.fromString(photo))["purged_at"].shouldBeNull()

        storage.deletes = true
        deliver().delivered shouldBe 1

        stored(variantOf(upload, "full")) shouldBe false
        stored(variantOf(upload, "display")) shouldBe false
        stored(upload) shouldBe false
        purgeDelivery()["processed_at"].shouldNotBeNull()
    }

    @Test
    fun `an owner's own delete that could not reach the storage leaves nothing behind once the storage answers`() {
        val photo = readyPhoto()
        val upload = storageKeyOf(photo)
        storage.deletes = false

        client.delete(ada, photo).status shouldBe 204
        stored(variantOf(upload, "full")) shouldBe true
        deliver().failed shouldBe 1

        storage.deletes = true
        clock.advance(Duration.ofMinutes(1))
        deliver().delivered shouldBe 1

        stored(variantOf(upload, "full")) shouldBe false
        stored(variantOf(upload, "display")) shouldBe false
    }

    @Test
    fun `a demoted photo loses its full variant from the storage and keeps its display variant`() {
        val photo = readyPhoto()
        val upload = storageKeyOf(photo)
        attach(photo)

        clock.advance(Duration.ofDays(91))
        lifecycle.demote(clock.instant(), LIMIT).changed shouldBe 1
        deliver()

        stored(variantOf(upload, "full")) shouldBe false
        stored(variantOf(upload, "display")) shouldBe true
        // Its owner is shown what is kept. (Not fetched: the URL is signed on the test's clock, 91 days ahead
        // of MinIO's.)
        val view = json.readTree(client.get(ada, photo).contentAsString)
        view["status"].asString() shouldBe "READY"
        view["url"].asString() shouldContain "/display.webp"
    }

    @Test
    fun `an upload never completed has its object deleted once the reaper has deleted it`() {
        val upload = "in/${UUID.randomUUID()}"
        putBehindTheApi(upload, TestImages.jpeg(16, 16))
        rows.insert(status = "PENDING_UPLOAD", createdAt = clock.instant(), upload = upload)

        clock.advance(Duration.ofHours(2))
        lifecycle.reap(clock.instant(), LIMIT).changed shouldBe 1
        stored(upload) shouldBe true
        deliver()

        stored(upload) shouldBe false
    }

    @Test
    fun `where no storage is configured media purge subscribes to nothing, so it neither fails nor logs on every poll`() {
        val purge =
            PurgeMedia(
                com.moyi.media.infra.database
                    .MediaAssetStore(jdbc),
                UnavailableObjectStore(),
                Clock.systemUTC(),
            )

        purge.eventTypes.shouldBeEmpty()
    }

    private fun readyPhoto(): String {
        val mediaId = client.upload(ada, TestImages.jpeg(64, 48))
        deliver()
        rows.statusOf(UUID.fromString(mediaId)) shouldBe "READY"
        return mediaId
    }

    private fun attach(
        mediaId: String,
        entryId: UUID = entry,
    ) {
        transactions.execute { attachments.claim(ada, UUID.fromString(mediaId), AttachableKind.IMAGE, entryId) } shouldBe true
    }

    private fun urlOf(mediaId: String): URI = URI.create(json.readTree(client.get(ada, mediaId).contentAsString)["url"].asString())

    private fun fetch(url: URI): Int =
        http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun deliver() = dispatcher.dispatchDue(clock.instant(), BUDGET)

    private fun purgeDelivery(): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT attempts, processed_at, last_error, next_attempt_at FROM outbox_deliveries WHERE consumer_id = ?",
            PurgeMedia.ID,
        )

    private fun undelivered(): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT consumer_id FROM outbox_deliveries WHERE processed_at IS NULL")

    private fun storageKeyOf(mediaId: String): String =
        jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId)!!

    private fun variantOf(
        upload: String,
        label: String,
    ) = "v/${upload.removePrefix("in/")}/$label.webp"

    private companion object {
        const val BUDGET = 20
        const val LIMIT = 100

        /** Past the doubling's cap (2 s doubled nine times is already over fifteen minutes). */
        const val FAILED_TRIES = 12
    }
}
