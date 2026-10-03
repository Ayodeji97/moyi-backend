package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.UUID
import javax.sql.DataSource

/**
 * `bond.web.BondCrossTenantTest`'s own shape, run against **this** module's
 * context instead — doc 12 §3.3, doc 09 §4, T-02, and ADR-0026's own reason
 * this file has to exist at all: `bond`'s suite reads its routes off
 * `RequestMappingHandlerMapping`, and that mapping is scoped to whatever
 * Spring context it runs in. `BondTestApplication` never scans `gratitude`,
 * so `POST /bonds/{bondId}/entries` is invisible to it — a route this module
 * adds cannot be covered by a suite that cannot see it.
 *
 * [GratitudeTestApplication] scans `com.moyi.bond` too (see that class's own
 * KDoc), so the route table this test reads off [routes] carries **every**
 * bond-scoped route in the application, `bond`'s own routes included — this
 * suite is the whole application's cross-tenant coverage running from a
 * different context, not a narrower copy of it. [fixtures] is `bond`'s own
 * table, copied rather than shared (`FakeUserDirectory`'s own KDoc gives the
 * reason for every such copy across this module boundary), plus one more
 * entry for the route this task adds.
 *
 * **Every request carries a fresh `Idempotency-Key`.** [call] mints one per
 * call regardless of route: harmless for a route `@Idempotent` does not
 * mark, and necessary for `POST /bonds/{bondId}/entries` — the reservation
 * is keyed on `(user_id, idempotency_key)` alone (V11's own unique
 * constraint), with `method` and `path` stored alongside it and compared in
 * code (F6, whole-branch review — this KDoc previously described the key
 * itself as `userId + endpoint + key`, which V11 does not declare). `path` is
 * the literal request URI, so reusing one key across this test's three
 * distinct URIs for the same caller (`ada`, against a random id and then a
 * non-`UUID`) would still collide as a reused key (`422
 * IDEMPOTENCY_KEY_REUSED`, from that comparison) rather than exercise the
 * guard this test is actually for.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
internal class GratitudeCrossTenantTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val routes: RequestMappingHandlerMapping,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    /** `bond.web.BondCrossTenantTest`'s own table, plus this task's route. */
    private val fixtures: Map<String, Fixture> =
        mapOf(
            "GET /api/v1/bonds/{bondId}" to Fixture(),
            "POST /api/v1/bonds/{bondId}/invites" to Fixture(),
            "DELETE /api/v1/bonds/{bondId}/invites/{inviteId}" to Fixture(),
            "POST /api/v1/bonds/{bondId}/leave" to Fixture(),
            "POST /api/v1/bonds/{bondId}/block" to Fixture(),
            "PATCH /api/v1/bonds/{bondId}" to
                Fixture(
                    body = """{"name":"Mine"}""",
                    headers = mapOf(HttpHeaders.IF_MATCH to "\"0\""),
                ),
            "GET /api/v1/bonds/{bondId}/members/me/settings" to Fixture(),
            "PUT /api/v1/bonds/{bondId}/members/me/settings" to Fixture(body = """{"reminderTimeLocal":"07:30"}"""),
            // Slice B5, copied from `bond`'s table with the rest. The PATCH
            // needs a body; confirm and cancel take none — and all three must
            // answer a non-member before they look at whether anything is
            // pending, or the 404 would depend on state only a member can know
            // about.
            "PATCH /api/v1/bonds/{bondId}/timezone" to Fixture(body = """{"anchorTimezone":"Europe/London"}"""),
            "POST /api/v1/bonds/{bondId}/timezone/confirm" to Fixture(body = """{"proposalId":"00000000-0000-0000-0000-000000000001"}"""),
            "DELETE /api/v1/bonds/{bondId}/timezone" to Fixture(),
            "POST /api/v1/bonds/{bondId}/deletion-request" to Fixture(),
            "DELETE /api/v1/bonds/{bondId}/deletion-request" to Fixture(),
            // Task 7. A body, since @Valid runs before the guard would ever
            // refuse anything about the bond — see EntriesController's own
            // KDoc on why that ordering is still fine (a fact about the
            // caller's own request, not about the bond).
            "POST /api/v1/bonds/{bondId}/entries" to Fixture(body = """{"text":"cross-tenant"}"""),
            // Task 8. A GET, no body — RevealGateTest is this route's own
            // suite; this is only the T-02 stranger/unknown-id/malformed-id
            // coverage every bond-scoped route owes this table.
            "GET /api/v1/bonds/{bondId}/today" to Fixture(),
        )

    private data class Fixture(
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `every bond-scoped route is covered, and answers a stranger exactly as it answers nonsense`() {
        val bondScoped = bondScopedRoutes()
        bondScoped.shouldNotBeEmpty()

        val uncovered = bondScoped - fixtures.keys
        check(uncovered.isEmpty()) {
            "Bond-scoped routes with no cross-tenant fixture. Add one to GratitudeCrossTenantTest — " +
                "an endpoint that takes a bondId and is not tested against a non-member is how T-02 happens: $uncovered"
        }

        val ada = users.verified("Ada")
        val eve = users.verified("Eve")
        val bondId = createBond(ada)

        bondScoped.forEach { route ->
            val fixture = fixtures.getValue(route)
            val stranger = call(route, eve, bondId, fixture)
            val randomId = call(route, ada, UUID.randomUUID().toString(), fixture)
            val notAnId = call(route, ada, "not-a-bond", fixture)

            listOf("a stranger" to stranger, "a random id" to randomId, "a value that is not an id" to notAnId)
                .forEach { (who, response) ->
                    check(response.status == 404) {
                        "$route with $who: expected 404, got ${response.status}: ${response.contentAsString}"
                    }
                    response.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
                }

            stranger.contentAsString.withoutInstance() shouldBe randomId.contentAsString.withoutInstance()
            stranger.contentAsString.withoutInstance() shouldBe notAnId.contentAsString.withoutInstance()
        }
    }

    /** Every `METHOD /path` this context serves whose pattern names a bond — `bond`'s own routes and `gratitude`'s together. */
    private fun bondScopedRoutes(): Set<String> =
        routes.handlerMethods.keys
            .flatMap { info ->
                val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
                val methods = info.methodsCondition.methods.ifEmpty { setOf(RequestMethod.GET) }
                patterns
                    .filter { it.contains("{bondId}") }
                    .flatMap { pattern -> methods.map { "${it.name} $pattern" } }
            }.toSet()

    private fun call(
        route: String,
        userId: UUID,
        bondId: String,
        fixture: Fixture,
    ): MockHttpServletResponse {
        val (method, pattern) = route.split(" ", limit = 2)
        val path =
            pattern
                .replace("{bondId}", bondId)
                .replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString())
        val request =
            MockMvcRequestBuilders
                .request(HttpMethod.valueOf(method), path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                .header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
        fixture.body?.let(request::content)
        fixture.headers.forEach { (name, value) -> request.header(name, value) }
        return mockMvc.perform(request).andReturn().response
    }

    private fun createBond(userId: UUID): String {
        val response =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        response.status shouldBe 201
        return Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]
    }

    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")
}
