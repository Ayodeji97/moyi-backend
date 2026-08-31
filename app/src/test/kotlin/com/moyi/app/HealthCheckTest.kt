package com.moyi.app

import com.moyi.common.testing.PostgresIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

// A real Postgres via Testcontainers (not a mock/embedded DB) is exactly
// what doc 25 §7 step 7 asks for, and this is also what proves Flyway's
// V1__extensions.sql actually runs — the app can't start otherwise.
@SpringBootTest
@AutoConfigureMockMvc
class HealthCheckTest(
    @param:Autowired private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {
    @Test
    fun `health endpoint returns 200`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
