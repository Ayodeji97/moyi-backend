package com.moyi.media.service

import com.moyi.common.testing.MutableClock
import com.moyi.media.infra.AssetRows
import com.moyi.media.infra.MediaIntegrationTest
import com.moyi.media.infra.MediaTestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
import java.util.UUID
import javax.sql.DataSource

/**
 * V36's backfill (review of #113): an asset made READY before D4 has no
 * `MediaReady`, and without one would never be moderated, nor shown to a
 * partner. The block between V36's `BEGIN backfill` and `END backfill`
 * comments is run here again, as the migration ran it, against rows of each
 * kind.
 */
@SpringBootTest(classes = [MediaTestApplication::class])
internal class MediaReadyBackfillTest(
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : MediaIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rows = AssetRows(jdbc)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_assets, outbox_deliveries, outbox_events")
    }

    @Test
    fun `a ready asset with no verdict and no MediaReady is given one, once, and nothing else is`() {
        val unmoderated = rows.insert(createdAt = clock.instant(), verdict = null)
        val passed = rows.insert(createdAt = clock.instant())
        val failed = rows.insert(createdAt = clock.instant(), status = "FAILED")

        jdbc.execute(BACKFILL)
        jdbc.execute(BACKFILL)

        readyEvents(unmoderated) shouldBe 1
        readyEvents(passed) shouldBe 0
        readyEvents(failed) shouldBe 0
        jdbc.queryForObject(
            "SELECT payload ->> 'mediaId' FROM outbox_events WHERE aggregate_id = ? AND event_type = 'MediaReady'",
            String::class.java,
            unmoderated,
        ) shouldBe unmoderated.toString()
    }

    private fun readyEvents(id: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'MediaReady'",
            Int::class.java,
            id,
        )!!

    private companion object {
        val BACKFILL: String =
            File("src/main/resources/db/migration/V36__media_moderation.sql")
                .readText()
                .substringAfter("-- BEGIN backfill")
                .substringBefore("-- END backfill")
                .substringAfter('\n')
    }
}
