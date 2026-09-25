package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The failure this slice exists to make impossible: **two people in a bond
 * meant for two, plus the person who started it.**
 *
 * A link is not recipient-safe (`states.md` §2: whoever opens it first claims
 * the bond), so two people genuinely can present one code at the same
 * instant. Everything about that moment is decided by two statements — the
 * `SELECT … FOR UPDATE` on the bond row, and the compare-and-set on the
 * invite — and neither can be shown to work by reading it.
 *
 * Real threads through the real HTTP stack, released together by a latch.
 * The two-transaction shape `RefreshTokenPersistenceTest` uses for the same
 * kind of proof.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class InviteRaceTest(
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

    @Test
    fun `two people accepting one code at the same moment - exactly one joins`() {
        val ada = users.verified("Ada")
        val code = codeOf(createBond(ada))
        val first = users.verified("First")
        val second = users.verified("Second")

        val statuses = inParallel(listOf({ accept(first, code) }, { accept(second, code) })).map { it.status }

        statuses shouldContainExactlyInAnyOrder listOf(200, 404)
        // The loser is told what the winner's success made true: the code is
        // used. Not "you lost a race" — the same 404 as any spent code.
        jdbc.queryForObject("SELECT count(*) FROM bond_members", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT count(*) FROM bond_invites WHERE used_at IS NOT NULL", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT status FROM bonds", String::class.java) shouldBe "ACTIVE"
    }

    @Test
    fun `eight people accepting one code at the same moment - still exactly one joins`() {
        // Two threads can agree by luck. Eight contending on one row is where
        // a lock that is merely *usually* taken stops looking correct.
        val ada = users.verified("Ada")
        val code = codeOf(createBond(ada))
        val joiners = (1..8).map { users.verified("Joiner $it") }

        val statuses = inParallel(joiners.map { joiner -> { accept(joiner, code) } }).map { it.status }

        statuses.count { it == 200 } shouldBe 1
        statuses.count { it == 404 } shouldBe 7
        jdbc.queryForObject("SELECT count(*) FROM bond_members", Int::class.java) shouldBe 2
    }

    @Test
    fun `two creates at the same moment leave exactly one live invite`() {
        // "Creating one revokes the outstanding one" has to hold when the two
        // creates overlap, or a bond ends up with two codes that both work —
        // and states.md §2 promises a member there is only ever one.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val statuses = inParallel(List(2) { { createInvite(ada, bondId) } }).map { it.status }

        statuses shouldContainExactlyInAnyOrder listOf(201, 201)
        jdbc.queryForObject(
            "SELECT count(*) FROM bond_invites WHERE revoked_at IS NULL AND used_at IS NULL",
            Int::class.java,
        ) shouldBe 1
    }

    /** Runs every call on its own thread and releases them together. */
    private fun <T> inParallel(calls: List<() -> T>): List<T> {
        val pool = Executors.newFixedThreadPool(calls.size)
        return try {
            val ready = CountDownLatch(calls.size)
            val go = CountDownLatch(1)
            val futures =
                calls.map { call ->
                    pool.submit(
                        Callable {
                            ready.countDown()
                            go.await(10, TimeUnit.SECONDS)
                            call()
                        },
                    )
                }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
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
