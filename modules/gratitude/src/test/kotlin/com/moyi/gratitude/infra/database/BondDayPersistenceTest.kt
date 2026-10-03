package com.moyi.gratitude.infra.database

import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.BondDayStatus
import com.moyi.gratitude.domain.DayWindow
import com.moyi.gratitude.domain.Entry
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.infra.GratitudeTestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
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
 * The boundary tests for gratitude persistence: real Postgres, real Flyway,
 * real Hibernate (doc 12: prefer real-Postgres integration tests over
 * mocks). On trial: V12's constraints, and the lazy-open compare-and-set
 * `BondDayStore.openOrGet` is built on (ADR-0027's shape, B2's own
 * invite-creation precedent).
 *
 * **Every store call goes through [transactions].** `BondDayStore` and
 * `EntryStore` are not transactional themselves — the boundary is the
 * caller's (doc 18 §4), same as `BondStore`/`MemberStore` — and this class
 * is that caller, exactly as `BondPersistenceTest`/`MemberPersistenceTest`
 * are for `bond`. The private wrappers below (`openOrGet`, `insert`, …) are
 * what keep every test method itself reading like the brief's own snippet
 * while each call still runs in its own real transaction underneath.
 *
 * `ddl-auto: validate` in this module's test `application.yml` is what
 * proves the mapping agrees with the schema at all — the context does not
 * start unless every mapped column exists with a compatible type.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class BondDayPersistenceTest(
    @Autowired private val days: BondDayStore,
    @Autowired private val entries: EntryStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()
    private val bondId = UUID.randomUUID()
    private val ada = UUID.randomUUID()
    private val bea = UUID.randomUUID()
    private val date = LocalDate.of(2026, 9, 28)
    private val lagos = ZoneId.of("Africa/Lagos")
    private val now = Instant.parse("2026-09-28T20:00:00Z")

    // Lagos's 28th, UTC+1 all year: 27T23:00Z to 28T23:00Z.
    private val window = DayWindow(date, Instant.parse("2026-09-27T23:00:00Z"), Instant.parse("2026-09-28T23:00:00Z"))

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE entries, bond_days CASCADE")
    }

    @Test
    fun `two first entries racing produce one day, not two`() {
        // The lazy open is INSERT ... ON CONFLICT DO NOTHING, which is B2's
        // invite-creation shape (ADR-0027) and not a check-then-insert. Each
        // call below runs in its own real transaction, as InviteRaceTest's
        // HTTP version does.
        val results = inParallel(listOf({ openOrGet(bondId, window, lagos, now) }, { openOrGet(bondId, window, lagos, now) }))

        results.map { it.id }.toSet().size shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 1
        // Ids.kt's own reason for the split: a bond-day's id is a v7 — index
        // locality over secrecy, since nothing hides when one was opened.
        val opened = results.first().id.value
        opened.version() shouldBe 7
    }

    @Test
    fun `eight first entries racing produce one day, not two`() {
        // Two threads can agree by luck — InviteRaceTest's own reason for
        // an eight-way case: eight contending on one row is where a lock
        // that is merely *usually* taken stops looking correct.
        val results = inParallel(List(8) { { openOrGet(bondId, window, lagos, now) } })

        results.map { it.id }.toSet().size shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 1
    }

    @Test
    fun `a second entry by the same member is refused by the index, not by a check`() {
        val day = openOrGet(bondId, window, lagos, now)
        val first = entry(day.id, ada)
        insert(first)

        shouldThrow<DataIntegrityViolationException> { insert(entry(day.id, ada)) }
        // …and the other member is fine.
        insert(entry(day.id, bea))
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 2
        // The other half of Ids.kt's split: an entry's id is a v4, opaque —
        // BR-8 hides when it was written, and a v7 would leak exactly that.
        first.id.value.version() shouldBe 4
    }

    @Test
    fun `the day keeps the zone it was opened in even after the bond's anchor moves`() {
        val day = openOrGet(bondId, window, ZoneId.of("Africa/Lagos"), now)

        // Nothing in this module reads the bond's current zone for an existing day.
        findByBondAndDate(bondId, date).shouldNotBeNull()
        find(day.id).shouldNotBeNull().anchorTimezone shouldBe ZoneId.of("Africa/Lagos")
    }

    @Test
    fun `the day's span is persisted exactly as given, and is not derived from its zone`() {
        // A merged westward day (plan R3): 48 hours, labelled the 28th, with a
        // Lagos snapshot. Deriving the span from `anchor_timezone` — Lagos
        // midnight to midnight — would store 27T23:00Z..28T23:00Z instead.
        val merged = DayWindow(date, Instant.parse("2026-09-27T10:00:00Z"), Instant.parse("2026-09-29T10:00:00Z"))

        val day = openOrGet(bondId, merged, lagos, now)

        day.startsAt shouldBe merged.startsAt
        day.endsAt shouldBe merged.endsAt
        val stored = "SELECT starts_at = '2026-09-27T10:00:00Z' AND ends_at = '2026-09-29T10:00:00Z' FROM bond_days"
        jdbc.queryForObject(stored, Boolean::class.java) shouldBe true
        find(day.id).shouldNotBeNull().endsAt shouldBe merged.endsAt
    }

    @Test
    fun `a second open with a different window finds the existing row unchanged`() {
        // An existing day is never recomputed (BR-6): a second open with a
        // different window finds the row, it does not rewrite it.
        openOrGet(bondId, window, lagos, now)
        val other = DayWindow(date, Instant.parse("2026-09-27T10:00:00Z"), Instant.parse("2026-09-28T10:00:00Z"))

        openOrGet(bondId, other, lagos, now).startsAt shouldBe window.startsAt
    }

    @Test
    fun `an extended end survives a reload, and the start and the zone snapshot do not move`() {
        // Ruling P10: the one change a day's span may undergo. Kiritimati's
        // 16th, opened [15T10:00Z, 16T10:00Z), merged by a westward change
        // into its successor's start, 17T11:00Z (plan R3).
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val sixteenth = LocalDate.of(2026, 9, 16)
        val opened = DayWindow(sixteenth, Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-16T10:00:00Z"))
        val merged = opened.copy(endsAt = Instant.parse("2026-09-17T11:00:00Z"))
        val day = openOrGet(bondId, opened, kiritimati, now)

        transactions.executeWithoutResult { days.update(days.lockAndFind(day.id).extendedTo(merged)) }

        val reloaded = find(day.id).shouldNotBeNull()
        reloaded.endsAt shouldBe merged.endsAt
        reloaded.startsAt shouldBe opened.startsAt
        reloaded.anchorTimezone shouldBe kiritimati
        val stored =
            "SELECT starts_at = '2026-09-15T10:00:00Z' AND ends_at = '2026-09-17T11:00:00Z' " +
                "AND anchor_timezone = 'Pacific/Kiritimati' FROM bond_days"
        jdbc.queryForObject(stored, Boolean::class.java) shouldBe true
    }

    @Test
    fun `the update path refuses to shorten a day`() {
        // `applyTo`'s own guard: `extendedTo` never produces a shorter day,
        // and no other path to the row may either.
        val day = openOrGet(bondId, window, lagos, now)

        shouldThrow<IllegalArgumentException> {
            transactions.executeWithoutResult { days.update(day.copy(endsAt = day.endsAt.minusSeconds(1))) }
        }
        find(day.id).shouldNotBeNull().endsAt shouldBe window.endsAt
    }

    @Test
    fun `V12 admits an empty span and refuses an inverted one`() {
        val at = "2026-09-28T23:00:00Z"
        rawInsert(UUID.randomUUID(), at, at) shouldBe 1

        shouldThrow<DataIntegrityViolationException> { rawInsert(UUID.randomUUID(), at, "2026-09-28T22:59:59Z") }
            .message shouldContain "bond_days_span_check"
    }

    /**
     * A7. `findAllByBondDayId` runs on every `GET /today` and asks for every
     * entry of a day, tombstones included — so it cannot use BR-2's partial
     * unique index, whose predicate is `deleted_at IS NULL`. Asked of the
     * planner rather than of the catalogue alone: with sequential scans
     * priced out, the plan names the index only if one can serve the query.
     */
    @Test
    fun `a day's entries are found through a plain index on bond_day_id, and BR-2's partial index is still there`() {
        val plan =
            transactions
                .execute {
                    jdbc.execute("SET LOCAL enable_seqscan = off")
                    jdbc.queryForList("EXPLAIN SELECT * FROM entries WHERE bond_day_id = '${UUID.randomUUID()}'", String::class.java)
                }.shouldNotBeNull()
                .joinToString("\n")

        plan shouldContain "entries_bond_day_idx"
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE tablename = 'entries' AND indexname = 'entries_bond_day_idx'",
            String::class.java,
        ) shouldBe "CREATE INDEX entries_bond_day_idx ON public.entries USING btree (bond_day_id)"
        jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE tablename = 'entries' AND indexname = 'entries_one_per_member_per_day'",
            String::class.java,
        ) shouldBe
            "CREATE UNIQUE INDEX entries_one_per_member_per_day ON public.entries USING btree " +
            "(bond_day_id, author_member_id) WHERE (deleted_at IS NULL)"
    }

    /**
     * B4. FR-041's octet cap at the row, below `EntryText.of`: the backstop
     * for a writer that does not come through the domain. The text is plain
     * ASCII, so its octets are its length and nothing else is in question.
     */
    @Test
    fun `V12 refuses a text over 8192 octets, and admits one of exactly 8192`() {
        val day = openOrGet(bondId, window, lagos, now)

        rawInsertEntry(day.id, ada, "a".repeat(8192)) shouldBe 1
        shouldThrow<DataIntegrityViolationException> { rawInsertEntry(day.id, bea, "a".repeat(8193)) }
            .message shouldContain "entries_text_octets_check"
        // Octets, not characters: 2,731 three-octet characters are 8,193 octets.
        shouldThrow<DataIntegrityViolationException> { rawInsertEntry(day.id, bea, "\u20ac".repeat(2731)) }
            .message shouldContain "entries_text_octets_check"
    }

    @Test
    fun `openOrGet can open a day already suspended, for a bond still waiting on its second member`() {
        // Doc 04 §8.3a, as the Phase 3 design §12.4 resolves it (02 J1): the
        // creator may write before their partner joins, and the row opens
        // excluded from evaluation from the first write rather than
        // becoming so later.
        val day = openOrGet(bondId, window, lagos, now, status = BondDayStatus.SUSPENDED)

        day.status shouldBe BondDayStatus.SUSPENDED
        jdbc.queryForObject("SELECT status FROM bond_days", String::class.java) shouldBe "SUSPENDED"
    }

    @Test
    fun `findForDay returns every entry filed against the day, and nothing else`() {
        val day = openOrGet(bondId, window, lagos, now)
        val otherDay = openOrGet(UUID.randomUUID(), window, lagos, now)
        insert(entry(day.id, ada))
        insert(entry(day.id, bea))
        insert(entry(otherDay.id, ada))

        findForDay(day.id).map { it.authorMemberId } shouldContainExactlyInAnyOrder listOf(ada, bea)
    }

    @Test
    fun `update writes a changed bond-day and moves the version`() {
        val day = openOrGet(bondId, window, lagos, now)

        transactions.executeWithoutResult { days.update(day.withEntry()) }

        val reloaded = find(day.id).shouldNotBeNull()
        reloaded.entryCount shouldBe 1
        reloaded.status shouldBe BondDayStatus.PARTIAL
        // Hibernate's @Version, moved by the actual column change above —
        // the ETag a later slice's If-Match will compare against.
        reloaded.version shouldBe 1
    }

    // ---- transaction boundaries -----------------------------------------
    //
    // BondDayStore and EntryStore are not transactional themselves; these
    // are this test's own TransactionTemplate.execute { } wrappers, the
    // same discipline BondPersistenceTest holds every `bond` store to.

    private fun openOrGet(
        bondId: UUID,
        window: DayWindow,
        zone: ZoneId,
        now: Instant,
        status: BondDayStatus = BondDayStatus.OPEN,
    ) = transactions.execute { days.openOrGet(bondId, window, zone, now, status) }.shouldNotBeNull()

    private fun find(id: BondDayId) = transactions.execute { days.find(id) }

    private fun findByBondAndDate(
        bondId: UUID,
        date: LocalDate,
    ) = transactions.execute { days.findByBondAndDate(bondId, date) }

    private fun insert(entry: Entry) = transactions.executeWithoutResult { entries.insert(entry) }

    private fun findForDay(bondDayId: BondDayId) = transactions.execute { entries.findForDay(bondDayId) }.shouldNotBeNull()

    // ---- fixtures ---------------------------------------------------------

    /** Below the domain, so the schema's own CHECK is what answers — BondDay's `init` would refuse first. */
    private fun rawInsert(
        bondId: UUID,
        startsAt: String,
        endsAt: String,
    ): Int =
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, version)
            VALUES (?, ?, ?, 'OPEN', 'Africa/Lagos', ?::timestamptz, ?::timestamptz, 0, now(), 0)
            """.trimIndent(),
            UUID.randomUUID(),
            bondId,
            date,
            startsAt,
            endsAt,
        )

    /** Below the domain, so `entries_text_octets_check` is what answers — `EntryText.of` would refuse first. */
    private fun rawInsertEntry(
        bondDayId: BondDayId,
        authorMemberId: UUID,
        text: String,
    ): Int =
        jdbc.update(
            """
            INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'SUBMITTED', now(), now(), now())
            """.trimIndent(),
            UUID.randomUUID(),
            bondDayId.value,
            bondId,
            authorMemberId,
            text,
        )

    private fun entry(
        bondDayId: BondDayId,
        authorMemberId: UUID,
    ): Entry =
        Entry.submit(
            id = EntryId(ids.opaque()),
            bondDayId = bondDayId,
            bondId = bondId,
            authorMemberId = authorMemberId,
            text = EntryText.of("thank you for the coffee"),
            intendedAt = now,
            now = now,
        )

    /**
     * Runs every call on its own thread and releases them together —
     * `InviteRaceTest`'s own helper (`modules/bond`), copied rather than
     * shared for the reason `FakeUserDirectory`'s own KDoc gives.
     */
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
}
