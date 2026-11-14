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
 * V66 (A3 spec §7) widens the action `CHECK` by `ACCOUNT_SUSPENDED` and `ACCOUNT_UNSUSPENDED` and
 * drops nothing: a database of its own, taken to V65, then to V66, as [V64MigrationTest] does for V64.
 */
internal class V66MigrationTest : PostgresIntegrationTest() {
    private val admin = JdbcTemplate(source("test"))
    private val target = source(DATABASE)
    private val jdbc = JdbcTemplate(target)

    @BeforeEach
    fun create() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        admin.execute("CREATE DATABASE $DATABASE")
        flyway("65").migrate()
    }

    @AfterEach
    fun drop() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
    }

    @Test
    fun `the two suspension actions are refused before V66 and insert after it`() {
        listOf("ACCOUNT_SUSPENDED", "ACCOUNT_UNSUSPENDED").forEach { action ->
            withClue("before V66: $action") { shouldThrow<DataIntegrityViolationException> { insert(action) } }
        }

        flyway("66").migrate()

        listOf("ACCOUNT_SUSPENDED", "ACCOUNT_UNSUSPENDED").forEach { action -> insert(action) shouldBe 1 }
    }

    @Test
    fun `every action of the enum, V64's among them, still inserts after V66, and one outside the list is refused`() {
        flyway("66").migrate()

        AuditAction.entries.map { it.name }.forEach { action -> withClue(action) { insert(action) shouldBe 1 } }
        listOf("NOPE", "ACCOUNT_SUSPEND").forEach { action ->
            withClue(action) {
                shouldThrow<DataIntegrityViolationException> { insert(action) }.message!! shouldContain "audit_log_action_check"
            }
        }
    }

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

    private companion object {
        const val DATABASE = "moyi_v66_audit_actions"
    }
}
