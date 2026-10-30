package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.ProcessedPhotos
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The operator's queue of members' reports (FR-100, ADR-0053):
 * `scripts/moyi reports list|show|dismiss|uphold`, run as the operator runs
 * it, against this test's own Postgres through `docker exec … psql`, and what
 * each command leaves behind: the report, its log, and what the two members
 * are then shown of the entry's photo.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
internal class ReportCommandsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val photos = ProcessedPhotos(jdbc)
    private val json = JsonMapper.builder().build()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        clock.set(NOW.minusSeconds(86_400))
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        bondId = pair(ada, bea)
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, media_report_holds, media_moderation_log, media_assets")
        jdbc.execute("TRUNCATE TABLE entry_report_log, entry_reports")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `list shows the open reports, priority first and marked, without the detail, and records who looked`() {
        val (entry) = revealedWithPhoto()
        val older = report(entry, "HARASSMENT", detail = DETAIL)
        clock.set(NOW.plusSeconds(60))
        val priority = report(entry, "MINOR_SEXUAL_CONTENT")

        val run = moyi("list").also { it.code shouldBe 0 }
        val lines = run.out.lines().filter { it.isNotBlank() }

        lines[0] shouldBe "reportId\treason\tentryId\theld\tdetail\treceivedAt"
        lines[1] shouldStartWith "$priority\tMINOR_SEXUAL_CONTENT\t$entry\t1\tf\t"
        lines[1] shouldEndWith "\tDO NOT VIEW — runbook §2"
        lines[2] shouldStartWith "$older\tHARASSMENT\t$entry\t1\tt\t"
        lines[2] shouldNotContain "DO NOT VIEW"
        run.out shouldNotContain DETAIL
        log(null) shouldContainExactly listOf("LISTED|op-fake-ada|")
    }

    @Test
    fun `an empty queue says so`() {
        moyi("list").out shouldContain "No report is open."
    }

    @Test
    fun `show gives the detail and what the report holds back, and records the look`() {
        val (entry, photo) = revealedWithPhoto()
        val report = report(entry, "NON_CONSENSUAL", detail = "$DETAIL\twith a tab\nand a line")

        val run = moyi("show", report.toString()).also { it.code shouldBe 0 }

        run.out shouldContain "reason:     NON_CONSENSUAL"
        run.out shouldContain "detail:     \"$DETAIL\\twith a tab\\nand a line\""
        run.out shouldContain "IMAGE\t$photo\tQUARANTINED\tQUARANTINED"
        log(report) shouldContainExactly listOf("READ|op-fake-ada|")
        moyi("show", UUID.randomUUID().toString()).code shouldBe 1
    }

    @Test
    fun `dismiss gives the photo back to the reporter, empties the detail, and records who and why`() {
        val (entry, photo) = revealedWithPhoto()
        val report = report(entry, "OTHER", detail = DETAIL)
        partnerImageStatus() shouldBe "PROCESSING"

        moyi("dismiss", report.toString(), "a family photo, nothing wrong").let {
            it.code shouldBe 0
            it.out shouldContain "Dismissed"
        }

        partnerImageStatus() shouldBe "READY"
        authorImageStatus() shouldBe "READY"
        reportRow(report) shouldBe "DISMISSED|true|null"
        log(report) shouldContainExactly listOf("DISMISSED|op-fake-ada|a family photo, nothing wrong")
        jdbc.queryForObject(
            "SELECT action FROM media_moderation_log WHERE media_id = ?::uuid ORDER BY at DESC, id DESC LIMIT 1",
            String::class.java,
            photo,
        ) shouldBe
            "RELEASED"
        moyi("dismiss", report.toString(), "again").let {
            it.code shouldBe 1
            it.err shouldContain "no open report"
        }
    }

    @Test
    fun `dismissing one of two reports of an entry gives nothing back while the other is open`() {
        val (entry) = revealedWithPhoto()
        val first = report(entry, "OTHER")
        val second = report(entry, "NON_CONSENSUAL")

        moyi("dismiss", first.toString(), "not this one").code shouldBe 0
        partnerImageStatus() shouldBe "PROCESSING"

        moyi("dismiss", second.toString(), "nor this").code shouldBe 0
        partnerImageStatus() shouldBe "READY"
    }

    @Test
    fun `uphold delete deletes the photo, and neither member is shown it again`() {
        val (entry, photo) = revealedWithPhoto()
        val report = report(entry, "NON_CONSENSUAL", detail = DETAIL)

        moyi("uphold", report.toString(), "delete", "NCII, confirmed").let {
            it.code shouldBe 0
            it.out shouldContain "$photo: deleted"
        }

        assetStatus(photo) shouldBe "DELETED"
        body(getToday(ada))["partnerEntry"]["image"].isNull shouldBe true
        body(getToday(bea))["myEntry"]["image"].isNull shouldBe true
        reportRow(report) shouldBe "UPHELD|true|null"
        log(report) shouldContainExactly listOf("UPHELD|op-fake-ada|DELETE: NCII, confirmed")
    }

    /** Q16: child sexual abuse material is blocked and kept as evidence, never deleted by an upheld report. */
    @Test
    fun `uphold block blocks the photo and holds it as evidence, and the author loses its URL`() {
        val (entry, photo) = revealedWithPhoto()
        val report = report(entry, "MINOR_SEXUAL_CONTENT")

        moyi("uphold", report.toString(), "block", "report 2026-09-15").let {
            it.code shouldBe 0
            it.out shouldContain "$photo: blocked and held as evidence"
        }

        assetStatus(photo) shouldBe "QUARANTINED"
        jdbc.queryForObject("SELECT evidence_held FROM media_assets WHERE id = ?::uuid", Boolean::class.java, photo) shouldBe true
        partnerImageStatus() shouldBe "PROCESSING"
        body(getToday(bea))["myEntry"]["image"].let {
            it["status"].asString() shouldBe "NOT_SHARED"
            it["url"].isNull shouldBe true
        }
    }

    @Test
    fun `a report of words alone is upheld with nothing to take down`() {
        val words = body(submit(bea, """{"text":"only words"}"""))["id"].asString()
        submit(ada, """{"text":"and mine"}""").status shouldBe 201
        val report = report(words, "HARASSMENT")

        moyi("uphold", report.toString(), "delete", "harassment, noted").let {
            it.code shouldBe 0
            it.out shouldContain "nothing to take down"
        }
        reportRow(report) shouldBe "UPHELD|true|null"
    }

    @Test
    fun `nothing runs without an operator's name, and a decision needs a note`() {
        val (entry) = revealedWithPhoto()
        val report = report(entry, "OTHER")

        moyi("list", operator = "").let {
            it.code shouldBe 1
            it.err shouldContain "MOYI_OPERATOR"
        }
        moyi("dismiss", report.toString(), "").code shouldBe 1
        moyi("uphold", report.toString(), "erase", "why").code shouldBe 1
        reportRow(report) shouldBe "OPEN|false|$DETAIL_ABSENT"
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    private fun revealedWithPhoto(): List<String> {
        val photo = photos.ready(owner = bea, verdict = "PASSED")
        val entry = body(submit(bea, """{"text":"look","imageMediaId":"$photo"}"""))["id"].asString()
        submit(ada, """{"text":"and mine"}""").status shouldBe 201
        return listOf(entry, photo.toString())
    }

    private fun report(
        entry: String,
        reason: String,
        detail: String? = null,
    ): UUID {
        val body = json.writeValueAsString(listOfNotNull("reason" to reason, detail?.let { "detail" to it }).toMap())
        val response =
            mockMvc
                .post("/api/v1/entries/$entry/report") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andReturn()
                .response
        response.status shouldBe 201
        return UUID.fromString(body(response)["id"].asString())
    }

    private fun partnerImageStatus(): String = body(getToday(ada))["partnerEntry"]["image"]["status"].asString()

    private fun authorImageStatus(): String = body(getToday(bea))["myEntry"]["image"]["status"].asString()

    private fun assetStatus(id: String): String =
        jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?::uuid", String::class.java, id)!!

    /** Status, whether it is resolved, and the detail (`null` once decided). */
    private fun reportRow(id: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || (resolved_at IS NOT NULL) || '|' || coalesce(detail, 'null') FROM entry_reports WHERE id = ?",
            String::class.java,
            id,
        )!!

    private fun log(report: UUID?): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor || '|' || coalesce(note, '') FROM entry_report_log " +
                    "WHERE report_id IS NOT DISTINCT FROM ?::uuid ORDER BY at, id",
                String::class.java,
                report?.toString(),
            ).filterNotNull()

    private data class Run(
        val code: Int,
        val out: String,
        val err: String,
    )

    /** `scripts/moyi reports <args>`, as the operator types it, reaching psql in this test's own Postgres. */
    private fun moyi(
        vararg args: String,
        operator: String = "op-fake-ada",
    ): Run {
        val postgres = PostgresIntegrationTest.postgres
        val process =
            ProcessBuilder(listOf("bash", SCRIPT.absolutePath, "reports") + args)
                .apply {
                    environment()["MOYI_OPERATOR"] = operator
                    environment()["MOYI_OPS_PSQL"] =
                        "docker exec -i ${postgres.containerId} psql -U ${postgres.username} -d ${postgres.databaseName}"
                }.start()
        process.outputStream.close()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "the script did not finish" }
        return Run(
            process.exitValue(),
            process.inputStream.readAllBytes().decodeToString(),
            process.errorStream.readAllBytes().decodeToString(),
        )
    }

    private fun pair(
        creator: UUID,
        joiner: UUID,
    ): String {
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(creator))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(joiner)) }
            .andReturn()
            .response.status shouldBe 200
        return Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
    }

    private fun submit(
        caller: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun getToday(caller: UUID): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId/today") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }.andReturn().response

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** The reporter's words: plainly a test's. */
        const val DETAIL = "fake-report-detail"

        /** What [reportRow] shows of a report sent with no detail. */
        const val DETAIL_ABSENT = "null"

        /** From the module's directory, where Gradle runs its tests. */
        val SCRIPT = File("../../scripts/moyi")
    }
}
