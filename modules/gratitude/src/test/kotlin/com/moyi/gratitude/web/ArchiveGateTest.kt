package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.service.CloseDay
import com.moyi.gratitude.service.asReader
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **The archive lists a day exactly when the gate would show the caller
 * something on it, and shows of each entry exactly what the gate allows**
 * (ADR-0036 decision 2).
 *
 * The feed has two parts that could disagree: a query that picks days, and
 * the read gate that renders their entries. This file holds them together
 * over every state a day can be in: each day status, crossed with each
 * member's entry (none, live and unrevealed, revealed, erased before it was
 * revealed, erased after), read by each member. For every cell it asserts
 *
 * - whether the day is listed;
 * - the exact JSON of each entry: every key and value of the wide shape, and
 *   the whole text of the two narrow ones;
 * - that no word of an entry the reader may not read in full is anywhere in
 *   what they were sent;
 * - and the cross-check: the day is listed **if and only if**
 *   `Entry.canBeReadBy` answers `FULL` or `TOMBSTONE`, for this reader, for
 *   at least one entry of the day.
 *
 * What is expected ([Shape.of]) is written here from the product's rule and
 * not taken from the code under test: your own entry, or one that has been
 * revealed; a tombstone where it was erased; and for a partner's entry you
 * were never shown, an author and a status.
 *
 * **Two ways of making the states.** The first tests build each state the
 * way the application reaches it: requests, the clock, the close job's step
 * for a day. Each then checks, from the rows, that it really is in the cell
 * it claims. The matrix test puts all two hundred cells down **by hand**,
 * because most cannot be reached: no request leaves a `FROZEN` day holding an
 * entry, an `EMPTY` or `OPEN` day holding a live one, a `REVEALED` day
 * holding an unrevealed one, or a `PARTIAL` day holding two. They are there
 * because the query reads no column of the day and the gate reads none
 * either, and the way to hold that is to vary the day under them.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList", "LargeClass", "TooManyFunctions") // What Spring hands the test; and one rule's whole table.
