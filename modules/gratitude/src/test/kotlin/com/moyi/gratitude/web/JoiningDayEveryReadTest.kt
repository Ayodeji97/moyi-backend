package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Spec §12.4, ADR-0031/ADR-0032 decision 6: while a bond's joining day is
 * still `SUSPENDED`, the first gratitude operation that meets it takes it
 * out. Every read route does that by calling `ReconcileJoiningDay.beforeRead`
 * by hand, so a route added without the call would compile, pass its own
 * suite, and leave the day suspended for whoever reads through it first.
 *
 * The routes come from [routes], not from a list here: every `GET` whose
 * handler lives in `com.moyi.gratitude` is a case, so a new one is a case
 * the day it is added. [fixtures] holds only what a route needs to be called
 * with valid parameters; a discovered route with no entry there fails by
 * name, rather than being skipped.
 *
 * Each case builds a **native** joining day, as [JoiningDayTest] does — Cara
 * writes on a bond of one, so the day is `SUSPENDED`; Eve joins the same
 * day — with no row touched by hand. Eve's request through the route is the
 * first gratitude operation to meet the day, and it must leave it `PARTIAL`.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
internal class JoiningDayEveryReadTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val routes: RequestMappingHandlerMapping,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    /**
     * What each route is called with, beyond `{bondId}`. Not the list of
     * routes: that is [gratitudeReads]. An entry here for a route that no
     * longer exists is harmless; a route with no entry here fails.
     */
    private val fixtures: Map<String, Fixture> =
        mapOf(
            "/api/v1/bonds/{bondId}/today" to Fixture(),
            "/api/v1/bonds/{bondId}/streak" to Fixture(),
            "/api/v1/bonds/{bondId}/days" to Fixture(),
            // The joining day itself. The archive does not hold a day still open, so the answer is DAY_NOT_FOUND —
            // read after the date is parsed and the day reconciled, which is what this test is for.
            "/api/v1/bonds/{bondId}/days/{date}" to Fixture(pathVariables = mapOf("date" to JOINING_DATE), expectedStatus = 404),
            "/api/v1/bonds/{bondId}/search" to Fixture(query = "q=waiting"),
            "/api/v1/bonds/{bondId}/on-this-day" to Fixture(),
            "/api/v1/bonds/{bondId}/milestones" to Fixture(),
        )

    private data class Fixture(
        /** Values for every path variable other than `{bondId}`. */
        val pathVariables: Map<String, String> = emptyMap(),
        /** Appended to the path after `?`, as it stands. */
        val query: String? = null,
        val expectedStatus: Int = 200,
    )

    @AfterEach
    fun clear() = truncate()

    @TestFactory
    fun `every gratitude read reconciles a suspended joining day`(): List<DynamicTest> {
        val reads = gratitudeReads()
        val count =
            DynamicTest.dynamicTest("the discovery finds at least the $KNOWN_READS known reads") {
                check(reads.size >= KNOWN_READS) { "Found ${reads.size} gratitude GET routes, expected at least $KNOWN_READS: $reads" }
            }
        // One case per path holds only while each path has one handler. A second `GET` on the same path, told apart by
        // `params`, `headers` or `produces`, would share the first one's case and never be called (Codex on #77).
        val oneHandlerEach =
            DynamicTest.dynamicTest("each gratitude read path is served by one handler") {
                val shared = gratitudeHandlersByPath().filterValues { it.size > 1 }
                check(shared.isEmpty()) {
                    "These paths have more than one GET handler, and this test calls each path once: $shared. Give each " +
                        "variant a fixture of its own that selects it."
                }
            }
        return listOf(count, oneHandlerEach) + reads.map { route -> DynamicTest.dynamicTest("GET $route") { reconcilesThrough(route) } }
    }

    private fun reconcilesThrough(route: String) {
        val fixture =
            checkNotNull(fixtures[route]) {
                "GET $route has no fixture in JoiningDayEveryReadTest. Add one, with the parameters a member calls it with, " +
                    "and make sure the route calls ReconcileJoiningDay.beforeRead before it reads."
            }
        try {
            clock.set(NOW)
            val cara = users.verified("Cara")
            val eve = users.verified("Eve")
            val created = createBond(cara)
            val bondId = idOf(created)
            submit(cara, bondId).status.let { check(it == 201) { "Cara's entry: expected 201, got $it" } }
            accept(eve, codeOf(created)).status.let { check(it == 200) { "Eve's accept: expected 200, got $it" } }
            check(joiningDayStatus(bondId) == "SUSPENDED") { "Setup: the joining day should be SUSPENDED before the read" }

            val response = read(route, fixture, eve, bondId)

            check(response.status == fixture.expectedStatus) {
                "GET $route: expected ${fixture.expectedStatus}, got ${response.status}: ${response.contentAsString}"
            }
            val after = joiningDayStatus(bondId)
            check(after == "PARTIAL") {
                "GET $route left the joining day $after, not PARTIAL: it reads without calling ReconcileJoiningDay.beforeRead"
            }
        } finally {
            truncate()
        }
    }

    /** Every `GET` pattern this context serves from a handler in `com.moyi.gratitude`. */
    private fun gratitudeReads(): List<String> = gratitudeHandlersByPath().keys.sorted()

    /** Each gratitude `GET` pattern, with every handler method that serves it. */
    private fun gratitudeHandlersByPath(): Map<String, List<String>> =
        routes.handlerMethods
            .filter { (info, handler) ->
                handler.isGratitude() && RequestMethod.GET in info.methodsCondition.methods.ifEmpty { setOf(RequestMethod.GET) }
            }.flatMap { (info, handler) ->
                info.pathPatternsCondition
                    ?.patternValues
                    .orEmpty()
                    .map { it to handler.shortLogMessage }
            }.groupBy({ it.first }, { it.second })

    private fun HandlerMethod.isGratitude(): Boolean = beanType.packageName.startsWith("com.moyi.gratitude")

    private fun read(
        route: String,
        fixture: Fixture,
        user: UUID,
        bondId: String,
    ): MockHttpServletResponse {
        val path =
            fixture.pathVariables.entries.fold(route.replace("{bondId}", bondId)) { path, (name, value) ->
                path.replace("{$name}", value)
            }
        check(!path.contains('{')) { "GET $route: its fixture leaves a path variable unfilled in $path" }
        return mockMvc
            .get(fixture.query?.let { "$path?$it" } ?: path) {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
            }.andReturn()
            .response
    }

    private fun joiningDayStatus(bondId: String): String? =
        jdbc.queryForObject(
            "SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date",
            String::class.java,
            bondId,
            JOINING_DATE,
        )

    private fun truncate() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    private fun submit(
        caller: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"written while waiting"}"""
            }.andReturn()
            .response

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        /** Midday UTC on the 15th is 11:00 in Africa/Lagos — nowhere near a midnight either side. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** The Bond-day [NOW] falls on in Lagos: the day the bond is created, written on, and joined. */
        const val JOINING_DATE = "2026-09-15"

        /** today, streak, days, days/{date}, search, on-this-day, milestones: the reads there were when this test was written. */
        const val KNOWN_READS = 7
    }
}
