package com.moyi.gratitude.service

import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.web.EntryChangesTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `gratitude`'s share of an account deletion (BR-10, doc 07 §7, ADR-0058 decision 8), through
 * the real outbox: identity's event, the real dispatcher, and with it `bond`'s consumer of the
 * same event, as the application runs them.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class EraseDeletedAccountTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val closer: DayCloser,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entry_report_log, entry_reports, entry_favourites, reactions")
        rig.clear()
        users.clear()
    }

    @Test
    fun `the person's entries become flagged tombstones, the partner's words and the settled days stay`() {
        val bond = written()
        val days = rig.wholeDays(bond).associateBy { it["date"].toString() }
        val streak = streakOf(bond)
        val beas = rig.wholeEntries(bond).single { it["author_member_id"] == rig.memberId(bond, bea) }

        deleteAccount(ada)

        val after = rig.wholeEntries(bond)
        val adas = after.filter { it["author_member_id"] == rig.memberId(bond, ada) }
        adas.size shouldBe 3
        for (row in adas) {
            row["text"].shouldBeNull()
            row["text_search"].shouldBeNull()
            row["status"] shouldBe "DELETED"
            row["author_deleted_account"] shouldBe true
        }
        after.single { it["author_member_id"] == rig.memberId(bond, bea) } shouldBe beas
        // Settled days are a record (BR-10): the partner's streak is what it was.
        val daysAfter = rig.wholeDays(bond).associateBy { it["date"].toString() }
        for (settled in listOf("2026-09-13", "2026-09-14")) daysAfter.getValue(settled) shouldBe days.getValue(settled)
        streakOf(bond) shouldBe streak
        // And bond's consumer of the same event ended it.
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, bond) shouldBe "ARCHIVED"
    }

    @Test
    fun `an entry the person had already deleted is flagged too`() {
        val bond = written()
        val first = rig.wholeEntries(bond).first { it["author_member_id"] == rig.memberId(bond, ada) && it["status"] == "SUBMITTED" }
        rig.delete(ada, first["id"].toString())

        deleteAccount(ada)

        rig.wholeEntries(bond).single { it["id"] == first["id"] }["author_deleted_account"] shouldBe true
    }

    @Test
    fun `their bookmarks and reactions go, and their reports keep everything but their words`() {
        val bond = written()
        val adasMember = rig.memberId(bond, ada)
        val beasEntry = rig.wholeEntries(bond).single { it["author_member_id"] == rig.memberId(bond, bea) }["id"] as UUID
        rig.react(ada, beasEntry.toString(), "HEART")
        jdbc.update("INSERT INTO entry_favourites (entry_id, member_id) VALUES (?, ?)", beasEntry, adasMember)
        val report = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entry_reports (id, target, entry_id, bond_id, reporter_member_id, reason, detail, status, created_at) " +
                "VALUES (?, 'ENTRY', ?, ?::uuid, ?, 'OTHER', 'what Ada wrote to a moderator', 'OPEN', now())",
            report,
            beasEntry,
            bond,
            adasMember,
        )

        deleteAccount(ada)

        jdbc.queryForList("SELECT * FROM reactions WHERE member_id = ?", adasMember).shouldBeEmpty()
        jdbc.queryForList("SELECT * FROM entry_favourites WHERE member_id = ?", adasMember).shouldBeEmpty()
        val row = jdbc.queryForMap("SELECT * FROM entry_reports WHERE id = ?", report)
        row["detail"].shouldBeNull()
        row["status"] shouldBe "OPEN"
        row["reason"] shouldBe "OTHER"
    }

    @Test
    fun `a bond the person left long ago is erased as well, and the other person's other bond is not touched`() {
        val left = written()
        rig.leave(ada, left)
        val beasOther = rig.pair(bea, users.verified("Cleo"))
        rig.submit(bea, beasOther, "Bea in another bond")
        val otherBefore = rig.wholeEntries(beasOther)

        deleteAccount(ada)

        rig.wholeEntries(left).filter { it["author_member_id"] == rig.memberId(left, ada) }.forEach { it["text"].shouldBeNull() }
        rig.wholeEntries(beasOther) shouldBe otherBefore
    }

    @Test
    fun `delivered twice, the second changes nothing`() {
        val bond = written()
        deleteAccount(ada)
        val once = rig.wholeEntries(bond) to rig.wholeDays(bond)

        deleteAccount(ada)

        (rig.wholeEntries(bond) to rig.wholeDays(bond)) shouldBe once
    }

    /** Ada and Bea on the 13th (revealed), Ada alone on the 14th (settled), Ada on the 15th (open). */
    private fun written(): String {
        clock.set(at(12))
        val bond = rig.pair(ada, bea)
        clock.set(at(13))
        rig.submit(ada, bond, "Ada thirteen")
        rig.submit(bea, bond, "Bea thirteen")
        clock.set(at(14))
        rig.submit(ada, bond, "Ada fourteen")
        clock.set(Instant.parse("2026-09-15T11:01:00Z"))
        closer.closeElapsedDays(clock.instant(), 1_000).failed shouldBe 0
        clock.set(at(15))
        rig.submit(ada, bond, "Ada fifteen")
        return bond
    }

    private fun deleteAccount(userId: UUID) {
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), 1_000)
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("User", userId, "AccountDeleted", mapOf("userId" to userId), clock.instant()))
        }
        val result = dispatcher.dispatchDue(clock.instant().plusSeconds(1), 1_000)
        result.failed shouldBe 0
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id " +
                "WHERE e.event_type = 'AccountDeleted' AND d.processed_at IS NULL",
            Int::class.java,
        ) shouldBe 0
    }

    private fun streakOf(bond: String): Map<String, Any?> =
        jdbc.queryForMap("SELECT current_streak, longest_streak, total_complete_days FROM streak_states WHERE bond_id = ?::uuid", bond)

    private fun at(day: Int): Instant = Instant.parse("2026-09-${day}T10:00:00Z")
}
