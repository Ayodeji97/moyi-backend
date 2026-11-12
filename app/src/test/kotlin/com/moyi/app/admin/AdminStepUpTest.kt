package com.moyi.app.admin

import com.moyi.app.admin.AdminConsoleDriver.Companion.code
import com.moyi.common.testing.MutableClock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * The HTTP half of the step-up (A2 spec §10.2) that exists before the moderation pages: the
 * sign-in's code step opens the five-minute window by writing `admin.secondFactorAt`, the time
 * the code was proven, into the session, and nothing short of an accepted code writes it.
 *
 * The rule itself (`StepUp.isFresh`) and `AdminSignIn.stepUp` are `internal` to the admin
 * module, so their tests are that module's `AdminStepUpTest`; the step-up route and its HTTP
 * tests come with the moderation pages (plan Task 6).
 */
class AdminStepUpTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val console = AdminConsoleDriver(mockMvc, JdbcTemplate(dataSource))

    @Test
    fun `an accepted code at sign-in records when the second factor was proven`() {
        val (email, secret) = enrolled()
        val session = MockHttpSession()
        console.password(session, email, PASSWORD).status shouldBe 302
        session.getAttribute(SECOND_FACTOR_AT).shouldBeNull()

        val start = clock.instant()
        try {
            clock.advance(Duration.ofSeconds(7))
            console.code(session, code(secret, clock.instant().plusSeconds(30))).status shouldBe 302

            session.getAttribute(SECOND_FACTOR_AT) shouldBe clock.instant()
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `a new password step in the same session drops the proof an earlier sign-in made`() {
        val (first, secret) = enrolled()
        val (second) = enrolled()
        val session = MockHttpSession()
        console.password(session, first, PASSWORD).status shouldBe 302
        console.code(session, code(secret, clock.instant().plusSeconds(30))).status shouldBe 302
        session.getAttribute(SECOND_FACTOR_AT) shouldBe clock.instant()

        console.password(session, second, PASSWORD).status shouldBe 302

        session.getAttribute(SECOND_FACTOR_AT).shouldBeNull()
    }

    @Test
    fun `a refused code records nothing`() {
        val (email, secret) = enrolled()
        val session = MockHttpSession()
        console.password(session, email, PASSWORD).status shouldBe 302
        // A six-digit code that no step of the window around now produces.
        val valid = (-1L..1L).map { code(secret, clock.instant().plusSeconds(it * 30)) }.toSet()
        val wrong = generateSequence(0) { it + 1 }.map { "%06d".format(it) }.first { it !in valid }

        console.code(session, wrong).status shouldBe 401

        session.getAttribute(SECOND_FACTOR_AT).shouldBeNull()
    }

    private fun enrolled(): Pair<String, ByteArray> {
        val email = "stepup-${UUID.randomUUID().toString().take(8)}@example.com"
        return email to console.enrol(email, PASSWORD, clock.instant())
    }

    private companion object {
        /** `AdminAuthorities.SECOND_FACTOR_AT`, which is `internal` to the admin module. */
        const val SECOND_FACTOR_AT = "admin.secondFactorAt"
        const val PASSWORD = "nineteen silver kettles hum"
    }
}
