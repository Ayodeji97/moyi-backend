package com.moyi.app.admin

import com.moyi.app.admin.ModerationRig.Companion.result
import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
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
 * marker, with a photo, reported with a unique detail; then every A2 response a moderator can
 * reach about it: each page, a look at the photo, every decision and its flash, and every
 * re-rendered form (a refused note, a step-up that needs a code, a wrong code).
 *
 * - The marker is in no response, no audit row and no log line.
 * - The detail is on the report's own page while it is open, and nowhere else.
 * - The note is the moderator's own input: it is in the textarea of the form re-rendered to them,
 *   in the decided report's own history (spec §5.1: when, who, what, note, from
 *   `entry_report_log`, where the function wrote it) and in the audit row's `justification`, and
 *   nowhere else: no other part of any response, no flash, no other audit column, no log line.
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
    @Suppress("LongMethod") // One reported entry walked through every A2 response; split, each part would rebuild it.
    fun `the entry's words appear nowhere, the detail only on the report's page, and the note only where it was typed`(
        output: CapturedOutput,
    ) {
        val marker = "MARKER-${UUID.randomUUID()}"
        val detail = "DETAIL-${UUID.randomUUID()}"
        val note = "NOTE-${UUID.randomUUID()}"
        val author = user()
        val entry = entry(author, user(), marker)
        val photo = rig.asset(status = "READY", verdict = "PASSED", attachedTo = entry, owner = author)
        val report = rig.report(entry, "VIOLENCE_OR_SELF_HARM", detail = detail)
        val dismissed = rig.report(entry, "OTHER", detail = detail)
        // Held back by moderation after the reports: attached to the same entry, held by neither.
        val (toRelease, toBlock, toDelete) =
            List(3) { rig.asset("QUARANTINED", "QUARANTINED", attachedTo = entry, reason = "EXTREME_VIOLENCE", owner = author) }
        val session = rig.session()
        val stale = rig.session(secondFactorAt = null)

        val reportPages =
            mutableListOf(
                rig.get(session, "/admin/reports/$report").contentAsString,
                rig.get(session, "/admin/reports/$report", "view" to photo.toString()).contentAsString,
            )
        val pages =
            mutableMapOf(
                "home" to rig.get(session, "/admin").contentAsString,
                "report list" to rig.get(session, "/admin/reports").contentAsString,
                "quarantine list" to rig.get(session, "/admin/quarantine").contentAsString,
                "reported photo's page" to rig.get(session, "/admin/quarantine/$photo").contentAsString,
                "held asset's page, viewing" to rig.get(session, "/admin/quarantine/$toRelease", "view" to "1").contentAsString,
            )
        rig.get(session, "/admin/media/$photo/review", "via" to "REPORT", "report" to report.toString()).status shouldBe 200

        // The forms re-rendered to the moderator: a refused note, a step-up that needs a code, a wrong code.
        val refusedNote = decide(session, "/admin/reports/$dismissed/dismiss", "OTHER", note + "x".repeat(400))
        val uphold = mapOf("expectedReason" to "VIOLENCE_OR_SELF_HARM", "takedown" to "DELETE", "note" to note)
        val codeNeeded = rig.post(stale, "/admin/reports/$report/uphold", uphold)
        // The rig's session has no authenticator enrolled, so any code is refused: no clock to race.
        val wrongCode = rig.post(stale, "/admin/reports/$report/uphold", uphold + ("code" to "000000"))
        val forms = mapOf("refused note" to (refusedNote to 400), "code needed" to (codeNeeded to 200), "wrong code" to (wrongCode to 401))

        // Every decision, its flash, and the page it redirects to with the flash shown.
        val decisions =
            linkedMapOf(
                "dismiss" to decide(session, "/admin/reports/$dismissed/dismiss", "OTHER", note),
                "reclassify" to
                    rig.post(
                        session,
                        "/admin/reports/$report/reclassify",
                        mapOf("expectedReason" to "VIOLENCE_OR_SELF_HARM", "newReason" to "HARASSMENT", "note" to note),
                    ),
            )
        reportPages += rig.follow(session, decisions.getValue("reclassify")).contentAsString
        decisions["uphold"] =
            rig.post(session, "/admin/reports/$report/uphold", uphold + ("expectedReason" to "HARASSMENT"))
        decisions["release"] = rig.post(session, "/admin/quarantine/$toRelease/release", mapOf("note" to note))
        decisions["block"] = rig.post(session, "/admin/quarantine/$toBlock/block", mapOf("note" to note))
        decisions["delete"] = rig.post(session, "/admin/quarantine/$toDelete/delete", mapOf("note" to note))
        decisions.forEach { (name, posted) ->
            withClue(name) { posted.response.status shouldBe 302 }
            if (name != "reclassify") pages["$name, its page"] = rig.follow(session, posted).contentAsString
        }

        decisions.mapValues { it.value.result() } shouldBe
            mapOf(
                "dismiss" to "Dismissed.",
                "reclassify" to "Reclassified.",
                "uphold" to "Upheld.",
                "release" to "Released.",
                "block" to "Blocked as evidence. Now follow runbook §2.",
                "delete" to "Deleted.",
            )
        reportPages.forEachIndexed { index, page ->
            withClue("report page $index") {
                page shouldContain detail
                page shouldNotContain marker
                outsideHistory(page) shouldNotContain note
            }
        }
        pages.forEach { (name, page) ->
            withClue(name) {
                page shouldNotContain marker
                page shouldNotContain detail
                outsideHistory(page) shouldNotContain note
            }
        }
        // The decided reports' pages show the note in their history, as the function logged it.
        listOf(reportPages.last(), pages.getValue("dismiss, its page"), pages.getValue("uphold, its page")).forEach {
            HISTORY.find(it).shouldNotBeNull().value shouldContain note
        }
        forms.forEach { (name, sent) ->
            val (posted, status) = sent
            val page = posted.response.contentAsString
            val textarea = Regex("""<textarea[^>]*>([^<]*)</textarea>""")
            withClue(name) {
                posted.response.status shouldBe status
                page shouldContain "Nothing has been decided yet."
                textarea.findAll(page).single().groupValues[1] shouldContain note
                page shouldNotContain marker
                page shouldNotContain detail
                page.replace(textarea, "") shouldNotContain note
            }
        }
        (decisions.values + forms.values.map { it.first }).forEach { posted ->
            posted.flashMap.values.forEach {
                it.toString() shouldNotContain marker
                it.toString() shouldNotContain detail
                it.toString() shouldNotContain note
            }
        }

        auditKeepsOnlyTheNote(marker, detail, note, rig.adminOf(session), rig.adminOf(stale))
        listOf(marker, detail, note).forEach { output.all shouldNotContain it }
    }

    /**
     * The audit rows of [admin] and [stale], in the order written: the marker and the detail in no
     * column, and the note only in `justification`, once for each of the six decisions.
     */
    private fun auditKeepsOnlyTheNote(
        marker: String,
        detail: String,
        note: String,
        admin: UUID,
        stale: UUID,
    ) {
        val audit =
            jdbc.queryForList(
                "SELECT action || '|' || outcome AS head, (to_jsonb(a) - 'justification')::text AS rest, justification " +
                    "FROM audit_log a WHERE actor_id IN (?, ?) ORDER BY id",
                admin,
                stale,
            )
        audit.map { it["head"] } shouldBe
            listOf(
                "REPORT_READ|SUCCEEDED",
                "REPORT_READ|SUCCEEDED",
                "REPORT_QUEUE_VIEWED|SUCCEEDED",
                "QUARANTINE_QUEUE_VIEWED|SUCCEEDED",
                "QUARANTINE_QUEUE_VIEWED|SUCCEEDED",
                "QUARANTINE_QUEUE_VIEWED|SUCCEEDED",
                "MEDIA_VIEWED|SUCCEEDED",
                // The refused note and the code-needed form wrote nothing; the wrong code wrote its refusal.
                "ADMIN_STEP_UP|REFUSED",
                "REPORT_DISMISSED|SUCCEEDED",
                "REPORT_RECLASSIFIED|SUCCEEDED",
                "REPORT_READ|SUCCEEDED",
                "REPORT_UPHELD|SUCCEEDED",
                "MEDIA_RELEASED|SUCCEEDED",
                "MEDIA_BLOCKED|SUCCEEDED",
                "MEDIA_DELETED|SUCCEEDED",
                "REPORT_READ|SUCCEEDED",
                "REPORT_READ|SUCCEEDED",
                "QUARANTINE_QUEUE_VIEWED|SUCCEEDED",
                "QUARANTINE_QUEUE_VIEWED|SUCCEEDED",
                "QUARANTINE_QUEUE_VIEWED|SUCCEEDED",
            )
        audit.forEach { row ->
            withClue(row["head"]) {
                row["rest"].toString() shouldNotContain marker
                row["rest"].toString() shouldNotContain detail
                row["rest"].toString() shouldNotContain note
                (row["justification"] ?: "").toString() shouldNotContain marker
                (row["justification"] ?: "").toString() shouldNotContain detail
            }
        }
        // Each of the six decisions kept the note as its reason.
        audit.count { it["justification"] == note } shouldBe 6
    }

    /** [page] without its history table: the one place a page shows a decided report's note. */
    private fun outsideHistory(page: String): String = page.replace(HISTORY, "")

    private fun decide(
        session: MockHttpSession,
        path: String,
        expectedReason: String,
        note: String,
    ) = rig.post(session, path, mapOf("expectedReason" to expectedReason, "note" to note))

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

    private companion object {
        val HISTORY = Regex("""<table id="history">.*?</table>""", RegexOption.DOT_MATCHES_ALL)
    }
}
