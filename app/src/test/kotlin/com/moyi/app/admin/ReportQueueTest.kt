package com.moyi.app.admin

import com.moyi.app.admin.ModerationRig.Companion.NOTE
import com.moyi.app.admin.ModerationRig.Companion.result
import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID
import javax.sql.DataSource

/**
 * The report queue through HTTP (A2 spec §5.1, §4, §8; plan Task 6): the list in the function's
 * order, a report's page with the detail, each decision end to end with its one-line result, and
 * the refusals: CSRF, the `SUPPORT` role, an unknown id, a stale page.
 */
class ReportQueueTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val rig = ModerationRig(mockMvc, JdbcTemplate(dataSource), clock)

    @Test
    fun `the list is entry_report_list's rows in its order, and a priority row says do not view in words`() {
        rig.report(rig.entryWithPhoto().first, "OTHER")
        val priority = rig.report(rig.entryWithPhoto().first, "MINOR_SEXUAL_CONTENT")
        val session = rig.session()

        val page = rig.get(session, "/admin/reports")

        page.status shouldBe 200
        val listed = Regex("""data-report="([0-9a-f-]{36})"""").findAll(page.contentAsString).map { it.groupValues[1] }.toList()
        page.contentAsString shouldContain
            "<td>${rig.jdbc.queryForObject("SELECT entry_id::text FROM entry_reports WHERE id = ?", String::class.java, priority)}</td>"
        listed shouldContainExactly rig.jdbc.queryForList("SELECT report_id::text FROM entry_report_list('test')", String::class.java)
        val row = page.contentAsString.substringAfter("""data-report="$priority"""").substringBefore("</tr>")
        row shouldContain "DO NOT VIEW — runbook §2"
        row shouldNotContain "view="
    }

    @Test
    fun `a report's page shows the reporter's detail, and a priority report's photo has no View link`() {
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "MINOR_SEXUAL_CONTENT", detail = "what the reporter saw ${UUID.randomUUID()}")
        val detail = rig.jdbc.queryForObject("SELECT detail FROM entry_reports WHERE id = ?", String::class.java, report)!!

        val page = rig.get(rig.session(), "/admin/reports/$report").contentAsString

        page shouldContain detail
        page shouldContain photo.toString()
        page shouldContain "DO NOT VIEW — runbook §2"
        page shouldNotContain "view=$photo"
        page shouldNotContain "/admin/media/"
        // The one uphold a priority report offers (spec §5.1), and no delete.
        page shouldContain "Block as evidence, then runbook §2"
        page shouldNotContain "Uphold, delete"
    }

    /**
     * The oracle is the schema's own CHECK on `entry_reports.reason`, not the port: a port whose list
     * dropped or added a name would agree with itself, and the database would still refuse or accept
     * what the form offered.
     */
    @Test
    fun `the reclassify choice is every reason the schema allows, in order, except the report's own`() {
        val allowed =
            Regex("'([A-Z_]+)'")
                .findAll(
                    rig.jdbc.queryForObject(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'entry_reports_reason_check'",
                        String::class.java,
                    )!!,
                ).map { it.groupValues[1] }
                .toList()
        val session = rig.session()

        allowed.size shouldBe 5
        allowed.forEach { reason ->
            val report = rig.report(rig.entryWithPhoto().first, reason)
            val page = rig.get(session, "/admin/reports/$report").contentAsString
            val select = page.substringAfter("""<select id="reclassify-reason"""", "").substringBefore("</select>")
            withClue(reason) {
                Regex("""<option value="([A-Z_]+)">""").findAll(select).map { it.groupValues[1] }.toList() shouldContainExactly
                    allowed - reason
            }
        }
    }

    @Test
    fun `dismiss works end to end, says Dismissed, and the decided page says the detail was emptied`() {
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "OTHER", detail = "a detail to be emptied")
        val session = rig.session()

        val posted = rig.post(session, "/admin/reports/$report/dismiss", mapOf("expectedReason" to "OTHER", "note" to NOTE))

        posted.response.status shouldBe 302
        posted.response.redirectedUrl shouldBe "/admin/reports/$report"
        posted.result() shouldBe "Dismissed."
        rig.reportStatus(report) shouldBe "DISMISSED|OTHER"
        rig.assetState(photo) shouldBe "READY|PASSED|false"
        val page = rig.follow(session, posted).contentAsString
        page shouldContain """<p class="notice" role="status">Dismissed.</p>"""
        page shouldContain "Emptied when decided."
        page shouldNotContain "a detail to be emptied"
        page shouldNotContain "/dismiss"
        rig.jdbc.queryForObject(
            "SELECT actor FROM entry_report_log WHERE report_id = ? AND action = 'DISMISSED'",
            String::class.java,
            report,
        ) shouldBe "admin:${rig.adminOf(session)}"
    }

    @Test
    fun `reclassify, uphold by delete and uphold by block each decide and say so`() {
        val session = rig.session()
        val reclassified = rig.report(rig.entryWithPhoto().first, "OTHER")
        val (deletedEntry, deletedPhoto) = rig.entryWithPhoto()
        val deleted = rig.report(deletedEntry, "VIOLENCE_OR_SELF_HARM")
        val (blockedEntry, blockedPhoto) = rig.entryWithPhoto()
        val blocked = rig.report(blockedEntry, "HARASSMENT")

        rig
            .post(
                session,
                "/admin/reports/$reclassified/reclassify",
                mapOf("expectedReason" to "OTHER", "newReason" to "HARASSMENT", "note" to NOTE),
            ).result() shouldBe "Reclassified."
        rig
            .post(
                session,
                "/admin/reports/$deleted/uphold",
                mapOf("expectedReason" to "VIOLENCE_OR_SELF_HARM", "takedown" to "DELETE", "note" to NOTE),
            ).result() shouldBe "Upheld."
        rig
            .post(
                session,
                "/admin/reports/$blocked/uphold",
                mapOf("expectedReason" to "HARASSMENT", "takedown" to "BLOCK", "note" to NOTE),
            ).result() shouldBe "Upheld: blocked as evidence. Now follow runbook §2."

        rig.reportStatus(reclassified) shouldBe "OPEN|HARASSMENT"
        rig.reportStatus(deleted) shouldBe "UPHELD|VIOLENCE_OR_SELF_HARM"
        rig.assetState(deletedPhoto) shouldBe "DELETED|QUARANTINED|false"
        rig.reportStatus(blocked) shouldBe "UPHELD|HARASSMENT"
        rig.assetState(blockedPhoto).substringAfter('|') shouldBe "BLOCKED|true"
        rig.audited(rig.adminOf(session)).map { it.head.substringBeforeLast('|') } shouldContainExactly
            listOf("REPORT_RECLASSIFIED|SUCCEEDED", "REPORT_UPHELD|SUCCEEDED", "REPORT_UPHELD|SUCCEEDED")
    }

    @Test
    fun `a decision on a page whose reason changed is refused stale, and nothing is taken down`() {
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "HARASSMENT")
        val first = rig.session()
        val second = rig.session()
        rig.post(
            first,
            "/admin/reports/$report/reclassify",
            mapOf("expectedReason" to "HARASSMENT", "newReason" to "OTHER", "note" to NOTE),
        )

        val stale =
            rig.post(
                second,
                "/admin/reports/$report/uphold",
                mapOf("expectedReason" to "HARASSMENT", "takedown" to "DELETE", "note" to NOTE),
            )

        stale.result() shouldBe "The report changed after this page was shown. Read it again before deciding."
        rig.reportStatus(report) shouldBe "OPEN|OTHER"
        rig.assetState(photo) shouldBe "QUARANTINED|QUARANTINED|false"
    }

    @Test
    fun `a note that breaks the rule decides nothing and shows the form again with the note kept`() {
        val report = rig.report(rig.entryWithPhoto().first, "OTHER")
        val long = "x".repeat(401)

        val refused = rig.post(rig.session(), "/admin/reports/$report/dismiss", mapOf("expectedReason" to "OTHER", "note" to long))

        refused.response.status shouldBe 400
        refused.response.contentAsString shouldContain "Write a note of 1 to 400 characters saying why."
        refused.response.contentAsString shouldContain long
        rig.reportStatus(report) shouldBe "OPEN|OTHER"
    }

    @Test
    fun `a POST without the CSRF token is the 403 page and decides nothing`() {
        val report = rig.report(rig.entryWithPhoto().first, "OTHER")
        val session = rig.session()

        val refused = rig.post(session, "/admin/reports/$report/dismiss", mapOf("expectedReason" to "OTHER", "note" to NOTE), csrf = false)

        refused.response.status shouldBe 403
        refused.response.contentAsString shouldContain "Error 403"
        rig.reportStatus(report) shouldBe "OPEN|OTHER"
        rig.audited(rig.adminOf(session)).map { it.head } shouldContainExactly emptyList()
    }

    @Test
    fun `an unknown id and a value that is not a UUID are the same 404 page, and neither writes a READ`() {
        val session = rig.session()
        val reads = rig.count("SELECT count(*) FROM entry_report_log WHERE action = 'READ'")

        val unknown = rig.get(session, "/admin/reports/${UUID.randomUUID()}")
        val garbage = rig.get(session, "/admin/reports/not-a-uuid")
        val noPage = rig.get(session, "/admin/no-such-page")

        unknown.status shouldBe 404
        garbage.status shouldBe 404
        unknown.contentAsString shouldBe noPage.contentAsString
        garbage.contentAsString shouldBe noPage.contentAsString
        rig.count("SELECT count(*) FROM entry_report_log WHERE action = 'READ'") shouldBe reads
    }

    @Test
    fun `SUPPORT gets the 403 page on every A2 route and nothing is decided or read`() {
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "OTHER")
        val held = rig.quarantined()
        val session = rig.session("SUPPORT")
        val form = mapOf("expectedReason" to "OTHER", "note" to NOTE, "newReason" to "HARASSMENT", "takedown" to "DELETE")

        val reads =
            listOf(
                "/admin/reports",
                "/admin/reports/$report",
                "/admin/reports/not-a-uuid",
                "/admin/quarantine",
                "/admin/quarantine/$held",
                "/admin/media/$photo/review?via=REPORT&report=$report",
                "/admin/media/$held/review?via=QUARANTINE",
            ).map { it to rig.get(session, it.substringBefore('?'), *params(it)) }
        val writes =
            listOf("dismiss", "reclassify", "uphold").map { "/admin/reports/$report/$it" } +
                listOf("release", "block", "delete").map { "/admin/quarantine/$held/$it" }
        val posted = writes.map { it to rig.post(session, it, form).response }

        (reads + posted).forEach { (path, response) ->
            withClue(path) {
                response.status shouldBe 403
                response.contentAsString shouldContain "Your role does not allow that."
            }
        }
        rig.reportStatus(report) shouldBe "OPEN|OTHER"
        rig.assetState(held) shouldBe "QUARANTINED|QUARANTINED|false"
        rig.audited(rig.adminOf(session)).map { it.head } shouldContainExactly emptyList()
        // The home page offers a supporter neither queue.
        rig.get(session, "/admin").contentAsString shouldNotContain "/admin/reports"
    }

    private fun params(path: String): Array<Pair<String, String>> =
        path
            .substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=') to it.substringAfter('=') }
            .toTypedArray()
}
