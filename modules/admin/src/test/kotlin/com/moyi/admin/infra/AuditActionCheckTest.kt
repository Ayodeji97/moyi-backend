package com.moyi.admin.infra

import com.moyi.admin.domain.AuditAction
import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * The rule that an audit action is one of a closed set lives once, in
 * [AuditAction]. V42's `CHECK` repeats it for the database; this holds the SQL
 * to the enum, so a value added to one and not the other fails here.
 */
@SpringBootTest
internal class AuditActionCheckTest(
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `the check on audit_log action lists exactly the AuditAction values`() {
        val definition =
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'audit_log_action_check'",
                String::class.java,
            )!!

        // Postgres prints it as `= ANY (ARRAY['A'::text, 'B'::text])`.
        val listed = Regex("'([A-Z_]+)'").findAll(definition).map { it.groupValues[1] }.toList()

        listed shouldContainExactlyInAnyOrder AuditAction.entries.map { it.name }
    }
}
