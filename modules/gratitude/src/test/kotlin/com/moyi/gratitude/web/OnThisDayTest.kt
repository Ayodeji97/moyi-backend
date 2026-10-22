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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `GET /bonds/{bondId}/on-this-day` (FR-092, doc 06:175; ADR-0039): the days of earlier years
 * on the bond's today, as the archive shows them, and nothing on a bond that takes no writes.
 *
 * Days are written by real requests with the clock moved to each (a bond made years ago);
 * a day no request could leave is put down by hand. Africa/Lagos is UTC+01:00 all year, so
 * its midnight is 23:00Z the evening before.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // What Spring hands the test; and one route's rules, kept together.
internal class OnThisDayTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val access: BondAccess,
    @Autowired private val getDays: GetDays,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)
    private val interruptible = access as MarkerReadLastTest.InterruptibleBondAccess
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
        interruptible.disarm()
        pool.shutdownNow()
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `every earlier year's day on the bond's today, newest first, each as the archive gives it`() {
        val bond = pair(Instant.parse("2023-09-01T10:00:00Z"))
        listOf("2023-09-15", "2024-09-15", "2025-09-14", "2025-09-15", "2025-09-16", "2026-09-15").forEach { bothWrite(bond, it) }
        clock.set(NOW)

        val body = onThisDay(ada, bond)

        body.propertyNames().toList() shouldBe listOf("items")
        rig.datesOf(body["items"].toList()) shouldBe listOf("2025-09-15", "2024-09-15", "2023-09-15")
        body["items"].forEach { day ->
            withClue(day["date"].asString()) { day shouldBe json.readTree(rig.day(ada, bond, day["date"].asString()).contentAsString) }
        }
        rig.datesOf(onThisDay(bea, bond)["items"].toList()) shouldBe listOf("2025-09-15", "2024-09-15", "2023-09-15")
    }

    @Test
    fun `today is the bond's date, not UTC's`() {
        val bond = pair(Instant.parse("2025-09-01T10:00:00Z"))
        bothWrite(bond, "2025-09-14")
        bothWrite(bond, "2025-09-15")

        // 22:30Z on the 14th is 23:30 in Lagos: still the 14th there.
        clock.set(Instant.parse("2026-09-14T22:30:00Z"))
        rig.datesOf(onThisDay(ada, bond)["items"].toList()) shouldBe listOf("2025-09-14")
        // 23:30Z on the 14th is 00:30 on the 15th in Lagos, and still the 14th in UTC.
        clock.set(Instant.parse("2026-09-14T23:30:00Z"))
        rig.datesOf(onThisDay(ada, bond)["items"].toList()) shouldBe listOf("2025-09-15")
    }

    @Test
    fun `29 February only on 29 February`() {
        val bond = pair(Instant.parse("2024-02-20T10:00:00Z"))
        listOf("2024-02-28", "2024-02-29", "2025-02-28").forEach { bothWrite(bond, it) }

        clock.set(Instant.parse("2027-02-28T10:00:00Z"))
        rig.datesOf(onThisDay(ada, bond)["items"].toList()) shouldBe listOf("2025-02-28", "2024-02-28")
        clock.set(Instant.parse("2027-03-01T10:00:00Z"))
        rig.datesOf(onThisDay(ada, bond)["items"].toList()) shouldBe emptyList()
        clock.set(Instant.parse("2028-02-29T10:00:00Z"))
        rig.datesOf(onThisDay(ada, bond)["items"].toList()) shouldBe listOf("2024-02-29")
    }

    @Test
    fun `a past day is resurfaced by the archive's rule, for its author alone when only they can see it`() {
        val bond = pair(Instant.parse("2023-09-01T10:00:00Z"))
        val adas = rig.bonds.memberId(bond, ada)
        val beas = rig.bonds.memberId(bond, bea)
        handDay(bond, "2025-09-15", "PARTIAL", adas to "her unrevealed heron")
        handDay(bond, "2024-09-15", "PARTIAL", beas to "his unrevealed giraffe")
        handDay(bond, "2023-09-15", "SUSPENDED", adas to "before he joined", beas to "and his, unrevealed")

        val hers = onThisDay(ada, bond)
        rig.datesOf(hers["items"].toList()) shouldBe listOf("2025-09-15", "2023-09-15")
        hers.toString() shouldNotContain "giraffe"
        hers.toString() shouldNotContain "and his"
        hers["items"][1]["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        val his = onThisDay(bea, bond)
        rig.datesOf(his["items"].toList()) shouldBe listOf("2024-09-15", "2023-09-15")
        his.toString() shouldNotContain "heron"
    }

    @Test
    fun `a day of tombstones is not resurfaced, though the archive lists it`() {
        val bond = pair(Instant.parse("2024-09-01T10:00:00Z"))
        val gone = bothWrite(bond, "2025-09-15")
        val half = bothWrite(bond, "2024-09-15")
        clock.set(NOW)
        rig.bonds.delete(ada, gone.adas)
        rig.bonds.delete(bea, gone.beas)
        rig.bonds.delete(ada, half.adas)

        val hers = onThisDay(ada, bond)
        rig.datesOf(hers["items"].toList()) shouldBe listOf("2024-09-15")
        hers["items"][0]["myEntry"]["status"].asString() shouldBe "DELETED"
        // The archive still lists the day of tombstones.
        rig.datesOf(rig.walk(ada, bond)) shouldBe listOf("2025-09-15", "2024-09-15")
    }

    @Test
    fun `an ended bond resurfaces nothing, the same bytes left or blocked, for both members`() {
        val cy = users.verified("Cy")
        val dee = users.verified("Dee")
        val fay = users.verified("Fay")
        val gus = users.verified("Gus")
        val left = memories(ada, bea)
        val blocked = memories(cy, dee)
        val withdrawn = memories(fay, gus)
        // Not vacuous: each has a memory while it is open.
        listOf(left to ada, blocked to cy, withdrawn to fay).forEach { (bond, user) -> onThisDay(user, bond)["items"].size() shouldBe 1 }

        rig.bonds.leave(bea, left, false)
        rig.bonds.block(dee, blocked, false)
        rig.bonds.block(gus, withdrawn, true)

        val answers =
            listOf(left to ada, left to bea, blocked to cy, blocked to dee, withdrawn to fay, withdrawn to gus)
                .map { (bond, user) -> raw(user, bond).also { it.status shouldBe 200 } }
        answers.map { it.contentAsString }.toSet() shouldBe setOf(EMPTY)
        // The headers too, but for those that differ by the moment (task 3 review m1): not one says which ending it was.
        val headers = answers.map(::headersOf).toSet()
        withClue("$headers") { headers.size shouldBe 1 }
        headers.single().keys shouldContain HttpHeaders.CACHE_CONTROL
        // And after the withdrawal's erasure, the same.
        dispatcher.dispatchDue(clock.instant(), DISPATCH_BUDGET).failed shouldBe 0
        listOf(raw(fay, withdrawn), raw(gus, withdrawn)).forEach { erased ->
            erased.contentAsString shouldBe EMPTY
            headersOf(erased) shouldBe headers.single()
        }
    }

    @Test
    fun `a deletion countdown resurfaces nothing, and cancelling it brings the memories back`() {
        val bond = memories(ada, bea)
        countdown(ada, bond, "POST")
        countdown(bea, bond, "POST")
        // To be confirmed by running: two requests put the bond in PENDING_DELETION (BondDeletionController).
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, bond) shouldBe "PENDING_DELETION"

        raw(ada, bond).contentAsString shouldBe EMPTY

        countdown(ada, bond, "DELETE")
        onThisDay(ada, bond)["items"].size() shouldBe 1
    }

    @Test
    fun `the answer does not move with anything the partner does but react`() {
        val bond = memories(ada, bea)
        val before = raw(ada, bond).contentAsString
        val lastYears = onThisDay(bea, bond)["items"][0]

        // She opens the app, writes today, keeps his entry from last year, and reads this route.
        rig.page(bea, bond)
        rig.bonds.submit(bea, bond, "and today")
        rig.favourite(bea, lastYears["partnerEntry"]["id"].asString())
        raw(bea, bond)

        raw(ada, bond).contentAsString shouldBe before
    }

    @Test
    fun `a page of memories is bounded by bytes, the newest first`() {
        val bond = pair(Instant.parse("2023-09-01T10:00:00Z"))
        listOf("2024-09-15", "2025-09-15").forEach { bothWrite(bond, it) }
        clock.set(NOW)

        val membership = access.membershipOf(ada, UUID.fromString(bond))
        getDays.onThisDay(membership, textOctets = 1).map { it.date.toString() } shouldBe listOf("2025-09-15")
    }

    @Test
    fun `the memories stop at the first that does not fit, and a smaller one after it is not taken`() {
        val bond = pair(Instant.parse("2022-09-01T10:00:00Z"))
        bothWrite(bond, "2023-09-15")
        clock.set(Instant.parse("2024-09-15T10:00:00Z"))
        rig.bonds.submit(ada, bond, "a".repeat(LARGE_ENTRY))
        rig.bonds.submit(bea, bond, "b".repeat(LARGE_ENTRY))
        bothWrite(bond, "2025-09-15")
        clock.set(NOW)
        val membership = access.membershipOf(ada, UUID.fromString(bond))
        // Not vacuous: the bound holds the newest and the oldest together, and not the newest with the middle one.
        rig.datesOf(onThisDay(ada, bond)["items"].toList()) shouldBe listOf("2025-09-15", "2024-09-15", "2023-09-15")

        // Oldest first out (decision 13): the page ends where the first day does not fit, as the archive's does, so
        // what is left out is always the oldest run, never a year from the middle (task 3 review m3).
        getDays.onThisDay(membership, textOctets = SMALL_DAYS_ONLY).map { it.date.toString() } shouldBe listOf("2025-09-15")
    }

    @Test
    fun `a withdrawal committed after the candidates were read is caught by the gate`() {
        val bond = pair(Instant.parse("2024-09-01T10:00:00Z"))
        handDay(bond, "2025-09-15", "SOLO", rig.bonds.memberId(bond, ada) to "her lone entry, revealed at the close", revealed = true)
        // Not vacuous: the day is his memory too, until she takes it back.
        rig.datesOf(onThisDay(bea, bond)["items"].toList()) shouldBe listOf("2025-09-15")
        // The second resolution is the exclusion read before the candidate query: the block commits after it.
        interruptible.afterResolution(2) {
            pool.submit { rig.bonds.block(ada, bond, true) }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        raw(bea, bond).contentAsString shouldBe EMPTY

        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 3
    }

    // ---- helpers ----

    private data class Written(
        val adas: String,
        val beas: String,
    )

    private fun pair(at: Instant): String {
        clock.set(at)
        return rig.bonds.pair(ada, bea).also { clock.set(NOW) }
    }

    /** A bond of [first] and [second] made in 2024, both writing on last year's today. */
    private fun memories(
        first: UUID,
        second: UUID,
    ): String {
        clock.set(Instant.parse("2024-09-01T10:00:00Z"))
        val bond = rig.bonds.pair(first, second)
        clock.set(Instant.parse("2025-09-15T10:00:00Z"))
        rig.bonds.submit(first, bond, "a year ago, the first")
        rig.bonds.submit(second, bond, "a year ago, the second")
        clock.set(NOW)
        return bond
    }

    /** Both write on [date], Ada first, so the day reveals. */
    private fun bothWrite(
        bond: String,
        date: String,
    ): Written {
        clock.set(Instant.parse("${date}T10:00:00Z"))
        return Written(rig.bonds.submit(ada, bond, "ada on $date"), rig.bonds.submit(bea, bond, "bea on $date"))
    }

    /** A day put down by hand, with one entry per author given: unrevealed unless [revealed]. */
    private fun handDay(
        bond: String,
        date: String,
        status: String,
        vararg entries: Pair<UUID, String>,
        revealed: Boolean = false,
    ) {
        val on = LocalDate.parse(date)
        val day = rig.insertDay(bond, on, status)
        entries.forEach { (author, words) ->
            rig.insertEntry(
                day,
                bond,
                author,
                if (revealed) EntryState.REVEALED else EntryState.LIVE_UNREVEALED,
                words,
                Instant.parse("${date}T10:00:00Z"),
            )
        }
    }

    private fun raw(
        user: UUID,
        bond: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bond/on-this-day") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response

    /** Every header of [response] by name, but those that change from one moment to the next. */
    private fun headersOf(response: MockHttpServletResponse): Map<String, List<String>> =
        response.headerNames
            .filterNot { name -> VOLATILE_HEADERS.any { it.equals(name, ignoreCase = true) } }
            .sorted()
            .associateWith { response.getHeaders(it) }

    private fun onThisDay(
        user: UUID,
        bond: String,
    ) = json.readTree(raw(user, bond).also { it.status shouldBe 200 }.contentAsString)

    private fun countdown(
        user: UUID,
        bond: String,
        method: String,
    ) {
        val path = "/api/v1/bonds/$bond/deletion-request"
        val bearer = "Bearer ${tokens.issue(user).token}"
        val response =
            if (method == "POST") {
                mockMvc.post(path) { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response
            } else {
                mockMvc.delete(path) { header(HttpHeaders.AUTHORIZATION, bearer) }.andReturn().response
            }
        withClue("$method deletion-request: ${response.contentAsString}") { (response.status / 100) shouldBe 2 }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val EMPTY = """{"items":[]}"""
        const val DISPATCH_BUDGET = 10
        const val TIMEOUT_SECONDS = 10L
        const val LARGE_ENTRY = 400

        /** Two small days ("ada on …" and "bea on …", 34 octets each) fit; a small day and the large one do not. */
        const val SMALL_DAYS_ONLY = 100
        val VOLATILE_HEADERS = listOf(HttpHeaders.DATE, HttpHeaders.LAST_MODIFIED)
    }
}
