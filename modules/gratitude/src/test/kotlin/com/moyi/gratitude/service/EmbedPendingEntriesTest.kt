package com.moyi.gratitude.service

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.EmbeddingRun
import com.moyi.gratitude.api.EmbeddingSweep
import com.moyi.gratitude.infra.FakeEmbeddingPort
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryEmbeddings
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The embedding sweep (ADR-0054, spec §3.3, §3.4): which entries it embeds, and the two
 * things that may change between its read and its write, the bond's setting and the entry's
 * words, neither of which may leave a vector behind.
 *
 * The provider is [FakeEmbeddingPort]; its `during` hook runs inside the provider call, which
 * is the moment between the sweep's read and its write.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class EmbedPendingEntriesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val sweep: EmbeddingSweep,
    @Autowired private val fake: FakeEmbeddingPort,
    @Autowired private val embeddings: EntryEmbeddings,
    @Autowired private val access: BondAccess,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val meters: MeterRegistry,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        rig.clear()
        fake.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        pool.shutdownNow()
        rig.clear()
        fake.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `a solo creator's entry is embedded`() { // bondsWithSemanticSearch has no "ever paired" filter
        val bond = solo()
        submit(bond, "alone with the kettle")

        sweep.embedPending(10).embedded shouldBe 1
        embeddings.countFor(bond.uuid) shouldBe 1
    }

    @Test
    fun `embeds the pending entries of bonds with the setting on, up to the budget`() {
        val on = pair()
        val off = pair()
        turnOff(off)
        submit(off, "never sent")
        // One entry a member a day: three of Ada's, on three days.
        repeat(3) {
            clock.set(NOW.plus(Duration.ofDays(it.toLong())))
            submit(on, "words $it")
        }
        val embeddedBefore = counted("embedded")

        sweep.embedPending(budget = 2) shouldBe EmbeddingRun(embedded = 2, skipped = 0, failed = false)
        sweep.embedPending(budget = 10).embedded shouldBe 1

        fake.texts shouldNotContain "never sent"
        embeddings.countFor(on.uuid) shouldBe 3
        embeddings.countFor(off.uuid) shouldBe 0
        counted("embedded") - embeddedBefore shouldBe 3.0
    }

    @Test
    fun `no provider means nothing is sent and nothing fails`() {
        fake.space = null
        submit(pair(), "words")

        sweep.embedPending(10) shouldBe EmbeddingRun(0, 0, false)
        fake.texts shouldBe emptyList()
    }

    @Test
    fun `a provider failure leaves the entries pending`() {
        fake.failNext = true
        val bond = pair()
        submit(bond, "words")

        sweep.embedPending(10).failed shouldBe true
        embeddings.countFor(bond.uuid) shouldBe 0
        sweep.embedPending(10).embedded shouldBe 1
    }

    /** Review of Task 6: one input the provider refuses must not stop every bond after it, run after run. */
    @Test
    fun `a bond whose input the provider refuses does not stop a later bond from being embedded`() {
        val (first, second) = inIdOrder(pair(), pair())
        submit(first, "refused")
        submit(second, "accepted")
        fake.failOn = setOf("refused")

        sweep.embedPending(10) shouldBe EmbeddingRun(embedded = 1, skipped = 0, failed = true)
        embeddings.countFor(second.uuid) shouldBe 1
        embeddings.countFor(first.uuid) shouldBe 0
    }

    @Test
    fun `three failures in a row end the run, and the provider is not asked again`() {
        // A member may hold only so many bonds: two couples, two bonds each.
        val cy = users.verified("Cy")
        val dee = users.verified("Dee")
        val authors = mapOf(pair() to ada, pair() to ada, pair(cy, dee) to cy, pair(cy, dee) to cy)
        val bonds = inIdOrder(*authors.keys.toTypedArray())
        bonds.forEachIndexed { i, bond -> submit(bond, "words $i", authors.getValue(bond)) }
        fake.failOn = bonds.indices.map { "words $it" }.toSet()

        sweep.embedPending(10) shouldBe EmbeddingRun(embedded = 0, skipped = 0, failed = true)
        fake.texts shouldBe listOf("words 0", "words 1", "words 2")
    }

    /**
     * Review of Task 6: the page of bonds is read before the walk reaches each one, and a member
     * who turns it off in between must not have their words sent. Off is committed while the
     * first bond's provider call is in progress; the second bond's words are never read.
     */
    @Test
    fun `a bond turned off after the page was read has no words sent`() {
        val (first, second) = inIdOrder(pair(), pair())
        submit(first, "the first bond's words")
        submit(second, "the second bond's words")
        fake.during = { turnOff(second) }

        sweep.embedPending(10).embedded shouldBe 1

        fake.texts shouldBe listOf("the first bond's words")
        embeddings.countFor(second.uuid) shouldBe 0
    }

    /** ADR-0054 decision 1: a provider call of seconds never holds a connection or a lock. */
    @Test
    fun `the provider is called with no transaction open`() {
        submit(pair(), "words")
        submit(solo(), "more words")

        sweep.embedPending(10).embedded shouldBe 2

        fake.calledInTransaction shouldContainExactly listOf(false, false)
    }

    @Test
    fun `turning off between the provider call and the write leaves no vector`() {
        val bond = pair()
        submit(bond, "words")
        fake.during = { turnOff(bond) }

        sweep.embedPending(10).skipped shouldBe 1
        embeddings.countFor(bond.uuid) shouldBe 0
    }

    /**
     * Review focus 3, as a race. The sweep has read the entry and embedded it; the member's
     * turn-off then holds the bond's row lock, its change made and not yet committed, as
     * `UpdateBond.patch` holds it. The sweep's write must queue behind that lock (read from
     * `pg_blocking_pids`, never inferred from time passing), and once the turn-off commits it
     * must read the setting as off and write nothing. A write that read the setting without the
     * lock would see the old committed `true`, finish without queueing, and leave a vector that
     * the turn-off's consumer, already run, would never delete.
     */
    @Test
    fun `a turn-off that holds the bond lock while the sweep writes leaves no vector`() {
        val bond = pair()
        submit(bond, "words")

        dataSource.connection.use { turnOff ->
            turnOff.autoCommit = false
            try {
                turnOff.lockBondRow(bond)
                turnOff.run("UPDATE bonds SET semantic_search_enabled = false WHERE id = ?", bond.uuid)
                val sweeping = pool.submit<EmbeddingRun> { sweep.embedPending(10) }
                awaitBlockedOrDone(backendPidOf(turnOff), sweeping)

                // The sweep has read and embedded, and is waiting to write.
                fake.texts shouldBe listOf("words")
                sweeping.isDone shouldBe false

                turnOff.commit()
                sweeping.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe EmbeddingRun(embedded = 0, skipped = 1, failed = false)
            } finally {
                turnOff.rollback()
                turnOff.autoCommit = true
            }
        }

        embeddings.countFor(bond.uuid) shouldBe 0
    }

    @Test
    fun `an edit between the provider call and the write leaves no stale vector`() {
        val bond = pair()
        val id = submit(bond, "an old plum")
        fake.during = { patch(ada, id, "a new pear") }

        sweep.embedPending(10).skipped shouldBe 1
        sweep.embedPending(10).embedded shouldBe 1 // the new words, next run

        fake.texts shouldBe listOf("an old plum", "a new pear")
    }

    /** CLAUDE.md's timestamp rule: what is stored is what the sweep meant, not Postgres's rounding of it. */
    @Test
    fun `the vector's time is stored truncated to microseconds`() {
        val bond = pair()
        val id = submit(bond, "words")
        val fine = Clock.fixed(Instant.parse("2026-09-15T10:00:00.123456789Z"), ZoneOffset.UTC)
        val byHand = EmbedPendingEntries(fake, embeddings, access, transactions, fine, meters)

        byHand.embedPending(10).embedded shouldBe 1

        jdbc
            .queryForObject(
                "SELECT created_at FROM entry_embeddings_openai_1536 WHERE entry_id = ?::uuid",
                java.sql.Timestamp::class.java,
                id,
            )!!
            .toInstant() shouldBe fine.instant().truncatedTo(ChronoUnit.MICROS)
    }

    // ---- helpers ----

    private val String.uuid: UUID get() = UUID.fromString(this)

    private fun counted(outcome: String): Double = meters.counter("gratitude.ai.embedding.entries", "outcome", outcome).count()

    private fun pair(
        creator: UUID = ada,
        joiner: UUID = bea,
    ): String {
        clock.set(BOND_CREATED)
        return rig.pair(creator, joiner).also { clock.set(NOW) }
    }

    /** The walk's order: bond ids, as Postgres orders a `uuid` (its bytes, which is the hex text's order). */
    private fun inIdOrder(vararg bonds: String): List<String> = bonds.sorted()

    private fun solo(): String = rig.idOf(rig.create(ada))

    private fun submit(
        bond: String,
        words: String,
        author: UUID = ada,
    ): String = rig.submit(author, bond, words)

    /** The real route: `PATCH /bonds/{id}`, which takes the bond's lock and publishes `SemanticSearchDisabled`. */
    private fun turnOff(bond: String) {
        val etag =
            mockMvc
                .get("/api/v1/bonds/$bond") { header(HttpHeaders.AUTHORIZATION, bearer(ada)) }
                .andReturn()
                .response
                .getHeader(HttpHeaders.ETAG)!!
        mockMvc
            .patch("/api/v1/bonds/$bond") {
                header(HttpHeaders.AUTHORIZATION, bearer(ada))
                header(HttpHeaders.IF_MATCH, etag)
                contentType = MediaType.APPLICATION_JSON
                content = """{"semanticSearchEnabled":false}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun patch(
        user: UUID,
        id: String,
        words: String,
    ) {
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    /** `BondRepository.lockRow`'s own statement, which `UpdateBond.patch` takes first. */
    private fun Connection.lockBondRow(bond: String) {
        prepareStatement("SELECT 1 FROM bonds WHERE id = ? FOR UPDATE").use {
            it.setObject(1, bond.uuid)
            it.executeQuery().use { rows -> rows.next() shouldBe true }
        }
    }

    private fun Connection.run(
        sql: String,
        vararg args: Any,
    ) {
        prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            statement.executeUpdate() shouldBe 1
        }
    }

    private fun backendPidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun awaitBlockedOrDone(
        holderPid: Int,
        waiter: Future<*>,
    ) {
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS)).until {
            waiter.isDone ||
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Int::class.java,
                    holderPid,
                )!! > 0
        }
    }

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val TIMEOUT_SECONDS = 10L
    }
}
