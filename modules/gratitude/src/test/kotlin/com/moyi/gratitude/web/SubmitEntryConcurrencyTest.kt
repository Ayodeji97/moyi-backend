package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.BondDayStore
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **What this class proves.**
 *
 * `SubmitEntry` takes the bond's row lock before resolving a day (spec 2.1),
 * so two *members* never race to create a day: they queue on the bonds row.
 * The old two-members-at-once case therefore stopped being a race over the
 * index. The index is still load-bearing, because the close job takes no bond
 * lock (plan R1): a sweep opening a missing date can collide with a member
 * writing that same date. The first test drives exactly that.
 *
 * **Deterministic, not a barrier race.** The sweep's transaction inserts the
 * day and holds it uncommitted; the submission is then started and observed,
 * through `pg_blocking_pids`, queued behind the sweep on the index entry.
 * Only then does the sweep commit. Two ways it goes red without the index:
 * (i) index removed - `ON CONFLICT (bond_id, date)` has no matching
 * constraint, so `openOrGet` errors; the sweep fails and the test fails at
 * `opened.get` with the SQL cause. (ii) index removed and a bare
 * `ON CONFLICT DO NOTHING` - the insert silently duplicates, the submission is
 * never blocked and finishes, so the test fails at `isDone`, and the row count
 * and id assertions would catch it too.
 *
 * The second test pins an outcome, not a mechanism; see its own comment.
 *
 * The clock is pinned (fix round 2, N3) so the submission's date is the one
 * the sweep opens, far from any midnight.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryConcurrencyTest.TimeConfiguration::class)
internal class SubmitEntryConcurrencyTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val sweepRunner: SweepRunner,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun resetClock() {
        clock.set(NOW)
    }

    private val pool = Executors.newCachedThreadPool()

    @AfterEach
    fun clear() {
        // The pool first, and awaited: a submission still queued after a
        // failed assertion must not commit after the truncate.
        pool.shutdownNow()
        check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "a worker thread outlived the test" }
        jdbc.execute("TRUNCATE TABLE entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a lock-free opener holding a new day makes a submission queue on the index, then share its row`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        val date = NOW.atZone(LAGOS).toLocalDate()
        val window =
            DayWindow(
                date = date,
                startsAt = date.atStartOfDay(LAGOS).toInstant(),
                endsAt = date.plusDays(1).atStartOfDay(LAGOS).toInstant(),
            )
        val opened = CompletableFuture<Int>() // the sweep's pid, completed only after its INSERT ran
        val commit = CountDownLatch(1)
        try {
            // The close job's shape: open the day directly, no bond lock, and
            // stay in the transaction.
            val sweep: Future<UUID> =
                pool.submit<UUID> {
                    try {
                        sweepRunner.inTransaction { days ->
                            val day = days.openOrGet(UUID.fromString(bondId), window, LAGOS, NOW, BondDayStatus.OPEN)
                            opened.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                            check(commit.await(20, TimeUnit.SECONDS)) { "the sweep was never told to commit" }
                            day.id.value
                        }
                    } catch (e: Throwable) {
                        // A sweep that dies before holding the day must fail the test now, with its cause, not as a timeout.
                        opened.completeExceptionally(e)
                        throw e
                    }
                }
            val sweepPid = opened.get(10, TimeUnit.SECONDS)

            val submission = pool.submit<MockHttpServletResponse> { submit(ada, bondId, """{"text":"thank you"}""") }
            awaitBlockedOrDone(sweepPid, submission)
            // Remove `bond_days_bond_date_key` and the conflict target breaks,
            // failing earlier at `opened.get`. Remove it and also bare the
            // ON CONFLICT, and the submission has nothing to wait on: it writes
            // a second row, finishes, and this is where the test goes red.
            submission.isDone shouldBe false

            commit.countDown()
            val sweptId = sweep.get(10, TimeUnit.SECONDS)
            submission.get(10, TimeUnit.SECONDS).status shouldBe 201

            jdbc.queryForObject(
                "SELECT count(*) FROM bond_days WHERE bond_id = ?::uuid AND date = ?",
                Int::class.java,
                bondId,
                date,
            ) shouldBe
                1
            jdbc.queryForObject("SELECT id FROM bond_days WHERE bond_id = ?::uuid", UUID::class.java, bondId) shouldBe sweptId
            jdbc.queryForObject("SELECT entry_count FROM bond_days", Int::class.java) shouldBe 1
            jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 1
            jdbc.queryForObject("SELECT bond_day_id FROM entries", UUID::class.java) shouldBe sweptId
        } finally {
            commit.countDown()
        }
    }

    @Test
    fun `two members' simultaneous first entries - both 201, one day at entry_count 2`() {
        // An outcome test, not a proof of any one mechanism. It pins the
        // entry_count read-modify-write and the PARTIAL status when two members
        // write the day's first entries together. It survives removing the bond
        // lock or the day lock alone (the other lock plus the index still
        // serialise the writers), and it goes red without the index, because
        // the second writer's ON CONFLICT relies on it. The proof of the bond
        // lock is SubmitEntryBondLockTest.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        val statuses =
            inParallel(
                listOf(
                    { submit(ada, bondId, """{"text":"thank you"}""") },
                    { submit(bea, bondId, """{"text":"thank you too"}""") },
                ),
            ).map { it.status }

        statuses shouldContainExactlyInAnyOrder listOf(201, 201)
        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT entry_count FROM bond_days", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT status FROM bond_days", String::class.java) shouldBe "PARTIAL"
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 2
    }

    /** Runs every call on its own thread and releases them together — `InviteRaceTest`'s own helper. */
    private fun <T> inParallel(calls: List<() -> T>): List<T> =
        run {
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
        }

    /** Until [waiter] is either finished or genuinely queued behind [holderPid] — `SubmitEntryBondLockTest`'s own wait. */
    private fun awaitBlockedOrDone(
        holderPid: Int,
        waiter: Future<*>,
    ) {
        await().atMost(Duration.ofSeconds(10)).until {
            waiter.isDone ||
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Int::class.java,
                    holderPid,
                )!! > 0
        }
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

    /**
     * The close job's seat, as the test needs it: a [BondDayStore] and a
     * transaction boundary of the test's own choosing, bundled so the test
     * constructor stays under detekt's parameter ceiling (field injection is
     * banned by `ArchitectureTest`).
     */
    class SweepRunner(
        private val days: BondDayStore,
        private val transactions: PlatformTransactionManager,
    ) {
        fun <T : Any> inTransaction(block: (BondDayStore) -> T): T = TransactionTemplate(transactions).execute { block(days) }
    }

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        fun sweepRunner(
            days: BondDayStore,
            transactions: PlatformTransactionManager,
        ): SweepRunner = SweepRunner(days, transactions)

        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = NOW)
    }

    private companion object {
        private val LAGOS: ZoneId = ZoneId.of("Africa/Lagos")

        /** Midday UTC — midday-plus-one in Africa/Lagos, nowhere near a midnight boundary either side (fix round 2, N3). */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
    }
}
