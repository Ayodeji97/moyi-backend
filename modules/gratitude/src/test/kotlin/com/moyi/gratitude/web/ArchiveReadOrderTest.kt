package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryRepository
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * **The archive asks who has withdrawn after its entries are in hand**
 * (ADR-0035 decision 12), held at the one point the other tests of it cannot
 * reach: between the read of the entries and the question.
 *
 * `DaysFeedTest` and `DayViewTest` stop a request by locking `entries`. But
 * the statement that finds the days reads `entries` too, so the request
 * waits there, before either of the two reads whose order is the rule, and
 * goes on to make both after the ending has committed. Swap the two and
 * those tests still pass; it was done, and they did.
 *
 * Here the ending is a real `/block`, run to its commit on another thread,
 * **as the archive's read of the entries returns**: [StoppableEntryStore] is
 * the application's own store with that one thing added. The rows it handed
 * back are whole and stay whole, because nothing erases in this module's
 * tests. A request that had already asked who has withdrawn holds an answer
 * from before the ending and shows the words in a response made after it. A
 * request that asks last does not.
 *
 * A context of its own, for the one bean it replaces.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, ArchiveReadOrderTest.StoreConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class ArchiveReadOrderTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired json: ObjectMapper,
    @Autowired store: EntryStore,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)
    private val stoppable = store as StoppableEntryStore
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        stoppable.disarm()
        pool.shutdownNow()
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `the day asks who has withdrawn after its entries are in hand - the blocker is shown none of her words`() {
        endingAsTheEntriesArrive { bond -> rig.day(ada, bond, "2026-09-15") }
    }

    @Test
    fun `the day asks who has withdrawn after its entries are in hand - the partner is shown none of the blocker's words`() {
        endingAsTheEntriesArrive { bond -> rig.day(bea, bond, "2026-09-15") }
    }

    @Test
    fun `the feed asks who has withdrawn after its entries are in hand - the blocker is shown none of her words`() {
        endingAsTheEntriesArrive { bond -> rig.days(ada, bond) }
    }

    @Test
    fun `the feed asks who has withdrawn after its entries are in hand - the partner is shown none of the blocker's words`() {
        endingAsTheEntriesArrive { bond -> rig.days(bea, bond) }
    }

    @Test
    fun `a withdrawal racing the first favourites window does not consume the refill bound`() {
        clock.set(Instant.parse("2019-12-31T10:00:00Z"))
        val bond = rig.bonds.pair(ada, bea)
        val adasMember = rig.bonds.memberId(bond, ada)
        val beasMember = rig.bonds.memberId(bond, bea)
        val first = LocalDate.of(2020, 1, 1)
        (0..1000).forEach { index ->
            val date = first.plusDays(index.toLong())
            val day = rig.insertDay(bond, date, "REVEALED")
            val at = Instant.parse("${date}T10:00:00Z")
            val adas = rig.insertEntry(day, bond, adasMember, EntryState.REVEALED, "zq-race-ada", at)
            val beas = rig.insertEntry(day, bond, beasMember, EntryState.REVEALED, "zq-race-bea", at)
            jdbc.update(
                "INSERT INTO entry_favourites (entry_id, member_id, created_at) VALUES (?, ?, ?)",
                if (index == 0) beas else adas,
                beasMember,
                java.sql.Timestamp.from(at),
            )
        }
        clock.set(NOW)
        stoppable.afterNextRead {
            pool.submit { rig.bonds.block(ada, bond) }.get(10, TimeUnit.SECONDS)
        }

        val pages = rig.pages(bea, bond, mapOf("limit" to "1", "favourites" to "true"))

        stoppable.fired shouldBe true
        pages.size shouldBe 1
        rig.datesOf(pages.single()["items"].toList()) shouldBe listOf(first.toString())
        pages.single()["nextCursor"].isNull shouldBe true
    }

    /** The ending commits as the request's read of the entries returns, and before it asks who has withdrawn. */
    private fun endingAsTheEntriesArrive(read: (String) -> MockHttpServletResponse) {
        clock.set(BOND_CREATED)
        val bond = rig.bonds.pair(ada, bea)
        clock.set(NOW)
        rig.bonds.submit(ada, bond, ADAS_WORDS)
        rig.bonds.submit(bea, bond, BEAS_WORDS)
        // Off the request's thread, which is inside the read's transaction: a block made on it would not commit.
        stoppable.afterNextRead {
            pool.submit { rig.bonds.block(ada, bond) }.get(10, TimeUnit.SECONDS)
            withdrawalsIn(bond) shouldBe 1
        }

        val response = read(bond)

        stoppable.fired shouldBe true
        response.status shouldBe 200
        response.contentAsString shouldNotContain "zq-ada"
        response.contentAsString shouldContain "zq-bea"
        // Nothing was erased: her row is whole, and it is the gate that hid the words.
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND text = ? AND deleted_at IS NULL",
            Int::class.java,
            bond,
            ADAS_WORDS,
        ) shouldBe 1
    }

    private fun withdrawalsIn(bond: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id = ?::uuid", Int::class.java, bond)!!

    /**
     * The application's own [EntryStore], with one thing added: something
     * can be made to happen as the next [findForDays] returns. What it
     * returns is the real answer, untouched. Armed for one read and no more,
     * so nothing the action itself sets off can fire it again.
     */
    internal class StoppableEntryStore(
        entries: EntryRepository,
        entityManager: EntityManager,
    ) : EntryStore(entries, entityManager) {
        private val then = AtomicReference<(() -> Unit)?>()

        private val happened = AtomicBoolean()

        val fired: Boolean get() = happened.get()

        fun afterNextRead(action: () -> Unit) {
            happened.set(false)
            then.set(action)
        }

        fun disarm() {
            then.set(null)
        }

        override fun findForDays(bondDayIds: Collection<BondDayId>): Map<BondDayId, List<Entry>> {
            val read = super.findForDays(bondDayIds)
            then.getAndSet(null)?.let { action ->
                action()
                happened.set(true)
            }
            return read
        }
    }

    @TestConfiguration
    class StoreConfiguration {
        @Bean
        @Primary
        fun stoppableEntryStore(
            entries: EntryRepository,
            entityManager: EntityManager,
        ): EntryStore = StoppableEntryStore(entries, entityManager)
    }

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)

        // Markers no UUID, date or hexadecimal digest can contain.
        val ADAS_WORDS = "zq-ada-words-of-$DAY_ONE"
        val BEAS_WORDS = "zq-bea-words-of-$DAY_ONE"
    }
}
