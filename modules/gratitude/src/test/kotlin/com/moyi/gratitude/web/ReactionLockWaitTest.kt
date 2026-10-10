package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.ReactionType
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.EntryStore
import com.moyi.gratitude.infra.database.Reactions
import com.moyi.gratitude.service.EntryImmutableException
import com.moyi.gratitude.service.ReactToEntry
import com.moyi.gratitude.service.ReconcileJoiningDay
import com.moyi.gratitude.service.WithdrawalRig
import com.moyi.identity.api.UserDirectory
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
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
 * **Neither reaction route waits out an erasure** (ADR-0036 decision 12,
 * which found the stall on the favourite routes; the plan's decision 6).
 *
 * The withdrawal consumer erases a member's entries one after another in one
 * transaction, and holds every row it has touched until the whole delivery
 * commits. A request that waited on one of those rows held a pooled
 * connection while it did, and a handful of them stalled every request in
 * the application. That a statement decides atomically says nothing about
 * how long it waits first, so this is run, with the lock held.
 *
 * Here an erasing transaction is opened on the test's own connection and
 * left open: the entry locked `FOR UPDATE`, erased, its reactions deleted,
 * nothing committed. It is this thread that holds it, so "the eraser is
 * still open" is a fact of where the test is and not of timing, and it is
 * checked: another connection is refused the row at once.
 *
 * - **Taking a reaction back from an entry the caller reads in full answers
 *   `409 REACTION_BUSY` at once while that transaction is open.** Here the
 *   eraser is the author's own delete in flight: nothing of it has
 *   committed, the entry is whole to everybody, and if it rolls back the
 *   reaction is still there and shown. So a deletion that has not committed
 *   is not reported as a removal. Asked again after either a commit or a
 *   rollback, it succeeds.
 * - **From an entry that is already a tombstone to the caller it is `204`
 *   whoever holds the row**, so that a withdrawal whose delivery is open
 *   answers as its twin deleted by hand does.
 * - **Setting one is refused as `ENTRY_IMMUTABLE` while it is open**, once
 *   its lock timeout has passed. The real timeout is two seconds, so the
 *   request here is made through the application's own `ReactToEntry` built
 *   over a [Reactions] that passes a shorter wait ([ImpatientReactions]):
 *   the one thing that differs. Built by hand and not as a bean, for the
 *   reason `FavouriteLockWaitTest` gives. So what is seen is the service's
 *   refusal, `EntryImmutableException`; that the exception is the `409` with
 *   that code is `ReactionsTest`'s, over HTTP, where the erasure commits.
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
internal class ReactionLockWaitTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired access: BondAccess,
    @Autowired entries: EntryStore,
    @Autowired joining: ReconcileJoiningDay,
    @Autowired transactions: TransactionTemplate,
    @Autowired private val json: ObjectMapper,
    @Autowired private val dispatcher: OutboxDispatcher,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val pool = Executors.newCachedThreadPool()

    /** The application's service for the two routes, over a store that waits [SHORT] for a lock. */
    private val impatient = ReactToEntry(access, entries, ImpatientReactions(JdbcTemplate(dataSource), transactions), joining)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: String
    private lateinit var adas: String
    private lateinit var beas: String

    @BeforeEach
    fun setUp() {
        rig.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(BOND_CREATED)
        bond = rig.pair(ada, bea)
        clock.set(NOW)
        adas = rig.submit(ada, bond, ADAS_WORDS)
        beas = rig.submit(bea, bond, BEAS_WORDS)
    }

    @AfterEach
    fun clear() {
        pool.shutdownNow()
        rig.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `taking a reaction back returns at once with 409 REACTION_BUSY while an erasure holds its row, which is as the eraser leaves it`() {
        react(bea, adas).status shouldBe 204
        reactionsOn(adas) shouldBe 1

        whileErasing(adas) { eraser ->
            val removing = pool.submit(Callable { unreact(bea, adas) })
            val refused =
                try {
                    removing.get(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                } finally {
                    eraser.rollbackIfStill(removing)
                }
            // Not a 204: the row's deletion is somebody else's and has not committed, so no removal is promised.
            refused.status shouldBe 409
            json.readTree(refused.contentAsString)["code"].asString() shouldBe "REACTION_BUSY"
            json.readTree(refused.contentAsString)["detail"].asString() shouldBe "This reaction is being changed. Please try again."
            // Answered with the eraser still open: nothing of its work is committed, and it still holds the row.
            wordsOf(adas) shouldBe ADAS_WORDS
            reactionsOn(adas) shouldBe 1
            refusedAtOnce("SELECT 1 FROM reactions WHERE entry_id = '$adas' FOR UPDATE NOWAIT")
            eraser.commit()
        }

        // The eraser committed: the row is gone with the words, and the same request is now a success.
        reactionsOn(adas) shouldBe 0
        wordsOf(adas) shouldBe null
        unreact(bea, adas).status shouldBe 204
    }

    /**
     * Why the held row is not answered `204`: the eraser may not commit.
     * Then the reaction is still there, shown to both, after a removal that
     * would have been reported successful.
     */
    @Test
    fun `a removal refused during an erasure that rolls back leaves the reaction there, and a second request removes it`() {
        react(bea, adas).status shouldBe 204

        whileErasing(adas) { eraser ->
            val removing = pool.submit(Callable { unreact(bea, adas) })
            try {
                val refused = removing.get(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                refused.status shouldBe 409
                json.readTree(refused.contentAsString)["code"].asString() shouldBe "REACTION_BUSY"
            } finally {
                eraser.rollbackIfStill(removing)
            }
            eraser.rollback()
        }

        wordsOf(adas) shouldBe ADAS_WORDS
        reactionsOn(adas) shouldBe 1
        unreact(bea, adas).status shouldBe 204
        reactionsOn(adas) shouldBe 0
    }

    /**
     * **The busy refusal must not tell a withdrawal from a deletion by hand**
     * (found by review). Two bonds with one history. In the first, Ada has
     * blocked and withdrawn, and an eraser holds the row of Bea's reaction on
     * Ada's entry: what the consumer holds, on every row it has touched,
     * until its whole delivery commits. In the second, its twin, Cy deleted
     * the entry by hand and left. To Bea and to Dee the entry is the same
     * tombstone in every view, so taking the reaction back must be the same
     * whole response; a `409` in the first alone would say that a delivery is
     * in flight, which a deletion by hand has no counterpart to.
     *
     * The answer is safe where the entry is already a tombstone to the
     * caller by a **committed** withdrawal or erasure: the rollback the
     * refusal exists for cannot make it false there, since a tombstone shows
     * no reaction whatever rows there are.
     */
    @Test
    fun `taking a reaction back from a withdrawn entry whose row an eraser holds is the same 204 as from its twin deleted by hand`() {
        react(bea, adas).status shouldBe 204
        rig.block(ada, bond)
        // Withdrawn and committed; nothing erased. The entry is a tombstone to her by the gate alone.
        wordsOf(adas) shouldBe ADAS_WORDS
        partnerEntryOf(bea, bond)["status"].asString() shouldBe "DELETED"

        val cy = users.verified("Cy")
        val dee = users.verified("Dee")
        clock.set(BOND_CREATED)
        val twin = rig.pair(cy, dee)
        clock.set(NOW)
        val cys = rig.submit(cy, twin, ADAS_WORDS)
        rig.submit(dee, twin, BEAS_WORDS)
        react(dee, cys).status shouldBe 204
        rig.delete(cy, cys)
        rig.leave(cy, twin)
        val byHand = unreact(dee, cys)
        byHand.status shouldBe 204

        whileErasing(adas) { eraser ->
            val removing = pool.submit(Callable { unreact(bea, adas) })
            val held =
                try {
                    removing.get(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                } finally {
                    eraser.rollbackIfStill(removing)
                }
            comparable(held) shouldBe comparable(byHand)
            // Answered with the eraser still open, and its row still held.
            reactionsOn(adas) shouldBe 1
            refusedAtOnce("SELECT 1 FROM reactions WHERE entry_id = '$adas' FOR UPDATE NOWAIT")
            eraser.commit()
        }

        // The delivery committed: there is no row, as the answer said.
        reactionsOn(adas) shouldBe 0
    }

    /**
     * The same held row, and the eraser **rolls back**: a delivery that
     * failed. The `204` was given for a row that is then still there. What
     * the caller sees is what they saw before: the entry is a tombstone to
     * them by the withdrawal, which did commit, and a tombstone shows no
     * reaction. And the row does not outlive the consumer's retry.
     */
    @Test
    fun `when the eraser of a withdrawn entry rolls back, the reaction's row is shown to nobody and goes with the consumer's retry`() {
        react(bea, adas).status shouldBe 204
        rig.block(ada, bond)

        whileErasing(adas) { eraser ->
            val removing = pool.submit(Callable { unreact(bea, adas) })
            try {
                removing.get(BOUND.toMillis(), TimeUnit.MILLISECONDS).status shouldBe 204
            } finally {
                eraser.rollbackIfStill(removing)
            }
            eraser.rollback()
        }

        // Nothing was erased and nothing removed: the row is whole, and so is the reaction's.
        wordsOf(adas) shouldBe ADAS_WORDS
        reactionsOn(adas) shouldBe 1
        for (user in listOf(ada, bea)) {
            val entry = if (user == ada) myEntryOf(user, bond) else partnerEntryOf(user, bond)
            entry["status"].asString() shouldBe "DELETED"
            entry["reaction"].isNull shouldBe true
        }

        // The delivery is tried again, and this time commits.
        dispatcher.dispatchDue(clock.instant(), 10).delivered shouldBe 1
        wordsOf(adas) shouldBe null
        reactionsOn(adas) shouldBe 0
    }

    /**
     * The refusal is about the caller's own row and comes after every `404`
     * decision: with the row held, somebody the entry was never shown to is
     * told exactly what they are told of an id nobody has, and the entry's
     * author, who has no row on it, is not told that anything is busy.
     */
    @Test
    fun `while the row is held a stranger still gets the one 404, and the author, who has no row there, a 204`() {
        react(bea, adas).status shouldBe 204
        val eve = users.verified("Eve")

        whileErasing(adas) { eraser ->
            val asked =
                pool.submit(
                    Callable { Triple(unreact(eve, adas), unreact(eve, UUID.randomUUID().toString()), unreact(ada, adas)) },
                )
            val (strangers, nobodys, authors) =
                try {
                    asked.get(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                } finally {
                    eraser.rollbackIfStill(asked)
                }
            strangers.status shouldBe 404
            comparable(strangers) shouldBe comparable(nobodys)
            strangers.contentAsString shouldNotContain "BUSY"
            authors.status shouldBe 204
            refusedAtOnce("SELECT 1 FROM reactions WHERE entry_id = '$adas' FOR UPDATE NOWAIT")
        }
    }

    @Test
    fun `a reaction is refused as ENTRY_IMMUTABLE once its lock timeout passes, while the erasure is still open, and leaves no row`(
        output: CapturedOutput,
    ) {
        whileErasing(adas) { eraser ->
            val reacting =
                pool.submit(
                    Callable { runCatching { impatient.react(bea, EntryId(UUID.fromString(adas)), ReactionType.HEART) } },
                )
            val refused =
                try {
                    reacting.get(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                } finally {
                    eraser.rollbackIfStill(reacting)
                }
            // The service's own refusal, which the advice answers as 409 ENTRY_IMMUTABLE; not a failure of the database's.
            refused.exceptionOrNull().shouldBeInstanceOf<EntryImmutableException>()
            // Answered with the eraser still open: the entry is whole to everybody else, and its row is still held.
            wordsOf(adas) shouldBe ADAS_WORDS
            refusedAtOnce("SELECT 1 FROM entries WHERE id = '$adas' FOR SHARE NOWAIT")
            eraser.commit()
        }

        reactionsOn(adas) shouldBe 0
        wordsOf(adas) shouldBe null
        // The refusal is an answer, not a failure: nothing of Postgres's message, or its code, is in the log.
        output.all shouldNotContain "lock timeout"
        output.all shouldNotContain LOCK_NOT_AVAILABLE
        output.all shouldNotContain "CannotAcquireLock"
    }

    @Test
    fun `the lock timeout is the transaction's own, and no pooled connection keeps it`() {
        whileErasing(adas) { eraser ->
            // Off this thread, which holds the eraser: a reaction set on it with no timeout would wait for itself.
            val reacting =
                pool.submit(
                    Callable { runCatching { impatient.react(bea, EntryId(UUID.fromString(adas)), ReactionType.HEART) } },
                )
            try {
                reacting.get(BOUND.toMillis(), TimeUnit.MILLISECONDS).exceptionOrNull().shouldBeInstanceOf<EntryImmutableException>()
            } finally {
                eraser.rollbackIfStill(reacting)
            }
            eraser.commit()
        }
        // A reaction that was not refused sets it too: this one through the route, with the application's two seconds.
        react(ada, beas).status shouldBe 204

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

    // ---- the eraser ----

    /**
     * Runs [block] with an erasure of [entryId] done and not committed, on a
     * connection of this thread's own: the entry locked as `EraseEntry` locks
     * it, its words gone, its reactions deleted. Whatever [block] leaves
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
                    it.executeUpdate("DELETE FROM reactions WHERE entry_id = '$entryId'")
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

    /** Status, every header but the request's own, and the body with the path the caller typed taken out. */
    private fun comparable(response: MockHttpServletResponse): String {
        val body = response.contentAsString.replace(Regex("\"instance\":\"[^\"]*\""), "\"instance\":\"-\"")
        val headers =
            response.headerNames
                .filterNot { it.equals("X-Request-Id", ignoreCase = true) || it.startsWith("X-RateLimit", ignoreCase = true) }
                .sorted()
                .joinToString { "$it=${response.getHeaders(it)}" }
        return "${response.status}|$headers|$body"
    }

    private fun partnerEntryOf(
        user: UUID,
        bond: String,
    ): JsonNode = json.readTree(rig.today(user, bond))["partnerEntry"]

    private fun myEntryOf(
        user: UUID,
        bond: String,
    ): JsonNode = json.readTree(rig.today(user, bond))["myEntry"]

    private fun reactionsOn(entryId: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM reactions WHERE entry_id = ?::uuid", Int::class.java, entryId)!!

    private fun wordsOf(entryId: String): String? =
        jdbc.queryForList("SELECT text FROM entries WHERE id = ?::uuid", String::class.java, entryId).single()

    private fun react(
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/entries/$entryId/reaction") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"type":"HEART"}"""
            }.andReturn()
            .response

    private fun unreact(
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$entryId/reaction") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response

    /** The application's own [Reactions], asked to wait [SHORT] for a lock where it would wait two seconds. Nothing else differs. */
    private class ImpatientReactions(
        jdbc: JdbcTemplate,
        transactions: TransactionTemplate,
    ) : Reactions(jdbc, transactions) {
        override fun set(
            entryId: EntryId,
            memberId: UUID,
            type: ReactionType,
            lockTimeout: Duration,
        ): Boolean = super.set(entryId, memberId, type, SHORT)
    }

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        // Markers no UUID, date or hexadecimal digest can contain.
        const val ADAS_WORDS = "zq-ada-words"
        const val BEAS_WORDS = "zq-bea-words"

        /** How long a request may take to answer before the test calls it stuck. */
        val BOUND: Duration = Duration.ofSeconds(10)

        /** The wait [ImpatientReactions] gives a reaction, where the application gives two seconds. */
        val SHORT: Duration = Duration.ofMillis(200)

        /** Postgres's `lock_not_available`: a lock asked for with `NOWAIT`, or one that outlasted `lock_timeout`. */
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
