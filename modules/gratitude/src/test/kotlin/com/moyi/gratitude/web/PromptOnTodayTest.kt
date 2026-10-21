package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.domain.PromptAudience
import com.moyi.gratitude.domain.PromptId
import com.moyi.gratitude.domain.PromptOfTheDay
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.PromptLibrary
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * `prompt` on `GET /today` (ADR-0038). The library under test is seven prompts of its own:
 * five shared, one COUPLE, one FRIENDS, and none for FAMILY, so that an empty overlay is
 * always in reach. The seed is retired for the test and brought back after it, row by row.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
// EntryChangesTest's clock, not one of this class's own: a new configuration is a new cached
// context and connection pool, and one more is past the test database's client limit.
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named.
internal class PromptOnTodayTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val json: ObjectMapper,
    @Autowired private val library: PromptLibrary,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var seeded: List<UUID>

    private fun p(n: Int) = UUID.fromString("0199e8a0-7e57-7000-8000-%012x".format(n))

    @BeforeEach
    fun setUp() {
        clock.set(Instant.parse("2026-09-15T10:00:00Z"))
        seeded = jdbc.queryForList("SELECT id FROM prompts WHERE active", UUID::class.java).filterNotNull()
        jdbc.update("UPDATE prompts SET active = false")
        (1..5).forEach { insert(p(it), "CORE", "core $it") }
        insert(p(6), "COUPLE", "couple 6")
        insert(p(7), "FRIENDS", "friends 7")
        // Translations of some, not all: a pt-PT member is shown en for the rest.
        (1..6).forEach { jdbc.update("INSERT INTO prompt_texts (prompt_id, locale, text) VALUES (?, 'pt-pt', ?)", p(it), "pt-pt $it") }
        jdbc.update("INSERT INTO prompt_texts (prompt_id, locale, text) VALUES (?, 'pt', ?)", p(1), "pt 1")
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_entry_withdrawals, " +
                "bond_invites, bond_members, bonds CASCADE",
        )
        (1..8).forEach {
            jdbc.update("DELETE FROM prompt_texts WHERE prompt_id = ?", p(it))
            jdbc.update("DELETE FROM prompts WHERE id = ?", p(it))
        }
        seeded.forEach { jdbc.update("UPDATE prompts SET active = true WHERE id = ?", it) }
        users.clear()
    }

    @Test
    fun `today carries the bond's prompt of the day, an id and a text and nothing else`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bond = pair(ada, bea)

        val prompt = today(ada, bond)["prompt"]

        prompt.propertyNames().asSequence().toSet() shouldBe setOf("id", "text")
        UUID.fromString(prompt["id"].asString()) shouldBe expected(bond, LocalDate.of(2026, 9, 15), PromptAudience.COUPLE)
        prompt["text"].asString() shouldBe
            jdbc.queryForObject("SELECT text FROM prompts WHERE id = ?", String::class.java, UUID.fromString(prompt["id"].asString()))
    }

    @Test
    fun `both members are offered the same prompt, and nothing either does moves it for the other`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bond = pair(ada, bea)
        val adas = prompt(ada, bond)
        // Each held to the bond's own choice, not only to the other: two members' choices made
        // per member would agree by chance about one day in six (six prompts are eligible here).
        val date = LocalDate.of(2026, 9, 15)
        promptId(ada, bond) shouldBe expected(bond, date, PromptAudience.COUPLE)
        promptId(bea, bond) shouldBe expected(bond, date, PromptAudience.COUPLE)

        // Bea opens the app, again and again; then writes; Ada sees her entry locked; Ada writes, and the day reveals.
        repeat(3) { today(bea, bond) }
        prompt(ada, bond) shouldBe adas
        val beasEntry = submit(bea, bond, "thank you for the umbrella")
        prompt(ada, bond) shouldBe adas
        val adasEntry = submit(ada, bond, "thank you for the soup")
        prompt(ada, bond) shouldBe adas
        // Present whether or not the caller has written (ruling 6).
        prompt(bea, bond).let {
            it shouldNotBe "null"
            promptId(bea, bond) shouldBe promptId(ada, bond)
        }

        // Bea marks Ada's entry, reacts to it, takes the reaction back; deletes her own.
        mockMvc
            .put("/api/v1/entries/$adasEntry/favourite") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204
        mockMvc
            .put("/api/v1/entries/$adasEntry/reaction") {
                header(HttpHeaders.AUTHORIZATION, bearer(bea))
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"HEART"}"""
            }.andReturn()
            .response.status shouldBe 204
        mockMvc
            .delete("/api/v1/entries/$adasEntry/reaction") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204
        mockMvc
            .delete("/api/v1/entries/$beasEntry") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        prompt(ada, bond) shouldBe adas

        // And on each day of the week after, both are offered the bond's prompt of that day. A
        // choice made per member would match it for both by chance about one day in thirty-six;
        // eight days running, today's included, about one run in 3 * 10^12.
        (1L..7L).forEach { later ->
            clock.set(Instant.parse("2026-09-15T10:00:00Z").plus(Duration.ofDays(later)))
            val day = date.plusDays(later)
            promptId(ada, bond) shouldBe expected(bond, day, PromptAudience.COUPLE)
            promptId(bea, bond) shouldBe expected(bond, day, PromptAudience.COUPLE)
        }
    }

    @Test
    fun `the same prompt in each member's own language, and en where their language has no text`() {
        val pt = users.verified("Ana", locale = "PT-pt")
        val br = users.verified("Bia", locale = "pt-BR")
        val de = users.verified("Dag", locale = "de")
        val bond = pair(pt, de)
        // Day by day until every case is met: a prompt with a pt-pt text, the one with a bare pt text, and one with neither.
        val seen = mutableSetOf<String>()
        for (day in 0L until 7L) {
            clock.set(Instant.parse("2026-09-15T10:00:00Z").plusSeconds(day * 86_400))
            val id = promptId(pt, bond)!!
            promptId(de, bond) shouldBe id
            val n = id.toString().takeLast(1).toInt()
            today(pt, bond)["prompt"]["text"].asString() shouldBe if (n <= 6) "pt-pt $n" else "core $n"
            today(de, bond)["prompt"]["text"].asString() shouldBe
                if (n == 7) {
                    "friends 7"
                } else if (n == 6) {
                    "couple 6"
                } else {
                    "core $n"
                }
            seen += "$n"
        }
        // Over a whole cycle the COUPLE bond met all six of its prompts.
        seen shouldBe setOf("1", "2", "3", "4", "5", "6")

        val brBond = pair(br, users.verified("Eli"))
        val id = promptId(br, brBond)!!.toString().takeLast(1).toInt()
        today(br, brBond)["prompt"]["text"].asString() shouldBe
            if (id == 1) {
                "pt 1"
            } else if (id == 6) {
                "couple 6"
            } else {
                "core $id"
            }

        // A member the directory no longer knows is shown en, not nothing: the en text of the bond's prompt, exactly.
        users.forget(pt)
        val forgotten = today(pt, bond)["prompt"]
        val m = forgotten["id"].asString().takeLast(1).toInt()
        forgotten["text"].asString() shouldBe if (m == 6) "couple 6" else "core $m"
    }

    @Test
    fun `no prompt once the bond takes no writes, the same null for a leave as for a block, and one before the second member arrives`() {
        val ada = users.verified("Ada")
        val waiting = create(ada).first
        promptId(ada, waiting) shouldBe expected(waiting, LocalDate.of(2026, 9, 15), PromptAudience.COUPLE)

        val leaver = users.verified("Lee")
        val stayer = users.verified("Sam")
        val left = pair(leaver, stayer)
        end(leaver, left, "leave", withdraw = false)

        val blocker = users.verified("Bo")
        val blocked = users.verified("Bud")
        val block = pair(blocker, blocked)
        end(blocker, block, "block", withdraw = true)

        for ((user, bond) in listOf(leaver to left, stayer to left, blocker to block, blocked to block)) {
            today(user, bond)["prompt"].isNull shouldBe true
        }
        // What the other member is shown is the same, whichever way the bond ended (states.md §9). Both are the
        // literal `null` the loop above has already asserted, so this line adds no evidence of its own: it states the
        // rule where a reader looks for it. The rest of the two bodies is WithdrawalReadTest's and the smoke run's.
        prompt(stayer, left) shouldBe prompt(blocked, block)

        val one = users.verified("Uno")
        val two = users.verified("Dos")
        val counting = pair(one, two)
        listOf(one, two).forEach { user ->
            mockMvc
                .post("/api/v1/bonds/$counting/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
                .andReturn()
                .response.status shouldBe 202
        }
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, counting) shouldBe "PENDING_DELETION"
        today(one, counting)["prompt"].isNull shouldBe true
    }

    /** Every prompt [user] is offered on [bond] over [days] days from the 15th. */
    private fun offered(
        user: UUID,
        bond: String,
        days: Long,
    ): Set<UUID> =
        (0L until days)
            .map { day ->
                clock.set(Instant.parse("2026-09-15T10:00:00Z").plusSeconds(day * 86_400))
                promptId(user, bond)!!
            }.toSet()

    @Test
    fun `an overlay with no prompts is offered the core, and a library with nothing for the bond is null`() {
        val fam = users.verified("Fam")
        val family = pair(fam, users.verified("Kin"), type = "FAMILY")
        // The library under test has no FAMILY prompt: the five shared ones, and nothing else.
        offered(fam, family, 5) shouldBe (1..5).map(::p).toSet()

        clock.set(Instant.parse("2026-09-15T10:00:00Z"))
        jdbc.update("UPDATE prompts SET active = false WHERE audience = 'CORE'")
        today(fam, family)["prompt"].isNull shouldBe true
    }

    @Test
    fun `a parent and child are offered the family overlay, and friends never the couple one`() {
        insert(p(8), "FAMILY", "family 8")
        val parent = users.verified("Ma")
        val parentChild = pair(parent, users.verified("Kid"), type = "PARENT_CHILD")
        val friend = users.verified("Fio")
        val friends = pair(friend, users.verified("Fia"), type = "FRIENDS")

        // Six eligible each: the five shared prompts and the bond's own overlay.
        offered(parent, parentChild, 6) shouldBe ((1..5) + 8).map(::p).toSet()
        offered(friend, friends, 6).let {
            it shouldBe ((1..5) + 7).map(::p).toSet()
            it.toList() shouldNotContain p(6)
        }
    }

    @Test
    fun `retiring today's prompt mid-day gives both members another, and no error`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bond = pair(ada, bea)
        val before = promptId(ada, bond)!!

        jdbc.update("UPDATE prompts SET active = false WHERE id = ?", before)

        val after = promptId(ada, bond)!!
        after shouldNotBe before
        promptId(bea, bond) shouldBe after
        after shouldBe expected(bond, LocalDate.of(2026, 9, 15), PromptAudience.COUPLE)
    }

    @Test
    fun `the prompt turns over at the bond's own midnight, forty-five minutes off the hour`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        clock.set(Instant.parse("2026-10-14T06:00:00Z"))
        val bond = pair(ada, bea, zone = "Asia/Kathmandu") // +05:45

        clock.set(Instant.parse("2026-10-15T18:14:59Z")) // 23:59:59 on the 15th in Kathmandu
        val late = promptId(ada, bond)
        late shouldBe expected(bond, LocalDate.of(2026, 10, 15), PromptAudience.COUPLE)

        clock.set(Instant.parse("2026-10-15T18:15:00Z")) // midnight, the 16th
        val early = promptId(bea, bond)
        early shouldBe expected(bond, LocalDate.of(2026, 10, 16), PromptAudience.COUPLE)
        early shouldNotBe late
        promptId(ada, bond) shouldBe early
    }

    @Test
    fun `across the night the clocks go forward, the bond's date chooses, not the UTC one`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        clock.set(Instant.parse("2026-03-28T12:00:00Z"))
        val bond = pair(ada, bea, zone = "Europe/London")

        // 23:30 UTC on the 29th is 00:30 BST on the 30th: the 29th was 23 hours long.
        clock.set(Instant.parse("2026-03-29T23:30:00Z"))
        promptId(ada, bond) shouldBe expected(bond, LocalDate.of(2026, 3, 30), PromptAudience.COUPLE)
        expected(bond, LocalDate.of(2026, 3, 30), PromptAudience.COUPLE) shouldNotBe
            expected(bond, LocalDate.of(2026, 3, 29), PromptAudience.COUPLE)
        promptId(bea, bond) shouldBe promptId(ada, bond)
    }

    private fun insert(
        id: UUID,
        audience: String,
        text: String,
    ) {
        jdbc.update("INSERT INTO prompts (id, audience, text) VALUES (?, ?, ?)", id, audience, text)
    }

    /** What the rule itself says this bond is offered on [date]: the oracle the wiring is held to. */
    private fun expected(
        bond: String,
        date: LocalDate,
        overlay: PromptAudience,
    ): UUID? = PromptOfTheDay.choose(UUID.fromString(bond), date, overlay, library.active())?.value

    private fun pair(
        creator: UUID,
        joiner: UUID,
        type: String = "COUPLE",
        zone: String = "Africa/Lagos",
    ): String {
        val bond = create(creator, type, zone)
        accept(joiner, bond.second)
        return bond.first
    }

    /** The bond's id and its invite code. */
    private fun create(
        creator: UUID,
        type: String = "COUPLE",
        zone: String = "Africa/Lagos",
    ): Pair<String, String> {
        val body =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(creator))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"$type","anchorTimezone":"$zone"}"""
                }.andReturn()
                .response
                .also { it.status shouldBe 201 }
                .contentAsString
        val node = json.readTree(body)
        return node["id"].asString() to node["invite"]["code"].asString()
    }

    private fun accept(
        user: UUID,
        code: String,
    ) {
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
            .andReturn()
            .response.status shouldBe 200
    }

    private fun today(
        user: UUID,
        bond: String,
    ): JsonNode =
        json.readTree(
            mockMvc
                .get("/api/v1/bonds/$bond/today") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
                .andReturn()
                .response
                .also { it.status shouldBe 200 }
                .contentAsString,
        )

    /** The `prompt` value exactly as sent: an object's JSON, or `null`. */
    private fun prompt(
        user: UUID,
        bond: String,
    ): String = today(user, bond)["prompt"].toString()

    private fun promptId(
        user: UUID,
        bond: String,
    ): UUID? = today(user, bond)["prompt"].takeUnless { it.isNull }?.let { UUID.fromString(it["id"].asString()) }

    private fun submit(
        user: UUID,
        bond: String,
        words: String,
    ): String =
        json
            .readTree(
                mockMvc
                    .post("/api/v1/bonds/$bond/entries") {
                        header(HttpHeaders.AUTHORIZATION, bearer(user))
                        header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"text":"$words"}"""
                    }.andReturn()
                    .response
                    .also { it.status shouldBe 201 }
                    .contentAsString,
            )["id"]
            .asString()

    private fun end(
        user: UUID,
        bond: String,
        how: String,
        withdraw: Boolean,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bond/$how") {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                contentType = MediaType.APPLICATION_JSON
                content = """{"withdrawEntries": $withdraw}"""
            }.andReturn()
            .response.status shouldBe 204
    }

    private fun bearer(user: UUID): String = "Bearer ${tokens.issue(user).token}"
}
