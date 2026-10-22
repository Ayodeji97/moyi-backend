package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.domain.SearchTerms
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.GetDays
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.patch
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * `GET /bonds/{bondId}/search` (FR-050, FR-091; ADR-0039): the archive, filtered by words.
 *
 * What is found: a day on which **one entry the caller can read in full** holds every word
 * of `q`, compared without case or accents, whole words only. What is shown: that day whole,
 * exactly as the archive gives it. What is never found: a day only the partner's unrevealed
 * entry matches (H-S2), the words of an erased entry (H-S4), and, in
 * `SearchWithdrawalTest`, the words of an author who has withdrawn (H-S1).
 *
 * Days are written by real requests with the clock moved to each, as `DaysFeedTest` writes
 * them; a past day holding an entry no request could leave there is put down by hand
 * (`ArchiveRig`). Words are plain words, because the `'simple'` parser splits `zq-ada-1` into
 * pieces that would match each other.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // What Spring hands the test; and one route's rules, kept together.
internal class SearchTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val access: BondAccess,
    @Autowired private val getDays: GetDays,
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
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        eve = users.verified("Eve")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    // ---- what is found, and how it is shown ----

    @Test
    fun `a search finds the days on which one readable entry holds every word, newest first, each day whole`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, "thank you for the long walk", "obrigada pelo café")
        bothWrite(bond, DAY_ONE.plusDays(1), "the walk to the market", "bread again")
        bothWrite(bond, DAY_ONE.plusDays(2), "nothing much", "a long quiet walk home")

        found(ada, bond, "walk") shouldBe listOf("2026-09-17", "2026-09-16", "2026-09-15")
        found(ada, bond, "long walk") shouldBe listOf("2026-09-17", "2026-09-15")
        found(bea, bond, "walk long") shouldBe listOf("2026-09-17", "2026-09-15")
        // Two words on one day, one in each entry: every word must be in one entry.
        found(ada, bond, "market bread") shouldBe emptyList()

        val page = rig.page(ada, bond, mapOf("q" to "walk"), route = "search")
        page["nextCursor"].isNull shouldBe true
        // The whole day, though only the partner's entry matched it, and byte for byte the archive's day.
        val newest = page["items"][0]
        newest["myEntry"]["text"].asString() shouldBe "nothing much"
        newest["partnerEntry"]["text"].asString() shouldBe "a long quiet walk home"
        newest shouldBe rig.page(ada, bond)["items"][0]
    }

    @Test
    fun `case and accents fold, and only whole words match`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, "Obrigada pelo Café", "e pela Ação")

        listOf("cafe", "CAFE", "café", "Café", "acao", "AÇÃO", "obrigada café").forEach { q ->
            withClue(q) { found(bea, bond, q) shouldBe listOf("2026-09-15") }
        }
        // Two words, one in each entry, find nothing: every word must be in one entry (ADR-0039 decision 7).
        listOf("caf", "cafes", "obrigado", "pel", "obrigada ação").forEach { q -> withClue(q) { found(bea, bond, q) shouldBe emptyList() } }
    }

    @Test
    fun `words with no letters or digits in them match nothing, and are not refused`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, "thank you!!!", "🌻 for the sunflower")

        listOf("!!!", "🌻", "...", "— …").forEach { q ->
            withClue(q) { rig.search(ada, bond, mapOf("q" to q)).contentAsString shouldBe EMPTY_PAGE }
        }
    }

    @Test
    fun `an edit before the reveal is searched as it now reads`() {
        val bond = pair()
        clock.set(at(DAY_ONE))
        val id = rig.bonds.submit(ada, bond, "an old plum")
        patch(ada, id, "a new pear")

        found(ada, bond, "plum") shouldBe emptyList()
        found(ada, bond, "pear") shouldBe listOf("2026-09-15")
    }

    @Test
    fun `a deleted entry's words find nothing, and a found day shows the other entry's tombstone`() {
        val bond = pair()
        val written = bothWrite(bond, DAY_ONE, "fig jam", "fig tart")
        rig.bonds.delete(ada, written.adas)

        found(ada, bond, "jam") shouldBe emptyList()
        found(bea, bond, "jam") shouldBe emptyList()
        found(ada, bond, "tart") shouldBe listOf("2026-09-15")
        val day = rig.page(ada, bond, mapOf("q" to "tart"), route = "search")["items"][0]
        day["myEntry"]["text"].isNull shouldBe true
        day["myEntry"]["status"].asString() shouldBe "DELETED"
    }

    @Test
    fun `a found day carries no snippet, highlight or rank`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, "thank you for the walk", "and the tea")

        val day = rig.page(ada, bond, mapOf("q" to "walk"), route = "search")["items"][0]
        day.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("date", "status", "myEntry", "partnerEntry")
        day["myEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
        day["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
    }

    // ---- whose words ----

    @Test
    fun `the caller's own unrevealed entry is found, and the partner's is not until the day reveals`() {
        val bond = pair()
        clock.set(at(DAY_ONE))
        rig.bonds.submit(ada, bond, "a secret zebra")

        found(ada, bond, "zebra") shouldBe listOf("2026-09-15")
        rig.page(ada, bond, mapOf("q" to "zebra"), route = "search")["items"][0]["partnerEntry"].isNull shouldBe true
        for (limit in listOf("1", "2", "20")) {
            withClue("limit $limit") { rig.search(bea, bond, mapOf("q" to "zebra", "limit" to limit)).contentAsString shouldBe EMPTY_PAGE }
        }

        rig.bonds.submit(bea, bond, "and a lion")

        found(bea, bond, "zebra") shouldBe listOf("2026-09-15")
    }

    @Test
    fun `a word only in the partner's unrevealed entry of a past day finds nothing, though the day is the caller's`() {
        val bond = pair()
        val past = LocalDate.of(2026, 9, 14)
        val day = rig.insertDay(bond, past, "PARTIAL")
        rig.insertEntry(day, bond, rig.bonds.memberId(bond, ada), EntryState.LIVE_UNREVEALED, "my own heron", at(past))
        rig.insertEntry(day, bond, rig.bonds.memberId(bond, bea), EntryState.LIVE_UNREVEALED, "her hidden giraffe", at(past))

        found(ada, bond, "heron") shouldBe listOf("2026-09-14")
        for (limit in listOf("1", "2", "20")) {
            withClue(
                "limit $limit",
            ) { rig.search(ada, bond, mapOf("q" to "giraffe", "limit" to limit)).contentAsString shouldBe EMPTY_PAGE }
        }
        val shown = rig.search(ada, bond, mapOf("q" to "heron")).contentAsString
        shown shouldNotContain "giraffe"
        json.readTree(shown)["items"][0]["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder
            listOf("authorMemberId", "status")
        found(bea, bond, "giraffe") shouldBe listOf("2026-09-14")
        found(bea, bond, "heron") shouldBe emptyList()
    }

    @Test
    fun `a word only in the partner's unrevealed entries finds nothing, over a history longer than one request reads`() {
        clock.set(LONG_AGO)
        val bond = rig.bonds.pair(ada, bea)
        clock.set(NOW)
        val adas = rig.bonds.memberId(bond, ada)
        val beas = rig.bonds.memberId(bond, bea)
        // More days than one request reads when asked for one (GetDays.fill's bound), each the caller's
        // through her own entry, each holding the partner's unrevealed word. The oldest is also hers.
        val reach = 2 + (GetDays.MAX_WINDOWS - 1) * GetDays.REFILL_WINDOW
        val first = LocalDate.of(2020, 1, 1)
        (0..reach + 5).forEach { index ->
            val date = first.plusDays(index.toLong())
            val day = rig.insertDay(bond, date, "PARTIAL")
            rig.insertEntry(day, bond, adas, EntryState.LIVE_UNREVEALED, if (index == 0) "my own giraffe" else "my day $index", at(date))
            rig.insertEntry(day, bond, beas, EntryState.LIVE_UNREVEALED, "giraffe number $index", at(date))
        }

        val pages = rig.pages(ada, bond, mapOf("q" to "giraffe", "limit" to "1"), route = "search")

        pages.map { rig.datesOf(it["items"].toList()) } shouldBe listOf(listOf(first.toString()))
        pages.single()["nextCursor"].isNull shouldBe true
    }

    @Test
    fun `an ended bond is searched by both members, the one who left included`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE, "the long walk", "the short tea")
        rig.bonds.leave(bea, bond, false)

        found(ada, bond, "walk") shouldBe listOf("2026-09-15")
        found(bea, bond, "walk") shouldBe listOf("2026-09-15")
        found(bea, bond, "tea") shouldBe listOf("2026-09-15")
    }

    // ---- the page ----

    @Test
    fun `a page is days and a cursor and nothing else, and is bounded by count and by bytes`() {
        val bond = pair()
        (0L..2L).forEach { bothWrite(bond, DAY_ONE.plusDays(it), "walk $it", "tea $it") }

        val first = rig.page(ada, bond, mapOf("q" to "walk", "limit" to "1"), route = "search")
        first.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("items", "nextCursor")
        rig.pages(ada, bond, mapOf("q" to "walk", "limit" to "1"), route = "search").map { rig.datesOf(it["items"].toList()) } shouldBe
            listOf(listOf("2026-09-17"), listOf("2026-09-16"), listOf("2026-09-15"))
        // The archive's own cursor, read the same way.
        found(ada, bond, "walk", mapOf("cursor" to rig.cursorBefore("2026-09-17"))) shouldBe listOf("2026-09-16", "2026-09-15")
        // And by bytes: with a bound smaller than one day, a page is its first day and a cursor (ADR-0036 decision 6).
        val membership = access.membershipOf(ada, UUID.fromString(bond))
        val page = getDays.search(membership, SearchTerms.of("walk")!!, before = null, limit = GetDays.MAX_LIMIT, textOctets = 1)
        page.days.map { it.date.toString() } shouldBe listOf("2026-09-17")
        page.next shouldBe LocalDate.of(2026, 9, 17)
    }

    // ---- what is refused ----

    @Test
    fun `every q that cannot be searched is one 422 on q that never echoes it, after the bond's 404`() {
        val bond = pair()
        val refused = listOf("", " ", "\t", "\u00A0", "\u2003", "z".repeat(101), FAMILY.repeat(41), "zq\u0000zq")
        val strangers = mutableSetOf<String>()
        for (q in refused) {
            withClue(
                q
                    .take(12)
                    .codePoints()
                    .toArray()
                    .toList(),
            ) {
                val answer = rig.search(ada, bond, mapOf("q" to q))
                answer.status shouldBe 422
                val body = json.readTree(answer.contentAsString)
                body["code"].asString() shouldBe "VALIDATION_FAILED"
                violations(body) shouldBe listOf("q" to "VALID_SEARCH")
                if (q.isNotBlank()) answer.contentAsString shouldNotContain q.trim().take(20)
                strangers += rig.search(eve, bond, mapOf("q" to q)).also { it.status shouldBe 404 }.contentAsString
            }
        }
        val missing = rig.search(ada, bond)
        missing.status shouldBe 422
        violations(json.readTree(missing.contentAsString)) shouldBe listOf("q" to "REQUIRED")
        strangers += rig.search(eve, bond).also { it.status shouldBe 404 }.contentAsString
        // One answer for a stranger, whatever q was: the bond's 404, before q is read.
        strangers.size shouldBe 1
        // Every problem at once.
        val all = rig.search(ada, bond, mapOf("q" to " ", "limit" to "0", "cursor" to "zz"))
        violations(json.readTree(all.contentAsString)).map { it.first }.toSet() shouldBe setOf("limit", "cursor", "q")
        // And at each edge, taken.
        listOf("z".repeat(100), FAMILY.repeat(40)).forEach { q ->
            withClue(q.take(12)) {
                rig.search(ada, bond, mapOf("q" to q)).status shouldBe
                    200
            }
        }
    }

    // ---- helpers ----

    private data class Written(
        val adas: String,
        val beas: String,
    )

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
    ): Written {
        clock.set(at(date))
        return Written(rig.bonds.submit(ada, bond, adas), rig.bonds.submit(bea, bond, beas))
    }

    private fun found(
        user: UUID,
        bond: String,
        q: String,
        more: Map<String, String> = emptyMap(),
    ): List<String> = rig.datesOf(rig.page(user, bond, mapOf("q" to q) + more, route = "search")["items"].toList())

    private fun violations(body: JsonNode): List<Pair<String, String>> =
        body["errors"].toList().map { it["field"].asString() to it["code"].asString() }

    private fun patch(
        user: UUID,
        id: String,
        words: String,
    ) {
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private companion object {
        val LONG_AGO: Instant = Instant.parse("2019-12-31T10:00:00Z")
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)
        const val EMPTY_PAGE = """{"items":[],"nextCursor":null}"""
        const val FAMILY = "👨‍👩‍👧‍👦"
        val FULL_KEYS =
            listOf("id", "bondId", "date", "authorMemberId", "text", "status", "createdAt", "intendedAt", "favourited", "reaction")

        /** 10:00Z: late morning of [date] in Africa/Lagos. */
        fun at(date: LocalDate): Instant = Instant.parse("${date}T10:00:00Z")
    }
}
