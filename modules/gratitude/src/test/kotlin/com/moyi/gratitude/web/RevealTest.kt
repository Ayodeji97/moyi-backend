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
import io.kotest.matchers.string.shouldMatch
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Spec §6.3, the reveal, by the requests that cause it — no row is set by
 * hand here, apart from the bond's `reveal_time_local` (a setting, not a
 * state) and one probe constraint. `RevealGateTest` holds the gate against
 * every state a day can be in by writing those states directly; this class
 * is the other half: that the states the gate is tested against are the ones
 * two people's requests actually produce, and what deleting does around them.
 *
 * The clock stands at 11:00 in `Africa/Lagos` on 2026-09-15: a reveal time of
 * 20:00 has not come, and one of 08:00 has passed.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(RevealTest.TimeConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
internal class RevealTest(
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
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(BOND_CREATED)
        val created = createBond(ada)
        bondId = idOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    // --- the three ways a second entry can leave a day (spec §6.3's table) ---

    @Test
    fun `once both have written each reads the other's words`() {
        submit(ada, """{"text":"hers, for him"}""").status shouldBe 201
        submit(bea, """{"text":"his, for her"}""").status shouldBe 201

        val adas = today(ada).contentAsString
        val beas = today(bea).contentAsString
        adas shouldContain "\"status\":\"REVEALED\""
        partnerEntryOf(adas) shouldContain "\"text\":\"his, for her\""
        partnerEntryOf(adas) shouldContain "\"status\":\"REVEALED\""
        partnerEntryOf(beas) shouldContain "\"text\":\"hers, for him\""
    }

    @Test
    fun `a timed reveal holds both entries in PENDING_REVEAL until the bond's time`() {
        revealAt("20:00:00")
        val adas = idOf(submit(ada, """{"text":"hers, for him"}"""))
        submit(bea, """{"text":"his, for her"}""").status shouldBe 201

        dayStatus() shouldBe "PENDING_REVEAL"
        jdbc.queryForObject("SELECT count(*) FROM bond_days WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL OR status <> 'SUBMITTED'", Int::class.java) shouldBe
            0
        events("DayRevealed") shouldBe 0
        for (member in listOf(ada, bea)) {
            val seen = today(member).contentAsString
            // FR-062: not PARTIAL, which would tell each of them the other has not written.
            seen shouldContain "\"bondDay\":{\"date\":\"2026-09-15\",\"status\":\"PENDING_REVEAL\"}"
            partnerEntryOf(seen) shouldMatch LOCKED_SHAPE
        }
        // BR-7 forbids editing what the partner may have read. Nobody has read this yet.
        patchEntry(ada, adas, """{"text":"hers, reworded"}""").status shouldBe 200
        today(bea).contentAsString shouldNotContain "reworded"
    }

    @Test
    fun `a reveal time already passed reveals at once, and the day, both entries and the event share one instant`() {
        revealAt("08:00:00")
        submit(ada, """{"text":"hers, for him"}""").status shouldBe 201
        clock.set(NOW.plusSeconds(90))
        submit(bea, """{"text":"his, for her"}""").status shouldBe 201

        dayStatus() shouldBe "REVEALED"
        val revealedAt = jdbc.queryForObject("SELECT revealed_at FROM bond_days", Timestamp::class.java)?.toInstant()
        revealedAt shouldBe NOW.plusSeconds(90)
        jdbc.queryForList("SELECT revealed_at FROM entries", Timestamp::class.java).map { it?.toInstant() } shouldBe
            listOf(revealedAt, revealedAt)
        jdbc
            .queryForList("SELECT occurred_at FROM outbox_events WHERE event_type = 'DayRevealed'", Timestamp::class.java)
            .map { it?.toInstant() } shouldBe listOf(revealedAt)
    }

    // --- deleting, around the reveal ---

    @Test
    fun `deleting on a PENDING_REVEAL day returns it to PARTIAL, and a rewrite follows the reveal rule again`() {
        revealAt("20:00:00")
        val first = idOf(submit(ada, """{"text":"hers, first try"}"""))
        submit(bea, """{"text":"his, for her"}""").status shouldBe 201

        deleteEntry(ada, first).status shouldBe 204

        dayStatus() shouldBe "PARTIAL"
        entryCount() shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE revealed_at IS NOT NULL", Int::class.java) shouldBe 0
        partnerEntryOf(today(ada).contentAsString) shouldMatch LOCKED_SHAPE

        submit(ada, """{"text":"hers, second try"}""").status shouldBe 201

        dayStatus() shouldBe "PENDING_REVEAL"
        entryCount() shouldBe 2
        events("EntrySubmitted") shouldBe 3
        events("DayRevealed") shouldBe 0
    }

    @Test
    fun `after a post-reveal delete the author cannot write that day again, and the day stands as it was revealed`() {
        val adas = idOf(submit(ada, """{"text":"hers, for him"}"""))
        submit(bea, """{"text":"his, for her"}""").status shouldBe 201
        deleteEntry(ada, adas).status shouldBe 204

        // The slot is free (BR-2 counts live rows) but the day is settled: a
        // revealed day is a record, and a second entry would be read as the first.
        val again = submit(ada, """{"text":"hers, a different story"}""")

        again.status shouldBe 409
        again.contentAsString shouldContain "DAY_CLOSED"
        dayStatus() shouldBe "REVEALED"
        entryCount() shouldBe 2
        events("EntrySubmitted") shouldBe 2
        events("DayRevealed") shouldBe 1
    }

    @Test
    fun `an entry deleted after the reveal is a tombstone to both, and the other entry is untouched`() {
        val adas = idOf(submit(ada, """{"text":"hers, for him"}"""))
        submit(bea, """{"text":"his, for her"}""").status shouldBe 201

        deleteEntry(ada, adas).status shouldBe 204

        val forAda = today(ada).contentAsString
        val forBea = today(bea).contentAsString
        forAda shouldNotContain "hers, for him"
        forBea shouldNotContain "hers, for him"
        // Bea had read it, so she is told which entry is gone: the wide tombstone.
        partnerEntryOf(forBea) shouldContain "\"id\":\"$adas\""
        partnerEntryOf(forBea) shouldContain "\"text\":null"
        partnerEntryOf(forBea) shouldContain "\"status\":\"DELETED\""
        partnerEntryOf(forAda) shouldContain "\"text\":\"his, for her\""
    }

    /**
     * What a partner can see of a delete made **before** the reveal, as built
     * (ADR-0031 decision 10 ruled the shape before any request could reach
     * it; ADR-0032 records that C2 makes it reachable and leaves the question
     * with the owner). The day steps back to `OPEN`, and the partner is shown
     * that an entry was removed — its author and that fact, nothing else.
     */
    @Test
    fun `a delete before the reveal shows the partner an author and that it is gone, and nothing else`() {
        val adas = idOf(submit(ada, """{"text":"hers, thought better of"}"""))

        deleteEntry(ada, adas).status shouldBe 204

        val forBea = today(bea).contentAsString
        forBea shouldContain "\"bondDay\":{\"date\":\"2026-09-15\",\"status\":\"OPEN\"}"
        partnerEntryOf(forBea) shouldMatch Regex("""\{"authorMemberId":"[0-9a-f-]{36}","status":"REMOVED"}""")
        forBea shouldNotContain adas
        entryCount() shouldBe 0
    }

    // --- the outbox says what happened, and only that ---

    @Test
    fun `a refused submission leaves no event behind`() {
        submit(ada, """{"text":"hers, for him"}""").status shouldBe 201

        submit(ada, """{"text":"hers, again"}""").status shouldBe 409
        submit(bea, """{"text":"his","imageMediaId":"${UUID.randomUUID()}"}""").status shouldBe 422

        events("EntrySubmitted") shouldBe 1
        events("DayRevealed") shouldBe 0
    }

    /**
     * ADR-0031, Owed: the reveal is the first `UPDATE` of `entries`, and
     * Postgres reports a violated CHECK on an update with the whole failing
     * row — **the partner's words included**, since the reveal writes both
     * rows. The probe constraint below is violated by exactly that update.
     */
    @Test
    fun `a constraint failure during the reveal exposes neither member's words, and reveals nothing`(output: CapturedOutput) {
        submit(ada, """{"text":"privacy-canary-hers"}""").status shouldBe 201
        jdbc.execute("ALTER TABLE entries ADD CONSTRAINT c2_reveal_probe CHECK (revealed_at IS NULL)")
        try {
            val refused = submit(bea, """{"text":"privacy-canary-his"}""")

            refused.status shouldBe 500
            refused.contentAsString shouldNotContain "privacy-canary"
            output.all shouldNotContain "privacy-canary"
            output.all shouldNotContain "Failing row contains"
            output.all shouldContain "c2_reveal_probe"
        } finally {
            jdbc.execute("ALTER TABLE entries DROP CONSTRAINT c2_reveal_probe")
        }
        // One transaction: the second entry, the reveal and both events went together.
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 1
        dayStatus() shouldBe "PARTIAL"
        events("EntrySubmitted") shouldBe 1
        events("DayRevealed") shouldBe 0
    }

    private fun revealAt(localTime: String) {
        jdbc.update("UPDATE bonds SET reveal_time_local = ?::time WHERE id = ?::uuid", localTime, bondId)
    }

    private fun dayStatus(): String? = jdbc.queryForObject("SELECT status FROM bond_days", String::class.java)

    private fun entryCount(): Int? = jdbc.queryForObject("SELECT entry_count FROM bond_days", Int::class.java)

    private fun events(type: String): Int? =
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)

    /** The `partnerEntry` object of a `today` body, as the bytes it was sent as. */
    private fun partnerEntryOf(today: String): String =
        checkNotNull(Regex(""""partnerEntry":(\{[^}]*})""").find(today)) { "no partnerEntry object in: $today" }.groupValues[1]

    private fun patchEntry(
        user: UUID,
        id: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun deleteEntry(
        user: UUID,
        id: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
            }.andReturn()
            .response

    private fun today(user: UUID): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
            }.andReturn()
            .response

    private fun submit(
        caller: UUID,
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
        /** 11:00 in Africa/Lagos on the 15th. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** Two days earlier, so the day under test is an ordinary one, not the joining day. */
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")

        /** BR-8, to the byte: an author and a status, and nothing else. */
        val LOCKED_SHAPE = Regex("""\{"authorMemberId":"[0-9a-f-]{36}","status":"LOCKED"}""")
    }
}
