package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Fix round 1, I3: two members writing on the same Bond-day at the same
 * moment is not a race to guard against, it is the ordinary case this
 * product exists for — a couple opening the app together in the evening.
 * `SubmitEntry.submit` locks the day's row before reading `entryCount`/
 * `status` for the update (`BondDayStore.lockAndFind`); this is the proof
 * `bond.web.InviteRaceTest` gives for its own row lock, aimed at this one.
 *
 * Real threads through the real HTTP stack, released together by a latch —
 * `InviteRaceTest`'s own `inParallel`, copied for the reason `FakeUserDirectory`'s
 * own KDoc gives across this module boundary.
 *
 * **The clock is pinned** (fix round 2, N3): without [TimeConfiguration],
 * [openDay] computes `LocalDate.now(Africa/Lagos)` against the real system
 * clock and the server computes its own, independently, at whatever instant
 * `SubmitEntry` actually runs — two separate reads of "now" that agree every
 * day except the one day a year this test would run within a few
 * milliseconds of Lagos midnight, where they could disagree and
 * `count(*) FROM bond_days shouldBe 1` would fail for a reason that has
 * nothing to do with the lock this test exists to prove. Pinning [NOW] well
 * away from any midnight and deriving [openDay]'s date from the same
 * [MutableClock] the server reads removes the second source of truth
 * entirely, rather than narrowing the window it could disagree in.
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
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun resetClock() {
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `two members submitting on the same day at the same moment - both 201, one day at entry_count 2`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        // The day is pre-created, committed, before either submission starts.
        // This is deliberate, not incidental: `openOrGet`'s own native
        // `INSERT ... ON CONFLICT DO NOTHING` already serialises two
        // concurrent *first* writes to a brand-new day (the second blocks on
        // the first transaction's row-level lock until it commits, then reads
        // the committed state) — so a race against a day that does not exist
        // yet never actually reaches the read-modify-write this test is
        // about. The exposure is a day that already exists when both
        // transactions start: an ordinary evening where the day opened
        // hours ago and both members happen to write within the same
        // instant.
        openDay(bondId)

        val statuses =
            inParallel(
                listOf(
                    { submit(ada, bondId, """{"text":"thank you"}""") },
                    { submit(bea, bondId, """{"text":"thank you too"}""") },
                ),
            ).map { it.status }

        // Without the row lock this is where it fails: one 201 and one 500
        // (ObjectOptimisticLockingFailureException, uncaught), because both
        // transactions read entryCount = 0 and both computed 1. Proven by
        // temporarily reverting SubmitEntry.kt's lockAndFind call — see the
        // fix report.
        statuses shouldContainExactlyInAnyOrder listOf(201, 201)
        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT entry_count FROM bond_days", Int::class.java) shouldBe 2
        jdbc.queryForObject("SELECT status FROM bond_days", String::class.java) shouldBe "PARTIAL"
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 2
    }

    /**
     * Opens today's Bond-day for [bondId] directly, committed before the
     * race starts — see the test's own comment. "Today" is read off [clock],
     * the same [MutableClock] `SubmitEntry` reads through this context's
     * `@Primary` bean, not a second, independent `LocalDate.now()` — see
     * the class KDoc's own note on N3.
     */
    private fun openDay(bondId: String) {
        val today = clock.instant().atZone(LAGOS).toLocalDate()
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, entry_count, created_at, version)
            VALUES (?, ?::uuid, ?, 'OPEN', 'Africa/Lagos', 0, now(), 0)
            """.trimIndent(),
            UUID.randomUUID(),
            bondId,
            today,
        )
    }

    /** Runs every call on its own thread and releases them together — `InviteRaceTest`'s own helper. */
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

    @TestConfiguration
    class TimeConfiguration {
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
