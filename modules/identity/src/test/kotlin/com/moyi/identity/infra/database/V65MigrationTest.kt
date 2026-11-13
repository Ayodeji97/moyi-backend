package com.moyi.identity.infra.database

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * V65, `account_suspensions` (admin console A3, spec §7), as Flyway runs it: a database of its
 * own in the shared container, taken to V65, then given rows. What is tested is what the schema
 * itself refuses: a second open span, a span that ends before it starts, a span for nobody.
 */
internal class V65MigrationTest : PostgresIntegrationTest() {
    private val admin = JdbcTemplate(source("test"))
    private val target = source(DATABASE)
    private val jdbc = JdbcTemplate(target)

    @BeforeEach
    fun create() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        admin.execute("CREATE DATABASE $DATABASE")
        Flyway
            .configure()
            .dataSource(target)
            .locations("classpath:db/migration")
            .target("65")
            .load()
            .migrate()
    }

    @AfterEach
    fun drop() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
    }

    @Test
    fun `a span inserts, open or closed`() {
        val ada = user()

        span(ada, FROM, null) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM account_suspensions WHERE user_id = ?", Int::class.java, ada) shouldBe 1
    }

    @Test
    fun `a second open span for the same user is refused by the partial unique index`() {
        val ada = user()
        span(ada, FROM, null)

        shouldThrow<DataIntegrityViolationException> { span(ada, FROM.plusSeconds(60), null) }
            .message!! shouldContain "account_suspensions_one_open_key"
    }

    @Test
    fun `closed spans and one open span coexist, and another user's open span is no conflict`() {
        val ada = user()
        val bea = user()

        span(ada, FROM, FROM.plusSeconds(60)) shouldBe 1
        span(ada, FROM.plusSeconds(120), FROM.plusSeconds(180)) shouldBe 1
        span(ada, FROM.plusSeconds(240), null) shouldBe 1
        span(bea, FROM, null) shouldBe 1
    }

    @Test
    fun `a span lifted before it began is refused, and one lifted the instant it began is not`() {
        val ada = user()

        shouldThrow<DataIntegrityViolationException> { span(ada, FROM, FROM.minusNanos(1_000)) }
            .message!! shouldContain "account_suspensions_lifted_after_check"
        span(ada, FROM, FROM) shouldBe 1
    }

    @Test
    fun `a span for a user that does not exist is refused`() {
        shouldThrow<DataIntegrityViolationException> { span(UUID.randomUUID(), FROM, null) }
            .message!! shouldContain "account_suspensions_user_id_fkey"
    }

    private fun user(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, locale, status, created_at) VALUES (?, ?, 'Ada', 'en', 'ACTIVE', now())",
            id,
            "u-$id@example.com",
        )
        return id
    }

    private fun span(
        userId: UUID,
        from: Instant,
        to: Instant?,
    ): Int =
        jdbc.update(
            "INSERT INTO account_suspensions (id, user_id, suspended_at, lifted_at) VALUES (?, ?, ?, ?)",
            UUID.randomUUID(),
            userId,
            Timestamp.from(from),
            to?.let(Timestamp::from),
        )

    private fun source(database: String): DriverManagerDataSource {
        val url = postgres.jdbcUrl
        val base = url.substringBefore('?').substringBeforeLast('/')
        val options = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        return DriverManagerDataSource("$base/$database$options", postgres.username, postgres.password)
    }

    private companion object {
        const val DATABASE = "moyi_v65_account_suspensions"
        val FROM: Instant = Instant.parse("2026-11-13T10:00:00.123456Z")
    }
}
