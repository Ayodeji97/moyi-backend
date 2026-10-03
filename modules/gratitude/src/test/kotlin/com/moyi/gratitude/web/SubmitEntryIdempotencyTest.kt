package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * Spec §5.4 on the real endpoint: `POST /bonds/{bondId}/entries` locks the
 * key, reserves it, writes the entry and completes the key in **one**
 * transaction, and a replay re-reads the entry from its current state.
 *
 * Each test's comment names the change that makes it fail. Where a test is
 * about waiting, the wait is observed through `pg_blocking_pids` — a request
 * that queued is *seen* queued — never inferred from a sleep or a timeout.
 *
 * **The tombstone is simulated at the row level.** C1 has no path that
 * erases an entry (`DELETE /entries/{id}` is C2, withdrawal on block is C5),
 * so the erasure test writes BR-10's own shape into the row directly — text
 * nulled, `status = DELETED`, `deleted_at` set — which is exactly what those
 * slices will produce.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryIdempotencyTest.TimeConfiguration::class)
internal class SubmitEntryIdempotencyTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val pool = Executors.newFixedThreadPool(2)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        // Other suites in this JVM share the database and may have left keys.
        jdbc.execute("TRUNCATE TABLE idempotency_keys")
        clock.set(NOW)
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
    }

    @AfterEach
    fun clear() {
        // The pool first, bounded: a submission still queued must not race the truncate.
        pool.shutdownNow()
        pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    // ---- atomicity ------------------------------------------------------

    @Test
    fun `a refused submission leaves no key, so the corrected retry under the same key is accepted`() {
        // Reserve the key in a transaction of its own (the old preHandle
        // shape) and the reservation outlives the 422: the corrected retry is
        // then 422 IDEMPOTENCY_KEY_REUSED — the body differs — or 409 in
        // flight, for 24 hours, for a request the server never accepted.
        val key = UUID.randomUUID().toString()
        val refused = submit(ada, """{"text":"look","imageMediaId":"${UUID.randomUUID()}"}""", key)
        refused.status shouldBe 422
        refused.contentAsString shouldContain "\"code\":\"MEDIA_NOT_YET_SUPPORTED\""
        count("SELECT count(*) FROM idempotency_keys") shouldBe 0

        val corrected = submit(ada, """{"text":"look"}""", key)

        corrected.status shouldBe 201
        corrected.getHeader(IdempotencyInterceptor.REPLAYED_HEADER).shouldBeNull()
        count("SELECT count(*) FROM entries") shouldBe 1
    }

    @Test
    fun `a write that fails at the database after the key was reserved rolls the key back with it`() {
        // BR-2's unique index refuses the second entry at the flush — after
        // the reservation's INSERT, inside the same transaction. The key must
        // go with the entry it never produced.
        submit(ada, """{"text":"first"}""", "key-one").status shouldBe 201

        val second = submit(ada, """{"text":"second"}""", "key-two")

        second.status shouldBe 409
        second.contentAsString shouldContain "\"code\":\"ENTRY_ALREADY_EXISTS\""
        jdbc.queryForList("SELECT idempotency_key FROM idempotency_keys", String::class.java) shouldBe listOf("key-one")
        count("SELECT count(*) FROM entries") shouldBe 1
    }

    @Test
    fun `a submission that never commits leaves neither an entry nor a key, and its retry runs`() {
        // The first attempt is parked behind the bond lock with its key
        // reserved, and its bond is archived under it: it rolls back. A
        // reservation committed apart from the write would survive that.
        val key = UUID.randomUUID().toString()
        withBondLockHeld { holderPid, release ->
            val submission = async { submit(ada, """{"text":"too late"}""", key) }
            awaitBlockedBy(holderPid, submission, blocked = 1)
            submission.isDone shouldBe false

            release { connection -> archiveBond(connection) }
            submission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status shouldBe 409
        }
        count("SELECT count(*) FROM idempotency_keys") shouldBe 0
        count("SELECT count(*) FROM entries") shouldBe 0

        jdbc.update("UPDATE bonds SET status = 'ACTIVE', archived_at = NULL WHERE id = ?::uuid", bondId)
        submit(ada, """{"text":"too late"}""", key).status shouldBe 201
    }

    // ---- nonblocking ----------------------------------------------------

    @Test
    fun `a second request under a key whose first is still in flight is refused at once, never queued`() {
        // The lock order: the key's advisory lock is TRIED before the bond's
        // row lock is waited for. The first request below holds the key and
        // is parked behind the bond lock. Any of these and the second one
        // queues instead of answering — it appears in pg_blocking_pids and is
        // not done:
        //  - take the bond lock before the key lock (it queues behind the holder);
        //  - use the blocking pg_advisory_xact_lock (it queues behind the first);
        //  - drop the advisory lock (its reservation INSERT queues on the
        //    unique index behind the first's uncommitted row).
        val key = UUID.randomUUID().toString()
        withBondLockHeld { holderPid, release ->
            val first = async { submit(ada, """{"text":"thank you"}""", key) }
            awaitBlockedBy(holderPid, first, blocked = 1)
            first.isDone shouldBe false

            val second = async { submit(ada, """{"text":"thank you"}""", key) }
            awaitBlockedBy(holderPid, second, blocked = 2)

            second.isDone shouldBe true
            val refused = second.get(1, TimeUnit.SECONDS)
            refused.status shouldBe 409
            refused.contentAsString shouldContain "\"code\":\"IDEMPOTENCY_KEY_IN_FLIGHT\""

            release {}
            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status shouldBe 201
        }
        count("SELECT count(*) FROM entries") shouldBe 1
        count("SELECT count(*) FROM idempotency_keys") shouldBe 1
    }

    // ---- a replay re-reads ----------------------------------------------

    @Test
    fun `a replay returns the same entry, re-read, and does not write a second one`() {
        val key = UUID.randomUUID().toString()
        val first = submit(ada, """{"text":"thank you for the coffee"}""", key)
        first.status shouldBe 201

        val replay = submit(ada, """{"text":"thank you for the coffee"}""", key)

        replay.status shouldBe 201
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        first.getHeader(IdempotencyInterceptor.REPLAYED_HEADER).shouldBeNull()
        // Unchanged resources reproduce the original response (spec §5.4).
        replay.contentAsString shouldBe first.contentAsString
        count("SELECT count(*) FROM entries") shouldBe 1
    }

    @Test
    fun `a replay after the entry was erased renders its tombstone, never the original words`() {
        // Simulated at the row level — see the class KDoc. Answer the replay
        // from anything but a re-read of the row and the words come back.
        val key = UUID.randomUUID().toString()
        val first = submit(ada, """{"text":"words since withdrawn xyzzy"}""", key)
        first.status shouldBe 201
        jdbc.update("UPDATE entries SET text = NULL, status = 'DELETED', deleted_at = now()")

        val replay = submit(ada, """{"text":"words since withdrawn xyzzy"}""", key)

        replay.status shouldBe 201
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldNotContain "xyzzy"
        replay.contentAsString shouldContain "\"status\":\"DELETED\""
        replay.contentAsString shouldContain "\"text\":null"
        replay.contentAsString shouldContain "\"id\":\"${idOf(first)}\""
    }

    @Test
    fun `a replay by a caller who has since left the bond gets today's refusal, not the cached 201`() {
        // Skip the membership guard on the replay path and this is a 201
        // carrying the entry of a bond the caller walked out of.
        val key = UUID.randomUUID().toString()
        submit(ada, """{"text":"before I left"}""", key).status shouldBe 201
        leave(ada).status shouldBe 204
        val fresh = submit(ada, """{"text":"a new request now"}""")

        val replay = submit(ada, """{"text":"before I left"}""", key)

        replay.status shouldBe fresh.status
        replay.status shouldBe 409
        replay.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        replay.contentAsString shouldNotContain "before I left"
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER).shouldBeNull()
    }

    @Test
    fun `a replay by a caller who is no longer a member at all gets the same 404 as a stranger`() {
        // Membership removed at the row level: no C1 path deletes a member
        // row, but the guard must not depend on that staying true.
        val key = UUID.randomUUID().toString()
        submit(ada, """{"text":"while I was here"}""", key).status shouldBe 201
        jdbc.update("DELETE FROM bond_members WHERE user_id = ?", ada)

        val replay = submit(ada, """{"text":"while I was here"}""", key)

        replay.status shouldBe 404
        replay.contentAsString shouldNotContain "while I was here"
    }

    // ---- the fingerprint is keyed ---------------------------------------

    @Test
    fun `the stored request_hash is not a plain SHA-256 of the body, nor of method, path and body`() {
        // Ruling P8: a short entry must not be recoverable by hashing guesses
        // against this column. Wire a plain-hash RequestFingerprint and one of
        // these candidates is the stored value.
        val body = """{"text":"thank you"}"""
        val path = "/api/v1/bonds/$bondId/entries"
        submit(ada, body, UUID.randomUUID().toString()).status shouldBe 201

        val stored = jdbc.queryForObject("SELECT request_hash FROM idempotency_keys", String::class.java)!!

        val inputs =
            listOf(
                body,
                "POST$path$body",
                "POST:$path:$body",
                "POST\n$path\n$body",
                "4:POST${path.length}:$path$body",
            )
        inputs.flatMap(::plainSha256Encodings).forEach { stored shouldNotBe it }
        stored shouldNotContain "thank"
    }

    // ---- the lock holder ------------------------------------------------

    private fun <T> async(call: () -> T): Future<T> = pool.submit<T> { call() }

    /** A transaction on its own connection holding the bond's `FOR UPDATE` — `SubmitEntryBondLockTest`'s own holder. */
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
                // A no-op after `release` committed; otherwise it frees whatever is still queued.
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

    private fun archiveBond(connection: Connection) {
        connection.prepareStatement("UPDATE bonds SET status = 'ARCHIVED', archived_at = now() WHERE id = ?::uuid").use {
            it.setString(1, bondId)
            it.executeUpdate()
        }
    }

    /**
     * Until [waiter] is done, or [blocked] backends are waiting on a lock at
     * all — behind the holder *or* behind each other, so a request queued on
     * the first request's lock or on the unique index is counted too.
     */
    private fun awaitBlockedBy(
        holderPid: Int,
        waiter: Future<*>,
        blocked: Int,
    ) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            waiter.isDone ||
                count("SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0") >= blocked
        }
        // The holder really is at the head of whatever is queued.
        if (!waiter.isDone) {
            (count("SELECT count(*) FROM pg_stat_activity WHERE $holderPid = ANY(pg_blocking_pids(pid))") > 0) shouldBe true
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    private fun plainSha256Encodings(input: String): List<String> {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return listOf(
            digest.joinToString("") { "%02x".format(it) },
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
            Base64.getEncoder().encodeToString(digest),
        )
    }

    private fun submit(
        caller: UUID,
        body: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun leave(caller: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}") }
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

    private fun idOf(response: MockHttpServletResponse): String =
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
        const val TIMEOUT_SECONDS = 10L
    }
}
