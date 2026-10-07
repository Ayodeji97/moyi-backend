package com.moyi.gratitude.service

import com.moyi.common.events.DispatchResult
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A withdrawal is recorded when the bond ends and its entries are erased
 * afterwards, by the outbox's consumer. In between, the close job or a read
 * can reach one of that author's days first. These tests are about that
 * interval (review of C5a Task 5, finding A1): **whoever reaches the day
 * first must leave what the consumer would have left**, and that in turn is
 * what the author's own `DELETE` at that instant leaves.
 *
 * **Each test runs the same story on several bonds at once and compares them
 * whole** ([WithdrawalRig.Snapshot]: every entry and day, the streak, the
 * events, and what both members are answered). One bond meets the close job
 * (or a read) before the dispatcher, one the dispatcher first, and on the
 * third the author deletes by hand and ends the bond keeping what is left.
 * The order is made by putting one bond's delivery off, as a failure backing
 * off would; nothing sleeps and nothing runs at the same time, because the
 * bond's lock already makes the two take turns and the question is only what
 * each turn leaves.
 *
 * The expectations are the twin's, not ones written here: what a lone entry
 * on an ended bond becomes at midnight is the close job's own rule
 * (ADR-0033 decision 9), and a test that restated it would be a second copy.
 * The few things asserted outright are the ones the finding named: an id and
 * timestamps the partner must never be handed, words the withdrawer must
 * never read, a streak that must not move.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used, and there is nothing to bundle them into.
