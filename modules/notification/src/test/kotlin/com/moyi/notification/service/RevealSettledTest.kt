package com.moyi.notification.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.domain.NotificationStatus
import com.moyi.notification.infra.NotificationTestApplication
import com.moyi.notification.infra.database.NotificationStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * `Notifications.revealClassSettled` (spec C17, ADR-0068 decision 6): which of a member's
 * Bond-days already had their reveal-class push settled, so a nudge leaves no slot free for them.
 * Settled is a final status other than `CANCELLED`; `REQUESTED` and `HELD` are still owed.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
internal class RevealSettledTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val today = LocalDate.parse("2026-09-01")
    private val now = Instant.parse("2026-09-01T18:00:00Z")

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        clock.set(now)
    }

    @Test
    fun `a final status other than cancelled is settled, and an owed one is not`() {
        val settled =
            listOf("REQUESTED", "HELD", "SENT", "SUPPRESSED", "DROPPED_BUDGET", "EXPIRED", "FAILED_PERMANENT", "CANCELLED")
                .associateWith { status ->
                    jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
                    record(status = status)
                    bond in notifications.revealClassSettled(ada, mapOf(bond to today))
                }

        settled shouldBe
            mapOf(
                "REQUESTED" to false,
                "HELD" to false,
                "SENT" to true,
                "SUPPRESSED" to true,
                "DROPPED_BUDGET" to true,
                "EXPIRED" to true,
                "FAILED_PERMANENT" to true,
                "CANCELLED" to false,
            )
    }

    @Test
    fun `settled and live-or-sent cover every status but cancelled, and meet only at sent`() {
        (PushRequests.SETTLED + PushRequests.LIVE_OR_SENT) shouldBe
            NotificationStatus.entries.toSet() - NotificationStatus.CANCELLED
        (PushRequests.SETTLED intersect PushRequests.LIVE_OR_SENT) shouldBe setOf(NotificationStatus.SENT)
    }

    @Test
    fun `a day opening with one entry is reveal-class too`() {
        record(category = PushCategory.SOLO_UNLOCK)

        notifications.revealClassSettled(ada, mapOf(bond to today)) shouldBe setOf(bond)
    }

    @Test
    fun `yesterday's reveal does not settle today`() {
        record(date = today.minusDays(1))

        notifications.revealClassSettled(ada, mapOf(bond to today)).shouldBeEmpty()
    }

    @Test
    fun `another member's reveal, another Bond's, or a milestone settles nothing`() {
        record(user = UUID.randomUUID())
        record(bondId = UUID.randomUUID())
        record(category = PushCategory.MILESTONE)

        notifications.revealClassSettled(ada, mapOf(bond to today)).shouldBeEmpty()
    }

    @Test
    fun `three Bonds at once are each answered by their own date`() {
        val second = UUID.randomUUID()
        val third = UUID.randomUUID()
        val tomorrow = today.plusDays(1)
        record()
        record(bondId = second, date = today)
        record(bondId = third, date = today, status = "REQUESTED")

        // The second Bond is asked about a later day (its zone has moved on): today's reveal is not that day's.
        notifications.revealClassSettled(ada, mapOf(bond to today, second to tomorrow, third to today)) shouldBe
            setOf(bond)
        notifications.revealClassSettled(ada, mapOf(bond to today, second to today, third to today)) shouldBe
            setOf(bond, second)
    }

    @Test
    fun `a reveal asked for more than 72 hours ago is not looked at`() {
        record(at = now.minus(Duration.ofHours(72)).minusSeconds(1))

        notifications.revealClassSettled(ada, mapOf(bond to today)).shouldBeEmpty()

        record(at = now.minus(Duration.ofHours(72)).plusSeconds(1))
        notifications.revealClassSettled(ada, mapOf(bond to today)) shouldBe setOf(bond)
    }

    @Test
    fun `no Bonds asked about, no answer`() {
        record()

        notifications.revealClassSettled(ada, emptyMap()).shouldBeEmpty()
    }

    @Test
    fun `the question can be answered from the recipient index`() {
        // An empty table gives the planner nothing to prefer an index for: seqscan off asks which index it can use.
        val plan =
            transactions.execute {
                jdbc.execute("SET LOCAL enable_seqscan = off")
                jdbc
                    .queryForList(
                        "EXPLAIN " + NotificationStore.DATED_SINCE,
                        String::class.java,
                        ada,
                        arrayOf(PushCategory.REVEAL.name, PushCategory.SOLO_UNLOCK.name),
                        Timestamp.from(now.minus(Duration.ofHours(72))),
                        arrayOf(bond),
                        // The real set: with SENT alone, V30's partial budget index would serve instead.
                        PushRequests.SETTLED.map { it.name }.toTypedArray(),
                    ).joinToString("\n")
            }

        plan shouldContain "notifications_recipient_idx"
        plan shouldContain Regex("Index Cond: .*category = ANY .*created_at >")
    }

    @Suppress("LongParameterList") // A push, column for column, each defaulted to the one asked about.
    private fun record(
        user: UUID = ada,
        bondId: UUID = bond,
        category: PushCategory = PushCategory.REVEAL,
        date: LocalDate = today,
        status: String = "SENT",
        at: Instant = now.minus(Duration.ofHours(1)),
    ) {
        clock.set(at)
        val key = "probe:${UUID.randomUUID()}"
        transactions.executeWithoutResult {
            notifications.request(
                PushRequest(
                    userId = user,
                    bondId = bondId,
                    category = category,
                    dedupeKey = key,
                    staleAfter = at.plus(Duration.ofHours(12)),
                    recipient = PushRecipient(ZoneId.of("Africa/Lagos"), null, NotificationPreferences.DEFAULTS),
                    bondDate = date,
                    nudge = null,
                    sharedChange = null,
                ),
            )
        }
        // SENT needs its sent_at (V30's CHECK); the rest need none.
        jdbc.update(
            "UPDATE notifications SET status = ?, sent_at = CASE WHEN ? = 'SENT' THEN created_at END WHERE dedupe_key = ?",
            status,
            status,
            key,
        )
        clock.set(now)
    }
}
