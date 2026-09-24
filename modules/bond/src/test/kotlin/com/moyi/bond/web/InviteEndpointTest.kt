package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * The four invite endpoints (doc 06 §3.3), through the real chain against a
 * real Postgres. This is milestone M2: two accounts pair, and no third can
 * see anything.
 *
 * The refusals are the interesting half, and they divide on one line. A fact
 * about the **caller's own account** may be named — unverified, already a
 * member, at the three-bond limit — because they can already discover it.
 * Anything about a **bond or a code** is the one 404, because a difference
 * between "expired" and "never existed" tells a stranger that a bond is real
 * (FR-024). `InviteOneAnswerTest` is where that is proved exhaustively.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class InviteEndpointTest(
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

    // ---- creating and revoking, behind the guard ----------------------------

    @Test
    fun `a member creates a new invite, and the outstanding one stops working`() {
        // states.md §2 "Replaced": there is never more than one live code, and
        // a creator who does not know that would assume the old one still works.
        val ada = users.verified("Ada")
        val bond = createBond(ada)
        val first = codeOf(bond)

        val response = createInvite(ada, bondIdOf(bond))

        response.status shouldBe 201
        val second = codeOf(response)
        second shouldNotBe first
        response.contentAsString shouldContain "\"link\":\"https://moyi.test/i/$second\""

        resolve(users.verified("Bob"), first).status shouldBe 404
        // And the bond now shows the new one.
        get(ada, bondIdOf(bond)).contentAsString shouldContain "\"code\":\"$second\""
    }

    @Test
    fun `creating an invite for a full bond is 409 BOND_FULL`() {
        val ada = users.verified("Ada")
        val bob = users.verified("Bob")
        val bond = createBond(ada)
        accept(bob, codeOf(bond)).status shouldBe 200

        val response = createInvite(ada, bondIdOf(bond))

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_FULL\""
    }

    @Test
    fun `creating an invite for an archived bond is 409 BOND_ARCHIVED`() {
        val ada = users.verified("Ada")
        val bond = createBond(ada)
        jdbc.update("UPDATE bonds SET status = 'ARCHIVED', archived_at = now() WHERE id = ?::uuid", bondIdOf(bond))

        val response = createInvite(ada, bondIdOf(bond))

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `a non-member cannot create an invite, and is told the bond does not exist`() {
        val ada = users.verified("Ada")
        val eve = users.verified("Eve")
        val bond = createBond(ada)

        val response = createInvite(eve, bondIdOf(bond))

        response.status shouldBe 404
        response.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
    }

    @Test
    fun `revoking is 204, and the code then resolves to the one 404`() {
        val ada = users.verified("Ada")
        val bond = createBond(ada)
        val code = codeOf(bond)
        val inviteId = inviteIdOf(bond)

        revokeInvite(ada, bondIdOf(bond), inviteId).status shouldBe 204

        resolve(users.verified("Bob"), code).status shouldBe 404
        // And the bond no longer advertises one.
        get(ada, bondIdOf(bond)).contentAsString shouldContain "\"invite\":null"
    }

    @Test
    fun `revoking twice, another bond's invite, or an invented id is 404 either way`() {
        val ada = users.verified("Ada")
        val bond = createBond(ada)
        val other = createBond(ada)

        revokeInvite(ada, bondIdOf(bond), inviteIdOf(bond)).status shouldBe 204
        revokeInvite(ada, bondIdOf(bond), inviteIdOf(bond)).status shouldBe 404
        // The id is real, and it is not this bond's — the predicate is the authorisation.
        revokeInvite(ada, bondIdOf(bond), inviteIdOf(other)).status shouldBe 404
        revokeInvite(ada, bondIdOf(bond), UUID.randomUUID().toString()).status shouldBe 404
        revokeInvite(ada, bondIdOf(bond), "not-an-id").status shouldBe 404
    }

    // ---- resolving and accepting --------------------------------------------

    @Test
    fun `M2 - two accounts pair, and the bond is then active with two members`() {
        val ada = users.verified("Ada")
        val bob = users.verified("Bob")
        val bond = createBond(ada, name = "Us")
        val code = codeOf(bond)

        // Bob sees who he would be joining before anything is spent
        // (states.md §2: accepting is destructive of the invite).
        val preview = resolve(bob, code)
        preview.status shouldBe 200
        preview.contentAsString shouldContain "\"bondName\":\"Us\""
        preview.contentAsString shouldContain "\"inviterDisplayName\":\"Ada\""
        preview.contentAsString shouldContain "\"bondType\":\"COUPLE\""

        val joined = accept(bob, code)

        joined.status shouldBe 200
        joined.contentAsString shouldContain "\"status\":\"ACTIVE\""
        joined.contentAsString shouldContain "\"displayName\":\"Ada\""
        joined.contentAsString shouldContain "\"displayName\":\"Bob\""
        joined.contentAsString shouldContain "\"invite\":null"

        // Both sides see the same bond, and the code is spent.
        get(ada, bondIdOf(bond)).contentAsString shouldContain "\"status\":\"ACTIVE\""
        resolve(users.verified("Eve"), code).status shouldBe 404
        accept(users.verified("Carol"), code).status shouldBe 404
        jdbc.queryForObject("SELECT count(*) FROM bond_members", Int::class.java) shouldBe 2
    }

    @Test
    fun `an unverified account may not accept`() {
        // FR-002, and the same answer as create: their own state, so it is safe to name.
        val bond = createBond(users.verified("Ada"))

        val response = accept(users.unverified("Bob"), codeOf(bond))

        response.status shouldBe 403
        response.contentAsString shouldContain "\"code\":\"EMAIL_NOT_VERIFIED\""
    }

    @Test
    fun `the creator scanning their own code is 409 ALREADY_MEMBER`() {
        val ada = users.verified("Ada")
        val bond = createBond(ada)

        val response = accept(ada, codeOf(bond))

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"ALREADY_MEMBER\""
        // And the code is not spent by the attempt.
        accept(users.verified("Bob"), codeOf(bond)).status shouldBe 200
    }

    @Test
    fun `someone already in three bonds is 409 BOND_LIMIT_REACHED`() {
        val ada = users.verified("Ada")
        val bob = users.verified("Bob")
        repeat(3) { createBond(bob) }
        val bond = createBond(ada)

        val response = accept(bob, codeOf(bond))

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_LIMIT_REACHED\""
        // Refused *before* the code was spent, so it still works for someone else.
        accept(users.verified("Carol"), codeOf(bond)).status shouldBe 200
    }

    @Test
    fun `a malformed code is 422 naming the field, and never echoes what was typed`() {
        // A fact about what the caller typed, not about any bond — so it is
        // not the 404, and the client can put the message under the field.
        val ada = users.verified("Ada")

        for (bad in listOf("ABC", "ABCDEFG", "ABC0DE")) {
            val response = resolve(ada, bad)
            response.status shouldBe 422
            response.contentAsString shouldContain "\"field\":\"code\""
            // `instance` is the path the caller typed, and that is theirs to
            // see — the rule doc 18 §5 sets is that the *sentences* must not
            // repeat the input, which is what a log or a screen would carry.
            // (The same correction slice H's 404 body needed.)
            response.contentAsString.withoutInstance() shouldNotContain bad
        }
        accept(ada, "ABC0DE").status shouldBe 422
    }

    @Test
    fun `a lowercase code works, because people paste and retype`() {
        val ada = users.verified("Ada")
        val bob = users.verified("Bob")
        val bond = createBond(ada)

        resolve(bob, codeOf(bond).lowercase()).status shouldBe 200
        accept(bob, codeOf(bond).lowercase()).status shouldBe 200
    }

    @Test
    fun `neither code endpoint is reachable without a token`() {
        // T-06: an unauthenticated resolve is an oracle that returns a real
        // person's name on a hit (doc 06 §3.3).
        val bond = createBond(users.verified("Ada"))
        val code = codeOf(bond)

        mockMvc
            .get("/api/v1/invites/$code")
            .andReturn()
            .response.status shouldBe 401
        mockMvc
            .post("/api/v1/invites/$code/accept")
            .andReturn()
            .response.status shouldBe 401
    }

    @Test
    fun `a blocked pair cannot be re-invited, in either direction`() {
        // FR-029: blocking prevents any future invitation between two
        // accounts, and it must not matter which of them holds the code.
        val ada = users.verified("Ada")
        val bob = users.verified("Bob")
        val bond = createBond(ada)
        jdbc.update(
            "INSERT INTO blocks (id, blocker_user_id, blocked_user_id, bond_id, created_at) VALUES (?, ?, ?, ?::uuid, now())",
            UUID.randomUUID(),
            bob,
            ada,
            bondIdOf(bond),
        )

        accept(bob, codeOf(bond)).status shouldBe 404
        resolve(bob, codeOf(bond)).status shouldBe 200
    }

    // ---- helpers ------------------------------------------------------------

    private fun createBond(
        userId: UUID,
        name: String = "Us",
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"$name","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun createInvite(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/invites") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
            }.andReturn()
            .response

    private fun revokeInvite(
        userId: UUID,
        bondId: String,
        inviteId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/bonds/$bondId/invites/$inviteId") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
            }.andReturn()
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

    private fun get(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]

    private fun inviteIdOf(response: MockHttpServletResponse): String =
        jdbc
            .queryForObject(
                "SELECT id FROM bond_invites WHERE code = ?",
                UUID::class.java,
                codeOf(response),
            )!!
            .toString()
}
