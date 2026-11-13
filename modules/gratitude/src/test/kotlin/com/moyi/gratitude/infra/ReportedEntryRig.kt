package com.moyi.gratitude.infra

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.util.UUID

/**
 * What `ReportedEntryTextTest` and `ReportedEntryTextRaceTest` share: a real bond of Ada and
 * Bea, entries submitted through the real route, and reports filed through the real routes
 * (`ReportEntry`, `ReportMember`), never inserted rows.
 */
internal class ReportedEntryRig(
    private val mockMvc: MockMvc,
    private val tokens: AccessTokenIssuer,
    val jdbc: JdbcTemplate,
) {
    private val photos = ProcessedPhotos(jdbc)

    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, media_report_holds, media_moderation_log, media_assets")
        jdbc.execute("TRUNCATE TABLE entry_report_log, entry_reports")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_entry_withdrawals, " +
                "bond_invites, bond_members, bonds CASCADE",
        )
    }

    /** A bond [creator] made and [joiner] accepted, and its id. */
    fun pair(
        creator: UUID,
        joiner: UUID,
    ): UUID {
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(creator))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
                .also { it.status shouldBe 201 }
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(joiner)) }
            .andReturn()
            .response.status shouldBe 200
        return UUID.fromString(idOf(created))
    }

    /** [author]'s entry with [body], then [partner]'s, so that the day reveals: the author's entry's id. */
    fun revealed(
        bond: UUID,
        author: UUID,
        partner: UUID,
        body: String,
    ): UUID {
        val entry = UUID.fromString(idOf(submit(bond, author, body)))
        submit(bond, partner, """{"text":"and mine"}""")
        return entry
    }

    /** A photo that has passed moderation, owned by [owner], ready to attach. */
    fun photo(owner: UUID): UUID = photos.ready(owner = owner, verdict = "PASSED")

    fun submit(
        bond: UUID,
        caller: UUID,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    /** [caller]'s report of [entry] for [reason], through `POST /entries/{id}/report`: its id. */
    fun reportEntry(
        caller: UUID,
        entry: UUID,
        reason: String,
    ): UUID =
        reported(
            mockMvc
                .post("/api/v1/entries/$entry/report") {
                    header(HttpHeaders.AUTHORIZATION, bearer(caller))
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"$reason","detail":"$DETAIL"}"""
                }.andReturn()
                .response,
        )

    /** [caller]'s report of their partner in [bond] for [reason], through the member route: its id. */
    fun reportMember(
        caller: UUID,
        bond: UUID,
        reason: String,
    ): UUID =
        reported(
            mockMvc
                .post("/api/v1/bonds/$bond/members/other/report") {
                    header(HttpHeaders.AUTHORIZATION, bearer(caller))
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"$reason"}"""
                }.andReturn()
                .response,
        )

    fun delete(
        caller: UUID,
        entry: UUID,
    ) {
        mockMvc
            .delete("/api/v1/entries/$entry") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }
            .andReturn()
            .response.status shouldBe 204
    }

    /** [caller] blocks their partner and takes back their entries: `bond` records the withdrawal; nothing is erased yet. */
    fun blockAndWithdraw(
        caller: UUID,
        bond: UUID,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bond/block") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                contentType = MediaType.APPLICATION_JSON
                content = """{"withdrawEntries":true}"""
            }.andReturn()
            .response.status shouldBe 204
    }

    fun dateOf(entry: UUID): LocalDate =
        jdbc.queryForObject(
            "SELECT d.date FROM entries e JOIN bond_days d ON d.id = e.bond_day_id WHERE e.id = ?",
            LocalDate::class.java,
            entry,
        )!!

    /** `entry_report_log` for [report], as `action|actor|note`. */
    fun log(report: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor || '|' || coalesce(note, 'null') FROM entry_report_log WHERE report_id = ? ORDER BY at, id",
                String::class.java,
                report,
            ).filterNotNull()

    private fun reported(response: MockHttpServletResponse): UUID {
        response.status shouldBe 201
        return UUID.fromString(idOf(response))
    }

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    private fun idOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    companion object {
        const val DETAIL = "what the reporter wrote"
    }
}
