package com.moyi.gratitude.infra.database

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.BondDay
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.RevealDay
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The reveal writes both entries of a day, and it must write them from what
 * the rows hold **under the day's lock** — not from a copy this transaction
 * happened to load before it had the lock.
 *
 * ADR-0031 decision 9 records this trap for a day: Hibernate answers a second
 * read of a row from its identity map, lock or no lock, so the object in hand
 * is the one read first. `EntryStore.update` writes every column from the
 * entry it is given. Put the two together and a reveal that reads an entry it
 * already had in memory writes that older copy back over whatever was
 * committed in between: an edit silently undone, or erased words restored.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class RevealFreshReadTest(
    @Autowired private val days: BondDayStore,
    @Autowired private val entries: EntryStore,
    @Autowired private val reveal: RevealDay,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()
    private val bondId = UUID.randomUUID()
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val lagos = ZoneId.of("Africa/Lagos")
    private val now = Instant.parse("2026-09-28T20:00:00Z")
    private val window =
        DayWindow(LocalDate.of(2026, 9, 28), Instant.parse("2026-09-27T23:00:00Z"), Instant.parse("2026-09-28T23:00:00Z"))

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
    }

    @Test
    fun `the reveal does not write a copy read before the lock back over the row`() {
        val (day, adas, _) = aDayBothHaveWrittenOn()

        transactions.executeWithoutResult {
            // Loaded before any lock, as a route that finds its entry by id does.
            entries.find(adas.id)
            // Another transaction commits a change to that row meanwhile.
            elsewhere { jdbc.update("UPDATE entries SET text = 'changed meanwhile' WHERE id = ?", adas.id.value) }

            reveal.apply(days.lockAndFind(day.id), null, now.plusSeconds(60))
        }

        jdbc.queryForObject("SELECT text FROM entries WHERE id = ?", String::class.java, adas.id.value) shouldBe "changed meanwhile"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 2
    }

    @Test
    fun `an update never clears a reveal stamp and never moves it`() {
        val (day, adas, _) = aDayBothHaveWrittenOn()
        val revealedAt = now.plusSeconds(60)
        transactions.executeWithoutResult { reveal.apply(days.lockAndFind(day.id), null, revealedAt) }

        transactions.executeWithoutResult {
            val current = entries.lockAndFind(adas.id)
            entries.update(current.copy(revealedAt = null))
            entries.update(current.copy(revealedAt = revealedAt.plusSeconds(3600)))
        }

        jdbc.queryForObject("SELECT revealed_at FROM entries WHERE id = ?", Timestamp::class.java, adas.id.value)?.toInstant() shouldBe
            revealedAt
    }

    /** A committed `PARTIAL` day at `entry_count = 2`: what the second submission holds just before it reveals. */
    private fun aDayBothHaveWrittenOn(): Triple<BondDay, Entry, Entry> =
        transactions
            .execute {
                val opened = days.openOrGet(bondId, window, lagos, now, BondDayStatus.OPEN)
                val adas = entry(opened, ada).also(entries::insert)
                val beas = entry(opened, bea).also(entries::insert)
                val both = opened.withEntry().withEntry()
                days.update(both)
                Triple(both, adas, beas)
            }.shouldNotBeNull()

    private fun entry(
        day: BondDay,
        author: UUID,
    ): Entry =
        Entry.submit(
            id = EntryId(ids.opaque()),
            bondDayId = day.id,
            bondId = bondId,
            authorMemberId = author,
            text = EntryText.of("thank you for the coffee"),
            intendedAt = now,
            now = now,
        )

    /** On another connection, committed before this returns. */
    private fun elsewhere(work: () -> Unit) {
        val thread = Executors.newSingleThreadExecutor()
        try {
            thread.submit(work).get(10, TimeUnit.SECONDS)
        } finally {
            thread.shutdownNow()
        }
    }
}