internal class WithdrawalRaceTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val closer: DayCloser,
    @Autowired private val json: ObjectMapper,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    /** Before as well as after: the close job and the dispatcher work on every bond other test classes left behind. */
    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    @AfterEach
    fun clear() {
        rig.clear()
        users.clear()
        clock.set(MIDDAY)
    }

    @Test
    fun `a day waiting for its reveal time is not revealed once one of its authors has withdrawn, whoever reaches it first`() {
        val bonds = threeBonds()
        bonds.all.forEach { jdbc.update("UPDATE bonds SET reveal_time_local = '20:00' WHERE id = ?::uuid", it) shouldBe 1 }
        clock.set(MIDDAY)
        val adas = bonds.all.associateWith { rig.submit(ada, it, ADAS_WORDS) }
        bonds.all.forEach { rig.submit(bea, it, BEAS_WORDS) }
        bonds.all.forEach { dayOf(it) shouldBe "PENDING_REVEAL 2" }

        clock.set(MIDDAY.plusSeconds(3_600))
        endAll(bonds, adas)

        // Half past eight in the evening in Lagos: the reveal time has come, the day has not ended.
        clock.set(Instant.parse("2026-09-15T19:30:00Z"))
        closer.closeElapsedDays(clock.instant(), BUDGET).failed shouldBe 0

        // The close job got to one bond before the consumer did, and left what the consumer left on the other.
        rig.delivery(bonds.closerFirst)["processed_at"].shouldBeNull()
        val twin = rig.snapshot(bonds.deleted, ada, bea)
        rig.snapshot(bonds.closerFirst, ada, bea) shouldBe twin
        rig.snapshot(bonds.consumerFirst, ada, bea) shouldBe twin
        for (bond in bonds.withdrawn) {
            dayOf(bond) shouldBe "PARTIAL 1"
            nothingOfEitherWasShown(bond, adas.getValue(bond))
            streakOf(bond) shouldBe streakOf(bonds.deleted)
            json.readTree(streakOf(bond))["current"].asInt() shouldBe 0
        }

        // The consumer arrives second, and finds nothing left to do.
        val before = rows(bonds.closerFirst)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        rows(bonds.closerFirst) shouldBe before
        bonds.withdrawn.forEach { rig.snapshot(it, ada, bea) shouldBe twin }
        // The same number of writes reached each day, too.
        bonds.withdrawn.forEach { versions(it) shouldBe versions(bonds.deleted) }

        // Midnight: the partner's lone entry, on a bond that ended before the day did. Whatever the twin's becomes.
        clock.set(Instant.parse("2026-09-15T23:02:00Z"))
        closer.closeElapsedDays(clock.instant(), BUDGET).failed shouldBe 0
        val settled = rig.snapshot(bonds.deleted, ada, bea)
        bonds.withdrawn.forEach { rig.snapshot(it, ada, bea) shouldBe settled }
        // Not vacuous: the day did close, in all three.
        bonds.all.forEach { closedDays(it) shouldBe closedDays(bonds.deleted) }
        closedDays(bonds.deleted).last().endsWith(" closed") shouldBe true
        bonds.withdrawn.forEach { revealedEntries(it) shouldBe 0 }

        nothingChangesOnASecondRun(bonds)
    }

    @Test
    fun `a lone entry withdrawn just after its day ended is not revealed by the close, whoever reaches it first`() {
        val bonds = threeBonds()
        clock.set(MIDDAY)
        val adas = bonds.all.associateWith { rig.submit(ada, it, ADAS_WORDS) }

        // Ten seconds after the 15th ended in Lagos: the bond ends after the day did, so the close would reveal a lone entry.
        clock.set(Instant.parse("2026-09-15T23:00:10Z"))
        endAll(bonds, adas)

        clock.set(Instant.parse("2026-09-15T23:02:00Z"))
        closer.closeElapsedDays(clock.instant(), BUDGET).failed shouldBe 0

        rig.delivery(bonds.closerFirst)["processed_at"].shouldBeNull()
        val twin = rig.snapshot(bonds.deleted, ada, bea)
        rig.snapshot(bonds.closerFirst, ada, bea) shouldBe twin
        rig.snapshot(bonds.consumerFirst, ada, bea) shouldBe twin
        for (bond in bonds.withdrawn) {
            // Nobody wrote, as far as the day is concerned: it is the day a delete leaves.
            closedDays(bond).last() shouldBe "2026-09-15 EMPTY 0 closed"
            revealedEntries(bond) shouldBe 0
            entry(adas.getValue(bond))["status"] shouldBe "DELETED"
            streakOf(bond) shouldBe streakOf(bonds.deleted)
        }

        val before = rows(bonds.closerFirst)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        rows(bonds.closerFirst) shouldBe before
        bonds.withdrawn.forEach { rig.snapshot(it, ada, bea) shouldBe twin }
        bonds.withdrawn.forEach { versions(it) shouldBe versions(bonds.deleted) }

        nothingChangesOnASecondRun(bonds)
    }

    /**
     * The other caller of the reveal: the joining day, which the first
     * request to meet it takes out of `SUSPENDED`, on an ended bond too
     * (reads are still answered there). With both entries on it, that is a
     * reveal. No request can leave a joining day suspended with two entries
     * any more, so the day is the one C1 left and is made by hand, as
     * `JoiningDayTest` makes it.
     *
     * **There is no delete twin here, and cannot be.** The author's `DELETE`
     * is itself a gratitude operation, so it resumes and reveals the day
     * before it erases: on such a day a delete always leaves a revealed
     * tombstone. A withdrawal is recorded by `bond` and meets no day. What
     * can be held is that the three orders agree with each other, and that
     * nothing is shown.
     */
    @Test
    fun `a suspended joining day with both entries is not revealed once one author has withdrawn, whoever reaches it first`() {
        clock.set(MIDDAY)
        val readerFirst = legacyJoiningDay()
        val consumerFirst = legacyJoiningDay()
        val closerFirst = legacyJoiningDay()
        val all = listOf(readerFirst, consumerFirst, closerFirst)
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        all.forEach { dayOf(it.bond) shouldBe "SUSPENDED 2" }

        clock.set(MIDDAY.plusSeconds(3_600))
        all.forEach { rig.block(ada, it.bond) }
        val midnight = Instant.parse("2026-09-15T23:02:00Z")
        rig.postpone(readerFirst.bond, midnight)
        rig.postpone(closerFirst.bond, midnight)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)

        // The partner opens the app: the first gratitude operation to meet the day, on two of the three.
        for (legacy in listOf(readerFirst, consumerFirst)) {
            rig.today(bea, legacy.bond)
            dayOf(legacy.bond) shouldBe "PARTIAL 1"
            nothingOfEitherWasShown(legacy.bond, legacy.adasEntry)
        }
        rig.delivery(readerFirst.bond)["processed_at"].shouldBeNull()
        rig.snapshot(readerFirst.bond, ada, bea) shouldBe rig.snapshot(consumerFirst.bond, ada, bea)

        // Nobody has read the third. The close job is the first thing to meet its joining day.
        dayOf(closerFirst.bond) shouldBe "SUSPENDED 2"
        clock.set(midnight)
        closer.closeElapsedDays(clock.instant(), BUDGET).failed shouldBe 0
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 2, failed = 0, more = false)

        val settled = rig.snapshot(consumerFirst.bond, ada, bea)
        rig.snapshot(readerFirst.bond, ada, bea) shouldBe settled
        rig.snapshot(closerFirst.bond, ada, bea) shouldBe settled
        all.forEach { revealedEntries(it.bond) shouldBe 0 }
        all.forEach { entry(it.adasEntry)["status"] shouldBe "DELETED" }
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'DayRevealed'", Int::class.java) shouldBe 0
    }

    // --- the three bonds ---

    private data class Bonds(
        val closerFirst: String,
        val consumerFirst: String,
        val deleted: String,
    ) {
        val withdrawn = listOf(closerFirst, consumerFirst)
        val all = withdrawn + deleted
    }

    /** Made the day before, so that the day under test is an ordinary day and not the one the couple paired on. */
    private fun threeBonds(): Bonds {
        clock.set(MIDDAY.minusSeconds(DAY))
        return Bonds(rig.pair(ada, bea), rig.pair(ada, bea), rig.pair(ada, bea))
    }

    /**
     * Ada ends all three at this instant. On one she deletes her entry and
     * keeps what is left; on the other two she takes her words back, and the
     * consumer then reaches one of them and is kept from the other until the
     * close job has run.
     */
    private fun endAll(
        bonds: Bonds,
        adas: Map<String, String>,
    ) {
        rig.delete(ada, adas.getValue(bonds.deleted))
        rig.block(ada, bonds.deleted, withdraw = false)
        bonds.withdrawn.forEach { rig.block(ada, it) }
        rig.postpone(bonds.closerFirst, Instant.parse("2026-09-15T19:30:00Z").coerceAtLeast(clock.instant().plusSeconds(60)))
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        rig.delivery(bonds.closerFirst)["processed_at"].shouldBeNull()
    }

    /** The close job again, and both deliveries made again: no row of any of the three moves. */
    private fun nothingChangesOnASecondRun(bonds: Bonds) {
        val before = bonds.all.map { rows(it) }
        clock.set(clock.instant().plusSeconds(900))
        val again = closer.closeElapsedDays(clock.instant(), BUDGET)
        (again.closed to again.revealed) shouldBe (0 to 0)
        again.failed shouldBe 0
        jdbc.update("UPDATE outbox_deliveries SET processed_at = NULL") shouldBe 2
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 2, failed = 0, more = false)
        bonds.all.map { rows(it) } shouldBe before
    }

    private data class Legacy(
        val bond: String,
        val adasEntry: String,
    )

    /** What C1 committed for a couple who both wrote on the day they paired: `SUSPENDED` with two entries, neither revealed. */
    private fun legacyJoiningDay(): Legacy {
        val created = rig.create(ada)
        val bond = rig.idOf(created)
        val adasEntry = rig.submit(ada, bond, ADAS_WORDS)
        rig.accept(bea, rig.codeOf(created))
        rig.submit(bea, bond, BEAS_WORDS)
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 1
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 2
        return Legacy(bond, adasEntry)
    }

    // --- what is asserted outright ---

    /**
     * Asked on the day itself, through `GET /today`. The partner is told an
     * entry was removed and by whom, and nothing that would name it or date
     * it (BR-8: it was never hers to see). The withdrawer does not read the
     * partner's words. And no row says otherwise.
     */
    private fun nothingOfEitherWasShown(
        bond: String,
        adasEntry: String,
    ) {
        val hers = rig.today(bea, bond)
        val removed = json.readTree(hers)["partnerEntry"]
        removed.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        removed["status"].asString() shouldBe "REMOVED"
        hers shouldNotContain adasEntry
        hers shouldNotContain ADAS_WORDS

        val his = rig.today(ada, bond)
        his shouldNotContain BEAS_WORDS
        his shouldNotContain ADAS_WORDS
        json.readTree(his)["partnerEntry"]["status"].asString() shouldBe "LOCKED"

        revealedEntries(bond) shouldBe 0
        entry(adasEntry)["status"] shouldBe "DELETED"
        entry(adasEntry)["text"].shouldBeNull()
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'DayRevealed' AND payload ->> 'bondId' = ?",
            Int::class.java,
            bond,
        ) shouldBe 0
    }

    private fun entry(id: String): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM entries WHERE id = ?::uuid", id)

    private fun revealedEntries(bond: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND revealed_at IS NOT NULL", Int::class.java, bond)!!

    /** The 15th, as `STATUS count`. */
    private fun dayOf(bond: String): String =
        jdbc.queryForObject(
            "SELECT status || ' ' || entry_count FROM bond_days WHERE bond_id = ?::uuid AND date = '2026-09-15'",
            String::class.java,
            bond,
        )!!

    private fun closedDays(bond: String): List<String> =
        jdbc
            .queryForList(
                """
                SELECT date || ' ' || status || ' ' || entry_count || CASE WHEN closed_at IS NULL THEN ' open' ELSE ' closed' END
                FROM bond_days WHERE bond_id = ?::uuid ORDER BY date
                """.trimIndent(),
                String::class.java,
                bond,
            ).filterNotNull()

    private fun streakOf(bond: String): String = rig.get(bea, "/api/v1/bonds/$bond/streak")

    private fun versions(bond: String): List<Any?> = rig.wholeDays(bond).map { it["version"] }

    /** Every column of every entry and day: for "no row moved". */
    private fun rows(bond: String) = rig.wholeEntries(bond) to rig.wholeDays(bond)

    private companion object {
        /** Eleven in the morning in Lagos on the 15th. */
        val MIDDAY: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val DAY = 86_400L
        const val BUDGET = 1_000
        const val ADAS_WORDS = "ada took these back"
        const val BEAS_WORDS = "bea wrote these for the two of them"
    }
}
