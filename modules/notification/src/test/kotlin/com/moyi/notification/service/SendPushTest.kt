package com.moyi.notification.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.PushToken
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import com.moyi.notification.infra.NotificationTestApplication
import com.moyi.notification.infra.RequestingConsumer
import com.moyi.notification.infra.ScriptedPushSender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * N1's ledger and consumer end to end on a real Postgres and the real outbox
 * (spec §4 N1, §2.2): a request is recorded once, and the `notification.push`
 * consumer sends it, expires it, retries it, prunes what the provider calls
 * invalid, and records what became of it.
 *
 * The provider is [ScriptedPushSender]; what FCM's answers mean on the wire is
 * `FcmPushSenderTest`'s. The database is shared with the other classes of this
 * module, so each test clears the ledger, the tokens and the outbox's events,
 * and keeps the consumers' registrations, which the context made at start-up.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
@Suppress("LongParameterList", "LargeClass") // What Spring hands the test; every promise of one consumer.
internal class SendPushTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val pushTokens: PushTokens,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val provider: ScriptedPushSender,
    @Autowired private val requester: RequestingConsumer,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val start = Instant.parse("2026-10-27T18:00:00Z")

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, push_tokens, outbox_deliveries, outbox_events")
        clock.set(start)
        provider.reset()
        requester.isolationLevels.clear()
    }

    @Test
    fun `a request is recorded once and owed once, however many times it is made`() {
        val request = reveal(ada)

        transactions.execute { notifications.request(request) } shouldBe true
        transactions.execute { notifications.request(request) } shouldBe false
        // A different fact for the same member is a different push.
        transactions.execute { notifications.request(request.copy(dedupeKey = "reveal:other-day:$ada")) } shouldBe true

        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE dedupe_key = ?", Int::class.java, request.dedupeKey) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'PushRequested'", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM outbox_deliveries WHERE consumer_id = 'notification.push'", Int::class.java) shouldBe 2
        val row = jdbc.queryForMap("SELECT * FROM notifications WHERE dedupe_key = ?", request.dedupeKey)
        row["status"] shouldBe "REQUESTED"
        row["category"] shouldBe "REVEAL"
        row["reserved"] shouldBe false
        row["user_id"] shouldBe ada
        row["bond_id"] shouldBe bond
    }

    @Test
    fun `a request refuses to run outside the caller's transaction`() {
        // The row and the event are one write: alone, the row could commit with nothing owed for it.
        shouldThrow<IllegalTransactionStateException> { notifications.request(reveal(ada)) }
        jdbc.queryForObject("SELECT count(*) FROM notifications", Int::class.java) shouldBe 0
    }

    @Test
    fun `two transactions asking for the same push at once make one row, the second after the first commits`() {
        val request = reveal(ada)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holderPid = CompletableFuture<Int>()
            val first =
                pool.submit<Boolean> {
                    transactions.execute {
                        val made = notifications.request(request)
                        holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                        release.await(10, TimeUnit.SECONDS)
                        made
                    }
                }
            val pid = holderPid.get(10, TimeUnit.SECONDS)
            val second = pool.submit<Boolean> { transactions.execute { notifications.request(request) } }

            // The second insert waits on the first's uncommitted row: seen in Postgres, not assumed.
            await().atMost(Duration.ofSeconds(10)).until { second.isDone || blockedBy(pid) }
            second.isDone shouldBe false

            release.countDown()
            first.get(10, TimeUnit.SECONDS) shouldBe true
            second.get(10, TimeUnit.SECONDS) shouldBe false
        } finally {
            pool.shutdownNow()
        }
        jdbc.queryForObject("SELECT count(*) FROM notifications", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'PushRequested'", Int::class.java) shouldBe 1
    }

    @Test
    fun `a requested push reaches every phone of the member, carries keys and ids only, and is recorded sent`() {
        val phone = register(ada, "token-ada-phone")
        val tablet = register(ada, "token-ada-tablet")
        register(UUID.randomUUID(), "token-somebody-else")
        transactions.execute { notifications.request(reveal(ada)) }

        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1

        provider.sent.map { it.token.value } shouldContainExactlyInAnyOrder listOf("token-ada-phone", "token-ada-tablet")
        provider.sent.toSet() shouldBe
            setOf(
                PushMessage(PushToken("token-ada-phone"), PushCategory.REVEAL, bond, start.plus(Duration.ofDays(1))),
                PushMessage(PushToken("token-ada-tablet"), PushCategory.REVEAL, bond, start.plus(Duration.ofDays(1))),
            )
        val row = notification(ada)
        row["status"] shouldBe "SENT"
        row["sent_at"] shouldBe java.sql.Timestamp.from(start)
        (row["provider_message_id"] as String).startsWith("projects/test/messages/") shouldBe true
        row["error_class"] shouldBe null
        tokensOf(ada) shouldContainExactlyInAnyOrder listOf(phone, tablet)
    }

    @Test
    fun `the same PushRequested delivered twice sends once`() {
        register(ada, "token-ada")
        transactions.execute { notifications.request(reveal(ada)) }
        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1
        provider.sent.size shouldBe 1

        // At least once (ADR-0035 decision 6): the dispatcher hands the event over again.
        jdbc.update("UPDATE outbox_deliveries SET processed_at = NULL WHERE consumer_id = 'notification.push'") shouldBe 1
        dispatcher.dispatchDue(start.plusSeconds(1), BUDGET).delivered shouldBe 1

        provider.sent.size shouldBe 1
        notification(ada)["status"] shouldBe "SENT"
    }

    @Test
    fun `a push still unsent at its stale-after instant is expired, not sent late`() {
        register(ada, "token-ada")
        transactions.execute { notifications.request(reveal(ada, staleAfter = start.plus(Duration.ofHours(1)))) }

        clock.set(start.plus(Duration.ofHours(1)))
        dispatcher.dispatchDue(clock.instant(), BUDGET).delivered shouldBe 1

        provider.sent.shouldBeEmpty()
        val row = notification(ada)
        row["status"] shouldBe "EXPIRED"
        row["sent_at"] shouldBe null
    }

    @Test
    fun `a request already stale when made is recorded and expired, so the ledger shows it`() {
        register(ada, "token-ada")
        transactions.execute { notifications.request(reveal(ada, staleAfter = start.minusSeconds(1))) } shouldBe true

        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1

        provider.sent.shouldBeEmpty()
        notification(ada)["status"] shouldBe "EXPIRED"
    }

    @Test
    fun `a provider that cannot take it now is retried with backoff, and the retry sends it`() {
        register(ada, "token-ada")
        provider.answer("token-ada", PushDelivery.Unavailable("FCM failed (HTTP 503)"))
        transactions.execute { notifications.request(reveal(ada)) }

        dispatcher.dispatchDue(start, BUDGET).failed shouldBe 1

        notification(ada)["status"] shouldBe "REQUESTED"
        val delivery = jdbc.queryForMap("SELECT * FROM outbox_deliveries WHERE consumer_id = 'notification.push'")
        delivery["last_error"] shouldBe PushProviderUnavailableException::class.java.name
        delivery["processed_at"] shouldBe null
        // FR-086: the outbox's backoff, 2 s after the first failure.
        delivery["next_attempt_at"] shouldBe java.sql.Timestamp.from(start.plusSeconds(2))

        provider.answer("token-ada", PushDelivery.Accepted("projects/test/messages/late"))
        dispatcher.dispatchDue(start.plusSeconds(2), BUDGET).delivered shouldBe 1
        notification(ada)["status"] shouldBe "SENT"
        notification(ada)["provider_message_id"] shouldBe "projects/test/messages/late"
    }

    @Test
    fun `a refusal that would be repeated is final and not retried for ever`() {
        register(ada, "token-ada")
        provider.answer("token-ada", PushDelivery.Rejected("FCM refused the message (HTTP 400, INVALID_ARGUMENT)"))
        transactions.execute { notifications.request(reveal(ada)) }

        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1

        val row = notification(ada)
        row["status"] shouldBe "FAILED_PERMANENT"
        row["error_class"] shouldBe "REJECTED"
        // Acknowledged: nothing is owed and nothing comes back.
        dispatcher.dispatchDue(start.plus(Duration.ofHours(1)), BUDGET).delivered shouldBe 0
        provider.sent.size shouldBe 1
        // The token was not the problem, so it stays.
        tokensOf(ada).size shouldBe 1
    }

    @Test
    fun `a token the provider calls invalid is forgotten, and the push still goes to the phones that took it`() {
        register(ada, "token-ada-uninstalled")
        val kept = register(ada, "token-ada-phone")
        provider.answer("token-ada-uninstalled", PushDelivery.TokenInvalid("FCM: the token is no longer registered (HTTP 404)"))
        transactions.execute { notifications.request(reveal(ada)) }

        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1

        tokensOf(ada) shouldContainExactly listOf(kept)
        notification(ada)["status"] shouldBe "SENT"
    }

    @Test
    fun `with every token invalid the push fails for good and the tokens are gone`() {
        register(ada, "token-ada-uninstalled")
        provider.answer("token-ada-uninstalled", PushDelivery.TokenInvalid("FCM: the token is no longer registered (HTTP 404)"))
        transactions.execute { notifications.request(reveal(ada)) }

        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1

        tokensOf(ada).shouldBeEmpty()
        notification(ada)["status"] shouldBe "FAILED_PERMANENT"
        notification(ada)["error_class"] shouldBe "NO_VALID_TOKEN"
    }

    @Test
    fun `a token registered again while the old one was being sent to is a new token, and is kept`() {
        val device = UUID.randomUUID()
        register(ada, "token-old", device)
        provider.answer("token-old", PushDelivery.TokenInvalid("FCM: the token is no longer registered (HTTP 404)"))
        // The phone registers a fresh token between the read of the tokens and the prune of the old one.
        provider.duringSend = {
            jdbc.update("UPDATE push_tokens SET token = 'token-new' WHERE device_id = ?", device) shouldBe 1
        }
        transactions.execute { notifications.request(reveal(ada)) }

        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1

        jdbc.queryForObject("SELECT token FROM push_tokens WHERE device_id = ?", String::class.java, device) shouldBe "token-new"
    }

    @Test
    fun `a member with no phone is recorded as such, and nothing is sent`() {
        transactions.execute { notifications.request(reveal(ada)) }

        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1

        provider.sent.shouldBeEmpty()
        notification(ada)["status"] shouldBe "FAILED_PERMANENT"
        notification(ada)["error_class"] shouldBe "NO_DEVICE"
    }

    @Test
    fun `a consumer may ask for a push from inside its own delivery, which runs at READ COMMITTED`() {
        // The spec's [inferred] item for N1 (§2.2), checked by running it: N3's consumers will request
        // pushes from inside their deliveries, and the outbox publishes only at READ COMMITTED (ADR-0035).
        register(ada, "token-ada")
        val fact = UUID.randomUUID()
        transactions.execute {
            publisher.publish(
                OutboxEvent("NotificationTest", fact, RequestingConsumer.FACT, mapOf("userId" to ada, "bondId" to bond), start),
            )
        }

        dispatcher.dispatchDue(start, 1).delivered shouldBe 1
        requester.isolationLevels shouldContainExactly listOf("read committed")
        notification(ada)["status"] shouldBe "REQUESTED"

        dispatcher.dispatchDue(start, BUDGET).delivered shouldBe 1
        notification(ada)["status"] shouldBe "SENT"
        provider.sent.size shouldBe 1
    }

    private fun reveal(
        userId: UUID,
        staleAfter: Instant = start.plus(Duration.ofDays(1)),
    ) = PushRequest(userId, bond, PushCategory.REVEAL, "reveal:day-1:$userId", staleAfter)

    private fun register(
        userId: UUID,
        token: String,
        deviceId: UUID = UUID.randomUUID(),
    ): UUID {
        transactions.execute { pushTokens.register(PushTokenRegistration(deviceId, userId, PushPlatform.ANDROID, PushToken(token))) }
        return deviceId
    }

    private fun tokensOf(userId: UUID): List<UUID> =
        jdbc
            .queryForList(
                "SELECT device_id FROM push_tokens WHERE user_id = ? ORDER BY device_id",
                UUID::class.java,
                userId,
            ).filterNotNull()

    private fun notification(userId: UUID): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM notifications WHERE user_id = ?", userId)

    private fun blockedBy(pid: Int): Boolean =
        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Int::class.java, pid)!! > 0

    private companion object {
        const val BUDGET = 10
    }
}
