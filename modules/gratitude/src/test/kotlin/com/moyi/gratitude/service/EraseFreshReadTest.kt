package com.moyi.gratitude.service

import com.moyi.common.events.DispatchResult
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * [EraseEntry] decides from the entry and the day **as they stand under its
 * own locks**, and not from a copy its transaction read before it had them.
 *
 * It is the trap ADR-0031 decision 9 records, met a third time. Hibernate
 * answers a second read of a row from its identity map, lock or no lock, so
 * taking the lock is not enough: the row must be read again after it. For an
 * erasure the older copy is not merely out of date. It says "live" of an
 * entry somebody else has since erased, and the day is then stepped back a
 * second time for one entry.
 *
 * **How "meanwhile" is made, without a sleep.** Another connection holds the
 * row the code under test must wait for. The code is started and is seen
 * waiting on that connection (`pg_blocking_pids`), which is after its first
 * read and before its lock. The holder then commits a change and lets go.
 *
 * Each test was seen to fail with the read it pins made stale: `entries.find`
 * in place of `entries.lockAndFind` in [EraseEntry], `EntryStore.lockAndFind`
 * without its `refresh`, and `days.find` in place of `days.lockAndFind`.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LongParameterList") // What Spring hands the test; each is used, and there is nothing to bundle them into.
