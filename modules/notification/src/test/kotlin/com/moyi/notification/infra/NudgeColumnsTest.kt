package com.moyi.notification.infra

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.Nudge
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.infra.database.NotificationStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** V68: a nudge's index and leave-free ride on the ledger row, and the CHECKs hold the shape. */
@SpringBootTest(classes = [NotificationTestApplication::class])
internal class NudgeColumnsTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val store: NotificationStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
) : PostgresIntegrationTest() {
    private val asked = Instant.parse("2026-11-16T09:00:00Z")

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        clock.set(asked)
    }

    @Test
    fun `a nudge and its leave-free are stored and read back by lock`() {
        val id = ask("reminder:a:2", nudge = Nudge(2, 1, emptySet()))

        jdbc.queryForMap("SELECT nudge, leave_free_across_bonds FROM notifications WHERE id = ?", id) shouldBe
            mapOf("nudge" to 2.toShort(), "leave_free_across_bonds" to 1.toShort())
        val stored = transactions.execute { store.lock(id) }!!
        stored.nudge shouldBe 2
        stored.leaveFreeAcrossBonds shouldBe 1
    }

    @Test
    fun `a request with no nudge stores null and zero`() {
        val id = ask("reminder:b", nudge = null)

        jdbc.queryForMap("SELECT nudge, leave_free_across_bonds FROM notifications WHERE id = ?", id) shouldBe
            mapOf("nudge" to null, "leave_free_across_bonds" to 0.toShort())
        val stored = transactions.execute { store.lock(id) }!!
        stored.nudge shouldBe null
        stored.leaveFreeAcrossBonds shouldBe 0
    }

    @Test
    fun `a nudge on a row that is not a REMINDER is refused by the database`() {
        val e = shouldThrow<DataIntegrityViolationException> { rawInsert("REVEAL", nudge = 1, leaveFree = 0) }
        e.message!! shouldContain "notifications_nudge_check"
    }

    @Test
    fun `a leave-free of 6 is refused by the database`() {
        val e = shouldThrow<DataIntegrityViolationException> { rawInsert("REMINDER", nudge = 2, leaveFree = 6) }
        e.message!! shouldContain "notifications_leave_free_check"
    }

    private fun ask(
        key: String,
        nudge: Nudge?,
    ): UUID {
        transactions.executeWithoutResult {
            notifications.request(
                PushRequest(
                    userId = UUID.randomUUID(),
                    bondId = UUID.randomUUID(),
                    category = PushCategory.REMINDER,
                    dedupeKey = key,
                    staleAfter = asked.plus(Duration.ofHours(12)),
                    recipient = PushRecipient(ZoneId.of("Africa/Lagos"), null, NotificationPreferences.DEFAULTS),
                    bondDate = null,
                    nudge = nudge,
                    sharedChange = null,
                ),
            )
        }
        return jdbc.queryForObject("SELECT id FROM notifications WHERE dedupe_key = ?", UUID::class.java, key)!!
    }

    private fun rawInsert(
        category: String,
        nudge: Int,
        leaveFree: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO notifications (id, user_id, bond_id, category, dedupe_key, not_before, stale_after, status,
                                       created_at, zone, wanted, nudge, leave_free_across_bonds)
            VALUES (?, ?, ?, ?, ?, now(), now() + interval '1 hour', 'REQUESTED', now(), 'UTC', true, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            category,
            "raw:${UUID.randomUUID()}",
            nudge,
            leaveFree,
        )
    }
}
