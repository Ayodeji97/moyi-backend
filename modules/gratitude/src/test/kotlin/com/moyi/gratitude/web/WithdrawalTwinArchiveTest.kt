package com.moyi.gratitude.web

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.GetDays
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **The archive cannot tell a withdrawal from a deletion by hand, before the
 * erasure as well as after it** (ADR-0028 decision 8, ADR-0036 decision 7).
 *
 * Two bonds with one history. In the first, Ada blocks and takes her entries
 * back, and nothing has erased them yet: the rows and Bea's bookmarks on them
 * are whole, and only the gate knows. In the second, its twin, Ada deletes
 * each entry herself and then leaves. Bea walks both archives, plain and with
 * `favourites=true`, at several page sizes, and every page must be the same:
 * the same days, the same shapes and the same `nextCursor`.
 *
 * It was not so. The favourites filter matched Bea's bookmark on a withdrawn
 * row, the gate turned the day into one that holds no favourite, and the page
 * was sent without it but with a cursor: `{"items":[],"nextCursor":"…"}`. A
 * deletion by hand removes the bookmark with the words, so the twin could
 * never answer that. One old bookmark was enough to tell the two apart for as
 * long as the outbox consumer was behind (found by review, with this probe).
 *
 * **What is compared.** Two bonds do not share an id, so the bodies are
 * compared with every UUID replaced by the order it first appears in the
 * walk; a cursor is only a date and is compared as sent. An `ETag` is a keyed
 * digest of the bytes, so it cannot be equal across two bonds. It is compared
 * where the bytes can be: the first bond before and after the consumer has
 * erased, which is the stored state a deletion by hand leaves. There every
 * page is the same bytes under the same tag.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class WithdrawalTwinArchiveTest(
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

    /** The twin bond's two people: Cy is its Ada and Dee its Bea. */
    private lateinit var cy: UUID
    private lateinit var dee: UUID

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        cy = users.verified("Cy")
        dee = users.verified("Dee")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `a withdrawal nothing has erased yet and a deletion by hand are the same archive to the other member, page by page`() {
        val withdrawn = history()
        rig.bonds.block(ada, withdrawn, true)
        // Nothing has erased: every row of hers holds its words, and every bookmark is still there.
        wholeEntriesOf(withdrawn, ada) shouldBe HISTORY.size
        marksOf(withdrawn) shouldBe HISTORY.sumOf { it.marks }

        // The twin is two other people: Bea cannot join Ada again once Ada has blocked her.
        val byHand = history(cy, dee)
        entriesOf(byHand, cy).forEach { rig.bonds.delete(cy, it) }
        rig.bonds.leave(cy, byHand, false)
        wholeEntriesOf(byHand, cy) shouldBe 0

        for (asked in WALKS) {
            withClue("asked with $asked") {
                val hers = normalised(bea, withdrawn, asked)
                hers shouldBe normalised(dee, byHand, asked)
                // And said outright, for the page that gave it away: no page is empty and still points on.
                rig.pages(bea, withdrawn, asked).forEach { page ->
                    withClue(page.toString()) { (page["items"].isEmpty && !page["nextCursor"].isNull) shouldBe false }
                }
            }
        }
        // Not vacuous: the filter did have withdrawn days to drop, and days to keep between them.
        rig.datesOf(rig.walk(bea, withdrawn, mapOf("favourites" to "true", "limit" to "1"))) shouldBe KEPT
        rig.pages(bea, withdrawn, mapOf("favourites" to "true", "limit" to "1")).size shouldBe KEPT.size
    }

    @Test
    fun `the erasure moves no byte and no tag of any page the other member reads`() {
        val bond = history()
        rig.bonds.block(ada, bond, true)
        val before = WALKS.associateWith { sent(bond, it) }
        before.getValue(mapOf("favourites" to "true", "limit" to "1")).size shouldBe KEPT.size

        dispatcher.dispatchDue(clock.instant(), 10).failed shouldBe 0

        wholeEntriesOf(bond, ada) shouldBe 0
        for (asked in WALKS) {
            withClue("asked with $asked") { sent(bond, asked) shouldBe before.getValue(asked) }
        }
    }

    /**
     * More withdrawn bookmarks than the old refill bound allowed. They
     * must not consume that bound or hide the favourite beyond them.
     */
    @Test
    fun `a large withdrawn history is skipped before the limit and an older favourite is reached`() {
        clock.set(BOND_CREATED)
        val bond = rig.bonds.pair(ada, bea)
        val adasMember = rig.bonds.memberId(bond, ada)
        val beasMember = rig.bonds.memberId(bond, bea)
        // One request reads at most this many days when asked for one: the first window of two, and the refills.
        val reach = 2 + (GetDays.MAX_WINDOWS - 1) * GetDays.REFILL_WINDOW
        val first = LocalDate.of(2020, 1, 1)
        // The oldest day holds her own entry, bookmarked: a favourite whatever Ada does. Every later day holds only a bookmark on Ada's.
        (0..reach + 5).forEach { index ->
            val date = first.plusDays(index.toLong())
            val day = rig.insertDay(bond, date, "REVEALED")
            val at = Instant.parse("${date}T10:00:00Z")
            val adas = rig.insertEntry(day, bond, adasMember, EntryState.REVEALED, "zq-ada-$index", at)
            val beas = rig.insertEntry(day, bond, beasMember, EntryState.REVEALED, "zq-bea-$index", at)
            mark(if (index == 0) beas!! else adas!!, beasMember, at)
        }
        clock.set(NOW)
        rig.bonds.block(ada, bond, true)

        val pages = rig.pages(bea, bond, mapOf("favourites" to "true", "limit" to "1"))

        pages.map { rig.datesOf(it["items"].toList()) } shouldBe listOf(listOf(first.toString()))
        pages.single()["nextCursor"].isNull shouldBe true
        rig.datesOf(rig.walk(bea, bond, mapOf("favourites" to "true"))) shouldBe listOf(first.toString())
    }

    /**
     * A history exactly as long as the old refill bound must also be empty
     * without a cursor after withdrawal, before the consumer runs.
     */
    @Test
    fun `a withdrawn history exactly at the old bound carries no cursor`() {
        clock.set(BOND_CREATED)
        val bond = rig.bonds.pair(ada, bea)
        val adasMember = rig.bonds.memberId(bond, ada)
        val beasMember = rig.bonds.memberId(bond, bea)
        // Exactly as many days as one request reads when asked for one: every window comes back full.
        val reach = 2 + (GetDays.MAX_WINDOWS - 1) * GetDays.REFILL_WINDOW
        val first = LocalDate.of(2020, 1, 1)
        (0 until reach).forEach { index ->
            val date = first.plusDays(index.toLong())
            val day = rig.insertDay(bond, date, "REVEALED")
            val at = Instant.parse("${date}T10:00:00Z")
            val adas = rig.insertEntry(day, bond, adasMember, EntryState.REVEALED, "zq-ada-$index", at)
            rig.insertEntry(day, bond, beasMember, EntryState.REVEALED, "zq-bea-$index", at)
            mark(adas!!, beasMember, at)
        }
        clock.set(NOW)
        rig.bonds.block(ada, bond, true)

        val pages = rig.pages(bea, bond, mapOf("favourites" to "true", "limit" to "1"))

        pages.size shouldBe 1
        rig.datesOf(pages[0]["items"].toList()) shouldBe emptyList()
        pages[0]["nextCursor"].isNull shouldBe true
    }

    @Test
    fun `a large withdrawn history has the same pages before and after erasure`() {
        clock.set(Instant.parse("2019-12-31T10:00:00Z"))
        val bond = rig.bonds.pair(ada, bea)
        val adasMember = rig.bonds.memberId(bond, ada)
        val beasMember = rig.bonds.memberId(bond, bea)
        val reach = 2 + (GetDays.MAX_WINDOWS - 1) * GetDays.REFILL_WINDOW
        val first = LocalDate.of(2020, 1, 1)
        (0..reach).forEach { index ->
            val date = first.plusDays(index.toLong())
            val day = rig.insertDay(bond, date, "REVEALED")
            val at = Instant.parse("${date}T10:00:00Z")
            val adas = rig.insertEntry(day, bond, adasMember, EntryState.REVEALED, "review-probe", at)
            rig.insertEntry(day, bond, beasMember, EntryState.REVEALED, "review-probe", at)
            mark(adas!!, beasMember, at)
        }
        clock.set(NOW)
        rig.bonds.block(ada, bond, true)
        val asked = mapOf("favourites" to "true", "limit" to "1")
        val before = sent(bond, asked)
        dispatcher.dispatchDue(clock.instant(), 10).failed shouldBe 0
        sent(bond, asked) shouldBe before
    }

    // ---- the history ----

    /** One day of the shared history: which entries Bea bookmarks on it. */
    private data class Marked(
        val adas: Boolean,
        val beas: Boolean,
    ) {
        val marks = listOf(adas, beas).count { it }
    }

    /**
     * A bond with [HISTORY] in it, oldest day first, every day revealed, and
     * nothing written on the day it ends. The same words in every such bond.
     */
    private fun history(
        author: UUID = ada,
        reader: UUID = bea,
    ): String {
        clock.set(BOND_CREATED)
        val bond = rig.bonds.pair(author, reader)
        HISTORY.forEachIndexed { index, marked ->
            clock.set(at(DAY_ONE.plusDays(index.toLong())))
            val adas = rig.bonds.submit(author, bond, "zq-first-words-$index")
            val beas = rig.bonds.submit(reader, bond, "zq-second-words-$index")
            if (marked.adas) rig.favourite(reader, adas)
            if (marked.beas) rig.favourite(reader, beas)
        }
        clock.set(at(DAY_ONE.plusDays(HISTORY.size.toLong())))
        return bond
    }

    /** Every page of [reader]'s walk as it was sent, with each UUID replaced by the order it first appears in the walk. */
    private fun normalised(
        reader: UUID,
        bond: String,
        asked: Map<String, String>,
    ): List<String> {
        val seen = LinkedHashMap<String, String>()
        return rig.pages(reader, bond, asked).map { page ->
            UUID_PATTERN.replace(page.toString()) { found -> seen.getOrPut(found.value) { "id-${seen.size + 1}" } }
        }
    }

    /** Every page of Bea's walk as the bytes and the tag it was sent with. */
    private fun sent(
        bond: String,
        asked: Map<String, String>,
    ): List<Pair<String, String>> {
        val pages = mutableListOf<Pair<String, String>>()
        var cursor: String? = null
        do {
            val response = rig.days(bea, bond, asked + listOfNotNull(cursor?.let { "cursor" to it }))
            response.status shouldBe 200
            val body = response.getContentAsString(Charsets.UTF_8)
            pages.add(body to response.getHeader(HttpHeaders.ETAG)!!)
            cursor = json.readTree(body)["nextCursor"].takeUnless { it.isNull }?.asString()
            check(pages.size < MAX_PAGES) { "the walk did not end" }
        } while (cursor != null)
        pages.size shouldBeGreaterThan 0
        return pages
    }

    private fun mark(
        entry: UUID,
        member: UUID,
        at: Instant,
    ) {
        jdbc.update(
            "INSERT INTO entry_favourites (entry_id, member_id, created_at) VALUES (?, ?, ?)",
            entry,
            member,
            java.sql.Timestamp.from(at),
        ) shouldBe 1
    }

    private fun entriesOf(
        bond: String,
        author: UUID,
    ): List<String> =
        jdbc
            .queryForList(
                "SELECT id::text FROM entries WHERE bond_id = ?::uuid AND author_member_id = ? ORDER BY created_at",
                String::class.java,
                bond,
                rig.bonds.memberId(bond, author),
            ).filterNotNull()

    private fun marksOf(bond: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_favourites f JOIN entries e ON e.id = f.entry_id WHERE e.bond_id = ?::uuid",
            Int::class.java,
            bond,
        )!!

    /** How many of [author]'s entries in [bond] still hold their words. */
    private fun wholeEntriesOf(
        bond: String,
        author: UUID,
    ): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND author_member_id = ? AND text IS NOT NULL AND deleted_at IS NULL",
            Int::class.java,
            bond,
            rig.bonds.memberId(bond, author),
        )!!

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)
        const val MAX_PAGES = 100

        val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * Oldest first. Days holding only a bookmark on Ada's entry are the
         * ones a withdrawal turns into no favourite: the oldest, the newest,
         * and two together in the middle, so a page meets one before, between
         * and after the days it keeps. One day holds both bookmarks and one
         * holds none.
         */
        val HISTORY =
            listOf(
                Marked(adas = true, beas = false),
                Marked(adas = false, beas = true),
                Marked(adas = true, beas = false),
                Marked(adas = true, beas = false),
                Marked(adas = false, beas = false),
                Marked(adas = true, beas = true),
                Marked(adas = false, beas = true),
                Marked(adas = true, beas = false),
            )

        /** The days of [HISTORY] that are still favourites once Ada's words are gone, newest first. */
        val KEPT = listOf("2026-09-21", "2026-09-20", "2026-09-16")

        val WALKS: List<Map<String, String>> =
            listOf(
                mapOf("favourites" to "true", "limit" to "1"),
                mapOf("favourites" to "true", "limit" to "2"),
                mapOf("favourites" to "true", "limit" to "3"),
                mapOf("favourites" to "true"),
                mapOf("limit" to "1"),
                mapOf("limit" to "2"),
                emptyMap(),
            )

        /** 10:00Z: late morning of [date] in Africa/Lagos. */
        fun at(date: LocalDate): Instant = Instant.parse("${date}T10:00:00Z")
    }
}
