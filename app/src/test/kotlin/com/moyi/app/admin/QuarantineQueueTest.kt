package com.moyi.app.admin

import com.moyi.app.admin.ModerationRig.Companion.NOTE
import com.moyi.app.admin.ModerationRig.Companion.result
import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import javax.sql.DataSource

/**
 * The quarantine queue through HTTP (A2 spec §5.2, §6.3, §8; plan Task 6): the list in the
 * function's order, release, block (listed first, with its runbook line) and delete, and the forms
 * a page does not offer, whose forged POSTs the port refuses under the row lock.
 */
class QuarantineQueueTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val rig = ModerationRig(mockMvc, JdbcTemplate(dataSource), clock)

    @Test
    fun `the list is media_quarantine_list's rows in its order, and a blocked row says do not view in words`() {
        val blocked = rig.quarantined()
        val session = rig.session()
        rig.post(session, "/admin/quarantine/$blocked/block", mapOf("note" to NOTE)).result() shouldBe
            "Blocked as evidence. Now follow runbook §2."

        val page = rig.get(session, "/admin/quarantine")

        page.status shouldBe 200
        val listed = Regex("""data-media="([0-9a-f-]{36})"""").findAll(page.contentAsString).map { it.groupValues[1] }.toList()
        listed shouldContainExactly rig.jdbc.queryForList("SELECT media_id::text FROM media_quarantine_list('test')", String::class.java)
        page.contentAsString.substringAfter("""data-media="$blocked"""").substringBefore("</tr>") shouldContain "DO NOT VIEW — runbook §2"
    }

    @Test
    fun `block is listed first with the runbook's words, then release, then delete`() {
        val photo = rig.quarantined()

        val page = rig.get(rig.session(), "/admin/quarantine/$photo").contentAsString

        page shouldContain "If this is child sexual abuse material: block it, do not delete it, then runbook §2."
        val block = page.indexOf("""action="/admin/quarantine/$photo/block"""")
        val release = page.indexOf("""action="/admin/quarantine/$photo/release"""")
        val delete = page.indexOf("""action="/admin/quarantine/$photo/delete"""")
        withClue("each form is on the page") { listOf(block, release, delete).all { it >= 0 } shouldBe true }
        block shouldBeLessThan release
        release shouldBeLessThan delete
    }

    @Test
    fun `release, block and delete each decide end to end and say so`() {
        val released = rig.quarantined()
        val blocked = rig.quarantined()
        val deleted = rig.quarantined()
        val session = rig.session()

        val release = rig.post(session, "/admin/quarantine/$released/release", mapOf("note" to NOTE))
        release.result() shouldBe "Released."
        // Followed before the next POST, as a browser follows each redirect: the line shows on the list.
        rig.follow(session, release).contentAsString shouldContain """<p class="notice" role="status">Released.</p>"""
        val block = rig.post(session, "/admin/quarantine/$blocked/block", mapOf("note" to NOTE))
        val delete = rig.post(session, "/admin/quarantine/$deleted/delete", mapOf("note" to NOTE))

        block.result() shouldBe "Blocked as evidence. Now follow runbook §2."
        delete.result() shouldBe "Deleted."
        delete.response.redirectedUrl shouldBe "/admin/quarantine"
        rig.assetState(released) shouldBe "READY|PASSED|false"
        rig.assetState(blocked) shouldBe "QUARANTINED|BLOCKED|true"
        rig.assetState(deleted) shouldBe "DELETED|QUARANTINED|false"
        rig.audited(rig.adminOf(session)).filter { it.justification != null }.map { it.head } shouldContainExactly
            listOf("MEDIA_RELEASED|SUCCEEDED|$released", "MEDIA_BLOCKED|SUCCEEDED|$blocked", "MEDIA_DELETED|SUCCEEDED|$deleted")
    }

    @Test
    fun `a blocked asset's page offers no delete, and a forged delete is EVIDENCE_SERVER_ONLY with the asset untouched`() {
        val photo = rig.quarantined()
        val session = rig.session()
        rig.post(session, "/admin/quarantine/$photo/block", mapOf("note" to NOTE))

        val page = rig.get(session, "/admin/quarantine/$photo").contentAsString
        val forged = rig.post(session, "/admin/quarantine/$photo/delete", mapOf("note" to NOTE))

        page shouldContain "DO NOT VIEW — runbook §2"
        page shouldNotContain "/delete\""
        page shouldNotContain "/release\""
        page shouldNotContain ">View<"
        forged.result() shouldBe "Blocked evidence is deleted only on the server: runbook §2 step 4."
        rig.assetState(photo) shouldBe "QUARANTINED|BLOCKED|true"
    }

    @Test
    fun `a report-held asset's page offers no decision and links to the report, and each forged POST is REPORT_HELD`() {
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "VIOLENCE_OR_SELF_HARM")
        val session = rig.session()

        val page = rig.get(session, "/admin/quarantine/$photo").contentAsString
        val answers = listOf("release", "block", "delete").map { rig.post(session, "/admin/quarantine/$photo/$it", mapOf("note" to NOTE)) }

        page shouldContain """href="/admin/reports/$report""""
        page shouldContain "It is decided in the report"
        page shouldNotContain """method="post" action="/admin/quarantine/$photo"""
        page shouldNotContain "/admin/quarantine/$photo/"
        answers.map { it.result() } shouldContainExactly List(3) { "An open report holds this. It is decided in the report." }
        rig.assetState(photo) shouldBe "QUARANTINED|QUARANTINED|false"
        rig.reportStatus(report) shouldBe "OPEN|VIOLENCE_OR_SELF_HARM"
    }

    @Test
    fun `the home page shows both open counts and writes no LISTED row and no audit row`() {
        val session = rig.session()
        rig.quarantined()
        val listed = rig.count("SELECT count(*) FROM entry_report_log WHERE action = 'LISTED'")
        val mediaListed = rig.count("SELECT count(*) FROM media_moderation_log WHERE action = 'LISTED'")
        val reports = rig.count("SELECT count(*) FROM entry_reports WHERE status = 'OPEN'")

        val home = rig.get(session, "/admin").contentAsString

        home shouldContain """<dd id="open-reports">$reports</dd>"""
        home shouldContain """href="/admin/quarantine""""
        rig.count("SELECT count(*) FROM entry_report_log WHERE action = 'LISTED'") shouldBe listed
        rig.count("SELECT count(*) FROM media_moderation_log WHERE action = 'LISTED'") shouldBe mediaListed
        rig.audited(rig.adminOf(session)).map { it.head } shouldContainExactly emptyList()
    }

    @Test
    fun `an unknown asset and one never held back are the same 404 page`() {
        val session = rig.session()
        val ready = rig.entryWithPhoto().second
        val noPage = rig.get(session, "/admin/no-such-page").contentAsString

        listOf("/admin/quarantine/$ready", "/admin/quarantine/${java.util.UUID.randomUUID()}", "/admin/quarantine/nope").forEach {
            withClue(it) {
                val response = rig.get(session, it)
                response.status shouldBe 404
                response.contentAsString shouldBe noPage
            }
        }
    }
}
