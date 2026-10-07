package com.moyi.gratitude.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.common.events.DispatchResult
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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * The outbox's first consumer (spec §6.7, plan C5a decision 12): a member who
 * ended a bond and took their words back has them erased, by the routine a
 * single `DELETE` uses, in the transaction that acknowledges the delivery.
 *
 * **Every test ends a real bond over HTTP and then runs the real dispatcher**,
 * outside any transaction, as the poller does. Nothing calls the handler
 * directly: what is on trial is the event as `bond` publishes it, the claim,
 * the handler and the acknowledgement together.
 *
 * **This is also the first consumer that writes through JPA.** The
 * dispatcher's own tests run a JDBC transaction manager, so "the handler's
 * work and the acknowledgement commit or vanish together" had only ever been
 * shown for JDBC. The three tests under "commit together" show it here, with
 * the failure put in three different places: in the handler, in the
 * acknowledgement after it, and at the commit after both.
 *
 * Failures are made by the database, with a trigger the test creates and
 * drops, and not by a stand-in bean: the handler, its stores and the
 * dispatcher are the ones the application runs.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LongParameterList") // What Spring hands the test; each is used, and there is nothing to bundle them into.
internal class WithdrawEntriesTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val closer: DayCloser,
    @Autowired private val publisher: EventPublisher,
    @Autowired manager: PlatformTransactionManager,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val transactions = TransactionTemplate(manager)
    private val pool = Executors.newFixedThreadPool(2)

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(at(12))
    }

    @AfterEach
    fun clear() {
        resetTriggers()
        rig.clear()
        users.clear()
        clock.set(at(15))
    }

    @AfterAll
    fun stop() {
        pool.shutdownNow()
    }

    // --- what is erased, and what is not ---

    @Test
    fun `a withdrawal erases every live entry of its author in that bond, and nothing else`() {
        val bond = rig.pair(ada, bea)
        val other = rig.pair(ada, bea)
        val written = threeDays(bond, other).getValue(bond)
        val adas = rig.memberId(bond, ada)
        val before = rig.wholeEntries(bond).associateBy { it["id"].toString() }
        val daysBefore = rig.wholeDays(bond).associateBy { it["date"].toString() }
        val otherBefore = rig.wholeEntries(other) to rig.wholeDays(other)
        daysBefore.mapValues { "${it.value["status"]} ${it.value["entry_count"]}" } shouldBe
            mapOf("2026-09-12" to "EMPTY 0", "2026-09-13" to "REVEALED 2", "2026-09-14" to "SOLO 1", "2026-09-15" to "PARTIAL 1")

        rig.block(ada, bond)
        val eventsBefore = events()
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)

        val after = rig.wholeEntries(bond).associateBy { it["id"].toString() }
        for (id in written.adas) {
            val row = after.getValue(id)
            row["text"].shouldBeNull()
            row["status"] shouldBe "DELETED"
            row["deleted_at"] shouldBe Timestamp.from(clock.instant())
            row["image_media_id"].shouldBeNull()
            row["voice_media_id"].shouldBeNull()
            row["voice_duration_ms"].shouldBeNull()
            // Whether it had been read is history, and an erasure does not rewrite it (BR-10).
            row["revealed_at"] shouldBe before.getValue(id)["revealed_at"]
            row["author_deleted_account"] shouldBe false
            row["author_member_id"] shouldBe adas
        }
        // Two of the three had been revealed, so the line above was about something.
        written.adas.map { after.getValue(it)["revealed_at"] != null } shouldContainExactly listOf(true, true, false)
        // The partner withdrew nothing: her row is the row it was, every column.
        after.getValue(written.beas) shouldBe before.getValue(written.beas)
        after.getValue(written.beas)["text"] shouldBe "${BEAS}13"

        // A settled day is a record. The one still being written is counted again without the entry.
        val daysAfter = rig.wholeDays(bond).associateBy { it["date"].toString() }
        for (settled in listOf("2026-09-12", "2026-09-13", "2026-09-14")) daysAfter.getValue(settled) shouldBe daysBefore.getValue(settled)
        daysAfter.getValue("2026-09-15")["status"] shouldBe "OPEN"
        daysAfter.getValue("2026-09-15")["entry_count"].toString() shouldBe "0"

        // The same two people's other bond, where the same member wrote the same days.
        (rig.wholeEntries(other) to rig.wholeDays(other)) shouldBe otherBefore

        // Acknowledged, and nothing published by the erasure.
        val delivery = rig.delivery(bond)
        delivery["processed_at"] shouldBe Timestamp.from(clock.instant())
        delivery["attempts"] shouldBe 1
        delivery["last_error"].shouldBeNull()
        events() shouldBe eventsBefore
    }

    @Test
    fun `a withdrawal leaves the rows and the answers that a run of single deletes leaves, and the close job then settles both alike`() {
        val withdrawn = rig.pair(ada, bea)
        val deleted = rig.pair(ada, bea)
        val written = threeDays(withdrawn, deleted)

        // One bond: the member deletes her entries one at a time, and ends it keeping what is left.
        written.getValue(deleted).adas.forEach { rig.delete(ada, it) }
        rig.block(ada, deleted, withdraw = false)
        // The other: she ends it and takes her words back.
        rig.block(ada, withdrawn)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)

        val twin = rig.snapshot(deleted, ada, bea)
        rig.snapshot(withdrawn, ada, bea) shouldBe twin
        // Not vacuous: the day that was still open stepped back, in both.
        twin.days.last().contains("status=OPEN, ") shouldBe true
        twin.read.forEach { it shouldNotContain ADAS }
        // The days' versions too: the same number of writes reached each day.
        rig.wholeDays(withdrawn).map { it["version"] } shouldBe rig.wholeDays(deleted).map { it["version"] }

        // Midnight. The stepped-back day is an ordinary empty day to the close job.
        clock.set(Instant.parse("2026-09-15T23:01:00Z"))
        closer.closeElapsedDays(clock.instant(), 1_000).failed shouldBe 0

        val settled = rig.snapshot(deleted, ada, bea)
        rig.snapshot(withdrawn, ada, bea) shouldBe settled
        settled.days.last().contains("status=EMPTY, ") shouldBe true
        // Nothing was revealed by the close: the erased entry of the 15th was never read and never will be.
        rig.wholeEntries(withdrawn).single { it["id"].toString() == written.getValue(withdrawn).adas.last() }["revealed_at"].shouldBeNull()
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE event_type = 'DayRevealed' AND occurred_at >= ?",
            Int::class.java,
            Timestamp.from(at(15)),
        ) shouldBe 0
    }

    /**
     * The one place a withdrawal and a run of deletes do **not** leave the
     * same rows, and how far the difference goes.
     *
     * A `DELETE` reconciles the couple's joining day before it erases
     * (`ChangeEntry`); the consumer does not, and is not meant to: it does
     * what an erasure does and nothing a read would do. So where one member
     * wrote while waiting, the other accepted, and nobody has opened the
     * app since, the day is still `SUSPENDED` after a withdrawal and has
     * been resumed after a delete. That is a day nobody has met since the
     * pairing, left as it would be with no withdrawal at all.
     *
     * What must hold, and does: the entries are the same rows, both members
     * are answered the same bytes, and the read that answers them
     * reconciles the day, after which the days are the same rows too.
     */
    @Test
    fun `a joining day nobody has met since pairing is all that differs from a delete, and the first read removes the difference`() {
        clock.set(at(15))

        fun writtenWhileWaiting(): Pair<String, String> {
            val created = rig.create(ada)
            val bond = rig.idOf(created)
            val entry = rig.submit(ada, bond, "${ADAS}15")
            rig.accept(bea, rig.codeOf(created))
            return bond to entry
        }
        val (withdrawn, _) = writtenWhileWaiting()
        val (deleted, adasEntry) = writtenWhileWaiting()
        for (bond in listOf(withdrawn, deleted)) {
            rig.wholeDays(bond).map { "${it["status"]} ${it["entry_count"]}" } shouldBe listOf("SUSPENDED 1")
        }

        rig.delete(ada, adasEntry)
        rig.block(ada, deleted, withdraw = false)
        rig.block(ada, withdrawn)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)

        // A snapshot reads the rows first and asks the two members afterwards, so these days are as the endings left them.
        val afterWithdrawal = rig.snapshot(withdrawn, ada, bea)
        val afterDelete = rig.snapshot(deleted, ada, bea)
        afterWithdrawal.entries shouldBe afterDelete.entries
        afterWithdrawal.read shouldBe afterDelete.read
        afterWithdrawal.read.forEach { it shouldNotContain ADAS }
        // The difference this test is about; were it gone, the sentences above would be out of date.
        afterWithdrawal.days.single().contains("status=SUSPENDED, ") shouldBe true
        afterDelete.days.single().contains("status=OPEN, ") shouldBe true

        // Both have now been read, by both members: nothing tells the two bonds apart.
        val read = rig.snapshot(deleted, ada, bea)
        rig.snapshot(withdrawn, ada, bea) shouldBe read
        read.days.single().contains("status=OPEN, ") shouldBe true
        rig.wholeDays(withdrawn).map { it["version"] } shouldBe rig.wholeDays(deleted).map { it["version"] }
    }

    /**
     * [EraseEntry] passes over an entry that is already erased, which is
     * what lets the author's own `DELETE` and the withdrawal meet at one
     * entry. Both orders, on bonds that both recorded a withdrawal: the day
     * that was still open is stepped back once, whoever came second wrote
     * nothing, and the two bonds cannot be told apart.
     */
    @Test
    fun `the author's own delete and the withdrawal reach the same entry in either order and leave the same rows`() {
        val deleteFirst = rig.pair(ada, bea)
        val consumerFirst = rig.pair(ada, bea)
        val written = threeDays(deleteFirst, consumerFirst)
        listOf(deleteFirst, consumerFirst).forEach { rig.block(ada, it) }
        rig.postpone(deleteFirst, clock.instant().plusSeconds(60))

        // One: her delete of the entry on the open day, then the consumer, which finds two of three still live.
        rig.delete(ada, written.getValue(deleteFirst).adas.last())
        rig.wholeDays(deleteFirst).last()["entry_count"].toString() shouldBe "0"
        // The other: the consumer, then her delete of an entry it has already erased.
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)
        val erased = rig.wholeEntries(consumerFirst) to rig.wholeDays(consumerFirst)
        rig.delete(ada, written.getValue(consumerFirst).adas.last())
        (rig.wholeEntries(consumerFirst) to rig.wholeDays(consumerFirst)) shouldBe erased

        clock.set(clock.instant().plusSeconds(60))
        val deletedOnly = rig.wholeEntries(deleteFirst) to rig.wholeDays(deleteFirst)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)
        // The consumer passed over the entry she had deleted: its row is the row her delete left, `deleted_at` and all.
        val deletedId = written.getValue(deleteFirst).adas.last()
        val itsRow = { rows: List<Map<String, Any?>> -> rows.single { it["id"].toString() == deletedId } }
        itsRow(rig.wholeEntries(deleteFirst)) shouldBe itsRow(deletedOnly.first)
        // And no day moved: the open one had its step back from her delete, and the settled ones are history.
        rig.wholeDays(deleteFirst) shouldBe deletedOnly.second

        rig.snapshot(deleteFirst, ada, bea) shouldBe rig.snapshot(consumerFirst, ada, bea)
        rig.wholeDays(deleteFirst).map { it["version"] } shouldBe rig.wholeDays(consumerFirst).map { it["version"] }
        rig.wholeDays(deleteFirst).last()["status"] shouldBe "OPEN"
        rig.wholeDays(deleteFirst).last()["entry_count"].toString() shouldBe "0"
        val adas = rig.memberId(deleteFirst, ada)
        rig.wholeEntries(deleteFirst).filter { it["author_member_id"] == adas }.map { it["text"] } shouldBe List(3) { null }
    }

    @Test
    fun `a delivery made twice changes no row the second time`() {
        val bond = rig.pair(ada, bea)
        threeDays(bond)
        rig.block(ada, bond)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)
        // Every column, `updated_at` and the days' versions among them.
        val once = rig.wholeEntries(bond) to rig.wholeDays(bond)

        jdbc.update("UPDATE outbox_deliveries SET processed_at = NULL") shouldBe 1
        clock.set(at(15).plusSeconds(3_600))
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)

        (rig.wholeEntries(bond) to rig.wholeDays(bond)) shouldBe once
        rig.delivery(bond)["attempts"] shouldBe 2
    }

    @Test
    fun `a leave that takes the entries back erases them as a block does`() {
        val bond = rig.pair(ada, bea)
        val written = threeDays(bond).getValue(bond)

        rig.leave(bea, bond, withdraw = true)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)

        val rows = rig.wholeEntries(bond).associateBy { it["id"].toString() }
        rows.getValue(written.beas)["text"].shouldBeNull()
        rows.getValue(written.beas)["status"] shouldBe "DELETED"
        written.adas.map { rows.getValue(it)["text"] } shouldContainExactly listOf("${ADAS}13", "${ADAS}14", "${ADAS}15")
    }

    // --- an event that should not exist ---

    @Test
    fun `an event with no withdrawal recorded behind it erases nothing, and fails where it can be seen`() {
        val bond = rig.pair(ada, bea)
        threeDays(bond)
        rig.block(ada, bond, withdraw = false)
        val before = rig.wholeEntries(bond)
        // What `bond` never does: the event without the marker it writes in the same transaction.
        transactions.executeWithoutResult {
            val references = mapOf("bondId" to UUID.fromString(bond), "memberId" to rig.memberId(bond, ada))
            publisher.publish(OutboxEvent("Bond", UUID.fromString(bond), "EntriesWithdrawn", references, clock.instant()))
        }

        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 0, failed = 1, more = false)

        rig.wholeEntries(bond) shouldBe before
        val delivery = rig.delivery(bond)
        delivery["processed_at"].shouldBeNull()
        delivery["attempts"] shouldBe 1
        delivery["last_error"] shouldBe "java.lang.IllegalStateException"
    }

    @Test
    fun `an event that names no member fails, and one for a bond that is gone is acknowledged`() {
        val gone = UUID.randomUUID()
        transactions.executeWithoutResult {
            publisher.publish(
                OutboxEvent("Bond", gone, "EntriesWithdrawn", mapOf("bondId" to gone, "memberId" to UUID.randomUUID()), clock.instant()),
            )
        }
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        rig.delivery(gone.toString())["processed_at"].shouldNotBeNull()

        val nameless = UUID.randomUUID()
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("Bond", nameless, "EntriesWithdrawn", mapOf("bondId" to nameless), clock.instant()))
        }
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 0, failed = 1, more = false)
        rig.delivery(nameless.toString())["last_error"] shouldBe "java.lang.IllegalStateException"
    }

    // --- the handler's work and the acknowledgement commit together ---

    @Test
    fun `a handler that fails on the third of five entries erases none, and the delivery is retried`() {
        val (bond, entries) = fiveDays()
        rig.block(ada, bond)
        val before = rig.wholeEntries(bond) to rig.wholeDays(bond)
        failingOnUpdateOf("entries", "id", entries[2])

        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 0, failed = 1, more = false)

        // The first two were erased, and their days stepped back, inside the transaction that was then undone.
        (rig.wholeEntries(bond) to rig.wholeDays(bond)) shouldBe before
        val failed = rig.delivery(bond)
        failed["processed_at"].shouldBeNull()
        failed["attempts"] shouldBe 1
        failed["last_error"].toString() shouldMatch CLASS_NAME
        failed["next_attempt_at"] shouldBe Timestamp.from(clock.instant().plusSeconds(2))

        // Retried once it is due, and then all five go.
        resetTriggers()
        clock.set(clock.instant().plusSeconds(2))
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)
        rig.wholeEntries(bond).map { it["text"] } shouldBe List(5) { null }
        rig.delivery(bond)["attempts"] shouldBe 2
    }

    @Test
    fun `when the acknowledgement fails after the handler succeeded, no entry stays erased`() {
        val (bond, _) = fiveDays()
        rig.block(ada, bond)
        val before = rig.wholeEntries(bond) to rig.wholeDays(bond)
        // The acknowledgement is the only statement that sets `processed_at`; the record of a failure does not.
        jdbc.execute(
            """
            CREATE TRIGGER $TRIGGER BEFORE UPDATE ON outbox_deliveries FOR EACH ROW
            WHEN (NEW.processed_at IS NOT NULL) EXECUTE FUNCTION $FUNCTION()
            """.trimIndent(),
        )

        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 0, failed = 1, more = false)

        // The handler had erased all five by then: every one of them is back.
        (rig.wholeEntries(bond) to rig.wholeDays(bond)) shouldBe before
        before.first.map { it["text"] } shouldContainExactly listOf("${ADAS}11", "${ADAS}12", "${ADAS}13", "${ADAS}14", "${ADAS}15")
        val failed = rig.delivery(bond)
        failed["processed_at"].shouldBeNull()
        failed["attempts"] shouldBe 1
        failed["last_error"].toString() shouldMatch CLASS_NAME
    }

    @Test
    fun `a failure at the commit erases nothing, is recorded, and what the database said of the row reaches no log`() {
        val (bond, entries) = fiveDays()
        rig.block(ada, bond)
        val before = rig.wholeEntries(bond) to rig.wholeDays(bond)
        // A deferred constraint on the handler's last write: checked when the transaction commits,
        // after the handler has returned and the acknowledgement has been made. Its message quotes
        // the row as Postgres quotes a failing row, words and all.
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION $QUOTING_FUNCTION() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'Failing row contains (%, %)', OLD.id, OLD.text USING ERRCODE = 'check_violation';
            END ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE CONSTRAINT TRIGGER $TRIGGER AFTER UPDATE ON entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
            WHEN (OLD.id = '${entries.last()}') EXECUTE FUNCTION $QUOTING_FUNCTION()
            """.trimIndent(),
        )

        val said = everythingLoggedAtDebug { dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(0, 1, false) }

        (rig.wholeEntries(bond) to rig.wholeDays(bond)) shouldBe before
        val failed = rig.delivery(bond)
        failed["processed_at"].shouldBeNull()
        failed["attempts"] shouldBe 1
        failed["last_error"].toString() shouldMatch CLASS_NAME
        failed["last_error"].toString() shouldNotContain ADAS
        // The leak first, so that a failure here names the line that carried it.
        said.filter { it.contains(ADAS) || it.contains("Failing row") }.map { it.take(160) }.shouldBeEmpty()
        // And not vacuous: the transaction machinery was heard at DEBUG while it failed.
        said.count { it.contains("org.springframework.orm.jpa.JpaTransactionManager") } shouldBeGreaterThan 0
    }

    // --- nothing of an entry is said ---

    @Test
    fun `no entry text reaches a log line or the event while a withdrawal is made and delivered`() {
        val bond = rig.pair(ada, bea)
        threeDays(bond)

        val said =
            everythingLoggedAtDebug {
                rig.block(ada, bond)
                dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)
            }

        said.filter { it.contains(ADAS) || it.contains(BEAS) }.map { it.take(160) }.shouldBeEmpty()
        // Not vacuous: the handler's own statements were heard.
        said.count { it.contains("entries") } shouldBeGreaterThan 0
        val payloads = jdbc.queryForList("SELECT payload::text FROM outbox_events", String::class.java)
        payloads.forEach {
            it shouldNotContain ADAS
            it shouldNotContain BEAS
        }
        jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE event_type = 'EntriesWithdrawn'", String::class.java) shouldMatch
            Regex("""\{"bondId": "$UUID_PATTERN", "memberId": "$UUID_PATTERN"}""")
    }

    // --- the bond's lock ---

    /**
     * The handler takes the bond's row before it reads which entries are
     * live. Held here from another connection, the delivery is seen waiting
     * on it, and nothing is erased until it is let go. Without the lock the
     * handler would run straight through, and the wait below would time out.
     */
    @Test
    fun `the handler waits for the bond's lock, and completes when it is released`() {
        val bond = rig.pair(ada, bea)
        threeDays(bond)
        rig.block(ada, bond)
        val before = rig.wholeEntries(bond)

        val result =
            dataSource.connection.use { holder ->
                holder.autoCommit = false
                val pid =
                    holder.createStatement().use { statement ->
                        statement.executeQuery("SELECT pg_backend_pid() FROM bonds WHERE id = '$bond' FOR UPDATE").use { rows ->
                            check(rows.next())
                            rows.getInt(1)
                        }
                    }
                val now = clock.instant()
                val dispatching = pool.submit(Callable { dispatcher.dispatchDue(now, 10) })
                try {
                    // Blocked by the holder, on a row lock, and by nobody else.
                    await().atMost(Duration.ofSeconds(20)).until {
                        jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND ? = ANY (pg_blocking_pids(pid))",
                            Int::class.java,
                            pid,
                        ) == 1
                    }
                    dispatching.isDone shouldBe false
                    rig.wholeEntries(bond) shouldBe before
                } finally {
                    holder.rollback()
                }
                dispatching.get(20, TimeUnit.SECONDS)
            }

        result shouldBe DispatchResult(1, 0, false)
        rig.wholeEntries(bond).filter { it["author_member_id"] == rig.memberId(bond, ada) }.map { it["text"] } shouldBe List(3) { null }
    }

    // --- how long a long history takes ---

    /**
     * An author with five and a half years of entries, one a day, all on
     * settled days: the delivery is one transaction (`WithdrawEntries`' KDoc
     * says why), and that transaction has the dispatcher's sixty seconds. The
     * time is printed for the record; the assertion is only that it is
     * nowhere near the limit on the machine the build runs on.
     */
    @Test
    fun `two thousand entries are erased in one delivery, well inside its time limit`() {
        val bond = rig.pair(ada, bea)
        val author = rig.memberId(bond, ada)
        val first = LocalDate.of(2021, 1, 1)
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, revealed_at, closed_at, created_at)
            SELECT gen_random_uuid(), ?::uuid, ?::date + n, 'SOLO', 'Africa/Lagos',
                   (?::date + n)::timestamp AT TIME ZONE 'Africa/Lagos', (?::date + n + 1)::timestamp AT TIME ZONE 'Africa/Lagos',
                   1, NULL, (?::date + n + 1)::timestamp AT TIME ZONE 'Africa/Lagos', (?::date + n)::timestamp AT TIME ZONE 'Africa/Lagos'
            FROM generate_series(0, $MANY - 1) AS n
            """.trimIndent(),
            bond,
            first,
            first,
            first,
            first,
            first,
        ) shouldBe MANY
        jdbc.update(
            """
            INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at, revealed_at)
            SELECT gen_random_uuid(), d.id, d.bond_id, ?, '$ADAS' || d.date, 'REVEALED', d.starts_at, d.starts_at, d.closed_at, d.closed_at
            FROM bond_days d WHERE d.bond_id = ?::uuid
            """.trimIndent(),
            author,
            bond,
        ) shouldBe MANY
        rig.block(ada, bond)

        val started = System.nanoTime()
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(1, 0, false)
        val took = Duration.ofNanos(System.nanoTime() - started)

        println("WithdrawEntriesTest: $MANY entries erased in one delivery in ${took.toMillis()} ms")
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND text IS NULL AND status = 'DELETED'",
            Int::class.java,
            bond,
        ) shouldBe
            MANY
        took shouldBeLessThan Duration.ofSeconds(30)
    }

    // ---- helpers ----------------------------------------------------------

    private data class Written(
        /** Ada's entries, oldest first: the 13th (both wrote), the 14th (she alone; closed `SOLO`), the 15th (she alone; still open). */
        val adas: List<String>,
        /** Bea's one entry, on the 13th. */
        val beas: String,
    )

    /**
     * The same three days in each of [bonds], which were made on the 12th:
     * both write on the 13th, Ada alone on the 14th, the close job settles
     * both days, and Ada alone writes on the 15th, which is left open at
     * 10:00 UTC with the clock standing there.
     */
    private fun threeDays(vararg bonds: String): Map<String, Written> {
        clock.set(at(13))
        val a13 = bonds.associateWith { rig.submit(ada, it, "${ADAS}13") }
        val b13 = bonds.associateWith { rig.submit(bea, it, "${BEAS}13") }
        clock.set(at(14))
        val a14 = bonds.associateWith { rig.submit(ada, it, "${ADAS}14") }
        clock.set(Instant.parse("2026-09-14T23:01:00Z"))
        closer.closeElapsedDays(clock.instant(), 1_000).failed shouldBe 0
        clock.set(at(15))
        val a15 = bonds.associateWith { rig.submit(ada, it, "${ADAS}15") }
        return bonds.associateWith { Written(listOf(a13.getValue(it), a14.getValue(it), a15.getValue(it)), b13.getValue(it)) }
    }

    /**
     * A bond made on the 10th in which Ada alone writes on each of five days,
     * the 11th to the 15th, and no day is closed. The bond, and her entry ids
     * oldest first.
     */
    private fun fiveDays(): Pair<String, List<String>> {
        clock.set(at(10))
        val bond = rig.pair(ada, bea)
        return bond to
            (11..15).map { day ->
                clock.set(at(day))
                rig.submit(ada, bond, "${ADAS}$day")
            }
    }

    /** A trigger that refuses any update of the row of [table] whose [column] is [value], with a message that says nothing. */
    private fun failingOnUpdateOf(
        table: String,
        column: String,
        value: String,
    ) {
        jdbc.execute(
            "CREATE TRIGGER $TRIGGER BEFORE UPDATE ON $table FOR EACH ROW WHEN (OLD.$column = '$value') EXECUTE FUNCTION $FUNCTION()",
        )
    }

    /** Drops what a test made, and (re)makes the function the refusing triggers call. */
    private fun resetTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS $TRIGGER ON entries")
        jdbc.execute("DROP TRIGGER IF EXISTS $TRIGGER ON outbox_deliveries")
        jdbc.execute("DROP FUNCTION IF EXISTS $QUOTING_FUNCTION()")
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION $FUNCTION() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'refused by a test'; END ${'$'}${'$'}
            """.trimIndent(),
        )
    }

    /**
     * Everything this JVM logs while [block] runs, with every logger that has
     * no level of its own at DEBUG: each line as its logger, its message and
     * the class and message of every exception in its chain. A logger this
     * module's configuration turns off (`org.hibernate.orm.jdbc.error`, as in
     * production) stays off; that setting is part of what keeps a row out of
     * the log, and is not what is on trial here.
     */
    private fun everythingLoggedAtDebug(block: () -> Unit): List<String> {
        val everything = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val level = root.level
        root.addAppender(everything)
        root.level = Level.DEBUG
        try {
            block()
        } finally {
            root.level = level
            root.detachAppender(everything)
        }
        return everything.list.toList().map { event ->
            "${event.loggerName} ${event.formattedMessage}" +
                generateSequence(event.throwableProxy) { it.cause }.joinToString("") { " ${it.className}: ${it.message}" }
        }
    }

    private fun events(): Int = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Int::class.java)!!

    private fun at(day: Int): Instant = Instant.parse("2026-09-${day}T10:00:00Z")

    private companion object {
        /**
         * What every entry of Ada's, and of Bea's, begins with, and what the
         * logs, the payloads and `last_error` are searched for.
         *
         * **Not spellable in hexadecimal, on purpose.** These were `ada-` and
         * `bea-`: three hex digits and a hyphen, which is how a group of a
         * UUID ends about once in five hundred ids. A DEBUG line that printed
         * such an id was then "entry text in a log", and two tests here
         * failed once for it. A capital, letters past `f` and a tilde occur
         * in no id, class name or statement.
         */
        const val ADAS = "Ada~wrote~"
        const val BEAS = "Bea~wrote~"
        const val MANY = 2_000
        const val TRIGGER = "withdraw_entries_test_refusal"
        const val FUNCTION = "withdraw_entries_test_refuse"
        const val QUOTING_FUNCTION = "withdraw_entries_test_quote"
        const val UUID_PATTERN = "[0-9a-f-]{36}"

        /** A class name and nothing after it: no colon, no space, no message. */
        val CLASS_NAME = Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+""")
    }
}
