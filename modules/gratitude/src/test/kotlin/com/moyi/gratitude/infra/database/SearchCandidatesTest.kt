package com.moyi.gratitude.infra.database

import com.moyi.ai.api.EmbeddingSpace
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.SearchTerms
import com.moyi.gratitude.domain.SemanticMatch
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.web.ArchiveRig
import com.moyi.gratitude.web.EntryState
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * `ArchiveDays.matching` asks erasure of the row that matched (`LIVE_ROW`), and does not rely
 * on the projection having been emptied (final review, concurrency I1).
 *
 * A build from before V25 does not map `text_search`, so an entry it erases keeps its words
 * there and in `search_vector`, during a blue/green overlap or after a rollback across V25.
 * The gate would still drop such a day, so no HTTP answer can show the difference: only the
 * statement can. Rows by hand; no bond is needed for a statement that takes ids.
 *
 * With a [SemanticMatch] (ADR-0054, spec §3.5), the meaning half is asked of the same row,
 * inside the same gate: the partner's unrevealed entry, an erased one and a withdrawn author's
 * match nothing by meaning either, and the vectors are read through their bond, never the table.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
internal class SearchCandidatesTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired dataSource: DataSource,
    @Autowired private val json: ObjectMapper,
    @Autowired private val archive: ArchiveDays,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)
    private val bond = UUID.randomUUID().toString()
    private val me = UUID.randomUUID()
    private val partner = UUID.randomUUID()

    @BeforeEach
    @AfterEach
    fun clear() {
        rig.bonds.clear()
    }

    @Test
    fun `an erased entry whose projection an older build left in place is never a match`() {
        val live = rig.insertDay(bond, LocalDate.parse("2026-09-10"), "REVEALED")
        rig.insertEntry(live, bond, me, EntryState.REVEALED, "the lamp on the stairs", at("2026-09-10"))
        val erased = rig.insertDay(bond, LocalDate.parse("2026-09-12"), "REVEALED")
        listOf(EntryState.ERASED_AFTER_REVEAL, EntryState.ERASED_BEFORE_REVEAL).forEach { state ->
            val id = rig.insertEntry(erased, bond, me, state, "", at("2026-09-12"))!!
            // What an older build's erasure leaves: text gone, the projection (and so the vector) not.
            jdbc.update("UPDATE entries SET text_search = 'the lamp in the hall' WHERE id = ?", id) shouldBe 1
        }
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_day_id = ? AND search_vector @@ plainto_tsquery('simple', 'lamp')",
            Long::class.java,
            erased,
        ) shouldBe 2L

        archive.matching(UUID.fromString(bond), me, lamp(), null, LIMIT, emptySet()).map { it.date.toString() } shouldBe
            listOf("2026-09-10")
    }

    @Test
    fun `either mark of an erasure is enough`() {
        val day = rig.insertDay(bond, LocalDate.parse("2026-09-12"), "REVEALED")
        val deletedAt = rig.insertEntry(day, bond, partner, EntryState.REVEALED, "the lamp", at("2026-09-12"))!!
        val deletedStatus = rig.insertEntry(day, bond, me, EntryState.REVEALED, "the lamp", at("2026-09-12"))!!
        jdbc.update("UPDATE entries SET text = NULL, deleted_at = now() WHERE id = ?", deletedAt) shouldBe 1
        jdbc.update("UPDATE entries SET text = NULL, status = 'DELETED' WHERE id = ?", deletedStatus) shouldBe 1

        archive.matching(UUID.fromString(bond), me, lamp(), null, LIMIT, emptySet()) shouldBe emptyList()
    }

    @Test
    fun `a meaning match is asked of the row that matched, inside the gate`() {
        val days =
            listOf("mine", "unrevealed", "erased", "withdrawn", "far").withIndex().associate { (index, name) ->
                name to rig.insertDay(bond, LocalDate.parse("2026-09-1$index"), "REVEALED")
            }
        val stranger = UUID.randomUUID()
        near(rig.insertEntry(days.getValue("mine"), bond, me, EntryState.REVEALED, "a quiet glow", at("2026-09-10"))!!)
        near(rig.insertEntry(days.getValue("unrevealed"), bond, partner, EntryState.LIVE_UNREVEALED, "a quiet glow", at("2026-09-11"))!!)
        val erased = rig.insertEntry(days.getValue("erased"), bond, me, EntryState.ERASED_AFTER_REVEAL, "", at("2026-09-12"))!!
        near(erased) // What an older build's erasure would leave: the row's vector, its words gone.
        near(rig.insertEntry(days.getValue("withdrawn"), bond, stranger, EntryState.REVEALED, "a quiet glow", at("2026-09-13"))!!)
        embed(rig.insertEntry(days.getValue("far"), bond, me, EntryState.REVEALED, "a quiet glow", at("2026-09-14"))!!, FAR)

        fun found(withdrawn: Set<UUID>) =
            archive.matching(UUID.fromString(bond), me, lamp(), null, LIMIT, withdrawn, semantic()).map { it.date.toString() }

        // None holds "lamp": every day found here was found by meaning.
        found(emptySet()) shouldBe listOf("2026-09-13", "2026-09-10")
        found(setOf(stranger)) shouldBe listOf("2026-09-10")
        archive.matching(UUID.fromString(bond), me, lamp(), null, LIMIT, emptySet()) shouldBe emptyList()
    }

    /** NFR-033 for the meaning half: the vectors are read through their entry or their bond, never by scanning the table. */
    @Test
    fun `the meaning half reads the bond's vectors, not the table's`() {
        val bonds = List(PLAN_BONDS) { UUID.randomUUID() }
        bonds.forEach { seed(it) }
        listOf("entries", "bond_days", EntryEmbeddings.tableOf(SPACE)).forEach { jdbc.execute("ANALYZE $it") }

        val (sql, arguments) = ArchiveDays.matchingQuery(bonds.first(), me, lamp(), null, LIMIT, emptySet(), semantic())
        val plan =
            json.readTree(
                jdbc
                    .query(
                        "EXPLAIN (FORMAT JSON) $sql",
                        PreparedStatementSetter { s -> arguments.forEachIndexed { i, v -> s.setObject(i + 1, v) } },
                        RowMapper { rs, _ -> rs.getString(1) },
                    ).single(),
            )[0]["Plan"]
        val onVectors = nodes(plan).filter { it["Relation Name"]?.asString() == EntryEmbeddings.tableOf(SPACE) }

        withClue(plan.toString()) {
            onVectors.shouldNotBeEmpty()
            onVectors.filter { it["Node Type"].asString() == "Seq Scan" }.shouldBeEmpty()
            // A bitmap scan names the table on its heap node and the index on its child.
            nodes(plan).mapNotNull { it["Index Name"]?.asString() }.any { it in VECTOR_INDEXES } shouldBe true
        }
    }

    private fun nodes(node: JsonNode): List<JsonNode> = listOf(node) + node["Plans"]?.toList().orEmpty().flatMap(::nodes)

    /** [PLAN_ENTRIES] revealed entries of [bondId], one a day, each with a vector. */
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
                 -- Correlated through WHERE, so that each entry gets its own vector.
                 LATERAL (SELECT array_agg(random() - 0.5)::vector(1536) AS embedding
                          FROM generate_series(1, ?) WHERE w.id IS NOT NULL) v
            """.trimIndent(),
            bondId,
            PLAN_ENTRIES,
            me,
            SPACE.dimensions,
        )
    }

    private fun near(entry: UUID) = embed(entry, NEAR)

    private fun embed(
        entry: UUID,
        vector: FloatArray,
    ) {
        jdbc.update(
            "INSERT INTO ${EntryEmbeddings.tableOf(SPACE)} (entry_id, bond_id, embedding, text_hash, created_at) " +
                "VALUES (?, ?::uuid, ?::vector, '\\x00', now())",
            entry,
            bond,
            EntryEmbeddings.vectorLiteral(vector),
        ) shouldBe 1
    }

    private fun semantic() = SemanticMatch(SPACE, NEAR, MAX_DISTANCE)

    private fun lamp() = SearchTerms.of("lamp")!!

    private fun at(date: String) = Instant.parse("${date}T10:00:00Z")

    private companion object {
        const val LIMIT = 10
        const val MAX_DISTANCE = 0.35
        const val PLAN_BONDS = 3
        const val PLAN_ENTRIES = 500
        val SPACE = EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL
        val NEAR = FloatArray(SPACE.dimensions).also { it[0] = 1f }
        val FAR = FloatArray(SPACE.dimensions).also { it[1] = 1f }
        val VECTOR_INDEXES = setOf("entry_embeddings_openai_1536_bond_idx", "entry_embeddings_openai_1536_pkey")
    }
}
