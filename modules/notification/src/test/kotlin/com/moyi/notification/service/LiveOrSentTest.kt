package com.moyi.notification.service

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.infra.NotificationTestApplication
import io.kotest.matchers.shouldBe
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

/**
 * `Notifications.hasLiveOrSent` (ADR-0046): the narrow read the on-this-day
 * sweep bounds its week with. A push counts while it was sent or is still
 * owed, and only for its own member, Bond and category, after the instant
 * asked about.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
internal class LiveOrSentTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
) : PostgresIntegrationTest() {
    private val ada = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val asked = Instant.parse("2026-09-01T11:00:00Z")

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        clock.set(asked)
        transactions.executeWithoutResult {
            notifications.request(
                PushRequest(
                    userId = ada,
                    bondId = bond,
                    category = PushCategory.ON_THIS_DAY,
                    dedupeKey = "on-this-day:probe",
                    staleAfter = asked.plus(Duration.ofHours(12)),
                    recipient = PushRecipient(ZoneId.of("Africa/Lagos"), null, NotificationPreferences(onThisDay = true)),
                ),
            )
        }
    }

    @Test
    fun `sent and still owed count, and nothing that reached nobody does`() {
        val counted =
            listOf("REQUESTED", "HELD", "SENT", "EXPIRED", "DROPPED_BUDGET", "SUPPRESSED", "CANCELLED", "FAILED_PERMANENT")
                .associateWith { status ->
                    // SENT needs its sent_at (V30's CHECK); the rest need none.
                    jdbc.update(
                        "UPDATE notifications SET status = ?, sent_at = CASE WHEN ? = 'SENT' THEN created_at END",
                        status,
                        status,
                    )
                    weekBefore(asked.plusSeconds(1))
                }

        counted shouldBe
            mapOf(
                "REQUESTED" to true,
                "HELD" to true,
                "SENT" to true,
                "EXPIRED" to false,
                "DROPPED_BUDGET" to false,
                "SUPPRESSED" to false,
                "CANCELLED" to false,
                "FAILED_PERMANENT" to false,
            )
    }

    @Test
    fun `only after the instant asked about, and only for its own member, Bond and category`() {
        notifications.hasLiveOrSent(ada, bond, PushCategory.ON_THIS_DAY, asked.minusNanos(1_000)) shouldBe true
        notifications.hasLiveOrSent(ada, bond, PushCategory.ON_THIS_DAY, asked) shouldBe false
        notifications.hasLiveOrSent(UUID.randomUUID(), bond, PushCategory.ON_THIS_DAY, asked.minusSeconds(1)) shouldBe false
        notifications.hasLiveOrSent(ada, UUID.randomUUID(), PushCategory.ON_THIS_DAY, asked.minusSeconds(1)) shouldBe false
        notifications.hasLiveOrSent(ada, bond, PushCategory.MILESTONE, asked.minusSeconds(1)) shouldBe false
    }

    /** Asked as the sweep asks it: anything in the seven days before [now]. */
    private fun weekBefore(now: Instant): Boolean =
        notifications.hasLiveOrSent(ada, bond, PushCategory.ON_THIS_DAY, now.minus(Duration.ofDays(7)))
}
