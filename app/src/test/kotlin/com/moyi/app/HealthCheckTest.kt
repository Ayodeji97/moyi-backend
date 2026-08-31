package com.moyi.app

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class HealthCheckTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `health endpoint returns 200`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
