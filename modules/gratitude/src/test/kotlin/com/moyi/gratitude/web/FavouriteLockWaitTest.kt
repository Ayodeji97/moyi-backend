package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.Favourites
import com.moyi.gratitude.service.EntryImmutableException
import com.moyi.gratitude.service.FavouriteEntry
import com.moyi.gratitude.service.ReconcileJoiningDay
import com.moyi.gratitude.service.WithdrawalRig
import com.moyi.identity.api.UserDirectory
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **Neither favourite route waits out an erasure** (ADR-0036 decision 12).
 *
 * The withdrawal consumer erases a member's entries one after another in one
 * transaction, and holds every row it has touched until the whole delivery
 * commits: seconds for a long history, up to its sixty-second limit. As first
 * built, an unmark of a bookmark the consumer had already removed waited on
 * that row for the rest of the delivery, holding a pooled connection while it
 * did. A handful of them took the pool, and then every request in the
 * application waited, a member of another bond's included (found by review:
 * six unmarks held 3.6 s each, and a stranger's `GET /today` 3.3 s).
 *
 * Here an erasing transaction is opened on the test's own connection and
 * left open: the entry locked `FOR UPDATE`, erased, its bookmarks deleted,
 * nothing committed. It is this thread that holds it, so "the eraser is
 * still open" is a fact of where the test is and not of timing, and it is
 * checked: another connection is refused the bookmark's row at once.
 *
 * - **The unmark answers `409 FAVOURITE_BUSY` while that transaction is open.**
 *   A retry after either commit or rollback succeeds; an uncommitted deletion
 *   is never treated as a successful removal.
 * - **The mark is refused as `ENTRY_IMMUTABLE` while it is open**, once its
 *   lock timeout has passed. The real timeout is two seconds, so the mark
 *   here is made through the application's own `FavouriteEntry` built over
 *   a [Favourites] that passes a shorter wait ([ImpatientFavourites]): the
 *   one thing that differs, so the test does not take two seconds to say
 *   the same. It is built by hand and not as a bean, because a bean of its
 *   own is a Spring context of its own, and one more context's connections
 *   are more than this module's test database has left. So what is seen is
 *   the service's refusal, `EntryImmutableException`; that the exception is
 *   the `409` with that code is `FavouritesTest`'s, over HTTP.
 *
 * Each request is given [BOUND] to answer. It is a bound and not a wait: a
 * request that answers does so in milliseconds, and one that is stuck behind
 * the eraser fails the test there.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class FavouriteLockWaitTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired access: BondAccess,
    @Autowired entries: EntryStore,
    @Autowired joining: ReconcileJoiningDay,
    @Autowired transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val pool = Executors.newCachedThreadPool()

    /** The application's service for the two routes, over a store that waits [SHORT] for a lock. */
    private val impatient = FavouriteEntry(access, entries, ImpatientFavourites(JdbcTemplate(dataSource), transactions), joining, clock)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String
    private lateinit var adas: String

    @BeforeEach
    fun setUp() {
        rig.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(BOND_CREATED)
        bond = rig.pair(ada, bea)
        clock.set(NOW)
        adas = rig.submit(ada, bond, ADAS_WORDS)
        rig.submit(bea, bond, BEAS_WORDS)
    }

    @AfterEach
    fun clear() {
        pool.shutdownNow()
        rig.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `an unmark conflicts while an erasure holds the bookmark and succeeds after commit`() {
        favourite(bea, adas).status shouldBe 204
        marksOn(adas) shouldBe 1

        whileErasing(adas) { eraser ->
            val unmarking = pool.submit(Callable { unfavourite(bea, adas) })
            try {
                val response = unmarking.get(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                response.status shouldBe 409
                response.contentAsString shouldContain "FAVOURITE_BUSY"
            } finally {
                eraser.rollbackIfStill(unmarking)
            }
            // Answered with the eraser still open: nothing of its work is committed, and it still holds the row.
            wordsOf(adas) shouldBe ADAS_WORDS
            marksOn(adas) shouldBe 1
            refusedAtOnce("SELECT 1 FROM entry_favourites WHERE entry_id = '$adas' FOR UPDATE NOWAIT")
            eraser.commit()
        }

        marksOn(adas) shouldBe 0
        wordsOf(adas) shouldBe null
        unfavourite(bea, adas).status shouldBe 204
    }

    @Test
    fun `a mark is refused as ENTRY_IMMUTABLE once its lock timeout passes, while the erasure is still open, and leaves no row`(
        output: CapturedOutput,
    ) {
        whileErasing(adas) { eraser ->
            val marking = pool.submit(Callable { runCatching { impatient.mark(bea, EntryId(UUID.fromString(adas))) } })
            val refused =
                try {
                    marking.get(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                } finally {
                    eraser.rollbackIfStill(marking)
                }
            // The service's own refusal, which the advice answers as 409 ENTRY_IMMUTABLE; not a failure of the database's.
            refused.exceptionOrNull().shouldBeInstanceOf<EntryImmutableException>()
            // Answered with the eraser still open: the entry is whole to everybody else, and its row is still held.
            wordsOf(adas) shouldBe ADAS_WORDS
            refusedAtOnce("SELECT 1 FROM entries WHERE id = '$adas' FOR SHARE NOWAIT")
            eraser.commit()
        }

        marksOn(adas) shouldBe 0
        wordsOf(adas) shouldBe null
        // The refusal is an answer, not a failure: nothing of Postgres's message, or its code, is in the log.
        output.all shouldNotContain "lock timeout"
        output.all shouldNotContain LOCK_NOT_AVAILABLE
        output.all shouldNotContain "CannotAcquireLock"
    }

    @Test
    fun `the lock timeout is the transaction's own, and no pooled connection keeps it`() {
        whileErasing(adas) { eraser ->
            // Off this thread, which holds the eraser: a mark made on it with no timeout would wait for itself.
            val marking = pool.submit(Callable { runCatching { impatient.mark(bea, EntryId(UUID.fromString(adas))) } })
            try {
                marking.get(BOUND.toMillis(), TimeUnit.MILLISECONDS).exceptionOrNull().shouldBeInstanceOf<EntryImmutableException>()
            } finally {
                eraser.rollbackIfStill(marking)
            }
            eraser.commit()
        }
        // A mark that was not refused sets it too: this one through the route, with the application's two seconds.
        val beas =
            jdbc.queryForObject(
                "SELECT id::text FROM entries WHERE bond_id = ?::uuid AND id <> ?::uuid",
                String::class.java,
                bond,
                adas,
            )!!
        favourite(bea, beas).status shouldBe 204

        // Every connection the pool has, held at once so that each is a different one.
        val size = dataSource.unwrap(HikariDataSource::class.java).maximumPoolSize
        val held = mutableListOf<Connection>()
        try {
            repeat(size) { held += dataSource.connection }
            held.map { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_setting('lock_timeout')").use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                }
            } shouldBe List(size) { "0" }
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test
    fun `an unmark refused during erasure can be retried after rollback`() {
        favourite(bea, adas).status shouldBe 204
        whileErasing(adas) { eraser ->
            val unmarking = pool.submit(Callable { unfavourite(bea, adas) })
            try {
                val response = unmarking.get(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                response.status shouldBe 409
                response.contentAsString.shouldContain("FAVOURITE_BUSY")
            } finally {
                eraser.rollbackIfStill(unmarking)
            }
            eraser.rollback()
        }
        wordsOf(adas) shouldBe ADAS_WORDS
        marksOn(adas) shouldBe 1
        unfavourite(bea, adas).status shouldBe 204
        marksOn(adas) shouldBe 0
    }

    // ---- the eraser ----

    /**
     * Runs [block] with an erasure of [entryId] done and not committed, on a
     * connection of this thread's own: the entry locked as `EraseEntry` locks
     * it, its words gone, its bookmarks deleted. Whatever [block] leaves
     * uncommitted is rolled back.
     */
    private fun whileErasing(
        entryId: String,
        block: (Connection) -> Unit,
    ) {
        dataSource.connection.use { eraser ->
            eraser.autoCommit = false
            try {
                eraser.createStatement().use {
                    it.execute("SELECT 1 FROM entries WHERE id = '$entryId' FOR UPDATE")
                    it.executeUpdate(
                        "UPDATE entries SET text = NULL, status = 'DELETED', deleted_at = now() WHERE id = '$entryId'",
                    ) shouldBe
                        1
                    it.executeUpdate("DELETE FROM entry_favourites WHERE entry_id = '$entryId'")
                }
                block(eraser)
            } finally {
                eraser.rollback()
                eraser.autoCommit = true
            }
        }
    }

    /**
     * For a request that did not answer in time: lets it go, so that it does
     * not sit on a pooled connection behind this test's own lock while the
     * next test starts. Does nothing when [request] has answered.
     */
    private fun Connection.rollbackIfStill(request: Future<*>) {
        if (request.isDone) return
        rollback()
        runCatching { request.get(BOUND.toMillis(), TimeUnit.MILLISECONDS) }
    }

    /** [sql] asks for a lock without waiting, on another connection, and must be refused: somebody holds it. */
    private fun refusedAtOnce(sql: String) {
        dataSource.connection.use { other ->
            other.autoCommit = false
            try {
                shouldThrow<SQLException> { other.createStatement().use { it.execute(sql) } }.sqlState shouldBe LOCK_NOT_AVAILABLE
            } finally {
                other.rollback()
                other.autoCommit = true
            }
        }
    }

    private fun marksOn(entryId: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM entry_favourites WHERE entry_id = ?::uuid", Int::class.java, entryId)!!

    private fun wordsOf(entryId: String): String? =
        jdbc.queryForList("SELECT text FROM entries WHERE id = ?::uuid", String::class.java, entryId).single()

    private fun favourite(
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/entries/$entryId/favourite") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response

    private fun unfavourite(
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$entryId/favourite") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response

    /** The application's own [Favourites], asked to wait [SHORT] for a lock where it would wait two seconds. Nothing else differs. */
    private class ImpatientFavourites(
        jdbc: JdbcTemplate,
        transactions: TransactionTemplate,
    ) : Favourites(jdbc, transactions) {
        override fun mark(
            entryId: EntryId,
            memberId: UUID,
            now: Instant,
            lockTimeout: Duration,
        ): Boolean = super.mark(entryId, memberId, now, SHORT)
    }

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        // Markers no UUID, date or hexadecimal digest can contain.
        const val ADAS_WORDS = "zq-ada-words"
        const val BEAS_WORDS = "zq-bea-words"

        /** How long a request may take to answer before the test calls it stuck. */
        val BOUND: Duration = Duration.ofSeconds(10)

        /** The wait [ImpatientFavourites] gives a mark, where the application gives two seconds. */
        val SHORT: Duration = Duration.ofMillis(200)

        /** Postgres's `lock_not_available`: a lock asked for with `NOWAIT`, or one that outlasted `lock_timeout`. */
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
