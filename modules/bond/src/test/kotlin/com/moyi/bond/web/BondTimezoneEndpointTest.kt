package com.moyi.bond.web

import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * The shared time zone: propose, confirm, cancel (FR-027, §5.2 rows #13–#15).
 *
 * `states.md` §8 draws this as three steps and explains why it is not a
 * settings toggle: the anchor zone decides which day an entry belongs to for
 * both members, so it changes only with both members' agreement and at most
 * once every 30 days.
 *
 * The lapsing cases move `expires_at` with SQL rather than waiting seven days —
 * the state is genuinely built rather than simulated, as `InviteOneAnswerTest`
 * does for expired codes.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondTimezoneEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a creator alone moves the zone at once, and records no proposal`() {
        // Spec §11 decision 6: there is nobody to consent, and ADR-0004 expects
        // an onboarding mistake to be correctable.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = propose(ada, bondId, "Europe/London")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"anchorTimezone\":\"Europe/London\""
        response.contentAsString shouldContain "\"pendingTimezoneChange\":null"
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals", Int::class.java) shouldBe 0
    }

    @Test
    fun `an active bond records a proposal, and the zone does not move yet`() {
        val (ada, bea, bondId) = pairedBond()

        val response = propose(ada, bondId, "Europe/London")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"anchorTimezone\":\"Africa/Lagos\""
        response.contentAsString shouldContain "\"proposedTimezone\":\"Europe/London\""
        // The pending state names the member, which is what `states.md` §8's
        // wording needs — and a member id, never a user id.
        response.contentAsString shouldContain "\"proposedByMemberId\""
        getBond(bea, bondId).contentAsString shouldContain "\"proposedTimezone\":\"Europe/London\""
        jdbc.queryForObject("SELECT anchor_timezone FROM bonds", String::class.java) shouldBe "Africa/Lagos"
    }

    @Test
    fun `the other member confirms, and the zone moves`() {
        val (ada, bea, bondId) = pairedBond()
        propose(ada, bondId, "Europe/London").status shouldBe 200

        val response = confirm(bea, bondId)

        response.status shouldBe 200
        response.contentAsString shouldContain "\"anchorTimezone\":\"Europe/London\""
        response.contentAsString shouldContain "\"pendingTimezoneChange\":null"
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NOT NULL", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT timezone_changed_at IS NOT NULL FROM bonds", Boolean::class.java) shouldBe true
    }

    @Test
    fun `the proposer cannot confirm their own proposal`() {
        // BR-6: two-party consent means the *other* member agrees. One person
        // clicking twice is not consent.
        val (ada, _, bondId) = pairedBond()
        propose(ada, bondId, "Europe/London").status shouldBe 200

        val response = confirm(ada, bondId)

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"PROPOSAL_NEEDS_OTHER_MEMBER\""
        jdbc.queryForObject("SELECT anchor_timezone FROM bonds", String::class.java) shouldBe "Africa/Lagos"
    }

    @Test
    fun `a second proposal while one is open is refused`() {
        val (ada, bea, bondId) = pairedBond()
        propose(ada, bondId, "Europe/London").status shouldBe 200

        val again = propose(ada, bondId, "Asia/Tokyo")
        val byTheOther = propose(bea, bondId, "Asia/Tokyo")

        again.status shouldBe 409
        again.contentAsString shouldContain "\"code\":\"PROPOSAL_PENDING\""
        byTheOther.status shouldBe 409
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals", Int::class.java) shouldBe 1
    }

    @Test
    fun `a lapsed proposal cannot be confirmed, and a fresh one may be made`() {
        // The lazy close (ADR-0030): the lapsed row is invisible to every read
        // and still holds V10's unique slot until a new proposal needs it.
        val (ada, bea, bondId) = pairedBond()
        propose(ada, bondId, "Europe/London").status shouldBe 200
        // Both columns, not just the expiry: V10 requires `expires_at >
        // proposed_at`, so a row with an expiry before its own proposal cannot
        // exist — which is what the constraint is for, and it caught this
        // fixture the moment it was added.
        jdbc.update(
            "UPDATE bond_proposals SET proposed_at = now() - interval '9 days', expires_at = now() - interval '2 days'",
        )

        confirm(bea, bondId).status shouldBe 404
        getBond(bea, bondId).contentAsString shouldContain "\"pendingTimezoneChange\":null"

        val fresh = propose(ada, bondId, "Asia/Tokyo")

        fresh.status shouldBe 200
        fresh.contentAsString shouldContain "\"proposedTimezone\":\"Asia/Tokyo\""
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE cancelled_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `either member may cancel, and then a fresh proposal is allowed`() {
        val (ada, bea, bondId) = pairedBond()
        propose(ada, bondId, "Europe/London").status shouldBe 200

        cancel(bea, bondId).status shouldBe 204

        getBond(ada, bondId).contentAsString shouldContain "\"pendingTimezoneChange\":null"
        propose(ada, bondId, "Asia/Tokyo").status shouldBe 200
    }

    @Test
    fun `cancelling nothing is 404`() {
        val (ada, _, bondId) = pairedBond()

        cancel(ada, bondId).status shouldBe 404
    }

    @Test
    fun `a change within thirty days is refused, and the detail names the date`() {
        val (ada, bea, bondId) = pairedBond()
        propose(ada, bondId, "Europe/London").status shouldBe 200
        confirm(bea, bondId).status shouldBe 200

        val response = propose(ada, bondId, "Asia/Tokyo")

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"TIMEZONE_CHANGE_TOO_SOON\""
        // A date rather than a countdown, for `states.md` §9's reason: a
        // countdown framed as a deadline is urgency, a date is a fact.
        response.contentAsString shouldContain "can change again from"
        // Nothing new is *open*: the confirmed row from the first change is
        // still there, which is the history, and the refusal recorded nothing.
        jdbc.queryForObject(
            "SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NULL AND cancelled_at IS NULL",
            Int::class.java,
        ) shouldBe 0
    }

    @Test
    fun `the thirty days are re-checked at confirmation, not only at proposal`() {
        // Up to seven days pass between proposing and confirming, so the window
        // can close while a proposal waits. Built by making the *bond* look as
        // though its zone moved yesterday, after the proposal was recorded.
        val (ada, bea, bondId) = pairedBond()
        propose(ada, bondId, "Europe/London").status shouldBe 200
        jdbc.update("UPDATE bonds SET timezone_changed_at = now() - interval '1 day'")

        val response = confirm(bea, bondId)

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"TIMEZONE_CHANGE_TOO_SOON\""
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NOT NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `a fixed-offset zone is 422 on the field a client would recognise`() {
        val (ada, _, bondId) = pairedBond()

        val response = propose(ada, bondId, "Etc/GMT+3")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"field\":\"anchorTimezone\""
    }

    @Test
    fun `a zone blank once trimmed is 422, whatever kind of space it is`() {
        val (ada, _, bondId) = pairedBond()

        // `@NotBlank` trims with Java's rules and `RegionZone.of` is reached
        // with Kotlin's, so U+00A0 passed the edge and threw deeper down
        // (ADR-0029 §13). `POST /bonds` had the same hole.
        for (blank in listOf("", " ", "\u00a0")) {
            val response = propose(ada, bondId, blank)

            response.status shouldBe 422
            response.contentAsString shouldContain "\"field\":\"anchorTimezone\""
        }
    }

    @Test
    fun `an archived bond refuses the proposal and the confirmation`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        leave(ada, bondId).status shouldBe 204

        propose(ada, bondId, "Europe/London").let {
            it.status shouldBe 409
            it.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        }
        confirm(ada, bondId).status shouldBe 409
        // Cancelling is allowed to be harmless: there is simply nothing there.
        cancel(ada, bondId).status shouldBe 404
    }

    @Test
    fun `a non-member gets the same 404 on all three routes`() {
        val (_, _, bondId) = pairedBond()
        val eve = users.verified("Eve")

        propose(eve, bondId, "Europe/London").status shouldBe 404
        confirm(eve, bondId).status shouldBe 404
        cancel(eve, bondId).status shouldBe 404
    }

    // ---- helpers ------------------------------------------------------------

    /** Ada creates, Bea joins: the ordinary two-member bond every consent rule is about. */
    private fun pairedBond(): Triple<UUID, UUID, String> {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        return Triple(ada, bea, bondId)
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun propose(
        userId: UUID,
        bondId: String,
        zone: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId/timezone") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"anchorTimezone":"$zone"}"""
            }.andReturn()
            .response

    private fun confirm(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/timezone/confirm") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun cancel(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/bonds/$bondId/timezone") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun leave(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{6})"""").find(response.contentAsString)!!.groupValues[1]
}
