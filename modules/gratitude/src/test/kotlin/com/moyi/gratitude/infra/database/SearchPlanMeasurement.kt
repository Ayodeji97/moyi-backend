package com.moyi.gratitude.infra.database

import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.SearchTerms
import com.moyi.gratitude.infra.GratitudeTestApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * NFR-033 ("no query whose cost grows with total table size rather than with the requested
 * page") and NFR-004 (search p95 ≤ 300 ms over 500k entries), asked of the search's own
 * statement (`ArchiveDays.matchingQuery`) as Postgres plans it (ADR-0039, the controller's
 * default 8). Not a test of the build: it seeds half a million rows. Run with
 * `MOYI_MEASURE=1 ./gradlew :modules:gratitude:test --tests "…SearchPlanMeasurement"`.
 *
 * One bond of two years, two entries a day (1,460 entries), and the rest of the table other
 * bonds of 500 entries each, at 100,000 rows and then at 500,000. Three words: one in every
 * entry (`thank`), one in an eighth (`lamp`), one in a single entry of the measured bond
 * (`zebra`). **The criterion:** no index scan in the plan returns more rows than the measured
 * bond has entries, at either size. A scan that returns more is reading other bonds' entries:
 * its cost grows with the table. **No sequential scan of `bond_days` or `entries`** at either
 * size, which reads every bond's rows by definition: the first version of this criterion
 * looked at index scans only, and a Hash Join over a Seq Scan of all of `bond_days` (49,980
 * rows at 100k, for `thank`) passed it unseen (task 2 review I2). And the warm execution time
 * at 500k is under 300 ms.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@EnabledIfEnvironmentVariable(named = "MOYI_MEASURE", matches = "1")
internal class SearchPlanMeasurement(
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
    }

    @Test
    fun `a search reads in proportion to its bond, not to the table`() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
        seed(bonds = listOf(BOND), days = TARGET_DAYS)
        seed(bonds = List((SMALL - TARGET_DAYS * 2) / OTHER_ENTRIES) { UUID.randomUUID() }, days = OTHER_ENTRIES / 2)
        val small = measure()
        seed(bonds = List((LARGE - SMALL) / OTHER_ENTRIES) { UUID.randomUUID() }, days = OTHER_ENTRIES / 2)
        val large = measure()

        println("C6b SEARCH MEASUREMENT 100k: $small")
        println("C6b SEARCH MEASUREMENT 500k: $large")
        // Every size and word judged before any assertion, so that a failure names all of them.
        val tableWide =
            listOf("100k" to small, "500k" to large).flatMap { (size, measured) ->
                measured.mapNotNull { (word, plan) ->
                    "$size $word: $plan".takeIf { plan.widestIndexScan > TARGET_DAYS * 2 || plan.seqScans.isNotEmpty() }
                }
            }
        withClue("plans that read beyond the measured bond: $tableWide") { tableWide shouldBe emptyList() }
        large.forEach { (word, plan) -> withClue("500k, $word: $plan") { (plan.milliseconds < NFR_004_MS) shouldBe true } }
    }

    private data class Plan(
        val buffers: Long,
        val widestIndexScan: Double,
        val seqScans: List<String>,
        val milliseconds: Double,
        val shape: String,
    )

    /**
     * On this day (task 3, the controller's note on review I2): `ArchiveDays.onThisDayQuery` over the same seed, for
     * a date with two earlier years in the measured bond. The same criterion: no index scan wider than the bond, no
     * sequential scan of `bond_days` or `entries`, under 300 ms at 500k.
     */
    @Test
    fun `on this day reads in proportion to its bond, not to the table`() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
        seed(bonds = listOf(BOND), days = TARGET_DAYS)
        seed(bonds = List((SMALL - TARGET_DAYS * 2) / OTHER_ENTRIES) { UUID.randomUUID() }, days = OTHER_ENTRIES / 2)
        val small = analyzed { explain(ArchiveDays.onThisDayQuery(BOND, MEMBER, ON_THIS_DAY, emptySet())) }
        seed(bonds = List((LARGE - SMALL) / OTHER_ENTRIES) { UUID.randomUUID() }, days = OTHER_ENTRIES / 2)
        val large = analyzed { explain(ArchiveDays.onThisDayQuery(BOND, MEMBER, ON_THIS_DAY, emptySet())) }

        println("C6b ON-THIS-DAY MEASUREMENT 100k: $small")
        println("C6b ON-THIS-DAY MEASUREMENT 500k: $large")
        val tableWide =
            listOf("100k" to small, "500k" to large).mapNotNull { (size, plan) ->
                "$size: $plan".takeIf { plan.widestIndexScan > TARGET_DAYS * 2 || plan.seqScans.isNotEmpty() }
            }
        withClue("plans that read beyond the measured bond: $tableWide") { tableWide shouldBe emptyList() }
        withClue("500k: $large") { (large.milliseconds < NFR_004_MS) shouldBe true }
    }

    private fun <T> analyzed(measured: () -> T): T {
        jdbc.execute("ANALYZE entries")
        jdbc.execute("ANALYZE bond_days")
        return measured()
    }

    private fun measure(): Map<String, Plan> =
        analyzed {
            WORDS.associateWith { word -> explain(ArchiveDays.matchingQuery(BOND, MEMBER, SearchTerms.of(word)!!, null, PAGE, emptySet())) }
        }

    private fun explain(query: Pair<String, List<Any>>): Plan {
        val (sql, arguments) = query
        val explain = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) $sql"
        val bind = PreparedStatementSetter { s -> arguments.forEachIndexed { i, v -> s.setObject(i + 1, v) } }
        // Twice first, so that what is measured is a warm run.
        repeat(2) { jdbc.query(explain, bind, RowCallbackHandler { }) }
        val text = jdbc.query(explain, bind, RowMapper { rs, _ -> rs.getString(1) }).single()
        val root = json.readTree(text)[0]
        val plan = root["Plan"]
        return Plan(
            buffers = plan["Shared Hit Blocks"].asLong() + plan["Shared Read Blocks"].asLong(),
            widestIndexScan = indexScans(plan).maxOfOrNull { it["Actual Rows"].asDouble() } ?: 0.0,
            seqScans =
                nodes(plan)
                    .filter { it["Node Type"].asString() == "Seq Scan" && it["Relation Name"]?.asString() in SEQ_SCAN_FORBIDDEN }
                    .map { "Seq Scan on ${it["Relation Name"].asString()} (${it["Actual Rows"].asDouble()} rows)" },
            milliseconds = root["Execution Time"].asDouble(),
            shape = indexScans(plan).joinToString { "${it["Node Type"].asString()} on ${it["Index Name"]?.asString()}" },
        )
    }

    private fun indexScans(node: JsonNode): List<JsonNode> = nodes(node).filter { it["Node Type"].asString().contains("Index") }

    private fun nodes(node: JsonNode): List<JsonNode> = listOf(node) + node["Plans"]?.toList().orEmpty().flatMap(::nodes)

    /** For each bond, [days] revealed days from 2024-01-01, two entries a day, every one holding `thank`. */
    private fun seed(
        bonds: List<UUID>,
        days: Int,
    ) {
        bonds.chunked(CHUNK).forEach { chunk ->
            jdbc.update(
                """
                WITH days AS (
                  INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at,
                                         closed_at, version)
                  SELECT gen_random_uuid(), b, date '2024-01-01' + i, 'REVEALED', 'UTC',
                         (date '2024-01-01' + i)::timestamptz, (date '2024-01-01' + i + 1)::timestamptz, 0,
                         (date '2024-01-01' + i)::timestamptz, (date '2024-01-01' + i + 1)::timestamptz, 0
                  FROM unnest(string_to_array(?, ',')::uuid[]) b, generate_series(0, ? - 1) i
                  RETURNING id, bond_id, date
                )
                INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, text_search, status, created_at, intended_at,
                                     updated_at, revealed_at)
                SELECT gen_random_uuid(), d.id, d.bond_id, CASE WHEN s = 1 THEN ?::uuid ELSE gen_random_uuid() END, w, w, 'REVEALED',
                       now(), now(), now(), now()
                FROM days d, generate_series(1, 2) s,
                     LATERAL (SELECT 'thank you for the ' ||
                                     (ARRAY['lamp', 'walk', 'tea', 'rain', 'song', 'bread', 'map', 'hill'])[1 + abs(hashtext(d.id::text || s)) % 8] ||
                                     CASE WHEN d.bond_id = ?::uuid AND d.date = date '2024-06-01' AND s = 1 THEN ' and a zebra' ELSE '' END AS w) words
                """.trimIndent(),
                chunk.joinToString(","),
                days,
                MEMBER,
                BOND,
            )
        }
    }

    private companion object {
        val BOND: UUID = UUID.fromString("0199f000-0000-7000-8000-0000000000b1")
        val MEMBER: UUID = UUID.fromString("0199f000-0000-7000-8000-0000000000a1")
        const val TARGET_DAYS = 730
        const val OTHER_ENTRIES = 500
        const val SMALL = 100_000
        const val LARGE = 500_000
        const val PAGE = 21
        const val CHUNK = 50
        const val NFR_004_MS = 300.0
        val WORDS = listOf("thank", "lamp", "zebra")
        val ON_THIS_DAY: LocalDate = LocalDate.of(2026, 6, 1)
        val SEQ_SCAN_FORBIDDEN = setOf("bond_days", "entries")
    }
}
