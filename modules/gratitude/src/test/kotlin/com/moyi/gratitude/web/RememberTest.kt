package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.EmbeddingSweep
import com.moyi.gratitude.domain.SearchTerms
import com.moyi.gratitude.domain.SemanticMatch
import com.moyi.gratitude.infra.FakeEmbeddingPort
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.ArchiveDays
import com.moyi.gratitude.infra.database.EntryEmbeddings
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * `GET /bonds/{bondId}/remember` (ADR-0054, spec §3.6): the days of a bond's entries ranked by
 * relevance, the keyword ranking and the vector ranking fused by RRF, **each list gated as search
 * is**, and every day read through the archive's gate before it is shown.
 *
 * The provider is [FakeEmbeddingPort] with [NEAR] as its table; every other text gets a seeded
 * random unit vector, about 1.0 from anything. Entries are embedded by the real sweep ([embedAll]).
 * The vector ranking has no cut-off: every entry the caller may read is in it, nearest first.
 *
 * Meter assertions read a delta: the registry is a bean of a context other classes share.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class RememberTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val sweep: EmbeddingSweep,
    @Autowired private val fake: FakeEmbeddingPort,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val access: BondAccess,
    @Autowired private val archive: ArchiveDays,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var eve: UUID

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        fake.clear()
        fake.table = NEAR
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        eve = users.verified("Eve")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.bonds.clear()
        fake.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `remember ranks by relevance, not by date`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, SOUP, "x")
        bothWrite(bond, DAY_ONE.plusDays(5), "soup", "y")
        embedAll()
        val calls = fake.calledInTransaction.size

        remembered(ada, bond, SICK).first() shouldBe "2026-09-15"

        // `q` was embedded once, as typed, with no transaction open.
        fake.texts shouldContain SICK
        fake.calledInTransaction.drop(calls) shouldBe listOf(false)
    }

    @Test
    fun `an entry both rankings hold comes before one that only meaning holds`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        val used = semantic("used")

        // The lamp is the nearest by meaning, alone; the calm light is a keyword match and somewhere in the meaning list.
        remembered(ada, bond, "calm light") shouldBe listOf("2026-09-16", "2026-09-15")

        semantic("used") - used shouldBe 1.0
        val body = rig.page(ada, bond, mapOf("q" to "calm light"), route = "remember")
        body.propertyNames().toSet() shouldBe setOf("items")
        // Each day is the archive's day, whole.
        body["items"][0] shouldBe rig.page(ada, bond)["items"][0]
        body["items"][1] shouldBe rig.page(ada, bond)["items"][1]
    }

    /**
     * Review focus 1, T-01: the partner's words before the reveal are in neither ranking. Asked of
     * the rankings as well as of the answer: the gate behind them would drop the day too, so only
     * the rankings show that the rows' own gate holds (and an unrevealed entry cannot take a place
     * among the fifty).
     */
    @Test
    fun `the partner's unrevealed entry is never remembered, until the reveal`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE.minusDays(1), "bread again", "nothing")
        clock.set(at(DAY_ONE))
        val lamp = UUID.fromString(rig.bonds.submit(bea, bond, LAMP))
        embedAll()
        val bondId = UUID.fromString(bond)
        val adas = access.membershipOf(ada, bondId).memberId
        val beas = access.membershipOf(bea, bondId).memberId

        remembered(ada, bond, "calm light") shouldBe listOf("2026-09-14")
        rig.page(ada, bond, mapOf("q" to "calm light"), route = "remember").toString() shouldNotContain LAMP
        archive.rankedByWords(bondId, adas, SearchTerms.of("lamp")!!, emptySet()) shouldNotContain lamp
        archive.rankedByMeaning(bondId, adas, calmLight(), emptySet()) shouldNotContain lamp
        // Not vacuous: the vector is there and is the nearest, for its author.
        remembered(bea, bond, "calm light").first() shouldBe "2026-09-15"
        archive.rankedByWords(bondId, beas, SearchTerms.of("lamp")!!, emptySet()) shouldContain lamp
        archive.rankedByMeaning(bondId, beas, calmLight(), emptySet()).first() shouldBe lamp

        rig.bonds.submit(ada, bond, "soup again")
        remembered(ada, bond, "calm light").first() shouldBe "2026-09-15"
        archive.rankedByMeaning(bondId, adas, calmLight(), emptySet()).first() shouldBe lamp
    }

    /** Review focus 5: the withdrawn-author filter applies to both ranked lists, before their `LIMIT`. */
    @Test
    fun `a withdrawn author's entry is in neither ranking, and is never remembered`() {
        val bond = pair()
        val written = bothWrite(bond, DAY_ONE, LAMP, "bread again")
        embedAll()
        val bondId = UUID.fromString(bond)
        val adas = access.membershipOf(ada, bondId).memberId
        val beas = access.membershipOf(bea, bondId)
        val lamp = UUID.fromString(written)
        val meaning = calmLight()
        // Not vacuous: with nobody withdrawn, each list holds the lamp, and the answer carries its words.
        archive.rankedByWords(bondId, beas.memberId, SearchTerms.of("lamp")!!, emptySet()) shouldContain lamp
        archive.rankedByMeaning(bondId, beas.memberId, meaning, emptySet()).first() shouldBe lamp
        rig.page(bea, bond, mapOf("q" to "quiet lamp"), route = "remember").toString() shouldContain LAMP

        rig.bonds.block(ada, bond, true)

        archive.rankedByWords(bondId, beas.memberId, SearchTerms.of("lamp")!!, setOf(adas)) shouldNotContain lamp
        archive.rankedByMeaning(bondId, beas.memberId, meaning, setOf(adas)) shouldNotContain lamp
        rig.page(bea, bond, mapOf("q" to "quiet lamp"), route = "remember").toString() shouldNotContain LAMP
        rig.page(bea, bond, mapOf("q" to "calm light"), route = "remember").toString() shouldNotContain LAMP
    }

    @Test
    fun `with semantic off, remember answers from words alone, still 200`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        rig.bonds.turnOffSemanticSearch(ada, bond)
        val disabled = semantic("disabled")

        remembered(ada, bond, "calm light") shouldBe listOf("2026-09-16")

        semantic("disabled") - disabled shouldBe 1.0
        fake.texts shouldNotContain "calm light"
    }

    /** The setting is asked again after `q` was embedded: a turn-off that commits meanwhile reads no vector. */
    @Test
    fun `a turn-off that commits while q is embedded leaves the words alone`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        fake.during = { rig.bonds.turnOffSemanticSearch(ada, bond) }
        val used = semantic("used")

        remembered(ada, bond, "calm light") shouldBe listOf("2026-09-16")

        // The embed itself succeeded, and the vectors were still there to be read: only the second asking stopped it.
        semantic("used") - used shouldBe 1.0
        jdbc.queryForObject(
            "SELECT count(*) FROM ${EntryEmbeddings.tableOf(FakeEmbeddingPort.DEFAULT_SPACE)} WHERE bond_id = ?::uuid",
            Int::class.java,
            bond,
        ) shouldBe 4
    }

    @Test
    fun `with the provider failing, remember answers from words alone, still 200`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        fake.failNext = true
        val degraded = semantic("degraded")

        remembered(ada, bond, "calm light") shouldBe listOf("2026-09-16")

        semantic("degraded") - degraded shouldBe 1.0
        // And with no provider at all.
        fake.space = null
        remembered(ada, bond, "calm light") shouldBe listOf("2026-09-16")
        // Words that match nothing are an empty answer, not an error.
        remembered(ada, bond, "zebra") shouldBe emptyList()
    }

    @Test
    fun `limit bounds the answer and 21 is a 422`() {
        val bond = pair()
        repeat(DAYS) { bothWrite(bond, DAY_ONE.plusDays(it.toLong()), "soup number $it", "nothing $it") }
        embedAll()

        remembered(ada, bond, "soup").size shouldBe DEFAULT_LIMIT
        remembered(ada, bond, "soup", mapOf("limit" to "2")).size shouldBe 2
        remembered(ada, bond, "soup", mapOf("limit" to "20")).size shouldBe DAYS
        // Every one of them a keyword match: fused, they are still each a day of their own, never repeated.
        remembered(ada, bond, "soup", mapOf("limit" to "20")).toSet().size shouldBe DAYS

        listOf("21", "0", "007", "ten", "-1").forEach { limit ->
            withClue(limit) {
                val refused = rig.remember(ada, bond, mapOf("q" to "soup", "limit" to limit))
                refused.status shouldBe 422
                violations(json.readTree(refused.contentAsString)) shouldBe listOf("limit" to "RANGE")
            }
        }
        val missing = rig.remember(ada, bond)
        missing.status shouldBe 422
        violations(json.readTree(missing.contentAsString)) shouldBe listOf("q" to "REQUIRED")
        val blank = rig.remember(ada, bond, mapOf("q" to " ", "limit" to "21"))
        violations(json.readTree(blank.contentAsString)).toSet() shouldBe setOf("q" to "VALID_SEARCH", "limit" to "RANGE")
    }

    @Test
    fun `a stranger gets the bond's 404 whatever q is`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        embedAll()
        val texts = fake.texts.size

        val answers =
            listOf(mapOf("q" to "calm light"), mapOf("q" to " "), emptyMap(), mapOf("q" to "calm light", "limit" to "21"))
                .map { rig.remember(eve, bond, it).also { answer -> answer.status shouldBe 404 }.contentAsString }
                .toSet()

        answers.size shouldBe 1
        answers.single() shouldNotContain "calm"
        // Refused before `q` was read: nothing was sent to the provider.
        fake.texts.size shouldBe texts
    }

    // ---- helpers ----

    /** "calm light" as the fake embeds it. The cut-off is never read by a ranking, so it is set to admit nothing. */
    private fun calmLight() =
        SemanticMatch(EntryEmbeddings.vectorTableOf(FakeEmbeddingPort.DEFAULT_SPACE), NEAR.getValue("calm light"), 0.0)

    private fun semantic(outcome: String) = meters.counter("gratitude.search.semantic", "outcome", outcome).count()

    /** The sweep, until nothing is left to embed. */
    private fun embedAll() {
        repeat(MAX_SWEEPS) { if (sweep.embedPending(SWEEP_BUDGET).embedded == 0) return }
        error("the sweep did not finish")
    }

    private fun pair(): String {
        clock.set(BOND_CREATED)
        return rig.bonds.pair(ada, bea).also { clock.set(NOW) }
    }

    /** Both write on [date], Ada first, so the day reveals. Ada's entry id. */
    private fun bothWrite(
        bond: String,
        date: LocalDate,
        adas: String,
        beas: String,
    ): String {
        clock.set(at(date))
        return rig.bonds.submit(ada, bond, adas).also { rig.bonds.submit(bea, bond, beas) }
    }

    private fun remembered(
        user: UUID,
        bond: String,
        q: String,
        parameters: Map<String, String> = emptyMap(),
    ): List<String> = rig.datesOf(rig.page(user, bond, parameters + ("q" to q), route = "remember")["items"].toList())

    private fun violations(body: JsonNode): List<Pair<String, String>> =
        body["errors"].toList().map { it["field"].asString() to it["code"].asString() }

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)
        const val LAMP = "the quiet lamp by the bed"
        const val SOUP = "you made soup when I was ill"
        const val SICK = "when you looked after me while sick"
        const val DEFAULT_LIMIT = 10
        const val DAYS = 12
        const val SWEEP_BUDGET = 1_000
        const val MAX_SWEEPS = 20

        /** "calm light" and the lamp on one axis, the soup and the question about being sick on another. */
        val NEAR: Map<String, FloatArray> =
            run {
                val dimensions = FakeEmbeddingPort.DEFAULT_SPACE.dimensions

                fun vector(vararg at: Pair<Int, Float>) = FloatArray(dimensions).also { v -> at.forEach { (i, x) -> v[i] = x } }
                val calm = vector(0 to 1f)
                mapOf(
                    "calm light" to calm,
                    "a calm light" to calm,
                    LAMP to vector(0 to 1f, 1 to 0.2f),
                    SICK to vector(2 to 1f),
                    SOUP to vector(2 to 1f, 3 to 0.2f),
                )
            }

        fun at(date: LocalDate): Instant = Instant.parse("${date}T10:00:00Z")
    }
}
