package com.moyi.admin.infra

import com.moyi.common.core.FeatureFlag
import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * The flags are a closed set written once, in [FeatureFlag]. V67's `CHECK` repeats it for the
 * database; this holds the SQL to the enum, as `AuditActionCheckTest` does for the actions.
 */
@SpringBootTest
internal class FeatureFlagCheckTest(
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `the check on feature_flags key lists exactly the FeatureFlag values`() {
        val definition =
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'feature_flags_key_check'",
                String::class.java,
            )!!

        Regex("'([A-Z_]+)'").findAll(definition).map { it.groupValues[1] }.toList() shouldContainExactlyInAnyOrder
            FeatureFlag.entries.map { it.name }
    }
}
