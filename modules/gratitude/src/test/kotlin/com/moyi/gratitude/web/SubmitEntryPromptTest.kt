package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `promptId` on `POST /bonds/{bondId}/entries` (ADR-0038 decisions 8-12): the prompt an entry
 * answers is recorded, refused in one `422` when it names no prompt, never sent back, and
 * erased with the words. The seed library is used as it is; the one row a test retires is
 * restored by that test.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
// EntryChangesTest's clock, not one of this class's own: a new configuration is a new cached
// context and connection pool, and one more is past the test database's client limit.
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named.
internal class SubmitEntryPromptTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val json: ObjectMapper,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        clock.set(Instant.parse("2026-09-15T10:00:00Z"))
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        bond = pair(ada, bea)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_entry_withdrawals, " +
                "bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    private val seedPrompt = UUID.fromString("0199e8a0-6a00-7000-8000-000000000001")

    @Test
    fun `an entry is written with the prompt it answers, and nobody is ever sent it back`() {
        // A prompt that is not today's, so that today's own `prompt` cannot be what the assertions find.
        val other =
            jdbc.queryForObject(
                "SELECT id FROM prompts WHERE audience = 'CORE' AND id <> ? ORDER BY id LIMIT 1",
                UUID::class.java,
                UUID.fromString(json.readTree(get(ada, "/api/v1/bonds/$bond/today"))["prompt"]["id"].asString()),
            )!!
        val written = post(ada, bond, """{"text":"thank you for the tea","promptId":"$other"}""")
        written.status shouldBe 201
        val entryId = json.readTree(written.contentAsString)["id"].asString()
        storedPrompt(entryId) shouldBe other

        // The author's 201, her today, Bea's locked view, both after the reveal, the feed, the day: no key, no value.
        val views =
            mutableListOf(
                written.contentAsString,
                get(ada, "/api/v1/bonds/$bond/today"),
                get(bea, "/api/v1/bonds/$bond/today"),
            )
        post(bea, bond, """{"text":"thank you for the walk"}""").status shouldBe 201
        listOf(ada, bea).forEach { user ->
            views += get(user, "/api/v1/bonds/$bond/today")
            views += get(user, "/api/v1/bonds/$bond/days")
            views += get(user, "/api/v1/bonds/$bond/days/2026-09-15")
        }
        views.forEach { body ->
            body shouldNotContain "promptId"
            body shouldNotContain other.toString()
        }
    }

    @Test
    fun `any prompt the library has ever held is accepted, retired or another audience's, and null is none`() {
        val retired = UUID.fromString("0199e8a0-6a00-7000-8000-000000000002")
        val friends = UUID.fromString("0199e8a0-6a00-7000-8000-000000000011")
        jdbc.update("UPDATE prompts SET active = false WHERE id = ?", retired)
        try {
            // A prompt retired since the draft was written, on a COUPLE bond.
            val one = post(ada, bond, """{"text":"one","promptId":"$retired"}""").also { it.status shouldBe 201 }
            storedPrompt(idOf(one)) shouldBe retired
            // Another bond type's prompt.
            val two = post(bea, bond, """{"text":"two","promptId":"$friends"}""").also { it.status shouldBe 201 }
            storedPrompt(idOf(two)) shouldBe friends
        } finally {
            jdbc.update("UPDATE prompts SET active = true WHERE id = ?", retired)
        }
        val cy = users.verified("Cy")
        val third = pair(cy, users.verified("Di"))
        val three = post(cy, third, """{"text":"three","promptId":null}""").also { it.status shouldBe 201 }
        storedPrompt(idOf(three)) shouldBe null
    }

    @Test
    fun `an id that names no prompt is one 422 on promptId, whatever it is, and nothing is written`() {
        val bodies =
            listOf(
                """{"text":"x","promptId":"${UUID.randomUUID()}"}""",
                """{"text":"x","promptId":"not-a-uuid"}""",
                """{"text":"x","promptId":"1-1-1-1-1"}""",
                // The seed's first prompt, spelled short: `UUID.fromString` reads it as that prompt's id. Not how an id is written.
                """{"text":"x","promptId":"${seedPrompt.toString().removePrefix("0")}"}""",
                """{"text":"x","promptId":""}""",
                """{"text":"x","promptId":5}""",
            )
        val answers = bodies.map { post(ada, bond, it).comparable() }
        answers.toSet().size shouldBe 1
        answers.first() shouldContain "422"
        answers.first() shouldContain "\"code\":\"VALIDATION_FAILED\""
        answers.first() shouldContain "\"field\":\"promptId\""
        answers.first() shouldContain "\"code\":\"VALID_PROMPT\""
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 0
        // An object is not a value at all: the standing 400, decided before anything else.
        post(ada, bond, """{"text":"x","promptId":{}}""").status shouldBe 400
    }

    @Test
    fun `a stranger is told the bond's 404 and an ended bond its 409, before the prompt is looked at`() {
        val eve = users.verified("Eve")
        post(eve, bond, """{"text":"x","promptId":"not-a-uuid"}""").comparable() shouldBe
            post(eve, bond, """{"text":"x"}""").comparable()
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204
        post(ada, bond, """{"text":"x","promptId":"not-a-uuid"}""").let {
            it.status shouldBe 409
            it.contentAsString shouldContain "BOND_ARCHIVED"
        }
    }

    @Test
    fun `a replay under the same key writes once, and a patch never touches the prompt`() {
        val key = UUID.randomUUID().toString()
        val body = """{"text":"once","promptId":"$seedPrompt"}"""
        val first = post(ada, bond, body, key)
        val again = post(ada, bond, body, key)
        again.status shouldBe 201
        again.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 1
        val entryId = json.readTree(first.contentAsString)["id"].asString()

        val other = UUID.fromString("0199e8a0-6a00-7000-8000-000000000003")
        mockMvc
            .patch("/api/v1/entries/$entryId") {
                header(HttpHeaders.AUTHORIZATION, bearer(ada))
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"twice","promptId":"$other"}"""
            }.andReturn()
            .response.status shouldBe 200
        storedPrompt(entryId) shouldBe seedPrompt
    }

    @Test
    fun `deleting an entry takes the prompt it answered with its words`() {
        val entryId = idOf(post(ada, bond, """{"text":"gone","promptId":"$seedPrompt"}"""))
        mockMvc
            .delete("/api/v1/entries/$entryId") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response.status shouldBe 204
        storedPrompt(entryId) shouldBe null
    }

    private fun pair(
        creator: UUID,
        joiner: UUID,
    ): String {
        val (id, code) = create(creator)
        accept(joiner, code)
        return id
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

    /** The body of a `200`, as `WithdrawalRig.get` gives it. */
    private fun get(
        user: UUID,
        path: String,
    ): String =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
            .andReturn()
            .response
            .also { it.status shouldBe 200 }
            .contentAsString

    private fun idOf(response: MockHttpServletResponse): String = json.readTree(response.contentAsString)["id"].asString()

    private fun post(
        user: UUID,
        bond: String,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun storedPrompt(entryId: String): UUID? =
        jdbc.queryForObject("SELECT prompt_id FROM entries WHERE id = ?::uuid", UUID::class.java, entryId)

    /** The response with `instance` blanked: what is left is what a client could tell two answers apart by. */
    private fun MockHttpServletResponse.comparable(): String =
        "$status ${contentAsString.replace(Regex("\"instance\":\"[^\"]*\""), "\"instance\":\"-\"")}"

    private fun bearer(user: UUID): String = "Bearer ${tokens.issue(user).token}"
}
