package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

// Postgres's own lock_timeout is 0: wait forever. Production bounds it through
// Hikari's connection-init-sql in application.yml, and nothing else would
// notice that line going missing — the application starts and every test
// passes without it, until a stuck write parks every request for its bond. The
// connection asked here comes from the application's own pool, so it reports
// what every production query runs under. Seen failing with the line removed
// ("0" instead of "10s").
@SpringBootTest
@ActiveProfiles("test")
class LockTimeoutTest(
    @Autowired private val jdbc: JdbcTemplate,
) : IntegrationTest() {
    @Test
    fun `every pooled connection gives up waiting for a lock after 10 seconds`() {
        jdbc.queryForObject("SHOW lock_timeout", String::class.java) shouldBe "10s"
    }
}
