package com.moyi.gratitude.infra.database

import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.infra.GratitudeTestApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.ceil

/**
 * The archive's query on its own: which days it picks (decision 1 of the
 * C5b plan), how it pages, and **what Postgres does to answer it**.
 *
 * Rows are put down by hand. Nothing here is about how a day comes to be in
 * a state; `ArchiveGateTest` builds those through the application. This is
 * about the statement: given these rows, these days, in this order, read off
 * the index and not by walking a bond's whole history.
 *
 * The ids are random and every assertion names the bond it made, so rows
 * another test class left in the shared database change nothing here.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class ArchiveDaysTest(
    @Autowired private val archive: ArchiveDays,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    private val bond = UUID.randomUUID()
    private val me = UUID.randomUUID()
    private val partner = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
    }

    // ---- which days ----

    @Test
    fun `a day is a candidate when it holds an entry the member wrote or one that has been revealed, erased or not`() {
        val expected = mutableListOf<LocalDate>()
        var date = FIRST

        fun case(
            listed: Boolean,
            status: String = "REVEALED",
            build: (UUID) -> Unit,
        ) {
            date = date.plusDays(1)
            build(day(bond, date, status))
            if (listed) expected += date
        }

        case(listed = false, status = "EMPTY") { }
        case(listed = true, status = "PARTIAL") { entry(it, me) }
        case(listed = false, status = "PARTIAL") { entry(it, partner) }
        case(listed = true) { entry(it, partner, revealed = true) }
        case(listed = true) { entry(it, partner, revealed = true, erased = true) }
        case(listed = false, status = "OPEN") { entry(it, partner, erased = true) }
        case(listed = true, status = "EMPTY") { entry(it, me, erased = true) }
        // The day's own columns decide nothing: a closed SOLO day and a REVEALED one, each holding only the partner's unrevealed entry.
        case(listed = false, status = "SOLO") { entry(it, partner) }
        case(listed = false, status = "REVEALED") { entry(it, partner) }
        case(listed = true, status = "SUSPENDED") { entry(it, me) }
        case(listed = false, status = "SUSPENDED") { entry(it, partner) }
        case(listed = true, status = "SUSPENDED") {
            entry(it, me)
            entry(it, partner)
        }

        candidates(limit = 50).map { it.date } shouldBe expected.reversed()
        // And for the partner, the mirror image of the one-sided days.
        val forPartner = archive.candidates(bond, partner, null, null, false, 50).map { it.date }
        forPartner shouldBe
            listOf(12, 11, 9, 8, 6, 5, 4, 3).map { FIRST.plusDays(it.toLong()) }
    }

    @Test
    fun `a candidate carries its day's id, date and status, and another bond's days are never among them`() {
        val mine = day(bond, FIRST, "SOLO").also { entry(it, me, revealed = true) }
        val other = UUID.randomUUID()
        day(other, FIRST, "REVEALED").also { entry(it, me, revealed = true) }
        day(other, FIRST.plusDays(1), "REVEALED").also { entry(it, UUID.randomUUID(), revealed = true) }

        val found = candidates(limit = 10)

        found.map { Triple(it.id.value, it.date, it.status.name) } shouldBe listOf(Triple(mine, FIRST, "SOLO"))
    }

    // ---- paging ----

    @Test
    fun `candidates come newest first, no more than asked for, strictly before one date and on or before another`() {
        (0L until 10).forEach { offset -> entry(day(bond, FIRST.plusDays(offset), "REVEALED"), me, revealed = true) }

        fun dates(
            before: LocalDate? = null,
            until: LocalDate? = null,
            limit: Int = 50,
        ) = archive.candidates(bond, me, before, until, false, limit).map { it.date.dayOfYear - FIRST.dayOfYear }

        dates() shouldBe (9 downTo 0).toList()
        dates(limit = 3) shouldBe listOf(9, 8, 7)
        dates(before = FIRST.plusDays(7), limit = 3) shouldBe listOf(6, 5, 4)
        dates(until = FIRST.plusDays(7), limit = 3) shouldBe listOf(7, 6, 5)
        // Both apply: whichever is the earlier bound wins.
        dates(before = FIRST.plusDays(7), until = FIRST.plusDays(3)) shouldBe listOf(3, 2, 1, 0)
        dates(before = FIRST.plusDays(3), until = FIRST.plusDays(7)) shouldBe listOf(2, 1, 0)
        dates(before = FIRST) shouldBe emptyList()
        dates(until = FIRST.minusDays(1)) shouldBe emptyList()
    }

    // ---- favourites ----

    @Test
    fun `with favourites only, a day is a candidate when the member has marked an entry on it that is revealed and not erased`() {
        val expected = mutableListOf<LocalDate>()
        var date = FIRST

        fun case(
            listed: Boolean,
            build: (UUID) -> Unit,
        ) {
            date = date.plusDays(1)
            build(day(bond, date, "REVEALED"))
            if (listed) expected += date
        }

        case(listed = true) { mark(entry(it, partner, revealed = true), me) }
        case(listed = true) { mark(entry(it, me, revealed = true), me) }
        case(listed = false) { entry(it, me, revealed = true) }
        // The partner's mark is the partner's.
        case(listed = false) { mark(entry(it, me, revealed = true), partner) }
        // A mark left on a row that was erased, or never revealed: no request leaves one, and the query does not rely on that.
        case(listed = false) { mark(entry(it, partner, revealed = true, erased = true), me) }
        case(listed = false) { mark(entry(it, me), me) }
        // Marked on one entry of the day and not the other: the day is listed once.
        case(listed = true) {
            mark(entry(it, me, revealed = true), me)
            entry(it, partner, revealed = true)
        }

        archive.candidates(bond, me, null, null, true, 50).map { it.date } shouldBe expected.reversed()
        archive.candidates(bond, me, expected.last(), null, true, 1).map { it.date } shouldBe listOf(expected[1])
    }

    // ---- the plan ----

    /**
     * A bond of 2,000 days among twenty others of 200, analysed: the first
     * page, a page 1,500 days in, and a page from a date.
     *
     * **What is asserted is what the feed needs, not how this version of
     * Postgres chose to give it.** Two things, read from the executor's own
     * counts (`EXPLAIN (ANALYZE, FORMAT JSON)`): no table is read from end
     * to end, and the rows taken from `bond_days` and from `entries` are
     * about a page, not the bond's two thousand days. The second carries the
     * weight. A plan that sorted the bond's days, or walked its history to
     * find a page, would have to take every one of them first, and the count
     * says so whatever the node is called. No index and no node other than
     * the sequential scan is named, because a newer planner may reach the
     * same cost by another road (a bitmap scan, an incremental sort of a few
     * rows, the other of the two indexes on `(bond_id, date)`), and that
     * would be no regression.
     *
     * Rows "taken" are the rows a scan returned and the rows it read and
     * discarded by a filter, over all its loops: a scan that reads the whole
     * bond and keeps a page of it is counted as the whole bond.
     *
     * **If this fails after a Postgres upgrade**, read
     * `build/archive-plans.txt`, which this test writes for a person.
     *
     * - A `Seq Scan` on `bond_days` or `entries`: an index the statement
     *   depends on is no longer chosen, or no longer there
     *   (`bond_days (bond_id, date)` unique, `bond_days_feed_idx`,
     *   `entries_bond_day_idx`). With these row counts that is real. The
     *   repair is in the schema or the statement, not here.
     * - A row count over the bound and no sequential scan: the read of
     *   `bond_days` no longer stops at the limit. Look for a `Sort` above a
     *   scan of the whole bond, and check that the statement still orders by
     *   the bare `date` of an index that leads with `bond_id`.
     * - On `entry_favourites`, a sequential scan or a count near the size of
     *   the table: V22's `(member_id, entry_id)` index has stopped serving
     *   the favourites filter, and every member's page reads everybody's
     *   marks.
     *
     * The guarantee is re-established when the three pages again take about
     * a page of rows from each table on this seeded history. If a new plan
     * does that by a road these counts misread, change how the count is
     * taken, not the bound.
     */
    @Test
    fun `a page takes about a page of rows from each table and scans none whole, however long the bond's history`() {
        seedHistory()

        val plans = StringBuilder()
        listOf(
            "first page" to ArchiveDays.query(bond, me, null, null, false, PAGE),
            "deep page" to ArchiveDays.query(bond, me, FIRST.plusDays(500), null, false, PAGE),
            "until" to ArchiveDays.query(bond, me, null, FIRST.plusDays(1_000), false, PAGE),
        ).forEach { (name, query) ->
            val text = explain(query)
            plans
                .append("== ")
                .append(name)
                .append('\n')
                .append(text)
                .append("\n\n")
            val scans = scansOf(query)
            withClue("$name\n$text") {
                scans.filter { it.node == SEQUENTIAL }.map { it.table } shouldBe emptyList()
                // A page's worth of days, not the bond's 2,000; and their entries found a day at a time.
                scans.rowsTakenFrom("bond_days") shouldBeLessThan PAGE * 3
                scans.rowsTakenFrom("entries") shouldBeLessThan PAGE * 3
            }
        }

        val favouritesQuery = ArchiveDays.query(bond, me, null, null, true, PAGE)
        val favourites = explain(favouritesQuery)
        plans.append("== favourites, first page\n").append(favourites).append('\n')
        val scans = scansOf(favouritesQuery)
        withClue(favourites) {
            // A member with twenty marks in two thousand days, in a table of eight thousand marks: found from the member,
            // not by reading every mark or every day. This is the query V22's `(member_id, entry_id)` index is for (the
            // primary key starts from the entry); the index is not named, and what it buys is counted.
            scans.filter { it.node == SEQUENTIAL }.map { it.table } shouldBe emptyList()
            scans.rowsTakenFrom("entry_favourites") shouldBeLessThan MARKS * 3
            scans.rowsTakenFrom("bond_days") shouldBeLessThan PAGE * 3
        }
        val marked =
            jdbc
                .queryForList(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT entry_id FROM entry_favourites WHERE member_id = ? AND entry_id IN " +
                        "(SELECT id FROM entries WHERE bond_id = ? LIMIT 40)",
                    String::class.java,
                    me,
                    bond,
                ).joinToString("\n")
        plans.append("\n== markedBy-shaped lookup\n").append(marked).append('\n')
        File("build").mkdirs()
        File("build/archive-plans.txt").writeText(plans.toString())
    }

    @Test
    fun `the seeded history pages as any other does`() {
        seedHistory()

        val first = candidates(limit = PAGE)
        first.size shouldBe PAGE
        first.first().date shouldBe FIRST.plusDays(1_999)
        val deep = archive.candidates(bond, me, FIRST.plusDays(500), null, false, PAGE)
        deep.map { it.date } shouldBe (499 downTo 479).map { FIRST.plusDays(it.toLong()) }
        archive.candidates(UUID.randomUUID(), me, null, null, false, PAGE).shouldBeEmpty()
    }

    // ---- rows ----

    private fun candidates(limit: Int) = archive.candidates(bond, me, null, null, false, limit)

    private fun explain(query: Pair<String, List<Any>>): String =
        jdbc
            .queryForList("EXPLAIN (ANALYZE, BUFFERS) ${query.first}", String::class.java, *query.second.toTypedArray())
            .joinToString("\n")

    /** One read of a table in an executed plan: what kind of node, and how many rows it took from the table over all its loops. */
    private data class Scan(
        val node: String,
        val table: String,
        val rowsTaken: Long,
    )

    /**
     * Every node of [query]'s executed plan that reads a table. Read from
     * the JSON form, whose keys are the executor's own and do not change
     * with how a release prints a plan.
     */
    private fun scansOf(query: Pair<String, List<Any>>): List<Scan> {
        val statement = "EXPLAIN (ANALYZE, FORMAT JSON) ${query.first}"
        val document = jdbc.queryForObject(statement, String::class.java, *query.second.toTypedArray())!!
        return scansIn(JSON.readTree(document).single()["Plan"])
    }

    private fun scansIn(node: JsonNode): List<Scan> {
        val own =
            node["Relation Name"]?.let { table ->
                // Returned, and read but dropped by a filter or a recheck: each is a row the scan had to take.
                val perLoop = TAKEN.sumOf { node[it]?.asDouble() ?: 0.0 }
                Scan(node["Node Type"].asString(), table.asString(), ceil(perLoop * node["Actual Loops"].asDouble()).toLong())
            }
        return listOfNotNull(own) + node["Plans"]?.flatMap(::scansIn).orEmpty()
    }

    /** All rows taken from [table], by however many nodes read it. A plan that never reads it is not this query's. */
    private fun List<Scan>.rowsTakenFrom(table: String): Int {
        val reads = filter { it.table == table }
        check(reads.isNotEmpty()) { "no read of $table in the plan" }
        return reads.sumOf { it.rowsTaken }.toInt()
    }

    private fun seedHistory() {
        seedBond(bond, me, partner, days = 2_000)
        repeat(20) { seedBond(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), days = 200) }
        // Some of the big bond's entries are marked, a hundred days apart.
        jdbc.update(
            """
            INSERT INTO entry_favourites (entry_id, member_id, created_at)
            SELECT e.id, ?, now() FROM entries e JOIN bond_days d ON d.id = e.bond_day_id
            WHERE e.bond_id = ? AND e.author_member_id = ? AND (d.date - DATE '2020-01-01') % 100 = 0
            """.trimIndent(),
            me,
            bond,
            partner,
        )
        // And every other bond's entries are marked by their own authors, so the table is other people's marks, as it will be.
        jdbc.update(
            "INSERT INTO entry_favourites (entry_id, member_id, created_at) " +
                "SELECT id, author_member_id, now() FROM entries WHERE bond_id <> ?",
            bond,
        )
        jdbc.execute("ANALYZE bond_days")
        jdbc.execute("ANALYZE entries")
        jdbc.execute("ANALYZE entry_favourites")
    }

    /** A history in two statements: a revealed day for every date, with an entry from each member. */
    private fun seedBond(
        bondId: UUID,
        first: UUID,
        second: UUID,
        days: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, revealed_at,
                                   closed_at, created_at, version)
            SELECT gen_random_uuid(), ?, d::date, 'REVEALED', 'UTC', d, d + interval '1 day', 2, d + interval '12 hours',
                   d + interval '1 day', d, 0
            FROM generate_series(
                TIMESTAMPTZ '2020-01-01 00:00:00+00',
                TIMESTAMPTZ '2020-01-01 00:00:00+00' + (? - 1) * interval '1 day',
                interval '1 day'
            ) AS d
            """.trimIndent(),
            bondId,
            days,
        )
        jdbc.update(
            """
            INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at, revealed_at)
            SELECT gen_random_uuid(), d.id, d.bond_id, a.author, 'seeded', 'REVEALED', d.starts_at, d.starts_at, d.starts_at, d.revealed_at
            FROM bond_days d CROSS JOIN (VALUES (?::uuid), (?::uuid)) AS a(author)
            WHERE d.bond_id = ?
            """.trimIndent(),
            first,
            second,
            bondId,
        )
    }

    private fun day(
        bondId: UUID,
        date: LocalDate,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        val start = Timestamp.from(date.atStartOfDay().toInstant(ZoneOffset.UTC))
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, version)
            VALUES (?, ?, ?, ?, 'UTC', ?, ?, 0, ?, 0)
            """.trimIndent(),
            id,
            bondId,
            date,
            status,
            start,
            Timestamp.from(date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)),
            start,
        ) shouldBe 1
        return id
    }

    private fun entry(
        dayId: UUID,
        author: UUID,
        revealed: Boolean = false,
        erased: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        val at = Timestamp.from(AT)
        jdbc.update(
            """
            INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at,
                                 revealed_at, deleted_at)
            VALUES (?, ?, (SELECT bond_id FROM bond_days WHERE id = ?), ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? THEN ?::timestamptz ELSE NULL END, CASE WHEN ? THEN ?::timestamptz ELSE NULL END)
            """.trimIndent(),
            id,
            dayId,
            dayId,
            author,
            if (erased) null else "words",
            if (erased) {
                "DELETED"
            } else if (revealed) {
                "REVEALED"
            } else {
                "SUBMITTED"
            },
            at,
            at,
            at,
            revealed,
            at,
            erased,
            at,
        ) shouldBe 1
        return id
    }

    private fun mark(
        entryId: UUID,
        memberId: UUID,
    ) {
        jdbc.update("INSERT INTO entry_favourites (entry_id, member_id, created_at) VALUES (?, ?, now())", entryId, memberId) shouldBe 1
    }

    private companion object {
        val FIRST: LocalDate = LocalDate.of(2020, 1, 1)
        val AT: Instant = Instant.parse("2020-01-01T10:00:00Z")

        /** A page and the one row more that says whether another follows. */
        const val PAGE = 21

        /** The marks [seedHistory] gives the member the plan is read for: one every hundred days of 2,000. */
        const val MARKS = 20

        /** The one node type named: reading a table from end to end has been called this in every release. */
        const val SEQUENTIAL = "Seq Scan"

        val JSON: JsonMapper = JsonMapper.builder().build()

        /** A node's counts of rows it had to take, per loop: the ones it returned and the ones it read and dropped. */
        val TAKEN = listOf("Actual Rows", "Rows Removed by Filter", "Rows Removed by Index Recheck")
    }
}
