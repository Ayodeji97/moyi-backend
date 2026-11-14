package com.moyi.app.admin

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingUnavailableException
import com.moyi.app.admin.ModerationRig.Companion.result
import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags
import com.moyi.common.testing.MutableClock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * A4's pages through the real chain (A4 spec §4, §9; plan Task 7): the jobs page with the real close
 * job and ShedLock, the flags page with the real table and the ports that read it, and the support
 * inbox with ADR-0057's real functions. Each role's view, each POST's answer, the audit rows, and
 * nothing secret in a `Location`, a flash or the log.
 */
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LargeClass") // Three pages and their roles, one context.
class A4PagesTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val flags: FeatureFlags,
    @Autowired private val embeddings: EmbeddingPort,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ModerationRig(mockMvc, jdbc, clock)

    @BeforeEach
    @AfterEach
    fun clean() {
        jdbc.update("DELETE FROM feature_flags")
        jdbc.update("DELETE FROM support_ticket_log")
        jdbc.update("DELETE FROM support_tickets")
        releaseCloseLock()
    }

    // --- navigation and roles ----------------------------------------------------------------------------------------

    @Test
    fun `every role sees the inbox and the jobs, only an ADMIN the flags and the forms that act`() {
        mapOf("SUPPORT" to false, "MODERATOR" to false, "ADMIN" to true).forEach { (role, admin) ->
            withClue(role) {
                val session = rig.session(role)
                val home = rig.get(session, "/admin").contentAsString
                home shouldContain """href="/admin/support""""
                home shouldContain """href="/admin/jobs""""
                home shouldContain """id="open-tickets""""
                (""""/admin/flags"""" in home) shouldBe admin

                val jobs = rig.get(session, "/admin/jobs")
                jobs.status shouldBe 200
                jobs.contentAsString shouldContain """data-job="close-days""""
                ("""id="run-close"""" in jobs.contentAsString) shouldBe admin
                ("""id="rebuild"""" in jobs.contentAsString) shouldBe admin
                rig.get(session, "/admin/support").status shouldBe 200
                rig.get(session, "/admin/flags").status shouldBe if (admin) 200 else 403
            }
        }
    }

    @Test
    fun `a SUPPORT or MODERATOR session's POSTs to the jobs and the flags are the 403 page and write nothing`() {
        listOf("SUPPORT", "MODERATOR").forEach { role ->
            val session = rig.session(role)
            listOf(
                "/admin/jobs/close-days/run" to mapOf("justification" to WHY),
                "/admin/jobs/streak/recalculate" to mapOf("bondId" to UUID.randomUUID().toString(), "justification" to WHY),
                "/admin/flags/DAILY_REMINDERS" to mapOf("enabled" to "false", "justification" to WHY),
            ).forEach { (path, form) ->
                withClue("$role $path") { rig.post(session, path, form).response.status shouldBe 403 }
            }
            rig.audited(rig.adminOf(session)).shouldBeEmpty()
        }
        flags.isOn(FeatureFlag.DAILY_REMINDERS) shouldBe true
    }

    // --- the jobs ----------------------------------------------------------------------------------------------------

    @Test
    fun `an ADMIN runs the close job, a 303 with the counts in the flash, a STARTED row, and its finish on the page`(
        output: CapturedOutput,
    ) {
        val session = rig.session("ADMIN")
        val admin = rig.adminOf(session)

        val ran = rig.post(session, "/admin/jobs/close-days/run", mapOf("justification" to WHY))

        ran.response.status shouldBe 303
        ran.response.redirectedUrl shouldBe "/admin/jobs"
        ran.result()!! shouldStartWith "Ran: created "
        rig.audited(admin).map { it.head } shouldContainExactly listOf("JOB_RUN|SUCCEEDED|null")
        rig.audited(admin).single().metadata shouldBe """{"job": "close-days", "result": "STARTED"}"""
        rig.audited(admin).single().justification shouldBe WHY
        val page = rig.follow(session, ran).contentAsString
        page shouldContain ran.result()!!
        jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'close-days'", Int::class.java) shouldBe 1
        output.out shouldNotContain WHY
    }

    @Test
    fun `while the close job's lock is held the run is BUSY, a REFUSED row, and nothing runs`() {
        val session = rig.session("ADMIN")
        val finishedBefore = lastClose()
        jdbc.update(
            "INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES ('close-days', $UTC_NOW + interval '10 minutes', " +
                "$UTC_NOW, 'another-instance') ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, " +
                "locked_at = EXCLUDED.locked_at, locked_by = EXCLUDED.locked_by",
        )

        val busy = rig.post(session, "/admin/jobs/close-days/run", mapOf("justification" to WHY))

        busy.response.status shouldBe 303
        busy.result() shouldBe "The close job is running now (the timer or another instance). Nothing was started; look again in a minute."
        rig.audited(rig.adminOf(session)).map { it.head + " " + it.metadata } shouldContainExactly
            listOf("""JOB_RUN|REFUSED|null {"job": "close-days", "result": "BUSY"}""")
        lastClose() shouldBe finishedBefore
        rig.get(session, "/admin/jobs").contentAsString shouldContain "another"
    }

    @Test
    fun `a refused justification is the page again with 422, the text kept, and no row`() {
        val session = rig.session("ADMIN")
        val long = "x".repeat(401)

        val run = rig.post(session, "/admin/jobs/close-days/run", mapOf("justification" to long))
        val rebuild =
            rig.post(
                session,
                "/admin/jobs/streak/recalculate",
                mapOf(
                    "bondId" to UUID.randomUUID().toString(),
                    "justification" to "",
                ),
            )
        val notABond = rig.post(session, "/admin/jobs/streak/recalculate", mapOf("bondId" to "nope", "justification" to WHY))

        run.response.status shouldBe 422
        run.response.contentAsString shouldContain "Write a justification of 1 to 400 characters saying why."
        run.response.contentAsString shouldContain long
        rebuild.response.status shouldBe 422
        notABond.response.status shouldBe 422
        notABond.response.contentAsString shouldContain "Not a bond id."
        rig.audited(rig.adminOf(session)).shouldBeEmpty()
    }

    @Test
    fun `a rebuild of a bond with no counted day is answered and written REFUSED NO_DAYS`() {
        val session = rig.session("ADMIN")
        val bond = UUID.randomUUID()

        val posted = rig.post(session, "/admin/jobs/streak/recalculate", mapOf("bondId" to bond.toString(), "justification" to WHY))

        posted.response.status shouldBe 303
        posted.result() shouldBe "That bond has no counted day: nothing to rebuild."
        rig.audited(rig.adminOf(session)).map { it.head } shouldContainExactly listOf("STREAK_RECALCULATED|REFUSED|$bond")
    }

    @Test
    fun `the date check counts a date's days, and a value that is not a date is 400 with the page`() {
        val session = rig.session("SUPPORT")

        val checked = rig.get(session, "/admin/jobs", "date" to "2020-01-01")
        val wrong = rig.get(session, "/admin/jobs", "date" to "yesterday")

        checked.status shouldBe 200
        checked.contentAsString shouldContain """id="past-due""""
        wrong.status shouldBe 400
        wrong.contentAsString shouldContain "Not a date. Use YYYY-MM-DD."
        rig.audited(rig.adminOf(session)).shouldBeEmpty()
    }

    // --- the flags ---------------------------------------------------------------------------------------------------

    @Test
    fun `a flag switched off on the page is off for every reader at once, and on again restores them`() {
        val session = rig.session("ADMIN")

        val off = rig.post(session, "/admin/flags/AI_EMBEDDINGS", mapOf("enabled" to "false", "justification" to WHY))

        off.response.status shouldBe 303
        off.response.redirectedUrl shouldBe "/admin/flags"
        off.result() shouldBe "Switched off."
        flags.isOn(FeatureFlag.AI_EMBEDDINGS) shouldBe false
        shouldThrow<EmbeddingUnavailableException> { embeddings.embed(listOf("tea")) }.message shouldBe "switched off"
        rig.follow(session, off).contentAsString shouldContain "Switched off."

        rig.post(session, "/admin/flags/AI_EMBEDDINGS", mapOf("enabled" to "false", "justification" to WHY)).result() shouldBe
            "Already off. Nothing changed."
        rig.post(session, "/admin/flags/AI_EMBEDDINGS", mapOf("enabled" to "true", "justification" to WHY)).result() shouldBe
            "Switched on."
        flags.isOn(FeatureFlag.AI_EMBEDDINGS) shouldBe true
        rig.audited(rig.adminOf(session)).map { it.head } shouldContainExactly
            listOf(
                "FEATURE_FLAG_CHANGED|SUCCEEDED|AI_EMBEDDINGS",
                "FEATURE_FLAG_CHANGED|REFUSED|AI_EMBEDDINGS",
                "FEATURE_FLAG_CHANGED|SUCCEEDED|AI_EMBEDDINGS",
            )
    }

    @Test
    fun `an unknown flag or value is the 404 page, a refused justification 422, and no POST without CSRF`() {
        val session = rig.session("ADMIN")

        rig.post(session, "/admin/flags/NO_SUCH_FLAG", mapOf("enabled" to "false", "justification" to WHY)).response.status shouldBe 404
        rig.post(session, "/admin/flags/DAILY_REMINDERS", mapOf("enabled" to "maybe", "justification" to WHY)).response.status shouldBe 404
        val refused = rig.post(session, "/admin/flags/DAILY_REMINDERS", mapOf("enabled" to "false", "justification" to " "))
        refused.response.status shouldBe 422
        refused.response.contentAsString shouldContain "Write a justification of 1 to 400 characters saying why."
        rig
            .post(session, "/admin/flags/DAILY_REMINDERS", mapOf("enabled" to "false", "justification" to WHY), csrf = false)
            .response.status shouldBe 403

        flags.isOn(FeatureFlag.DAILY_REMINDERS) shouldBe true
        rig.audited(rig.adminOf(session)).shouldBeEmpty()
    }

    // --- the support inbox -------------------------------------------------------------------------------------------

    @Test
    fun `a SUPPORT session lists without the words, reads with them, and closes inside the window`(output: CapturedOutput) {
        val session = rig.session("SUPPORT")
        val admin = rig.adminOf(session)
        val ticket = ticket()

        val list = rig.get(session, "/admin/support").contentAsString
        list shouldContain """data-ticket="$ticket""""
        list shouldNotContain SECRET

        val page = rig.get(session, "/admin/support/$ticket").contentAsString
        page shouldContain SECRET
        page shouldContain "MOYI-42"
        page shouldContain """action="/admin/support/$ticket/close""""

        val closed = rig.post(session, "/admin/support/$ticket/close", mapOf("note" to WHY))
        closed.response.redirectedUrl shouldBe "/admin/support/$ticket"
        closed.result() shouldBe "Closed. Its words are gone."
        rig.follow(session, closed).contentAsString shouldNotContain SECRET
        rig.post(session, "/admin/support/$ticket/close", mapOf("note" to WHY)).result() shouldBe "Already closed. Nothing changed."

        rig.audited(admin).map { it.head } shouldContainExactly
            listOf(
                "SUPPORT_INBOX_VIEWED|SUCCEEDED|null",
                "SUPPORT_TICKET_READ|SUCCEEDED|$ticket",
                "SUPPORT_TICKET_CLOSED|SUCCEEDED|$ticket",
                "SUPPORT_TICKET_READ|SUCCEEDED|$ticket",
                "SUPPORT_TICKET_CLOSED|REFUSED|$ticket",
            )
        jdbc.queryForList("SELECT DISTINCT actor FROM support_ticket_log", String::class.java) shouldBe listOf("admin:$admin")
        listOf(SECRET, "MOYI-42", WHY).forEach { output.out shouldNotContain it }
    }

    @Test
    fun `a close outside the window asks for a code and closes nothing, and an unknown ticket is the 404 page`() {
        val session = rig.session("SUPPORT", secondFactorAt = clock.instant().minus(Duration.ofMinutes(6)))
        val ticket = ticket()

        val asked = rig.post(session, "/admin/support/$ticket/close", mapOf("note" to WHY))

        asked.response.status shouldBe 200
        asked.response.contentAsString shouldContain "Enter a code from your authenticator app"
        jdbc.queryForObject("SELECT status FROM support_tickets WHERE id = ?", String::class.java, ticket) shouldBe "OPEN"
        rig.get(session, "/admin/support/${UUID.randomUUID()}").status shouldBe 404
        rig.get(session, "/admin/support/not-a-uuid").status shouldBe 404
        rig.audited(rig.adminOf(session)).map { it.head.substringBefore('|') } shouldContainExactly listOf("SUPPORT_TICKET_READ")
    }

    private fun ticket(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO support_tickets (id, user_id, subject, body, reference, status, created_at) " +
                "VALUES (?, ?, 'Help', ?, 'MOYI-42', 'OPEN', now())",
            id,
            UUID.randomUUID(),
            SECRET,
        )
        return id
    }

    private fun lastClose(): java.sql.Timestamp? =
        jdbc.queryForList("SELECT last_success_at FROM job_runs WHERE name = 'close-days'", java.sql.Timestamp::class.java).firstOrNull()

    /** Released, never deleted: ShedLock only updates a row it has inserted once (`JobConsoleTest`). */
    private fun releaseCloseLock() {
        jdbc.update("UPDATE shedlock SET lock_until = $UTC_NOW - interval '1 second' WHERE name = 'close-days'")
    }

    private companion object {
        val WHY = "the support rota ${UUID.randomUUID()}"
        val SECRET = "my streak broke yesterday ${UUID.randomUUID()}"
        const val UTC_NOW = "timezone('utc', now())"
    }
}
