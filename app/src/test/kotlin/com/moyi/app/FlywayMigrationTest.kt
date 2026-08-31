package com.moyi.app

import com.moyi.common.testing.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
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
class FlywayMigrationTest(
    @param:Autowired dataSource: DataSource,
) : PostgresIntegrationTest() {
    private val jdbcTemplate = JdbcTemplate(dataSource)

    @Test
    fun `V1__extensions migration actually ran`() {
        val appliedVersions =
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                String::class.java,
            )
        assertEquals(listOf("1"), appliedVersions)

        val extensions =
            jdbcTemplate.queryForList(
                "SELECT extname FROM pg_extension WHERE extname IN ('citext', 'pgcrypto')",
                String::class.java,
            )
        assertTrue(
            extensions.containsAll(listOf("citext", "pgcrypto")),
            "Expected citext and pgcrypto extensions, found: $extensions",
        )
    }
}
