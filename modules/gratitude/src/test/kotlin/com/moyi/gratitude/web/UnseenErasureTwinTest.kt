package com.moyi.gratitude.web

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **A partner's entry erased before the caller was ever shown it is, to the caller, an
 * entry that was never written** (the owner's ruling of 2026-10-21: ADR-0032 question 2,
 * ADR-0036 question 6). On every route that renders a day, `/today`, `/days`,
 * `/days/{date}`, search and on this day, and whether it was deleted by hand or withdrawn
 * and not yet erased (ADR-0028 decision 8).
 *
 * Two bonds with one history for the caller. In the first, Ada writes and takes it back
 * before Bea is shown it. In its twin, Cy writes nothing, and Dee does what Bea does. Bea
 * and Dee must be sent the same thing, byte for byte once ids are put in the order they
 * first appear, on all five routes and on the streak and the milestones besides (decision
 * 4: no count or status may differ either). An `ETag` is a keyed digest and cannot be
 * equal across bonds; where a withdrawal is waiting on its erasure, the first bond is also
 * compared with itself after the erasure, tags and all.
 *
 * **The history** is the same in every test: three days of last year, put down by hand
 * (the on-this-day date, on which Bea wrote alone and Ada wrote and erased before any
 * reveal; a day on which only Ada wrote and erased; and a day both wrote and read), and
 * today, written by requests. The tests differ in today. `prompt` is left out of `/today`:
 * it is a function of the bond's id and the date and nothing else (ADR-0038), so two bonds
 * are offered two prompts whatever anybody wrote.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class UnseenErasureTwinTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val dispatcher: OutboxDispatcher,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    /** The twin bond's two people: Cy is its Ada, who never writes, and Dee its Bea. */
    private lateinit var cy: UUID
    private lateinit var dee: UUID

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        cy = users.verified("Cy")
        dee = users.verified("Dee")
    }

    @AfterEach
    fun clear() {
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `a partner who wrote today and deleted it before the caller wrote is a partner who never wrote`() {
        val (erased, never) = twins()
        clock.set(NOW)
        val adas = rig.bonds.submit(ada, erased, PARTNERS_WORDS)
        rig.bonds.delete(ada, adas)

        sameOnEveryRoute(erased, never, partnerEntryId = adas)
        rig.dayStatus(erased, TODAY.toString()) shouldBe "OPEN"
    }

    @Test
    fun `a partner who wrote today and deleted it before the caller wrote theirs is a partner who never wrote`() {
        val (erased, never) = twins()
        clock.set(NOW)
        val adas = rig.bonds.submit(ada, erased, PARTNERS_WORDS)
        rig.bonds.delete(ada, adas)
        rig.bonds.submit(bea, erased, CALLERS_WORDS)
        rig.bonds.submit(dee, never, CALLERS_WORDS)

        sameOnEveryRoute(erased, never, partnerEntryId = adas)
        json.readTree(rig.bonds.today(bea, erased))["myEntry"]["text"].asString() shouldBe CALLERS_WORDS
    }

    @Test
    fun `a partner who wrote and deleted while the day waited on its reveal time is a partner who never wrote`() {
        val (erased, never) = twins()
        revealLate(erased, never)
        clock.set(NOW)
        rig.bonds.submit(bea, erased, CALLERS_WORDS)
        rig.bonds.submit(dee, never, CALLERS_WORDS)
        val adas = rig.bonds.submit(ada, erased, PARTNERS_WORDS)
        rig.dayStatus(erased, TODAY.toString()) shouldBe "PENDING_REVEAL"
        rig.bonds.delete(ada, adas)

        sameOnEveryRoute(erased, never, partnerEntryId = adas)
    }

    @Test
    fun `a partner who wrote today and withdrew it with a block is a partner who blocked without writing, before and after the erasure`() {
        val (erased, never) = twins()
        clock.set(NOW)
        val adas = rig.bonds.submit(ada, erased, PARTNERS_WORDS)
        rig.bonds.block(ada, erased)
        rig.bonds.block(cy, never)
        // Nothing has erased: today's row and last year's revealed one hold their words, and today still counts hers.
        wordsOf(erased, ada) shouldBe 2
        rig.dayStatus(erased, TODAY.toString()) shouldBe "PARTIAL"

        sameOnEveryRoute(erased, never, partnerEntryId = adas)
        erasureMovesNothing(erased)
        rig.dayStatus(erased, TODAY.toString()) shouldBe "OPEN"
        sameOnEveryRoute(erased, never, partnerEntryId = adas)
    }

    @Test
    fun `a partner who withdrew while the day waited on its reveal time is a partner who blocked without writing`() {
        val (erased, never) = twins()
        revealLate(erased, never)
        clock.set(NOW)
        rig.bonds.submit(bea, erased, CALLERS_WORDS)
        rig.bonds.submit(dee, never, CALLERS_WORDS)
        val adas = rig.bonds.submit(ada, erased, PARTNERS_WORDS)
        rig.bonds.block(ada, erased)
        rig.bonds.block(cy, never)
        rig.dayStatus(erased, TODAY.toString()) shouldBe "PENDING_REVEAL"

        sameOnEveryRoute(erased, never, partnerEntryId = adas)
        erasureMovesNothing(erased)
        rig.dayStatus(erased, TODAY.toString()) shouldBe "PARTIAL"
        sameOnEveryRoute(erased, never, partnerEntryId = adas)
    }

    // ---- the comparison ----

    /**
     * Bea's view of [erased] is Dee's view of [never] on every route, and nothing of Ada's
     * entry is in it: not its words, not its id. Not vacuous: the routes show something.
     */
    private fun sameOnEveryRoute(
        erased: String,
        never: String,
        partnerEntryId: String,
    ) {
        val hers = everything(bea, erased)
        val twins = everything(dee, never)
        hers.keys shouldBe twins.keys
        for (route in hers.keys) {
            withClue(route) { normalised(hers.getValue(route), hers) shouldBe normalised(twins.getValue(route), twins) }
        }
        val all = hers.values.joinToString("\n")
        all shouldNotContain PARTNERS_WORDS
        all shouldNotContain partnerEntryId
        all shouldNotContain "REMOVED"
        // The walk lists last year's days the caller has something on, and search finds her words.
        all shouldContain SHARED_WORDS
        all shouldContain LAST_YEAR.toString()
    }

    /** Every route that renders a day, and the two that count days, as [reader] is sent them: the body, and its status where it varies. */
    private fun everything(
        reader: UUID,
        bond: String,
    ): Map<String, String> {
        val routes = LinkedHashMap<String, String>()
        routes["today"] = withoutPrompt(rig.bonds.today(reader, bond))
        for (asked in listOf(emptyMap(), mapOf("limit" to "1"))) {
            routes["days $asked"] = rig.pages(reader, bond, asked).joinToString("\n")
            for (q in listOf(SHARED_WORDS, PARTNERS_WORDS, CALLERS_WORDS)) {
                routes["search $q $asked"] = rig.pages(reader, bond, asked + ("q" to q), route = "search").joinToString("\n")
            }
        }
        for (date in listOf(TODAY, LAST_YEAR, LAST_YEAR.plusDays(1), LAST_YEAR.plusDays(2))) {
            val response = rig.day(reader, bond, date.toString())
            routes["day $date"] = "${response.status} ${response.getContentAsString(Charsets.UTF_8)}"
        }
        routes["on this day"] = rig.bonds.get(reader, "/api/v1/bonds/$bond/on-this-day")
        routes["streak"] = rig.bonds.get(reader, "/api/v1/bonds/$bond/streak")
        routes["milestones"] = rig.bonds.get(reader, "/api/v1/bonds/$bond/milestones")
        return routes
    }

    /** [body] with each UUID replaced by the order it first appears across [all], so that two bonds can be compared. */
    private fun normalised(
        body: String,
        all: Map<String, String>,
    ): String {
        val seen = LinkedHashMap<String, String>()
        all.values.forEach { text -> UUID_PATTERN.findAll(text).forEach { seen.getOrPut(it.value) { "id-${seen.size + 1}" } } }
        return UUID_PATTERN.replace(body) { seen.getValue(it.value) }
    }

    private fun withoutPrompt(body: String): String = (json.readTree(body) as ObjectNode).apply { remove("prompt") }.toString()

    /**
     * The consumer erases what [bond]'s withdrawal took back, and Bea is sent the same bytes,
     * under the same tags, as before it: the response does not move a second time.
     */
    private fun erasureMovesNothing(bond: String) {
        val before = sent(bond)
        dispatcher.dispatchDue(clock.instant(), 10).failed shouldBe 0
        wordsOf(bond, ada) shouldBe 0
        sent(bond) shouldBe before
    }

    /** `/today`, and today and each day of last year alone, as Bea is sent them, with their tags. */
    private fun sent(bond: String): List<String> =
        listOf(rig.bonds.today(bea, bond)) +
            listOf(TODAY, LAST_YEAR, LAST_YEAR.plusDays(1), LAST_YEAR.plusDays(2)).map { date ->
                val response = rig.day(bea, bond, date.toString())
                "${response.status} ${response.getHeader(HttpHeaders.ETAG)} ${response.getContentAsString(Charsets.UTF_8)}"
            }

    /** How many of [author]'s entries in [bond] still hold their words. */
    private fun wordsOf(
        bond: String,
        author: UUID,
    ): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND author_member_id = ? AND text IS NOT NULL AND deleted_at IS NULL",
            Int::class.java,
            bond,
            rig.bonds.memberId(bond, author),
        )!!

    // ---- the history ----

    /** Ada and Bea's bond and Cy and Dee's, each with last year's history in it: the first and its twin. */
    private fun twins(): Pair<String, String> {
        clock.set(BOND_CREATED)
        val erased = rig.bonds.pair(ada, bea)
        val never = rig.bonds.pair(cy, dee)
        lastYear(erased, author = ada, reader = bea, partnerWrote = true)
        lastYear(never, author = cy, reader = dee, partnerWrote = false)
        return erased to never
    }

    /**
     * Last year's three days in [bond], as the close job would have left them. Where
     * [partnerWrote], [author] wrote on the first two and erased each before any reveal;
     * otherwise [author] wrote on neither, and those days hold what the close job leaves.
     */
    private fun lastYear(
        bond: String,
        author: UUID,
        reader: UUID,
        partnerWrote: Boolean,
    ) {
        val authors = rig.bonds.memberId(bond, author)
        val readers = rig.bonds.memberId(bond, reader)
        val partners = if (partnerWrote) EntryState.ERASED_BEFORE_REVEAL else EntryState.NONE
        val at = Instant.parse("${LAST_YEAR}T10:00:00Z")
        // The on-this-day date: hers alone, revealed when the day closed SOLO.
        val first = rig.insertDay(bond, LAST_YEAR, "SOLO")
        rig.insertEntry(first, bond, authors, partners, PARTNERS_WORDS, at)
        rig.insertEntry(first, bond, readers, EntryState.REVEALED, "$SHARED_WORDS one", at.plusSeconds(1))
        // His alone, erased: an emptied day.
        val second = rig.insertDay(bond, LAST_YEAR.plusDays(1), "EMPTY")
        rig.insertEntry(second, bond, authors, partners, PARTNERS_WORDS, at.plusSeconds(86_400))
        // Both wrote and read.
        val third = rig.insertDay(bond, LAST_YEAR.plusDays(2), "REVEALED")
        rig.insertEntry(third, bond, authors, EntryState.REVEALED, "the other's thanks", at.plusSeconds(2 * 86_400))
        rig.insertEntry(third, bond, readers, EntryState.REVEALED, "$SHARED_WORDS three", at.plusSeconds(2 * 86_400 + 1))
    }

    /** Today's reveal falls due after every read here, so two entries wait in `PENDING_REVEAL`. */
    private fun revealLate(vararg bonds: String) {
        bonds.forEach { jdbc.update("UPDATE bonds SET reveal_time_local = '23:59:59' WHERE id = ?::uuid", it) shouldBe 1 }
    }

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2025-09-10T10:00:00Z")

        /** The clock's own instant ([EntryChangesTest.TimeConfiguration]): late morning on the 15th in Lagos. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 15)
        val LAST_YEAR: LocalDate = LocalDate.of(2025, 9, 15)

        const val PARTNERS_WORDS = "zqtakenback"
        const val CALLERS_WORDS = "zqcallertoday"
        const val SHARED_WORDS = "zqkept"

        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
