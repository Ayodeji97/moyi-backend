package com.moyi.gratitude.service

import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DailyReminders
import com.moyi.gratitude.api.NudgeCounts
import com.moyi.gratitude.api.ReminderRun
import com.moyi.gratitude.infra.CapturingPushSender
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * The daily reminder (spec §4 N2, FR-076, ADR-0045), from the members' rows to
 * the ledger: `bond`'s roster says whose reminder fell, this module asks
 * whether they wrote, and `notification` records the push it will send.
 *
 * `Africa/Lagos`, UTC+1, for the bond and both members: the bond is paired at
 * 10:00Z on 2026-09-01 and the reminder set to 21:00 there, 20:00Z, so the
 * sweep asked about `(19:55Z, 20:00Z]` finds it. The clock configuration is
 * `StreakEvaluationTest`'s, so the contexts are shared.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(StreakEvaluationTest.TimeConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList") // A test's collaborators, each named.
internal class DailyRemindersTest(
    @Autowired private val reminders: DailyReminders,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val held: HeldPushes,
    @Autowired private val notificationsApi: Notifications,
    @Autowired private val pushTokens: PushTokens,
    @Autowired private val sender: CapturingPushSender,
    @Autowired transactionManager: PlatformTransactionManager,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(transactionManager)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE TABLE notifications, push_tokens, outbox_deliveries, outbox_events")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        sender.sent.clear()
        users.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(Instant.parse("2026-09-01T10:00:00Z"))
        val created = createBond(ada)
        bond = idOf(created)
        accept(bea, codeOf(created))
        listOf(ada, bea).forEach { settings(it, """{"reminderTimeLocal":"21:00","reminderTimezone":"Africa/Lagos"}""") }
        // The pairing's own events (the join's push) are not this class's subject.
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
    }

    @Test
    fun `a member who wrote gets no reminder, and one who did not gets one`() {
        write(ada)

        sweep() shouldBe
            ReminderRun(due = 2, requested = 1, alreadyWritten = 1, failed = 0, byNudge = mapOf(1 to NudgeCounts(1, 1, 0, 0)))

        reminded() shouldBe listOf(bea)
    }

    @Test
    fun `whether the other member has written changes nothing about a member's reminder`() {
        // FR-076, T-09: a reminder that came, or did not, or came worded otherwise,
        // when the partner had written would tell the member so.
        sweep()
        val nobodyWrote = rowOf(ada)
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        write(bea)
        sweep()

        reminded() shouldBe listOf(ada)
        rowOf(ada) shouldBe nobodyWrote
    }

    @Test
    fun `the reminder is asked for once a bond-day, however often its window is swept`() {
        sweep().requested shouldBe 2
        sweep() shouldBe
            ReminderRun(due = 2, requested = 0, alreadyWritten = 0, failed = 0, byNudge = mapOf(1 to NudgeCounts(0, 0, 2, 0)))
        jdbc.queryForObject("SELECT count(*) FROM notifications", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'PushRequested'", Int::class.java) shouldBe 2
    }

    @Test
    fun `a member who wrote and deleted the entry is reminded`() {
        val entry = write(ada)
        mockMvc
            .delete("/api/v1/entries/$entry") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response.status shouldBe 204

        sweep()

        reminded().toSet() shouldBe setOf(ada, bea)
    }

    @Test
    fun `the push carries the member's settings, its key, and goes stale when the bond-day ends`() {
        settings(
            ada,
            """{"reminderTimeLocal":"21:00","reminderTimezone":"Africa/Lagos","quietHoursStart":"23:00","quietHoursEnd":"07:00"}""",
        )
        notifications(bea, """{"categories":{"reminder":false}}""")

        sweep()

        val row = rowOf(ada)
        row["category"] shouldBe "REMINDER"
        row["dedupe_key"] shouldBe "reminder:${memberIdOf(ada)}:2026-09-01"
        row["stale_after"] shouldBe Timestamp.from(Instant.parse("2026-09-01T23:00:00Z"))
        row["zone"] shouldBe "Africa/Lagos"
        // Issue #146: the Bond-day it reminds of, for the phone to open.
        row["bond_date"] shouldBe java.sql.Date.valueOf("2026-09-01")
        row["quiet_hours_start"].toString() shouldBe "23:00:00"
        row["wanted"] shouldBe true
        // Bea turned reminders off: asked for all the same, and recorded unwanted, so the push consumer suppresses it.
        rowOf(bea)["wanted"] shouldBe false
    }

    @Test
    fun `a reminder held by quiet hours is called off when the member writes before they end`() {
        // The review's scenario: a 06:30 reminder, quiet hours 22:00 to 07:00, she writes at 06:45,
        // and must not be told to write at 07:00. Lagos is UTC+1: 06:30 there is 05:30Z.
        settings(
            ada,
            """{"reminderTimeLocal":"06:30","reminderTimezone":"Africa/Lagos","quietHoursStart":"22:00","quietHoursEnd":"07:00"}""",
        )
        val reminderAt = Instant.parse("2026-09-02T05:30:00Z")
        clock.set(reminderAt)
        reminders.remindDue(reminderAt.minusSeconds(300), reminderAt).requested shouldBe 1
        dispatcher.dispatchDue(reminderAt, DISPATCH)
        rowOf(ada)["status"] shouldBe "HELD"

        clock.set(Instant.parse("2026-09-02T05:45:00Z"))
        write(ada)

        rowOf(ada)["status"] shouldBe "CANCELLED"
        // 07:00 there: the hold ends, and there is nothing to release or send.
        val seven = Instant.parse("2026-09-02T06:00:00Z")
        clock.set(seven)
        held.releaseDue(seven, DISPATCH) shouldBe 0
        dispatcher.dispatchDue(seven, DISPATCH)
        rowOf(ada)["status"] shouldBe "CANCELLED"
        // A later sweep of the same window asks for nothing: the key is spent.
        reminders.remindDue(reminderAt.minusSeconds(300), reminderAt).requested shouldBe 0
    }

    @Test
    fun `an entry calls off only its own author's reminder for that day`() {
        sweep()
        clock.set(Instant.parse("2026-09-01T19:58:00Z"))
        write(bea)

        rowOf(bea)["status"] shouldBe "CANCELLED"
        rowOf(ada)["status"] shouldBe "REQUESTED"
    }

    @Test
    fun `a tick missed in a restart is caught up by the next, with exactly one push each`() {
        // Both reminders fall at 20:00Z. The 20:00 tick never ran; the job's next two runs look
        // back half an hour (ReminderJob.LOOKBACK), so both cover 20:00, and the key keeps it one.
        reminders.remindDue(Instant.parse("2026-09-01T19:35:00Z"), Instant.parse("2026-09-01T20:05:00Z")).requested shouldBe 2
        reminders.remindDue(Instant.parse("2026-09-01T19:40:00Z"), Instant.parse("2026-09-01T20:10:00Z")).requested shouldBe 0

        jdbc.queryForObject("SELECT count(*) FROM notifications WHERE category = 'REMINDER'", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'PushRequested'", Int::class.java) shouldBe 2
    }

    @Test
    fun `a reminder names the bond's day, not the member's - 23-30 in Reykjavik is already tomorrow in Lagos`() {
        // The bond's midnight is 23:00Z (Lagos, UTC+1); Ada's reminder is at 23:30 in her own zone,
        // Reykjavik's, which is UTC (a reminder zone is a region zone, never `UTC` itself).
        settings(ada, """{"reminderTimeLocal":"23:30","reminderTimezone":"Atlantic/Reykjavik"}""")
        clock.set(Instant.parse("2026-09-01T23:30:00Z"))

        reminders.remindDue(Instant.parse("2026-09-01T23:25:00Z"), Instant.parse("2026-09-01T23:30:00Z")).requested shouldBe 1

        rowOf(ada)["bond_date"] shouldBe java.sql.Date.valueOf("2026-09-02")
        rowOf(ada)["dedupe_key"] shouldBe "reminder:${memberIdOf(ada)}:2026-09-02"
    }

    @Test
    fun `nobody is reminded outside the window their reminder falls in`() {
        reminders.remindDue(Instant.parse("2026-09-01T20:00:00Z"), Instant.parse("2026-09-01T20:05:00Z")).due shouldBe 0
        reminded().shouldBeEmpty()
    }

    // ---- Nudges (#200, ADR-0068). Ada's reminder at 08:00 in Lagos: 07:00Z, then 10:00Z and 13:00Z,
    // all in the bond-day of 2026-09-02, which runs from 2026-09-01T23:00Z to 2026-09-02T23:00Z.

    @Test
    fun `one who never writes gets three nudges, each with its index`() {
        morningReminder(ada)
        phone(ada)

        listOf(NUDGE_1, NUDGE_2, NUDGE_3).forEach { sweepAt(it, dispatch = true) }

        val member = memberIdOf(ada)
        nudgesOf(ada) shouldBe
            listOf(
                Nudged("reminder:$member:2026-09-02", 1, 0, "SENT"),
                // One Bond, nothing revealed yet: the nudged Bond's own reveal is owed.
                Nudged("reminder:$member:2026-09-02:2", 2, 1, "SENT"),
                Nudged("reminder:$member:2026-09-02:3", 3, 1, "SENT"),
            )
        jdbc
            .queryForList("SELECT DISTINCT stale_after FROM notifications WHERE user_id = ?", Timestamp::class.java, ada)
            .shouldContainExactly(Timestamp.from(DAY_ENDS))
        sender.sent.map { it.nudge } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `writing after nudge 1 means no nudge 2`() {
        morningReminder(ada)
        sweepAt(NUDGE_1, dispatch = true)
        clock.set(Instant.parse("2026-09-02T08:00:00Z"))
        write(ada)

        sweepAt(NUDGE_2).byNudge shouldBe mapOf(2 to NudgeCounts(requested = 0, written = 1, alreadyAsked = 0, failed = 0))
        sweepAt(NUDGE_3).byNudge shouldBe mapOf(3 to NudgeCounts(requested = 0, written = 1, alreadyAsked = 0, failed = 0))

        nudgesOf(ada).map { it.nudge } shouldBe listOf(1)
    }

    @Test
    fun `writing calls off a nudge 2 held by quiet hours`() {
        // A newer nudge supersedes an unsent older one (C16), so a member has at most one unsent
        // nudge a day: a held nudge 2 and a requested nudge 3 never stand together. This test and
        // the next hold that a write calls off whichever index is the unsent one.
        settings(
            ada,
            """{"reminderTimeLocal":"08:00","reminderTimezone":"Africa/Lagos","quietHoursStart":"10:00","quietHoursEnd":"12:00"}""",
        )
        phone(ada)
        sweepAt(NUDGE_1, dispatch = true)
        sweepAt(NUDGE_2, dispatch = true)
        statusOf(ada, 2) shouldBe "HELD"

        clock.set(Instant.parse("2026-09-02T10:30:00Z"))
        write(ada)

        statusOf(ada, 2) shouldBe "CANCELLED"
        val noon = Instant.parse("2026-09-02T11:00:00Z")
        clock.set(noon)
        held.releaseDue(noon, DISPATCH) shouldBe 0
        sender.sent.map { it.nudge } shouldBe listOf(1)
    }

    @Test
    fun `writing calls off a requested nudge 3`() {
        morningReminder(ada)
        phone(ada)
        sweepAt(NUDGE_1, dispatch = true)
        sweepAt(NUDGE_2, dispatch = true)
        sweepAt(NUDGE_3)
        statusOf(ada, 3) shouldBe "REQUESTED"

        write(ada)
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), DISPATCH)

        statusOf(ada, 3) shouldBe "CANCELLED"
        sender.sent.map { it.nudge } shouldBe listOf(1, 2)
    }

    @Test
    fun `whether the other member wrote changes nothing about any nudge`() {
        // FR-076, T-09, for every index: the series, its keys and its counts are the same either way.
        morningReminder(ada)
        listOf(NUDGE_1, NUDGE_2, NUDGE_3).forEach { sweepAt(it, dispatch = true) }
        val nobodyWrote = nudgeRowsOf(ada)
        nobodyWrote.size shouldBe 3

        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        clock.set(Instant.parse("2026-09-02T06:00:00Z"))
        write(bea)
        listOf(NUDGE_1, NUDGE_2, NUDGE_3).forEach { sweepAt(it, dispatch = true) }

        nudgeRowsOf(ada) shouldBe nobodyWrote
    }

    @Test
    fun `quiet hours holding nudges 1 and 2 release one`() {
        settings(
            ada,
            """{"reminderTimeLocal":"08:00","reminderTimezone":"Africa/Lagos","quietHoursStart":"22:00","quietHoursEnd":"12:00"}""",
        )
        phone(ada)
        sweepAt(NUDGE_1, dispatch = true)
        statusOf(ada, 1) shouldBe "HELD"
        sweepAt(NUDGE_2, dispatch = true)

        statusOf(ada, 1) shouldBe "CANCELLED"
        statusOf(ada, 2) shouldBe "HELD"
        // It took over nudge 1's claim (C16): leave none free, so no cap may drop the day's only reminder.
        nudgesOf(ada).single { it.nudge == 2 }.leaveFree shouldBe 0

        val noon = Instant.parse("2026-09-02T11:00:00Z")
        clock.set(noon)
        held.releaseDue(noon, DISPATCH) shouldBe 1
        dispatcher.dispatchDue(noon.plusSeconds(1), DISPATCH)

        statusOf(ada, 2) shouldBe "SENT"
        sender.sent.map { it.nudge } shouldBe listOf(2)
    }

    @Test
    fun `nudge 1 is asked with leave-free 0 whatever is owed`() {
        val bonds = threeBonds()

        sweepAt(NUDGE_1)

        nudgesOf(ada).map { it.nudge to it.leaveFree } shouldBe List(bonds.size) { 1 to 0 }
    }

    @Test
    fun `nudge 2's leave-free counts the nudged Bond and every Bond still owed a reveal`() {
        val (_, second, third) = threeBonds()
        sweepAt(NUDGE_1, dispatch = true)
        clock.set(Instant.parse("2026-09-02T09:00:00Z"))
        // The second Bond's reveal for today is sent: settled. The third's for yesterday is sent too,
        // but today's is still owed.
        reveal(second, LocalDate.parse("2026-09-02"))
        reveal(third, LocalDate.parse("2026-09-01"))
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), DISPATCH)

        sweepAt(NUDGE_2)

        // Owed: the nudged Bond, the third Bond's today; not the second's. Each Bond's nudge 2 sees the same three.
        nudgesOf(ada).filter { it.nudge == 2 }.map { it.leaveFree } shouldBe listOf(2, 2, 2)
    }

    @Test
    fun `three Bonds - nudge 2 is held back and the evening's three reveals are all sent`() {
        val bonds = threeBonds()
        // Three nudge 1s sent: half of FR-082's six across Bonds.
        sweepAt(NUDGE_1, dispatch = true)

        sweepAt(NUDGE_2, dispatch = true)

        // 3 sent + 3 owed (every Bond, the nudged one included) = 6: each nudge 2 leaves room.
        nudgesOf(ada).filter { it.nudge == 2 }.map { it.status } shouldBe List(3) { "DROPPED_BUDGET" }
        clock.set(Instant.parse("2026-09-02T19:00:00Z"))
        bonds.forEach { reveal(it, LocalDate.parse("2026-09-02")) }
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), DISPATCH)

        jdbc.queryForList(
            "SELECT status FROM notifications WHERE user_id = ? AND category = 'REVEAL'",
            String::class.java,
            ada,
        ) shouldBe List(3) { "SENT" }
    }

    @Test
    fun `after yesterday's solo-unlock takes the reserved slot, nudge 3 leaves today's reveal its slot`() {
        // The lead's ruling (ADR-0068 decision 19): yesterday closed after its late-entry grace with Bea's
        // entry alone, so Ada's solo-unlock of it came at noon and took the Bond's reserved slot for today.
        morningReminder(ada)
        phone(ada)
        sweepAt(NUDGE_1, dispatch = true)
        sweepAt(NUDGE_2, dispatch = true)
        clock.set(Instant.parse("2026-09-02T11:00:00Z"))
        reveal(UUID.fromString(bond), LocalDate.parse("2026-09-01"), PushCategory.SOLO_UNLOCK)
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), DISPATCH)

        sweepAt(NUDGE_3, dispatch = true)

        // Three sent in the Bond, the reserved among them: the fourth is today's reveal's.
        statusOf(ada, 3) shouldBe "DROPPED_BUDGET"
        nudgesOf(ada).single { it.nudge == 3 }.leaveFree shouldBe 1
        clock.set(Instant.parse("2026-09-02T19:00:00Z"))
        write(bea)
        write(ada)
        reveal(UUID.fromString(bond), LocalDate.parse("2026-09-02"))
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), DISPATCH)

        sender.sent.map { it.category to it.nudge } shouldBe
            listOf(
                PushCategory.REMINDER to 1,
                PushCategory.REMINDER to 2,
                PushCategory.SOLO_UNLOCK to null,
                PushCategory.REVEAL to null,
            )
    }

    @Test
    fun `moving the reminder time mid-day never asks a fourth nudge`() {
        morningReminder(ada)
        phone(ada)
        sweepAt(NUDGE_1, dispatch = true)
        clock.set(Instant.parse("2026-09-02T08:00:00Z"))
        settings(ada, """{"reminderTimeLocal":"10:00","reminderTimezone":"Africa/Lagos"}""")

        // Every five-minute tick to the bond-day's end, each looking back half an hour (ReminderJob).
        var tick = Instant.parse("2026-09-02T08:05:00Z")
        while (tick <= DAY_ENDS) {
            clock.set(tick)
            reminders.remindDue(tick.minus(LOOKBACK), tick)
            dispatcher.dispatchDue(tick.plusSeconds(1), DISPATCH)
            tick = tick.plus(TICK)
        }

        // 10:00 is nudge 1 again, already asked; 13:00 and 16:00 are nudges 2 and 3. Each index once.
        nudgesOf(ada).map { it.nudge } shouldBe listOf(1, 2, 3)
        sender.sent.map { it.nudge } shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `a member who wrote and deleted the entry is nudged again later that day`() {
        morningReminder(ada)
        sweepAt(NUDGE_1, dispatch = true)
        clock.set(Instant.parse("2026-09-02T08:00:00Z"))
        val entry = write(ada)
        mockMvc
            .delete("/api/v1/entries/$entry") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
            .andReturn()
            .response.status shouldBe 204

        sweepAt(NUDGE_2).byNudge shouldBe mapOf(2 to NudgeCounts(requested = 1, written = 0, alreadyAsked = 0, failed = 0))
    }

    @Test
    fun `a reminder recorded before V68 is not asked again, and writing calls it off`() {
        morningReminder(ada)
        val member = memberIdOf(ada)
        clock.set(NUDGE_1.minusSeconds(60))
        // Today's key, as the sweep before nudges asked for it: no nudge index.
        transactions.executeWithoutResult {
            notificationsApi.request(
                PushRequest(
                    userId = ada,
                    bondId = UUID.fromString(bond),
                    category = PushCategory.REMINDER,
                    dedupeKey = "reminder:$member:2026-09-02",
                    staleAfter = DAY_ENDS,
                    recipient = LAGOS,
                    bondDate = LocalDate.parse("2026-09-02"),
                    nudge = null,
                ),
            )
        }

        sweepAt(NUDGE_1).byNudge shouldBe mapOf(1 to NudgeCounts(requested = 0, written = 0, alreadyAsked = 1, failed = 0))
        nudgesOf(ada) shouldBe listOf(Nudged("reminder:$member:2026-09-02", null, 0, "REQUESTED"))

        write(ada)

        statusOf(ada, null) shouldBe "CANCELLED"
    }

    @Test
    fun `one nudge that cannot be asked for costs only itself, and its warning names no one`(output: CapturedOutput) {
        listOf(ada, bea).forEach(::morningReminder)
        sweepAt(NUDGE_1)
        // Ada's nudge 2 alone is refused by the database; Bea's, in the same run, is not.
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION $FAILING_NUDGE() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'refused by the test' USING ERRCODE = 'check_violation';
            END ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TRIGGER $FAILING_NUDGE BEFORE INSERT ON notifications FOR EACH ROW
            WHEN (NEW.user_id = '$ada' AND NEW.nudge = 2) EXECUTE FUNCTION $FAILING_NUDGE()
            """.trimIndent(),
        )
        val logged = output.all.length
        val run =
            try {
                sweepAt(NUDGE_2)
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS $FAILING_NUDGE ON notifications")
                jdbc.execute("DROP FUNCTION IF EXISTS $FAILING_NUDGE()")
            }
        val duringTheRun = output.all.substring(logged).lines()

        run.byNudge shouldBe mapOf(2 to NudgeCounts(requested = 1, written = 0, alreadyAsked = 0, failed = 1))
        run.failed shouldBe 1
        // Bea's nudge 2 superseded her unsent nudge 1 (C16); Ada's failed with its whole transaction, nudge 1 untouched.
        nudgesOf(bea).map { it.nudge to it.status } shouldBe listOf(1 to "CANCELLED", 2 to "REQUESTED")
        nudgesOf(ada).map { it.nudge to it.status } shouldBe listOf(1 to "REQUESTED")
        // The next nudge is asked for as if nothing had happened: a failure is not retried, and stops nothing after it.
        sweepAt(NUDGE_3).byNudge shouldBe mapOf(3 to NudgeCounts(requested = 2, written = 0, alreadyAsked = 0, failed = 0))
        nudgesOf(ada).map { it.nudge to it.status } shouldBe listOf(1 to "CANCELLED", 3 to "REQUESTED")
        val warning = duringTheRun.single { "one nudge could not be asked for" in it }
        warning shouldContain "WARN"
        warning shouldContain "index=2"
        // Above DEBUG, no line the failing run wrote names the member, the user or the bond (the DEBUG line may).
        val ids = listOf(ada.toString(), memberIdOf(ada).toString(), bond)
        duringTheRun.filterNot { " DEBUG " in it || " TRACE " in it }.forEach { line ->
            ids.forEach { line shouldNotContain it }
        }
    }

    @Test
    fun `a run counts each index`() {
        listOf(ada, bea).forEach(::morningReminder)
        clock.set(Instant.parse("2026-09-02T06:00:00Z"))
        write(bea)

        sweepAt(NUDGE_1) shouldBe
            ReminderRun(due = 2, requested = 1, alreadyWritten = 1, failed = 0, byNudge = mapOf(1 to NudgeCounts(1, 1, 0, 0)))
        sweepAt(NUDGE_1) shouldBe
            ReminderRun(due = 2, requested = 0, alreadyWritten = 1, failed = 0, byNudge = mapOf(1 to NudgeCounts(0, 1, 1, 0)))
        // One wide window: both indices at once, each counted under its own.
        reminders.remindDue(NUDGE_1.minusSeconds(1), NUDGE_2) shouldBe
            ReminderRun(
                due = 4,
                requested = 1,
                alreadyWritten = 2,
                failed = 0,
                byNudge = mapOf(1 to NudgeCounts(0, 1, 1, 0), 2 to NudgeCounts(1, 1, 0, 0)),
            )
    }

    private fun sweep(): ReminderRun = reminders.remindDue(Instant.parse("2026-09-01T19:55:00Z"), Instant.parse("2026-09-01T20:00:00Z"))

    /** A reminder at 08:00 in Lagos for [userId] in [bondId]: 07:00Z, nudges at 10:00Z and 13:00Z. */
    private fun morningReminder(
        userId: UUID,
        bondId: String = bond,
    ) = settings(userId, """{"reminderTimeLocal":"08:00","reminderTimezone":"Africa/Lagos"}""", bondId)

    /** Sweeps the five minutes up to [at] with the clock there, and, if asked, runs the push consumer. */
    private fun sweepAt(
        at: Instant,
        dispatch: Boolean = false,
    ): ReminderRun {
        clock.set(at)
        val run = reminders.remindDue(at.minusSeconds(SWEEP_SECONDS), at)
        if (dispatch) dispatcher.dispatchDue(at.plusSeconds(1), DISPATCH)
        return run
    }

    /**
     * Ada in three Bonds (FR-025's most), each with an 08:00 Lagos reminder, the first with Bea; her
     * phone registered, and the other members' reminders in the evening, out of every morning sweep.
     */
    private fun threeBonds(): List<UUID> {
        val others =
            listOf("Cy", "Di").map { name ->
                val other = users.verified(name)
                val created = createBond(ada, "FRIENDS")
                accept(other, codeOf(created))
                val id = idOf(created)
                settings(other, """{"reminderTimeLocal":"21:00","reminderTimezone":"Africa/Lagos"}""", id)
                morningReminder(ada, id)
                id
            }
        morningReminder(ada)
        phone(ada)
        jdbc.execute("TRUNCATE TABLE notifications, outbox_deliveries, outbox_events")
        return (listOf(bond) + others).map(UUID::fromString)
    }

    /** A reveal-class push to Ada in [bondId] for [date], asked for as the day's consumer would, not yet sent. */
    private fun reveal(
        bondId: UUID,
        date: LocalDate,
        category: PushCategory = PushCategory.REVEAL,
    ) {
        transactions.executeWithoutResult {
            notificationsApi.request(
                PushRequest(
                    userId = ada,
                    bondId = bondId,
                    category = category,
                    dedupeKey = "${category.name.lowercase()}:$bondId:$date:$ada",
                    staleAfter = DAY_ENDS.plus(Duration.ofDays(1)),
                    recipient = LAGOS,
                    bondDate = date,
                    nudge = null,
                ),
            )
        }
    }

    private fun phone(userId: UUID) {
        transactions.executeWithoutResult {
            pushTokens.register(PushTokenRegistration(UUID.randomUUID(), userId, PushPlatform.ANDROID, PushToken("token-$userId")))
        }
    }

    private data class Nudged(
        val dedupeKey: String,
        val nudge: Int?,
        val leaveFree: Int,
        val status: String,
    )

    /** [userId]'s reminder rows, in key order (by Bond, then index). */
    private fun nudgesOf(userId: UUID): List<Nudged> =
        jdbc.query(
            """
            SELECT dedupe_key, nudge, leave_free_across_bonds, status FROM notifications
             WHERE user_id = ? AND category = 'REMINDER' ORDER BY bond_id, dedupe_key
            """.trimIndent(),
            { row, _ ->
                Nudged(
                    row.getString("dedupe_key"),
                    row.getObject("nudge")?.let { (it as Number).toInt() },
                    row.getInt("leave_free_across_bonds"),
                    row.getString("status"),
                )
            },
            userId,
        )

    /** The status of [userId]'s nudge [index] in the first bond; `null` is a pre-V68 row. */
    private fun statusOf(
        userId: UUID,
        index: Int?,
    ): String = nudgesOf(userId).single { it.nudge == index }.status

    /** Every column of [userId]'s reminder rows a member could learn from, less ids and moments of insert or send. */
    private fun nudgeRowsOf(userId: UUID): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT category, dedupe_key, nudge, leave_free_across_bonds, status, stale_after, bond_date, zone, wanted, reserved
              FROM notifications WHERE user_id = ? ORDER BY dedupe_key
            """.trimIndent(),
            userId,
        )

    private fun reminded(): List<UUID> =
        jdbc
            .queryForList(
                "SELECT user_id FROM notifications WHERE category = 'REMINDER' ORDER BY user_id",
                UUID::class.java,
            ).filterNotNull()

    /** The member's reminder row, less the columns that differ between any two requests: ids and when it was made. */
    private fun rowOf(userId: UUID): Map<String, Any?> =
        jdbc.queryForMap("SELECT * FROM notifications WHERE user_id = ?", userId) - setOf("id", "created_at", "not_before")

    private fun memberIdOf(userId: UUID): UUID =
        jdbc.queryForObject(
            "SELECT id FROM bond_members WHERE bond_id = ? AND user_id = ?",
            UUID::class.java,
            UUID.fromString(bond),
            userId,
        )!!

    /** Writes today's entry for [author] and returns its id. */
    private fun write(author: UUID): String {
        val response =
            mockMvc
                .post("/api/v1/bonds/$bond/entries") {
                    header(HttpHeaders.AUTHORIZATION, bearer(author))
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"text":"thank you"}"""
                }.andReturn()
                .response
        response.status shouldBe 201
        return idOf(response)
    }

    private fun settings(
        userId: UUID,
        body: String,
        bondId: String = bond,
    ) {
        mockMvc
            .put("/api/v1/bonds/$bondId/members/me/settings") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun notifications(
        userId: UUID,
        body: String,
    ) {
        mockMvc
            .put("/api/v1/bonds/$bond/members/me/notifications") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun createBond(
        userId: UUID,
        type: String = "COUPLE",
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"$type","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    private fun accept(
        userId: UUID,
        code: String,
    ) {
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private companion object {
        const val DISPATCH = 100
        const val SWEEP_SECONDS = 300L
        const val FAILING_NUDGE = "daily_reminders_test_failing_nudge"
        val NUDGE_1: Instant = Instant.parse("2026-09-02T07:00:00Z")
        val NUDGE_2: Instant = Instant.parse("2026-09-02T10:00:00Z")
        val NUDGE_3: Instant = Instant.parse("2026-09-02T13:00:00Z")
        val DAY_ENDS: Instant = Instant.parse("2026-09-02T23:00:00Z")
        val TICK: Duration = Duration.ofMinutes(5)
        val LOOKBACK: Duration = Duration.ofMinutes(30)
        val LAGOS = PushRecipient(ZoneId.of("Africa/Lagos"), null, NotificationPreferences())
    }
}
