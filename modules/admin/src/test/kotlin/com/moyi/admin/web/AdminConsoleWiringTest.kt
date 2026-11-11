package com.moyi.admin.web

import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.service.LiveAdminSessions
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

/**
 * The console switched on inside this module's own context: the chain is there and admits
 * only the list, and [LiveAdminSessions] answers each of the questions the session guard
 * asks of it. The chain's whole behaviour, beside the API's, is `app`'s `AdminChainTest`.
 */
@SpringBootTest(
    classes = [AdminTestApplication::class],
    properties = [
        "moyi.admin.enabled=true",
        "moyi.admin.allowed-networks=10.9.0.0/16",
        "moyi.admin.totp-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    ],
)
@AutoConfigureMockMvc
@Import(AdminConsoleWiringTest.TimeConfiguration::class)
internal class AdminConsoleWiringTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val sessions: LiveAdminSessions,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `the listed network gets the sign-in page, under the spec's policy, and anyone else gets 401`() {
        val page = mockMvc.get("/admin/login") { with { it.apply { remoteAddr = "10.9.1.1" } } }.andReturn().response
        page.status shouldBe 200
        page.getHeader("Content-Security-Policy") shouldBe AdminSecurityConfiguration.CONTENT_SECURITY_POLICY
        mockMvc
            .get("/admin/login") { with { it.apply { remoteAddr = "10.10.0.1" } } }
            .andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `a session is live while the account is active, unchanged and inside its lifetime`() {
        val (id, changedAt) = newAdmin()
        val deadline = clock.instant().plus(LIFETIME)
        sessions.isLive(id, changedAt, deadline) shouldBe true
        sessions.isLive(id, changedAt.minusNanos(MICRO), deadline) shouldBe false
        sessions.isLive(id, null, deadline) shouldBe false
        sessions.isLive(id, changedAt, null) shouldBe false
        sessions.isLive(UUID.randomUUID(), changedAt, deadline) shouldBe false
        sessions.isLive(id, changedAt, clock.instant().minusSeconds(1)) shouldBe false

        jdbc.update("UPDATE admin_accounts SET status = 'DISABLED' WHERE id = ?", id) shouldBe 1
        sessions.isLive(id, changedAt, deadline) shouldBe false
    }

    private fun newAdmin(): Pair<UUID, Instant> {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO admin_accounts (id, email, role, credentials_changed_at, status, failed_attempts) " +
                "VALUES (?, ?, 'SUPPORT', ?, 'ACTIVE', 0)",
            id,
            "wiring-${id.toString().take(8)}@example.com",
            Timestamp.from(clock.instant()),
        )
        val stored =
            jdbc.queryForObject("SELECT credentials_changed_at FROM admin_accounts WHERE id = ?", OffsetDateTime::class.java, id)!!
        return id to stored.toInstant()
    }

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock()
    }

    private companion object {
        val LIFETIME: Duration = Duration.ofHours(8)
        const val MICRO = 1_000L
    }
}
