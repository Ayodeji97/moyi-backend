package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Doc 18 §9 at the composition root, with the real `application.yml`: the
 * one expected constraint violation in the system — a second registration
 * of an address (ADR-0015) — must not write the address to the log. The
 * module's own test proves the mechanism; this one proves the configuration
 * that ships.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension::class)
class AddressNeverLoggedTest(
    @Autowired private val mockMvc: MockMvc,
) : IntegrationTest() {
    @Test
    fun `a duplicate registration does not put the address in the log`(output: CapturedOutput) {
        repeat(2) {
            mockMvc
                .post("/api/v1/auth/register") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"email":"never-logged@example.com","password":"correct horse battery","displayName":"Ada",""" +
                        """"locale":"en","acceptedTermsVersion":"2026-09-01","over18":true}"""
                }.andReturn()
                .response.status shouldBe 201
        }

        output.all shouldNotContain "never-logged@example.com"
    }
}
