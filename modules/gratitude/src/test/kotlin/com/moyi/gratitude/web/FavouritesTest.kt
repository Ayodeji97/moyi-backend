package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.events.DispatchResult
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.Favourites
import com.moyi.gratitude.service.EraseEntry
import com.moyi.gratitude.service.WithdrawalRig
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * `PUT` and `DELETE /entries/{entryId}/favourite` (FR-093, spec §6.6), and
 * the `favourited` every full entry now carries, through the real chain
 * against a real Postgres.
 *
 * What is held here, in the order the tests come:
 *
 * - **Who may mark what** is the read gate's answer for the caller and
 *   nothing else: a revealed entry either member can read, yes; the caller's
 *   own entry still waiting, `409 ENTRY_NOT_REVEALED`; a tombstone, `409
 *   ENTRY_IMMUTABLE`; anything the caller was never shown, the one `404`.
 * - **The partner's marks are nowhere**: not a byte of anything the other
 *   member can fetch moves when one is made or removed.
 * - **An erased entry keeps no mark**, whichever of the three erasures
 *   reached it (the author's delete, the withdrawal's consumer, the close
 *   job's pre-step), and whichever of the mark and the erasure came first.
 *
 * These routes carry no `{bondId}`, so the route-driven cross-tenant suite
 * cannot see them (as it cannot see `PATCH` and `DELETE /entries/{entryId}`).
 * The test that compares the `404`s is their cross-tenant test, and
 * `EntryChangesTest` holds the exact set of `{entryId}` routes.
 *
 * The scheduler does not run in this module's tests: nothing is erased or
 * closed unless a test says so. Entry words here are spelled with letters a
 * UUID cannot hold, so finding or not finding them in a body means what it
 * seems to.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // What Spring hands the test; and one feature's rules, kept where they can be read together.
internal class FavouritesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val closer: DayCloser,
    @Autowired private val favourites: Favourites,
    @Autowired private val eraser: EraseEntry,
    @Autowired private val access: BondAccess,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = WithdrawalRig(mockMvc, tokens, jdbc)
    private val interruptible = access as MarkerReadLastTest.InterruptibleBondAccess
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var eve: UUID

    /** Before as well as after: the close job and the dispatcher work on every bond another test class left behind. */
    @BeforeEach
    fun setUp() {
        rig.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        eve = users.verified("Eve")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        interruptible.disarm()
        pool.shutdownNow()
        rig.clear()
        users.clear()
        clock.set(NOW)
    }

    // ---- marking and unmarking ----

    @Test
    fun `a mark and its removal are each 204 with no body, twice over, and show to the member who made them and not to the partner`() {
        val day = revealedDay()

        repeat(2) {
            val marked = favourite(bea, day.adas)
            marked.status shouldBe 204
            marked.contentAsString shouldBe ""
            marks() shouldBe listOf(day.adas to day.beaMember)
        }
        // Hers, on the entry she marked and not on her own.
        today(bea, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe true
        today(bea, day.bond)["myEntry"]["favourited"].asBoolean() shouldBe false
        // Not his: the entry is his own, the mark is hers, and he is told nothing of it.
        today(ada, day.bond)["myEntry"]["favourited"].asBoolean() shouldBe false
        today(ada, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe false

        repeat(2) {
            val unmarked = unfavourite(bea, day.adas)
            unmarked.status shouldBe 204
            unmarked.contentAsString shouldBe ""
            marks().shouldBeEmpty()
        }
        today(bea, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe false
    }

    @Test
    fun `either member may mark either revealed entry, their own included, and each mark is the marker's own`() {
        val day = revealedDay()

        for (user in listOf(ada, bea)) for (entry in listOf(day.adas, day.beas)) favourite(user, entry).status shouldBe 204

        // Keyed by the member in this bond, never by the user.
        marks() shouldContainExactlyInAnyOrder
            listOf(day.adas to day.adaMember, day.adas to day.beaMember, day.beas to day.adaMember, day.beas to day.beaMember)
        marks().map { it.second }.intersect(setOf(ada, bea)).shouldBeEmpty()
        for (user in listOf(ada, bea)) {
            today(user, day.bond)["myEntry"]["favourited"].asBoolean() shouldBe true
            today(user, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe true
        }

        // Taking one's own mark off leaves the partner's on the same entry where it was.
        unfavourite(ada, day.adas).status shouldBe 204
        marks() shouldContainExactlyInAnyOrder
            listOf(day.adas to day.beaMember, day.beas to day.adaMember, day.beas to day.beaMember)
        today(ada, day.bond)["myEntry"]["favourited"].asBoolean() shouldBe false
        today(bea, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe true
    }

    @Test
    fun `the caller's own entry, still waiting for the partner, cannot be marked yet, and unmarking it is success`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, ADAS_WORDS)

        val refused = favourite(ada, adas)

        refused.status shouldBe 409
        json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_NOT_REVEALED"
        // True of every way an entry can be unrevealed: waiting, a pending bond, a solo day that closed after the bond ended.
        json.readTree(refused.contentAsString)["detail"].asString() shouldBe "This entry has not been revealed."
        refused.contentAsString shouldNotContain ADAS_WORDS
        marks().shouldBeEmpty()
        today(ada, bond)["myEntry"]["favourited"].asBoolean() shouldBe false

        // Absent is what was asked for.
        val absent = unfavourite(ada, adas)
        absent.status shouldBe 204
        absent.contentAsString shouldBe ""

        // And once the partner has written, the same request is granted.
        rig.submit(bea, bond, BEAS_WORDS)
        favourite(ada, adas).status shouldBe 204
        marks() shouldBe listOf(adas to rig.memberId(bond, ada))
    }

    // ---- the one 404 ----

    @Test
    fun `an entry the caller was never shown is the one 404 on both verbs - a locked entry, a stranger, no such id, not an id`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, ADAS_WORDS)
        // The partner (it is locked to her), a stranger, an id nobody has, and something that is not an id.
        val targets = listOf(bea to adas, eve to adas, ada to UUID.randomUUID().toString(), ada to "not-an-entry")

        val puts = targets.map { (caller, target) -> favourite(caller, target) }
        val deletes = targets.map { (caller, target) -> unfavourite(caller, target) }
        // The route beside these two, for the same partner: one answer for an entry id, whatever was asked of it.
        val sibling =
            mockMvc
                .delete("/api/v1/entries/$adas") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
                .andReturn()
                .response

        (puts + deletes + sibling).forEach { it.status shouldBe 404 }
        (puts + deletes + sibling).map(::comparable).toSet().size shouldBe 1
        puts.first().contentAsString shouldNotContain "ENTRY_NOT_REVEALED"
        marks().shouldBeEmpty()

        // Erased before it was ever revealed: she was told an author and REMOVED, never its id. Still the same answer.
        rig.delete(ada, adas)
        today(bea, bond)["partnerEntry"]["status"].asString() shouldBe "REMOVED"
        val unseen = listOf(favourite(bea, adas), unfavourite(bea, adas))
        unseen.forEach { it.status shouldBe 404 }
        (unseen + puts).map(::comparable).toSet().size shouldBe 1
        marks().shouldBeEmpty()
    }

    @Test
    fun `the shapes for an entry the caller was never shown gain nothing, and the full shape gains exactly favourited`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, ADAS_WORDS)

        today(bea, bond)["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        today(bea, bond)["partnerEntry"]["status"].asString() shouldBe "LOCKED"
        today(ada, bond)["myEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS

        rig.delete(ada, adas)

        today(bea, bond)["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        today(bea, bond)["partnerEntry"]["status"].asString() shouldBe "REMOVED"
        // Her own tombstone is the wide shape, and says false.
        val own = today(ada, bond)["myEntry"]
        own.propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
        own["status"].asString() shouldBe "DELETED"
        own["favourited"].asBoolean() shouldBe false
    }

    // ---- the partner's marks are nowhere ----

    @Test
    fun `the partner marking and unmarking changes no byte of anything the other member is answered`() {
        val bond = pair()
        val key = UUID.randomUUID().toString()
        val adas = json.readTree(submit(ada, bond, ADAS_WORDS, key).contentAsString)["id"].asString()
        val beas = rig.submit(bea, bond, BEAS_WORDS)

        fun whatAdaIsAnswered(): List<String> =
            listOf(
                comparable(get(ada, "/api/v1/bonds/$bond/today")),
                comparable(get(ada, "/api/v1/bonds/$bond/streak")),
                comparable(get(ada, "/api/v1/bonds/$bond")),
                // A replay re-reads the entry as it stands now: his own, which she is about to mark.
                comparable(submit(ada, bond, ADAS_WORDS, key).also { it.status shouldBe 201 }),
            )

        val before = whatAdaIsAnswered()
        before.first() shouldContain "\"favourited\":false"

        favourite(bea, adas).status shouldBe 204
        favourite(bea, beas).status shouldBe 204
        marks().size shouldBe 2
        val whileMarked = whatAdaIsAnswered()

        unfavourite(bea, adas).status shouldBe 204
        unfavourite(bea, beas).status shouldBe 204
        val after = whatAdaIsAnswered()

        whileMarked shouldBe before
        after shouldBe before
        whileMarked.forEach { it shouldNotContain "\"favourited\":true" }
    }

    // ---- favourited on a write and on its replay ----

    @Test
    fun `a fresh write says false, and its replay says what the caller's own mark is now - never the partner's`() {
        val bond = pair()
        val postKey = UUID.randomUUID().toString()
        val patchKey = UUID.randomUUID().toString()

        val posted = submit(ada, bond, ADAS_FIRST_WORDS, postKey)
        posted.status shouldBe 201
        json.readTree(posted.contentAsString)["favourited"].asBoolean() shouldBe false
        val adas = json.readTree(posted.contentAsString)["id"].asString()
        val patched = patchEntry(ada, adas, ADAS_WORDS, patchKey)
        patched.status shouldBe 200
        json.readTree(patched.contentAsString)["favourited"].asBoolean() shouldBe false
        rig.submit(bea, bond, BEAS_WORDS)

        // The partner's mark on it is hers: neither replay says so.
        favourite(bea, adas).status shouldBe 204
        replayOf(submit(ada, bond, ADAS_FIRST_WORDS, postKey))["favourited"].asBoolean() shouldBe false
        replayOf(patchEntry(ada, adas, ADAS_WORDS, patchKey))["favourited"].asBoolean() shouldBe false

        favourite(ada, adas).status shouldBe 204
        replayOf(submit(ada, bond, ADAS_FIRST_WORDS, postKey))["favourited"].asBoolean() shouldBe true
        replayOf(patchEntry(ada, adas, ADAS_WORDS, patchKey))["favourited"].asBoolean() shouldBe true

        // Erased, the replay is the wide tombstone, which says false whatever rows there were.
        rig.delete(ada, adas)
        val tombstone = replayOf(submit(ada, bond, ADAS_FIRST_WORDS, postKey))
        tombstone["status"].asString() shouldBe "DELETED"
        tombstone["favourited"].asBoolean() shouldBe false
        replayOf(patchEntry(ada, adas, ADAS_WORDS, patchKey))["favourited"].asBoolean() shouldBe false
    }

    // ---- a bond that has ended ----

    @Test
    fun `a bookmark is still the member's to make and remove after the bond has ended, for the one who left and the one who stayed`() {
        for (ending in listOf("leave", "block keeping entries", "deletion countdown")) {
            withClue(ending) {
                rig.clear()
                val day = revealedDay()
                when (ending) {
                    "leave" -> rig.leave(bea, day.bond)
                    "block keeping entries" -> rig.block(bea, day.bond, withdraw = false)
                    else -> listOf(ada, bea).forEach { requestDeletion(it, day.bond) }
                }
                // Not vacuous: the bond does refuse a write to what the two share.
                val edit = patchEntry(ada, day.adas, ADAS_FIRST_WORDS, null)
                edit.status shouldBe 409
                edit.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""

                for (user in listOf(ada, bea)) for (entry in listOf(day.adas, day.beas)) favourite(user, entry).status shouldBe 204
                marks().size shouldBe 4
                for (user in listOf(ada, bea)) {
                    today(user, day.bond)["myEntry"]["favourited"].asBoolean() shouldBe true
                    today(user, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe true
                }
                for (user in listOf(ada, bea)) for (entry in listOf(day.adas, day.beas)) unfavourite(user, entry).status shouldBe 204
                marks().shouldBeEmpty()
            }
        }
    }

    // ---- erasure ----

    @Test
    fun `an entry its author deletes keeps no mark, reads as an unmarked tombstone, and can no longer be marked by anybody`() {
        val day = revealedDay()
        favourite(bea, day.adas).status shouldBe 204
        favourite(ada, day.adas).status shouldBe 204
        favourite(ada, day.beas).status shouldBe 204
        marks().size shouldBe 3

        rig.delete(ada, day.adas)

        // The entry's marks went with its words; the mark on the other entry is untouched.
        marks() shouldBe listOf(day.beas to day.adaMember)
        val hers = today(bea, day.bond)["partnerEntry"]
        hers["id"].asString() shouldBe day.adas
        hers["status"].asString() shouldBe "DELETED"
        hers["favourited"].asBoolean() shouldBe false
        today(ada, day.bond)["myEntry"]["favourited"].asBoolean() shouldBe false

        for (user in listOf(ada, bea)) {
            val refused = favourite(user, day.adas)
            refused.status shouldBe 409
            json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
            // One sentence for an edit and for a bookmark: neither can be made of a tombstone.
            json.readTree(refused.contentAsString)["detail"].asString() shouldBe "This entry can no longer be changed."
            unfavourite(user, day.adas).status shouldBe 204
        }
        marks() shouldBe listOf(day.beas to day.adaMember)

        // Deleting again is a repeat of nothing: it erases nothing and writes nothing, here included. A row put
        // on the tombstone by hand, which no request can make, is how a write that should not happen would show.
        mark(day.adas, day.beaMember)
        rig.delete(ada, day.adas)
        marks() shouldContainExactlyInAnyOrder listOf(day.adas to day.beaMember, day.beas to day.adaMember)
        // And nothing shows it: the tombstone says false whatever rows there are.
        today(bea, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe false
    }

    @Test
    fun `an author's own unrevealed entry, once deleted, is a tombstone to them and not an entry still waiting`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, ADAS_WORDS)
        rig.delete(ada, adas)

        val refused = favourite(ada, adas)

        refused.status shouldBe 409
        json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
        unfavourite(ada, adas).status shouldBe 204
        marks().shouldBeEmpty()
    }

    /**
     * A withdrawal is recorded when the bond ends and the entries are erased
     * afterwards. In between, the row is whole: the statement that makes a
     * mark would take it. The gate is what refuses, and what says `false`
     * over a mark that is still in the table.
     */
    @Test
    fun `a withdrawn author's entry answers as a tombstone before anything is erased, and its marks go when the consumer runs`() {
        val day = revealedDay()
        favourite(bea, day.adas).status shouldBe 204
        favourite(ada, day.adas).status shouldBe 204
        favourite(ada, day.beas).status shouldBe 204

        rig.block(ada, day.bond)

        // Nothing has been erased: the rows are whole and the marks are still there.
        entry(day.adas)["text"] shouldBe ADAS_WORDS
        marks().size shouldBe 3
        // The gate hides the words and says false, for the reader who marked it and for its author.
        val hers = today(bea, day.bond)["partnerEntry"]
        hers["status"].asString() shouldBe "DELETED"
        hers["text"].isNull shouldBe true
        hers["favourited"].asBoolean() shouldBe false
        today(ada, day.bond)["myEntry"]["favourited"].asBoolean() shouldBe false
        // The entry of the member who did not withdraw is as it was, mark and all.
        today(ada, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe true

        // A tombstone to both: the mark comes off, and cannot be put back although the row is live.
        for (user in listOf(ada, bea)) unfavourite(user, day.adas).status shouldBe 204
        marks() shouldBe listOf(day.beas to day.adaMember)
        for (user in listOf(ada, bea)) {
            val refused = favourite(user, day.adas)
            refused.status shouldBe 409
            json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
        }
        marks() shouldBe listOf(day.beas to day.adaMember)
        entry(day.adas)["text"] shouldBe ADAS_WORDS

        // A mark that was there when the consumer arrives goes with the entry.
        mark(day.adas, day.beaMember)
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        entry(day.adas)["text"].shouldBeNull()
        marks() shouldBe listOf(day.beas to day.adaMember)
        today(bea, day.bond)["partnerEntry"]["favourited"].asBoolean() shouldBe false
    }

    @Test
    fun `the close job reaching a withdrawn author's day before the consumer leaves no mark on what it erases`() {
        clock.set(NOW.minusSeconds(DAY))
        val bond = rig.pair(ada, bea)
        clock.set(NOW)
        val adas = rig.submit(ada, bond, ADAS_WORDS)
        val beas = rig.submit(bea, bond, BEAS_WORDS)
        favourite(bea, adas).status shouldBe 204
        favourite(ada, adas).status shouldBe 204
        favourite(ada, beas).status shouldBe 204
        rig.block(ada, bond)
        rig.postpone(bond, MIDNIGHT.plusSeconds(3_600))
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 0, failed = 0, more = false)
        marks().size shouldBe 3

        clock.set(MIDNIGHT)
        closer.closeElapsedDays(clock.instant(), 1_000).failed shouldBe 0

        // The close job did the consumer's work on that day, and the consumer has still not run.
        rig.delivery(bond)["processed_at"].shouldBeNull()
        entry(adas)["status"] shouldBe "DELETED"
        entry(adas)["text"].shouldBeNull()
        marks() shouldBe listOf(beas to rig.memberId(bond, ada))

        // The consumer, arriving second, finds nothing to do.
        clock.set(MIDNIGHT.plusSeconds(3_601))
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        marks() shouldBe listOf(beas to rig.memberId(bond, ada))
    }

    // ---- the statement, by itself ----

    @Test
    fun `the statement that makes a mark takes only an entry that is revealed and not erased, whoever asks it`() {
        val bond = pair()
        val adaMember = rig.memberId(bond, ada)
        val adas = rig.submit(ada, bond, ADAS_WORDS)
        val id = EntryId(UUID.fromString(adas))

        // Not revealed: nothing, even for its author, whom the service would have stopped first.
        favourites.mark(id, adaMember, clock.instant()) shouldBe false
        marks().shouldBeEmpty()

        rig.submit(bea, bond, BEAS_WORDS)
        favourites.mark(id, adaMember, clock.instant()) shouldBe true
        // A second time: the row is there, and that is what was asked.
        favourites.mark(id, adaMember, clock.instant().plusSeconds(5)) shouldBe true
        marks() shouldBe listOf(adas to adaMember)
        jdbc.queryForObject("SELECT created_at FROM entry_favourites", Timestamp::class.java)!!.toInstant() shouldBe clock.instant()
        favourites.markedBy(adaMember, listOf(id, EntryId(UUID.randomUUID()))) shouldBe setOf(id)
        favourites.markedBy(rig.memberId(bond, bea), listOf(id)) shouldBe emptySet()
        favourites.markedBy(adaMember, emptyList()) shouldBe emptySet()

        // Erased, by either mark of an erasure: nothing.
        favourites.unmark(id, adaMember)
        for (erasure in listOf("deleted_at = now()", "status = 'DELETED'")) {
            withClue(erasure) {
                jdbc.update("UPDATE entries SET deleted_at = NULL, status = 'REVEALED' WHERE id = ?::uuid", adas) shouldBe 1
                jdbc.update("UPDATE entries SET $erasure WHERE id = ?::uuid", adas) shouldBe 1
                favourites.mark(id, adaMember, clock.instant()) shouldBe false
                marks().shouldBeEmpty()
            }
        }
        // And an id nobody has.
        favourites.mark(EntryId(UUID.randomUUID()), adaMember, clock.instant()) shouldBe false
        marks().shouldBeEmpty()
    }

    // ---- the mark and the erasure, at the same time ----

    /**
     * The erasure commits **after the gate has answered and before the
     * statement runs**: the request has read the entry whole and been told it
     * may mark it. The statement is the second line, and finds nothing to
     * insert.
     *
     * The seam is `MarkerReadLastTest`'s: something happens as the request's
     * last membership resolution returns, which is the one its reader is made
     * from. The count is checked, so a path that gains or loses a resolution
     * fails here and is looked at again.
     */
    @Test
    fun `an erasure that commits between the gate and the statement leaves no mark, and the request is told the entry is gone`() {
        val day = revealedDay()
        // Two resolutions: the route's, then the reader's. The gate is asked of what the second returns.
        interruptible.afterResolution(2) {
            // Disarmed first, so that the delete's own resolutions are not counted as this request's.
            interruptible.disarm()
            pool.submit { rig.delete(ada, day.adas) }.get(10, TimeUnit.SECONDS)
        }

        val refused = favourite(bea, day.adas)

        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 2
        refused.status shouldBe 409
        json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
        entry(day.adas)["status"] shouldBe "DELETED"
        marks().shouldBeEmpty()
    }

    /**
     * The same seam, with a withdrawal instead: the gate was asked before it
     * committed, the row is still whole, and so the statement takes the mark.
     * That is a mark on an entry about to be erased, made by a request that
     * could have been answered entirely before the ending. Nothing shows it
     * (the gate says tombstone from here on), and the erasure removes it.
     */
    @Test
    fun `a withdrawal that commits between the gate and the statement can leave a mark, which nothing shows and the erasure removes`() {
        val day = revealedDay()
        interruptible.afterResolution(2) { pool.submit { rig.block(ada, day.bond) }.get(10, TimeUnit.SECONDS) }

        favourite(bea, day.adas).status shouldBe 204

        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 2
        interruptible.disarm()
        marks() shouldBe listOf(day.adas to day.beaMember)
        val hers = today(bea, day.bond)["partnerEntry"]
        hers["status"].asString() shouldBe "DELETED"
        hers["favourited"].asBoolean() shouldBe false

        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        entry(day.adas)["text"].shouldBeNull()
        marks().shouldBeEmpty()
    }

    /**
     * **The marker is read last** (ADR-0035 decision 12), for this route: the
     * request reads the entry, and only then asks who has withdrawn. Here an
     * ending commits while the request is stopped at its read of the entry,
     * which is after it resolved the caller's membership for the route. A
     * reader made from that first membership, or one asked for before the
     * entry was read, says nobody has withdrawn; the row is whole; and the
     * mark would be made on an entry both people are already being shown as
     * a tombstone.
     *
     * The request is stopped by a lock on the table, taken as its first
     * resolution returns, and the test waits until Postgres reports its read
     * of `entries` waiting behind that lock before the ending is made.
     *
     * A lock on the whole table stalls every reader of `entries` while it is
     * held. That is safe because this module's test classes run one after
     * another in one JVM (the build configures no parallel execution), and
     * the lock is held only while this test's own request waits on it. If
     * classes are ever run in parallel, this is the test to change first.
     */
    @Test
    fun `who has withdrawn is asked after the entry is read, so an ending that commits during that read refuses the mark`() {
        val day = revealedDay()
        withAnotherTransaction { holder ->
            val holderPid = backendPidOf(holder)
            interruptible.afterResolution(1) {
                interruptible.disarm()
                holder.createStatement().use { it.execute("LOCK TABLE entries IN ACCESS EXCLUSIVE MODE") }
            }

            val marking = pool.submit(Callable { favourite(bea, day.adas) })
            await().atMost(Duration.ofSeconds(10)).until { marking.isDone || waitingBehind(holderPid).isNotEmpty() }
            marking.isDone shouldBe false
            interruptible.fired shouldBe true
            // Whatever else may be waiting on that lock, this request is.
            waitingBehind(holderPid).any { "entries" in it } shouldBe true

            rig.block(ada, day.bond)
            holder.commit()
            val refused = marking.get(10, TimeUnit.SECONDS)

            refused.status shouldBe 409
            json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
            // Nothing was erased: the row is whole, and it is the gate that refused.
            entry(day.adas)["text"] shouldBe ADAS_WORDS
            marks().shouldBeEmpty()
        }
    }

    /**
     * The two really at once: an erasure that has done everything but
     * commit, and a mark that arrives while it is open. The request reads the
     * entry as it was committed, whole, and its gate says yes; what is left
     * to stop it is the statement.
     *
     * Synchronised on what happened, not on time: the erasing transaction
     * says when it has erased, and the test then waits until Postgres itself
     * reports the request's statement waiting behind that transaction. Only
     * then is the erasure allowed to commit.
     */
    @Test
    fun `a mark that arrives while an erasure is uncommitted waits for it, and then leaves no mark on the erased entry`() {
        val day = revealedDay()
        val dayId = BondDayId(jdbc.queryForObject("SELECT bond_day_id FROM entries WHERE id = ?::uuid", UUID::class.java, day.adas)!!)
        val erased = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val eraserPid = AtomicInteger()

        val erasing =
            pool.submit {
                transactions.execute {
                    // As every caller of the erasure does: the bond's lock first, then the day and the entry inside.
                    access.lockMembershipOf(ada, UUID.fromString(day.bond))
                    eraser.erase(EntryId(UUID.fromString(day.adas)), dayId, clock.instant())
                    eraserPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                    erased.countDown()
                    check(commit.await(30, TimeUnit.SECONDS)) { "the test never let the erasure commit" }
                }
            }
        try {
            erased.await(10, TimeUnit.SECONDS) shouldBe true
            // Uncommitted: everybody else still reads the entry whole.
            entry(day.adas)["text"] shouldBe ADAS_WORDS

            val marking = pool.submit(Callable { favourite(bea, day.adas) })
            await().atMost(Duration.ofSeconds(10)).until { marking.isDone || waitingBehind(eraserPid.get()).isNotEmpty() }
            marking.isDone shouldBe false
            waitingBehind(eraserPid.get()).any { "entry_favourites" in it } shouldBe true

            commit.countDown()
            erasing.get(10, TimeUnit.SECONDS)
            val refused = marking.get(10, TimeUnit.SECONDS)

            refused.status shouldBe 409
            json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
            entry(day.adas)["status"] shouldBe "DELETED"
            marks().shouldBeEmpty()
        } finally {
            commit.countDown()
        }
    }

    /**
     * A second mark inserts nothing, and that must not be mistaken for an
     * entry that cannot be marked. Whether the entry qualified is the
     * statement's own answer; it is not read back afterwards from a row the
     * member's own unmark, a moment later, can have taken away.
     *
     * The store here is the real one over a `JdbcTemplate` that runs the
     * member's unmark, to its commit, as soon as the store's statement on
     * `entry_favourites` returns: the double tap on a toggle, in the order
     * that used to answer `409` on a whole entry.
     */
    @Test
    fun `a repeat mark is a success whatever the member's own unmark does a moment later`() {
        val day = revealedDay()
        val entry = EntryId(UUID.fromString(day.adas))
        favourites.mark(entry, day.beaMember, clock.instant()) shouldBe true
        var unmarked = false
        val racing =
            Favourites(
                AfterTheMark(dataSource) {
                    unmarked = true
                    // On another thread, so that it commits by itself and not with the mark's transaction.
                    pool.submit { favourites.unmark(entry, day.beaMember) }.get(10, TimeUnit.SECONDS)
                },
                transactions,
            )

        racing.mark(entry, day.beaMember, clock.instant()) shouldBe true

        unmarked shouldBe true
        // The unmark came last, so it is what stands.
        marks().shouldBeEmpty()
    }

    /**
     * The statement's lock must wait for **any** change to the entry's row,
     * not only for an eraser that takes `FOR UPDATE` first. This eraser is a
     * plain `UPDATE`, which Postgres locks `FOR NO KEY UPDATE`: `FOR KEY
     * SHARE` does not conflict with that, read the row whole, and left a
     * mark on an entry erased under it. `FOR SHARE` does conflict.
     */
    @Test
    fun `a mark waits for an erasure that took no row lock first, and then leaves nothing on the erased entry`() {
        val day = revealedDay()
        withAnotherTransaction { eraser ->
            val eraserPid = backendPidOf(eraser)
            eraser.createStatement().use {
                it.executeUpdate("UPDATE entries SET text = NULL, status = 'DELETED', deleted_at = now() WHERE id = '${day.adas}'")
                it.executeUpdate("DELETE FROM entry_favourites WHERE entry_id = '${day.adas}'")
            }

            val marking = pool.submit(Callable { favourites.mark(EntryId(UUID.fromString(day.adas)), day.beaMember, clock.instant()) })
            await().atMost(Duration.ofSeconds(10)).until { marking.isDone || waitingBehind(eraserPid).any { "entry_favourites" in it } }
            marking.isDone shouldBe false

            eraser.commit()
            marking.get(10, TimeUnit.SECONDS) shouldBe false
        }
        entry(day.adas)["status"] shouldBe "DELETED"
        marks().shouldBeEmpty()
    }

    /**
     * A `JdbcTemplate` that runs [then] once, right after the first statement
     * on `entry_favourites` made through it returns. Not the first statement
     * of all: the mark sets its lock timeout before it.
     */
    private class AfterTheMark(
        dataSource: DataSource,
        private val then: () -> Unit,
    ) : JdbcTemplate(dataSource) {
        private var fired = false

        private fun <T> T.andThen(sql: String): T {
            if (!fired && "entry_favourites" in sql) {
                fired = true
                then()
            }
            return this
        }

        override fun update(
            sql: String,
            vararg args: Any?,
        ): Int = super.update(sql, *args).andThen(sql)

        override fun <T : Any> queryForObject(
            sql: String,
            requiredType: Class<T>,
            vararg args: Any?,
        ): T? = super.queryForObject(sql, requiredType, *args).andThen(sql)
    }

    // ---- fixtures ----

    private data class Day(
        val bond: String,
        val adas: String,
        val beas: String,
        val adaMember: UUID,
        val beaMember: UUID,
    )

    /** Made two days before [NOW], so that the day written on is an ordinary one and not the day the two paired. */
    private fun pair(): String {
        clock.set(BOND_CREATED)
        return rig.pair(ada, bea).also { clock.set(NOW) }
    }

    /** Both have written today, so both entries are revealed. */
    private fun revealedDay(): Day {
        val bond = pair()
        val adas = rig.submit(ada, bond, ADAS_WORDS)
        val beas = rig.submit(bea, bond, BEAS_WORDS)
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND revealed_at IS NOT NULL",
            Int::class.java,
            bond,
        ) shouldBe 2
        return Day(bond, adas, beas, rig.memberId(bond, ada), rig.memberId(bond, bea))
    }

    /** Every mark there is, as entry id to member id. */
    private fun marks(): List<Pair<String, UUID>> =
        jdbc.query("SELECT entry_id::text AS entry, member_id FROM entry_favourites ORDER BY entry_id, member_id") { row, _ ->
            row.getString("entry") to row.getObject("member_id", UUID::class.java)
        }

    /** A mark put there by hand: the state a request leaves when it was answered just before a withdrawal committed. */
    private fun mark(
        entryId: String,
        memberId: UUID,
    ) {
        jdbc.update(
            "INSERT INTO entry_favourites (entry_id, member_id, created_at) VALUES (?::uuid, ?, now())",
            entryId,
            memberId,
        ) shouldBe 1
    }

    private fun entry(id: String): Map<String, Any?> =
        jdbc.queryForMap("SELECT text, status, deleted_at, revealed_at FROM entries WHERE id = ?::uuid", id)

    /** A connection of the test's own with a transaction open on it, rolled back afterwards whatever happened. */
    private fun withAnotherTransaction(block: (Connection) -> Unit) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection)
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    private fun backendPidOf(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /** The statements Postgres reports as waiting on a lock [holderPid] holds. */
    private fun waitingBehind(holderPid: Int): List<String> =
        jdbc
            .queryForList("SELECT query FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", String::class.java, holderPid)
            .filterNotNull()

    // ---- requests ----

    private fun favourite(
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/entries/$entryId/favourite") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
            .andReturn()
            .response

    private fun unfavourite(
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$entryId/favourite") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
            .andReturn()
            .response

    private fun get(
        user: UUID,
        path: String,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
            .andReturn()
            .response
            .also { it.status shouldBe 200 }

    private fun today(
        user: UUID,
        bond: String,
    ): JsonNode = json.readTree(get(user, "/api/v1/bonds/$bond/today").contentAsString)

    private fun submit(
        user: UUID,
        bond: String,
        words: String,
        key: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response

    private fun patchEntry(
        user: UUID,
        entryId: String,
        words: String,
        key: String?,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$entryId") {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                if (key != null) header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response

    /** The body of a response that must be a replay. */
    private fun replayOf(response: MockHttpServletResponse): JsonNode {
        response.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        return json.readTree(response.contentAsString)
    }

    private fun requestDeletion(
        user: UUID,
        bond: String,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bond/deletion-request") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
            .andReturn()
            .response.status shouldBe 202
    }

    private fun bearer(user: UUID): String = "Bearer ${tokens.issue(user).token}"

    /**
     * Everything of a response a caller could tell two answers apart by: the
     * status, every header and the body. Taken out are the two things that
     * are the request's own: `instance`, the path the caller typed, and the
     * id of this one request (`EntryChangesTest.comparable`'s own rule).
     */
    private fun comparable(response: MockHttpServletResponse): String {
        val body = response.contentAsString.replace(Regex("\"instance\":\"[^\"]*\""), "\"instance\":\"-\"")
        val headers =
            response.headerNames
                .filterNot { it.equals("X-Request-Id", ignoreCase = true) || it.startsWith("X-RateLimit", ignoreCase = true) }
                .sorted()
                .joinToString { "$it=${response.getHeaders(it)}" }
        return "${response.status}|$headers|$body"
    }

    private companion object {
        /** Eleven in the morning in Lagos on the 15th: nowhere near a midnight on either side. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")

        /** Two minutes after the 15th ended in Lagos: past the close job's margin. */
        val MIDNIGHT: Instant = Instant.parse("2026-09-15T23:02:00Z")
        const val DAY = 86_400L

        val FULL_KEYS = listOf("id", "bondId", "date", "authorMemberId", "text", "status", "createdAt", "intendedAt", "favourited")

        const val ADAS_FIRST_WORDS = "ada-first-thoughts-on-the-rain"
        const val ADAS_WORDS = "ada-thanks-for-the-walk-home"
        const val BEAS_WORDS = "bea-thanks-for-the-tea"
    }
}
