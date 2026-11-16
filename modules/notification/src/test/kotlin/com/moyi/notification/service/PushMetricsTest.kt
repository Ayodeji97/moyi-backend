package com.moyi.notification.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.notification.api.CancelReason
import com.moyi.notification.api.HeldPushes
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.Notifications
import com.moyi.notification.api.Nudge
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushPlatform
import com.moyi.notification.api.PushRecipient
import com.moyi.notification.api.PushRequest
import com.moyi.notification.api.PushToken
import com.moyi.notification.api.PushTokenRegistration
import com.moyi.notification.api.PushTokens
import com.moyi.notification.api.QuietHoursWindow
import com.moyi.notification.api.SharedChange
import com.moyi.notification.infra.NotificationTestApplication
import com.moyi.notification.infra.ScriptedPushSender
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotMatch
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * What became of each push, counted (spec §9, ADR-0068): one counter per settlement, by category,
 * nudge and outcome, and one per push called off, by category, nudge and reason. No tag names a
 * member, a user or a Bond.
 */
@SpringBootTest(classes = [NotificationTestApplication::class])
@Suppress("LongParameterList") // What Spring hands the test.
internal class PushMetricsTest(
    @Autowired private val notifications: Notifications,
    @Autowired private val pushTokens: PushTokens,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val clock: MutableClock,
    @Autowired private val provider: ScriptedPushSender,
    @Autowired private val meters: MeterRegistry,
    @Autowired private val held: HeldPushes,
) : PostgresIntegrationTest() {
    private val member = UUID.randomUUID()
    private val bond = UUID.randomUUID()
    private val start = Instant.parse("2026-11-16T18:00:00Z")
    private val date = LocalDate.parse("2026-11-16")
    private val first = "reminder:$member:$date"
    private val second = "$first:2"

    @BeforeEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE notifications, push_tokens, outbox_deliveries, outbox_events")
        clock.set(start)
        provider.reset()
        meters.clear()
    }

    @Test
    fun `a sent nudge is counted sent, under its category and index`() {
        register("token")
        ask(first, Nudge(1, 0, emptySet()))
        dispatch()

        settled("REMINDER", "1", "sent") shouldBe 1.0
        totalSettled() shouldBe 1.0
    }

    @Test
    fun `a push about no nudge is counted under none`() {
        register("token")
        ask("joined:$member", null, PushCategory.PARTNER_JOINED)
        dispatch()

        settled("PARTNER_JOINED", "none", "sent") shouldBe 1.0
    }

    @Test
    fun `a push inside quiet hours is counted held`() {
        ask(first, Nudge(1, 0, emptySet()), recipient = recipient(quiet = QuietHoursWindow(LocalTime.of(17, 0), LocalTime.of(23, 0))))
        dispatch()

        settled("REMINDER", "1", "held") shouldBe 1.0
        totalSettled() shouldBe 1.0
    }

    @Test
    fun `a push held only by its own not_before is counted settling, not held`() {
        // ADR-0069 decision 13, spec B22: `held` stays quiet-hours volume.
        ask("shared:1", null, PushCategory.SHARED_SETTING_CHANGED, sharedChange = SharedChange.SETTING)
        dispatch()

        settled("SHARED_SETTING_CHANGED", "none", "settling") shouldBe 1.0
        settled("SHARED_SETTING_CHANGED", "none", "held") shouldBe 0.0
        totalSettled() shouldBe 1.0
    }

    @Test
    fun `a settling push that quiet hours would hold longer is counted held`() {
        val night = recipient(quiet = QuietHoursWindow(LocalTime.of(17, 0), LocalTime.of(23, 0)))
        ask("shared:1", null, PushCategory.SHARED_SETTING_CHANGED, recipient = night, sharedChange = SharedChange.SETTING)
        dispatch()

        settled("SHARED_SETTING_CHANGED", "none", "held") shouldBe 1.0
        settled("SHARED_SETTING_CHANGED", "none", "settling") shouldBe 0.0
    }

    @Test
    fun `a held push counts held once, and its final outcome once when released`() {
        register("token")
        val night = recipient(quiet = QuietHoursWindow(LocalTime.of(17, 0), LocalTime.of(23, 0)))
        ask(first, Nudge(1, 0, emptySet()), recipient = night)
        dispatch()

        settled("REMINDER", "1", "held") shouldBe 1.0
        settled("REMINDER", "1", "sent") shouldBe 0.0

        clock.set(Instant.parse("2026-11-16T23:00:00Z"))
        held.releaseDue(clock.instant(), 10) shouldBe 1
        dispatch()

        settled("REMINDER", "1", "held") shouldBe 1.0
        settled("REMINDER", "1", "sent") shouldBe 1.0
        totalSettled() shouldBe 2.0
    }

    @Test
    fun `cancelling a held push counts it cancelled, reason written, once`() {
        val night = recipient(quiet = QuietHoursWindow(LocalTime.of(17, 0), LocalTime.of(23, 0)))
        ask(first, Nudge(1, 0, emptySet()), recipient = night)
        dispatch()

        transactions.execute { notifications.cancel(listOf(first), CancelReason.WRITTEN) } shouldBe 1
        transactions.execute { notifications.cancel(listOf(first), CancelReason.WRITTEN) } shouldBe 0

        cancelled("REMINDER", "1", "written") shouldBe 1.0
    }

    @Test
    fun `the reason a caller gives is the reason counted`() {
        ask(first, Nudge(1, 0, emptySet()))

        transactions.execute { notifications.cancel(listOf(first), CancelReason.PROPOSAL_CLOSED) } shouldBe 1

        cancelled("REMINDER", "1", "proposal_closed") shouldBe 1.0
        cancelled("REMINDER", "1", "written") shouldBe 0.0
    }

    @Test
    fun `a push the member turned off is counted suppressed`() {
        ask(first, Nudge(1, 0, emptySet()), recipient = recipient(NotificationPreferences(reminder = false)))
        dispatch()

        settled("REMINDER", "1", "suppressed") shouldBe 1.0
    }

    @Test
    fun `a push past its deadline is counted expired`() {
        ask(first, Nudge(1, 0, emptySet()), staleAfter = start.minusSeconds(1))
        dispatch()

        settled("REMINDER", "1", "expired") shouldBe 1.0
    }

    @Test
    fun `a push the provider refuses for good is counted failed`() {
        register("token")
        provider.answer("token", PushDelivery.Rejected("refused"))
        ask(first, Nudge(1, 0, emptySet()))
        dispatch()

        settled("REMINDER", "1", "failed") shouldBe 1.0
    }

    @Test
    fun `a push the provider cannot take now is retried and counted nothing`() {
        register("token")
        provider.answer("token", PushDelivery.Unavailable("down"))
        ask(first, Nudge(1, 0, emptySet()))
        dispatch()

        totalSettled() shouldBe 0.0
    }

    @Test
    fun `a push over the per-Bond cap is counted dropped_budget`() {
        register("token")
        repeat(3) { ask("other:$it", null, PushCategory.MILESTONE) }
        dispatch()
        ask(first, Nudge(1, 0, emptySet()))
        dispatch()

        settled("MILESTONE", "none", "sent") shouldBe 3.0
        settled("REMINDER", "1", "dropped_budget") shouldBe 1.0
        settled("REMINDER", "1", "dropped_leaving_room") shouldBe 0.0
    }

    @Test
    fun `a nudge that would leave too few cross-Bond slots is counted dropped_leaving_room, not dropped_budget`() {
        register("token")
        ask(first, Nudge(1, 0, emptySet()))
        dispatch()
        ask(second, Nudge(2, 5, emptySet()))
        dispatch()

        settled("REMINDER", "1", "sent") shouldBe 1.0
        settled("REMINDER", "2", "dropped_leaving_room") shouldBe 1.0
        settled("REMINDER", "2", "dropped_budget") shouldBe 0.0
    }

    @Test
    fun `a nudge superseded by a newer one is counted cancelled, reason superseded, under its own index`() {
        ask(first, Nudge(1, 0, emptySet()))

        ask(second, Nudge(2, 0, setOf(first)))

        cancelled("REMINDER", "1", "superseded") shouldBe 1.0
        cancelled("REMINDER", "2", "superseded") shouldBe 0.0
    }

    @Test
    fun `a re-ask counts nothing`() {
        ask(first, Nudge(1, 0, emptySet()))
        ask(second, Nudge(2, 0, setOf(first))) shouldBe true
        ask(second, Nudge(2, 0, setOf(first))) shouldBe false

        cancelled("REMINDER", "1", "superseded") shouldBe 1.0
    }

    @Test
    fun `a push called off by its writer is counted once per row, reason written, and a sent one not at all`() {
        ask(first, Nudge(1, 0, emptySet()))
        ask(second, Nudge(2, 0, emptySet()))
        register("token")
        ask("$first:sent", Nudge(3, 0, emptySet()))
        dispatch()
        jdbc.update("UPDATE notifications SET status = 'REQUESTED', sent_at = NULL WHERE dedupe_key IN (?, ?)", first, second)
        jdbc.update("UPDATE notifications SET status = 'SENT' WHERE dedupe_key = ?", "$first:sent")

        transactions.execute { notifications.cancel(listOf(first, second, "$first:sent"), CancelReason.WRITTEN) } shouldBe 2
        transactions.execute { notifications.cancel(listOf(first, second, "$first:sent"), CancelReason.WRITTEN) } shouldBe 0

        cancelled("REMINDER", "1", "written") shouldBe 1.0
        cancelled("REMINDER", "2", "written") shouldBe 1.0
        cancelled("REMINDER", "3", "written") shouldBe 0.0
    }

    @Test
    fun `no tag of any meter is an id`() {
        register("token")
        ask(first, Nudge(1, 0, emptySet()))
        ask(second, Nudge(2, 0, setOf(first)))
        dispatch()
        transactions.execute { notifications.cancel(listOf(second), CancelReason.WRITTEN) }

        val ours = meters.meters.filter { it.id.name.startsWith("notification.push.") }
        (ours.size > 0) shouldBe true
        ours.flatMap { it.id.tags }.forEach { tag ->
            tag.value shouldNotMatch UUID_PATTERN
            listOf(member.toString(), bond.toString()).forEach { id -> tag.value.contains(id) shouldBe false }
        }
        ours
            .flatMap { it.id.tags }
            .map { it.key }
            .toSet()
            .all { it in setOf("category", "nudge", "outcome", "reason") } shouldBe true
    }

    private fun settled(
        category: String,
        nudge: String,
        outcome: String,
    ): Double =
        meters
            .find(SendPush.SETTLED)
            .tags("category", category, "nudge", nudge, "outcome", outcome)
            .counter()
            ?.count() ?: 0.0

    private fun cancelled(
        category: String,
        nudge: String,
        reason: String,
    ): Double =
        meters
            .find(PushRequests.CANCELLED)
            .tags("category", category, "nudge", nudge, "reason", reason)
            .counter()
            ?.count() ?: 0.0

    private fun totalSettled(): Double = meters.find(SendPush.SETTLED).counters().sumOf { it.count() }

    private fun dispatch() = dispatcher.dispatchDue(clock.instant(), BUDGET)

    private fun register(token: String) {
        transactions.execute {
            pushTokens.register(PushTokenRegistration(UUID.randomUUID(), member, PushPlatform.ANDROID, PushToken(token)))
        }
    }

    private fun recipient(
        preferences: NotificationPreferences = NotificationPreferences.DEFAULTS,
        quiet: QuietHoursWindow? = null,
    ) = PushRecipient(ZoneId.of("UTC"), quiet, preferences)

    private fun ask(
        key: String,
        nudge: Nudge?,
        category: PushCategory = PushCategory.REMINDER,
        recipient: PushRecipient = recipient(),
        staleAfter: Instant = start.plus(Duration.ofDays(1)),
        sharedChange: SharedChange? = null,
    ): Boolean =
        transactions.execute {
            notifications.request(
                PushRequest(
                    userId = member,
                    bondId = bond,
                    category = category,
                    dedupeKey = key,
                    staleAfter = staleAfter,
                    recipient = recipient,
                    bondDate = date,
                    nudge = nudge,
                    sharedChange = sharedChange,
                ),
            )
        }

    private companion object {
        const val BUDGET = 10
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-.*")
    }
}
