package com.moyi.notification.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.CancelReason
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.Nudge
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.domain.NotificationStatus
import com.moyi.notification.infra.NotificationTestApplication
import com.moyi.notification.infra.database.NotificationStore
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A newer nudge supersedes an older one still owed, and takes over its budget claim (spec C8, C16,
 * ADR-0068 decision 7), on the real ledger. Superseding and cancelling are one statement that
 * returns what it cancelled, so a push already sent is neither cancelled nor inherited from.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
internal class SupersedeTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val store: NotificationStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
) : PostgresIntegrationTest() {
    private val member = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val date = LocalDate.parse("2026-11-16")
    private val asked = Instant.parse("2026-11-16T08:00:00Z")
    private val first = "reminder:$member:$date"
    private val second = "$first:2"
    private val third = "$first:3"

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        clock.set(asked)
    }

    @Test
    fun `a newer nudge cancels an older one still held, and only one is left owed`() {
        ask(first, Nudge(1, 0, emptySet()))
        hold(first)

        ask(second, Nudge(2, 1, setOf(first))) shouldBe true

        status(first) shouldBe "CANCELLED"
        status(second) shouldBe "REQUESTED"
        owed() shouldBe 1
    }

    @Test
    fun `a nudge that supersedes a hard one is hard`() {
        ask(first, Nudge(1, 0, emptySet()))
        hold(first)

        ask(second, Nudge(2, 2, setOf(first)))

        leaveFree(second) shouldBe 0
    }

    @Test
    fun `the claim carries down a chain`() {
        ask(first, Nudge(1, 0, emptySet()))
        hold(first)
        ask(second, Nudge(2, 2, setOf(first)))
        leaveFree(second) shouldBe 0
        hold(second)

        ask(third, Nudge(3, 2, setOf(first, second)))

        leaveFree(third) shouldBe 0
        status(first) shouldBe "CANCELLED"
        status(second) shouldBe "CANCELLED"
        status(third) shouldBe "REQUESTED"
    }

    @Test
    fun `a sent nudge is not superseded and gives nothing`() {
        ask(first, Nudge(1, 0, emptySet()))
        send(first)

        ask(second, Nudge(2, 2, setOf(first)))

        status(first) shouldBe "SENT"
        leaveFree(second) shouldBe 2
    }

    @Test
    fun `asked again, a nudge cancels nothing twice`() {
        // Review Focus 5: the look-back asks for the same nudge up to six times in thirty minutes.
        ask(first, Nudge(1, 0, emptySet()))
        hold(first)

        ask(second, Nudge(2, 2, setOf(first))) shouldBe true
        ask(second, Nudge(2, 2, setOf(first))) shouldBe false

        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE dedupe_key = ?", Int::class.java, second) shouldBe 1
        status(first) shouldBe "CANCELLED"
        leaveFree(second) shouldBe 0
        // Asked once, published once: the re-ask wrote nothing to the outbox either.
        jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java) shouldBe 2
    }

    @Test
    fun `a re-ask supersedes nothing, even a push asked for after it`() {
        // Lead ruling 2026-11-16: nudge 2 recorded first, nudge 1 only later (and held); the
        // look-back asking for nudge 2 again must not call off the one push still owed.
        ask(second, Nudge(2, 2, setOf(first))) shouldBe true
        ask(first, Nudge(1, 0, emptySet())) shouldBe true
        hold(first)

        ask(second, Nudge(2, 2, setOf(first))) shouldBe false

        status(first) shouldBe "HELD"
        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE dedupe_key = ?", Int::class.java, second) shouldBe 1
        leaveFree(second) shouldBe 2
    }

    @Test
    fun `cancel takes a series in one call`() {
        ask(first, Nudge(1, 0, emptySet()))
        ask(second, Nudge(2, 1, emptySet()))
        hold(second)
        ask(third, Nudge(3, 1, emptySet()))
        val sent = "$first:sent"
        ask(sent, Nudge(1, 0, emptySet()))
        send(sent)

        transactions.execute { notifications.cancel(listOf(first, second, third, sent), CancelReason.WRITTEN) } shouldBe 3
        transactions.execute { notifications.cancel(listOf(first, second, third, sent), CancelReason.WRITTEN) } shouldBe 0

        listOf(first, second, third).map(::status) shouldBe List(3) { "CANCELLED" }
        status(sent) shouldBe "SENT"
    }

    @Test
    fun `a supersede waits for a send in progress and leaves it sent`() {
        ask(first, Nudge(1, 0, emptySet()))
        val firstId = idOf(first)

        val locked = CountDownLatch(1)
        val letSenderFinish = CountDownLatch(1)
        val senderPid = CompletableFuture<Int>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            // The consumer, sending nudge 1: its row locked, the provider not yet answered.
            val sender =
                pool.submit<Boolean> {
                    transactions.execute {
                        store.lock(firstId)
                        senderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        locked.countDown()
                        letSenderFinish.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        store.settle(firstId, NotificationStatus.SENT, clock.instant())
                    }
                }
            locked.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val pid = senderPid.get()
            val superseding = pool.submit<Boolean> { ask(second, Nudge(2, 2, setOf(first))) }

            // The supersede's UPDATE waits on the sender's row lock: seen in Postgres, not assumed.
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { superseding.isDone || waitsOnRowLockOf(pid) }
            superseding.isDone shouldBe false

            letSenderFinish.countDown()
            sender.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            superseding.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        } finally {
            letSenderFinish.countDown()
            pool.shutdownNow()
        }

        status(first) shouldBe "SENT"
        status(second) shouldBe "REQUESTED"
        leaveFree(second) shouldBe 2
    }

    private fun ask(
        key: String,
        nudge: Nudge,
    ): Boolean =
        transactions.execute {
            notifications.request(
                PushRequest(
                    userId = member,
                    bondId = bond,
                    category = PushCategory.REMINDER,
                    dedupeKey = key,
                    staleAfter = asked.plus(Duration.ofDays(1)),
                    recipient = PushRecipient(ZoneId.of("Europe/London"), null, NotificationPreferences.DEFAULTS),
                    bondDate = date,
                    nudge = nudge,
                    sharedChange = null,
                ),
            )
        }!!

    private fun idOf(key: String): UUID = jdbc.queryForObject("SELECT id FROM notifications WHERE dedupe_key = ?", UUID::class.java, key)!!

    /** Held by quiet hours, as the consumer would hold it. */
    private fun hold(key: String) {
        transactions.execute { store.hold(idOf(key), asked.plus(Duration.ofHours(1))) } shouldBe true
    }

    /** Sent, as the consumer would settle it. */
    private fun send(key: String) {
        transactions.execute { store.settle(idOf(key), NotificationStatus.SENT, clock.instant()) } shouldBe true
    }

    private fun status(key: String): String =
        jdbc.queryForObject("SELECT status FROM notifications WHERE dedupe_key = ?", String::class.java, key)!!

    private fun leaveFree(key: String): Int =
        jdbc.queryForObject("SELECT leave_free_across_bonds FROM notifications WHERE dedupe_key = ?", Int::class.java, key)!!

    private fun owed(): Int =
        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE status IN ('REQUESTED', 'HELD')", Int::class.java)!!

    /** A backend waiting, not granted, for the transaction [pid] holds: a row lock it has taken. */
    private fun waitsOnRowLockOf(pid: Int): Boolean =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM pg_locks waiting
              JOIN pg_locks holding ON holding.locktype = 'transactionid' AND holding.pid = ? AND holding.granted
                                   AND holding.transactionid = waiting.transactionid
             WHERE waiting.locktype = 'transactionid' AND NOT waiting.granted
            """.trimIndent(),
            Int::class.java,
            pid,
        )!! > 0

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
