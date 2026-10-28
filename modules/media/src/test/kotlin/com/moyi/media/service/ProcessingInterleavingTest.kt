package com.moyi.media.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.MutableClock
import com.moyi.media.domain.StorageKey
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import com.moyi.media.infra.processing.Encoding
import com.moyi.media.infra.processing.ImageProcessor
import com.moyi.media.infra.processing.TestImages
import com.moyi.media.infra.processing.Vipses
import com.moyi.media.infra.storage.MediaStorageProperties
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import com.moyi.media.infra.storage.R2ObjectStore
import com.moyi.media.web.MediaClient
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * What happens to a photo when something else happens while it is processed
 * (spec §4 D2). The processor and the storage here are the real ones behind a
 * gate and a switch the test holds, so each interleaving is made to happen,
 * not hoped for: the test waits for the processor to be entered before it
 * acts, never for time to pass.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
@Import(ProcessingInterleavingTest.Doubles::class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@Suppress("LongParameterList") // A test's collaborators, each injected by Spring.
internal class ProcessingInterleavingTest(
    @Autowired mvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val clock: MutableClock,
    @Autowired private val gate: GatedProcessor,
    @Autowired private val storage: SwitchableStore,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val client = MediaClient(mvc, tokens)
    private val ada = UUID.randomUUID()

    @AfterEach
    fun clear() {
        gate.reset()
        storage.puts = true
        storage.putsLeft = Int.MAX_VALUE
        jdbc.execute("TRUNCATE TABLE media_assets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    @Test
    fun `a photo deleted while it is processed is not made ready, and what was made of it is removed`() {
        val mediaId = client.upload(ada, TestImages.jpeg(64, 48))
        val upload = storageKeyOf(mediaId)
        gate.close()

        val worker = CompletableFuture.runAsync { dispatcher.dispatchDue(clock.instant(), BUDGET) }
        gate.awaitEntered()
        // The worker holds no lock on the asset while it works: the owner's delete does not wait for it.
        client.delete(ada, mediaId).status shouldBe 204
        gate.open()
        worker.get(30, TimeUnit.SECONDS)

        statusOf(mediaId) shouldBe "DELETED"
        stored(variantOf(upload, "full")) shouldBe false
        stored(variantOf(upload, "display")) shouldBe false
        stored(upload) shouldBe false
    }

    @Test
    fun `a storage that fails while a photo is processed is retried by the outbox, and the photo is made ready then`() {
        val mediaId = client.upload(ada, TestImages.jpeg(64, 48))
        storage.puts = false

        val first = dispatcher.dispatchDue(clock.instant(), BUDGET)

        first.failed shouldBe 1
        jdbc.queryForMap("SELECT status, processing_attempts FROM media_assets WHERE id = ?::uuid", mediaId) shouldBe
            mapOf("status" to "PROCESSING", "processing_attempts" to 1)

        storage.puts = true
        clock.advance(Duration.ofMinutes(1))
        dispatcher.dispatchDue(clock.instant(), BUDGET).delivered shouldBe 1

        jdbc.queryForMap("SELECT status, processing_attempts FROM media_assets WHERE id = ?::uuid", mediaId) shouldBe
            mapOf("status" to "READY", "processing_attempts" to 2)
    }

    /**
     * Review of #108: an attempt that stored the full variant and failed on the
     * display one records nothing; a deletion before the retry must still find
     * the full one, since nothing will retry a deleted photo.
     */
    @Test
    fun `a photo deleted after an attempt left one variant behind takes that variant with it`() {
        val mediaId = client.upload(ada, TestImages.jpeg(64, 48))
        val upload = storageKeyOf(mediaId)
        storage.putsLeft = 1

        dispatcher.dispatchDue(clock.instant(), BUDGET).failed shouldBe 1
        stored(variantOf(upload, "full")) shouldBe true
        jdbc.queryForObject("SELECT variants::text FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId) shouldBe "[]"

        storage.putsLeft = Int.MAX_VALUE
        client.delete(ada, mediaId).status shouldBe 204

        stored(variantOf(upload, "full")) shouldBe false
        stored(upload) shouldBe false
    }

    private fun statusOf(mediaId: String): String =
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId)!!

    private fun storageKeyOf(mediaId: String): String =
        jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?::uuid", String::class.java, mediaId)!!

    private fun variantOf(
        upload: String,
        label: String,
    ) = "v/${upload.removePrefix("in/")}/$label.webp"

    /** The real libvips, behind a gate: [close] makes the next photo wait inside the processor until [open]. */
    internal class GatedProcessor(
        private val real: ImageProcessor,
    ) : ImageProcessor {
        @Volatile private var entered = CountDownLatch(1)

        @Volatile private var release = CountDownLatch(0)

        override val processesHere: Boolean = true

        fun close() {
            release = CountDownLatch(1)
        }

        fun open() = release.countDown()

        fun awaitEntered() {
            check(entered.await(30, TimeUnit.SECONDS)) { "the processor was never entered" }
        }

        fun reset() {
            release.countDown()
            entered = CountDownLatch(1)
        }

        override fun encode(
            source: Path,
            workDir: Path,
        ): Encoding {
            entered.countDown()
            check(release.await(30, TimeUnit.SECONDS)) { "the test never opened the gate" }
            return real.encode(source, workDir)
        }
    }

    /** The real store, whose PUTs the test can make fail as an unreachable storage does. */
    internal class SwitchableStore(
        private val real: ObjectStore,
    ) : ObjectStore by real {
        @Volatile var puts = true

        /** How many more PUTs succeed before the storage is "unreachable"; unlimited unless a test sets it. */
        @Volatile var putsLeft = Int.MAX_VALUE

        override fun put(
            key: StorageKey,
            file: Path,
            contentType: String,
        ) {
            if (!puts || putsLeft-- <= 0) {
                throw ObjectStoreUnavailableException("The media storage could not store an object (switched off by the test).")
            }
            real.put(key, file, contentType)
        }
    }

    @TestConfiguration
    internal class Doubles {
        @Bean
        @Primary
        fun gatedProcessor(): GatedProcessor = GatedProcessor(Vipses.onPath())

        @Bean
        @Primary
        fun switchableStore(properties: MediaStorageProperties): SwitchableStore = SwitchableStore(R2ObjectStore.create(properties.r2))
    }

    private companion object {
        const val BUDGET = 20
    }
}
