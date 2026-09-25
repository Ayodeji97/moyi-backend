package com.moyi.bond.web

import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
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
 * Doc 12 §3.3, doc 09 §4, T-02: **for every bond-scoped endpoint, a
 * non-member receives 404 and no data** — and the list of endpoints is read
 * from the route table rather than kept by hand, so an endpoint added without
 * a case here fails the build instead of shipping unguarded. Doc 12 asks for
 * exactly that: "driven by reflection over the controller list, so a new
 * endpoint that is not covered fails the build."
 *
 * Three callers hit every `{bondId}` route with a request a member could have
 * succeeded with: a stranger holding a real bond's id, a member holding a
 * random id, and a member holding a value that is not an id at all. All three
 * must receive the same 404 with the same body — `instance` aside, which is
 * the path the caller typed and therefore theirs. A difference between any two
 * of them is the existence oracle T-02 exists to deny.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondCrossTenantTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val routes: RequestMappingHandlerMapping,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    /**
     * One request shape per bond-scoped route: a body and any headers a member
     * would need for the call to get as far as the guard. **Grows with every
     * slice** — the assertion below fails on a route with no entry here, which
     * is the whole point.
     */
    private val fixtures: Map<String, Fixture> =
        mapOf(
            "GET /api/v1/bonds/{bondId}" to Fixture(),
        )

    private data class Fixture(
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `every bond-scoped route is covered, and answers a stranger exactly as it answers nonsense`() {
        val bondScoped = bondScopedRoutes()
        bondScoped.shouldNotBeEmpty()

        val uncovered = bondScoped - fixtures.keys
        check(uncovered.isEmpty()) {
            "Bond-scoped routes with no cross-tenant fixture. Add one to BondCrossTenantTest — " +
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

    /** Every `METHOD /path` this application serves whose pattern names a bond. */
    private fun bondScopedRoutes(): Set<String> =
        routes.handlerMethods.keys
            .flatMap { info ->
                val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
                // A mapping with no method condition answers all of them; GET
                // is the one worth probing, and no such mapping exists here.
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
                // Any other path variable a later slice adds (an invite id, a
                // member id) gets a value that names nothing — the bond id is
                // what this test is about.
                .replace(Regex("\\{[^}]+}"), UUID.randomUUID().toString())
        val request =
            MockMvcRequestBuilders
                .request(HttpMethod.valueOf(method), path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
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

    /** `instance` is the path the caller typed, which is theirs to see (the lesson from #35's 404 body). */
    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")
}
