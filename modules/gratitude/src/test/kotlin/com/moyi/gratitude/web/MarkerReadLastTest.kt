package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.GetToday
import com.moyi.gratitude.service.WithdrawalRig
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * **Who has withdrawn is asked after the entries are read, never before**
 * (ADR-0035 decision 12, as Codex's review of the pull request left it).
 *
 * Every test here puts an ending, with its withdrawal, **between** a
 * request's resolution of the caller's membership and its read of the
 * entries. A membership taken before the ending says nobody has withdrawn.
 * If that membership decides what is rendered, the withdrawn author's words
 * are in a response given after the withdrawal committed.
 *
 * Three seams, each as close to the real thing as the path allows:
 *
 * - **A membership in hand.** [GetToday.today] is handed one resolved before
 *   a real `/block`. Nothing is simulated.
 * - **An ending that commits right after resolution N.**
 *   [InterruptibleBondAccess] runs a real `/block`, on another thread and to
 *   its commit, as the Nth `membershipOf` of a request returns. N is the last
 *   resolution the path makes before it loads the entry; each test says which
 *   one that is, and then checks the count, so a path that gains or loses a
 *   resolution fails here and is looked at again.
 * - **A read that waits on the bond's lock.** `GET /today` reconciles a
 *   joining day that is still `SUSPENDED`, under the bond's row lock. The
 *   test holds that lock, sees the read blocked behind it, writes the marker
 *   in the lock-holding transaction and commits. This is the wide form of
 *   the window: the membership is as old as the wait.
 *
 * The scheduler does not run in this module's tests, so nothing is erased
 * unless a request erases it: where a row is whole, the gate alone is what
 * hid the words.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class MarkerReadLastTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val access: BondAccess,
    @Autowired private val getToday: GetToday,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val interruptible = access as InterruptibleBondAccess
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        rig.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    @AfterEach
    fun clear() {
        interruptible.disarm()
        pool.shutdownNow()
        rig.clear()
        users.clear()
    }

    @Test
    fun `today, asked with a membership taken before the block, answers the tombstone to both`() {
        val bond = revealedDay()
        val before = listOf(ada, bea).associateWith { access.membershipOf(it, UUID.fromString(bond)) }
        before.values.forEach { it.withdrawnMemberIds shouldBe emptySet() }

        rig.block(ada, bond)

        for ((reader, membership) in before) {
            val view = getToday.today(membership)
            val (hers, his) = if (reader == ada) view.myEntry to view.partnerEntry else view.partnerEntry to view.myEntry
            hers?.readability shouldBe Readability.TOMBSTONE
            val disclosed = hers?.disclosed.shouldNotBeNull()
            disclosed.text.shouldBeNull()
            his?.readability shouldBe Readability.FULL
            val rendered = json.writeValueAsString(TodayResponse.from(view))
            rendered shouldNotContain ADAS_WORDS
            rendered shouldContain BEAS_WORDS
        }
        adasRowIsWhole(bond)
    }

    @Test
    fun `today over HTTP shows the blocker none of her words when the block commits after her membership is resolved`() {
        todayInterruptedFor { ada }
    }

    @Test
    fun `today over HTTP shows the partner none of the blocker's words when the block commits after his membership is resolved`() {
        todayInterruptedFor { bea }
    }

    /** `GET /today` resolves the membership once before it loads the entries: in the controller. The ending follows that one. */
    private fun todayInterruptedFor(reader: () -> UUID) {
        val bond = revealedDay()
        interruptible.afterResolution(1) { blockOnAnotherThread(ada, bond) }

        val response = today(reader(), bond)

        response.status shouldBe 200
        response.contentAsString shouldNotContain ADAS_WORDS
        response.contentAsString shouldContain BEAS_WORDS
        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 2
        adasRowIsWhole(bond)
    }

    @Test
    fun `a replayed submission shows none of the words when the block commits after the replay resolved its membership`() {
        val bond = pairedAt(BOND_CREATED)
        clock.set(NOW)
        val key = UUID.randomUUID().toString()
        submit(ada, bond, ADAS_WORDS, key).status shouldBe 201
        // Two resolutions precede the entry: the joining-day reconcile's, then the replay's own.
        interruptible.afterResolution(2) { blockOnAnotherThread(ada, bond) }

        val replay = submit(ada, bond, ADAS_WORDS, key)

        replay.status shouldBe 201
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldNotContain ADAS_WORDS
        val body = json.readTree(replay.contentAsString)
        body["text"].isNull shouldBe true
        body["status"].asString() shouldBe "DELETED"
        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 3
        adasRowIsWhole(bond)
    }

    @Test
    fun `a replayed edit shows none of the words when the block commits after the replay resolved its membership`() {
        val bond = pairedAt(BOND_CREATED)
        clock.set(NOW)
        val id = rig.submit(ada, bond, ADAS_FIRST_WORDS)
        val key = UUID.randomUUID().toString()
        patch(ada, id, ADAS_WORDS, key).also {
            it.status shouldBe 200
            it.contentAsString shouldContain ADAS_WORDS
        }
        // Two resolutions precede the entry: the joining-day reconcile's, then the replay's own.
        interruptible.afterResolution(2) { blockOnAnotherThread(ada, bond) }

        val replay = patch(ada, id, ADAS_WORDS, key)

        replay.status shouldBe 200
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldNotContain ADAS_WORDS
        replay.contentAsString shouldNotContain ADAS_FIRST_WORDS
        val body = json.readTree(replay.contentAsString)
        body["text"].isNull shouldBe true
        body["status"].asString() shouldBe "DELETED"
        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 3
        adasRowIsWhole(bond)
    }

    @Test
    fun `today that waited on the bond's lock while her withdrawal committed shows the withdrawer none of her words`() {
        todayAfterWaitingFor { ada }
    }

    @Test
    fun `today that waited on the bond's lock while the withdrawal committed shows the partner none of the words`() {
        todayAfterWaitingFor { bea }
    }

    /**
     * The joining day (the 13th) is `SUSPENDED` with an entry on it, as slice
     * C1 left such days, and both have since written on the 15th. The first
     * read reconciles the 13th under the bond's lock, and the test is holding
     * that lock. The membership the read resolved is from before the wait.
     *
     * The reconcile itself erases what was withdrawn **on the joining day**,
     * from a membership it reads under the lock. Today's entries are not that
     * day's, and are whole when the read gets to them.
     */
    private fun todayAfterWaitingFor(reader: () -> UUID) {
        val bond = suspendedJoiningDayAndRevealedToday()

        withBondLockHeld(bond) { holderPid, release ->
            val read = pool.submit<MockHttpServletResponse> { today(reader(), bond) }
            awaitBlockedOrDone(holderPid, read)
            read.isDone shouldBe false

            release { connection -> withdraw(connection, bond, ada) }

            val response = read.get(10, TimeUnit.SECONDS)
            response.status shouldBe 200
            response.contentAsString shouldNotContain ADAS_WORDS
            response.contentAsString shouldNotContain ADAS_FIRST_WORDS
            response.contentAsString shouldContain BEAS_WORDS
        }
        adasRowIsWhole(bond)
    }

    @Test
    fun `today that waited on the lock for a joining day that is today shows none of the withdrawn words`() {
        // The joining day is today: Ada wrote alone, then Bea joined. What was
        // withdrawn is on the very day the reconcile erases under the lock.
        clock.set(NOW)
        val created = rig.create(ada)
        val bond = rig.idOf(created)
        rig.submit(ada, bond, ADAS_WORDS)
        rig.accept(bea, rig.codeOf(created))

        withBondLockHeld(bond) { holderPid, release ->
            val read = pool.submit<MockHttpServletResponse> { today(bea, bond) }
            awaitBlockedOrDone(holderPid, read)
            read.isDone shouldBe false

            release { connection -> withdraw(connection, bond, ada) }

            val response = read.get(10, TimeUnit.SECONDS)
            response.status shouldBe 200
            response.contentAsString shouldNotContain ADAS_WORDS
        }
    }

    // ---- the bonds ----

    private fun pairedAt(at: Instant): String {
        clock.set(at)
        return rig.pair(ada, bea)
    }

    /** Paired two days ago; both wrote today, and the day is revealed. */
    private fun revealedDay(): String {
        val bond = pairedAt(BOND_CREATED)
        clock.set(NOW)
        rig.submit(ada, bond, ADAS_WORDS)
        rig.submit(bea, bond, BEAS_WORDS)
        today(bea, bond).contentAsString shouldContain ADAS_WORDS
        return bond
    }

    private fun suspendedJoiningDayAndRevealedToday(): String {
        clock.set(BOND_CREATED)
        val created = rig.create(ada)
        val bond = rig.idOf(created)
        rig.submit(ada, bond, ADAS_FIRST_WORDS)
        rig.accept(bea, rig.codeOf(created))
        clock.set(NOW)
        rig.submit(ada, bond, ADAS_WORDS)
        rig.submit(bea, bond, BEAS_WORDS)
        // Those writes reconciled the joining day. Put it back as C1 left it.
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED' WHERE bond_id = ?::uuid AND date = DATE '2026-09-13'", bond) shouldBe 1
        return bond
    }

    /** Today's entry of Ada's still holds its words: nothing but the gate hid them. */
    private fun adasRowIsWhole(bond: String) {
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND text = ? AND deleted_at IS NULL",
            Int::class.java,
            bond,
            ADAS_WORDS,
        ) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id = ?::uuid", Int::class.java, bond) shouldBe 1
    }

    // ---- the ending ----

    /**
     * A real block, to its commit, off the request's thread: that thread may
     * be inside the transaction that holds the request's `Idempotency-Key`,
     * and a block made on it would join that transaction and not commit.
     */
    private fun blockOnAnotherThread(
        user: UUID,
        bond: String,
    ) {
        pool.submit { rig.block(user, bond) }.get(10, TimeUnit.SECONDS)
        jdbc.queryForObject("SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id = ?::uuid", Int::class.java, bond) shouldBe 1
    }

    /** The marker as `bond` writes it, in the transaction that holds the bond's lock. */
    private fun withdraw(
        connection: Connection,
        bond: String,
        user: UUID,
    ) {
        connection
            .prepareStatement("INSERT INTO bond_entry_withdrawals (bond_id, member_id, withdrawn_at) VALUES (?::uuid, ?, ?)")
            .use {
                it.setString(1, bond)
                it.setObject(2, rig.memberId(bond, user))
                it.setTimestamp(3, Timestamp.from(clock.instant()))
                it.executeUpdate() shouldBe 1
            }
    }

    private fun withBondLockHeld(
        bond: String,
        block: (holderPid: Int, release: ((Connection) -> Unit) -> Unit) -> Unit,
    ) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                lockBondRow(connection, bond)
                block(backendPidOf(connection)) { last ->
                    last(connection)
                    connection.commit()
                }
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private fun lockBondRow(
        connection: Connection,
        bond: String,
    ) {
        connection.prepareStatement("SELECT 1 FROM bonds WHERE id = ?::uuid FOR UPDATE").use {
            it.setString(1, bond)
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

    /** Synchronises on the lock wait itself, as `SubmitEntryBondLockTest` does: no sleep. */
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

    // ---- requests ----

    private fun today(
        user: UUID,
        bond: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bond/today") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response

    private fun submit(
        user: UUID,
        bond: String,
        words: String,
        key: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response

    private fun patch(
        user: UUID,
        id: String,
        words: String,
        key: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response

    /**
     * `bond`'s own [BondAccess], with one thing added: something can be made
     * to happen as the Nth `membershipOf` since [afterResolution] returns.
     * What it returns is the real answer, untouched.
     */
    internal class InterruptibleBondAccess(
        private val real: BondAccess,
    ) : BondAccess by real {
        private val count = AtomicInteger()

        @Volatile private var nth: Int = 0

        @Volatile private var then: (() -> Unit)? = null

        @Volatile var fired: Boolean = false
            private set

        /** How many times a membership has been resolved since [afterResolution]. */
        val resolutions: Int get() = count.get()

        fun afterResolution(
            n: Int,
            action: () -> Unit,
        ) {
            count.set(0)
            fired = false
            nth = n
            then = action
        }

        fun disarm() {
            then = null
        }

        override fun membershipOf(
            userId: UUID,
            bondId: UUID,
        ): BondMembership {
            val membership = real.membershipOf(userId, bondId)
            val action = then
            if (action != null && count.incrementAndGet() == nth) {
                action()
                fired = true
            }
            return membership
        }
    }

    @TestConfiguration
    class AccessConfiguration {
        @Bean
        @Primary
        fun interruptibleBondAccess(
            @Qualifier("bondAccessAdapter") real: BondAccess,
        ): BondAccess = InterruptibleBondAccess(real)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")

        const val ADAS_FIRST_WORDS = "ada-first-draft-of-thanks"
        const val ADAS_WORDS = "ada-thanks-for-the-walk-home"
        const val BEAS_WORDS = "bea-thanks-for-the-tea"
    }
}
