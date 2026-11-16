package com.moyi.app.admin

import com.moyi.common.testing.MutableClock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.lang.reflect.InvocationTargetException
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random

/**
 * ADR-0067 decision 7, through the real jobs and the real `gratitude` and `bond` behind them, not
 * a recording fake: a flag switched on after the run's last boundary leaves an empty window, and
 * the run asks about nobody and still finishes. A window `(upTo, upTo]` handed to the roster is
 * refused by `ReminderTime` ("a window ends after it begins"), outside the per-member `try`, so
 * the run would fail, record no finish and leave the gauge stale.
 *
 * The check fires per candidate, so each test first makes one: a member, through the API, in a bond
 * of their own (`PENDING_MEMBER` takes entries, so it is reminded), opted in to on-this-day.
 *
 * The jobs are `internal` to `scheduling`, so they are found by name and run by their one public
 * method, the run without the timer or the lock.
 */
class PausedSendWindowTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private lateinit var start: Instant
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        start = clock.instant()
        jdbc.update(
            "INSERT INTO admin_accounts (id, email, role, credentials_changed_at, status) VALUES (?, ?, 'ADMIN', now(), 'ACTIVE')",
            admin,
            "paused-$admin@example.com",
        )
        aCandidate()
    }

    /** One member in a bond of their own: a reminder and on-this-day candidate for the real roster. */
    private fun aCandidate(): UUID {
        val email = "paused-${UUID.randomUUID().toString().take(8)}@example.com"
        val address = "192.0.2.${Random.nextInt(1, 255)}"
        mockMvc
            .post("/api/v1/auth/register") {
                header("X-Forwarded-For", address)
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"$PASSWORD","displayName":"Pau","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        jdbc.update("UPDATE users SET email_verified_at = now(), status = 'ACTIVE' WHERE email = ?::citext", email) shouldBe 1
        val signedIn =
            mockMvc
                .post("/api/v1/auth/login") {
                    header("X-Forwarded-For", address)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email":"$email","password":"$PASSWORD"}"""
                }.andReturn()
                .response
        signedIn.status shouldBe 200
        val access = Regex("\"accessToken\":\"([^\"]+)\"").find(signedIn.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer $access")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response.status shouldBe 201
        // On-this-day is opt-in (FR-092): opted in by SQL, so this member is its candidate too.
        jdbc.update(
            "UPDATE bond_members SET notification_prefs = notification_prefs || '{\"onThisDay\": true}'::jsonb " +
                "WHERE user_id = (SELECT id FROM users WHERE email = ?::citext)",
            email,
        ) shouldBe 1
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?::citext", UUID::class.java, email)!!
    }

    @AfterEach
    fun clean() {
        jdbc.update("DELETE FROM feature_flags")
        clock.set(start)
    }

    @Test
    fun `DAILY_REMINDERS switched on after the last boundary is an empty run that finishes, not a failed one`() {
        switchedOn("DAILY_REMINDERS", Instant.parse("2026-11-14T10:05:10Z"))
        clock.set(Instant.parse("2026-11-14T10:05:30Z"))

        run("com.moyi.scheduling.service.ReminderJob", "remindOnce")

        lastFinish("daily-reminders") shouldBe Instant.parse("2026-11-14T10:05:30Z")
    }

    @Test
    fun `ON_THIS_DAY_PUSHES switched on after the last boundary is an empty run that finishes, not a failed one`() {
        switchedOn("ON_THIS_DAY_PUSHES", Instant.parse("2026-11-14T10:15:20Z"))
        clock.set(Instant.parse("2026-11-14T10:15:45Z"))

        run("com.moyi.scheduling.service.OnThisDayJob", "pushOnce")

        lastFinish("on-this-day-pushes") shouldBe Instant.parse("2026-11-14T10:15:45Z")
    }

    @Test
    fun `switched on inside the look-back, the real roster is asked about the bounded window and the run finishes`() {
        switchedOn("DAILY_REMINDERS", Instant.parse("2026-11-14T10:02:30Z"))
        clock.set(Instant.parse("2026-11-14T10:05:30Z"))

        run("com.moyi.scheduling.service.ReminderJob", "remindOnce")

        lastFinish("daily-reminders") shouldBe Instant.parse("2026-11-14T10:05:30Z")
    }

    @Test
    fun `DAILY_REMINDERS switched on between nudge 1 and nudge 2 asks for nudge 2 only`() {
        val member = aCandidate()
        // 08:00 UTC, the day's first nudge; nudge 2 falls three real hours on, at 11:00.
        jdbc.update("UPDATE bond_members SET reminder_time_local = '08:00', reminder_timezone = 'UTC' WHERE user_id = ?", member)
        switchedOn("DAILY_REMINDERS", tomorrowAt("09:00:00"))

        clock.set(tomorrowAt("11:00:30"))
        run("com.moyi.scheduling.service.ReminderJob", "remindOnce")

        keysAskedOf(member).map { it.substringAfterLast(':') } shouldBe listOf("2")
    }

    @Test
    fun `DAILY_REMINDERS off asks for no nudge at the reminder time`() {
        val member = aCandidate()
        jdbc.update("UPDATE bond_members SET reminder_time_local = '08:00', reminder_timezone = 'UTC' WHERE user_id = ?", member)
        jdbc.update(
            "INSERT INTO feature_flags (key, enabled, updated_at, updated_by) VALUES (?, false, ?, ?)",
            "DAILY_REMINDERS",
            Timestamp.from(start),
            admin,
        )

        clock.set(tomorrowAt("08:00:30"))
        run("com.moyi.scheduling.service.ReminderJob", "remindOnce")

        keysAskedOf(member) shouldBe emptyList()
    }

    /** A day after the member's bond began (the roster asks nothing before it), at [time] UTC. */
    private fun tomorrowAt(time: String): Instant = Instant.parse("${start.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)}T${time}Z")

    private fun keysAskedOf(user: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT dedupe_key FROM notifications WHERE user_id = ? ORDER BY dedupe_key",
                String::class.java,
                user,
            ).filterNotNull()

    private fun switchedOn(
        key: String,
        at: Instant,
    ) {
        jdbc.update(
            "INSERT INTO feature_flags (key, enabled, updated_at, updated_by) VALUES (?, true, ?, ?)",
            key,
            Timestamp.from(at),
            admin,
        )
    }

    /** The job's run, its own exception rethrown as itself so a failure reads as the job's. */
    private fun run(
        job: String,
        method: String,
    ) {
        val bean = context.getBean(Class.forName(job))
        try {
            bean.javaClass.getMethod(method).invoke(bean)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private fun lastFinish(name: String): Instant? =
        jdbc
            .query("SELECT last_success_at FROM job_runs WHERE name = ?", { rs, _ -> rs.getTimestamp(1).toInstant() }, name)
            .firstOrNull()

    private companion object {
        const val PASSWORD = "correct horse battery"
    }
}
