package com.moyi.gratitude.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.CapturingPushSender
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushToken
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.reflect.full.memberProperties

/**
 * `gratitude.notify` (spec §4 N3, ADR-0046), from the second entry to the
 * payload a phone is handed: the reveal to both members, the solo unlock to
 * the partner who did not write, the milestone to both, and nothing on a
 * bond that takes no writes.
 *
 * `Africa/Lagos`, UTC+1, as `DailyRemindersTest`: the bond is paired at
 * 10:00Z on 2026-09-01, whose bond-day ends at 23:00Z. No reveal time, so
 * the second entry reveals the day.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
// The consumer under test, which the shared context leaves out (GratitudeTestApplication.PUSH_CONSUMERS).
@Import(StreakEvaluationTest.TimeConfiguration::class, NotifyDayMembers::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // for the one clean-up after every test, below
@Suppress("LongParameterList") // A test's collaborators, each named.
internal class DayPushesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val consumer: NotifyDayMembers,
    @Autowired private val consumers: List<EventConsumer>,
    @Autowired private val closer: DayCloser,
    @Autowired private val pushTokens: PushTokens,
    @Autowired private val sender: CapturingPushSender,
    @Autowired transactionManager: PlatformTransactionManager,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(transactionManager)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    /**
     * A consumer's subscription outlives its context: it is a row, and every later publication
     * of its types writes a delivery for it, in whatever context publishes. The shared context
     * leaves this consumer out (GratitudeTestApplication.PUSH_CONSUMERS), and tests run after
     * this class count `outbox_deliveries` rows (`WithdrawalRaceTest`), so its registration is
     * taken back with the deliveries that name it.
     */
    @AfterAll
    fun forgetTheSubscription() {
        jdbc.update("DELETE FROM outbox_deliveries WHERE consumer_id = ?", consumer.id)
        jdbc.update("DELETE FROM outbox_subscriptions WHERE consumer_id = ?", consumer.id)
        jdbc.update("DELETE FROM outbox_consumers WHERE consumer_id = ?", consumer.id)
    }

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE TABLE notifications, push_tokens, outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(Instant.parse("2026-09-01T10:00:00Z"))
        val created = createBond(ada)
        bond = idOf(created)
        accept(bea, codeOf(created))
        // The join's own push (bond.notify) is not this class's subject.
        dispatchAll()
        jdbc.execute("TRUNCATE TABLE notifications")
        sender.sent.clear()
        phone(ada, ADA_TOKEN)
        phone(bea, BEA_TOKEN)
    }

    @Test
    fun `the M4 test - the second entry sends exactly one push to each member, with no content`() {
        clock.set(Instant.parse("2026-09-01T18:00:00Z"))
        write(ada, ADA_WORDS)
        dispatchAll()
        // One entry is no reveal, and tells nobody anything (FR-076).
        rows().shouldBeEmpty()

        write(bea, BEA_WORDS)
        dispatchAll()

        rows() shouldBe listOf(Row(ada, "REVEAL", "SENT"), Row(bea, "REVEAL", "SENT")).sortedBy { it.userId }
        val reveals = sender.sent.filter { it.category == PushCategory.REVEAL }
        reveals.map { it.token.value }.toSet() shouldBe setOf(ADA_TOKEN, BEA_TOKEN)
        reveals.size shouldBe 2
        reveals.forEach { it.bondId shouldBe UUID.fromString(bond) }
        // No content: the message is the token, the category, the bond and its deadline (#96), and
        // nothing else can carry words.
        PushMessage::class.memberProperties.map { it.name }.toSet() shouldBe setOf("token", "category", "bondId", "staleAfter")
        reveals.forEach { it.staleAfter shouldBe Instant.parse("2026-09-02T23:00:00Z") }
        val everythingSent = sender.sent.joinToString { "${it.category}|${it.bondId}" }
        val ledger = jdbc.queryForList("SELECT n::text FROM notifications n", String::class.java).joinToString()
        val events = jdbc.queryForList("SELECT payload::text FROM outbox_events", String::class.java).joinToString()
        listOf(everythingSent, ledger, events).forEach { written ->
            written shouldNotContain ADA_WORDS
            written shouldNotContain BEA_WORDS
            written shouldNotContain "Ada"
            written shouldNotContain "Bea"
            written shouldNotContain "Us" // the bond's name
        }
    }

    @Test
    fun `a reveal is worth telling until the end of the bond-day after it`() {
        bothWrite()

        jdbc
            .queryForList("SELECT stale_after FROM notifications WHERE category = 'REVEAL'", Timestamp::class.java)
            .map { it!!.toInstant() }
            .toSet() shouldBe setOf(Instant.parse("2026-09-02T23:00:00Z"))
        jdbc.queryForList("SELECT dedupe_key FROM notifications ORDER BY dedupe_key", String::class.java).toSet() shouldBe
            setOf("reveal:${dayId()}:$ada", "reveal:${dayId()}:$bea")
    }

    @Test
    fun `the same DayRevealed delivered twice asks for one push each`() {
        bothWrite()
        val revealed = received("DayRevealed")

        handle(revealed)
        handle(revealed)
        dispatchAll()

        rows() shouldBe listOf(Row(ada, "REVEAL", "SENT"), Row(bea, "REVEAL", "SENT")).sortedBy { it.userId }
        sender.sent.count { it.category == PushCategory.REVEAL } shouldBe 2
    }

    @Test
    fun `one member's mute holds back their copy only, and the decision is still made for both`() {
        notifications(bea, """{"categories":{"reveal":false}}""")

        bothWrite()

        rows() shouldBe listOf(Row(ada, "REVEAL", "SENT"), Row(bea, "REVEAL", "SUPPRESSED")).sortedBy { it.userId }
        sender.sent.map { it.token.value } shouldBe listOf(ADA_TOKEN)
    }

    @Test
    fun `a day that closed with one entry opens it to the partner who did not write, and tells only them`() {
        clock.set(Instant.parse("2026-09-01T18:00:00Z"))
        write(ada, ADA_WORDS)
        dispatchAll()

        closeTheDay()
        dispatchAll()

        rows() shouldBe listOf(Row(bea, "SOLO_UNLOCK", "SENT"))
        sender.sent.map { it.token.value to it.category } shouldBe listOf(BEA_TOKEN to PushCategory.SOLO_UNLOCK)
        jdbc.queryForObject("SELECT dedupe_key FROM notifications", String::class.java) shouldBe "solo:${dayId()}:$bea"
        jdbc.queryForObject("SELECT stale_after FROM notifications", Timestamp::class.java)!!.toInstant() shouldBe
            Instant.parse("2026-09-02T23:00:00Z")
    }

    @Test
    fun `a day both wrote on closes with no solo push, having been told by its reveal`() {
        bothWrite()
        closeTheDay()
        dispatchAll()

        rows() shouldBe listOf(Row(ada, "REVEAL", "SENT"), Row(bea, "REVEAL", "SENT")).sortedBy { it.userId }
    }

    @Test
    fun `a day nobody wrote on closes and tells nobody`() {
        closeTheDay()
        dispatchAll()

        rows().shouldBeEmpty()
    }

    @Test
    fun `a SOLO close whose lone entry was not opened tells nobody`() {
        // The close leaves a lone entry its author's when the bond separated before the day
        // ended (ADR-0033 decision 9): no stamp, nothing opened. Made here by hand on a bond that
        // still takes writes, so that the stamp alone is what is asked.
        clock.set(Instant.parse("2026-09-01T18:00:00Z"))
        write(ada, ADA_WORDS)
        dispatchAll()
        closeTheDay()
        jdbc.update("UPDATE entries SET revealed_at = NULL, status = 'SUBMITTED'")
        jdbc.execute("TRUNCATE TABLE notifications")

        handle(received("DayClosed"))

        rows().shouldBeEmpty()
    }

    @Test
    fun `only a SOLO close is a solo unlock, whatever its entries say`() {
        // A day both wrote on, one of whose stamps is gone: one opened entry, as on a solo day,
        // but the day closed REVEALED and was told by its reveal.
        bothWrite()
        closeTheDay()
        jdbc.update("UPDATE entries SET revealed_at = NULL, status = 'SUBMITTED' WHERE author_member_id = ?", memberIdOf(bea))
        jdbc.execute("TRUNCATE TABLE notifications")

        handle(received("DayClosed"))

        rows().shouldBeEmpty()
    }

    @Test
    fun `a milestone is told to both members, once a day, for a day`() {
        val day = UUID.randomUUID()
        val at = Instant.parse("2026-09-01T23:00:01Z")

        handle(milestone(day, at))
        handle(milestone(day, at))

        jdbc.queryForList("SELECT dedupe_key FROM notifications", String::class.java).toSet() shouldBe
            setOf("milestone:$day:$ada", "milestone:$day:$bea")
        jdbc
            .queryForList("SELECT stale_after FROM notifications", Timestamp::class.java)
            .map { it!!.toInstant() }
            .toSet() shouldBe setOf(Instant.parse("2026-09-02T23:00:01Z"))
    }

    @Test
    fun `an ended bond's MilestoneReached tells nobody`() {
        // ADR-0041 Owed and ADR-0040:141: the evaluation writes the event for an ended bond's
        // last days too, and the consumer must ask the bond before telling anyone.
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        handle(milestone(UUID.randomUUID(), Instant.parse("2026-09-01T23:00:01Z")))

        rows().shouldBeEmpty()
    }

    @Test
    fun `an ended bond's reveal tells nobody`() {
        bothWriteWithoutDispatch()
        mockMvc
            .post("/api/v1/bonds/$bond/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        dispatchAll()

        rows().shouldBeEmpty()
        sender.sent.shouldBeEmpty()
    }

    @Test
    fun `the day's consumer subscribes to the reveal, the close and the milestone, and no consumer to a streak`() {
        consumer.eventTypes shouldBe setOf("DayRevealed", "DayClosed", "MilestoneReached")
        consumer.id shouldBe "gratitude.notify"
        consumer.startFrom shouldBe com.moyi.common.events.StartFrom.NOW
        consumers.flatMap { it.eventTypes }.filter { it.startsWith("Streak") || it.contains("React") }.shouldBeEmpty()
    }

    private data class Row(
        val userId: UUID,
        val category: String,
        val status: String,
    )

    private fun rows(): List<Row> =
        jdbc
            .query("SELECT user_id, category, status FROM notifications") { row, _ ->
                Row(row.getObject("user_id", UUID::class.java), row.getString("category"), row.getString("status"))
            }.sortedWith(compareBy({ it.userId }, { it.category })) // Kotlin's order of UUIDs, as the expectations sort them

    private fun bothWrite() {
        bothWriteWithoutDispatch()
        dispatchAll()
    }

    private fun bothWriteWithoutDispatch() {
        clock.set(Instant.parse("2026-09-01T18:00:00Z"))
        write(ada, ADA_WORDS)
        write(bea, BEA_WORDS)
    }

    private fun closeTheDay() {
        val after = Instant.parse("2026-09-01T23:05:00Z")
        clock.set(after)
        closer.closeElapsedDays(after, CLOSE_BUDGET)
    }

    private fun memberIdOf(userId: UUID): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_members WHERE bond_id = ? AND user_id = ?",
            UUID::class.java,
            UUID.fromString(bond),
            userId,
        )!!

    private fun dayId(): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_days WHERE bond_id = ? AND date = '2026-09-01'",
            UUID::class.java,
            UUID.fromString(bond),
        )!!

    private fun milestone(
        day: UUID,
        at: Instant,
    ) = ReceivedEvent(
        id = UUID.randomUUID(),
        aggregateType = "BondDay",
        aggregateId = day,
        eventType = "MilestoneReached",
        references = mapOf("bondId" to UUID.fromString(bond), "bondDayId" to day),
        occurredAt = at,
    )

    /** The event as the dispatcher hands it over, read back from the outbox. */
    private fun received(type: String): ReceivedEvent =
        jdbc.queryForObject(
            "SELECT id, aggregate_type, aggregate_id, payload->>'bondId' AS bond_id, occurred_at FROM outbox_events WHERE event_type = ?",
            { row, _ ->
                ReceivedEvent(
                    id = row.getObject("id", UUID::class.java),
                    aggregateType = row.getString("aggregate_type"),
                    aggregateId = row.getObject("aggregate_id", UUID::class.java),
                    eventType = type,
                    references = mapOf("bondId" to UUID.fromString(row.getString("bond_id"))),
                    occurredAt = row.getTimestamp("occurred_at").toInstant(),
                )
            },
            type,
        )!!

    private fun handle(event: ReceivedEvent) {
        transactions.executeWithoutResult { consumer.handle(event) }
    }

    /** Runs the outbox until it has nothing due: the event, then the pushes it asked for. */
    private fun dispatchAll() {
        repeat(DISPATCH_ROUNDS) { dispatcher.dispatchDue(clock.instant().plusSeconds(1), DISPATCH) }
    }

    private fun phone(
        userId: UUID,
        token: String,
    ) {
        transactions.executeWithoutResult {
            pushTokens.register(PushTokenRegistration(UUID.randomUUID(), userId, PushPlatform.ANDROID, PushToken(token)))
        }
    }

    private fun write(
        author: UUID,
        words: String,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(author))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
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
        const val CLOSE_BUDGET = 1_000
        const val ADA_TOKEN = "fake-push-token-ada-0001"
        const val BEA_TOKEN = "fake-push-token-bea-0002"
        const val ADA_WORDS = "the plum tree by the gate"
        const val BEA_WORDS = "her laugh on the stairs"
    }
}
