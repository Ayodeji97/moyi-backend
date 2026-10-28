package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.GetDays
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
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
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **Search cannot tell a withdrawal from a deletion by hand, before the erasure as after it**
 * (ADR-0028 decision 8, ADR-0036 decision 7, c6-corpus H-S1).
 *
 * A withdrawn author's rows keep their words, and so their projection and vector, until the
 * consumer runs (ADR-0035). The search's SQL excludes the known withdrawn before its `LIMIT`;
 * the gate, the marker read last, catches one that commits after the candidates were read; and
 * the fill reads on past a dropped day. These tests hold the three: a twin bond where the
 * author deleted each entry by hand and left, page for page; a history longer than one request
 * reads; and a block committed between the candidate query and the marker.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class SearchWithdrawalTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired access: BondAccess,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)
    private val interruptible = access as MarkerReadLastTest.InterruptibleBondAccess
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var cy: UUID
    private lateinit var dee: UUID

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        // The twins go by the same names: each day carries the partner's name, and the two
        // archives must differ by nothing but the mechanism.
        cy = users.verified("Ada")
        dee = users.verified("Bea")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        interruptible.disarm()
        pool.shutdownNow()
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `a withdrawn author's words find nothing before the erasure, page for page as after a hand deletion`() {
        val withdrawn = history(ada, bea)
        rig.bonds.block(ada, withdrawn, true)
        // Nothing has erased: her rows still hold their words.
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND text_search LIKE 'zebra%'",
            Int::class.java,
            withdrawn,
        ) shouldBe DAYS

        val byHand = history(cy, dee)
        jdbc
            .queryForList(
                "SELECT e.id::text FROM entries e JOIN bond_members m ON m.id = e.author_member_id " +
                    "WHERE e.bond_id = ?::uuid AND m.user_id = ? AND e.deleted_at IS NULL",
                String::class.java,
                byHand,
                cy,
            ).forEach { rig.bonds.delete(cy, it!!) }
        rig.bonds.leave(cy, byHand, false)

        for (q in listOf("zebra", "lion", "number")) {
            for (limit in listOf("1", "2", null)) {
                val asked = mapOf("q" to q) + listOfNotNull(limit?.let { "limit" to it })
                withClue("asked with $asked") {
                    normalised(bea, withdrawn, asked) shouldBe normalised(dee, byHand, asked)
                    rig.pages(bea, withdrawn, asked, route = "search").forEach { page ->
                        withClue(page.toString()) { (page["items"].isEmpty && !page["nextCursor"].isNull) shouldBe false }
                    }
                }
            }
        }
        // Not vacuous: her days are found through his words, with her entry a tombstone; hers find nothing.
        rig.datesOf(rig.walk(bea, withdrawn, mapOf("q" to "lion"), route = "search")).size shouldBe DAYS
        rig.walk(bea, withdrawn, mapOf("q" to "zebra"), route = "search") shouldBe emptyList()

        // And the erasure moves no byte of any page.
        val before = WALKS.associateWith { rig.pages(bea, withdrawn, it, route = "search").map(Any::toString) }
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET).failed shouldBe 0
        WALKS.forEach { asked ->
            withClue(asked) {
                rig.pages(bea, withdrawn, asked, route = "search").map(Any::toString) shouldBe
                    before[asked]
            }
        }
    }

    @Test
    fun `a withdrawn author's words find nothing over a history longer than one request reads`() {
        clock.set(LONG_AGO)
        val bond = rig.bonds.pair(ada, bea)
        val adas = rig.bonds.memberId(bond, ada)
        val beas = rig.bonds.memberId(bond, bea)
        val reach = 2 + (GetDays.MAX_WINDOWS - 1) * GetDays.REFILL_WINDOW
        val first = LocalDate.of(2020, 1, 1)
        (0..reach + 5).forEach { index ->
            val date = first.plusDays(index.toLong())
            val day = rig.insertDay(bond, date, "REVEALED")
            val at = Instant.parse("${date}T10:00:00Z")
            rig.insertEntry(day, bond, adas, EntryState.REVEALED, "zebra number $index", at)
            rig.insertEntry(day, bond, beas, EntryState.REVEALED, if (index == 0) "her own zebra" else "lion number $index", at)
        }
        clock.set(NOW)
        rig.bonds.block(ada, bond, true)

        val pages = rig.pages(bea, bond, mapOf("q" to "zebra", "limit" to "1"), route = "search")

        pages.map { rig.datesOf(it["items"].toList()) } shouldBe listOf(listOf(first.toString()))
        pages.single()["nextCursor"].isNull shouldBe true
    }

    @Test
    fun `a withdrawal committed after the candidates were read is caught by the gate, and leaves no trace`() {
        val bond = history(ada, bea)
        // The second resolution is the exclusion read before the candidate query: the block commits after it.
        interruptible.afterResolution(2) {
            pool.submit { rig.bonds.block(ada, bond, true) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            jdbc.queryForObject("SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id = ?::uuid", Int::class.java, bond) shouldBe 1
        }

        val answer = rig.search(bea, bond, mapOf("q" to "zebra"))

        answer.status shouldBe 200
        answer.contentAsString shouldBe """{"items":[],"nextCursor":null}"""
        interruptible.fired shouldBe true
        // The controller's, the exclusion read before the candidates, and the one after the entries were loaded.
        interruptible.resolutions shouldBe 3
    }

    // ---- helpers ----

    /** A bond of [author] and [reader] with [DAYS] revealed days: the author's "zebra number N", the reader's "lion number N". */
    private fun history(
        author: UUID,
        reader: UUID,
    ): String {
        clock.set(BOND_CREATED)
        val bond = rig.bonds.pair(author, reader)
        (0 until DAYS).forEach { index ->
            clock.set(Instant.parse("${DAY_ONE.plusDays(index.toLong())}T10:00:00Z"))
            rig.bonds.submit(author, bond, "zebra number $index")
            rig.bonds.submit(reader, bond, "lion number $index")
        }
        clock.set(Instant.parse("${DAY_ONE.plusDays(DAYS.toLong())}T10:00:00Z"))
        return bond
    }

    /** Every page of [reader]'s search as sent, each UUID replaced by the order it first appears in the walk. */
    private fun normalised(
        reader: UUID,
        bond: String,
        asked: Map<String, String>,
    ): List<String> {
        val seen = LinkedHashMap<String, String>()
        return rig.pages(reader, bond, asked, route = "search").map { page ->
            UUID_PATTERN.replace(page.toString()) { found -> seen.getOrPut(found.value) { "id-${seen.size + 1}" } }
        }
    }

    private companion object {
        val LONG_AGO: Instant = Instant.parse("2019-12-31T10:00:00Z")
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)
        const val DAYS = 4
        const val DISPATCH_BUDGET = 10
        const val TIMEOUT_SECONDS = 10L
        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val WALKS =
            listOf(
                mapOf("q" to "lion", "limit" to "1"),
                mapOf("q" to "lion"),
                mapOf("q" to "zebra", "limit" to "1"),
                mapOf("q" to "number"),
            )
    }
}
