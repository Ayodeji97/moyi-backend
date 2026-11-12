package com.moyi.app.admin

import com.moyi.app.admin.AdminConsoleDriver.Companion.code
import com.moyi.common.testing.MutableClock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
 * module, so their tests are that module's `AdminStepUpTest`. Here, from plan Task 6, the
 * step-up as a moderator meets it: an irreversible act on a stale window shows the code field
 * with the note kept and decides nothing; a correct code opens the window again and decides; a
 * wrong one decides nothing, writes no proof, and counts toward the lockout.
 */
class AdminStepUpTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val console = AdminConsoleDriver(mockMvc, JdbcTemplate(dataSource))
    private val rig = ModerationRig(mockMvc, JdbcTemplate(dataSource), clock)

    @Test
    fun `six minutes after sign-in, uphold delete, block and delete each ask for a code with the note kept, and decide nothing`() {
        val (session, _) = signedInModerator()
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "VIOLENCE_OR_SELF_HARM")
        val toBlock = rig.quarantined()
        val toDelete = rig.quarantined()
        val start = clock.instant()
        try {
            clock.advance(Duration.ofMinutes(6))
            val note = "a note to keep ${UUID.randomUUID()}"
            val asked =
                listOf(
                    rig.post(
                        session,
                        "/admin/reports/$report/uphold",
                        mapOf(
                            "expectedReason" to "VIOLENCE_OR_SELF_HARM",
                            "takedown" to "DELETE",
                            "note" to note,
                        ),
                    ),
                    rig.post(session, "/admin/quarantine/$toBlock/block", mapOf("note" to note)),
                    rig.post(session, "/admin/quarantine/$toDelete/delete", mapOf("note" to note)),
                )

            asked.forEach {
                it.response.status shouldBe 200
                it.response.contentAsString shouldContain """name="code""""
                it.response.contentAsString shouldContain note
                it.response.contentAsString shouldContain "Nothing has been decided yet."
            }
            rig.reportStatus(report) shouldBe "OPEN|VIOLENCE_OR_SELF_HARM"
            rig.assetState(photo) shouldBe "QUARANTINED|QUARANTINED|false"
            rig.assetState(toBlock) shouldBe "QUARANTINED|QUARANTINED|false"
            rig.assetState(toDelete) shouldBe "QUARANTINED|QUARANTINED|false"
            rig.audited(rig.adminOf(session)).map { it.head.substringBefore('|') }.filter {
                it.startsWith("REPORT_") ||
                    it.startsWith("MEDIA_")
            } shouldBe
                emptyList()
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `a page whose window has closed shows the code field up front`() {
        val (session, _) = signedInModerator()
        val photo = rig.quarantined()
        val start = clock.instant()
        try {
            rig.get(session, "/admin/quarantine/$photo").contentAsString shouldNotContain """name="code""""
            clock.advance(Duration.ofMinutes(6))
            rig.get(session, "/admin/quarantine/$photo").contentAsString shouldContain """name="code""""
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `a correct code opens the window again and the uphold, block and delete then decide`() {
        val (session, secret) = signedInModerator()
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "VIOLENCE_OR_SELF_HARM")
        val toBlock = rig.quarantined()
        val toDelete = rig.quarantined()
        val admin = rig.adminOf(session)
        val start = clock.instant()
        try {
            clock.advance(Duration.ofMinutes(6))
            val upheld =
                rig.post(
                    session,
                    "/admin/reports/$report/uphold",
                    mapOf(
                        "expectedReason" to "VIOLENCE_OR_SELF_HARM",
                        "takedown" to "DELETE",
                        "note" to NOTE,
                        "code" to code(secret, clock.instant()),
                    ),
                )
            upheld.response.status shouldBe 302
            session.getAttribute(SECOND_FACTOR_AT) shouldBe clock.instant()
            // The window the code opened covers the next irreversible act; then it closes again.
            clock.advance(Duration.ofMinutes(4))
            rig.post(session, "/admin/quarantine/$toBlock/block", mapOf("note" to NOTE)).response.status shouldBe 302
            clock.advance(Duration.ofMinutes(2))
            rig.post(session, "/admin/quarantine/$toDelete/delete", mapOf("note" to NOTE)).response.status shouldBe 200
            rig
                .post(session, "/admin/quarantine/$toDelete/delete", mapOf("note" to NOTE, "code" to code(secret, clock.instant())))
                .response.status shouldBe 302

            rig.reportStatus(report) shouldBe "UPHELD|VIOLENCE_OR_SELF_HARM"
            rig.assetState(photo).substringBefore('|') shouldBe "DELETED"
            rig.assetState(toBlock) shouldBe "QUARANTINED|BLOCKED|true"
            rig.assetState(toDelete).substringBefore('|') shouldBe "DELETED"
            rig.audited(admin).map { it.head.substringBeforeLast('|') }.filter { it.substringBefore('|') in DECIDING } shouldBe
                listOf(
                    "ADMIN_STEP_UP|SUCCEEDED",
                    "REPORT_UPHELD|SUCCEEDED",
                    "MEDIA_BLOCKED|SUCCEEDED",
                    "ADMIN_STEP_UP|SUCCEEDED",
                    "MEDIA_DELETED|SUCCEEDED",
                )
        } finally {
            clock.set(start)
        }
    }

    @Test
    fun `a wrong code decides nothing, writes no proof, and counts toward the lockout`() {
        val (session, secret) = signedInModerator()
        val photo = rig.quarantined()
        val admin = rig.adminOf(session)
        val start = clock.instant()
        try {
            clock.advance(Duration.ofMinutes(6))
            val before = session.getAttribute(SECOND_FACTOR_AT)

            val refused = rig.post(session, "/admin/quarantine/$photo/delete", mapOf("note" to NOTE, "code" to wrongCode(secret)))

            refused.response.status shouldBe 401
            refused.response.contentAsString shouldContain AdminAuthControllerWords.WRONG_CODE
            refused.response.contentAsString shouldContain NOTE
            session.getAttribute(SECOND_FACTOR_AT) shouldBe before
            rig.assetState(photo) shouldBe "QUARANTINED|QUARANTINED|false"
            rig.count("SELECT failed_attempts FROM admin_accounts WHERE id = ?", admin) shouldBe 1
            rig.audited(admin).last().head shouldBe "ADMIN_STEP_UP|REFUSED|null"
        } finally {
            clock.set(start)
        }
    }

    /** A `MODERATOR` signed in for real, through both factors: the session and the authenticator's secret. */
    private fun signedInModerator(): Pair<MockHttpSession, ByteArray> {
        val email = "stepup-mod-${UUID.randomUUID().toString().take(8)}@example.com"
        val secret = console.enrol(email, PASSWORD, clock.instant(), role = "MODERATOR")
        val session = MockHttpSession()
        console.password(session, email, PASSWORD).status shouldBe 302
        console.code(session, code(secret, clock.instant().plusSeconds(30))).status shouldBe 302
        return session to secret
    }

    private fun wrongCode(secret: ByteArray): String {
        val valid = (-1L..1L).map { code(secret, clock.instant().plusSeconds(it * 30)) }.toSet()
        return generateSequence(0) { it + 1 }.map { "%06d".format(it) }.first { it !in valid }
    }

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
        val NOTE = ModerationRig.NOTE
        val DECIDING = setOf("ADMIN_STEP_UP", "REPORT_UPHELD", "MEDIA_BLOCKED", "MEDIA_DELETED")
    }

    /** `AdminAuthController.WRONG_CODE`, which is `internal` to the admin module. */
    private object AdminAuthControllerWords {
        const val WRONG_CODE = "That code is not right. Codes change every 30 seconds."
    }
}
