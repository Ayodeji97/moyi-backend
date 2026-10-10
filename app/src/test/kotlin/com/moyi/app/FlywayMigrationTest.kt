package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

/**
 * A regression test for a real Phase 0 bug: `spring-boot-starter-flyway`
 * (Spring Boot 4's dedicated Flyway autoconfiguration module) was missing
 * from `app`'s dependencies, so Flyway sat on the classpath configured but
 * never ran — and nothing failed, because no `@Entity` existed yet for
 * Hibernate's `ddl-auto: validate` to check against. `HealthCheckTest`
 * passing was not evidence Flyway worked; only a query against the
 * database it actually ran migrations against is.
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationTest(
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbcTemplate = JdbcTemplate(dataSource)

    @Test
    fun `the outbox carries the two indexes its guarantees rest on`() {
        // V14's two indexes are correctness, not performance (spec §7): one
        // delivery per event per consumer, and a pending-work index that
        // stays small because it leaves the delivered rows out.
        val outboxIndexes =
            jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'outbox_deliveries'",
                String::class.java,
            )
        assertTrue(
            outboxIndexes.any { "UNIQUE" in it!! && "(event_id, consumer_id)" in it },
            "Expected a unique (event_id, consumer_id) on outbox_deliveries, found: $outboxIndexes",
        )
        assertTrue(
            outboxIndexes.any { "(consumer_id, next_attempt_at)" in it!! && "WHERE (processed_at IS NULL)" in it },
            "Expected the partial pending index on outbox_deliveries, found: $outboxIndexes",
        )
    }

    @Test
    fun `every module's migrations run, in one sequence, against one schema`() {
        // V1 lives in `app` (database-wide extensions); V2 to V8 live in
        // `modules/identity`, V9, V10, V13, V18, V19 and V21 in `modules/bond` (its own
        // tables, its proposals, and the effective-anchor timeline), V11 in
        // `common:web` (idempotency_keys, doc 06 §1), V12, V15, V17 and V22 in
        // `modules/gratitude` (its tables, the close job's index, the streak, favourites), V14
        // and V20 in `common:events` (the outbox, spec §8, and its consumers) and
        // V16 in `modules/scheduling` (ShedLock's table). Versions are one global sequence across
        // modules. Flyway merges every `classpath:db/migration` it finds, which
        // is what lets a module own its schema without `app` restating it — and
        // this assertion is what notices when a module's migrations are not on
        // the classpath at all, a failure that otherwise shows up as a missing
        // table much later.
        //
        // The rule checked is "every module's migrations landed, as one
        // ascending sequence" — not "exactly this list forever". A hard-coded
        // exact list broke the moment this task landed V11, and would break
        // again the moment the next task in this slice lands V12 (review
        // round 1, Minor #2): checking numeric order plus a minimum-required
        // subset survives that without losing what the test is actually for.
        val appliedVersions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String::class.java,
            )
        val versions = appliedVersions.map { it!!.toInt() }
        assertEquals(versions.sorted(), versions, "Flyway applied migrations out of numeric order: $versions")
        assertTrue(
            versions.containsAll((1..22).toList()),
            "Expected every module's migrations through V22, found: $versions",
        )

        val extensions =
            jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension WHERE extname IN ('citext', 'pgcrypto')",
                String::class.java,
            )
        assertTrue(
            extensions.containsAll(listOf("citext", "pgcrypto")),
            "Expected citext and pgcrypto extensions, found: $extensions",
        )

        val identityTables =
            jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' " +
                    "AND tablename IN ('users', 'credentials', 'consent_records', 'verification_tokens', 'refresh_tokens')",
                String::class.java,
            )
        assertTrue(
            identityTables.containsAll(listOf("users", "credentials", "consent_records", "verification_tokens", "refresh_tokens")),
            "Expected the identity module's tables, found: $identityTables",
        )

        val bondTables =
            jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' " +
                    "AND tablename IN ('bonds', 'bond_members', 'bond_invites', 'blocks', 'bond_proposals')",
                String::class.java,
            )
        assertTrue(
            bondTables.containsAll(listOf("bonds", "bond_members", "bond_invites", "blocks")),
            "Expected the bond module's tables (V9), found: $bondTables",
        )

        val webTables =
            jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename = 'idempotency_keys'",
                String::class.java,
            )
        assertTrue(
            webTables.contains("idempotency_keys"),
            "Expected common:web's idempotency_keys table (V11), found: $webTables",
        )
    }
}
