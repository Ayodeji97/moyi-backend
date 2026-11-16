package com.moyi.notification.infra

import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.SharedChange
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

/** V71: the shared change rides on the ledger row, and the CHECKs hold its shape (ADR-0069). */
@SpringBootTest(classes = [NotificationTestApplication::class])
internal class SharedSettingColumnsTest(
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
    fun `a shared change is stored and read back by lock`() {
        val id = ask("shared:a", PushCategory.SHARED_SETTING_CHANGED, SharedChange.PROPOSAL_DECLINED)

        jdbc.queryForObject("SELECT shared_change FROM notifications WHERE id = ?", String::class.java, id) shouldBe "PROPOSAL_DECLINED"
        transactions.execute { store.lock(id) }!!.sharedChange shouldBe SharedChange.PROPOSAL_DECLINED
    }

    @Test
    fun `a push of another category stores null`() {
        val id = ask("reveal:a", PushCategory.REVEAL, null)

        jdbc.queryForObject("SELECT shared_change FROM notifications WHERE id = ?", String::class.java, id) shouldBe null
        transactions.execute { store.lock(id) }!!.sharedChange shouldBe null
    }

    @Test
    fun `a shared change on a row of another category is refused by the database`() {
        val e = shouldThrow<DataIntegrityViolationException> { rawInsert("REVEAL", "SETTING") }
        e.message!! shouldContain "notifications_shared_change_check"
    }

    @Test
    fun `a shared-setting row without a change is refused by the database`() {
        val e = shouldThrow<DataIntegrityViolationException> { rawInsert("SHARED_SETTING_CHANGED", null) }
        e.message!! shouldContain "notifications_shared_change_check"
    }

    @Test
    fun `a change the enum does not know is refused by the database`() {
        val e = shouldThrow<DataIntegrityViolationException> { rawInsert("SHARED_SETTING_CHANGED", "BOGUS") }
        e.message!! shouldContain "notifications_shared_change_check"
    }

    private fun ask(
        key: String,
        category: PushCategory,
        change: SharedChange?,
    ): UUID {
        transactions.executeWithoutResult {
            notifications.request(
                PushRequest(
                    userId = UUID.randomUUID(),
                    bondId = UUID.randomUUID(),
                    category = category,
                    dedupeKey = key,
                    staleAfter = asked.plus(Duration.ofHours(12)),
                    recipient = PushRecipient(ZoneId.of("Africa/Lagos"), null, NotificationPreferences.DEFAULTS),
                    bondDate = null,
                    nudge = null,
                    sharedChange = change,
                ),
            )
        }
        return jdbc.queryForObject("SELECT id FROM notifications WHERE dedupe_key = ?", UUID::class.java, key)!!
    }

    private fun rawInsert(
        category: String,
        change: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO notifications (id, user_id, bond_id, category, dedupe_key, not_before, stale_after, status,
                                       created_at, zone, wanted, shared_change)
            VALUES (?, ?, ?, ?, ?, now(), now() + interval '1 hour', 'REQUESTED', now(), 'UTC', true, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            category,
            "raw:${UUID.randomUUID()}",
            change,
        )
    }
}
