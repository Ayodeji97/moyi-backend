package com.moyi.gratitude.infra.database

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.infra.GratitudeTestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The boundary tests for gratitude persistence: real Postgres, real Flyway,
 * real Hibernate (doc 12: prefer real-Postgres integration tests over
 * mocks). On trial: V12's constraints, and the lazy-open compare-and-set
 * `BondDayStore.openOrGet` is built on (ADR-0027's shape, B2's own
 * invite-creation precedent).
 *
 * `ddl-auto: validate` in this module's test `application.yml` is what
 * proves the mapping agrees with the schema at all — the context does not
 * start unless every mapped column exists with a compatible type.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class BondDayPersistenceTest(
    @Autowired private val days: BondDayStore,
    @Autowired private val entries: EntryStore,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()
    private val bondId = UUID.randomUUID()
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val date = LocalDate.of(2026, 9, 28)
    private val lagos = ZoneId.of("Africa/Lagos")
    private val now = Instant.parse("2026-09-28T20:00:00Z")

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
    }

    @Test
    fun `two first entries racing produce one day, not two`() {
        // The lazy open is INSERT ... ON CONFLICT DO NOTHING, which is B2's
        // invite-creation shape (ADR-0027) and not a check-then-insert.
        val results = inParallel(listOf({ days.openOrGet(bondId, date, lagos, now) }, { days.openOrGet(bondId, date, lagos, now) }))

        results.map { it.id }.toSet().size shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 1
    }

    @Test
    fun `a second entry by the same member is refused by the index, not by a check`() {
        val day = days.openOrGet(bondId, date, lagos, now)
        entries.insert(entry(day.id, ada))

        shouldThrow<DataIntegrityViolationException> { entries.insert(entry(day.id, ada)) }
        // …and the other member is fine.
        entries.insert(entry(day.id, bea))
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 2
    }

    @Test
    fun `the day keeps the zone it was opened in even after the bond's anchor moves`() {
        val day = days.openOrGet(bondId, date, ZoneId.of("Africa/Lagos"), now)

        // Nothing in this module reads the bond's current zone for an existing day.
        days.statusOf(bondId, date).shouldNotBeNull()
        days.find(day.id)!!.anchorTimezone shouldBe ZoneId.of("Africa/Lagos")
    }

    @Test
    fun `findForDay returns every entry filed against the day, and nothing else`() {
        val day = days.openOrGet(bondId, date, lagos, now)
        val otherDay = days.openOrGet(UUID.randomUUID(), date, lagos, now)
        entries.insert(entry(day.id, ada))
        entries.insert(entry(day.id, bea))
        entries.insert(entry(otherDay.id, ada))

        entries.findForDay(day.id).map { it.authorMemberId } shouldContainExactlyInAnyOrder listOf(ada, bea)
    }

    @Test
    fun `update writes a changed bond-day and moves the version`() {
        val day = days.openOrGet(bondId, date, lagos, now)

        days.update(day.withEntry())

        val reloaded = days.find(day.id).shouldNotBeNull()
        reloaded.entryCount shouldBe 1
        reloaded.status shouldBe BondDayStatus.PARTIAL
        // Hibernate's @Version, moved by the actual column change above —
        // the ETag a later slice's If-Match will compare against.
        reloaded.version shouldBe 1
    }

    private fun entry(
        bondDayId: BondDayId,
        authorMemberId: UUID,
    ): Entry =
        Entry.submit(
            id = EntryId(ids.opaque()),
            bondDayId = bondDayId,
            bondId = bondId,
            authorMemberId = authorMemberId,
            text = EntryText.of("thank you for the coffee"),
            intendedAt = now,
            now = now,
        )

    /**
     * Runs every call on its own thread and releases them together —
     * `InviteRaceTest`'s own helper (`modules/bond`), copied rather than
     * shared for the reason `FakeUserDirectory`'s own KDoc gives.
     */
    private fun <T> inParallel(calls: List<() -> T>): List<T> {
        val pool = Executors.newFixedThreadPool(calls.size)
        return try {
            val ready = CountDownLatch(calls.size)
            val go = CountDownLatch(1)
            val futures =
                calls.map { call ->
                    pool.submit(
                        Callable {
                            ready.countDown()
                            go.await(10, TimeUnit.SECONDS)
                            call()
                        },
                    )
                }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
