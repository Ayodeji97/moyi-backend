package com.moyi.bond.web

import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeIn
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Consent under contention.
 *
 * Two people are looking at the same pending change, and whatever they do at the
 * same moment must add up: a zone moves once or not at all, one proposal is open
 * at a time, and nothing is applied to a bond that has ended. The compare-and-set
 * on `confirm` and the bond's row lock are what make that true, and these are the
 * tests that fail without them.
 *
 * Each race is run a few times: B3's lesson is that a narrow window needs more
 * than one attempt before it means anything.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class ProposalRaceTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private companion object {
        /** A few attempts rather than one: cheap, and more chances at a narrow window. */
        const val RACES = 4
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `two proposals at the same moment leave exactly one open`() {
        repeat(RACES) { run ->
            val (ada, bea, bondId) = pairedBond(run)

            val statuses =
                inParallel(
                    listOf(
                        { propose(ada, bondId, "Europe/London") },
                        { propose(bea, bondId, "Asia/Tokyo") },
                    ),
                ).map { it.status }

            withClue("run $run: $statuses") {
                // One records it; the other finds it already there. Both are
                // legitimate answers and neither is a 500.
                statuses shouldContainExactlyInAnyOrder listOf(200, 409)
                jdbc.queryForObject(
                    "SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NULL AND cancelled_at IS NULL",
                    Int::class.java,
                ) shouldBe 1
            }
            clear()
        }
    }

    @Test
    fun `a confirm and a cancel at the same moment - one wins and the other finds nothing`() {
        repeat(RACES) { run ->
            val (ada, bea, bondId) = pairedBond(run)
            propose(ada, bondId, "Europe/London").status shouldBe 200

            val statuses =
                inParallel(listOf({ confirm(bea, bondId) }, { cancelTimezone(ada, bondId) })).map { it.status }

            withClue("run $run: confirm ${statuses.first()}, cancel ${statuses.last()}") {
                // Whichever lost is told there is nothing waiting, which is true
                // by the time it looked.
                statuses.first() shouldBeIn listOf(200, 404)
                statuses.last() shouldBeIn listOf(204, 404)
                (statuses.first() == 200) shouldBe (statuses.last() == 404)
                // The zone moved if and only if the confirmation won.
                val moved = jdbc.queryForObject("SELECT anchor_timezone FROM bonds", String::class.java)
                moved shouldBe if (statuses.first() == 200) "Europe/London" else "Africa/Lagos"
                jdbc.queryForObject(
                    "SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NULL AND cancelled_at IS NULL",
                    Int::class.java,
                ) shouldBe 0
            }
            clear()
        }
    }

    @Test
    fun `a confirm and a leave at the same moment never move the zone of an ended bond`() {
        repeat(RACES) { run ->
            val (ada, bea, bondId) = pairedBond(run)
            propose(ada, bondId, "Europe/London").status shouldBe 200

            val statuses = inParallel(listOf({ confirm(bea, bondId) }, { leave(ada, bondId) })).map { it.status }

            withClue("run $run: confirm ${statuses.first()}, leave ${statuses.last()}") {
                statuses.last() shouldBe 204
                // Either the confirmation got in first (200) or it found the bond
                // archived (409) or the proposal already cancelled by the ending
                // (404). What it must never be is a zone moved on a bond that has
                // ended.
                statuses.first() shouldBeIn listOf(200, 404, 409)
                val archived = jdbc.queryForObject("SELECT status FROM bonds", String::class.java)
                archived shouldBe "ARCHIVED"
                if (statuses.first() != 200) {
                    jdbc.queryForObject("SELECT anchor_timezone FROM bonds", String::class.java) shouldBe "Africa/Lagos"
                }
                jdbc.queryForObject(
                    "SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NULL AND cancelled_at IS NULL",
                    Int::class.java,
                ) shouldBe 0
            }
            clear()
        }
    }

    @Test
    fun `both members asking for deletion at the same moment start one cooling-off`() {
        repeat(RACES) { run ->
            val (ada, bea, bondId) = pairedBond(run)

            val statuses = inParallel(listOf({ requestDeletion(ada, bondId) }, { requestDeletion(bea, bondId) })).map { it.status }

            withClue("run $run: $statuses") {
                statuses shouldContainExactlyInAnyOrder listOf(202, 202)
                // Either they serialised into ask-then-agree (PENDING_DELETION,
                // one confirmed proposal) or one of them wrote first and the
                // other found its own request already there (ACTIVE, one open
                // proposal). Never two proposals, and never a half-applied state.
                val status = jdbc.queryForObject("SELECT status FROM bonds", String::class.java)
                status shouldBeIn listOf("ACTIVE", "PENDING_DELETION")
                jdbc.queryForObject("SELECT count(*) FROM bond_proposals", Int::class.java) shouldBe 1
                val confirmed =
                    jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NOT NULL", Int::class.java)!!
                confirmed shouldBe if (status == "PENDING_DELETION") 1 else 0
            }
            clear()
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

    private fun pairedBond(run: Int): Triple<UUID, UUID, String> {
        val ada = users.verified("Ada $run")
        val bea = users.verified("Bea $run")
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

    private fun cancelTimezone(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/bonds/$bondId/timezone") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun requestDeletion(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

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
