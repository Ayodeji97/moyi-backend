package com.moyi.gratitude.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DailyReminders
import com.moyi.gratitude.api.ReminderRun
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import com.moyi.notification.api.HeldPushes
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The daily reminder (spec §4 N2, FR-076, ADR-0045), from the members' rows to
 * the ledger: `bond`'s roster says whose reminder fell, this module asks
 * whether they wrote, and `notification` records the push it will send.
 *
 * `Africa/Lagos`, UTC+1, for the bond and both members: the bond is paired at
 * 10:00Z on 2026-09-01 and the reminder set to 21:00 there, 20:00Z, so the
 * sweep asked about `(19:55Z, 20:00Z]` finds it. The clock configuration is
 * `StreakEvaluationTest`'s, so the contexts are shared.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakEvaluationTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named.
internal class DailyRemindersTest(
    @Autowired private val reminders: DailyReminders,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val held: HeldPushes,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(Instant.parse("2026-09-01T10:00:00Z"))
        val created = createBond(ada)
        bond = idOf(created)
        accept(bea, codeOf(created))
        listOf(ada, bea).forEach { settings(it, """{"reminderTimeLocal":"21:00","reminderTimezone":"Africa/Lagos"}""") }
    }

    @Test
    fun `a member who wrote gets no reminder, and one who did not gets one`() {
        write(ada)

        sweep() shouldBe ReminderRun(due = 2, requested = 1, alreadyWritten = 1, failed = 0)

        reminded() shouldBe listOf(bea)
    }

    @Test
    fun `whether the other member has written changes nothing about a member's reminder`() {
        // FR-076, T-09: a reminder that came, or did not, or came worded otherwise,
        // when the partner had written would tell the member so.
        sweep()
        val nobodyWrote = rowOf(ada)
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        write(bea)
        sweep()

        reminded() shouldBe listOf(ada)
        rowOf(ada) shouldBe nobodyWrote
    }

    @Test
    fun `the reminder is asked for once a bond-day, however often its window is swept`() {
        sweep().requested shouldBe 2
        sweep() shouldBe ReminderRun(due = 2, requested = 0, alreadyWritten = 0, failed = 0)
        jdbc.queryForObject("SELECT count(*) FROM notifications", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'PushRequested'", Int::class.java) shouldBe 2
    }

    @Test
    fun `a member who wrote and deleted the entry is reminded`() {
        val entry = write(ada)
        mockMvc
            .delete("/api/v1/entries/$entry") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response.status shouldBe 204

        sweep()

        reminded().toSet() shouldBe setOf(ada, bea)
    }

    @Test
    fun `the push carries the member's settings, its key, and goes stale when the bond-day ends`() {
        settings(
            ada,
            """{"reminderTimeLocal":"21:00","reminderTimezone":"Africa/Lagos","quietHoursStart":"23:00","quietHoursEnd":"07:00"}""",
        )
        notifications(bea, """{"categories":{"reminder":false}}""")

        sweep()

        val row = rowOf(ada)
        row["category"] shouldBe "REMINDER"
        row["dedupe_key"] shouldBe "reminder:${memberIdOf(ada)}:2026-09-01"
        row["stale_after"] shouldBe Timestamp.from(Instant.parse("2026-09-01T23:00:00Z"))
        row["zone"] shouldBe "Africa/Lagos"
        row["quiet_hours_start"].toString() shouldBe "23:00:00"
        row["wanted"] shouldBe true
        // Bea turned reminders off: asked for all the same, and recorded unwanted, so the push consumer suppresses it.
        rowOf(bea)["wanted"] shouldBe false
    }

    @Test
    fun `a reminder held by quiet hours is called off when the member writes before they end`() {
        // The review's scenario: a 06:30 reminder, quiet hours 22:00 to 07:00, she writes at 06:45,
        // and must not be told to write at 07:00. Lagos is UTC+1: 06:30 there is 05:30Z.
        settings(
            ada,
            """{"reminderTimeLocal":"06:30","reminderTimezone":"Africa/Lagos","quietHoursStart":"22:00","quietHoursEnd":"07:00"}""",
        )
        val reminderAt = Instant.parse("2026-09-02T05:30:00Z")
        clock.set(reminderAt)
        reminders.remindDue(reminderAt.minusSeconds(300), reminderAt).requested shouldBe 1
        dispatcher.dispatchDue(reminderAt, DISPATCH)
        rowOf(ada)["status"] shouldBe "HELD"

        clock.set(Instant.parse("2026-09-02T05:45:00Z"))
        write(ada)

        rowOf(ada)["status"] shouldBe "CANCELLED"
        // 07:00 there: the hold ends, and there is nothing to release or send.
        val seven = Instant.parse("2026-09-02T06:00:00Z")
        clock.set(seven)
        held.releaseDue(seven, DISPATCH) shouldBe 0
        dispatcher.dispatchDue(seven, DISPATCH)
        rowOf(ada)["status"] shouldBe "CANCELLED"
        // A later sweep of the same window asks for nothing: the key is spent.
        reminders.remindDue(reminderAt.minusSeconds(300), reminderAt).requested shouldBe 0
    }

    @Test
    fun `an entry calls off only its own author's reminder for that day`() {
        sweep()
        clock.set(Instant.parse("2026-09-01T19:58:00Z"))
        write(bea)

        rowOf(bea)["status"] shouldBe "CANCELLED"
        rowOf(ada)["status"] shouldBe "REQUESTED"
    }

    @Test
    fun `a tick missed in a restart is caught up by the next, with exactly one push each`() {
        // Both reminders fall at 20:00Z. The 20:00 tick never ran; the job's next two runs look
        // back half an hour (ReminderJob.LOOKBACK), so both cover 20:00, and the key keeps it one.
        reminders.remindDue(Instant.parse("2026-09-01T19:35:00Z"), Instant.parse("2026-09-01T20:05:00Z")).requested shouldBe 2
        reminders.remindDue(Instant.parse("2026-09-01T19:40:00Z"), Instant.parse("2026-09-01T20:10:00Z")).requested shouldBe 0

        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE category = 'REMINDER'", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'PushRequested'", Int::class.java) shouldBe 2
    }

    @Test
    fun `nobody is reminded outside the window their reminder falls in`() {
        reminders.remindDue(Instant.parse("2026-09-01T20:00:00Z"), Instant.parse("2026-09-01T20:05:00Z")).due shouldBe 0
        reminded().shouldBeEmpty()
    }

    private fun sweep(): ReminderRun = reminders.remindDue(Instant.parse("2026-09-01T19:55:00Z"), Instant.parse("2026-09-01T20:00:00Z"))

    private fun reminded(): List<UUID> =
        jdbc
            .queryForList(
                "SELECT user_id FROM notifications WHERE category = 'REMINDER' ORDER BY user_id",
                UUID::class.java,
            ).filterNotNull()

    /** The member's reminder row, less the columns that differ between any two requests: ids and when it was made. */
    private fun rowOf(userId: UUID): Map<String, Any?> =
        jdbc.queryForMap("SELECT * FROM notifications WHERE user_id = ?", userId) - setOf("id", "created_at", "not_before")

    private fun memberIdOf(userId: UUID): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_members WHERE bond_id = ? AND user_id = ?",
            UUID::class.java,
            UUID.fromString(bond),
            userId,
        )!!

    /** Writes today's entry for [author] and returns its id. */
    private fun write(author: UUID): String {
        val response =
            mockMvc
                .post("/api/v1/bonds/$bond/entries") {
                    header(HttpHeaders.AUTHORIZATION, bearer(author))
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"text":"thank you"}"""
                }.andReturn()
                .response
        response.status shouldBe 201
        return idOf(response)
    }

    private fun settings(
        userId: UUID,
        body: String,
    ) {
        mockMvc
            .put("/api/v1/bonds/$bond/members/me/settings") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun notifications(
        userId: UUID,
        body: String,
    ) {
        mockMvc
            .put("/api/v1/bonds/$bond/members/me/notifications") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    private fun accept(
        userId: UUID,
        code: String,
    ) {
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        const val DISPATCH = 100
    }
}
