package com.moyi.app.admin

import com.moyi.common.testing.MutableClock
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The A2 pages' rig (plan Task 6): signed-in sessions for each role, GETs and POSTs from an
 * allowed address with the CSRF token read off a rendered page, held-back media and reports
 * written as the media pipeline and the report route write them, and the audit rows read back.
 *
 * A session is built as the sign-in's code step builds it (as `UserLookupTest` does), for a
 * freshly invited account made `ACTIVE`; its `admin.secondFactorAt` is [Instant] given, so a test
 * chooses whether the step-up window is open. The step-up's own HTTP tests sign in for real.
 */
internal class ModerationRig(
    private val mockMvc: MockMvc,
    val jdbc: JdbcTemplate,
    private val clock: MutableClock,
) {
    /** A two-factor admin session for [role], its step-up proven at [secondFactorAt] (null: never). */
    fun session(
        role: String = "MODERATOR",
        secondFactorAt: Instant? = clock.instant(),
    ): MockHttpSession {
        val email = "mod-${UUID.randomUUID().toString().take(8)}@example.com"
        jdbc.queryForObject("SELECT admin_invite(?::citext, ?)", String::class.java, email, role)
        val adminId = jdbc.queryForObject("SELECT id FROM admin_accounts WHERE email = ?::citext", UUID::class.java, email)!!
        jdbc.update("UPDATE admin_accounts SET status = 'ACTIVE', password_hash = 'x' WHERE id = ?", adminId)
        val changed =
            jdbc.queryForObject("SELECT credentials_changed_at FROM admin_accounts WHERE id = ?", OffsetDateTime::class.java, adminId)!!
        val authorities: List<GrantedAuthority> =
            listOf(FactorGrantedAuthority.PASSWORD_AUTHORITY, "FACTOR_TOTP").map { FactorGrantedAuthority.fromAuthority(it) } +
                SimpleGrantedAuthority("ROLE_$role")
        return MockHttpSession().apply {
            val authentication = UsernamePasswordAuthenticationToken.authenticated(adminId, null, authorities)
            setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextImpl(authentication))
            setAttribute("admin.credentialsChangedAt", changed.toInstant())
            setAttribute("admin.absoluteDeadline", clock.instant().plus(Duration.ofHours(1)))
            if (secondFactorAt != null) setAttribute(SECOND_FACTOR_AT, secondFactorAt)
        }
    }

    fun adminOf(session: MockHttpSession): UUID =
        (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContextImpl)
            .authentication!!
            .principal as UUID

    fun get(
        session: MockHttpSession,
        path: String,
        vararg params: Pair<String, String>,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) {
                with { it.apply { remoteAddr = AdminConsoleDriver.ALLOWED } }
                this.session = session
                params.forEach { (name, value) -> param(name, value) }
            }.andReturn()
            .response

    /** The page a decision redirected to, with the flash it left, as a browser follows it. */
    fun follow(
        session: MockHttpSession,
        posted: MvcResult,
    ): MockHttpServletResponse =
        mockMvc
            .get(posted.response.redirectedUrl!!) {
                with { it.apply { remoteAddr = AdminConsoleDriver.ALLOWED } }
                this.session = session
                flashAttrs = posted.flashMap
            }.andReturn()
            .response

    /** A form's POST, with the session's CSRF token unless [csrf] is false. The flash is on the result. */
    fun post(
        session: MockHttpSession,
        path: String,
        params: Map<String, String>,
        csrf: Boolean = true,
    ): MvcResult {
        val token = if (csrf) AdminConsoleDriver.csrfFrom(get(session, "/admin")) else null
        return mockMvc
            .post(path) {
                with { it.apply { remoteAddr = AdminConsoleDriver.ALLOWED } }
                this.session = session
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                if (token != null) param("_csrf", token)
                params.forEach { (name, value) -> param(name, value) }
            }.andReturn()
    }

    // --- held-back media and reports ---------------------------------------------------------------------------------

    /** An entry's id and its photo, `READY` and `PASSED` as moderation left it. */
    fun entryWithPhoto(owner: UUID = UUID.randomUUID()): Pair<UUID, UUID> {
        val entry = UUID.randomUUID()
        return entry to asset(status = "READY", verdict = "PASSED", attachedTo = entry, owner = owner)
    }

    /** A photo held back by moderation, as the pipeline holds one; uploaded as [contentType]. */
    fun quarantined(contentType: String = "image/jpeg"): UUID =
        asset(status = "QUARANTINED", verdict = "QUARANTINED", reason = "EXTREME_VIOLENCE", contentType = contentType)

    /** A photo's storage key fragment, which names every object of it (`in/<x>`, `v/<x>/display.webp`). */
    fun keyOf(media: UUID): String =
        jdbc.queryForObject("SELECT storage_key FROM media_assets WHERE id = ?", String::class.java, media)!!.removePrefix("in/")

    @Suppress("LongParameterList") // One asset row's facts a test chooses.
    fun asset(
        status: String,
        verdict: String,
        attachedTo: UUID? = null,
        reason: String? = null,
        contentType: String = "image/jpeg",
        owner: UUID = UUID.randomUUID(),
        id: UUID = UUID.randomUUID(),
        random: UUID = UUID.randomUUID(),
    ): UUID {
        val ready = status == "READY"
        val now = Timestamp.from(clock.instant())
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, variants,
                                      width, height, attached_entry_id, tier, created_at, ready_at, expires_at,
                                      moderation_verdict, moderated_at, moderation_scores)
            VALUES (?, ?, 'IMAGE', ?, ?, 1000, 'Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=', ?, ?::jsonb,
                    ?, ?, ?, 1, ?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            id,
            owner,
            "in/$random",
            contentType,
            status,
            if (ready) {
                """[{"variant":"FULL","key":"v/$random/full.webp","width":2048,"height":1536,"bytes":180000},""" +
                    """{"variant":"DISPLAY","key":"v/$random/display.webp","width":1280,"height":960,"bytes":75000}]"""
            } else {
                "[]"
            },
            if (ready) 2048 else null,
            if (ready) 1536 else null,
            attachedTo,
            now,
            if (ready) now else null,
            Timestamp.from(clock.instant().plusSeconds(UPLOAD_URL_SECONDS)),
            verdict,
            now,
            reason?.let { """{"reason":"$it"}""" },
        )
        return id
    }

    /** A report of [entry], filed as the route files one: the row, then `media_report_hold`. [id] for a twin seeded the same. */
    fun report(
        entry: UUID,
        reason: String,
        detail: String? = null,
        id: UUID = UUID.randomUUID(),
    ): UUID {
        jdbc.update(
            "INSERT INTO entry_reports (id, target, entry_id, bond_id, reporter_member_id, reason, detail, status, created_at) " +
                "VALUES (?, 'ENTRY', ?, ?, ?, ?, ?, 'OPEN', ?)",
            id,
            entry,
            UUID.randomUUID(),
            UUID.randomUUID(),
            reason,
            detail,
            Timestamp.from(clock.instant()),
        )
        jdbc.queryForObject("SELECT media_report_hold(?, ?, 'member-report')", Int::class.java, entry, id)
        return id
    }

    fun reportStatus(id: UUID): String =
        jdbc.queryForObject("SELECT status || '|' || reason FROM entry_reports WHERE id = ?", String::class.java, id)!!

    fun assetState(id: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, '') || '|' || evidence_held FROM media_assets WHERE id = ?",
            String::class.java,
            id,
        )!!

    fun count(
        sql: String,
        vararg args: Any,
    ): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!

    /** [admin]'s audit rows, in the order written: `action|outcome|target_id`, then the metadata and justification. */
    fun audited(admin: UUID): List<AuditRow> =
        jdbc.query(
            "SELECT action, outcome, target_id, justification, metadata::text AS metadata FROM audit_log WHERE actor_id = ? ORDER BY id",
            { rs, _ ->
                AuditRow(
                    "${rs.getString("action")}|${rs.getString("outcome")}|${rs.getString("target_id")}",
                    rs.getString("metadata"),
                    rs.getString("justification"),
                )
            },
            admin,
        )

    class AuditRow(
        val head: String,
        val metadata: String,
        val justification: String?,
    ) {
        override fun toString(): String = "$head $metadata"
    }

    companion object {
        /** `AdminAuthorities.SECOND_FACTOR_AT`, which is `internal` to the admin module. */
        const val SECOND_FACTOR_AT = "admin.secondFactorAt"
        const val UPLOAD_URL_SECONDS = 15 * 60L
        val NOTE = "looked at it, and decided ${UUID.randomUUID()}"

        /** The flash's result line, or null. */
        fun MvcResult.result(): String? = flashMap["result"] as String?

        /** Every `href`, `src` and `action` on a page. */
        fun links(page: String): List<String> = Regex("""(?:href|src|action)="([^"]*)"""").findAll(page).map { it.groupValues[1] }.toList()
    }
}
