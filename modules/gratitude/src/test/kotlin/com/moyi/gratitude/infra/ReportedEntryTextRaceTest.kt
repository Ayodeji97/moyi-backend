package com.moyi.gratitude.infra

import com.moyi.bond.api.BondAccess
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.ReportedEntryRead
import com.moyi.gratitude.api.ReportedEntryText
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.service.EraseDeletedAccount
import com.moyi.gratitude.service.EraseEntry
import com.moyi.gratitude.web.SubmitEntryBondLockTest
import com.moyi.identity.api.AccountDeletions
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The read's locks (A5 spec §8, plan Global Constraints): the report `FOR SHARE`, then the
 * entry `FOR SHARE`, and **no bond lock**. In each case one transaction is held open at a known
 * point while the other is **observed waiting for a lock** in `pg_stat_activity`, never
 * assumed to by a sleep; every wait has a deadline and fails the test when it passes.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LongParameterList") // What Spring hands the test.
internal class ReportedEntryTextRaceTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val port: ReportedEntryText,
    @Autowired private val access: BondAccess,
    @Autowired private val eraser: EraseEntry,
    @Autowired private val accountErasure: EraseDeletedAccount,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ReportedEntryRig(mockMvc, tokens, jdbc)
    private val threads = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: UUID

    @BeforeEach
    fun setUp() {
        clock.set(NOW.minusSeconds(86_400))
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        bond = rig.pair(ada, bea)
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.clear()
        users.clear()
    }

    @AfterAll
    fun stop() {
        threads.shutdownNow()
    }

    /** (1) A decision waits for a read in flight, and proceeds once it commits. */
    @Test
    fun `a read holding its share lock makes a dismiss wait, and the dismiss proceeds after the read commits`() {
        val (entry, report) = reported("HARASSMENT")
        val read = held { port.read(report, "HARASSMENT", ACTOR) }

        dataSource.connection.use { decider ->
            val dismiss = CompletableFuture.supplyAsync({ dismiss(decider, report) }, threads)
            awaitLockWait(DISMISS)
            read.commit().shouldBeInstanceOf<ReportedEntryRead.Shown>().entryId shouldBe entry
            dismiss.get(DEADLINE_SECONDS, TimeUnit.SECONDS) shouldBe "DISMISSED"
        }
        // The function stamps the database's now(), the port the application's clock: compared as a set.
        rig.log(report).map { it.substringBefore('|') } shouldContainExactlyInAnyOrder listOf("WORDS_READ", "DISMISSED")
    }

    /** (2) A read waits for a decision in flight, and then finds the report decided. */
    @Test
    fun `a dismiss holding the report makes a read wait, and the read then answers not open`() {
        val (entry, report) = reported("HARASSMENT")

        val answer =
            dataSource.connection.use { decider ->
                decider.autoCommit = false
                try {
                    dismiss(decider, report) shouldBe "DISMISSED"
                    val read = CompletableFuture.supplyAsync({ inTransaction { port.read(report, "HARASSMENT", ACTOR) } }, threads)
                    awaitLockWait(REPORT_SHARE)
                    decider.commit()
                    read.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
                } finally {
                    decider.rollback()
                }
            }

        answer shouldBe ReportedEntryRead.NotOpen(entry)
        rig.log(report).map { it.substringBefore('|') } shouldBe listOf("DISMISSED")
    }

    /** (3) The author's erasure holds the entry's lock; the read waits for it and then reads the tombstone, never half. */
    @Test
    fun `the author's erasure holding the entry makes a read wait, and the read then answers erased`() {
        val (entry, report) = reported("HARASSMENT")
        val day = jdbc.queryForObject("SELECT bond_day_id FROM entries WHERE id = ?", UUID::class.java, entry)!!
        val erasure =
            held {
                access.lockClosingViewOf(bond)
                eraser.erase(EntryId(entry), BondDayId(day), clock.instant())
            }

        val read = CompletableFuture.supplyAsync({ inTransaction { port.read(report, "HARASSMENT", ACTOR) } }, threads)
        awaitLockWait(ENTRY_SHARE)
        erasure.commit()

        read.get(DEADLINE_SECONDS, TimeUnit.SECONDS) shouldBe ReportedEntryRead.Erased(entry)
    }

    /**
     * (3b) The other order (ADR-0064 decision 9: "an erasure waits for a read"): the read holds the
     * report and the entry `FOR SHARE`; the author's own `DELETE /entries/{id}` is observed waiting
     * on the entry's row lock; the read commits with the words; the delete then completes.
     */
    @Test
    fun `a read holding the entry makes the author's erasure wait, and the erasure completes after it`() {
        val (entry, report) = reported("HARASSMENT")
        val read = held { port.read(report, "HARASSMENT", ACTOR) }

        val erasure = CompletableFuture.runAsync({ rig.delete(bea, entry) }, threads)
        awaitLockWait(ENTRY_ERASURE)
        read.commit().shouldBeInstanceOf<ReportedEntryRead.Shown>().entryId shouldBe entry

        erasure.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
        jdbc.queryForObject("SELECT status FROM entries WHERE id = ?", String::class.java, entry) shouldBe "DELETED"
    }

    /**
     * (4a) The reporter's account erasure (`EraseDeletedAccount`: the bond's lock, then
     * `UPDATE entry_reports SET detail = NULL`) against a read already holding the report: the
     * erasure waits on the report, the read never waits on the bond. Both complete.
     */
    @Test
    fun `a read holding the report, then the reporter's account erasure - both complete`() {
        val (_, report) = reported("HARASSMENT")
        val read = held { port.read(report, "HARASSMENT", ACTOR) }

        val erasure = CompletableFuture.supplyAsync({ inTransaction { accountErasure.handle(deletionOf(ada)) } }, threads)
        awaitLockWait(DETAIL_ERASURE)
        read.commit().shouldBeInstanceOf<ReportedEntryRead.Shown>()

        erasure.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
        detail(report) shouldBe null
    }

    /**
     * (4b) The other order, and the one that would deadlock if the read took the bond's lock:
     * the erasure holds the bond first; the read takes the report and the entry and must finish
     * without the bond; then the erasure reaches the report and waits for the read. Both complete.
     */
    @Test
    fun `the reporter's account erasure holding the bond, then a read - both complete, no deadlock`() {
        val (_, report) = reported("HARASSMENT")
        val bondHeld = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val erasure =
            CompletableFuture.supplyAsync({
                inTransaction {
                    access.lockClosingViewOf(bond)
                    bondHeld.countDown()
                    check(goOn.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "the erasure was never let go on" }
                    accountErasure.handle(deletionOf(ada))
                }
            }, threads)
        try {
            check(bondHeld.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "the erasure did not take the bond within $DEADLINE_SECONDS s" }
            val read = held { port.read(report, "HARASSMENT", ACTOR) }
            goOn.countDown()
            awaitLockWait(DETAIL_ERASURE)
            read.commit().shouldBeInstanceOf<ReportedEntryRead.Shown>()
            erasure.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
        } finally {
            goOn.countDown()
        }
        detail(report) shouldBe null
    }

    /** (4c) The erasure holds the bond and the report; the read waits on the report, then reads. Both complete. */
    @Test
    fun `the reporter's account erasure holding the report, then a read - both complete`() {
        val (_, report) = reported("HARASSMENT")
        val erasure = held { accountErasure.handle(deletionOf(ada)) }

        val read = CompletableFuture.supplyAsync({ inTransaction { port.read(report, "HARASSMENT", ACTOR) } }, threads)
        awaitLockWait(REPORT_SHARE)
        erasure.commit()

        read.get(DEADLINE_SECONDS, TimeUnit.SECONDS).shouldBeInstanceOf<ReportedEntryRead.Shown>()
        detail(report) shouldBe null
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    /** Bea's revealed entry reported by Ada for [reason]: the entry and the report. */
    private fun reported(reason: String): Pair<UUID, UUID> {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        return entry to rig.reportEntry(ada, entry, reason)
    }

    private fun dismiss(
        connection: java.sql.Connection,
        report: UUID,
    ): String =
        connection.prepareStatement("SELECT entry_report_dismiss(?, 'op', 'decided')").use { statement ->
            statement.setObject(1, report)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getString(1)
            }
        }

    private fun detail(report: UUID): String? =
        jdbc.queryForObject("SELECT detail FROM entry_reports WHERE id = ?", String::class.java, report)

    private fun deletionOf(user: UUID) =
        ReceivedEvent(
            id = UUID.randomUUID(),
            aggregateType = "Account",
            aggregateId = user,
            eventType = AccountDeletions.EVENT,
            references = mapOf(AccountDeletions.USER_ID to user),
            occurredAt = clock.instant(),
        )

    private fun <T> inTransaction(work: () -> T): T {
        var answer: Result<T>? = null
        transactions.executeWithoutResult { answer = Result.success(work()) }
        return answer!!.getOrThrow()
    }

    /** A transaction that has done [work] and is held open, uncommitted, until [commit]. */
    private inner class Held<T>(
        work: () -> T,
    ) {
        private val done = CountDownLatch(1)
        private val release = CountDownLatch(1)

        private val future =
            CompletableFuture.supplyAsync({
                inTransaction {
                    work().also {
                        done.countDown()
                        check(release.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) { "the held transaction was never let commit" }
                    }
                }
            }, threads)

        init {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
            while (!done.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (future.isDone) future.get() // rethrows what the work threw
                check(System.nanoTime() < deadline) {
                    release.countDown()
                    "the held transaction did not reach its point within $DEADLINE_SECONDS s"
                }
            }
        }

        /** Lets the transaction commit; its answer, once it has. */
        fun commit(): T {
            release.countDown()
            return future.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun <T> held(work: () -> T): Held<T> = Held(work)

    /**
     * Until another backend of this database waits for a lock in a statement matching
     * [statement] (a `LIKE` pattern), or the deadline passes: then the test fails.
     */
    private fun awaitLockWait(statement: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                        "AND datname = current_database() AND pid <> pg_backend_pid() AND query LIKE ?",
                    Int::class.java,
                    statement,
                )!!
            if (waiting > 0) return
            Thread.onSpinWait()
        }
        error("no statement like '$statement' was seen waiting for a lock within $DEADLINE_SECONDS s")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val ACTOR = "admin:0f0f0f0f-0000-4000-8000-0000000000ad"
        const val MARKER = "MARKER-a5-race-41d2"

        const val DISMISS = "%entry\\_report\\_dismiss%"
        const val REPORT_SHARE = "%FROM entry\\_reports WHERE id = %FOR SHARE%"
        const val ENTRY_SHARE = "%FROM entries e JOIN bond\\_days d%FOR SHARE OF e%"

        /** The author's erasure taking the entry's row lock (`EntryRepository.lockRow`, via `EraseEntry`). */
        const val ENTRY_ERASURE = "%FROM entries WHERE id = %FOR UPDATE%"
        const val DETAIL_ERASURE = "%UPDATE entry\\_reports SET detail = NULL%"

        /** Every wait in this class: long enough for a loaded CI runner, short enough to fail fast. */
        const val DEADLINE_SECONDS = 10L
        const val POLL_MILLIS = 20L
    }
}
