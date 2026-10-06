package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The close job over doc 04 §6's timezone matrix (spec §6.4: "not a
 * suggestion"). `TimezoneMatrixTest` proves which instants belong to which
 * day; this proves the job ends each day at the instant that test says it
 * ends — one second before, the day is as it was; at it, the day is settled.
 *
 * Each case has one entry on the day under test, so "settled" reads `SOLO`.
 * The job also writes the earlier days nobody wrote on; they are not what a
 * case is about and are left out of its assertions unless it says otherwise.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(CloseMatrixTest.TimeConfiguration::class)
internal class CloseMatrixTest(
    @Autowired private val closer: DayCloser,
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
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    /**
     * Before as well as after ([setUp] calls it first). The close job works on every bond in the
     * database, and this database is shared with test classes that leave
     * bonds behind: their unwritten days would be written first and use up
     * the job's allowance for a run before it reached the bond under test.
     */
    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, bond_anchor_intervals, bond_proposals, " +
                "blocks, bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    /** `America/New_York`, 8 March 2026: the 8th is `[03-08T05:00Z, 03-09T04:00Z)`, 23 hours. */
    @Test
    fun `a spring-forward day closes after its 23 hours, not 24`() {
        pairedBond("America/New_York", createdAt = "2026-03-04T12:00:00Z")
        writeAt("2026-03-08T12:00:00Z")

        closesExactlyAt("2026-03-08", "2026-03-09T04:00:00Z")
    }

    /** `America/New_York`, 1 November 2026: the 1st is `[11-01T04:00Z, 11-02T05:00Z)`, 25 hours. */
    @Test
    fun `a fall-back day stays open for its twenty-fifth hour`() {
        pairedBond("America/New_York", createdAt = "2026-10-28T12:00:00Z")
        writeAt("2026-11-01T12:00:00Z")

        // 04:00Z on the 2nd is where a fixed 24 hours would have ended it.
        closeAt("2026-11-02T04:00:00Z")
        statusOf("2026-11-01") shouldBe "PARTIAL"
        closesExactlyAt("2026-11-01", "2026-11-02T05:00:00Z")
    }

    /** `Asia/Kathmandu`, UTC+5:45: the 15th ends at 18:15Z. */
    @Test
    fun `Kathmandu's day closes on the quarter hour`() {
        pairedBond("Asia/Kathmandu", createdAt = "2026-09-11T12:00:00Z")
        writeAt("2026-09-15T10:00:00Z")

        closesExactlyAt("2026-09-15", "2026-09-15T18:15:00Z")
    }

    /** `Pacific/Chatham` in standard time, UTC+12:45: the 15th is `[09-14T11:15Z, 09-15T11:15Z)`. */
    @Test
    fun `Chatham's day closes at a quarter past, twelve and three quarter hours ahead`() {
        pairedBond("Pacific/Chatham", createdAt = "2026-09-11T12:00:00Z")
        writeAt("2026-09-15T00:00:00Z")

        closesExactlyAt("2026-09-15", "2026-09-15T11:15:00Z")
    }

    /**
     * Two people twenty-two hours apart write on one Bond-day: the bond's
     * calendar decides, not either of theirs. The day both wrote on is
     * revealed when the second writes and closed at the bond's midnight.
     */
    @Test
    fun `a day two far-apart members both wrote on closes once, at the bond's midnight`() {
        pairedBond("Africa/Lagos", createdAt = "2026-09-11T12:00:00Z")
        writeAt("2026-09-14T23:30:00Z", by = ada) // 12:30 on the 15th in Kiritimati, 00:30 in Lagos
        writeAt("2026-09-15T22:30:00Z", by = bea) // 11:30 on the 15th in Pago Pago, 23:30 in Lagos
        statusOf("2026-09-15") shouldBe "REVEALED"

        closeAt("2026-09-15T23:00:59Z")
        closedAtOf("2026-09-15") shouldBe null
        closeAt("2026-09-15T23:01:00Z")
        closedAtOf("2026-09-15") shouldBe Instant.parse("2026-09-15T23:01:00Z")
        statusOf("2026-09-15") shouldBe "REVEALED"
    }

    /**
     * Pago Pago (UTC−11) to Kiritimati (UTC+14), confirmed on the 15th. The
     * change takes hold when Pago Pago's 15th ends, 11:00Z on the 16th — by
     * which time it is already the 17th in Kiritimati. No instant is ever
     * dated the 16th.
     */
    @Test
    fun `an eastward crossing - the day before closes at the handoff, and the skipped date is FROZEN with no length`() {
        pairedBond("Pacific/Pago_Pago", createdAt = "2026-09-13T12:00:00Z")
        writeAt("2026-09-15T19:00:00Z") // 08:00 on the 15th, Pago Pago
        clock.set(Instant.parse("2026-09-15T20:00:00Z"))
        changeZone("Pacific/Kiritimati")

        closesExactlyAt("2026-09-15", HANDOFF_EAST)
        // The 16th was stepped over at the handoff, and is on record as soon
        // as the handoff is: FROZEN, before the day after it has ended.
        statusOf("2026-09-16") shouldBe "FROZEN"
        jdbc.queryForObject("SELECT count(*) FROM bond_days WHERE date = '2026-09-17'", Int::class.java) shouldBe 0

        // Kiritimati's 17th runs from the handoff to its own midnight, 10:00Z on the 17th.
        closeAt("2026-09-17T10:01:00Z")

        statusOf("2026-09-17") shouldBe "EMPTY"
        statusOf("2026-09-16") shouldBe "FROZEN"
        jdbc
            .queryForMap(
                "SELECT starts_at, ends_at, anchor_timezone, closed_at IS NOT NULL AS closed " +
                    "FROM bond_days WHERE date = '2026-09-16'",
            ).let {
                (it["starts_at"] as java.sql.Timestamp).toInstant() shouldBe Instant.parse(HANDOFF_EAST)
                (it["ends_at"] as java.sql.Timestamp).toInstant() shouldBe Instant.parse(HANDOFF_EAST)
                it["anchor_timezone"] shouldBe "Pacific/Kiritimati"
                it["closed"] shouldBe true
            }
        runningAgainChangesNothing("2026-09-17T10:15:00Z")
    }

    /**
     * Kiritimati to Pago Pago, confirmed on Kiritimati's 16th. Moving west
     * would reuse a date, so the day spanning the change runs on instead:
     * the 16th is `[09-15T10:00Z, 09-17T11:00Z)`, forty-nine hours.
     */
    @Test
    fun `a westward crossing - a row opened before the change is not closed at its old midnight`() {
        pairedBond("Pacific/Kiritimati", createdAt = "2026-09-12T12:00:00Z")
        writeAt("2026-09-15T12:00:00Z") // 02:00 on the 16th, Kiritimati: the row is opened ending 10:00Z on the 16th
        clock.set(Instant.parse("2026-09-15T20:00:00Z"))
        changeZone("Pacific/Pago_Pago")
        endsAtOf("2026-09-16") shouldBe Instant.parse("2026-09-16T10:00:00Z")

        // The old midnight. The job looks, because the stored end has passed —
        // and finds the day has twenty-five hours still to run.
        closeAt("2026-09-16T10:01:00Z")
        statusOf("2026-09-16") shouldBe "PARTIAL"
        endsAtOf("2026-09-16") shouldBe Instant.parse(HANDOFF_WEST)

        closesExactlyAt("2026-09-16", HANDOFF_WEST)
        // One row for the 16th, not two: no date is used twice.
        jdbc.queryForObject("SELECT count(*) FROM bond_days WHERE date = '2026-09-16'", Int::class.java) shouldBe 1
        runningAgainChangesNothing("2026-09-17T11:15:00Z")
    }

    /**
     * Still as the entry left it one second before [end], and for the minute
     * after it (`DayCloser.SETTLE_MARGIN`); `SOLO` and closed once that
     * minute is up.
     */
    private fun closesExactlyAt(
        date: String,
        end: String,
    ) {
        val settles = Instant.parse(end).plus(DayCloser.SETTLE_MARGIN)
        for (tooSoon in listOf(Instant.parse(end).minusSeconds(1), settles.minusSeconds(1))) {
            closeAt(tooSoon.toString())
            statusOf(date) shouldBe "PARTIAL"
            closedAtOf(date) shouldBe null
        }

        closeAt(settles.toString())
        statusOf(date) shouldBe "SOLO"
        closedAtOf(date) shouldBe settles
    }

    /** Spec §9, "close-job idempotency": a second run finds nothing. */
    private fun runningAgainChangesNothing(at: String) {
        val before = jdbc.queryForList("SELECT date, status, ends_at, closed_at, entry_count FROM bond_days ORDER BY date")
        val again = closer.closeElapsedDays(Instant.parse(at), BUDGET)

        (again.created to again.closed) shouldBe (0 to 0)
        jdbc.queryForList("SELECT date, status, ends_at, closed_at, entry_count FROM bond_days ORDER BY date") shouldBe before
    }

    private fun closeAt(at: String) {
        closer.closeElapsedDays(Instant.parse(at), BUDGET).failed shouldBe 0
    }

    private fun statusOf(date: String): String? =
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date", String::class.java, bondId, date)

    private fun closedAtOf(date: String): Instant? =
        jdbc
            .queryForObject(
                "SELECT closed_at FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date",
                java.sql.Timestamp::class.java,
                bondId,
                date,
            )?.toInstant()

    private fun endsAtOf(date: String): Instant? =
        jdbc
            .queryForObject(
                "SELECT ends_at FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date",
                java.sql.Timestamp::class.java,
                bondId,
                date,
            )?.toInstant()

    private fun writeAt(
        at: String,
        by: UUID = ada,
    ) {
        clock.set(Instant.parse(at))
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(by))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"thank you"}"""
            }.andReturn()
            .response.status shouldBe 201
    }

    /** A two-member bond anchored in [zone], created and joined at [createdAt]. */
    private fun pairedBond(
        zone: String,
        createdAt: String,
    ) {
        clock.set(Instant.parse(createdAt))
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"$zone"}"""
                }.andReturn()
                .response
        created.status shouldBe 201
        bondId = Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
    }

    /** Two-party consent (ADR-0030): Ada proposes, Bea confirms, at the clock's current instant. */
    private fun changeZone(zone: String) {
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(ada))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response.status shouldBe 200
        val proposalId =
            jdbc.queryForObject(
                "SELECT id FROM bond_proposals WHERE bond_id = ?::uuid AND kind = 'TIMEZONE_CHANGE' ORDER BY proposed_at DESC LIMIT 1",
                UUID::class.java,
                bondId,
            )
        mockMvc
            .post("/api/v1/bonds/$bondId/timezone/confirm") {
                header(HttpHeaders.AUTHORIZATION, bearer(bea))
                contentType = MediaType.APPLICATION_JSON
                content = """{"proposalId":"$proposalId"}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = Instant.parse("2026-01-01T00:00:00Z"))
    }

    private companion object {
        /** Pago Pago's midnight ending its 15th. */
        const val HANDOFF_EAST = "2026-09-16T11:00:00Z"

        /** Pago Pago's midnight opening its 17th. */
        const val HANDOFF_WEST = "2026-09-17T11:00:00Z"
        const val BUDGET = 1_000
    }
}