internal class EraseFreshReadTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val eraser: EraseEntry,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val pool = Executors.newFixedThreadPool(2)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(at(13))
    }

    @AfterEach
    fun clear() {
        rig.clear()
        users.clear()
        clock.set(at(15))
    }

    @AfterAll
    fun stop() {
        pool.shutdownNow()
    }

    /**
     * A `DELETE` finds its entry by id before it holds anything: that is how
     * it learns which bond to lock. Here the bond is held, and while the
     * delete waits for it the same entry is erased and its day stepped back,
     * as a withdrawal or the author's other device would have done.
     *
     * The delete then has nothing left to do, and must know it. Working from
     * the copy it read first, it would see a live entry, erase it again
     * (moving `deleted_at`) and take a second entry off a day that only ever
     * counted one.
     */
    @Test
    fun `a delete that waited while its entry was erased elsewhere does not step the day back a second time`() {
        val bond = rig.pair(ada, bea)
        clock.set(at(15))
        val entry = rig.submit(ada, bond, WORDS)
        val day = dayOf(entry)
        val counted = dayRow(day)
        counted shouldBe mapOf("status" to "PARTIAL", "entry_count" to 1, "version" to counted["version"])
        val erasedElsewhereAt = Timestamp.from(at(15).plusSeconds(1))

        val status =
            whileHolding("SELECT pg_backend_pid() FROM bonds WHERE id = '$bond' FOR UPDATE") { holder, blocked ->
                val deleting = pool.submit(Callable { deleteStatus(ada, entry) })
                blocked()
                deleting.isDone shouldBe false
                // What one erasure writes, to the entry and to its day (`Entry.erase`, `BondDay.withoutEntry`).
                val erase = "UPDATE entries SET text = NULL, status = 'DELETED', deleted_at = ?, updated_at = ? WHERE id = ?::uuid"
                holder.prepareStatement(erase).use {
                    it.setTimestamp(1, erasedElsewhereAt)
                    it.setTimestamp(2, erasedElsewhereAt)
                    it.setString(3, entry)
                    it.executeUpdate() shouldBe 1
                }
                holder.createStatement().use {
                    it.executeUpdate(
                        "UPDATE bond_days SET status = 'OPEN', entry_count = 0, version = version + 1 WHERE id = '$day'",
                    ) shouldBe 1
                }
                holder.commit()
                deleting.get(20, TimeUnit.SECONDS)
            }

        status shouldBe 204
        // The day as the other erasure left it, version and all: the delete wrote nothing to it.
        dayRow(day) shouldBe mapOf("status" to "OPEN", "entry_count" to 0, "version" to (counted["version"] as Int) + 1)
        val row = jdbc.queryForMap("SELECT text, status, deleted_at, updated_at FROM entries WHERE id = ?::uuid", entry)
        row["text"].shouldBeNull()
        row["status"] shouldBe "DELETED"
        // Erased once, when the other connection did it.
        row["deleted_at"] shouldBe erasedElsewhereAt
        row["updated_at"] shouldBe erasedElsewhereAt
    }

    /**
     * The withdrawal's handler holds the bond and calls [EraseEntry] with no
     * day locked: [EraseEntry] takes the day itself. Here the day's row is
     * held, the delivery is seen waiting **on that row**, and the day is
     * settled before it is let go.
     *
     * A settled day is history and is not stepped back (BR-10), so the
     * delivery must erase the entry and leave the day exactly as it was
     * settled. Had it read the day without the lock it would have read it
     * unsettled, stepped it back to an empty open day, and then either
     * written that over the settled row or failed on the row's version.
     *
     * Nothing in the application settles a day without the bond's lock, which
     * the handler holds, so this cannot happen today. It is pinned because
     * [EraseEntry] says it is safe for a caller that has not locked the day,
     * and that sentence had no test.
     */
    @Test
    fun `a withdrawal waits for the day's own lock and decides from the day as it was committed meanwhile`() {
        val bond = rig.pair(ada, bea)
        clock.set(at(15))
        val entry = rig.submit(ada, bond, WORDS)
        val day = dayOf(entry)
        rig.block(ada, bond)
        val now = clock.instant()
        val counted = dayRow(day)

        val result =
            whileHolding("SELECT pg_backend_pid() FROM bond_days WHERE id = '$day' FOR UPDATE") { holder, blocked ->
                val dispatching = pool.submit(Callable { dispatcher.dispatchDue(now, 10) })
                blocked()
                dispatching.isDone shouldBe false
                holder.createStatement().use {
                    it.executeUpdate(
                        "UPDATE bond_days SET status = 'SOLO', closed_at = ends_at, version = version + 1 WHERE id = '$day'",
                    ) shouldBe 1
                }
                holder.commit()
                dispatching.get(20, TimeUnit.SECONDS)
            }

        result shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        // As it was settled, version and all: the erasure wrote nothing to it.
        dayRow(day) shouldBe mapOf("status" to "SOLO", "entry_count" to 1, "version" to (counted["version"] as Int) + 1)
        jdbc.queryForMap("SELECT text, status FROM entries WHERE id = ?::uuid", entry) shouldBe mapOf("text" to null, "status" to "DELETED")
    }

    /**
     * [EraseEntry] is told which day to lock by its caller, and steps that
     * day back. Told the wrong one, it would take an entry off a day that
     * never counted it; so it checks, under both locks, before it writes.
     */
    @Test
    fun `an entry is not erased against a day it is not filed on, and nothing is written`() {
        val bond = rig.pair(ada, bea)
        clock.set(at(14))
        val yesterdays = rig.submit(ada, bond, WORDS)
        clock.set(at(15))
        val todays = rig.submit(ada, bond, WORDS)
        val before = rig.wholeEntries(bond) to rig.wholeDays(bond)
        val wrongDay = dayOf(yesterdays)

        shouldThrow<IllegalStateException> {
            transactions.executeWithoutResult {
                eraser.erase(EntryId(UUID.fromString(todays)), BondDayId(UUID.fromString(wrongDay)), clock.instant())
            }
        }

        (rig.wholeEntries(bond) to rig.wholeDays(bond)) shouldBe before
        // Not vacuous: the day it was pointed at is one it would have stepped back.
        dayRow(wrongDay)["status"] shouldBe "PARTIAL"
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Runs [work] with a second connection holding the rows [lock] selects
     * `FOR UPDATE`. [work] is handed that connection, still in its
     * transaction, and a function that returns once exactly one other
     * backend is waiting on it for a lock. Whatever [work] left uncommitted
     * is rolled back.
     */
    private fun <T> whileHolding(
        lock: String,
        work: (holder: Connection, blocked: () -> Unit) -> T,
    ): T =
        dataSource.connection.use { holder ->
            holder.autoCommit = false
            try {
                val pid = pidAfter(holder, lock)
                work(holder) { await().atMost(Duration.ofSeconds(20)).until { waitingOn(pid) == 1 } }
            } finally {
                holder.rollback()
            }
        }

    /** Runs [lock] on [holder] and answers the `pg_backend_pid()` it selects. */
    private fun pidAfter(
        holder: Connection,
        lock: String,
    ): Int =
        holder.createStatement().use { statement ->
            statement.executeQuery(lock).use { rows ->
                check(rows.next())
                rows.getInt(1)
            }
        }

    /** How many backends are waiting for a lock that the backend [pid] holds. */
    private fun waitingOn(pid: Int): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ? = ANY (pg_blocking_pids(pid))",
            Int::class.java,
            pid,
        )!!

    private fun deleteStatus(
        user: UUID,
        entry: String,
    ): Int =
        mockMvc
            .delete("/api/v1/entries/$entry") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response.status

    private fun dayOf(entry: String): String =
        jdbc.queryForObject("SELECT bond_day_id::text FROM entries WHERE id = ?::uuid", String::class.java, entry)!!

    private fun dayRow(day: String): Map<String, Any?> =
        jdbc
            .queryForMap("SELECT status, entry_count, version FROM bond_days WHERE id = ?::uuid", day)
            .mapValues { (_, value) -> if (value is Number) value.toInt() else value }

    private fun at(day: Int): Instant = Instant.parse("2026-09-${day}T10:00:00Z")

    private companion object {
        /** Not spellable in hexadecimal: it could be looked for in anything that prints an id. */
        const val WORDS = "Thank you~ for waiting"
    }
}
