package com.moyi.notification.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.HeldPushes
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.PushToken
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import com.moyi.notification.api.QuietHoursWindow
import com.moyi.notification.infra.NotificationTestApplication
import com.moyi.notification.infra.ScriptedPushSender
import com.moyi.notification.infra.database.BUDGET_LOCK_NAMESPACE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * N2's decisions in the `notification.push` consumer, on a real Postgres and
 * the real outbox (spec §4 N2, ADR-0045): a category turned off, quiet hours
 * and their release, and FR-082's budget with its reservation and its lock.
 * The rules themselves are `QuietHoursTest`'s and `PushBudgetTest`'s; this is
 * the ledger counting what they count, and the consumer obeying the answer.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
@Suppress("LongParameterList", "LargeClass") // What Spring hands the test; every decision of one consumer.
internal class PushPreferencesBudgetTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val held: HeldPushes,
    @Autowired private val pushTokens: PushTokens,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val provider: ScriptedPushSender,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val london: ZoneId = ZoneId.of("Europe/London")

    /** 20:00 in London on an ordinary October day. */
    private val evening = Instant.parse("2026-10-14T19:00:00Z")
    private val night = QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(7, 0))

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, push_tokens, outbox_deliveries, outbox_events")
        clock.set(evening)
        provider.reset()
        register(ada, "token-ada")
        register(bob, "token-bob")
    }

    @Test
    fun `a category the member turned off is suppressed and sent to nobody`() {
        ask(push(ada, PushCategory.MILESTONE, preferences = NotificationPreferences(milestone = false)))

        dispatcher.dispatchDue(evening, BUDGET).delivered shouldBe 1

        provider.sent.shouldBeEmpty()
        status(ada) shouldBe "SUPPRESSED"
    }

    @Test
    fun `on-this-day is not sent to a member who never opted in`() {
        ask(push(ada, PushCategory.ON_THIS_DAY))
        dispatcher.dispatchDue(evening, BUDGET)
        status(ada) shouldBe "SUPPRESSED"
    }

    @Test
    fun `one member's mute, quiet hours or spent budget holds back their copy only`() {
        // H-M5 as Q9 reads it: the Bond decided both are told; each member's own settings decide their copy.
        clock.set(at("23:00"))
        spend(bob, 6)
        ask(push(ada, PushCategory.MILESTONE, preferences = NotificationPreferences(milestone = false)))
        ask(push(bob, PushCategory.MILESTONE))
        val carol = UUID.randomUUID().also { register(it, "token-carol") }
        ask(push(carol, PushCategory.MILESTONE, quietHours = night))
        val dan = UUID.randomUUID().also { register(it, "token-dan") }
        ask(push(dan, PushCategory.MILESTONE))

        dispatcher.dispatchDue(clock.instant(), BUDGET).delivered shouldBe 4

        status(ada) shouldBe "SUPPRESSED"
        status(bob) shouldBe "DROPPED_BUDGET"
        status(carol) shouldBe "HELD"
        status(dan) shouldBe "SENT"
        provider.sent.map { it.token.value } shouldContainExactly listOf("token-dan")
    }

    @Test
    fun `a reveal inside quiet hours is held to their end, released then, and sent`() {
        clock.set(at("22:30"))
        ask(push(ada, PushCategory.REVEAL, quietHours = night))

        dispatcher.dispatchDue(clock.instant(), BUDGET).delivered shouldBe 1

        // Held, not dropped (Q7), and the outbox owes nothing: it is not a delivery waiting all night.
        provider.sent.shouldBeEmpty()
        status(ada) shouldBe "HELD"
        row(ada)["not_before"] shouldBe Timestamp.from(Instant.parse("2026-10-15T06:00:00Z")) // 07:00 BST
        undelivered() shouldBe 0

        // Before the hours end the release has nothing to hand back.
        held.releaseDue(Instant.parse("2026-10-15T05:59:59Z"), LIMIT) shouldBe 0
        clock.set(Instant.parse("2026-10-15T06:00:00Z"))
        held.releaseDue(clock.instant(), LIMIT) shouldBe 1
        status(ada) shouldBe "REQUESTED"

        dispatcher.dispatchDue(clock.instant(), BUDGET).delivered shouldBe 1
        status(ada) shouldBe "SENT"
        row(ada)["reserved"] shouldBe true
        provider.sent.size shouldBe 1
    }

    @Test
    fun `a held push that has gone stale by the end of quiet hours is expired, not sent late`() {
        clock.set(at("22:30"))
        // A reminder for today, stale at midnight; the member's night lasts until 07:00.
        ask(push(ada, PushCategory.REMINDER, quietHours = night, staleAfter = Instant.parse("2026-10-14T23:00:00Z")))
        dispatcher.dispatchDue(clock.instant(), BUDGET)
        status(ada) shouldBe "HELD"

        clock.set(Instant.parse("2026-10-15T06:00:00Z"))
        held.releaseDue(clock.instant(), LIMIT) shouldBe 1
        dispatcher.dispatchDue(clock.instant(), BUDGET).delivered shouldBe 1

        status(ada) shouldBe "EXPIRED"
        provider.sent.shouldBeEmpty()
    }

    @Test
    fun `a push retried by the outbox into the member's quiet hours is held then, not sent`() {
        // Asked for at 21:59, refused by the provider, and due again after 22:00.
        clock.set(at("21:59"))
        ask(push(ada, PushCategory.MILESTONE, quietHours = night))
        provider.answer(
            "token-ada",
            com.moyi.notification.api.PushDelivery
                .Unavailable("FCM failed (HTTP 503)"),
        )
        dispatcher.dispatchDue(clock.instant(), BUDGET).failed shouldBe 1

        provider.answer(
            "token-ada",
            com.moyi.notification.api.PushDelivery
                .Accepted("projects/test/messages/1"),
        )
        clock.set(at("22:01"))
        dispatcher.dispatchDue(clock.instant(), BUDGET).delivered shouldBe 1

        status(ada) shouldBe "HELD"
        provider.sent.size shouldBe 1 // only the refused attempt
    }

    @Test
    fun `a release run twice, or on two instances, hands each push back once`() {
        clock.set(at("23:00"))
        ask(push(ada, PushCategory.REVEAL, quietHours = night))
        dispatcher.dispatchDue(clock.instant(), BUDGET)
        val morning = Instant.parse("2026-10-15T06:00:00Z")

        held.releaseDue(morning, LIMIT) shouldBe 1
        held.releaseDue(morning, LIMIT) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'PushRequested'", Int::class.java) shouldBe 2
    }

    @Test
    fun `FR-082's example in the ledger - the evening's other pushes cannot take the 22-00 reveal's slot`() {
        listOf("20:00" to PushCategory.REMINDER, "20:15" to PushCategory.MILESTONE, "20:30" to PushCategory.PARTNER_JOINED)
            .forEachIndexed { index, (time, category) ->
                clock.set(at(time))
                ask(push(ada, category, key = "other-$index"))
                dispatcher.dispatchDue(clock.instant(), BUDGET)
            }
        clock.set(at("22:00"))
        ask(push(ada, PushCategory.REVEAL, key = "reveal"))
        dispatcher.dispatchDue(clock.instant(), BUDGET)

        statuses(ada) shouldBe
            mapOf(
                "other-0" to ("SENT" to false),
                "other-1" to ("SENT" to false),
                "other-2" to ("DROPPED_BUDGET" to false),
                "reveal" to ("SENT" to true),
            )
    }

    @Test
    fun `three Bonds of three pushes each stop at six for the member`() {
        val bonds = List(3) { UUID.randomUUID() }
        bonds.forEach { other ->
            listOf(PushCategory.REVEAL, PushCategory.REMINDER, PushCategory.MILESTONE).forEach { category ->
                clock.set(clock.instant().plusSeconds(60))
                ask(push(ada, category, bondId = other, key = "$other-$category"))
                dispatcher.dispatchDue(clock.instant(), BUDGET)
            }
        }
        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status = 'SENT'", Int::class.java) shouldBe 6
        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status = 'DROPPED_BUDGET'", Int::class.java) shouldBe 3
        provider.sent.size shouldBe 6
    }

    @Test
    fun `the per-Bond day is the member's day in their reminder zone, so midnight there gives three more`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        // 23:00 in Kathmandu (17:15Z): three sent, a fourth dropped.
        clock.set(Instant.parse("2026-10-14T17:15:00Z"))
        repeat(4) { ask(push(ada, PushCategory.REVEAL, zone = kathmandu, key = "before-$it")) }
        dispatcher.dispatchDue(clock.instant(), BUDGET)
        // 00:00 the next day there (18:15Z), an hour later: a new day in that Bond, though the rolling cap still counts.
        clock.set(Instant.parse("2026-10-14T18:15:00Z"))
        ask(push(ada, PushCategory.REVEAL, zone = kathmandu, key = "after"))
        dispatcher.dispatchDue(clock.instant(), BUDGET)

        statuses(ada).filterValues { it.first == "SENT" }.keys shouldBe setOf("before-0", "before-1", "before-2", "after")
        statuses(ada)["after"] shouldBe ("SENT" to true)
    }

    @Test
    fun `a push that is retried gives its slot back with the rollback`() {
        spend(ada, 1, bondId = bond, reserved = false)
        provider.answer(
            "token-ada",
            com.moyi.notification.api.PushDelivery
                .Unavailable("FCM failed (HTTP 503)"),
        )
        ask(push(ada, PushCategory.MILESTONE, key = "retried"))
        dispatcher.dispatchDue(evening, BUDGET).failed shouldBe 1
        // The slot it would have taken is still free for another push meanwhile.
        provider.answer(
            "token-ada",
            com.moyi.notification.api.PushDelivery
                .Accepted("projects/test/messages/2"),
        )
        ask(push(ada, PushCategory.MILESTONE, key = "other"))
        dispatcher.dispatchDue(evening.plusSeconds(1), BUDGET).delivered shouldBe 1

        statuses(ada)["other"] shouldBe ("SENT" to false)
    }

    @Test
    fun `two pushes for one member decided at once are decided one after the other, under the budget lock`() {
        // One remainder slot left in this Bond today: of two pushes sent at once, exactly one may take it.
        spend(ada, 1, bondId = bond, reserved = false)
        ask(push(ada, PushCategory.MILESTONE, key = "first"))
        ask(push(ada, PushCategory.REMINDER, key = "second"))

        val firstIsSending = CountDownLatch(1)
        val letFirstFinish = CountDownLatch(1)
        val firstPid = CompletableFuture<Int>()
        provider.duringSend = {
            if (firstPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)) {
                firstIsSending.countDown()
                letFirstFinish.await(10, TimeUnit.SECONDS)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            // The first delivery reaches the provider holding the budget lock, and waits there.
            val first = pool.submit<Int> { dispatcher.dispatchDue(evening, 1).delivered }
            firstIsSending.await(10, TimeUnit.SECONDS) shouldBe true
            val pid = firstPid.get()
            val second = pool.submit<Int> { dispatcher.dispatchDue(evening, 1).delivered }

            // The second waits on the first's budget lock: seen in Postgres, not assumed.
            await().atMost(Duration.ofSeconds(10)).until { second.isDone || waitsOnBudgetLockOf(pid) }
            second.isDone shouldBe false

            letFirstFinish.countDown()
            first.get(10, TimeUnit.SECONDS) shouldBe 1
            second.get(10, TimeUnit.SECONDS) shouldBe 1
        } finally {
            letFirstFinish.countDown()
            pool.shutdownNow()
        }
        statuses(ada).values.map { it.first }.sorted() shouldBe listOf("DROPPED_BUDGET", "SENT")
        provider.sent.size shouldBe 1
    }

    private fun push(
        userId: UUID,
        category: PushCategory,
        bondId: UUID = bond,
        key: String = "$category:$userId",
        zone: ZoneId = london,
        quietHours: QuietHoursWindow? = null,
        preferences: NotificationPreferences = NotificationPreferences.DEFAULTS,
        staleAfter: Instant = evening.plus(Duration.ofDays(2)),
    ) = PushRequest(userId, bondId, category, key, staleAfter, PushRecipient(zone, quietHours, preferences))

    private fun ask(request: PushRequest) {
        transactions.execute { notifications.request(request) } shouldBe true
    }

    /** [count] pushes already sent to [userId] this evening, as the ledger would hold them. */
    private fun spend(
        userId: UUID,
        count: Int,
        bondId: UUID = UUID.randomUUID(),
        reserved: Boolean = false,
    ) {
        repeat(count) {
            jdbc.update(
                """
                INSERT INTO notifications (id, user_id, bond_id, category, dedupe_key, not_before, stale_after, status,
                                           created_at, sent_at, zone, reserved)
                VALUES (?, ?, ?, 'MILESTONE', ?, ?, ?, 'SENT', ?, ?, 'Europe/London', ?)
                """.trimIndent(),
                UUID.randomUUID(),
                userId,
                bondId,
                "spent:${UUID.randomUUID()}",
                Timestamp.from(evening.minusSeconds(60)),
                Timestamp.from(evening.plus(Duration.ofDays(1))),
                Timestamp.from(evening.minusSeconds(60)),
                Timestamp.from(evening.minusSeconds(60)),
                reserved,
            )
        }
    }

    /** [time] in London on [evening]'s date. */
    private fun at(time: String): Instant =
        LocalTime
            .parse(time)
            .atDate(evening.atZone(london).toLocalDate())
            .atZone(london)
            .toInstant()

    private fun register(
        userId: UUID,
        token: String,
    ) {
        transactions.execute {
            pushTokens.register(PushTokenRegistration(UUID.randomUUID(), userId, PushPlatform.ANDROID, PushToken(token)))
        }
    }

    private fun row(userId: UUID): Map<String, Any?> =
        jdbc.queryForMap("SELECT * FROM notifications WHERE user_id = ? AND dedupe_key NOT LIKE 'spent:%'", userId)

    private fun status(userId: UUID): String = row(userId)["status"] as String

    private fun statuses(userId: UUID): Map<String, Pair<String, Boolean>> =
        jdbc
            .queryForList(
                "SELECT dedupe_key, status, reserved FROM notifications WHERE user_id = ? AND dedupe_key NOT LIKE 'spent:%'",
                userId,
            ).associate { it["dedupe_key"] as String to (it["status"] as String to it["reserved"] as Boolean) }

    private fun undelivered(): Int =
        jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE processed_at IS NULL", Int::class.java)!!

    /** A backend waiting for the advisory lock of the budget's namespace that [pid] holds. */
    private fun waitsOnBudgetLockOf(pid: Int): Boolean =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM pg_locks waiting
              JOIN pg_locks holding ON holding.locktype = 'advisory' AND holding.pid = ? AND holding.granted
                                   AND holding.classid = waiting.classid AND holding.objid = waiting.objid
             WHERE waiting.locktype = 'advisory' AND NOT waiting.granted AND waiting.classid = ?
            """.trimIndent(),
            Int::class.java,
            pid,
            BUDGET_LOCK_NAMESPACE,
        )!! > 0

    private companion object {
        const val BUDGET = 20
        const val LIMIT = 100
    }
}
