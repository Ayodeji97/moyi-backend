package com.moyi.gratitude.infra.database

import com.moyi.ai.api.EmbeddingSpace
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.SearchTerms
import com.moyi.gratitude.domain.SemanticMatch
import com.moyi.gratitude.infra.GratitudeTestApplication
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import javax.sql.DataSource

/**
 * NFR-033 for "Remember when"'s two rankings (ADR-0054, spec §3.6): each reads the bond's own
 * rows, never a table's. The meaning ranking joins its vectors through the bond's id, a constant,
 * as the search's meaning half does: without it Postgres was free to read every bond's vectors
 * (Task 7's finding for `matchingQuery`). Rows by hand, several bonds, one vector an entry.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class RankedCandidatesTest(
    @Autowired dataSource: DataSource,
    @Autowired private val json: ObjectMapper,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val me = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clear() {
        // As `WithdrawalRig.clear`, which every class of this suite runs: the vectors go with their entries.
        jdbc.execute("TRUNCATE TABLE ${EntryEmbeddings.tableOf(SPACE)}, entries, bond_days CASCADE")
    }

    @Test
    fun `the meaning ranking reads the bond's vectors, not the table's`() {
        val bonds = List(PLAN_BONDS) { UUID.randomUUID() }
        bonds.forEach { seed(it) }
        analyze()

        val plan = planOf(ArchiveDays.rankedByMeaningQuery(bonds.first(), me, semantic(), emptySet()))
        val onVectors = nodes(plan).filter { it["Relation Name"]?.asString() == EntryEmbeddings.tableOf(SPACE) }

        withClue(plan.toString()) {
            onVectors.shouldNotBeEmpty()
            onVectors.filter { it["Node Type"].asString() == "Seq Scan" }.shouldBeEmpty()
        }
    }

    @Test
    fun `the keyword ranking reads the bond's entries, not the table's`() {
        val bonds = List(PLAN_BONDS) { UUID.randomUUID() }
        bonds.forEach { seed(it) }
        analyze()

        val plan = planOf(ArchiveDays.rankedByWordsQuery(bonds.first(), me, SearchTerms.of("walk")!!, emptySet()))

        withClue(plan.toString()) {
            nodes(plan).filter { it["Relation Name"]?.asString() == "entries" && it["Node Type"].asString() == "Seq Scan" }.shouldBeEmpty()
        }
    }

    private fun analyze() = listOf("entries", "bond_days", EntryEmbeddings.tableOf(SPACE)).forEach { jdbc.execute("ANALYZE $it") }

    private fun planOf(query: Pair<String, List<Any>>): JsonNode =
        json.readTree(
            jdbc
                .query(
                    "EXPLAIN (FORMAT JSON) ${query.first}",
                    PreparedStatementSetter { s -> query.second.forEachIndexed { i, v -> s.setObject(i + 1, v) } },
                    RowMapper { rs, _ -> rs.getString(1) },
                ).single(),
        )[0]["Plan"]

    private fun nodes(node: JsonNode): List<JsonNode> = listOf(node) + node["Plans"]?.toList().orEmpty().flatMap(::nodes)

    /** [PLAN_ENTRIES] revealed entries of [bondId], one a day, each with a vector. As `SearchCandidatesTest` seeds. */
    private fun seed(bondId: UUID) {
        jdbc.update(
            """
            WITH days AS (
              INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at,
                                     closed_at, version)
              SELECT gen_random_uuid(), ?, date '2024-01-01' + i, 'REVEALED', 'UTC', now(), now(), 1, now(), now(), 0
              FROM generate_series(0, ? - 1) i
              RETURNING id, bond_id
            ), written AS (
              INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, text_search, status, created_at, intended_at,
                                   updated_at, revealed_at)
              SELECT gen_random_uuid(), d.id, d.bond_id, ?, 'thank you for the walk', 'thank you for the walk', 'REVEALED',
                     now(), now(), now(), now()
              FROM days d
              RETURNING id, bond_id
            )
            INSERT INTO ${EntryEmbeddings.tableOf(SPACE)} (entry_id, bond_id, embedding, text_hash, created_at)
            SELECT w.id, w.bond_id, v.embedding, '\x00', now()
            FROM written w,
                 LATERAL (SELECT array_agg(random() - 0.5)::vector(1536) AS embedding
                          FROM generate_series(1, ?) WHERE w.id IS NOT NULL) v
            """.trimIndent(),
            bondId,
            PLAN_ENTRIES,
            me,
            SPACE.dimensions,
        )
    }

    private fun semantic() = SemanticMatch(SPACE, FloatArray(SPACE.dimensions).also { it[0] = 1f }, 0.0)

    private companion object {
        const val PLAN_BONDS = 20
        const val PLAN_ENTRIES = 100
        val SPACE = EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL
    }
}
