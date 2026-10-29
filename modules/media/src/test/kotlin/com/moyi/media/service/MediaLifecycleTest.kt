package com.moyi.media.service

import com.moyi.common.testing.MutableClock
import com.moyi.media.api.ImageViewStatus
import com.moyi.media.api.ImageViews
import com.moyi.media.api.LifecycleRun
import com.moyi.media.api.MediaLifecycle
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The reaper and the demotion (spec §4 D5, ADR-0050), each rule at its edge,
 * on the clock the tests move ([MutableClock]). The assets are written as each
 * rule needs them ([AssetRows]); the objects are `media.purge`'s, and
 * `MediaPurgeTest` holds them.
 *
 * Both jobs act on every row in the table, so each test starts from an empty
 * one (a class that left rows behind would otherwise be in its counts).
 */
@SpringBootTest(classes = [MediaTestApplication::class])
internal class MediaLifecycleTest(
    @Autowired private val lifecycle: MediaLifecycle,
    @Autowired private val images: ImageViews,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : MediaIntegrationTest() {
    private val rows = AssetRows(JdbcTemplate(dataSource))
    private val json = JsonMapper.builder().build()

    @BeforeEach
    fun clear() {
        rows.clear()
        clock.set(T0)
    }

    // The reaper.

    @Test
    fun `an upload never completed is deleted an hour after its URL stopped working, and not a moment before`() {
        val pending = rows.insert(status = "PENDING_UPLOAD", createdAt = T0)
        val deadline = T0.plus(UPLOAD_URL).plus(Duration.ofHours(1))

        reapAt(deadline).changed shouldBe 0
        rows.statusOf(pending) shouldBe "PENDING_UPLOAD"

        reapAt(deadline.plus(MICRO)).changed shouldBe 1
        rows.statusOf(pending) shouldBe "DELETED"
        rows.events(pending, MediaReleases.MEDIA_RELEASED) shouldBe 1
    }

    @Test
    fun `a ready photo on no entry is kept a day from when it became ready, however long processing took`() {
        // Ready two hours after it was made: the day counts from the readiness.
        val photo = rows.insert(createdAt = T0, readyAt = T0.plus(Duration.ofHours(2)))

        reapAt(T0.plus(DAY).plus(MICRO)).changed shouldBe 0
        reapAt(T0.plus(Duration.ofHours(2)).plus(DAY)).changed shouldBe 0
        rows.statusOf(photo) shouldBe "READY"

        reapAt(T0.plus(Duration.ofHours(2)).plus(DAY).plus(MICRO)).changed shouldBe 1
        rows.statusOf(photo) shouldBe "DELETED"
    }

    @Test
    fun `a failed asset on no entry is deleted a day after it was made`() {
        val failed = rows.insert(status = "FAILED", createdAt = T0)

        reapAt(T0.plus(DAY)).changed shouldBe 0
        reapAt(T0.plus(DAY).plus(MICRO)).changed shouldBe 1
        rows.statusOf(failed) shouldBe "DELETED"
    }

    @Test
    fun `a photo an entry let go is its owner's for a day from then, as one never attached is`() {
        val detachedAt = T0.plus(Duration.ofDays(30))
        val photo = rows.insert(createdAt = T0, detachedAt = detachedAt)

        reapAt(detachedAt.plus(DAY)).changed shouldBe 0
        rows.statusOf(photo) shouldBe "READY"

        reapAt(detachedAt.plus(DAY).plus(MICRO)).changed shouldBe 1
        rows.statusOf(photo) shouldBe "DELETED"
    }

    @Test
    fun `an attached asset is never reaped, however old it is`() {
        val attached = rows.insert(createdAt = T0, attachedTo = UUID.randomUUID())

        reapAt(T0.plus(Duration.ofDays(3650))).changed shouldBe 0

        rows.statusOf(attached) shouldBe "READY"
        rows.events(MediaReleases.MEDIA_RELEASED) shouldBe 0
    }

    @Test
    fun `an upload still being processed is not the reaper's, however long it has waited`() {
        val uploaded = rows.insert(status = "UPLOADED", createdAt = T0)
        val processing = rows.insert(status = "PROCESSING", createdAt = T0)

        reapAt(T0.plus(Duration.ofDays(30))).changed shouldBe 0

        rows.statusOf(uploaded) shouldBe "UPLOADED"
        rows.statusOf(processing) shouldBe "PROCESSING"
    }

    @Test
    fun `the reaper run twice in a row changes nothing the second time`() {
        val reaped =
            listOf(
                rows.insert(status = "PENDING_UPLOAD", createdAt = T0),
                rows.insert(createdAt = T0),
                rows.insert(status = "FAILED", createdAt = T0),
            )
        val kept = rows.insert(createdAt = T0, attachedTo = UUID.randomUUID())
        val later = T0.plus(Duration.ofDays(2))

        reapAt(later).changed shouldBe 3
        val after = (reaped + kept).associateWith(rows::row)

        reapAt(later).changed shouldBe 0
        (reaped + kept).associateWith(rows::row) shouldBe after
        rows.events(MediaReleases.MEDIA_RELEASED) shouldBe 3
        reaped.forEach { rows.events(it, MediaReleases.MEDIA_RELEASED) shouldBe 1 }
    }

    @Test
    fun `a run stops at its limit and says more are waiting`() {
        repeat(3) { rows.insert(createdAt = T0) }
        val later = T0.plus(Duration.ofDays(2))

        lifecycle.reap(later, 2) shouldBe LifecycleRun(changed = 2, more = true)
        lifecycle.reap(later, 2) shouldBe LifecycleRun(changed = 1, more = false)
    }

    // The demotion.

    @Test
    fun `an attached photo ninety days ready keeps only its display variant from then on, and is shown at its size`() {
        val owner = UUID.randomUUID()
        val photo = rows.insert(owner = owner, createdAt = T0, attachedTo = UUID.randomUUID())

        demoteAt(T0.plus(NINETY_DAYS).plus(MICRO)).changed shouldBe 1

        val row = rows.row(photo)
        rows.tierOf(photo) shouldBe 2
        row["status"] shouldBe "READY"
        val variants = json.readTree(row["variants"] as String)
        variants.size() shouldBe 1
        variants[0]["variant"].asString() shouldBe "DISPLAY"
        (row["width"] to row["height"]) shouldBe (1280 to 960)
        rows.events(photo, MediaReleases.MEDIA_DEMOTED) shouldBe 1

        // Its owner is shown the display variant now, at the display variant's size.
        val view = images.shownTo(owner, listOf(photo)).getValue(photo)
        view.status shouldBe ImageViewStatus.READY
        (view.width to view.height) shouldBe (1280 to 960)
        view.url.toString() shouldNotContain "full.webp"
    }

    @Test
    fun `demotion - a photo ready exactly ninety days ago is not yet demoted`() {
        val photo = rows.insert(createdAt = T0, attachedTo = UUID.randomUUID())

        demoteAt(T0.plus(NINETY_DAYS)).changed shouldBe 0

        rows.tierOf(photo) shouldBe 1
    }

    @Test
    fun `demotion - a photo on no entry is not demoted, however old`() {
        val photo = rows.insert(createdAt = T0)

        demoteAt(T0.plus(Duration.ofDays(400))).changed shouldBe 0

        rows.tierOf(photo) shouldBe 1
    }

    @Test
    fun `demotion - a photo already at tier 2 is not demoted again`() {
        val photo = rows.insert(createdAt = T0, attachedTo = UUID.randomUUID(), tier = 2)
        val before = rows.row(photo)

        demoteAt(T0.plus(Duration.ofDays(400))).changed shouldBe 0

        rows.row(photo) shouldBe before
        rows.events(MediaReleases.MEDIA_DEMOTED) shouldBe 0
    }

    @Test
    fun `demotion - an asset that is not READY is not demoted`() {
        // Attached and old, but deleted (erased with its entry) or quarantined (D4): neither is demoted.
        val deleted = rows.insert(status = "DELETED", createdAt = T0, readyAt = T0, attachedTo = UUID.randomUUID(), withVariants = true)
        val quarantined =
            rows.insert(status = "QUARANTINED", createdAt = T0, readyAt = T0, attachedTo = UUID.randomUUID(), withVariants = true)

        demoteAt(T0.plus(Duration.ofDays(400))).changed shouldBe 0

        rows.tierOf(deleted) shouldBe 1
        rows.tierOf(quarantined) shouldBe 1
    }

    @Test
    fun `demotion - a photo with no display variant is not demoted, so it is never left with nothing`() {
        val photo = rows.insert(createdAt = T0, attachedTo = UUID.randomUUID(), withDisplay = false)

        demoteAt(T0.plus(Duration.ofDays(400))).changed shouldBe 0

        rows.tierOf(photo) shouldBe 1
    }

    @Test
    fun `demotion - a voice note is not demoted`() {
        val voice = rows.insert(kind = "VOICE", createdAt = T0, attachedTo = UUID.randomUUID(), withVariants = true)

        demoteAt(T0.plus(Duration.ofDays(400))).changed shouldBe 0

        rows.tierOf(voice) shouldBe 1
    }

    @Test
    fun `the demotion run twice in a row changes nothing the second time`() {
        val photos = List(3) { rows.insert(createdAt = T0, attachedTo = UUID.randomUUID()) }
        val later = T0.plus(Duration.ofDays(120))

        demoteAt(later).changed shouldBe 3
        val after = photos.associateWith(rows::row)

        demoteAt(later).changed shouldBe 0
        photos.associateWith(rows::row) shouldBe after
        rows.events(MediaReleases.MEDIA_DEMOTED) shouldBe 3
    }

    private fun reapAt(now: Instant): LifecycleRun {
        clock.set(now)
        return lifecycle.reap(clock.instant(), LIMIT)
    }

    private fun demoteAt(now: Instant): LifecycleRun {
        clock.set(now)
        return lifecycle.demote(clock.instant(), LIMIT)
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-06-01T10:00:00Z")
        val MICRO: Duration = Duration.ofNanos(1_000)
        val DAY: Duration = Duration.ofHours(24)
        val UPLOAD_URL: Duration = Duration.ofMinutes(15)
        val NINETY_DAYS: Duration = Duration.ofDays(90)
        const val LIMIT = 100
    }
}
