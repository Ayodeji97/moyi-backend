package com.moyi.admin.infra

import com.moyi.admin.domain.AuditAction
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
 * V67 (A4 spec §6, ADR-0067) creates `feature_flags`, empty, and widens the action `CHECK` by A4's six
 * actions and drops nothing: a database of its own, taken to V66 with A3's rows, then to V67.
 */
internal class V67MigrationTest : PostgresIntegrationTest() {
    private val admin = JdbcTemplate(source("test"))
    private val target = source(DATABASE)
    private val jdbc = JdbcTemplate(target)

    @BeforeEach
    fun create() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        admin.execute("CREATE DATABASE $DATABASE")
        flyway("66").migrate()
    }

    @AfterEach
    fun drop() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
    }

    @Test
    fun `A4's six actions are refused before V67 and insert after it, and every earlier action still does`() {
        A4_ACTIONS.forEach { action -> withClue("before V67: $action") { shouldThrow<DataIntegrityViolationException> { insert(action) } } }
        insert("ACCOUNT_SUSPENDED") shouldBe 1

        flyway("67").migrate()

        (AuditAction.entries.map { it.name } - V69MigrationTest.V69_ACTIONS).forEach { action ->
            withClue(action) { insert(action) shouldBe 1 }
        }
        shouldThrow<DataIntegrityViolationException> { insert("JOB_RAN") }.message!! shouldContain "audit_log_action_check"
        jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ACCOUNT_SUSPENDED'", Int::class.java) shouldBe 2
    }

    @Test
    fun `feature_flags starts empty, takes a known key switched by an admin, and refuses an unknown key or admin`() {
        flyway("67").migrate()
        jdbc.queryForObject("SELECT count(*) FROM feature_flags", Int::class.java) shouldBe 0
        val admin = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO admin_accounts (id, email, role, credentials_changed_at, status) VALUES (?, ?, 'ADMIN', now(), 'ACTIVE')",
            admin,
            "flags-$admin@example.com",
        )

        flag("DAILY_REMINDERS", admin) shouldBe 1
        shouldThrow<DataIntegrityViolationException> { flag("DAILY_REMINDERS", admin) }
        shouldThrow<DataIntegrityViolationException> { flag("NO_SUCH_FLAG", admin) }.message!! shouldContain "feature_flags_key_check"
        shouldThrow<DataIntegrityViolationException> { flag("AI_EMBEDDINGS", UUID.randomUUID()) }
    }

    private fun flag(
        key: String,
        admin: UUID,
    ): Int = jdbc.update("INSERT INTO feature_flags (key, enabled, updated_at, updated_by) VALUES (?, false, now(), ?)", key, admin)

    private fun insert(action: String): Int =
        jdbc.update(
            "INSERT INTO audit_log (id, actor_type, action, outcome) VALUES (?, 'ADMIN', ?, 'SUCCEEDED')",
            UUID.randomUUID(),
            action,
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

    companion object {
        private const val DATABASE = "moyi_v67_flags"

        /** A4's actions, which V61's, V64's and V66's tests leave out of "every action of the enum". */
        val A4_ACTIONS =
            listOf(
                "JOB_RUN",
                "STREAK_RECALCULATED",
                "FEATURE_FLAG_CHANGED",
                "SUPPORT_INBOX_VIEWED",
                "SUPPORT_TICKET_READ",
                "SUPPORT_TICKET_CLOSED",
            )
    }
}
