package com.moyi.gratitude.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.OnThisDayPushes
import com.moyi.gratitude.api.OnThisDayRun
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import com.moyi.notification.api.HeldPushes
import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushToken
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The on-this-day push (FR-092, ADR-0046): opt-in, private, at most one in
 * seven days, and only when `GET /on-this-day` would answer the member with a day.
 *
 * `Africa/Lagos`, UTC+1: the bond is made on 2025-09-01 and the memories are
 * written on 1, 2, 7 and 8 September 2025; the push time is midday there,
 * 11:00Z, so the sweep asked about `(10:00Z, 11:00Z]` a year later finds it.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakEvaluationTest.TimeConfiguration::class)
@Suppress("LongParameterList") // A test's collaborators, each named.
internal class OnThisDayPushesTest(
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val held: HeldPushes,
    @Autowired private val pushTokens: PushTokens,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val pushes: OnThisDayPushes,
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
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(Instant.parse("2025-09-01T10:00:00Z"))
        val created = createBond(ada)
        bond = idOf(created)
        accept(bea, codeOf(created))
        // Ada writes on four days a year back; Bea writes nothing, and no day is ever closed, so
        // none of Ada's words is ever opened to her.
        listOf("2025-09-01", "2025-09-02", "2025-09-07", "2025-09-08").forEach { date ->
            clock.set(Instant.parse("${date}T12:00:00Z"))
            write(ada)
        }
        notifications(ada, """{"categories":{"onThisDay":true}}""")
        // Her phone, so that a push the consumer sends is SENT and not "no device".
        jdbc.execute("TRUNCATE TABLE push_tokens")
        TransactionTemplate(transactionManager).executeWithoutResult {
            pushTokens.register(PushTokenRegistration(UUID.randomUUID(), ada, PushPlatform.ANDROID, PushToken("fake-push-token-otd-0001")))
        }
    }

    @Test
    fun `a member who opted in and has a memory today is asked one push, stale at the end of the bond-day`() {
        clock.set(Instant.parse("2026-09-01T11:00:00Z"))
        onThisDay(ada) shouldContain """"date":"2025-09-01""""

        sweep("2026-09-01") shouldBe OnThisDayRun(due = 1, requested = 1, nothingToRemember = 0, failed = 0)

        val row = jdbc.queryForMap("SELECT * FROM notifications")
        row["user_id"] shouldBe ada
        row["category"] shouldBe "ON_THIS_DAY"
        row["dedupe_key"] shouldBe "on-this-day:${memberIdOf(ada)}:2026-09-01"
        row["stale_after"] shouldBe Timestamp.from(Instant.parse("2026-09-01T23:00:00Z"))
    }

    @Test
    fun `a push sent on day one spends the next six days, and the eighth day is told again`() {
        sweep("2026-09-01").requested shouldBe 1
        dispatchAll()
        statusOn("2026-09-01") shouldBe "SENT"
        sweep("2026-09-01").requested shouldBe 0

        // Memories on the 2nd and the 7th: both inside the seven days, so both ask nothing.
        sweep("2026-09-02") shouldBe OnThisDayRun(due = 1, requested = 0, nothingToRemember = 0, failed = 0)
        sweep("2026-09-07") shouldBe OnThisDayRun(due = 1, requested = 0, nothingToRemember = 0, failed = 0)
        sweep("2026-09-08").requested shouldBe 1

        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Int::class.java, ada) shouldBe 2
    }

    @Test
    fun `a push that expired in quiet hours spends nothing, and the next day's memory is told`() {
        // The review of #100: quiet hours from 11:30 to 00:30 cover her midday and run past the
        // bond's midnight. The first day's push is held, then expires at the day's end. Under a
        // key per week it would have taken her week with it, and she would never be told.
        settings(
            ada,
            """{"reminderTimeLocal":"21:00","reminderTimezone":"Africa/Lagos","quietHoursStart":"11:30","quietHoursEnd":"00:30"}""",
        )
        clock.set(Instant.parse("2026-09-01T10:45:00Z")) // 11:45 in Lagos, inside her hours
        pushes.pushDue(Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z")).requested shouldBe 1
        dispatchAll()
        statusOn("2026-09-01") shouldBe "HELD"
        val released = Instant.parse("2026-09-01T23:31:00Z") // 00:31 in Lagos: her hours are over, and so is the day
        clock.set(released)
        held.releaseDue(released, DISPATCH) shouldBe 1
        dispatchAll()
        statusOn("2026-09-01") shouldBe "EXPIRED"

        settings(ada, """{"reminderTimeLocal":"21:00","reminderTimezone":"Africa/Lagos"}""")
        sweep("2026-09-02").requested shouldBe 1
        dispatchAll()
        statusOn("2026-09-02") shouldBe "SENT"
    }

    @Test
    fun `a push the budget dropped spends nothing`() {
        sweep("2026-09-01").requested shouldBe 1
        // As the consumer leaves it when FR-082's budget is spent: dropped, never sent.
        jdbc.update("UPDATE notifications SET status = 'DROPPED_BUDGET'") shouldBe 1

        sweep("2026-09-02").requested shouldBe 1
    }

    @Test
    fun `a day with nothing to remember asks for nothing, exactly when the endpoint would answer empty`() {
        clock.set(Instant.parse("2026-09-03T11:00:00Z"))
        onThisDay(ada) shouldBe """{"items":[]}"""

        sweep("2026-09-03") shouldBe OnThisDayRun(due = 1, requested = 0, nothingToRemember = 1, failed = 0)
        notified().shouldBeEmpty()
    }

    @Test
    fun `it is private - a partner who opted in is not told of words they cannot read`() {
        // Bea opts in too. A year ago only Ada wrote, and nothing of it was ever opened to Bea:
        // her own on-this-day answers empty, and so she is told nothing.
        notifications(bea, """{"categories":{"onThisDay":true}}""")
        clock.set(Instant.parse("2026-09-01T11:00:00Z"))
        onThisDay(bea) shouldBe """{"items":[]}"""

        sweep("2026-09-01") shouldBe OnThisDayRun(due = 2, requested = 1, nothingToRemember = 1, failed = 0)

        notified() shouldBe listOf(ada)
    }

    @Test
    fun `a midday whose bond-day has ended by the sweep spends nothing, and the next day's memory is still told`() {
        // The review of #100: the sweep looks back an hour, so a midday can be swept after its
        // bond-day ended. Here 1 September's window is swept after the bond's midnight (23:00Z in
        // Lagos), when `GET /on-this-day` answers for 2 September instead. Asked under 1
        // September's key and deadline, that push would be expired at once and spend the week's
        // key, and 2 September's memory would never be told.
        clock.set(Instant.parse("2026-09-01T23:00:01Z"))
        pushes.pushDue(Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z")) shouldBe
            OnThisDayRun(due = 1, requested = 0, nothingToRemember = 0, failed = 0)
        notified().shouldBeEmpty()

        sweep("2026-09-02").requested shouldBe 1
    }

    @Test
    fun `a member who did not opt in is never asked about`() {
        notifications(ada, """{"categories":{"onThisDay":false}}""")

        sweep("2026-09-01") shouldBe OnThisDayRun(due = 0, requested = 0, nothingToRemember = 0, failed = 0)
    }

    @Test
    fun `nobody is told on a bond that has ended`() {
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        sweep("2026-09-01").requested shouldBe 0
        notified().shouldBeEmpty()
    }

    @Test
    fun `nobody is asked about outside the hour their midday falls in`() {
        clock.set(Instant.parse("2026-09-01T12:00:00Z"))
        pushes.pushDue(Instant.parse("2026-09-01T11:00:00Z"), Instant.parse("2026-09-01T12:00:00Z")).due shouldBe 0
    }

    /** The sweep of the hour that holds midday in Lagos (11:00Z) on [date], run at that hour's end. */
    private fun sweep(date: String): OnThisDayRun {
        clock.set(Instant.parse("${date}T11:00:00Z"))
        return pushes.pushDue(Instant.parse("${date}T10:00:00Z"), Instant.parse("${date}T11:00:00Z"))
    }

    /** Runs the outbox until it has nothing due: the push request, then its send. */
    private fun dispatchAll() {
        repeat(DISPATCH_ROUNDS) { dispatcher.dispatchDue(clock.instant().plusSeconds(1), DISPATCH) }
    }

    /** The status of Ada's on-this-day push asked on the bond-day [date]. */
    private fun statusOn(date: String): String =
        jdbc.queryForObject("SELECT status FROM notifications WHERE dedupe_key LIKE ?", String::class.java, "on-this-day:%:$date")!!

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

    private fun notified(): List<UUID> = jdbc.queryForList("SELECT user_id FROM notifications", UUID::class.java).filterNotNull()

    private fun onThisDay(userId: UUID): String =
        mockMvc
            .get("/api/v1/bonds/$bond/on-this-day") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response
            .also { it.status shouldBe 200 }
            .contentAsString

    private fun memberIdOf(userId: UUID): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_members WHERE bond_id = ? AND user_id = ?",
            UUID::class.java,
            UUID.fromString(bond),
            userId,
        )!!

    private fun write(author: UUID) {
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(author))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"a year ago today"}"""
            }.andReturn()
            .response.status shouldBe 201
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
        const val DISPATCH_ROUNDS = 3
    }
}
