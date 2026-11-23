package com.moyi.notification.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.SharedChange
import com.moyi.notification.domain.NotificationStatus
import com.moyi.notification.infra.NotificationTestApplication
import com.moyi.notification.infra.database.NotificationStore
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shared-setting pushes coalesce, on the real ledger (ADR-0069 decision 7, spec §3 B17, §10): a
 * newer one to the same member in the same Bond calls off every older one still owed, takes the
 * earliest `not_before` of them, and says `SEVERAL` if they were about different changes.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
internal class CoalescingLedgerTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val store: NotificationStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
) : PostgresIntegrationTest() {
    private val member = UUID.randomUUID()
    private val partner = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val otherBond = UUID.randomUUID()
    private val first = Instant.parse("2026-11-16T06:00:00Z")

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        meters.clear()
        clock.set(first)
    }

    @Test
    fun `five requests leave one owed, with the first's not_before`() {
        // Review Focus 3: five taps in a minute are one push, about two minutes after the first.
        repeat(FIVE) { i ->
            clock.set(first.plusSeconds(i * FIFTEEN_SECONDS))
            ask("shared:e$i:$member") shouldBe true
        }

        owed() shouldBe listOf("shared:e4:$member")
        notBefore("shared:e4:$member") shouldBe first.plus(Duration.ofMinutes(2))
        (0 until FIVE - 1).map { status("shared:e$it:$member") } shouldBe List(FIVE - 1) { "CANCELLED" }
        superseded() shouldBe (FIVE - 1).toDouble()
        changeOf("shared:e4:$member") shouldBe "SETTING"
    }

    @Test
    fun `the earliest not_before wins, an owed one asked earlier`() {
        clock.set(Instant.parse("2026-11-16T05:57:00Z"))
        ask("shared:early:$member")

        clock.set(Instant.parse("2026-11-16T06:00:00Z"))
        ask("shared:late:$member")

        notBefore("shared:late:$member") shouldBe Instant.parse("2026-11-16T05:59:00Z")
        status("shared:early:$member") shouldBe "CANCELLED"
    }

    @Test
    fun `the earliest not_before wins, a held one included`() {
        clock.set(Instant.parse("2026-11-16T05:00:00Z"))
        ask("shared:held:$member")
        transactions.execute { store.hold(idOf("shared:held:$member"), Instant.parse("2026-11-16T07:00:00Z")) } shouldBe true

        clock.set(Instant.parse("2026-11-16T06:00:00Z"))
        ask("shared:new:$member")

        notBefore("shared:new:$member") shouldBe Instant.parse("2026-11-16T06:02:00Z")
        status("shared:held:$member") shouldBe "CANCELLED"
        owed() shouldBe listOf("shared:new:$member")
    }

    @Test
    fun `different changes make SEVERAL`() {
        ask("shared:a:$member", change = SharedChange.SETTING)
        ask("shared:b:$member", change = SharedChange.PROPOSAL_DECLINED)

        changeOf("shared:b:$member") shouldBe "SEVERAL"
        transactions.execute { store.lock(idOf("shared:b:$member")) }!!.sharedChange shouldBe SharedChange.SEVERAL
    }

    @Test
    fun `several superseded stays several, though the newest change matches the first`() {
        ask("shared:a:$member", change = SharedChange.SETTING)
        ask("shared:b:$member", change = SharedChange.PROPOSAL_DECLINED)
        ask("shared:c:$member", change = SharedChange.SETTING)

        changeOf("shared:c:$member") shouldBe "SEVERAL"
    }

    @Test
    fun `the same change superseded keeps its own`() {
        ask("shared:a:$member", change = SharedChange.PROPOSAL_ACCEPTED)
        ask("shared:b:$member", change = SharedChange.PROPOSAL_ACCEPTED)

        changeOf("shared:b:$member") shouldBe "PROPOSAL_ACCEPTED"
    }

    @Test
    fun `a re-ask cancels nothing and publishes nothing`() {
        // The same event delivered twice, a later change's request recorded between the two.
        ask("shared:a:$member") shouldBe true
        clock.set(first.plusSeconds(FIFTEEN_SECONDS))
        ask("shared:b:$member") shouldBe true
        val events = outboxEvents()
        clock.set(first.plusSeconds(2 * FIFTEEN_SECONDS))

        ask("shared:a:$member") shouldBe false

        status("shared:a:$member") shouldBe "CANCELLED"
        status("shared:b:$member") shouldBe "REQUESTED"
        notBefore("shared:b:$member") shouldBe first.plus(Duration.ofMinutes(2))
        changeOf("shared:b:$member") shouldBe "SETTING"
        outboxEvents() shouldBe events
        superseded() shouldBe 1.0
    }

    @Test
    fun `another member's, another Bond's, another category's and a SENT push are untouched`() {
        ask("shared:partner:$partner", user = partner)
        ask("shared:other-bond:$member", bondId = otherBond)
        ask("proposal:p:$member", category = PushCategory.PROPOSAL_OPENED, change = null)
        ask("shared:sent:$member")
        transactions.execute { store.settle(idOf("shared:sent:$member"), NotificationStatus.SENT, clock.instant()) } shouldBe true

        clock.set(first.plusSeconds(FIFTEEN_SECONDS))
        ask("shared:mine:$member")

        status("shared:partner:$partner") shouldBe "REQUESTED"
        status("shared:other-bond:$member") shouldBe "REQUESTED"
        status("proposal:p:$member") shouldBe "REQUESTED"
        status("shared:sent:$member") shouldBe "SENT"
        // Nothing it cancelled, so nothing it took over: its own two minutes, its own change.
        notBefore("shared:mine:$member") shouldBe first.plusSeconds(FIFTEEN_SECONDS).plus(Duration.ofMinutes(2))
        changeOf("shared:mine:$member") shouldBe "SETTING"
        superseded() shouldBe 0.0
    }

    @Test
    fun `a supersede waits for a send in progress and leaves it sent`() {
        ask("shared:sending:$member", change = SharedChange.PROPOSAL_DECLINED)
        val sendingId = idOf("shared:sending:$member")
        val later = first.plusSeconds(FIFTEEN_SECONDS)

        val locked = CountDownLatch(1)
        val letSenderFinish = CountDownLatch(1)
        val senderPid = CompletableFuture<Int>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            // The consumer, sending the older push: its row locked, the provider not yet answered.
            val sender =
                pool.submit<Boolean> {
                    transactions.execute {
                        store.lock(sendingId)
                        senderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        locked.countDown()
                        letSenderFinish.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        store.settle(sendingId, NotificationStatus.SENT, clock.instant())
                    }
                }
            locked.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val pid = senderPid.get()
            clock.set(later)
            val superseding = pool.submit<Boolean> { ask("shared:newer:$member") }

            // The group UPDATE waits on the sender's row lock: seen in Postgres, not assumed.
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { superseding.isDone || waitsOnRowLockOf(pid) }
            superseding.isDone shouldBe false

            letSenderFinish.countDown()
            sender.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            superseding.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        } finally {
            letSenderFinish.countDown()
            pool.shutdownNow()
        }

        status("shared:sending:$member") shouldBe "SENT"
        status("shared:newer:$member") shouldBe "REQUESTED"
        notBefore("shared:newer:$member") shouldBe later.plus(Duration.ofMinutes(2))
        changeOf("shared:newer:$member") shouldBe "SETTING"
        superseded() shouldBe 0.0
    }

    @Test
    fun `two requests at once leave both owed, the older cancelled once`() {
        // Spec §7: each group UPDATE sees only committed rows, so two changes in flight may both
        // survive. Never zero, and what they both reached for is cancelled and counted once.
        ask("shared:r0:$member")
        val r0NotBefore = notBefore("shared:r0:$member")
        val firstAsk = first.plusSeconds(FIFTEEN_SECONDS)
        val secondAsk = first.plusSeconds(2 * FIFTEEN_SECONDS)

        val asked = CountDownLatch(1)
        val letFirstCommit = CountDownLatch(1)
        val firstPid = CompletableFuture<Int>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            clock.set(firstAsk)
            // e1: recorded, R0 cancelled under its row lock, not yet committed.
            val one =
                pool.submit<Boolean> {
                    transactions.execute {
                        val recorded = request("shared:e1:$member")
                        firstPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        asked.countDown()
                        letFirstCommit.await(WAIT_SECONDS, TimeUnit.SECONDS)
                        recorded
                    }
                }
            asked.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            val pid = firstPid.get()
            clock.set(secondAsk)
            val two = pool.submit<Boolean> { ask("shared:e2:$member") }

            // e2's group UPDATE reaches for R0 and waits on e1's lock: seen in Postgres, not assumed.
            await().atMost(Duration.ofSeconds(WAIT_SECONDS)).until { two.isDone || waitsOnRowLockOf(pid) }
            two.isDone shouldBe false

            letFirstCommit.countDown()
            one.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
            two.get(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        } finally {
            letFirstCommit.countDown()
            pool.shutdownNow()
        }

        status("shared:r0:$member") shouldBe "CANCELLED"
        status("shared:e1:$member") shouldBe "REQUESTED"
        status("shared:e2:$member") shouldBe "REQUESTED"
        superseded() shouldBe 1.0
        notBefore("shared:e1:$member") shouldBe r0NotBefore
        notBefore("shared:e2:$member") shouldBe secondAsk.plus(Duration.ofMinutes(2))
    }

    @Suppress("LongParameterList") // A request's varying fields, each defaulted for the common case.
    private fun ask(
        key: String,
        user: UUID = member,
        bondId: UUID = bond,
        category: PushCategory = PushCategory.SHARED_SETTING_CHANGED,
        change: SharedChange? = SharedChange.SETTING,
    ): Boolean = transactions.execute { request(key, user, bondId, category, change) }!!

    /** A request in the caller's transaction. */
    @Suppress("LongParameterList") // As [ask].
    private fun request(
        key: String,
        user: UUID = member,
        bondId: UUID = bond,
        category: PushCategory = PushCategory.SHARED_SETTING_CHANGED,
        change: SharedChange? = SharedChange.SETTING,
    ): Boolean =
        notifications.request(
            PushRequest(
                userId = user,
                bondId = bondId,
                category = category,
                dedupeKey = key,
                staleAfter = clock.instant().plus(Duration.ofDays(1)),
                recipient = PushRecipient(ZoneId.of("Europe/London"), null, NotificationPreferences.DEFAULTS),
                bondDate = null,
                nudge = null,
                sharedChange = change,
            ),
        )

    private fun idOf(key: String): UUID = jdbc.queryForObject("SELECT id FROM notifications WHERE dedupe_key = ?", UUID::class.java, key)!!

    private fun status(key: String): String =
        jdbc.queryForObject("SELECT status FROM notifications WHERE dedupe_key = ?", String::class.java, key)!!

    private fun notBefore(key: String): Instant =
        jdbc.queryForObject("SELECT not_before FROM notifications WHERE dedupe_key = ?", Instant::class.java, key)!!

    private fun changeOf(key: String): String? =
        jdbc.queryForObject("SELECT shared_change FROM notifications WHERE dedupe_key = ?", String::class.java, key)

    private fun owed(): List<String> =
        jdbc
            .queryForList(
                "SELECT dedupe_key FROM notifications WHERE user_id = ? AND bond_id = ? AND status IN ('REQUESTED', 'HELD')",
                String::class.java,
                member,
                bond,
            ).filterNotNull()

    private fun outboxEvents(): Int = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java)!!

    private fun superseded(): Double =
        meters
            .find(PushRequests.CANCELLED)
            .tags("category", "SHARED_SETTING_CHANGED", "nudge", SendPush.NO_NUDGE, "reason", PushRequests.SUPERSEDED)
            .counter()
            ?.count() ?: 0.0

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
        const val FIVE = 5
        const val FIFTEEN_SECONDS = 15L
        const val WAIT_SECONDS = 10L
    }
}
