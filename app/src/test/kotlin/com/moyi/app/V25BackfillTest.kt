package com.moyi.app

import com.moyi.common.testing.PostgresIntegrationTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * V25 runs on a database that already holds entries: every live row's words become its
 * projection, and no erased row gains any (ADR-0039, the controller's default 8).
 *
 * A database of its own in the shared container, taken to V24 by Flyway, given rows, then
 * taken to V25. No Spring context: what is tested is the migration, as Flyway runs it in
 * production, against rows written before it.
 */
class V25BackfillTest : PostgresIntegrationTest() {
    private val admin = JdbcTemplate(source("test"))

    @AfterEach
    fun drop() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
    }

    @Test
    fun `V25 gives every live entry its words as its projection and no erased entry any`() {
        admin.execute("DROP DATABASE IF EXISTS $DATABASE WITH (FORCE)")
        admin.execute("CREATE DATABASE $DATABASE")
        val target = source(DATABASE)
        flyway(target, "24").migrate()
        val jdbc = JdbcTemplate(target)
        val bond = UUID.randomUUID()
        val day = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, version)
            VALUES (?, ?, '2026-09-15', 'OPEN', 'UTC', '2026-09-15T00:00:00Z', '2026-09-16T00:00:00Z', 0, '2026-09-15T00:00:00Z', 0)
            """.trimIndent(),
            day,
            bond,
        )
        val live = entry(jdbc, day, bond, "Obrigada pelo café", "SUBMITTED", deleted = false)
        val revealed = entry(jdbc, day, bond, "thank you for the lamp", "REVEALED", deleted = false)
        val erased = entry(jdbc, day, bond, null, "DELETED", deleted = true)
        // No request writes this row (`Entry`'s init refuses it); it is here so that the backfill's
        // own condition, and not only an erased row's missing text, is what leaves it unsearchable.
        val handMade = entry(jdbc, day, bond, "words that were taken back", "DELETED", deleted = true)

        flyway(target, "25").migrate()

        fun projection(id: UUID) = jdbc.queryForObject("SELECT text_search FROM entries WHERE id = ?", String::class.java, id)

        fun finds(
            id: UUID,
            words: String,
        ) = jdbc.queryForObject(
            "SELECT search_vector @@ plainto_tsquery('simple', public.immutable_unaccent(?)) FROM entries WHERE id = ?",
            Boolean::class.java,
            words,
            id,
        )
        projection(live) shouldBe "Obrigada pelo café"
        projection(revealed) shouldBe "thank you for the lamp"
        projection(erased).shouldBeNull()
        projection(handMade).shouldBeNull()
        finds(live, "CAFE") shouldBe true
        finds(revealed, "lamp") shouldBe true
        finds(handMade, "taken") shouldBe false
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE tablename = 'entries' AND indexname = 'entries_search_idx'",
            Int::class.java,
        ) shouldBe 1
    }

    @Suppress("LongParameterList") // A row's own columns.
    private fun entry(
        jdbc: JdbcTemplate,
        day: UUID,
        bond: UUID,
        text: String?,
        status: String,
        deleted: Boolean,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at,
                                 revealed_at, deleted_at)
            VALUES (?, ?, ?, ?, ?, ?, now(), now(), now(), CASE WHEN ? = 'REVEALED' THEN now() END, CASE WHEN ? THEN now() END)
            """.trimIndent(),
            id,
            day,
            bond,
            UUID.randomUUID(),
            text,
            status,
            status,
            deleted,
        )
        return id
    }

    private fun flyway(
        target: DriverManagerDataSource,
        version: String,
    ): Flyway =
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
        const val DATABASE = "moyi_v25_backfill"
    }
}
