package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * **FR-024, proved exhaustively.** Six different reasons a code does not
 * work, and one indistinguishable response for all of them.
 *
 * `states.md` §2 puts it as "one screen, one string, four causes" and says
 * why the collapse is required rather than tidy: *"any visible difference
 * between these leaks information"*. A `410 Gone` for an expired code
 * confirms the code was once real. A distinct message for a full bond
 * confirms the bond exists and that someone else got there. And a special
 * answer for a blocked pair tells the blocked party they were identified as
 * a threat, which doc 26 §2.1 calls the moment retaliation risk peaks.
 *
 * So this test does not check six statuses. It checks that six responses are
 * **the same bytes**, `instance` aside — and `instance` is the path the
 * caller typed, which is theirs.
 *
 * If a later slice adds a seventh way for a code to fail, it belongs in the
 * list below.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class InviteOneAnswerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    /** One person trying every code, because the blocked case is about *this* joiner. */
    private val joiner: UUID by lazy { users.verified("Joiner") }

    @Test
    fun `every way a code can fail is one response, on resolve and on accept alike`() {
        val cases = unusableCodes()

        val resolved = cases.mapValues { (_, code) -> resolve(joiner, code) }
        val accepted = cases.mapValues { (_, code) -> accept(joiner, code) }

        (resolved + accepted.mapKeys { "${it.key} (accept)" }).forEach { (cause, response) ->
            withClue(cause) {
                response.status shouldBe 404
                response.contentType!!.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE) shouldBe true
            }
        }

        // The bodies, normalised only for the path the caller typed.
        val resolveBodies = resolved.mapValues { it.value.contentAsString.withoutInstance() }
        val acceptBodies = accepted.mapValues { it.value.contentAsString.withoutInstance() }
        val reference = resolveBodies.getValue("a code that never existed")

        (resolveBodies + acceptBodies.mapKeys { "${it.key} (accept)" }).forEach { (cause, body) ->
            withClue("$cause must be indistinguishable from a code that never existed") { body shouldBe reference }
        }
    }

    /**
     * One live-looking code per cause, each genuinely in that state rather
     * than simulated: the point is that the *system* cannot tell the caller
     * them apart, and a stubbed case would not prove it.
     */
    private fun unusableCodes(): Map<String, String> {
        // A creator each, because FR-025 caps one account at three open bonds
        // — which is the limit doing its job, and would otherwise make the
        // fourth case here a 409 instead of the state it is meant to set up.
        val expired = codeOf(createBond(users.verified("Ada")))
        jdbc.update("UPDATE bond_invites SET expires_at = now() - interval '1 day' WHERE code = ?", expired)

        val revoked = codeOf(createBond(users.verified("Bea")))
        jdbc.update("UPDATE bond_invites SET revoked_at = now() WHERE code = ?", revoked)

        val used = codeOf(createBond(users.verified("Cal")))
        accept(users.verified("Someone"), used).status shouldBe 200

        // A bond whose seats are taken, holding a code that is still live.
        //
        // **Unreachable through the API, and that is the point.** Creating an
        // invite on a full bond is refused, and accepting one spends it, so
        // the two states cannot coexist by any route a client can take. The
        // check in `AcceptInvite` is defence in depth against a path that does
        // not exist yet — a re-invite after someone leaves (slice B3), or a
        // future bond type with more seats — so the state is built here with
        // SQL rather than pretended.
        val owner = users.verified("Dee")
        val fullBond = createBond(owner)
        val stillLive = codeOf(fullBond)
        val spent = codeOf(createInvite(owner, bondIdOf(fullBond)))
        accept(users.verified("First"), spent).status shouldBe 200
        jdbc.update("UPDATE bond_invites SET revoked_at = NULL WHERE code = ?", stillLive)

        val blocker = users.verified("Eve")
        val blockedBond = createBond(blocker)
        val blocked = codeOf(blockedBond)
        jdbc.update(
            "INSERT INTO blocks (id, blocker_user_id, blocked_user_id, bond_id, created_at) VALUES (?, ?, ?, ?::uuid, now())",
            UUID.randomUUID(),
            blocker,
            joiner,
            bondIdOf(blockedBond),
        )

        return mapOf(
            "an expired code" to expired,
            "a revoked code" to revoked,
            "a code already used" to used,
            "a live code for a bond that is now full" to stillLive,
            "a code that never existed" to InviteCode.random(java.util.Random(7)).value,
            "a code for a bond the caller is blocked from" to blocked,
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun createInvite(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/invites") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun resolve(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/invites/$code") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]

    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")
}
