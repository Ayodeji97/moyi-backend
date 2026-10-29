package com.moyi.gratitude.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.ProcessedPhotos
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * **Every erasure releases the entry's media** (spec D-9, Phase 4 D5,
 * ADR-0050): the photo is `DELETED` in `media` in the erasure's own
 * transaction, and a `MediaReleased` hands its objects to `media.purge`. One
 * test per path that erases an entry today, each its own way in:
 *
 * 1. the author's `DELETE /entries/{entryId}` ([ChangeEntry]);
 * 2. the withdrawal's consumer, `gratitude.withdrawal` ([WithdrawEntries]);
 * 3. the close job reaching a withdrawn author's day before that consumer
 *    ([CloseDay], through [EraseWithdrawnEntries]);
 * 4. a read reaching a withdrawn author's legacy joining day before it
 *    ([ReconcileJoiningDay], through [EraseWithdrawnEntries]).
 *
 * All four go through [EraseEntry], where the release is made: that it is made
 * in one place is the point, and each test goes red when that one call is
 * removed. Account deletion, which will erase too, is Phase 5's; it must go
 * through the same routine.
 *
 * The photo is put in place as the claim leaves one ([ProcessedPhotos],
 * attached by hand): what the claim does is `EntryPhotoTest`'s.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class ErasureReleasesMediaTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val closer: DayCloser,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val photos = ProcessedPhotos(jdbc)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    /** Before as well as after: the close job and the dispatcher work on every bond other test classes left behind. */
    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    @AfterEach
    fun clear() {
        rig.clear()
        jdbc.execute("TRUNCATE TABLE media_assets")
        users.clear()
        clock.set(MIDDAY)
    }

    @Test
    fun `1 - an author's DELETE of an entry releases its photo`() {
        clock.set(MIDDAY.minusSeconds(DAY))
        val bond = rig.pair(ada, bea)
        clock.set(MIDDAY)
        val entry = rig.submit(ada, bond, WORDS)
        val photo = attachPhoto(ada, entry)

        rig.delete(ada, entry)

        released(photo)
    }

    @Test
    fun `2 - the withdrawal's consumer releases the photo of every entry it erases`() {
        clock.set(MIDDAY.minusSeconds(DAY))
        val bond = rig.pair(ada, bea)
        val yesterdays = rig.submit(ada, bond, WORDS)
        val older = attachPhoto(ada, yesterdays)
        clock.set(MIDDAY)
        val entry = rig.submit(ada, bond, WORDS)
        val photo = attachPhoto(ada, entry)

        rig.block(ada, bond)
        stillAttached(photo)
        dispatcher.dispatchDue(clock.instant(), BUDGET)

        rig.delivery(bond)["processed_at"].shouldNotBeNull()
        released(photo)
        released(older)
    }

    @Test
    fun `3 - the close job reaching a withdrawn author's day before the consumer releases the photo`() {
        clock.set(MIDDAY.minusSeconds(DAY))
        val bond = rig.pair(ada, bea)
        clock.set(MIDDAY)
        val entry = rig.submit(ada, bond, WORDS)
        val photo = attachPhoto(ada, entry)

        // Just after the 15th ended in Lagos; the consumer is kept off until after the close job has run.
        clock.set(Instant.parse("2026-09-15T23:00:10Z"))
        rig.block(ada, bond)
        rig.postpone(bond, Instant.parse("2026-09-16T06:00:00Z"))
        clock.set(Instant.parse("2026-09-15T23:02:00Z"))
        closer.closeElapsedDays(clock.instant(), BUDGET).failed shouldBe 0

        rig.delivery(bond)["processed_at"].shouldBeNull()
        released(photo)
    }

    @Test
    fun `4 - a read reaching a withdrawn author's legacy joining day before the consumer releases the photo`() {
        // What C1 committed for a couple who both wrote on the day they paired: SUSPENDED, neither revealed.
        clock.set(MIDDAY)
        val created = rig.create(ada)
        val bond = rig.idOf(created)
        val entry = rig.submit(ada, bond, WORDS)
        val photo = attachPhoto(ada, entry)
        rig.accept(bea, rig.codeOf(created))
        rig.submit(bea, bond, WORDS)
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 1
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 2

        clock.set(MIDDAY.plusSeconds(3_600))
        rig.block(ada, bond)
        rig.postpone(bond, Instant.parse("2026-09-16T06:00:00Z"))
        rig.today(bea, bond)

        rig.delivery(bond)["processed_at"].shouldBeNull()
        released(photo)
    }

    /** A ready photo of [owner]'s, attached to [entry] as the claim attaches one. */
    private fun attachPhoto(
        owner: UUID,
        entry: String,
    ): UUID {
        val photo = photos.ready(owner)
        jdbc.update("UPDATE media_assets SET attached_entry_id = ?::uuid WHERE id = ?", entry, photo) shouldBe 1
        jdbc.update("UPDATE entries SET image_media_id = ? WHERE id = ?::uuid", photo, entry) shouldBe 1
        return photo
    }

    private fun stillAttached(photo: UUID) {
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, photo) shouldBe "READY"
        photos.attachedTo(photo).shouldNotBeNull()
    }

    /** Deleted in `media`, attached to nothing, and handed to `media.purge` exactly once. */
    private fun released(photo: UUID) {
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, photo) shouldBe "DELETED"
        photos.attachedTo(photo).shouldBeNull()
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'MediaReleased'",
            Int::class.java,
            photo,
        ) shouldBe 1
    }

    private companion object {
        /** Eleven in the morning in Lagos on the 15th. */
        val MIDDAY: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val DAY = 86_400L
        const val BUDGET = 1_000
        const val WORDS = "words that are taken back"
    }
}
