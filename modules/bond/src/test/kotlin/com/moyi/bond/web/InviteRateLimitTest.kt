package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * Doc 06 §4's three invite buckets, on the real endpoints.
 *
 * The per-IP one is the control that matters: 30⁶ is about 7.3 × 10⁸ codes,
 * and doc 06 says plainly that "the limit is what makes it fine in practice"
 * (T-06). It is **shared between resolve and accept**, which is what that
 * table means by "combined" — an attacker who could spend twenty guesses on
 * each endpoint would have forty.
 *
 * Its own context with the limiter on; the shared one runs with it off (see
 * `src/test/resources/application.yml`), because the tests there create more
 * than ten invites between them and would otherwise depend on their order.
 */
@SpringBootTest(
    classes = [BondTestApplication::class],
    properties = ["moyi.security.rate-limit.enabled=true"],
)
@AutoConfigureMockMvc
internal class InviteRateLimitTest(
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
        // Through the container rather than a Redis client: this module has no
        // Redis dependency of its own and should not grow one for a test.
        redis.execInContainer("valkey-cli", "FLUSHALL").exitCode shouldBe 0
    }

    @Test
    fun `the eleventh invite a user creates in a day is 429, with Retry-After`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        // One came with the bond; ten more is the day's allowance.
        repeat(10) { createInvite(ada, bondId).status shouldBe 201 }

        val refused = createInvite(ada, bondId)

        refused.status shouldBe 429
        refused.contentType!!.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE) shouldBe true
        refused.contentAsString shouldContain "\"code\":\"RATE_LIMITED\""
        refused.getHeader(HttpHeaders.RETRY_AFTER)!!.toInt() shouldBe 8640
        refused.getHeader("X-RateLimit-Limit") shouldBe "10"
        refused.getHeader("X-RateLimit-Remaining") shouldBe "0"
    }

    @Test
    fun `the per-user create bucket is per user, not per bond`() {
        // Otherwise three bonds would buy thirty invites a day, and FR-025
        // permits three bonds.
        val ada = users.verified("Ada")
        val first = bondIdOf(createBond(ada))
        val second = bondIdOf(createBond(ada))

        repeat(10) { createInvite(ada, first).status shouldBe 201 }

        createInvite(ada, second).status shouldBe 429
    }

    @Test
    fun `resolve and accept share one per-IP bucket, which is what combined means`() {
        // Twenty an hour from an address, however the guesses are spread. An
        // attacker who could spend twenty on each endpoint would have forty.
        val ada = users.verified("Ada")
        val code = codeOf(createBond(ada))

        // Ten resolves by one caller empties that caller's own bucket, and
        // takes ten of the address's twenty.
        val guesser = users.verified("Guesser")
        repeat(10) { resolve(guesser, code).status shouldBe 200 }
        resolve(guesser, code).status shouldBe 429

        // A different account from the same address: its own per-user bucket
        // is full, and it gets the ten shared tokens that are left — on
        // *accept*, which proves the two endpoints draw from one bucket.
        val second = users.verified("Second")
        repeat(10) { accept(second, unusedCode(it)).status shouldBe 404 }

        // Twenty spent from this address. Nobody gets another guess of either
        // kind, whoever they are.
        accept(users.verified("Third"), code).status shouldBe 429
        resolve(users.verified("Fourth"), code).status shouldBe 429
    }

    @Test
    fun `a rate-limited invite endpoint still carries the headers when it succeeds`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = createInvite(ada, bondId)

        response.status shouldBe 201
        response.getHeader("X-RateLimit-Limit") shouldBe "10"
        response.getHeader("X-RateLimit-Remaining") shouldBe "9"
        (response.getHeader("X-RateLimit-Reset") != null) shouldBe true
    }

    // ---- helpers ------------------------------------------------------------

    /** A well-formed code that opens nothing — a guess, which is what the bucket exists to bound. */
    private fun unusedCode(seed: Int): String = InviteCode.random(java.util.Random(seed.toLong() + 1_000)).value

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
}
