package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.CloseDay.Outcome
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Spec §6.4 step 2, one day at a time. The days are made by real requests
 * (and, where no request can make the state any more, by the same hand-made
 * rows `JoiningDayTest` uses); then [CloseDay.settle] is called with an
 * instant, which is all the close job will ever do with it.
 *
 * Every day here is 2026-09-15 in `Africa/Lagos`, 23:00Z on the 14th to
 * 23:00Z on the 15th, unless a test says otherwise.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(CloseDayTest.TimeConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
internal class CloseDayTest(
    @Autowired private val closeDay: CloseDay,
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

    @BeforeEach
    fun setUp() {
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    // --- the table: what the end of a day makes of it ---

    @Test
    fun `a day nobody ended up writing on closes EMPTY`() {
        val bond = pairedBond()
        deleteEntry(ada, idOf(submit(ada, bond, """{"text":"thought better of it"}"""))).status shouldBe 204
        status(bond) shouldBe "OPEN"

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

        status(bond) shouldBe "EMPTY"
        closedAt(bond) shouldBe END
        events("DayClosed") shouldBe 1
    }

    @Test
    fun `a day one member wrote on closes SOLO and their entry is revealed`() {
        val bond = pairedBond()
        submit(ada, bond, """{"text":"hers, alone today"}""").status shouldBe 201

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

        status(bond) shouldBe "SOLO"
        jdbc.queryForObject("SELECT revealed_at FROM entries", Timestamp::class.java)?.toInstant() shouldBe END
        jdbc.queryForObject("SELECT status FROM entries", String::class.java) shouldBe "REVEALED"
        // The day was not read by two people together, and does not say it was.
        jdbc.queryForObject("SELECT count(*) FROM bond_days WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 0
        events("DayClosed") shouldBe 1
        events("DayRevealed") shouldBe 0
    }

    @Test
    fun `a day both wrote on, revealed while it was open, gains closedAt and one DayClosed`() {
        val bond = pairedBond()
        submit(ada, bond, """{"text":"hers"}""").status shouldBe 201
        submit(bea, bond, """{"text":"his"}""").status shouldBe 201
        val revealedAt = jdbc.queryForObject("SELECT revealed_at FROM bond_days", Timestamp::class.java)

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

        status(bond) shouldBe "REVEALED"
        closedAt(bond) shouldBe END
        jdbc.queryForObject("SELECT revealed_at FROM bond_days", Timestamp::class.java) shouldBe revealedAt
        events("DayRevealed") shouldBe 1
        events("DayClosed") shouldBe 1
    }

    @Test
    fun `a day that has not ended is left exactly as it was`() {
        val bond = pairedBond()
        submit(ada, bond, """{"text":"hers"}""").status shouldBe 201
        val before = jdbc.queryForMap("SELECT status, entry_count, ends_at, closed_at FROM bond_days")

        closeDay.settle(dayOf(bond), END.minusSeconds(1)) shouldBe Outcome.NOT_YET

        jdbc.queryForMap("SELECT status, entry_count, ends_at, closed_at FROM bond_days") shouldBe before
        events("DayClosed") shouldBe 0
    }

    @Test
    fun `closing twice closes once`() {
        val bond = pairedBond()
        submit(ada, bond, """{"text":"hers"}""").status shouldBe 201

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED
        closeDay.settle(dayOf(bond), END.plusSeconds(900)) shouldBe Outcome.ALREADY_CLOSED

        closedAt(bond) shouldBe END
        events("DayClosed") shouldBe 1
    }

    // --- the second sweep: a reveal time that came while nobody was submitting (spec §6.3, row 4) ---

    @Test
    fun `a PENDING_REVEAL day is left until its time, revealed at it, and closed at its end`() {
        val bond = pairedBond()
        jdbc.update("UPDATE bonds SET reveal_time_local = '20:00' WHERE id = ?::uuid", bond)
        submit(ada, bond, """{"text":"hers"}""").status shouldBe 201
        submit(bea, bond, """{"text":"his"}""").status shouldBe 201
        status(bond) shouldBe "PENDING_REVEAL"
        val due = Instant.parse("2026-09-15T19:00:00Z")

        closeDay.settle(dayOf(bond), due.minusSeconds(1)) shouldBe Outcome.NOT_YET
        status(bond) shouldBe "PENDING_REVEAL"

        closeDay.settle(dayOf(bond), due.plusSeconds(60)) shouldBe Outcome.REVEALED
        status(bond) shouldBe "REVEALED"
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE revealed_at = ?",
            Int::class.java,
            Timestamp.from(due.plusSeconds(60)),
        ) shouldBe 2
        closedAt(bond) shouldBe null
        events("DayRevealed") shouldBe 1

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED
        events("DayRevealed") shouldBe 1
        events("DayClosed") shouldBe 1
    }

    @Test
    fun `a day still pending at its end is revealed once and closed, in one run`() {
        val bond = pairedBond()
        // 23:59 in Lagos is 22:59Z: nobody ran the sweep in the last minute
        // of the day, so the first run to see it is already past its end.
        jdbc.update("UPDATE bonds SET reveal_time_local = '23:59' WHERE id = ?::uuid", bond)
        submit(ada, bond, """{"text":"hers"}""").status shouldBe 201
        submit(bea, bond, """{"text":"his"}""").status shouldBe 201
        status(bond) shouldBe "PENDING_REVEAL"

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

        status(bond) shouldBe "REVEALED"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL AND status = 'REVEALED'", Int::class.java) shouldBe
            2
        events("DayRevealed") shouldBe 1
        events("DayClosed") shouldBe 1
    }

    // --- a stale span: the day ends when the timeline says, not when the row said ---

    @Test
    fun `a row whose stored end is behind the timeline is extended first, and closes at the timeline's end`() {
        val bond = pairedBond()
        submit(ada, bond, """{"text":"hers"}""").status shouldBe 201
        // What a row opened before a westward zone change holds: an end two
        // hours short of where the bond's calendar now runs the day to.
        val staleEnd = END.minusSeconds(7_200)
        jdbc.update("UPDATE bond_days SET ends_at = ? WHERE bond_id = ?::uuid", Timestamp.from(staleEnd), bond)

        closeDay.settle(dayOf(bond), staleEnd.plusSeconds(60)) shouldBe Outcome.NOT_YET

        status(bond) shouldBe "PARTIAL"
        jdbc.queryForObject("SELECT ends_at FROM bond_days", Timestamp::class.java)?.toInstant() shouldBe END
        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED
        status(bond) shouldBe "SOLO"
    }

    // --- the joining day is evaluated before it is closed; earlier days are closed unevaluated ---

    @Test
    fun `a joining day one member wrote on closes SOLO, not suspended for ever`() {
        clock.set(NOW)
        val created = createBond(ada)
        val bond = idOf(created)
        submit(ada, bond, """{"text":"written while waiting"}""").status shouldBe 201
        accept(bea, codeOf(created)).status shouldBe 200
        status(bond) shouldBe "SUSPENDED"

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

        status(bond) shouldBe "SOLO"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `a joining day both wrote on before the reveal existed is revealed and then closed`() {
        clock.set(NOW)
        val created = createBond(ada)
        val bond = idOf(created)
        submit(ada, bond, """{"text":"hers"}""").status shouldBe 201
        accept(bea, codeOf(created)).status shouldBe 200
        submit(bea, bond, """{"text":"his"}""").status shouldBe 201
        // What C1 committed: both entries in, the day still SUSPENDED, nothing revealed.
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", bond)
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", bond)
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

        status(bond) shouldBe "REVEALED"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 2
        events("DayRevealed") shouldBe 1
        events("DayClosed") shouldBe 1
    }

    @Test
    fun `a day from before the pairing is closed as it stands - still suspended, nothing revealed, no event`() {
        clock.set(NOW.minusSeconds(86_400))
        val created = createBond(ada)
        val bond = idOf(created)
        submit(ada, bond, """{"text":"written the day before he came"}""").status shouldBe 201
        clock.set(NOW)
        accept(bea, codeOf(created)).status shouldBe 200

        // The 14th, which ended at 23:00Z on the 14th: long over by END.
        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

        status(bond) shouldBe "SUSPENDED"
        jdbc.queryForObject("SELECT closed_at IS NOT NULL FROM bond_days", Boolean::class.java) shouldBe true
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 0
        events("DayClosed") shouldBe 0
    }

    @Test
    fun `a day on a bond still waiting for its partner is closed suspended, its entry still its author's alone`() {
        clock.set(NOW)
        val bond = idOf(createBond(ada))
        submit(ada, bond, """{"text":"written while waiting"}""").status shouldBe 201

        closeDay.settle(dayOf(bond), END) shouldBe Outcome.CLOSED

        status(bond) shouldBe "SUSPENDED"
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 0
    }

    // --- what two people wrote does not leave with a failure ---

    @Test
    fun `a constraint failure while closing exposes nobody's words and closes nothing`(output: CapturedOutput) {
        val bond = pairedBond()
        submit(ada, bond, """{"text":"privacy-canary-solo"}""").status shouldBe 201
        jdbc.execute("ALTER TABLE entries ADD CONSTRAINT c3_close_probe CHECK (revealed_at IS NULL)")
        try {
            val failure = shouldThrow<DataIntegrityViolationException> { closeDay.settle(dayOf(bond), END) }

            failure.message.orEmpty() shouldContain "c3_close_probe"
            failure.message.orEmpty() shouldNotContain "privacy-canary"
            failure.cause shouldBe null
            output.all shouldNotContain "privacy-canary"
        } finally {
            jdbc.execute("ALTER TABLE entries DROP CONSTRAINT c3_close_probe")
        }
        status(bond) shouldBe "PARTIAL"
        events("DayClosed") shouldBe 0
    }

    /** Ada and Bea, paired two days ago, so 2026-09-15 is an ordinary day; the clock is left at [NOW]. */
    private fun pairedBond(): String {
        clock.set(NOW.minusSeconds(172_800))
        val created = createBond(ada)
        accept(bea, codeOf(created)).status shouldBe 200
        clock.set(NOW)
        return idOf(created)
    }

    private fun dayOf(bond: String): BondDayId =
        BondDayId(jdbc.queryForObject("SELECT id FROM bond_days WHERE bond_id = ?::uuid", UUID::class.java, bond)!!)

    private fun status(bond: String): String? =
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid", String::class.java, bond)

    private fun closedAt(bond: String): Instant? =
        jdbc.queryForObject("SELECT closed_at FROM bond_days WHERE bond_id = ?::uuid", Timestamp::class.java, bond)?.toInstant()

    private fun events(type: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)

    private fun submit(
        caller: UUID,
        bondId: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun deleteEntry(
        user: UUID,
        id: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$id") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
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

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = NOW)
    }

    private companion object {
        /** 11:00 in Africa/Lagos on 2026-09-15. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** Midnight in Lagos between the 15th and the 16th: the first instant the 15th is over. */
        val END: Instant = Instant.parse("2026-09-15T23:00:00Z")
    }
}
