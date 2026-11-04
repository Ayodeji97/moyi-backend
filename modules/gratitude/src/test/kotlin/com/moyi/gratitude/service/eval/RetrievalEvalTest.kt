package com.moyi.gratitude.service.eval

import com.moyi.ai.api.EmbeddingSpace
import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.domain.SearchTerms
import com.moyi.gratitude.domain.SemanticMatch
import com.moyi.gratitude.infra.FakeEmbeddingPort
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryEmbeddings
import com.moyi.gratitude.service.GetDays
import com.moyi.gratitude.web.ArchiveRig
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.gratitude.web.EntryState
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.fail
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.sqrt

/**
 * The retrieval eval (Task 9, spec §4): the golden set's bonds and entries put down as revealed,
 * each provider's **stored** vectors written with [EntryEmbeddings.upsert], and the **real**
 * [GetDays.search] and [GetDays.remember] asked every golden query, with the query's stored
 * vector as [FakeEmbeddingPort]'s answer. No provider is reached.
 *
 * For each space that has a vectors file it prints, and gates:
 * - "Remember when" **recall@10** and **MRR**, each at least its floor in `eval/floors.json`
 *   (the first measured run less 0.02);
 * - the search box's **precision at the shipped `max-distance`** (app's `application.yml`, never
 *   this module's test value), at least 0.9;
 * and prints, ungated, the largest `max-distance` on a 0.01 grid whose precision is at least 0.9,
 * and for each no-relevant query how many days "remember" answered and the nearest distance in its
 * bond: remember has no floor, so the owner rules on these numbers.
 *
 * **A space with no vectors file is skipped**, not failed, until the owner's provider run
 * (`tools/embedding-eval`) commits one. That the pipeline measures what it claims to is the
 * self-check's to show, with or without those files: hand-made vectors with known answers,
 * through every step above.
 *
 * Every day here holds one entry, so the day the API answers with is the golden entry it holds.
 * Prints keys, never texts.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class RetrievalEvalTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired json: ObjectMapper,
    @Autowired private val clock: MutableClock,
    @Autowired private val fake: FakeEmbeddingPort,
    @Autowired private val embeddings: EntryEmbeddings,
    @Autowired private val getDays: GetDays,
    @Autowired private val access: BondAccess,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        fake.clear()
    }

    @AfterEach
    fun clear() {
        rig.bonds.clear()
        fake.clear()
        users.clear()
        clock.set(READ_AT)
    }

    /**
     * The harness, on twelve hand-made vectors whose answers are known: entry `s<i>` lies at cosine
     * distance 0.05·i − 0.005 from axis 0, each on an axis of its own besides. `p1` and `p2` are
     * axis 0, `p4` halfway between axes 0 and 1 (the same order, every distance larger), and `p3`,
     * axis 1, is no-relevant: every entry is at distance 1 from it.
     *
     * - remember: `p1` finds s1-s3 first (recall 1, RR 1); `p2`'s s11 is eleventh (recall 2/3,
     *   RR 1); `p4`'s s4 is fourth (recall 1, RR 1/4). Recall@10 8/9, MRR 0.75.
     * - search at 0.12: `p1` and `p2` each get s1 and s2, all relevant: 1.0. At 0.20: s1-s4 each,
     *   five of eight relevant: 0.625. The largest grid threshold at 0.9 is 0.14 (s3 is at 0.145).
     */
    @Test
    fun `the harness measures hand-made vectors exactly`() {
        val measured = measure(SELF_CHECK, SELF_CHECK_VECTORS, FakeEmbeddingPort.DEFAULT_SPACE, shipped = 0.12)

        measured.recallAt10 shouldBe (8.0 / 9 plusOrMinus EPSILON)
        measured.mrr shouldBe (0.75 plusOrMinus EPSILON)
        measured.precisionAtShipped shouldBe (1.0 plusOrMinus EPSILON)
        measured.precisionAt.getValue(0.2) shouldBe (0.625 plusOrMinus EPSILON)
        measured.largestThreshold shouldBe 0.14
        // The probe: remember has no floor, so a query near nothing still gets its ten days.
        measured.probes.single().key shouldBe "p3"
        measured.probes.single().days shouldBe RetrievalMetrics.K
        measured.probes.single().nearest shouldBe (1.0 plusOrMinus EPSILON)
    }

    @TestFactory
    fun `each space with stored vectors meets its floors`(): List<DynamicTest> = spaces(GoldenSet.storedVectors(), floors())

    /**
     * A floor for a space with no vectors is a lost vectors file, not a space never measured: it
     * fails, where a skip would let the gate go quiet unnoticed.
     */
    @Test
    fun `a space with a floor and no vectors fails, and one with neither is skipped`() {
        val plan = spaces(stored = emptyMap(), floors = mapOf(EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL to Floor(0.5, 0.5)))
        val outcomes = plan.associate { it.displayName to runCatching { it.executable.execute() }.exceptionOrNull() }

        outcomes.getValue("OPENAI_TEXT_EMBEDDING_3_SMALL").shouldBeInstanceOf<AssertionFailedError>().message shouldBe
            "eval/floors.json gates OPENAI_TEXT_EMBEDDING_3_SMALL, but it has no vectors file: run tools/embedding-eval generate"
        outcomes.getValue("VOYAGE_3_5").shouldBeInstanceOf<TestAbortedException>()
    }

    /** One dynamic test per space: gated with its vectors, failed with a floor and none, skipped with neither. */
    private fun spaces(
        stored: Map<EmbeddingSpace, Map<String, FloatArray>>,
        floors: Map<EmbeddingSpace, Floor>,
    ): List<DynamicTest> =
        EmbeddingSpace.entries.map { space ->
            DynamicTest.dynamicTest(space.name) {
                val vectors = stored[space]
                if (vectors == null && space in floors) {
                    fail("eval/floors.json gates $space, but it has no vectors file: run tools/embedding-eval generate")
                }
                assumeTrue(vectors != null, "no vectors for $space: run tools/embedding-eval generate")
                gate(space, checkNotNull(vectors), floors)
            }
        }

    private fun gate(
        space: EmbeddingSpace,
        vectors: Map<String, FloatArray>,
        floors: Map<EmbeddingSpace, Floor>,
    ) {
        val shipped = shippedMaxDistance(space)
        val measured = measure(GoldenSet.load(), vectors, space, shipped)
        report(space, measured)
        if (shipped == null) fail("$MAX_DISTANCE_KEY.$space is not set in app's application.yml: set it from the printed threshold")
        val floor = floors[space] ?: fail("eval/floors.json has no floor for $space: write the printed recall and MRR, less 0.02")
        withClue("remember recall@10 for $space") { measured.recallAt10 shouldBeGreaterThanOrEqual floor.recall }
        withClue("remember MRR for $space") { measured.mrr shouldBeGreaterThanOrEqual floor.mrr }
        withClue("search precision at the shipped max-distance for $space") {
            checkNotNull(measured.precisionAtShipped) { "nothing was found at the shipped max-distance" } shouldBeGreaterThanOrEqual
                PRECISION_FLOOR
        }
    }

    // ---- the pipeline ----

    /** What [measure] found: [precisionAt] is the grid's, [precisionAtShipped] at the shipped value if there is one. */
    class Measured(
        val recallAt10: Double,
        val mrr: Double,
        val precisionAt: Map<Double, Double?>,
        val shipped: Double?,
        val precisionAtShipped: Double?,
        val largestThreshold: Double?,
        val probes: List<Probe>,
    )

    /** A no-relevant query: how many days remember answered it with, and the nearest entry's distance in its bond. */
    class Probe(
        val key: String,
        val days: Int,
        val nearest: Double,
    )

    /** One reader per bond, and the golden entry each of its dates holds. */
    private class Seeded(
        val readers: Map<String, BondMembership>,
        val entryOn: Map<Pair<String, LocalDate>, String>,
    )

    private fun measure(
        golden: GoldenSet,
        vectors: Map<String, FloatArray>,
        space: EmbeddingSpace,
        shipped: Double?,
    ): Measured {
        val missing = (golden.bonds.flatMap { bond -> bond.entries.map { it.key } } + golden.queries.map { it.key }) - vectors.keys
        check(missing.isEmpty()) { "the $space vectors have no vector for ${missing.size} keys, e.g. ${missing.first()}: regenerate them" }
        val seeded = seed(golden, vectors, space)
        fake.space = space
        fake.table = golden.queries.associate { it.text to vectors.getValue(it.key) }

        val asked = golden.queries.associateWith { query -> fake.embed(listOf(query.text)).single() }
        val answerable = golden.queries.filter { it.relevant.isNotEmpty() }
        val remembered = golden.queries.associateWith { remember(seeded, it, asked.getValue(it), space) }
        val grid = (1..GRID_STEPS).map { it / GRID_STEPS.toDouble() }
        val precisionAt = grid.associateWith { precision(seeded, golden, asked, space, it) }
        return Measured(
            recallAt10 = answerable.map { RetrievalMetrics.recallAt(RetrievalMetrics.K, remembered.getValue(it), it.relevant) }.average(),
            mrr = answerable.map { RetrievalMetrics.reciprocalRank(RetrievalMetrics.K, remembered.getValue(it), it.relevant) }.average(),
            precisionAt = precisionAt,
            shipped = shipped,
            precisionAtShipped = shipped?.let { precision(seeded, golden, asked, space, it) },
            largestThreshold = RetrievalMetrics.largestThreshold(precisionAt, PRECISION_FLOOR),
            probes =
                golden.queries.filter { it.relevant.isEmpty() }.map { query ->
                    Probe(query.key, remembered.getValue(query).size, nearest(seeded, query, asked.getValue(query), space))
                },
        )
    }

    /** Every bond paired, every entry put down revealed on its own day, and its stored vector written over what `text_search` holds. */
    private fun seed(
        golden: GoldenSet,
        vectors: Map<String, FloatArray>,
        space: EmbeddingSpace,
    ): Seeded {
        rig.bonds.clear()
        val readers = mutableMapOf<String, BondMembership>()
        val entryOn = mutableMapOf<Pair<String, LocalDate>, String>()
        golden.bonds.forEach { bond ->
            val people = bond.authors.associateWith { users.verified(it) }
            clock.set(BOND_CREATED)
            val bondId = rig.bonds.pair(people.getValue(bond.authors[0]), people.getValue(bond.authors[1]))
            val members = people.mapValues { (_, user) -> rig.bonds.memberId(bondId, user) }
            bond.entries.forEach { entry ->
                val at = entry.date.atTime(WRITTEN_AT_HOUR, 0).toInstant(java.time.ZoneOffset.UTC)
                val day = rig.insertDay(bondId, entry.date, "REVEALED")
                val id = checkNotNull(rig.insertEntry(day, bondId, members.getValue(entry.author), EntryState.REVEALED, entry.text, at))
                // What the projection holds, read back: a future projection change must not refuse every row.
                val projected = jdbc.queryForObject("SELECT text_search FROM entries WHERE id = ?", String::class.java, id)!!
                val written =
                    embeddings.upsert(
                        space,
                        id,
                        UUID.fromString(bondId),
                        vectors.getValue(entry.key),
                        EntryEmbeddings.hashOf(projected),
                        at,
                    )
                check(written) { "${entry.key}'s vector was refused" }
                entryOn[bond.key to entry.date] = entry.key
            }
            clock.set(READ_AT)
            readers[bond.key] = access.membershipOf(people.getValue(bond.authors[0]), UUID.fromString(bondId))
        }
        return Seeded(readers, entryOn)
    }

    /** "Remember when", at its default limit, as the golden keys of the days it answered, in order. */
    private fun remember(
        seeded: Seeded,
        query: GoldenSet.Query,
        vector: FloatArray,
        space: EmbeddingSpace,
    ): List<String> {
        // The ranking never reads the cut-off (RememberTest's calmLight): any value will do.
        val meaning = SemanticMatch(EntryEmbeddings.vectorTableOf(space), vector, 0.0)
        val days = getDays.remember(seeded.readers.getValue(query.bond), terms(query), meaning, RetrievalMetrics.K)
        return days.map { seeded.entryOn.getValue(query.bond to it.date) }
    }

    /** The search box at [maxDistance], every page, pooled over every golden query. */
    private fun precision(
        seeded: Seeded,
        golden: GoldenSet,
        asked: Map<GoldenSet.Query, FloatArray>,
        space: EmbeddingSpace,
        maxDistance: Double,
    ): Double? =
        RetrievalMetrics.pooledPrecision(
            golden.queries.map { query ->
                val meaning = SemanticMatch(EntryEmbeddings.vectorTableOf(space), asked.getValue(query), maxDistance)
                val found = mutableListOf<String>()
                var before: LocalDate? = null
                do {
                    val page = getDays.search(seeded.readers.getValue(query.bond), terms(query), before, GetDays.MAX_LIMIT, meaning)
                    page.days.forEach { found += seeded.entryOn.getValue(query.bond to it.date) }
                    before = page.next
                } while (before != null)
                found to query.relevant
            },
        )

    /** The nearest stored vector in [query]'s bond, by the distance the queries use (pgvector's cosine). */
    private fun nearest(
        seeded: Seeded,
        query: GoldenSet.Query,
        vector: FloatArray,
        space: EmbeddingSpace,
    ): Double =
        jdbc.queryForObject(
            "SELECT min(embedding <=> ?::vector) FROM ${EntryEmbeddings.tableOf(space)} WHERE bond_id = ?",
            Double::class.java,
            EntryEmbeddings.vectorLiteral(vector),
            seeded.readers.getValue(query.bond).bondId,
        )!!

    private fun terms(query: GoldenSet.Query) = checkNotNull(SearchTerms.of(query.text)) { "${query.key} is not a search" }

    private fun report(
        space: EmbeddingSpace,
        measured: Measured,
    ) {
        println("[retrieval-eval] $space")
        println("[retrieval-eval]   remember recall@10         ${"%.4f".format(measured.recallAt10)}")
        println("[retrieval-eval]   remember MRR               ${"%.4f".format(measured.mrr)}")
        val atShipped =
            when {
                measured.shipped == null -> "(no shipped max-distance)"
                measured.precisionAtShipped == null -> "nothing found at ${measured.shipped}"
                else -> "%.4f at %s".format(measured.precisionAtShipped, measured.shipped)
            }
        println("[retrieval-eval]   search precision, shipped  $atShipped")
        println("[retrieval-eval]   largest max-distance at precision >= 0.9: ${measured.largestThreshold ?: "none on the grid"}")
        measured.precisionAt.forEach { (threshold, precision) ->
            println("[retrieval-eval]     precision at %.2f: %s".format(threshold, precision?.let { "%.4f".format(it) } ?: "nothing found"))
        }
        println("[retrieval-eval]   no-relevant queries: days remember answered, nearest distance in the bond")
        measured.probes.forEach { println("[retrieval-eval]     ${it.key}: ${it.days} days, nearest %.4f".format(it.nearest)) }
    }

    private class Floor(
        val recall: Double,
        val mrr: Double,
    )

    /** `eval/floors.json`: `{ "<space>": { "recall": …, "mrr": … } }`, or nothing until the first run writes it. */
    private fun floors(): Map<EmbeddingSpace, Floor> {
        val file = GoldenSet.directory.resolve("floors.json")
        if (!file.exists()) return emptyMap()
        val tree = ObjectMapper().readTree(file.readText())
        return tree.properties().associate { (space, floor) ->
            EmbeddingSpace.valueOf(space) to Floor(floor["recall"].asDouble(), floor["mrr"].asDouble())
        }
    }

    /**
     * `moyi.gratitude.semantic-search.max-distance.<space>` as **app's** `application.yml` ships it,
     * read by path as `ProductionStartsWithoutMediaSecretsTest` reads `deploy.yml`: this module's
     * test value (0.35) is a fake's, not a measurement.
     */
    private fun shippedMaxDistance(space: EmbeddingSpace): Double? {
        var node: Any? = Yaml().load<Map<String, Any?>>(File(APP_CONFIG).readText())
        (MAX_DISTANCE_KEY.split('.') + space.name).forEach { node = (node as? Map<*, *>)?.get(it) }
        return (node as? Number)?.toDouble()
    }

    private companion object {
        const val APP_CONFIG = "../../app/src/main/resources/application.yml"
        const val MAX_DISTANCE_KEY = "moyi.gratitude.semantic-search.max-distance"
        const val PRECISION_FLOOR = 0.9
        const val GRID_STEPS = 100
        const val EPSILON = 1e-6
        const val WRITTEN_AT_HOUR = 10
        val BOND_CREATED: Instant = Instant.parse("2025-12-01T10:00:00Z")
        val READ_AT: Instant = Instant.parse("2026-09-15T10:00:00Z")

        const val SELF_CHECK_ENTRIES = 12
        val SELF_CHECK: GoldenSet =
            GoldenSet(
                listOf(
                    GoldenSet.Bond(
                        "s",
                        (1..SELF_CHECK_ENTRIES).map {
                            GoldenSet.Entry("s$it", if (it % 2 == 1) "sam" else "sky", LocalDate.of(2026, 1, it), "self check entry $it")
                        },
                    ),
                ),
                listOf(
                    GoldenSet.Query("p1", "s", "probe alpha", setOf("s1", "s2", "s3")),
                    GoldenSet.Query("p2", "s", "probe beta", setOf("s1", "s2", "s11")),
                    GoldenSet.Query("p3", "s", "probe gamma", emptySet()),
                    GoldenSet.Query("p4", "s", "probe delta", setOf("s4")),
                ),
            )

        val SELF_CHECK_VECTORS: Map<String, FloatArray> =
            run {
                val dimensions = FakeEmbeddingPort.DEFAULT_SPACE.dimensions

                fun vector(vararg axes: Pair<Int, Double>) =
                    FloatArray(dimensions).also { v -> axes.forEach { (i, x) -> v[i] = x.toFloat() } }
                val entries =
                    (1..SELF_CHECK_ENTRIES).associate {
                        val cosine = 1.0 - 0.05 * it + 0.005
                        "s$it" to vector(0 to cosine, it + 1 to sqrt(1 - cosine * cosine))
                    }
                val half = sqrt(0.5)
                entries +
                    mapOf(
                        "p1" to vector(0 to 1.0),
                        "p2" to vector(0 to 1.0),
                        "p3" to vector(1 to 1.0),
                        "p4" to vector(0 to half, 1 to half),
                    )
            }
    }
}
