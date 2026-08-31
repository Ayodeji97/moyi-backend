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
//
// @Autowired IS required here, unlike a regular Spring bean: Spring's
// TestContext framework only auto-wires a test class's constructor when
// @Autowired is present (or @TestConstructor opts in globally) — the
// implicit-single-constructor rule that applies to application beans does
// not extend to test classes. This is still constructor injection, not
// field injection (see ArchitectureTest, which checks isConstructorDefined
// rather than banning the annotation outright).
@SpringBootTest
@AutoConfigureMockMvc
class HealthCheckTest(
    @Autowired private val mockMvc: MockMvc,
) : PostgresIntegrationTest() {
    @Test
    fun `health endpoint returns 200`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
