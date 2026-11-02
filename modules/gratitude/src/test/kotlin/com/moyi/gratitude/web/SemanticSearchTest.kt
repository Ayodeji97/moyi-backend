package com.moyi.gratitude.web

import com.moyi.ai.api.EmbeddingSpace
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.EmbeddingSweep
import com.moyi.gratitude.infra.FakeEmbeddingPort
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryEmbeddings
import com.moyi.gratitude.service.SemanticSearchProperties
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
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
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.sqrt

/**
 * The search box finds by meaning too (ADR-0054, spec §3.5): an entry whose vector lies within
 * the model's threshold of `q`'s matches as a keyword match does, **asked of the same row inside
 * the same gate**, in the same order, under the same contract.
 *
 * The provider is [FakeEmbeddingPort] with [NEAR] as its table: "calm light" and "the quiet
 * lamp by the bed" are close (cosine distance about 0.02, inside the test threshold of 0.35);
 * every other text gets a seeded random unit vector, about 1.0 from anything. Entries are
 * embedded by the real sweep ([embedAll]). The query timeout is the test configuration's 1 s.
 *
 * Meter assertions read a delta: the registry is a bean of a context other classes share.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class SemanticSearchTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired json: ObjectMapper,
    @Autowired private val sweep: EmbeddingSweep,
    @Autowired private val fake: FakeEmbeddingPort,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val properties: SemanticSearchProperties,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        fake.clear()
        fake.table = NEAR
        ada = users.verified("Ada")
        bea = users.verified("Bea")
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
    fun `the test threshold is the one the vector table is built around`() {
        // Not vacuous: the near pair is inside it, and a seeded vector is far outside it.
        val threshold = properties.maxDistance.getValue(EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL)
        distance(NEAR.getValue("calm light"), NEAR.getValue(LAMP)) shouldBeLessThan threshold
        distance(NEAR.getValue("calm light"), fake.embed(listOf("bread again")).single()) shouldBeGreaterThan 0.8
    }

    @Test
    fun `a meaning match joins the keyword matches, newest first, the page shape unchanged`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        val used = semantic("used")
        val calls = fake.calledInTransaction.size

        found(ada, bond, "calm light") shouldBe listOf("2026-09-16", "2026-09-15")

        semantic("used") - used shouldBe 1.0
        // `q` was embedded once, as typed, and with no transaction open: a provider call never holds a connection.
        fake.texts shouldContain "calm light"
        fake.calledInTransaction.drop(calls) shouldBe listOf(false)
        rig.page(ada, bond, mapOf("q" to "calm light"), route = "search").propertyNames().toSet() shouldBe setOf("items", "nextCursor")
        // The meaning-matched day is the archive's day, whole.
        rig.page(ada, bond, mapOf("q" to "calm light"), route = "search")["items"][1] shouldBe rig.page(ada, bond)["items"][1]
    }

    /** Review focus 1, T-01: the partner's words before the reveal match nothing, by meaning as by keyword. */
    @Test
    fun `the partner's unrevealed entry is never a meaning match, until the reveal`() {
        val bond = pair()
        clock.set(at(DAY_ONE))
        rig.bonds.submit(bea, bond, LAMP)
        embedAll()

        found(ada, bond, "a calm light") shouldBe emptyList()
        // Not vacuous: the vector is there and matches, for its author.
        found(bea, bond, "a calm light") shouldBe listOf("2026-09-15")

        rig.bonds.submit(ada, bond, "bread again")
        found(ada, bond, "a calm light") shouldBe listOf("2026-09-15")
    }

    @Test
    fun `with the setting off, the same query is keywords only, and q is never sent`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        rig.bonds.turnOffSemanticSearch(ada, bond)
        val before = semantic("disabled")

        found(ada, bond, "calm light") shouldBe listOf("2026-09-16")

        semantic("disabled") - before shouldBe 1.0
        fake.texts shouldNotContain "calm light"
    }

    @Test
    fun `with no provider, the same query is keywords only`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        fake.space = null
        val before = semantic("disabled")

        found(ada, bond, "calm light") shouldBe listOf("2026-09-16")

        semantic("disabled") - before shouldBe 1.0
    }

    /** Review focus 4: the timeout here is the test configuration's 1 s; its production value, 5 s, is `SemanticSearchPropertiesTest`'s. */
    @Test
    fun `a provider that hangs costs at most the timeout, and the page is keywords`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        fake.delay = Duration.ofSeconds(HANG_SECONDS)
        val before = semantic("degraded")
        val started = System.nanoTime()

        found(ada, bond, "calm light") shouldBe listOf("2026-09-16")

        Duration.ofNanos(System.nanoTime() - started) shouldBeLessThan properties.queryTimeout.plusSeconds(2)
        semantic("degraded") - before shouldBe 1.0
    }

    @Test
    fun `a provider that fails costs nothing but the meaning, and the page is keywords`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
        embedAll()
        fake.failNext = true
        val before = semantic("degraded")

        found(ada, bond, "calm light") shouldBe listOf("2026-09-16")

        semantic("degraded") - before shouldBe 1.0
    }

    /** Before the erasure: the withdrawn author's rows, and so their vectors, are still there. */
    @Test
    fun `a withdrawn author's entry is not a meaning match`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, LAMP, "bread again")
        embedAll()
        found(bea, bond, "calm light") shouldBe listOf("2026-09-15")

        rig.bonds.block(ada, bond, true)
        jdbc.queryForObject(
            "SELECT count(*) FROM ${EntryEmbeddings.tableOf(FakeEmbeddingPort.DEFAULT_SPACE)} x " +
                "JOIN entries e ON e.id = x.entry_id WHERE e.bond_id = ?::uuid AND e.text_search = ?",
            Int::class.java,
            bond,
            LAMP,
        ) shouldBe 1
        val used = semantic("used")

        found(bea, bond, "calm light") shouldBe emptyList()
        // The meaning half was asked, and the gate's filter dropped what it found.
        semantic("used") - used shouldBe 1.0
    }

    @Test
    fun `paging by cursor walks the same days with semantic matching on`() {
        val bond = pair()
        // Thirty days: a keyword match, a meaning match, and neither, in turn.
        val matching =
            (0 until PAGING_DAYS).mapNotNull { index ->
                val date = DAY_ONE.plusDays(index.toLong())
                val adas =
                    when (index % 3) {
                        0 -> "a calm light number $index"
                        1 -> LAMP
                        else -> "bread number $index"
                    }
                bothWrite(bond, date, adas, "nothing $index")
                date.toString().takeIf { index % 3 != 2 }
            }
        embedAll()

        val walked = rig.datesOf(rig.walk(ada, bond, mapOf("q" to "calm light", "limit" to "7"), route = "search"))

        walked shouldBe matching.reversed()
    }

    // ---- helpers ----

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

    /** Both write on [date], Ada first, so the day reveals. */
    private fun bothWrite(
        bond: String,
        date: LocalDate,
        adas: String,
        beas: String,
    ) {
        clock.set(at(date))
        rig.bonds.submit(ada, bond, adas)
        rig.bonds.submit(bea, bond, beas)
    }

    private fun found(
        user: UUID,
        bond: String,
        q: String,
    ): List<String> = rig.datesOf(rig.page(user, bond, mapOf("q" to q), route = "search")["items"].toList())

    private fun distance(
        a: FloatArray,
        b: FloatArray,
    ): Double = 1.0 - a.indices.sumOf { (a[it] * b[it]).toDouble() } / (norm(a) * norm(b))

    private fun norm(vector: FloatArray): Double = sqrt(vector.sumOf { (it * it).toDouble() })

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)
        const val LAMP = "the quiet lamp by the bed"
        const val HANG_SECONDS = 10L
        const val PAGING_DAYS = 30
        const val SWEEP_BUDGET = 1_000
        const val MAX_SWEEPS = 20

        /** "calm light" on one axis, the lamp a little off it: distance 1 - 1/√1.04, about 0.02. */
        val NEAR: Map<String, FloatArray> =
            run {
                val dimensions = FakeEmbeddingPort.DEFAULT_SPACE.dimensions
                val calm = FloatArray(dimensions).also { it[0] = 1f }
                val lamp =
                    FloatArray(dimensions).also {
                        it[0] = 1f
                        it[1] = 0.2f
                    }
                mapOf("calm light" to calm, "a calm light" to calm, LAMP to lamp)
            }

        fun at(date: LocalDate): Instant = Instant.parse("${date}T10:00:00Z")
    }
}
