package com.moyi.app

import com.moyi.common.testing.PostgresIntegrationTest
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
) : PostgresIntegrationTest() {
    private val jdbcTemplate = JdbcTemplate(dataSource)

    @Test
    fun `every module's migrations run, in one sequence, against one schema`() {
        // V1 lives in `app` (database-wide extensions); V2, V3 and V4 live in
        // `modules/identity` (its own tables). Flyway merges every
        // `classpath:db/migration` it finds, which is what lets a module own
        // its schema without `app` restating it — and this assertion is what
        // notices when a module's migrations are not on the classpath at all,
        // a failure that otherwise shows up as a missing table much later.
        val appliedVersions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String::class.java,
            )
        assertEquals(listOf("1", "2", "3", "4"), appliedVersions)

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
                    "AND tablename IN ('users', 'credentials', 'consent_records', 'verification_tokens')",
                String::class.java,
            )
        assertTrue(
            identityTables.containsAll(listOf("users", "credentials", "consent_records", "verification_tokens")),
            "Expected the identity module's tables, found: $identityTables",
        )
    }
}