internal class ArchiveGateTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val access: BondAccess,
    @Autowired private val entries: EntryStore,
    @Autowired private val closeDay: CloseDay,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)

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
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    // ---- states the application reaches ----

    @Test
    fun `OPEN again after its only entry was deleted - the author's own tombstone, and not listed for the partner`() {
        val bond = pair()
        rig.bonds.delete(ada, rig.bonds.submit(ada, bond, ADAS))

        holds(bond, "OPEN", EntryState.ERASED_BEFORE_REVEAL, EntryState.NONE)
    }

    @Test
    fun `PARTIAL - listed for the one who wrote, and not for the one it is waiting for`() {
        val bond = pair()
        rig.bonds.submit(ada, bond, ADAS)

        holds(bond, "PARTIAL", EntryState.LIVE_UNREVEALED, EntryState.NONE)
    }

    @Test
    fun `PARTIAL after the partner wrote and deleted - each is listed their own, and the partner's is an author and a status`() {
        val bond = pair()
        rig.bonds.delete(bea, rig.bonds.submit(bea, bond, BEAS))
        rig.bonds.submit(ada, bond, ADAS)

        holds(bond, "PARTIAL", EntryState.LIVE_UNREVEALED, EntryState.ERASED_BEFORE_REVEAL)
    }

    @Test
    fun `PENDING_REVEAL - both have written and neither reads the other yet`() {
        val bond = pair()
        // A reveal time that has not come: the second entry does not reveal the day.
        jdbc.update("UPDATE bonds SET reveal_time_local = '23:59:59' WHERE id = ?::uuid", bond) shouldBe 1
        rig.bonds.submit(ada, bond, ADAS)
        rig.bonds.submit(bea, bond, BEAS)

        holds(bond, "PENDING_REVEAL", EntryState.LIVE_UNREVEALED, EntryState.LIVE_UNREVEALED)
    }

    @Test
    fun `REVEALED - both read both, before the day closes and after`() {
        val bond = pair()
        rig.bonds.submit(ada, bond, ADAS)
        rig.bonds.submit(bea, bond, BEAS)

        holds(bond, "REVEALED", EntryState.REVEALED, EntryState.REVEALED)
        close(bond)
        holds(bond, "REVEALED", EntryState.REVEALED, EntryState.REVEALED)
    }

    @Test
    fun `REVEALED with one entry deleted, then both - the wide tombstone for both readers`() {
        val bond = pair()
        val adas = rig.bonds.submit(ada, bond, ADAS)
        val beas = rig.bonds.submit(bea, bond, BEAS)

        rig.bonds.delete(ada, adas)
        holds(bond, "REVEALED", EntryState.ERASED_AFTER_REVEAL, EntryState.REVEALED)

        rig.bonds.delete(bea, beas)
        holds(bond, "REVEALED", EntryState.ERASED_AFTER_REVEAL, EntryState.ERASED_AFTER_REVEAL)
    }

    @Test
    fun `SOLO, closed while the bond was whole - the lone entry was revealed at the close and both read it`() {
        val bond = pair()
        val adas = rig.bonds.submit(ada, bond, ADAS)
        close(bond)

        holds(bond, "SOLO", EntryState.REVEALED, EntryState.NONE)

        // The partner wrote nothing and the entry is then erased: listed for him still, the wide tombstone and no entry of his.
        rig.bonds.delete(ada, adas)
        holds(bond, "SOLO", EntryState.ERASED_AFTER_REVEAL, EntryState.NONE)
    }

    @Test
    fun `SOLO on a bond that ended before the day did - never revealed, so the author's alone and not listed for the partner`() {
        val bond = pair()
        rig.bonds.submit(ada, bond, ADAS)
        clock.set(NOW.plusSeconds(7_200))
        rig.bonds.leave(bea, bond)
        close(bond)

        holds(bond, "SOLO", EntryState.LIVE_UNREVEALED, EntryState.NONE)
    }

    @Test
    fun `EMPTY after its only entry was deleted - a closed day with the author's tombstone is listed for the author alone`() {
        val bond = pair()
        rig.bonds.delete(ada, rig.bonds.submit(ada, bond, ADAS))
        close(bond)

        holds(bond, "EMPTY", EntryState.ERASED_BEFORE_REVEAL, EntryState.NONE)
    }

    @Test
    fun `SUSPENDED from before the pairing - the creator's entry is hers alone, and listed for nobody else`() {
        clock.set(DAY_BEFORE)
        val created = rig.bonds.create(ada)
        val bond = rig.bonds.idOf(created)
        rig.bonds.submit(ada, bond, ADAS)
        clock.set(NOW)
        rig.bonds.accept(bea, rig.bonds.codeOf(created))

        holds(bond, "SUSPENDED", EntryState.LIVE_UNREVEALED, EntryState.NONE, date = "2026-09-14")
    }

    @Test
    fun `SUSPENDED from before the pairing with both entries - each is listed their own and the other's stays locked`() {
        clock.set(DAY_BEFORE)
        val created = rig.bonds.create(ada)
        val bond = rig.bonds.idOf(created)
        rig.bonds.submit(ada, bond, ADAS)
        clock.set(NOW)
        rig.bonds.accept(bea, rig.bonds.codeOf(created))
        // The joiner files an entry against yesterday: a day whose span ended before the pairing stays private.
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(bea).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$BEAS","intendedAt":"2026-09-14T20:00:00Z"}"""
            }.andReturn()
            .response.status shouldBe 201

        holds(bond, "SUSPENDED", EntryState.LIVE_UNREVEALED, EntryState.LIVE_UNREVEALED, date = "2026-09-14")
    }

    @Test
    fun `an entry deleted and written again - the replacement is what its author is shown, and the partner is listed nothing`() {
        val bond = pair()
        rig.bonds.delete(ada, rig.bonds.submit(ada, bond, "zqw-first-draft"))
        clock.set(NOW.plusSeconds(60))
        rig.bonds.submit(ada, bond, ADAS)

        holds(bond, "PARTIAL", EntryState.LIVE_UNREVEALED, EntryState.NONE)
        rig.days(ada, bond).contentAsString shouldNotContain "zqw-first-draft"
    }

    // ---- every state, by hand ----

    @Test
    fun `over every day status and every state of each member's entry, a day is listed exactly when the gate shows something on it`() {
        val bond = pair()
        val cells = matrix(bond)
        cells.size shouldBe 200

        val listed = checkAll(bond, cells, withdrawn = emptySet())

        // The table is not vacuous: of 200 days, each member is listed the 160 they wrote on and the 16 only the other did but revealed.
        listed shouldBe mapOf(ada to 176, bea to 176)
    }

    @Test
    fun `a withdrawal changes what every listed entry of its author shows and not one day of what is listed`() {
        val bond = pair()
        val cells = matrix(bond)
        val before = listOf(ada, bea).associateWith { rig.datesOf(rig.walk(it, bond, mapOf("limit" to "50"))) }

        rig.bonds.block(ada, bond)

        // No dispatcher runs here: every row is as it was. Only the gate knows.
        cells.count { it.adas.state == EntryState.REVEALED || it.adas.state == EntryState.LIVE_UNREVEALED } shouldBe
            jdbc.queryForObject(
                "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND author_member_id = ? AND text IS NOT NULL",
                Int::class.java,
                bond,
                rig.bonds.memberId(bond, ada),
            )
        checkAll(bond, cells, withdrawn = setOf(ada))
        listOf(ada, bea).forEach { rig.datesOf(rig.walk(it, bond, mapOf("limit" to "50"))) shouldBe before.getValue(it) }
    }

    // ---- the table ----

    /** One day, as it was put down or found: its status and each member's entry on it. */
    private data class Cell(
        val date: String,
        val status: String,
        val adas: Written,
        val beas: Written,
    )

    /** One member's entry on a day: its state, and what a full rendering of it must carry. All null exactly for [EntryState.NONE]. */
    private data class Written(
        val state: EntryState,
        val id: String?,
        val words: String?,
        val at: Instant?,
        /** The instant the entry was filed against: the same as [at] unless it was back-filled. */
        val intendedAt: Instant? = at,
    )

    /** What a reader is sent for one entry. */
    private enum class Shape {
        ABSENT,
        FULL,
        TOMBSTONE,
        LOCKED,
        REMOVED,
        ;

        companion object {
            /**
             * BR-1 and BR-8, from the product's side. [mine] is whether the
             * reader wrote it; [withdrawn] is whether its author has
             * withdrawn their entries, which is an erasure whatever the row
             * still holds.
             */
            fun of(
                state: EntryState,
                mine: Boolean,
                withdrawn: Boolean,
            ): Shape {
                val erased = state.erased || withdrawn
                return when {
                    state == EntryState.NONE -> ABSENT
                    mine || state.revealed -> if (erased) TOMBSTONE else FULL
                    else -> if (erased) REMOVED else LOCKED
                }
            }
        }
    }

    /** Eight statuses by five states by five states, one day each, in a year the bond's own days are nowhere near. */
    private fun matrix(bond: String): List<Cell> {
        val adasMember = rig.bonds.memberId(bond, ada)
        val beasMember = rig.bonds.memberId(bond, bea)
        var date = LocalDate.of(2024, 1, 1)
        return BondDayStatus.entries.flatMap { status ->
            EntryState.entries.flatMap { adas ->
                EntryState.entries.map { beas ->
                    date = date.plusDays(1)
                    val day = rig.insertDay(bond, date, status.name)
                    val at = Instant.parse("${date}T10:00:00Z")

                    fun written(
                        author: UUID,
                        state: EntryState,
                        who: String,
                    ): Written {
                        val words = "zqw-$date-$who-words"
                        val id = rig.insertEntry(day, bond, author, state, words, at)
                        return Written(state, id?.toString(), words.takeIf { id != null }, at.takeIf { id != null })
                    }
                    Cell(date.toString(), status.name, written(adasMember, adas, "ada"), written(beasMember, beas, "bea"))
                }
            }
        }
    }

    /** A day the application made, read back from its rows as a [Cell]: which row is each member's, and what state it is in. */
    private fun cellOf(
        bond: String,
        date: String,
    ): Cell {
        fun written(user: UUID): Written {
            val rows =
                jdbc.queryForList(
                    """
                    SELECT e.id::text AS id, e.text, e.created_at, e.intended_at, e.revealed_at IS NOT NULL AS revealed,
                           (e.deleted_at IS NOT NULL OR e.status = 'DELETED') AS erased
                    FROM entries e JOIN bond_days d ON d.id = e.bond_day_id
                    WHERE d.bond_id = ?::uuid AND d.date = ?::date AND e.author_member_id = ?
                    ORDER BY (e.deleted_at IS NOT NULL OR e.status = 'DELETED'), e.created_at DESC, e.id
                    """.trimIndent(),
                    bond,
                    date,
                    rig.bonds.memberId(bond, user),
                )
            // A live row before an erased one, the newest first: the rule `GET /today` chooses by.
            val row = rows.firstOrNull() ?: return Written(EntryState.NONE, null, null, null)
            val state = EntryState.entries.single { it != EntryState.NONE && it.revealed == row["revealed"] && it.erased == row["erased"] }
            return Written(
                state,
                row["id"] as String,
                row["text"] as String?,
                (row["created_at"] as Timestamp).toInstant(),
                (row["intended_at"] as Timestamp).toInstant(),
            )
        }
        return Cell(date, rig.dayStatus(bond, date), written(ada), written(bea))
    }

    /** The one day of [bond] on [date] is in the state claimed, and both members are sent exactly what that state allows. */
    private fun holds(
        bond: String,
        status: String,
        adas: EntryState,
        beas: EntryState,
        date: String = "2026-09-15",
    ) {
        val cell = cellOf(bond, date)
        withClue("the state the requests left") {
            Triple(cell.status, cell.adas.state, cell.beas.state) shouldBe Triple(status, adas, beas)
        }
        checkAll(bond, listOf(cell), withdrawn = emptySet())
    }

    /** Every cell, for each reader. Answers how many days each reader was listed. */
    private fun checkAll(
        bond: String,
        cells: List<Cell>,
        withdrawn: Set<UUID>,
    ): Map<UUID, Int> =
        listOf(ada, bea).associateWith { reader ->
            val pages = rig.pages(reader, bond, mapOf("limit" to "50"))
            val raw = pages.joinToString("\n") { it.toString() }
            val days = pages.flatMap { it["items"].toList() }.associateBy { it["date"].asString() }
            days.size shouldBe pages.sumOf { it["items"].size() }
            val gate = access.membershipOf(reader, UUID.fromString(bond)).asReader()

            cells.count { cell ->
                val (mine, partners) = if (reader == ada) cell.adas to cell.beas else cell.beas to cell.adas
                val (author, partner) = if (reader == ada) ada to bea else bea to ada
                val own = Shape.of(mine.state, mine = true, withdrawn = author in withdrawn)
                val theirs = Shape.of(partners.state, mine = false, withdrawn = partner in withdrawn)
                val expected = own != Shape.ABSENT || theirs == Shape.FULL || theirs == Shape.TOMBSTONE
                val day = days[cell.date]

                withClue(
                    "${cell.status}, hers ${cell.adas.state}, his ${cell.beas.state}, read by ${if (reader == ada) "her" else "him"}",
                ) {
                    (day != null) shouldBe expected
                    // The cross-check: the query and the gate, each asked on its own, say the same of this day.
                    val readable =
                        entries.findForDay(BondDayId(rig.dayId(bond, cell.date))).any {
                            it.canBeReadBy(gate) == Readability.FULL || it.canBeReadBy(gate) == Readability.TOMBSTONE
                        }
                    (day != null) shouldBe readable
                    if (day != null) {
                        day.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("date", "status", "myEntry", "partnerEntry")
                        day["status"].asString() shouldBe cell.status
                        day["myEntry"].shouldBe(own, mine, rig.bonds.memberId(bond, author), bond, cell.date)
                        day["partnerEntry"].shouldBe(theirs, partners, rig.bonds.memberId(bond, partner), bond, cell.date)
                    }
                    // Words reach a reader only inside an entry rendered in full, listed day or not.
                    listOf(own to mine, theirs to partners).forEach { (shape, written) ->
                        written.words?.let { if (shape == Shape.FULL) raw shouldContain it else raw shouldNotContain it }
                    }
                }
                day != null
            }
        }

    /** One entry as it was sent, against the shape it must have: every key, and every value. */
    private fun JsonNode.shouldBe(
        shape: Shape,
        written: Written,
        author: UUID,
        bond: String,
        date: String,
    ) {
        when (shape) {
            Shape.ABSENT -> {
                isNull shouldBe true
            }

            Shape.LOCKED -> {
                toString() shouldBe """{"authorMemberId":"$author","status":"LOCKED"}"""
            }

            Shape.REMOVED -> {
                toString() shouldBe """{"authorMemberId":"$author","status":"REMOVED"}"""
            }

            Shape.FULL, Shape.TOMBSTONE -> {
                propertyNames().toList() shouldContainExactlyInAnyOrder WIDE_KEYS
                this["id"].asString() shouldBe written.id
                this["bondId"].asString() shouldBe bond
                this["date"].asString() shouldBe date
                this["authorMemberId"].asString() shouldBe author.toString()
                Instant.parse(this["createdAt"].asString()) shouldBe written.at
                Instant.parse(this["intendedAt"].asString()) shouldBe written.intendedAt
                this["favourited"].isBoolean shouldBe true
                this["favourited"].asBoolean() shouldBe false
                if (shape == Shape.FULL) {
                    this["text"].asString() shouldBe written.words
                    this["status"].asString() shouldBe if (written.state.revealed) "REVEALED" else "SUBMITTED"
                } else {
                    this["text"].isNull shouldBe true
                    this["status"].asString() shouldBe "DELETED"
                }
            }
        }
    }

    // ---- helpers ----

    private fun pair(): String {
        clock.set(BOND_CREATED)
        return rig.bonds.pair(ada, bea).also { clock.set(NOW) }
    }

    /** The close job's one step for the 15th, at the first instant that Lagos day is over. */
    private fun close(bond: String) {
        closeDay.settle(BondDayId(rig.dayId(bond, "2026-09-15")), END) shouldBe CloseDay.Outcome.CLOSED
    }

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val DAY_BEFORE: Instant = Instant.parse("2026-09-14T10:00:00Z")

        /** 11:00 in Africa/Lagos on 2026-09-15. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** Midnight in Lagos between the 15th and the 16th. */
        val END: Instant = Instant.parse("2026-09-15T23:00:00Z")

        // Markers no UUID or date can contain.
        const val ADAS = "zqw-ada-words"
        const val BEAS = "zqw-bea-words"

        val WIDE_KEYS = listOf("id", "bondId", "date", "authorMemberId", "text", "status", "createdAt", "intendedAt", "favourited")
    }
}
