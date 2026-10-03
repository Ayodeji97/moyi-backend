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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A Bond-day's row carries the span the bond's **anchor timeline** gave it
 * (spec §3.1), resolved through the real `BondAccess` — not a midnight
 * derived from `bonds.anchor_timezone`, which is only the zone the bond
 * currently *requests*.
 *
 * The fixture is a real deferred handoff, driven through bond's own
 * endpoints: a Lagos (UTC+1) bond agrees at 15T11:00Z to move to Kiritimati
 * (UTC+14). BR-6 defers the effect to the end of the current logical day —
 * Lagos's midnight, 15T23:00Z (`BondAccessLockingTest` pins the same
 * handoff from bond's side). Kiritimati's first label is the 16th, and at the
 * handoff it is already 16T13:00 there, so that first day is clipped:
 * `[15T23:00Z, 16T10:00Z)`, eleven hours. A row whose span came from any
 * zone's midnights gets one of these wrong:
 *
 * | computed from          | 15th's row               | 16th's row                 |
 * |------------------------|--------------------------|----------------------------|
 * | the timeline (correct) | Lagos, 14T23Z..15T23Z    | Kiritimati, 15T23Z..16T10Z |
 * | requested zone only    | filed on the 16th        | 15T10Z..16T10Z             |
 * | Lagos only             | 14T23Z..15T23Z           | 15T23Z..16T23Z, the 16th   |
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(BondDayIntervalTest.TimeConfiguration::class)
internal class BondDayIntervalTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        clock.set(CREATED)
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        clock.set(Instant.parse("2026-09-15T11:00:00Z"))
        propose(ada, "Pacific/Kiritimati").status shouldBe 200
        confirm(bea).status shouldBe 200
    }

    @AfterEach
    fun clear() {
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, bond_anchor_intervals, bond_proposals, " +
                "blocks, bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    @Test
    fun `a write after the change is agreed but before it takes effect is filed on the old zone's day`() {
        clock.set(Instant.parse("2026-09-15T12:00:00Z"))

        submit(ada, """{"text":"still Lagos"}""").status shouldBe 201

        // In Kiritimati, the zone the bond now requests, 15T12:00Z is already
        // 16T02:00 — the 16th. The timeline says Lagos decides until 15T23:00Z.
        dayRow() shouldBe DayRow("2026-09-15", "Africa/Lagos", "2026-09-14T23:00:00Z", "2026-09-15T23:00:00Z")
    }

    @Test
    fun `today agrees with where a write would land, while the change is deferred`() {
        clock.set(Instant.parse("2026-09-15T12:00:00Z"))

        today(ada).contentAsString shouldContain "\"date\":\"2026-09-15\""
    }

    @Test
    fun `the first day after the handoff carries its clipped span, not a midnight-to-midnight one`() {
        clock.set(Instant.parse("2026-09-16T05:00:00Z")) // 16T19:00 in Kiritimati

        submit(ada, """{"text":"first Kiritimati day"}""").status shouldBe 201

        dayRow() shouldBe DayRow("2026-09-16", "Pacific/Kiritimati", "2026-09-15T23:00:00Z", "2026-09-16T10:00:00Z")
    }

    // ---- helpers --------------------------------------------------------

    private data class DayRow(
        val date: String,
        val zone: String,
        val startsAt: String,
        val endsAt: String,
    )

    private fun dayRow(): DayRow =
        jdbc.queryForObject(
            "SELECT date::text, anchor_timezone, starts_at, ends_at FROM bond_days WHERE bond_id = ?::uuid",
            { rs, _ ->
                DayRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getTimestamp(3).toInstant().toString(),
                    rs.getTimestamp(4).toInstant().toString(),
                )
            },
            bondId,
        )!!

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun submit(
        caller: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun today(caller: UUID): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }
            .andReturn()
            .response

    private fun propose(
        caller: UUID,
        zone: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response

    private fun confirm(caller: UUID): MockHttpServletResponse {
        val proposalId =
            jdbc.queryForObject(
                "SELECT id FROM bond_proposals WHERE bond_id = ?::uuid AND kind = 'TIMEZONE_CHANGE' ORDER BY proposed_at DESC LIMIT 1",
                UUID::class.java,
                bondId,
            )
        return mockMvc
            .post("/api/v1/bonds/$bondId/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                contentType = MediaType.APPLICATION_JSON
                content = """{"proposalId":"$proposalId"}"""
            }.andReturn()
            .response
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = CREATED)
    }

    private companion object {
        val CREATED: Instant = Instant.parse("2026-09-01T00:00:00Z")
    }
}
