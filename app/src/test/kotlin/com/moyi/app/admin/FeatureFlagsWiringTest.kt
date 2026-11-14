package com.moyi.app.admin

import com.moyi.ai.api.EmbeddingPort
import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags
import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import javax.sql.DataSource

/**
 * ADR-0067 decision 11, with the console **off** (production's state, and the media worker's): the
 * application's one [FeatureFlags] is admin's store over `feature_flags`, so the jobs and the
 * embedding port read the table the console writes, and a flag switched (here, by SQL, as the
 * console would) is seen by the next read with no restart.
 */
@SpringBootTest
@ActiveProfiles("test")
class FeatureFlagsWiringTest(
    @Autowired private val context: ApplicationContext,
    @Autowired private val flags: FeatureFlags,
    @Autowired private val embeddings: EmbeddingPort,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clean() {
        jdbc.update("DELETE FROM feature_flags")
    }

    @Test
    fun `the one FeatureFlags is admin's store, present with the console off`() {
        val beans = context.getBeansOfType(FeatureFlags::class.java)

        beans.size shouldBe 1
        AopUtils.getTargetClass(beans.values.single()).name shouldBe "com.moyi.admin.infra.FeatureFlagStore"
        context.getBeanNamesForType(Class.forName("com.moyi.admin.service.FlagConsole")).size shouldBe 0
    }

    @Test
    fun `a row in feature_flags is what every reader sees next, with no restart`() {
        flags.isOn(FeatureFlag.AI_EMBEDDINGS) shouldBe true
        val admin = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO admin_accounts (id, email, role, credentials_changed_at, status) VALUES (?, ?, 'ADMIN', now(), 'ACTIVE')",
            admin,
            "wiring-$admin@example.com",
        )
        jdbc.update("INSERT INTO feature_flags (key, enabled, updated_at, updated_by) VALUES ('AI_EMBEDDINGS', false, now(), ?)", admin)

        flags.isOn(FeatureFlag.AI_EMBEDDINGS) shouldBe false
        runCatching { embeddings.embed(listOf("x")) }.exceptionOrNull()?.message shouldBe "switched off"
    }
}
