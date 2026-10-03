package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The first link of spec §2.1's lock order — bond, then bond-day, then entry
 * — on the submit path: `SubmitEntry.submit` takes the `bonds` row lock
 * (`BondAccess.lockMembershipOf`) inside its own transaction, before it reads
 * anything, and holds it to commit.
 *
 * **The holder is raw JDBC, not a bond endpoint.** Every bond write that
 * matters here — leave, block, deletion, zone confirmation — takes this same
 * `SELECT … FOR UPDATE` on the bonds row first and commits within the request,
 * so none of them can be paused mid-transaction from a test. A plain
 * connection issuing the identical statement is that write, frozen at the
 * moment it holds the lock. Blocking is observed through
 * `pg_blocking_pids`, never inferred from a sleep: `BondAccessLockingTest`'s
 * own technique, aimed one layer up.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
internal class SubmitEntryBondLockTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        clock.set(NOW)
        ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
    }

    @AfterEach
    fun clear() {
        // The pool first: a submission still queued must not race the truncate.
        pool.shutdownNow()
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a submission waits behind a transaction holding the bond's row lock`() {
        // Delete `lockMembershipOf` from SubmitEntry (or read the membership
        // with the unlocked `membershipOf`) and nothing on the submit path
        // touches the bonds row with FOR UPDATE: the submission completes at
        // once, never appears in pg_blocking_pids, and `isDone` below is true.
        withBondLockHeld { holderPid, release ->
            val submission = async { submit(ada, """{"text":"thank you"}""") }

            awaitBlockedOrDone(holderPid, submission)
            submission.isDone shouldBe false

            release {}
            submission.get(10, TimeUnit.SECONDS).status shouldBe 201
        }
    }

    @Test
    fun `a bond that ends while a submission waits for its lock refuses the entry`() {
        // The harm the lock exists to prevent (spec §2.1): the old shape read
        // membership outside the transaction, checked `isOpen` on that copy,
        // and could commit an entry after the bond had ended. Here the bond
        // ends inside the very transaction the submission is queued behind;
        // the submission must see the ended bond, not the one it would have
        // read before it queued.
        withBondLockHeld { holderPid, release ->
            val submission = async { submit(ada, """{"text":"too late"}""") }
            awaitBlockedOrDone(holderPid, submission)
            submission.isDone shouldBe false

            release { connection ->
                connection.prepareStatement("UPDATE bonds SET status = 'ARCHIVED', archived_at = now() WHERE id = ?::uuid").use {
                    it.setString(1, bondId)
                    it.executeUpdate()
                }
            }

            val response = submission.get(10, TimeUnit.SECONDS)
            response.status shouldBe 409
            response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
            jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 0
            jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 0
        }
    }

    @Test
    fun `today is a read and never queues behind the bond's row lock`() {
        // Amendment 4: GetToday keeps the unlocked `membershipOf`. Move it onto
        // `lockMembershipOf` (or give it a `lockBond`) and this read appears in
        // pg_blocking_pids behind the holder, so `isDone` below is false.
        withBondLockHeld { holderPid, release ->
            val read = async { today(ada) }

            awaitBlockedOrDone(holderPid, read)
            read.isDone shouldBe true
            read.get(1, TimeUnit.SECONDS).status shouldBe 200

            release {}
        }
    }

    // ---- the lock holder ------------------------------------------------

    private val pool = Executors.newCachedThreadPool()

    private fun <T> async(call: () -> T): Future<T> = pool.submit<T> { call() }

    /**
     * Opens a transaction on its own connection, takes `bondRepository.lockRow`'s
     * exact statement on [bondId], and hands [block] the holder's backend pid
     * and a `release` that runs an optional last statement under the lock and
     * then commits. Rolled back if [block] never released it.
     */
    private fun withBondLockHeld(block: (holderPid: Int, release: ((Connection) -> Unit) -> Unit) -> Unit) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                lockBondRow(connection)
                block(backendPidOf(connection)) { last ->
                    last(connection)
                    connection.commit()
                }
            } finally {
                // A no-op after `release` committed; otherwise it is what
                // frees a submission still queued behind a failed assertion.
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private fun lockBondRow(connection: Connection) {
        connection.prepareStatement("SELECT 1 FROM bonds WHERE id = ?::uuid FOR UPDATE").use {
            it.setString(1, bondId)
            it.executeQuery().use { rows -> rows.next() shouldBe true }
        }
    }

    private fun backendPidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /** Until [waiter] is either finished or genuinely queued behind [holderPid] — `BondAccessLockingTest`'s own wait. */
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

    private fun today(caller: UUID): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}") }
            .andReturn()
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

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = NOW)
    }

    private companion object {
        /** Midday-plus-one in Africa/Lagos, nowhere near a day boundary. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
    }
}
