package com.moyi.app.admin

import com.moyi.app.admin.ModerationRig.Companion.NOTE
import com.moyi.common.testing.MutableClock
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID
import javax.sql.DataSource

/**
 * **Console ≡ script** (A2 spec §1, §2's anti-goal "a moderator's decision differs in any effect
 * from the same decision made with `scripts/moyi`", §12's cross-check; plan Review Focus 5).
 *
 * Two databases, seeded identically, ids included: the application's, and a twin made for this
 * class on the same Postgres and migrated by Flyway from the same migrations. Each decision is
 * made once through the console, over HTTP, as a moderator makes it, and once on the twin by the
 * very statement `scripts/moyi` sends through `ops_sql` (cited at each call, with psql's
 * `:'name'` variables as JDBC parameters). Then every effect is compared: the assets (status,
 * verdict, scores, the evidence hold, whether deleted, the entry attached to, whether moderated and
 * whether that was restamped), the report holds, the reports, the two modules' own log rows by
 * action and note, and the outbox events by type, each with its NULLs kept in place. Excepted: who acted (the actor
 * strings, which differ by design) and the ids and times the functions make for themselves.
 */
class ConsoleMatchesScriptTest(
    @Autowired mockMvc: MockMvc,
    @Autowired clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val console = ModerationRig(mockMvc, JdbcTemplate(dataSource), clock)
    private val script = ModerationRig(mockMvc, twin, clock)

    @Test
    fun `a dismiss`() {
        val case = reported("VIOLENCE_OR_SELF_HARM")
        decided(case) {
            post("/admin/reports/${case.report}/dismiss", "expectedReason" to "VIOLENCE_OR_SELF_HARM")
            // scripts/moyi:602, `moyi reports dismiss`
            twinSays("SELECT entry_report_dismiss(?::uuid, ?, ?)", case.report, ACTOR, NOTE) shouldBe "DISMISSED"
        }
    }

    @Test
    fun `a reclassify`() {
        val case = reported("VIOLENCE_OR_SELF_HARM")
        decided(case) {
            post("/admin/reports/${case.report}/reclassify", "expectedReason" to "VIOLENCE_OR_SELF_HARM", "newReason" to "HARASSMENT")
            // scripts/moyi:616, `moyi reports reclassify`
            twinSays("SELECT entry_report_reclassify(?::uuid, ?, ?, ?)", case.report, "HARASSMENT", ACTOR, NOTE) shouldBe "RECLASSIFIED"
        }
    }

    @Test
    fun `an uphold by delete`() {
        val case = reported("VIOLENCE_OR_SELF_HARM")
        decided(case) {
            post("/admin/reports/${case.report}/uphold", "expectedReason" to "VIOLENCE_OR_SELF_HARM", "takedown" to "DELETE")
            // scripts/moyi:630, `moyi reports uphold <id> delete`
            twinUpholds(case.report, "DELETE") shouldBe listOf("${case.media}|DELETED")
        }
    }

    @Test
    fun `an uphold by block`() {
        val case = reported("MINOR_SEXUAL_CONTENT")
        decided(case) {
            post("/admin/reports/${case.report}/uphold", "expectedReason" to "MINOR_SEXUAL_CONTENT", "takedown" to "BLOCK")
            // scripts/moyi:630, `moyi reports uphold <id> block`
            twinUpholds(case.report, "BLOCK") shouldBe listOf("${case.media}|BLOCKED")
        }
    }

    @Test
    fun `a release`() {
        val case = heldBack()
        decided(case) {
            post("/admin/quarantine/${case.media}/release")
            // scripts/moyi:764, `moyi media quarantine release`
            twinSays("SELECT media_quarantine_release(?::uuid, ?, ?)", case.media, ACTOR, NOTE) shouldBe "RELEASED"
        }
    }

    @Test
    fun `a block`() {
        val case = heldBack()
        decided(case) {
            post("/admin/quarantine/${case.media}/block")
            // scripts/moyi:749, `moyi media quarantine block`
            twinSays("SELECT media_quarantine_block(?::uuid, ?, ?)", case.media, ACTOR, NOTE) shouldBe "BLOCKED"
        }
    }

    @Test
    fun `a delete`() {
        val case = heldBack()
        decided(case) {
            post("/admin/quarantine/${case.media}/delete")
            // scripts/moyi:764, `moyi media quarantine delete`
            twinSays("SELECT media_quarantine_delete(?::uuid, ?, ?)", case.media, ACTOR, NOTE) shouldBe "DELETED"
        }
    }

    // ---- the two decisions, and the comparison ----

    /** What one case seeded, in both databases under the same ids. */
    private class Case(
        val media: UUID,
        val report: UUID? = null,
        val entry: UUID? = null,
    )

    /** A photo attached to an entry, `READY` and `PASSED`, and a member's report of the entry for [reason], holding it. */
    private fun reported(reason: String): Case {
        val case = Case(media = UUID.randomUUID(), report = UUID.randomUUID(), entry = UUID.randomUUID())
        val key = UUID.randomUUID()
        val owner = UUID.randomUUID()
        listOf(console, script).forEach { rig ->
            rig.asset(status = "READY", verdict = "PASSED", attachedTo = case.entry, owner = owner, id = case.media, random = key)
            rig.report(case.entry!!, reason, detail = "what the reporter wrote", id = case.report!!)
        }
        return case
    }

    /** A photo moderation held back, as the pipeline holds one. */
    private fun heldBack(): Case {
        val case = Case(media = UUID.randomUUID())
        val key = UUID.randomUUID()
        val owner = UUID.randomUUID()
        listOf(console, script).forEach { rig ->
            rig.asset(
                status = "QUARANTINED",
                verdict = "QUARANTINED",
                reason = "EXTREME_VIOLENCE",
                owner = owner,
                id = case.media,
                random = key,
            )
        }
        return case
    }

    private inner class Decision {
        private val session = console.session()

        /** The console's form, from a moderator whose second factor is fresh: it must decide (a redirect), not ask again. */
        fun post(
            path: String,
            vararg fields: Pair<String, String>,
        ) {
            val posted = console.post(session, path, mapOf("note" to NOTE) + fields)
            withClue(path) { posted.response.status shouldBe 302 }
        }

        fun twinSays(
            sql: String,
            vararg args: Any?,
        ): String? = twin.queryForObject(sql, String::class.java, *args)

        fun twinUpholds(
            report: UUID?,
            takedown: String,
        ): List<String> =
            twin.query(
                "SELECT media_id, outcome FROM entry_report_uphold(?::uuid, ?, ?, ?)",
                { rs, _ -> "${rs.getString("media_id")}|${rs.getString("outcome")}" },
                report,
                takedown,
                ACTOR,
                NOTE,
            )
    }

    /** Runs [decide] (the console's half, then the script's), and holds every effect of the two to each other. */
    private fun decided(
        case: Case,
        decide: Decision.() -> Unit,
    ) {
        val before = effects(console.jdbc, case)
        before shouldBe effects(twin, case)
        Decision().decide()
        val after = effects(console.jdbc, case)
        withClue("the decision changed something, so the comparison is of a decision") { (after == before) shouldBe false }
        after shouldBe effects(twin, case)
    }

    /**
     * Everything a decision can change about [case], with who acted and the functions' own ids and times left out.
     * Each row is one string of every column it compares, in a fixed place, **a NULL written as [NULL]**: `format`
     * with `coalesce`, never `concat_ws`, which drops a NULL and moves every column after it up one place.
     *
     * `moderated_at` is compared as what it is, not when: null, still the instant the case was seeded with
     * (`created_at`, which the rig sets to the same instant), or stamped since. The two databases decide at
     * different instants, so a stamp's value differs, but whether a decision stamped it must not.
     *
     * The two modules' logs have no outcome column: a row is its action and its note, which says what was
     * decided (`to moderation: ` before it for a remoderation, the reason for a verdict).
     */
    private data class Effects(
        val asset: String,
        val holds: List<String?>,
        val report: String?,
        val reportLog: List<String?>,
        val mediaLog: List<String?>,
        val events: List<String?>,
    )

    private fun effects(
        jdbc: JdbcTemplate,
        case: Case,
    ): Effects =
        Effects(
            asset =
                jdbc.queryForObject(
                    "SELECT format('%s|%s|%s|%s|%s|%s|%s', ${kept("status")}, ${kept("moderation_verdict")}, " +
                        "${kept("moderation_scores::text")}, ${kept("evidence_held::text")}, deleted_at IS NULL, " +
                        "${kept("attached_entry_id::text")}, " +
                        "CASE WHEN moderated_at IS NULL THEN '$NULL' WHEN moderated_at = created_at THEN 'seeded' ELSE 'stamped' END) " +
                        "FROM media_assets WHERE id = ?",
                    String::class.java,
                    case.media,
                )!!,
            holds =
                jdbc.queryForList(
                    "SELECT format('%s|%s', media_id, ${kept("report_id::text")}) FROM media_report_holds " +
                        "WHERE media_id = ? ORDER BY report_id",
                    String::class.java,
                    case.media,
                ),
            report =
                case.report?.let {
                    jdbc.queryForObject(
                        "SELECT format('%s|%s|%s|%s', ${kept("status")}, ${kept("reason")}, detail IS NULL, resolved_at IS NULL) " +
                            "FROM entry_reports WHERE id = ?",
                        String::class.java,
                        it,
                    )
                },
            reportLog =
                case.report
                    ?.let {
                        jdbc.queryForList(
                            "SELECT format('%s|%s', action, ${kept("note")}) FROM entry_report_log WHERE report_id = ? ORDER BY at, id",
                            String::class.java,
                            it,
                        )
                    }.orEmpty(),
            mediaLog =
                jdbc.queryForList(
                    "SELECT format('%s|%s', action, ${kept("note")}) FROM media_moderation_log WHERE media_id = ? ORDER BY at, id",
                    String::class.java,
                    case.media,
                ),
            events =
                jdbc.queryForList(
                    "SELECT format('%s|%s|%s', ${kept("aggregate_type")}, aggregate_id, ${kept("event_type")}) FROM outbox_events " +
                        "WHERE aggregate_id IN (?::uuid, ?::uuid, ?::uuid) ORDER BY 1",
                    String::class.java,
                    case.media,
                    case.report,
                    case.entry,
                ),
        )

    /** [column], or [NULL] in its place when it holds none. */
    private fun kept(column: String) = "coalesce($column, '$NULL')"

    companion object {
        /** `$OPERATOR`, as `scripts/moyi` passes it: a typed name, where the console passes `admin:<id>`. */
        private const val ACTOR = "daniel"

        /** A NULL column, in [effects]' strings: a sign no column holds. */
        private const val NULL = "∅"

        private val name = "moyi_twin_${UUID.randomUUID().toString().replace("-", "")}"

        private val url: String by lazy {
            val postgres = PostgresIntegrationTest.postgres
            "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(POSTGRES_PORT)}/$name"
        }

        /** The twin: a database of its own on the shared container, built by the same migrations the application runs. */
        private val twin: JdbcTemplate by lazy {
            val postgres = PostgresIntegrationTest.postgres
            JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
                .execute("CREATE DATABASE $name")
            Flyway
                .configure()
                .dataSource(url, postgres.username, postgres.password)
                .load()
                .migrate()
            JdbcTemplate(DriverManagerDataSource(url, postgres.username, postgres.password))
        }

        @AfterAll
        @JvmStatic
        fun dropTwin() {
            val postgres = PostgresIntegrationTest.postgres
            JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
                .execute("DROP DATABASE IF EXISTS $name WITH (FORCE)")
        }

        private const val POSTGRES_PORT = 5432
    }
}
