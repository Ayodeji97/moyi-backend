package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * FR-060's acceptance clause, parameterised. A failure here is a P0 (doc 11).
 * C1 covers the statuses C1 can produce; C2 extends the matrix to REVEALED
 * and C3 to SOLO, and neither may narrow what is asserted here.
 *
 * `EntriesEndpointTest`'s own harness — a paired bond (`ada` and `bea`)
 * through the real `bond` endpoints [GratitudeTestApplication] scans for,
 * against a real Postgres, truncated between tests.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
internal class RevealGateTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        ada = users.verified("Ada")
        bea = users.verified("Bea")

        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a locked entry is exactly an author and a status, and nothing else`() {
        val beaSubmitted = submit(bea, bondId, """{"text":"a secret kindness"}""")
        beaSubmitted.status shouldBe 201
        val beaMemberId = authorMemberIdOf(beaSubmitted)

        val today = getToday(ada, bondId)

        // Scoped to the partner's own object, not the whole body (fix round
        // 1, M2) — a whole-body check for `createdAt`/`length`/`hasImage`/
        // `intendedAt` would also pass once `ada` has an entry of her own
        // carrying those very field names, for a reason unrelated to the
        // gate. This pins `partnerEntry`'s exact wire shape: nothing but the
        // author and the status, in that order, and nothing else — BR-8,
        // byte for byte.
        partnerEntryJson(today.contentAsString) shouldBe """{"authorMemberId":"$beaMemberId","status":"LOCKED"}"""
        // The text itself, body-wide: it must never appear anywhere in the
        // response, not only be absent from partnerEntry's own shape.
        today.contentAsString shouldNotContain "a secret kindness"
    }

    @Test
    fun `the day's status IS returned, because it is deliberately shared`() {
        // Doc 04 §6.1: a member who has not written can infer from PARTIAL that
        // their partner has, and J2's "Waiting for Tunde" depends on it. The
        // converse assertion matters as much as the one above — a later change
        // that hid the status to be "safe" would break the product.
        submit(bea, bondId, """{"text":"a secret kindness"}""").status shouldBe 201

        getToday(ada, bondId).contentAsString shouldContain "\"status\":\"PARTIAL\""
    }

    @Test
    fun `an author reads their own entry in full`() {
        submit(ada, bondId, """{"text":"thank you"}""").status shouldBe 201

        val today = getToday(ada, bondId)

        today.contentAsString shouldContain "\"myEntry\""
        today.contentAsString shouldContain "thank you"
    }

    @Test
    fun `priming today as one member does not serve it to the other`() {
        // Doc 12 names cache poisoning as a reveal-gate bypass no
        // authorisation layer sees. `bea`'s own GET below is the priming
        // call that would matter: her response carries her own words under
        // `myEntry` (BR-1's first clause — an author always reads their own
        // in full), so a naive `bondId`-keyed cache that served that same
        // response back to `ada` would leak exactly what test 1 above
        // proves the reveal gate refuses her — a failure test 1 alone would
        // never catch, since it never primes anything. C1 caches nothing;
        // this test is what makes adding one later a deliberate act rather
        // than an accident.
        submit(bea, bondId, """{"text":"a secret kindness"}""").status shouldBe 201
        getToday(bea, bondId).status shouldBe 200

        getToday(ada, bondId).contentAsString shouldNotContain "a secret kindness"
    }

    @Test
    fun `a day nobody has written to yet is OPEN with two absent entries`() {
        val today = getToday(ada, bondId)

        today.contentAsString shouldContain "\"status\":\"OPEN\""
        today.contentAsString shouldContain "\"myEntry\":null"
        today.contentAsString shouldContain "\"partnerEntry\":null"
    }

    /**
     * BR-3/BR-3a's own guard, from the read side: C3 — the row [GetToday]
     * reports without ever creating one. Asserted here rather than only
     * inferred from the response, because a passing `OPEN` status is
     * consistent with either a row that does not exist or one that does and
     * merely has no entries yet — this is the assertion that tells them
     * apart.
     */
    @Test
    fun `a day nobody has written to yet is never created by reading it`() {
        getToday(ada, bondId).status shouldBe 200

        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 0
    }

    /**
     * Fix round 1, C1: `membership.awaitingSecondMember` (doc 04 §8.3a, J1)
     * was in hand at `GetToday` and unused — a still-solo bond's `/today`
     * reported `OPEN`, indistinguishable from a paired bond nobody has
     * written in, in exactly the field BR-1's own contract says is returned
     * *so that* the two can be told apart. This is also the regression
     * proof for the status-travels-backwards bug that omission caused:
     * `SubmitEntry` opens a still-solo bond's day `SUSPENDED`, so a creator
     * who had just been told `OPEN` would watch it become `SUSPENDED` on
     * their own write — an edge the state machine does not have. Asserted
     * both before and after the creator's own write, so the status is shown
     * not to move.
     */
    @Test
    fun `a still-solo bond reports SUSPENDED before anybody has written, not OPEN`() {
        val cara = users.verified("Cara")
        val solo = createBond(cara)
        val soloBondId = bondIdOf(solo)

        getToday(cara, soloBondId).contentAsString shouldContain "\"status\":\"SUSPENDED\""

        submit(cara, soloBondId, """{"text":"waiting for you"}""").status shouldBe 201

        getToday(cara, soloBondId).contentAsString shouldContain "\"status\":\"SUSPENDED\""
    }

    // ---- helpers --------------------------------------------------------

    private fun submit(
        caller: UUID,
        bondId: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun getToday(
        caller: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
            }.andReturn()
            .response

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun authorMemberIdOf(response: MockHttpServletResponse): String =
        Regex(""""authorMemberId":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    /** `partnerEntry`'s own JSON object, scoped out of the whole response body — fix round 1, M2. */
    private fun partnerEntryJson(body: String): String = PARTNER_ENTRY.find(body)!!.groupValues[1]

    private companion object {
        val PARTNER_ENTRY = Regex(""""partnerEntry":(\{[^}]*})""")
    }
}
