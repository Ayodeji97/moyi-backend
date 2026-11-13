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
 * V64 (A5 spec §9) widens the action `CHECK` by `ENTRY_TEXT_READ` and drops nothing: a database of
 * its own, taken to V63, then to V64, as [V61MigrationTest] does for V61.
 */
internal class V64MigrationTest : PostgresIntegrationTest() {
    private val admin = JdbcTemplate(source("test"))
    private val target = source(DATABASE)
    private val jdbc = JdbcTemplate(target)

    @BeforeEach
    fun create() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        admin.execute("CREATE DATABASE $DATABASE")
        flyway("63").migrate()
    }

    @AfterEach
    fun drop() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
    }

    @Test
    fun `ENTRY_TEXT_READ is refused before V64 and inserts after it`() {
        withClue("before V64") { shouldThrow<DataIntegrityViolationException> { insert("ENTRY_TEXT_READ") } }

        flyway("64").migrate()

        insert("ENTRY_TEXT_READ") shouldBe 1
    }

    @Test
    fun `every older action still inserts after V64, and one outside the list is still refused`() {
        flyway("64").migrate()

        (AuditAction.entries.map { it.name }).forEach { action -> withClue(action) { insert(action) shouldBe 1 } }
        shouldThrow<DataIntegrityViolationException> { insert("NOPE") }.message!! shouldContain "audit_log_action_check"
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
        const val DATABASE = "moyi_v64_audit_actions"
    }
}
