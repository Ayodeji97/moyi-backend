package com.moyi.app.admin

import com.moyi.app.admin.ModerationRig.Companion.result
import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random

/**
 * What a moderator never sees, and what never leaves the one page that shows it (A2 spec §2's
 * anti-goals, §7.1, §7.2; plan Task 6, Review Focus 1). A real entry whose words are a unique
 * marker, with a photo, reported with a unique detail; then every A2 page a moderator can reach
 * about it, a look at the photo, and a decision. The marker is on no page, in no audit row and in
 * no log line; the detail is on the report's page and nowhere else, the flash included.
 */
@ExtendWith(OutputCaptureExtension::class)
class ModerationPrivacyTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ModerationRig(mockMvc, jdbc, clock)

    @Test
    fun `the entry's words appear nowhere, and the reporter's detail only on the report's page`(output: CapturedOutput) {
        val marker = "MARKER-${UUID.randomUUID()}"
        val detail = "DETAIL-${UUID.randomUUID()}"
        val author = user()
        val entry = entry(author, user(), marker)
        val photo = rig.asset(status = "READY", verdict = "PASSED", attachedTo = entry, owner = author)
        val report = rig.report(entry, "VIOLENCE_OR_SELF_HARM", detail = detail)
        val session = rig.session()

        val reportPages =
            listOf(
                rig.get(session, "/admin/reports/$report").contentAsString,
                rig.get(session, "/admin/reports/$report", "view" to photo.toString()).contentAsString,
            )
        val otherPages =
            listOf(
                rig.get(session, "/admin").contentAsString,
                rig.get(session, "/admin/reports").contentAsString,
                rig.get(session, "/admin/quarantine").contentAsString,
                rig.get(session, "/admin/quarantine/$photo").contentAsString,
            )
        rig.get(session, "/admin/media/$photo/review", "via" to "REPORT", "report" to report.toString()).status shouldBe 200
        val refusedNote = dismiss(session, report, note = "").response.contentAsString
        val dismissed = dismiss(session, report, note = ModerationRig.NOTE)
        val decidedPage = rig.get(session, "/admin/reports/$report").contentAsString

        reportPages.forEach { it shouldContain detail }
        dismissed.result() shouldBe "Dismissed."
        (reportPages + otherPages + refusedNote + decidedPage).forEachIndexed { index, page ->
            withClue("page $index, the marker") { page shouldNotContain marker }
        }
        (otherPages + refusedNote + decidedPage).forEachIndexed { index, page ->
            withClue("page $index, the detail") { page shouldNotContain detail }
        }
        dismissed.flashMap.values.forEach { it.toString() shouldNotContain detail }
        val audit =
            jdbc.queryForList(
                "SELECT row_to_json(a)::text FROM audit_log a WHERE actor_id = ?",
                String::class.java,
                rig.adminOf(session),
            )
        // Five page reads (the home page writes none), a look, a dismiss and the decided page; the refused note wrote nothing.
        audit.size shouldBe 8
        audit.forEach {
            it shouldNotContain marker
            it shouldNotContain detail
        }
        listOf(marker, detail, ModerationRig.NOTE).forEach { output.all shouldNotContain it }
    }

    private fun dismiss(
        session: MockHttpSession,
        report: UUID,
        note: String,
    ) = rig.post(session, "/admin/reports/$report/dismiss", mapOf("expectedReason" to "VIOLENCE_OR_SELF_HARM", "note" to note))

    private fun user(): UUID {
        val email = "privacy-${UUID.randomUUID().toString().take(8)}@example.com"
        mockMvc
            .post("/api/v1/auth/register") {
                with { it.apply { remoteAddr = "198.51.100.${Random.nextInt(1, 255)}" } }
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"email":"$email","password":"eleven~amber~lanterns~at~dusk","displayName":"Ada Test","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}"""
            }.andReturn()
            .response.status shouldBe 201
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?::citext", UUID::class.java, email)!!
    }

    /** A bond of [author] and [partner], today's day in it, and [author]'s entry of [text]: its id. */
    private fun entry(
        author: UUID,
        partner: UUID,
        text: String,
    ): UUID {
        val bond = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO bonds (id, name, anchor_timezone, status, created_by, created_at) " +
                "VALUES (?, 'Pair', 'Africa/Lagos', 'ACTIVE', ?, now())",
            bond,
            author,
        )
        val member = UUID.randomUUID()
        listOf(author to member, partner to UUID.randomUUID()).forEachIndexed { index, (user, id) ->
            jdbc.update(
                "INSERT INTO bond_members (id, bond_id, user_id, role, joined_at, reminder_timezone) " +
                    "VALUES (?, ?, ?, ?, now(), 'Africa/Lagos')",
                id,
                bond,
                user,
                if (index == 0) "OWNER" else "MEMBER",
            )
        }
        val day = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, created_at) " +
                "VALUES (?, ?, current_date, 'OPEN', 'Africa/Lagos', now(), now() + interval '1 day', now())",
            day,
            bond,
        )
        val entry = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'SUBMITTED', now(), now(), now())",
            entry,
            day,
            bond,
            member,
            text,
        )
        return entry
    }
}
