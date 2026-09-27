package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeIn
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
 * What the bond's row lock in `EndBond` is for: **nobody joins a bond as it is
 * being ended.**
 *
 * Without the lock an accept and a leave are two transactions over READ
 * COMMITTED snapshots. The accept sees `PENDING_MEMBER` with a free seat, the
 * leave sees an open bond, and both commit — leaving somebody an active member
 * of an archived bond, which is a person who joined something that no longer
 * exists. No amount of reading the code reveals that; it is the same lesson as
 * PR #32's refresh-token family and B2's two concurrent invites, and the same
 * conclusion: fixing a race once does not fix its class.
 *
 * Real threads through the real HTTP stack, released together by a latch — the
 * shape `InviteRaceTest` and `RefreshTokenPersistenceTest` use.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class EndBondRaceTest(
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
    fun `a leave and an accept at the same moment cannot both win`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        val code = codeOf(created)
        val joiner = users.verified("Joiner")

        val responses = inParallel(listOf({ leave(ada, bondId) }, { accept(joiner, code) }))
        val leaving = responses.first().status
        val joining = responses.last().status

        // Either the accept got in first (200, and the leave then archives a
        // bond of two) or the leave did (204, and the code is dead: the one 404).
        leaving shouldBe 204
        joining shouldBeIn listOf(200, 404)
        jdbc.queryForObject("SELECT status FROM bonds", String::class.java) shouldBe "ARCHIVED"
        val active = jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE left_at IS NULL", Int::class.java)!!
        // The one outcome that must never happen: the joiner was refused and is
        // nonetheless in the bond, or was admitted to a bond already closed.
        if (joining == 404) {
            active shouldBe 0
            jdbc.queryForObject("SELECT count(*) FROM bond_members", Int::class.java) shouldBe 1
        } else {
            active shouldBe 1
            jdbc.queryForObject("SELECT count(*) FROM bond_members", Int::class.java) shouldBe 2
        }
    }

    @Test
    fun `both members ending it at the same moment archive it once, and the loser is told`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        val statuses = inParallel(listOf({ leave(ada, bondId) }, { block(bea, bondId) })).map { it.status }
        val leaving = statuses.first()
        val blocking = statuses.last()

        // The two serialise on the row lock, and which of them gets there first
        // decides one thing only: whether the *leave* succeeds. The block is
        // accepted either way — that is FR-029, and the reason it is.
        blocking shouldBe 204
        leaving shouldBeIn listOf(204, 409)
        jdbc.queryForObject("SELECT count(*) FROM bonds WHERE archived_at IS NOT NULL", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1

        // **`left_at` is only ever stamped by ending a bond that is still open**,
        // and only for the caller. So exactly one member is left unstamped in
        // either ordering: whichever request arrived second found the bond
        // already archived, and neither path touches membership there — the
        // leave because it is refused, the block because stamping would tell the
        // other member they were blocked (doc 26 §2.1).
        //
        // No slot is held by that row: FR-025 counts memberships in *open*
        // bonds. Worth asserting rather than discovering — the first version of
        // this test expected zero, which held only in the ordering my machine
        // produced, and CI failed it.
        jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE left_at IS NULL", Int::class.java) shouldBe 1
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
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun leave(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun block(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/block") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]
}
