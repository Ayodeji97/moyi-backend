package com.moyi.app.admin

import com.moyi.common.testing.IntegrationTest
import com.moyi.export.api.DataExportJobs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * **With the console switched off, the export still says the log is kept** (A2 spec §9.3, §12;
 * ADR-0055 ruling D): `AdminAccessDisclosure` is a persistence bean, not a console one, so the
 * audit rows written while the console was on are still disclosed by their rule once it is off.
 * The shipped defaults, as `AdminDisabledTest` runs them, with the export's storage in memory
 * ([ExportArchives]), and the export built through `PrepareExports` with no transaction open.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ExportArchives::class)
class AdminAccessDisclosureOffTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val context: ApplicationContext,
    @Autowired private val jobs: DataExportJobs,
    @Autowired private val clock: Clock,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val json = JsonMapper.builder().build()

    @Test
    fun `an export with the console off says the log is kept, in the one sentence, and lists what its rule shows`() {
        // The console really is off here: none of its beans exists.
        context.getBeanNamesForType(Class.forName("com.moyi.admin.service.AdminProperties")).toList().shouldBeEmpty()
        val ada = ExportArchives.register(mockMvc, jdbc)
        val untouched = ExportArchives.register(mockMvc, jdbc)
        // A lookup made while the console was on, for the member's own request.
        jdbc.update(
            "INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, target_type, target_id, outcome, metadata) " +
                "VALUES (uuidv7(), ?, 'ADMIN', ?, 'USER_LOOKED_UP', 'USER', ?, 'SUCCEEDED', " +
                "'{\"by\": \"id\", \"purpose\": \"MEMBER_REQUEST\"}')",
            Timestamp.from(clock.instant()),
            UUID.randomUUID(),
            ada.toString(),
        )

        val adas = json.readTree(ExportArchives.build(jobs, jdbc, clock, ada))["adminAccess"]
        val others = json.readTree(ExportArchives.build(jobs, jdbc, clock, untouched))["adminAccess"]

        adas["logKept"].asBoolean() shouldBe true
        adas["accesses"].toList().map { it["action"].asString() } shouldContainExactly listOf("ACCOUNT_LOOKED_UP")
        others["logKept"].asBoolean() shouldBe true
        others["accesses"].toList().shouldBeEmpty()
        adas["statement"].asString() shouldBe others["statement"].asString()
        adas["statement"].asString() shouldBe
            "Administrator access to your account or to what you wrote is recorded. This lists it, oldest first, " +
            "except access the law or a safety review requires us not to disclose."
    }
}
