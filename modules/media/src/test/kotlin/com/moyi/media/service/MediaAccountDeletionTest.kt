package com.moyi.media.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.MutableClock
import com.moyi.media.api.AttachableKind
import com.moyi.media.api.MediaAttachments
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import com.moyi.media.infra.processing.TestImages
import com.moyi.media.infra.processing.TestVoices
import com.moyi.media.web.MediaClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `media`'s share of an account deletion (ADR-0058 decision 9): every asset the person
 * uploaded, on an entry or not, is deleted, and its objects are gone from the storage once
 * `media.purge` has run. Nobody else's is touched. The same context as [MediaPurgeTest].
 */
@SpringBootTest(classes = [MediaTestApplication::class])
@AutoConfigureMockMvc
@Import(ProcessingInterleavingTest.Doubles::class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@Suppress("LongParameterList") // A test's collaborators, each injected by Spring.
internal class MediaAccountDeletionTest(
    @Autowired mvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val clock: MutableClock,
    @Autowired private val attachments: MediaAttachments,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val transactions: TransactionTemplate,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)
    private val client = MediaClient(mvc, tokens)
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()

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
    fun `every asset of the person goes, attached or not, objects and all, and nobody else's`() {
        val attached = readyPhoto(ada)
        transactions.execute { attachments.claim(ada, attached, AttachableKind.IMAGE, UUID.randomUUID()) } shouldBe true
        val loose = readyPhoto(ada)
        val beas = readyPhoto(bea)
        // ADR-0060: a voice note's Opus and its AAC variant go too.
        val voice = readyVoice(ada)
        val keys = listOf(attached, loose).map(::displayOf) + listOf("voice.ogg", "voice.m4a").map { keyOf(voice, it) }
        keys.forEach { stored(it) shouldBe true }

        deleteAccount(ada)

        rows.statusOf(attached) shouldBe "DELETED"
        rows.statusOf(loose) shouldBe "DELETED"
        rows.row(attached)["attached_entry_id"] shouldBe null
        keys.forEach { stored(it) shouldBe false }
        rows.statusOf(beas) shouldBe "READY"
        stored(displayOf(beas)) shouldBe true
        jdbc.queryForList("SELECT consumer_id FROM outbox_deliveries WHERE processed_at IS NULL").shouldBeEmpty()
    }

    @Test
    fun `delivered twice, nothing is released the second time`() {
        readyPhoto(ada)
        deleteAccount(ada)
        val released = rows.events("MediaReleased")

        deleteAccount(ada)

        rows.events("MediaReleased") shouldBe released
    }

    private fun readyPhoto(owner: UUID): UUID {
        val mediaId = UUID.fromString(client.upload(owner, TestImages.jpeg(64, 48)))
        dispatcher.dispatchDue(clock.instant(), BUDGET)
        rows.statusOf(mediaId) shouldBe "READY"
        return mediaId
    }

    private fun readyVoice(owner: UUID): UUID {
        val mediaId = UUID.fromString(client.upload(owner, TestVoices.wav(2.0), declaredType = "audio/wav", kind = "VOICE"))
        dispatcher.dispatchDue(clock.instant(), BUDGET)
        rows.statusOf(mediaId) shouldBe "READY"
        return mediaId
    }

    private fun keyOf(
        mediaId: UUID,
        name: String,
    ): String =
        "v/${jdbc.queryForObject(
            "SELECT storage_key FROM media_assets WHERE id = ?",
            String::class.java,
            mediaId,
        )!!.removePrefix("in/")}/$name"

    private fun deleteAccount(userId: UUID) {
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("User", userId, "AccountDeleted", mapOf("userId" to userId), clock.instant()))
        }
        // Twice: the account's delivery, then the purges it published.
        repeat(2) { dispatcher.dispatchDue(clock.instant(), BUDGET).failed shouldBe 0 }
    }

    /** The display variant processing made, as [MediaPurgeTest] names it. */
    private fun displayOf(mediaId: UUID): String =
        "v/${jdbc.queryForObject(
            "SELECT storage_key FROM media_assets WHERE id = ?",
            String::class.java,
            mediaId,
        )!!.removePrefix("in/")}/display.webp"

    private companion object {
        const val BUDGET = 20
    }
}
