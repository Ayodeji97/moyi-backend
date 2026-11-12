package com.moyi.admin.infra

import com.moyi.admin.domain.AuditAction
import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
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
 * V61 against a database that already holds A1's audit rows (spec §9.1): it widens the
 * action `CHECK` to the seventeen actions and changes nothing else.
 *
 * As `app`'s `V43BackfillTest`: a database of its own in the shared container, taken to V60 by
 * Flyway, given rows, then taken to V61. No Spring context: what is tested is the migration as
 * Flyway runs it in production, against rows written before it.
 */
internal class V61MigrationTest : PostgresIntegrationTest() {
    private val admin = JdbcTemplate(source("test"))
    private val target = source(DATABASE)
    private val jdbc = JdbcTemplate(target)

    @BeforeEach
    fun create() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        admin.execute("CREATE DATABASE $DATABASE")
        flyway("60").migrate()
    }

    @AfterEach
    fun drop() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
    }

    @Test
    fun `an A1 row written before V61 is unchanged by it, and nothing but the check changes`() {
        // One row from the operator's function, one written as A1's Kotlin writes a lookup.
        jdbc.queryForObject("SELECT admin_invite(CAST(? AS citext), 'SUPPORT')", String::class.java, "op-${UUID.randomUUID()}@example.com")
        jdbc.update(
            "INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, target_type, target_id, outcome, metadata, ip_hash) " +
                "VALUES (?, now(), 'ADMIN', ?, 'USER_LOOKED_UP', 'USER', ?, 'SUCCEEDED', '{\"found\": true}'::jsonb, '\\x0102'::bytea)",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
        )
        val rowsBefore = rows()
        val columnsBefore = columns()
        val otherConstraintsBefore = otherConstraints()
        val triggersBefore = triggers()
        rowsBefore shouldHaveSize 2

        flyway("61").migrate()

        rows() shouldBe rowsBefore
        columns() shouldBe columnsBefore
        otherConstraints() shouldBe otherConstraintsBefore
        triggers() shouldBe triggersBefore
    }

    @Test
    fun `each new action is refused before V61 and inserts after it`() {
        val added = AuditAction.entries.map { it.name } - A1_ACTIONS
        added shouldHaveSize 11

        added.forEach { action ->
            withClue("$action before V61") { shouldThrow<DataIntegrityViolationException> { insert(action) } }
        }

        flyway("61").migrate()

        (A1_ACTIONS + added).forEach { action -> withClue(action) { insert(action) shouldBe 1 } }
        AuditAction.entries shouldHaveSize 17
    }

    @Test
    fun `an action outside the list is still refused`() {
        flyway("61").migrate()

        shouldThrow<DataIntegrityViolationException> { insert("NOPE") }
            .message!! shouldContain "audit_log_action_check"
    }

    @Test
    fun `audit_log is still append-only after V61`() {
        flyway("61").migrate()
        insert(AuditAction.MEDIA_VIEWED.name)

        shouldThrow<Exception> { jdbc.update("UPDATE audit_log SET outcome = 'FAILED'") }
            .message!! shouldContain "audit_log is append-only"
        shouldThrow<Exception> { jdbc.update("DELETE FROM audit_log") }
            .message!! shouldContain "audit_log is append-only"
    }

    private fun insert(action: String): Int =
        jdbc.update(
            "INSERT INTO audit_log (id, actor_type, action, outcome) VALUES (?, 'ADMIN', ?, 'SUCCEEDED')",
            UUID.randomUUID(),
            action,
        )

    /** Every row, every column, as Postgres prints it: two snapshots compare byte for byte. */
    private fun rows(): List<String?> = jdbc.queryForList("SELECT row_to_json(a)::text FROM audit_log a ORDER BY id", String::class.java)

    private fun columns(): List<String?> =
        jdbc.queryForList(
            "SELECT column_name || ' ' || data_type || ' ' || is_nullable || ' ' || coalesce(column_default, '') " +
                "FROM information_schema.columns WHERE table_name = 'audit_log' ORDER BY ordinal_position",
            String::class.java,
        )

    private fun otherConstraints(): List<String?> =
        jdbc.queryForList(
            "SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint " +
                "WHERE conrelid = 'audit_log'::regclass AND conname <> 'audit_log_action_check' ORDER BY conname",
            String::class.java,
        )

    private fun triggers(): List<String?> =
        jdbc.queryForList(
            "SELECT tgname || ' ' || pg_get_triggerdef(oid) FROM pg_trigger " +
                "WHERE tgrelid = 'audit_log'::regclass AND NOT tgisinternal ORDER BY tgname",
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
        const val DATABASE = "moyi_v61_audit_actions"
        val A1_ACTIONS =
            listOf("ADMIN_INVITED", "ADMIN_DISABLED", "ADMIN_ENROLLED", "ADMIN_SIGN_IN", "ADMIN_SIGN_OUT", "USER_LOOKED_UP")
    }
}
