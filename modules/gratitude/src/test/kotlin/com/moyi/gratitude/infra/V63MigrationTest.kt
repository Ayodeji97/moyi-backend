package com.moyi.gratitude.infra

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * V63 (A5 spec §7): `entry_report_log`'s action `CHECK` gains `WORDS_READ`, and nothing else
 * changes. As `V61MigrationTest`: a database of its own in the shared container, taken to the
 * version before by Flyway, given rows, then taken to V63. No Spring context.
 */
internal class V63MigrationTest : PostgresIntegrationTest() {
    private val admin = JdbcTemplate(source("test"))
    private val target = source(DATABASE)
    private val jdbc = JdbcTemplate(target)

    @BeforeEach
    fun create() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        admin.execute("CREATE DATABASE $DATABASE")
        flyway("62").migrate()
    }

    @AfterEach
    fun drop() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
    }

    @Test
    fun `WORDS_READ is refused before V63 and inserts after it`() {
        val report = report()
        withClue("before V63") { shouldThrow<DataIntegrityViolationException> { log(report, "WORDS_READ") } }

        flyway("63").migrate()

        log(report, "WORDS_READ") shouldBe 1
    }

    @Test
    fun `the five old actions still insert, and rows written before V63 are unchanged by it`() {
        val report = report()
        OLD_ACTIONS.forEach { log(if (it == "LISTED") null else report, it) }
        val rowsBefore = rows()
        val otherConstraintsBefore = otherConstraints()

        flyway("63").migrate()

        rows() shouldBe rowsBefore
        otherConstraints() shouldBe otherConstraintsBefore
        OLD_ACTIONS.forEach { action -> withClue(action) { log(if (action == "LISTED") null else report, action) shouldBe 1 } }
    }

    @Test
    fun `an action outside the list is still refused`() {
        flyway("63").migrate()

        shouldThrow<DataIntegrityViolationException> { log(report(), "WORDS_RED") }
            .message!! shouldContain "entry_report_log_action_check"
    }

    private fun report(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entry_reports (id, target, entry_id, bond_id, reporter_member_id, reason, status, created_at) " +
                "VALUES (?, 'ENTRY', ?, ?, ?, 'HARASSMENT', 'OPEN', now())",
            id,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        return id
    }

    private fun log(
        report: UUID?,
        action: String,
    ): Int =
        jdbc.update(
            "INSERT INTO entry_report_log (id, report_id, action, actor, note, at) VALUES (?, ?, ?, 'admin:x', NULL, now())",
            UUID.randomUUID(),
            report,
            action,
        )

    private fun rows(): List<String?> =
        jdbc.queryForList("SELECT row_to_json(l)::text FROM entry_report_log l ORDER BY id", String::class.java)

    private fun otherConstraints(): List<String?> =
        jdbc.queryForList(
            "SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint " +
                "WHERE conrelid = 'entry_report_log'::regclass AND conname <> 'entry_report_log_action_check' ORDER BY conname",
            String::class.java,
        )

    private fun flyway(version: String): Flyway =
        Flyway
            .configure()
            .dataSource(target)
            .locations("classpath:db/migration")
            .target(version)
            .load()

    private fun source(database: String): DriverManagerDataSource {
        val url = postgres.jdbcUrl
        val base = url.substringBefore('?').substringBeforeLast('/')
        val options = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        return DriverManagerDataSource("$base/$database$options", postgres.username, postgres.password)
    }

    private companion object {
        const val DATABASE = "moyi_v63_report_words_read"
        val OLD_ACTIONS = listOf("LISTED", "READ", "RECLASSIFIED", "DISMISSED", "UPHELD")
    }
}
