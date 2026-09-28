package com.moyi.bond.web

import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **The lost update, which is the whole reason doc 06 §1 requires `If-Match`.**
 *
 * Two members open the settings screen, both change something, and the second
 * write must not erase the first in silence. The `If-Match` check alone cannot
 * promise that: comparing a version and then writing is a read-check-write, and
 * over a READ COMMITTED snapshot both callers can read version 0, both be
 * satisfied, and both commit. So `UpdateBond` takes the bond's row lock first,
 * and this is the test that fails if somebody removes it.
 *
 * It is also the test that showed `@Version` does **not** cover this path: the
 * store re-reads the row inside the transaction, so Hibernate's
 * `WHERE version = ?` always carries the current value and has nothing to catch
 * (ADR-0029, `BondPersistenceTest`).
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondSettingsRaceTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `two patches with the same ETag - exactly one wins`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val statuses =
            inParallel(
                listOf(
                    { patch(ada, bondId, """{"name":"First"}""", "\"0\"") },
                    { patch(ada, bondId, """{"name":"Second"}""", "\"0\"") },
                ),
            ).map { it.status }

        statuses shouldContainExactlyInAnyOrder listOf(200, 412)
        // One write, one version bump — not two of either.
        jdbc.queryForObject("SELECT version FROM bonds", Int::class.java) shouldBe 1
        // And the winner's value is intact rather than a mixture of the two.
        jdbc.queryForObject("SELECT name FROM bonds", String::class.java) shouldBeIn listOf("First", "Second")
    }

    @Test
    fun `eight patches on one ETag - still exactly one wins`() {
        // Two threads can agree by luck. Eight contending on one row is where a
        // lock that is merely *usually* taken stops looking correct — the same
        // escalation `InviteRaceTest` uses on accept.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val statuses = inParallel((1..8).map { n -> { patch(ada, bondId, """{"name":"Name $n"}""", "\"0\"") } }).map { it.status }

        statuses.count { it == 200 } shouldBe 1
        statuses.count { it == 412 } shouldBe 7
        jdbc.queryForObject("SELECT version FROM bonds", Int::class.java) shouldBe 1
    }

    @Test
    fun `a patch and a member settings write both succeed without invalidating the condition`() {
        // Both take the lifecycle lock, but settings only change the member
        // row, so they do not invalidate the patch's bond version.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val statuses =
            inParallel(
                listOf(
                    { patch(ada, bondId, """{"name":"First"}""", "\"0\"") },
                    { putSettings(ada, bondId, """{"reminderTimeLocal":"07:30"}""") },
                ),
            ).map { it.status }

        statuses shouldContainExactlyInAnyOrder listOf(200, 200)
        jdbc.queryForObject("SELECT version FROM bonds", Int::class.java) shouldBe 1
    }

    @Test
    fun `settings wait for an ending transaction and cannot erase its membership timestamp`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val saving =
                transactions.execute {
                    // Hold exactly the rows EndBond changes before it commits. The
                    // guard can still read their previous values under MVCC.
                    val blocker = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                    jdbc.update(
                        "UPDATE bonds SET status = 'ARCHIVED', archived_at = now(), version = version + 1 WHERE id = ?",
                        UUID.fromString(bondId),
                    )
                    jdbc.update("UPDATE bond_members SET left_at = now() WHERE bond_id = ?", UUID.fromString(bondId))
                    val request =
                        pool.submit<MockHttpServletResponse> {
                            putSettings(ada, bondId, """{"reminderTimeLocal":"07:30"}""")
                        }
                    // Wait until the PUT actually reaches a contended row. Without
                    // the bond lock it waits on the member UPDATE instead, then
                    // writes its stale leftAt after this transaction commits.
                    await().atMost(Duration.ofSeconds(10)).until {
                        request.isDone || jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                            Int::class.java,
                            blocker,
                        )!! > 0
                    }
                    request
                }!!
            saving.get(10, TimeUnit.SECONDS).status shouldBe 409
            jdbc.queryForObject("SELECT left_at IS NOT NULL FROM bond_members", Boolean::class.java) shouldBe true
            jdbc.queryForObject("SELECT reminder_time_local::text FROM bond_members", String::class.java) shouldBe "20:00:00"
        } finally {
            pool.shutdownNow()
        }
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

    private fun patch(
        userId: UUID,
        bondId: String,
        body: String,
        ifMatch: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                header(HttpHeaders.IF_MATCH, ifMatch)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun putSettings(
        userId: UUID,
        bondId: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/bonds/$bondId/members/me/settings") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]
}
