package com.moyi.notification.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.Nudge
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.SharedChange
import com.moyi.notification.domain.NotificationStatus
import com.moyi.notification.infra.NotificationTestApplication
import com.moyi.notification.infra.database.NotificationStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * A Bond that ends calls off every push of it still owed, every member's, every category's
 * (ADR-0069 decision 5, spec §3 B12), on the real ledger.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
internal class CancelBondTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val store: NotificationStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: MeterRegistry,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val ben = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val otherBond = UUID.randomUUID()
    private val asked = Instant.parse("2026-11-16T22:00:00Z")

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        meters.clear()
        clock.set(asked)
    }

    @Test
    fun `every owed push of the Bond, both members, every category, is cancelled and counted`() {
        ask("reminder:ada", ada, bond, PushCategory.REMINDER, nudge = Nudge(2, 1, emptySet()))
        ask("proposal:ben", ben, bond, PushCategory.PROPOSAL_OPENED)
        ask("shared:ben", ben, bond, PushCategory.SHARED_SETTING_CHANGED, SharedChange.SETTING)
        transactions.execute { store.hold(idOf("shared:ben"), asked.plus(Duration.ofHours(9))) } shouldBe true
        ask("sent:ada", ada, bond, PushCategory.PROPOSAL_OPENED)
        transactions.execute { store.settle(idOf("sent:ada"), NotificationStatus.SENT, clock.instant()) } shouldBe true
        ask("other:ada", ada, otherBond, PushCategory.SHARED_SETTING_CHANGED, SharedChange.SETTING)

        transactions.execute { notifications.cancelBond(bond) } shouldBe 3

        listOf("reminder:ada", "proposal:ben", "shared:ben").map(::status) shouldBe List(3) { "CANCELLED" }
        status("sent:ada") shouldBe "SENT"
        status("other:ada") shouldBe "REQUESTED"
        cancelled("REMINDER", "2") shouldBe 1.0
        cancelled("PROPOSAL_OPENED", SendPush.NO_NUDGE) shouldBe 1.0
        cancelled("SHARED_SETTING_CHANGED", SendPush.NO_NUDGE) shouldBe 1.0
    }

    @Test
    fun `called twice, it cancels and counts once`() {
        ask("proposal:ben", ben, bond, PushCategory.PROPOSAL_OPENED)

        transactions.execute { notifications.cancelBond(bond) } shouldBe 1
        transactions.execute { notifications.cancelBond(bond) } shouldBe 0

        cancelled("PROPOSAL_OPENED", SendPush.NO_NUDGE) shouldBe 1.0
    }

    @Test
    fun `without a transaction it refuses`() {
        ask("proposal:ben", ben, bond, PushCategory.PROPOSAL_OPENED)

        shouldThrow<IllegalTransactionStateException> { notifications.cancelBond(bond) }

        status("proposal:ben") shouldBe "REQUESTED"
    }

    @Test
    fun `the statement reads the Bond's owed rows through the partial index`() {
        // In the leave's own transaction: it must not read the whole ledger (V71).
        val plan =
            transactions.execute {
                jdbc.execute("SET LOCAL enable_seqscan = off")
                jdbc.queryForList("EXPLAIN " + NotificationStore.CANCEL_OWED_OF_BOND, String::class.java, bond).joinToString("\n")
            }!!

        plan shouldContain "notifications_bond_owed_idx"
    }

    @Suppress("LongParameterList") // A request's varying fields.
    private fun ask(
        key: String,
        user: UUID,
        bondId: UUID,
        category: PushCategory,
        change: SharedChange? = null,
        nudge: Nudge? = null,
    ) {
        transactions.execute {
            notifications.request(
                PushRequest(
                    userId = user,
                    bondId = bondId,
                    category = category,
                    dedupeKey = key,
                    staleAfter = asked.plus(Duration.ofDays(1)),
                    recipient = PushRecipient(ZoneId.of("Europe/London"), null, NotificationPreferences.DEFAULTS),
                    bondDate = null,
                    nudge = nudge,
                    sharedChange = change,
                ),
            )
        } shouldBe true
    }

    private fun idOf(key: String): UUID = jdbc.queryForObject("SELECT id FROM notifications WHERE dedupe_key = ?", UUID::class.java, key)!!

    private fun status(key: String): String =
        jdbc.queryForObject("SELECT status FROM notifications WHERE dedupe_key = ?", String::class.java, key)!!

    private fun cancelled(
        category: String,
        nudge: String,
    ): Double =
        meters
            .find(PushRequests.CANCELLED)
            .tags("category", category, "nudge", nudge, "reason", PushRequests.BOND_ENDED)
            .counter()
            ?.count() ?: 0.0
}
