package com.moyi.app.admin

import com.moyi.app.admin.ModerationRig.Companion.NOTE
import com.moyi.common.testing.MutableClock
import com.moyi.export.api.DataExportJobs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/**
 * What a member's data export discloses of administrator access (A2 spec §9.3, §12, §14 Q1 and
 * owner ruling 6): the acts the console records, made through HTTP as a moderator makes them, and
 * the export built through the real job.
 *
 * **Not `@Transactional`, and nothing here opens a transaction around a build.** Every export is
 * built by `PrepareExports` through [DataExportJobs.prepareDue], as the scheduler calls it, with
 * none open: a build inside a test's transaction would let a `MANDATORY` read pass that fails in
 * production (the re-review's finding 1). Each must come out `READY` ([ExportArchives.build]).
 *
 * The console's half of the "off" case (`logKept` with the console off) is
 * `AdminAccessDisclosureOffTest`, which needs a context of its own.
 */
class AdminAccessDisclosureTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val jobs: DataExportJobs,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ModerationRig(mockMvc, jdbc, clock)
    private val json = JsonMapper.builder().build()

    @Test
    fun `member-request and operations lookups and a removal from a moderation quarantine are listed, for their member alone`() {
        val ada = member()
        val bea = member()
        val session = rig.session()
        lookup(session, ada, "MEMBER_REQUEST").status shouldBe 200
        clock.advance(SECOND)
        lookup(session, ada, "OPERATIONS").status shouldBe 200
        clock.advance(SECOND)
        val photo = heldBack(ada)
        rig.post(session, "/admin/quarantine/$photo/delete", mapOf("note" to NOTE)).response.status shouldBe 302
        rig.assetState(photo) shouldBe "DELETED|QUARANTINED|false"

        val adas = adminAccess(ada)
        adas["logKept"].asBoolean() shouldBe true
        adas["accesses"].toList().map { it["action"].asString() } shouldContainExactly
            listOf("ACCOUNT_LOOKED_UP", "ACCOUNT_LOOKED_UP", "MEDIA_REMOVED")
        adas["accesses"].toList().forEach { withClue(it) { it["justification"].isNull shouldBe true } }
        adminAccess(bea)["accesses"].toList().shouldBeEmpty()
        // The rows behind them say why, for the operator, and the export said none of it.
        rig.audited(rig.adminOf(session)).map { it.head } shouldContainExactly
            listOf("USER_LOOKED_UP|SUCCEEDED|$ada", "USER_LOOKED_UP|SUCCEEDED|$ada", "MEDIA_DELETED|SUCCEEDED|$photo")
        rig.audited(rig.adminOf(session)).take(2).map { purposeOf(it.metadata) } shouldContainExactly
            listOf("MEMBER_REQUEST", "OPERATIONS")
    }

    @Test
    fun `a view and a release list nothing, and exports either side of a later block of the viewed photo disclose the same bytes`() {
        val ada = member()
        val session = rig.session()
        // Something listed, so that the comparison below is of a list and not of two empty ones.
        lookup(session, ada, "MEMBER_REQUEST").status shouldBe 200
        val viewed = heldBack(ada)
        val released = heldBack(ada)
        rig.get(session, "/admin/media/$viewed/review", "via" to "QUARANTINE").status shouldBe 200
        rig.post(session, "/admin/quarantine/$released/release", mapOf("note" to NOTE)).response.status shouldBe 302
        rig.assetState(released) shouldBe "READY|PASSED|false"

        val before = ExportArchives.build(jobs, jdbc, clock, ada)
        rig.post(session, "/admin/quarantine/$viewed/block", mapOf("note" to NOTE)).response.status shouldBe 302
        rig.assetState(viewed) shouldBe "QUARANTINED|BLOCKED|true"
        val after = ExportArchives.build(jobs, jdbc, clock, ada)

        rig.audited(rig.adminOf(session)).map { it.head } shouldContainExactly
            listOf(
                "USER_LOOKED_UP|SUCCEEDED|$ada",
                "MEDIA_VIEWED|SUCCEEDED|$viewed",
                "MEDIA_RELEASED|SUCCEEDED|$released",
                "MEDIA_BLOCKED|SUCCEEDED|$viewed",
            )
        val listed = json.readTree(before)["adminAccess"]["accesses"].toList()
        listed.map { it["action"].asString() } shouldContainExactly listOf("ACCOUNT_LOOKED_UP")
        // The re-review's finding 2: nothing a later block does may change what an earlier export said.
        ExportArchives.adminAccessBytes(after) shouldBe ExportArchives.adminAccessBytes(before)
    }

    @Test
    fun `a view under a report, an uphold's takedown, and a delete after an operator's REPORTED hold list nothing`() {
        val ada = member()
        val session = rig.session()
        val (viewedEntry, viewed) = rig.entryWithPhoto(owner = ada)
        val viewedReport = rig.report(viewedEntry, "VIOLENCE_OR_SELF_HARM")
        rig.get(session, "/admin/media/$viewed/review", "via" to "REPORT", "report" to viewedReport.toString()).status shouldBe 200
        val (upheldEntry, upheld) = rig.entryWithPhoto(owner = ada)
        val upheldReport = rig.report(upheldEntry, "VIOLENCE_OR_SELF_HARM")
        val uphold = mapOf("expectedReason" to "VIOLENCE_OR_SELF_HARM", "takedown" to "DELETE", "note" to NOTE)
        rig.post(session, "/admin/reports/$upheldReport/uphold", uphold).response.status shouldBe 302
        rig.assetState(upheld) shouldBe "DELETED|QUARANTINED|false"
        val emailed = rig.entryWithPhoto(owner = ada).second
        jdbc.queryForObject("SELECT media_quarantine_hold(?, 'op', 'a report that came by email')", String::class.java, emailed)
        rig.post(session, "/admin/quarantine/$emailed/delete", mapOf("note" to NOTE)).response.status shouldBe 302

        val rows = rig.audited(rig.adminOf(session))
        rows.map { it.head } shouldContainExactly
            listOf("MEDIA_VIEWED|SUCCEEDED|$viewed", "REPORT_UPHELD|SUCCEEDED|$upheldReport", "MEDIA_DELETED|SUCCEEDED|$emailed")
        rows.first().metadata shouldContain """"reportDriven": true"""
        rows.last().metadata shouldContain """"reportDriven": true"""
        adminAccess(ada)["accesses"].toList().shouldBeEmpty()
    }

    @Test
    fun `a lookup for SAFETY or LEGAL needs a note, keeps it only as the row's justification, and is never listed`() {
        val ada = member()
        val session = rig.session()
        val admin = rig.adminOf(session)
        listOf("SAFETY", "LEGAL").forEach { purpose ->
            withClue(purpose) {
                listOf("", "   ", "x".repeat(401)).forEach { note ->
                    val refused = rig.post(session, "/admin/users", mapOf("q" to ada.toString(), "purpose" to purpose, "note" to note))
                    refused.response.status shouldBe 400
                    refused.response.contentAsString shouldContain "needs a note"
                    refused.response.contentAsString shouldNotContain "Ada Test"
                }
                rig.audited(admin).filter { it.head.startsWith("USER_LOOKED_UP") }.shouldBeEmpty()
            }
        }
        val notes = mapOf("SAFETY" to "report ${UUID.randomUUID()} names this account", "LEGAL" to "request ${UUID.randomUUID()}")
        notes.forEach { (purpose, note) ->
            val page = rig.post(session, "/admin/users", mapOf("q" to ada.toString(), "purpose" to purpose, "note" to note)).response
            page.status shouldBe 200
            page.contentAsString shouldContain "Ada Test"
        }

        val rows = rig.audited(admin)
        rows.map { it.head } shouldContainExactly listOf("USER_LOOKED_UP|SUCCEEDED|$ada", "USER_LOOKED_UP|SUCCEEDED|$ada")
        rows.map { it.justification } shouldContainExactly notes.values.toList()
        rows.map { purposeOf(it.metadata) } shouldContainExactly notes.keys.toList()
        rows.forEach { row -> notes.values.forEach { row.metadata shouldNotContain it } }
        val document = ExportArchives.build(jobs, jdbc, clock, ada)
        json.readTree(document)["adminAccess"]["accesses"].toList().shouldBeEmpty()
        notes.values.forEach { String(document) shouldNotContain it }
    }

    @Test
    fun `the statement is the one sentence, byte for byte, for a member with access withheld and for one with none`() {
        val withheld = member()
        lookup(rig.session(), withheld, "SAFETY", note = "a report names this account").status shouldBe 200
        val untouched = member()

        val one = adminAccess(withheld)
        val other = adminAccess(untouched)

        one["accesses"].toList().shouldBeEmpty()
        one["statement"].asString().toByteArray() shouldBe other["statement"].asString().toByteArray()
        one["statement"].asString() shouldBe
            "Administrator access to your account or to what you wrote is recorded. This lists it, oldest first, " +
            "except access the law or a safety review requires us not to disclose."
    }

    /**
     * Rows the console never writes, seeded as SQL can write them, each beside a positive control: a purpose that
     * is not one, a `reportDriven` that is not a JSON boolean, a lookup row whose target is not a `USER`, a
     * removal written against Ada's account and a lookup written against her photo, and a removal of someone
     * else's photo. None is listed for Ada, and her export is still built (`READY`).
     */
    @Test
    fun `a row whose metadata is malformed or whose target is not the member's is withheld, and the export is still built`() {
        val ada = member()
        val bea = member()
        val adas = heldBack(ada)
        val adasOther = heldBack(ada)
        val beas = heldBack(bea)
        seed("USER_LOOKED_UP", "USER", ada, """{"purpose": "BOGUS"}""")
        seed("USER_LOOKED_UP", "USER", ada, """{"purpose": 7}""")
        seed("MEDIA_DELETED", "MEDIA", adas, """{"result": "DELETED", "reportDriven": "yes"}""")
        seed("MEDIA_DELETED", "MEDIA", adas, """{"result": "DELETED", "reportDriven": "false"}""")
        seed("USER_LOOKED_UP", "ADMIN_ACCOUNT", ada, """{"purpose": "MEMBER_REQUEST"}""")
        seed("USER_LOOKED_UP", "REPORT", ada, """{"purpose": "OPERATIONS"}""")
        seed("MEDIA_DELETED", "MEDIA", beas, """{"result": "DELETED", "reportDriven": false}""")
        // Read by the query (a USER row of Ada's, a MEDIA row of hers), but each action on the other's target.
        seed("MEDIA_DELETED", "USER", ada, """{"result": "DELETED", "reportDriven": false}""")
        seed("USER_LOOKED_UP", "MEDIA", adas, """{"purpose": "MEMBER_REQUEST"}""")
        // The controls: a well-formed removal of Ada's own photo is listed for her, and Bea's for Bea.
        seed("MEDIA_DELETED", "MEDIA", adasOther, """{"result": "DELETED", "reportDriven": false}""")

        adminAccess(ada)["accesses"].toList().map { it["action"].asString() } shouldContainExactly listOf("MEDIA_REMOVED")
        adminAccess(bea)["accesses"].toList().map { it["action"].asString() } shouldContainExactly listOf("MEDIA_REMOVED")
    }

    // ---- helpers ----

    private fun member(): UUID = ExportArchives.register(mockMvc, jdbc)

    private fun adminAccess(member: UUID): JsonNode = json.readTree(ExportArchives.build(jobs, jdbc, clock, member))["adminAccess"]

    /** A photo of [owner]'s held back by moderation, as the pipeline holds one. */
    private fun heldBack(owner: UUID): UUID =
        rig.asset(status = "QUARANTINED", verdict = "QUARANTINED", reason = "EXTREME_VIOLENCE", owner = owner)

    private fun lookup(
        session: MockHttpSession,
        member: UUID,
        purpose: String,
        note: String? = null,
    ) = rig
        .post(session, "/admin/users", mapOf("q" to member.toString(), "purpose" to purpose) + listOfNotNull(note?.let { "note" to it }))
        .response

    /** An audit row as an operator's SQL could write one, a second after the clock, so that each is ordered. */
    private fun seed(
        action: String,
        targetType: String,
        target: UUID,
        metadata: String,
    ) {
        clock.advance(SECOND)
        jdbc.update(
            "INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, target_type, target_id, outcome, metadata) " +
                "VALUES (uuidv7(), ?, 'ADMIN', ?, ?, ?, ?, 'SUCCEEDED', ?::jsonb)",
            Timestamp.from(clock.instant()),
            UUID.randomUUID(),
            action,
            targetType,
            target.toString(),
            metadata,
        )
    }

    private fun purposeOf(metadata: String): String? = json.readTree(metadata)["purpose"]?.asString()

    private companion object {
        val SECOND: java.time.Duration = java.time.Duration.ofSeconds(1)
    }
}
