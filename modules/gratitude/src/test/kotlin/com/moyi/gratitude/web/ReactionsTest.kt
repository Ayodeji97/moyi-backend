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
import com.moyi.gratitude.domain.ReactionType
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.database.Reactions
import com.moyi.gratitude.service.EraseEntry
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
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
 * `PUT` and `DELETE /entries/{entryId}/reaction` (FR-048), and the `reaction`
 * every full entry now carries, through the real chain against a real
 * Postgres.
 *
 * What is held here, in the order the tests come:
 *
 * - **Who may react to what** is the read gate's answer for the caller: the
 *   partner's entry read in full, yes; the caller's own, `409
 *   REACTION_ON_OWN_ENTRY`; the partner's tombstone, `409 ENTRY_IMMUTABLE`;
 *   anything the caller was never shown, the one `404`; and on a bond that
 *   has ended, `409 BOND_ARCHIVED`, though taking one back is always allowed.
 * - **A reaction is the one thing the other person sees.** Both members are
 *   shown the same value, and nothing else the reactor does moves a byte of
 *   what the author is answered.
 * - **An erased entry keeps no reaction**, whichever erasure reached it and
 *   in whichever order; what the withdrawing member left on the partner's
 *   entries stays.
 * - **The statement**, by itself and against an erasure in flight.
 *
 * `ReactionLockWaitTest` has what the two routes do while an erasure holds
 * the row. These routes carry no `{bondId}`, so the route-driven
 * cross-tenant suite cannot see them: the test that compares the `404`s is
 * their cross-tenant test, and `EntryChangesTest` holds the exact set of
 * `{entryId}` routes.
 *
 * The scheduler does not run in this module's tests: nothing is erased or
 * closed unless a test says so. Entry words and every other marker here are
 * spelled `zq…`, which no UUID, date or hexadecimal digest can contain.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // What Spring hands the test; and one feature's rules, kept where they can be read together.
internal class ReactionsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val closer: DayCloser,
    @Autowired private val reactions: Reactions,
    @Autowired private val eraser: EraseEntry,
    @Autowired private val access: BondAccess,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val archive = ArchiveRig(mockMvc, tokens, jdbc, json)
    private val rig = archive.bonds
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

    // ---- setting, replacing and removing ----

    @Test
    fun `a reaction is set, replaced and removed, each twice and each 204 with no body, and both members are shown it everywhere`() {
        val day = revealedDay()
        reactionShown(ada, day.bond, day.adas) shouldBe listOf(null, null, null)

        for (type in listOf("HEART", "THANK_YOU")) {
            repeat(2) {
                val set = react(bea, day.adas, type)
                set.status shouldBe 204
                set.contentAsString shouldBe ""
                // One row, whatever was there: a second type replaces the first.
                rows() shouldBe listOf(Row(day.adas, day.beaMember, type))
            }
            // The same value to the author and to the member who left it, on `/today`, the feed and the day.
            reactionShown(ada, day.bond, day.adas) shouldBe listOf(type, type, type)
            reactionShown(bea, day.bond, day.adas) shouldBe listOf(type, type, type)
            // It is on the entry reacted to, and not on the reactor's own.
            reactionShown(ada, day.bond, day.beas) shouldBe listOf(null, null, null)
            reactionShown(bea, day.bond, day.beas) shouldBe listOf(null, null, null)
        }

        repeat(2) {
            val removed = unreact(bea, day.adas)
            removed.status shouldBe 204
            removed.contentAsString shouldBe ""
            rows().shouldBeEmpty()
        }
        reactionShown(ada, day.bond, day.adas) shouldBe listOf(null, null, null)
        reactionShown(bea, day.bond, day.adas) shouldBe listOf(null, null, null)
    }

    @Test
    fun `each of the four types is taken, by its exact name`() {
        val day = revealedDay()
        // The wire names, written out: a rename in the enum is a breaking change and fails here.
        ReactionType.entries.map { it.name } shouldBe TYPES

        for (type in TYPES) {
            react(bea, day.adas, type).status shouldBe 204
            rows() shouldBe listOf(Row(day.adas, day.beaMember, type))
            reactionShown(ada, day.bond, day.adas) shouldBe listOf(type, type, type)
        }
    }

    @Test
    fun `the two members each react to the other's entry, and neither changes the other's reaction`() {
        val day = revealedDay()

        react(bea, day.adas, "HEART").status shouldBe 204
        react(ada, day.beas, "SMILE").status shouldBe 204

        // Keyed by the member in this bond, never by the user.
        rows() shouldContainExactlyInAnyOrder listOf(Row(day.adas, day.beaMember, "HEART"), Row(day.beas, day.adaMember, "SMILE"))
        rows().map { it.member }.intersect(setOf(ada, bea)).shouldBeEmpty()
        for (user in listOf(ada, bea)) {
            reactionShown(user, day.bond, day.adas) shouldBe listOf("HEART", "HEART", "HEART")
            reactionShown(user, day.bond, day.beas) shouldBe listOf("SMILE", "SMILE", "SMILE")
        }

        // He changes his, then takes it back: hers is where it was throughout.
        react(ada, day.beas, "MOVED").status shouldBe 204
        rows() shouldContainExactlyInAnyOrder listOf(Row(day.adas, day.beaMember, "HEART"), Row(day.beas, day.adaMember, "MOVED"))
        unreact(ada, day.beas).status shouldBe 204
        rows() shouldBe listOf(Row(day.adas, day.beaMember, "HEART"))
        for (user in listOf(ada, bea)) {
            reactionShown(user, day.bond, day.adas) shouldBe listOf("HEART", "HEART", "HEART")
            reactionShown(user, day.bond, day.beas) shouldBe listOf(null, null, null)
        }
        // Taking back a reaction one never left removes nobody else's.
        unreact(ada, day.adas).status shouldBe 204
        rows() shouldBe listOf(Row(day.adas, day.beaMember, "HEART"))
    }

    // ---- the caller's own entry ----

    @Test
    fun `the caller's own entry cannot be reacted to, revealed or not or erased, and taking nothing back from it is success`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, ADAS_WORDS)

        fun refusedAsOwn(state: String) {
            withClue(state) {
                val refused = react(ada, adas)
                refused.status shouldBe 409
                json.readTree(refused.contentAsString)["code"].asString() shouldBe "REACTION_ON_OWN_ENTRY"
                json.readTree(refused.contentAsString)["detail"].asString() shouldBe "You cannot react to your own entry."
                refused.contentAsString shouldNotContain ADAS_WORDS
                rows().shouldBeEmpty()
                // Absent is what was asked for.
                val absent = unreact(ada, adas)
                absent.status shouldBe 204
                absent.contentAsString shouldBe ""
            }
        }

        refusedAsOwn("waiting for the partner")
        rig.submit(bea, bond, BEAS_WORDS)
        refusedAsOwn("revealed")
        rig.delete(ada, adas)
        refusedAsOwn("erased")
    }

    // ---- the one 404 ----

    @Test
    fun `an entry the caller was never shown is the one 404 on both verbs - a locked entry, a stranger, no such id, not an id`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, ADAS_WORDS)
        // The partner (it is locked to her), a stranger, an id nobody has, and something that is not an id.
        val targets = listOf(bea to adas, eve to adas, ada to UUID.randomUUID().toString(), ada to "not-an-entry")

        val puts = targets.map { (caller, target) -> react(caller, target) }
        val deletes = targets.map { (caller, target) -> unreact(caller, target) }
        // The route beside these two, for the same partner: one answer for an entry id, whatever was asked of it.
        val sibling =
            mockMvc
                .delete("/api/v1/entries/$adas") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
                .andReturn()
                .response

        (puts + deletes + sibling).forEach { it.status shouldBe 404 }
        (puts + deletes + sibling).map(::comparable).toSet().size shouldBe 1
        rows().shouldBeEmpty()

        // Erased before it was ever revealed: she was told an author and REMOVED, never its id. Still the same answer.
        rig.delete(ada, adas)
        json.readTree(rig.today(bea, bond))["partnerEntry"]["status"].asString() shouldBe "REMOVED"
        val unseen = listOf(react(bea, adas), unreact(bea, adas))
        unseen.forEach { it.status shouldBe 404 }
        (unseen + puts).map(::comparable).toSet().size shouldBe 1
        rows().shouldBeEmpty()
    }

    @Test
    fun `the shapes for an entry the caller was never shown gain nothing, and the full shape gains exactly reaction`() {
        val bond = pair()
        val adas = rig.submit(ada, bond, ADAS_WORDS)

        // The day is not in her archive at all yet, so `/today` is the one place she is shown anything of it.
        val locked = json.readTree(rig.today(bea, bond))["partnerEntry"]
        locked.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        locked["status"].asString() shouldBe "LOCKED"
        for (view in views(ada, bond)) view["myEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS

        rig.delete(ada, adas)

        val removed = json.readTree(rig.today(bea, bond))["partnerEntry"]
        removed.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        removed["status"].asString() shouldBe "REMOVED"
        // His own tombstone is the wide shape, and says null.
        for (view in views(ada, bond)) {
            view["myEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
            view["myEntry"]["status"].asString() shouldBe "DELETED"
            view["myEntry"]["reaction"].isNull shouldBe true
        }
    }

    // ---- a tombstone ----

    @Test
    fun `a partner's erased entry takes no reaction, shows none whatever rows there are, and a reaction can still be taken back from it`() {
        val day = revealedDay()
        rig.delete(ada, day.adas)

        val refused = react(bea, day.adas)

        refused.status shouldBe 409
        json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
        json.readTree(refused.contentAsString)["detail"].asString() shouldBe "This entry can no longer be changed."
        rows().shouldBeEmpty()

        // A row put on the tombstone by hand, which no request can make: nothing shows it, to either of them.
        row(day.adas, day.beaMember, "HEART")
        for (user in listOf(ada, bea)) {
            shown(user, day.bond, day.adas).forEach { it["status"].asString() shouldBe "DELETED" }
            reactionShown(user, day.bond, day.adas) shouldBe listOf(null, null, null)
        }
        // And the member it belongs to can take it away.
        unreact(bea, day.adas).status shouldBe 204
        rows().shouldBeEmpty()
    }

    /**
     * A withdrawal is recorded when the bond ends and the entries are erased
     * afterwards. In between, the row is whole: the statement that sets a
     * reaction would take it. The gate is what refuses, and what says `null`
     * over a reaction that is still in the table.
     */
    @Test
    fun `a withdrawn author's entry answers as a tombstone does before anything is erased, and its reaction goes when the consumer runs`() {
        val day = revealedDay()
        react(bea, day.adas, "HEART").status shouldBe 204
        react(ada, day.beas, "SMILE").status shouldBe 204

        rig.block(ada, day.bond)

        // Nothing has been erased: the rows are whole and both reactions are still there.
        entry(day.adas)["text"] shouldBe ADAS_WORDS
        rows().size shouldBe 2
        // The gate hides the words and the reaction, for the member who left it and for the author.
        for (user in listOf(ada, bea)) {
            shown(user, day.bond, day.adas).forEach { it["status"].asString() shouldBe "DELETED" }
            reactionShown(user, day.bond, day.adas) shouldBe listOf(null, null, null)
        }
        // What the withdrawing member left on the partner's entry is not withdrawn: it is still shown to both.
        for (user in listOf(ada, bea)) reactionShown(user, day.bond, day.beas) shouldBe listOf("SMILE", "SMILE", "SMILE")

        // As a tombstone does: no reaction can be set, although the row is live; one can be taken back.
        val refused = react(bea, day.adas, "MOVED")
        refused.status shouldBe 409
        json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
        rows() shouldContainExactlyInAnyOrder listOf(Row(day.adas, day.beaMember, "HEART"), Row(day.beas, day.adaMember, "SMILE"))
        unreact(bea, day.adas).status shouldBe 204
        rows() shouldBe listOf(Row(day.beas, day.adaMember, "SMILE"))
        entry(day.adas)["text"] shouldBe ADAS_WORDS

        // A reaction that is there when the consumer arrives goes with the entry, and only that one.
        row(day.adas, day.beaMember, "HEART")
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        entry(day.adas)["text"].shouldBeNull()
        rows() shouldBe listOf(Row(day.beas, day.adaMember, "SMILE"))
        for (user in listOf(ada, bea)) reactionShown(user, day.bond, day.beas) shouldBe listOf("SMILE", "SMILE", "SMILE")
    }

    // ---- a bond that has ended ----

    @Test
    fun `no reaction can be left once the bond has ended, by the one who left or the one who stayed, and one can still be taken back`() {
        for (ending in listOf("leave", "block keeping entries", "deletion countdown")) {
            withClue(ending) {
                rig.clear()
                val day = revealedDay()
                react(bea, day.adas, "HEART").status shouldBe 204
                when (ending) {
                    "leave" -> rig.leave(bea, day.bond)
                    "block keeping entries" -> rig.block(bea, day.bond, withdraw = false)
                    else -> listOf(ada, bea).forEach { requestDeletion(it, day.bond) }
                }
                // Not vacuous: both entries are still read in full by both.
                for (user in listOf(ada, bea)) shown(user, day.bond, day.beas).forEach { it["text"].asString() shouldBe BEAS_WORDS }

                // Neither a new one nor a change to the one that is there.
                for ((user, target) in listOf(ada to day.beas, bea to day.adas)) {
                    val refused = react(user, target, "MOVED")
                    refused.status shouldBe 409
                    json.readTree(refused.contentAsString)["code"].asString() shouldBe "BOND_ARCHIVED"
                    json.readTree(refused.contentAsString)["detail"].asString() shouldBe "This bond has ended. It is read only now."
                }
                rows() shouldBe listOf(Row(day.adas, day.beaMember, "HEART"))
                // What was left before the ending is still shown, to both.
                for (user in listOf(ada, bea)) reactionShown(user, day.bond, day.adas) shouldBe listOf("HEART", "HEART", "HEART")

                // Taking one's own back is always allowed, as deleting one's own entry is.
                unreact(ada, day.beas).status shouldBe 204
                unreact(bea, day.adas).status shouldBe 204
                rows().shouldBeEmpty()
                for (user in listOf(ada, bea)) reactionShown(user, day.bond, day.adas) shouldBe listOf(null, null, null)
            }
        }
    }

    @Test
    fun `on an ended bond the refusals keep their order - never shown, own, erased, and only then the ending`() {
        val day = revealedDay()
        clock.set(NOW.plusSeconds(DAY))
        val waiting = rig.submit(ada, day.bond, ADAS_LATER_WORDS)
        rig.leave(bea, day.bond)
        rig.delete(ada, day.adas)

        // The ending is told only to a caller the entry was shown to, about an entry that could have taken a reaction.
        react(bea, waiting).status shouldBe 404
        comparable(react(bea, waiting)) shouldBe comparable(react(eve, waiting))
        json.readTree(react(ada, waiting).contentAsString)["code"].asString() shouldBe "REACTION_ON_OWN_ENTRY"
        json.readTree(react(bea, day.adas).contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
        json.readTree(react(ada, day.beas).contentAsString)["code"].asString() shouldBe "BOND_ARCHIVED"
        rows().shouldBeEmpty()
    }

    // ---- the body ----

    @Test
    fun `a body that is not exactly one known type is refused before the entry is looked at, the same for a member and a stranger`() {
        val day = revealedDay()
        val long = "zq".repeat(MEGABYTE / 2)
        val bodies =
            listOf(
                // A value that is not one of the four: unknown, the wrong case, padded, a number, a boolean, null, absent, 1 MB.
                """{"type":"LOVE"}""" to 422,
                """{"type":"heart"}""" to 422,
                """{"type":" HEART"}""" to 422,
                """{"type":""}""" to 422,
                """{"type":3}""" to 422,
                """{"type":0}""" to 422,
                """{"type":true}""" to 422,
                """{"type":null}""" to 422,
                """{}""" to 422,
                """{"type":"$long"}""" to 422,
                // A body that is not the one key, once, with a plain value.
                """{"type":"HEART","note":"zq-note"}""" to 400,
                """{"note":"zq-note","type":"HEART"}""" to 400,
                """{"type":"HEART","type":"SMILE"}""" to 400,
                """{"type":"HEART","type":"HEART"}""" to 400,
                """{"Type":"HEART"}""" to 400,
                """{"type":["HEART"]}""" to 400,
                """{"type":{"name":"HEART"}}""" to 400,
                """["HEART"]""" to 400,
                """"HEART"""" to 400,
                """{"type":"HEART","pad":"$long"}""" to 400,
                """{"type":""" to 400,
                "" to 400,
                // A whole body, and then more: a second body, or anything at all.
                """{"type":"HEART"}{"type":"SMILE"}""" to 400,
                """{"type":"HEART"} zq-trailing""" to 400,
                """{"type":"HEART"},""" to 400,
            )

        for ((body, status) in bodies) {
            withClue(body.take(60)) {
                val refused = send(bea, day.adas, body)
                refused.status shouldBe status
                val problem = json.readTree(refused.contentAsString)
                problem["code"].asString() shouldBe if (status == 422) "VALIDATION_FAILED" else "MALFORMED_REQUEST"
                if (status == 422) problem["errors"].toList().map { it["field"].asString() } shouldBe listOf("type")
                // Nothing that was sent comes back.
                refused.contentAsString shouldNotContain "zq"
                refused.contentAsString shouldNotContain "LOVE"
                // Decided from the request alone: a stranger and an id nobody has are told the same.
                comparable(send(eve, day.adas, body)) shouldBe comparable(refused)
                rows().shouldBeEmpty()
            }
        }
    }

    @Test
    fun `a body with no Content-Type, or one that is not JSON, is 415 and sets nothing`() {
        val day = revealedDay()

        for (type in listOf(null, MediaType.TEXT_PLAIN, MediaType.APPLICATION_FORM_URLENCODED)) {
            withClue(type.toString()) {
                val refused = send(bea, day.adas, """{"type":"HEART"}""", type)
                refused.status shouldBe 415
                json.readTree(refused.contentAsString)["code"].asString() shouldBe "UNSUPPORTED_MEDIA_TYPE"
                comparable(send(eve, day.adas, """{"type":"HEART"}""", type)) shouldBe comparable(refused)
                rows().shouldBeEmpty()
            }
        }
    }

    // ---- nothing else moves ----

    @Test
    fun `nothing the partner does but react changes a byte or a tag of what the author is answered`() {
        val day = revealedDay()

        /** His `/today`, feed and day view: status, every header (the tag among them) and the body. */
        fun whatAdaIsAnswered(): List<String> =
            listOf(
                get(ada, "/api/v1/bonds/${day.bond}/today"),
                archive.days(ada, day.bond),
                archive.day(ada, day.bond, DATE),
            ).map(::comparable)

        val before = whatAdaIsAnswered()
        before.forEach { it shouldContain "\"reaction\":null" }

        // She opens today, the feed and the day; keeps both entries; and is refused a reaction four ways.
        views(bea, day.bond).size shouldBe 3
        archive.favourite(bea, day.adas)
        archive.favourite(bea, day.beas)
        react(bea, day.beas).status shouldBe 409
        send(bea, day.adas, """{"type":"LOVE"}""").status shouldBe 422
        send(bea, day.adas, """{"type":"HEART","note":"zq-note"}""").status shouldBe 400
        send(bea, day.adas, """{"type":"HEART"}""", contentType = null).status shouldBe 415
        // And takes back a reaction she never left.
        unreact(bea, day.adas).status shouldBe 204
        rows().shouldBeEmpty()

        whatAdaIsAnswered() shouldBe before

        // The reaction itself is what he sees: set, then replaced, then gone.
        react(bea, day.adas, "HEART").status shouldBe 204
        val set = whatAdaIsAnswered()
        set.zip(before).forEach { (now, then) -> now shouldNotBe then }
        set.forEach { it shouldContain "\"reaction\":\"HEART\"" }
        // Her reading it again, and setting what is already set, moves nothing.
        views(bea, day.bond).size shouldBe 3
        react(bea, day.adas, "HEART").status shouldBe 204
        whatAdaIsAnswered() shouldBe set

        react(bea, day.adas, "THANK_YOU").status shouldBe 204
        val replaced = whatAdaIsAnswered()
        replaced.zip(set).forEach { (now, then) -> now shouldNotBe then }
        replaced.forEach { it shouldContain "\"reaction\":\"THANK_YOU\"" }

        unreact(bea, day.adas).status shouldBe 204
        whatAdaIsAnswered() shouldBe before
    }

    @Test
    fun `no response carries when a reaction was made, and the table has nowhere to keep it`() {
        val day = revealedDay()
        clock.set(NOW.plusSeconds(4_321))
        react(bea, day.adas, "HEART").status shouldBe 204

        jdbc.queryForList(
            "SELECT column_name::text FROM information_schema.columns WHERE table_name = 'reactions' ORDER BY ordinal_position",
            String::class.java,
        ) shouldBe listOf("entry_id", "member_id", "type")
        // The instant it was made, to the second, is in nothing either of them is sent.
        for (user in listOf(ada, bea)) {
            for (view in views(user, day.bond)) view.toString() shouldNotContain "11:12:01"
            shown(user, day.bond, day.adas).forEach { it.propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS }
        }
    }

    // ---- a write and its replay ----

    @Test
    fun `a fresh write says null, and its replay says what the partner has left on it since`() {
        val bond = pair()
        val key = UUID.randomUUID().toString()

        val posted = submit(ada, bond, ADAS_WORDS, key)
        posted.status shouldBe 201
        json.readTree(posted.contentAsString)["reaction"].isNull shouldBe true
        val adas = json.readTree(posted.contentAsString)["id"].asString()
        rig.submit(bea, bond, BEAS_WORDS)

        react(bea, adas, "MOVED").status shouldBe 204
        json.readTree(submit(ada, bond, ADAS_WORDS, key).contentAsString)["reaction"].asString() shouldBe "MOVED"

        // Erased, the replay is the wide tombstone, which says null whatever rows there are.
        rig.delete(ada, adas)
        row(adas, rig.memberId(bond, bea), "MOVED")
        val tombstone = json.readTree(submit(ada, bond, ADAS_WORDS, key).contentAsString)
        tombstone["status"].asString() shouldBe "DELETED"
        tombstone["reaction"].isNull shouldBe true
    }

    // ---- erasure ----

    @Test
    fun `an entry its author deletes keeps no reaction, and the reaction its author left on the partner's entry stays`() {
        val day = revealedDay()
        react(bea, day.adas, "HEART").status shouldBe 204
        react(ada, day.beas, "SMILE").status shouldBe 204

        rig.delete(ada, day.adas)

        rows() shouldBe listOf(Row(day.beas, day.adaMember, "SMILE"))
        for (user in listOf(ada, bea)) {
            reactionShown(user, day.bond, day.adas) shouldBe listOf(null, null, null)
            reactionShown(user, day.bond, day.beas) shouldBe listOf("SMILE", "SMILE", "SMILE")
        }

        // Deleting again is a repeat of nothing: it erases nothing and writes nothing, here included. A row put
        // on the tombstone by hand, which no request can make, is how a write that should not happen would show.
        row(day.adas, day.beaMember, "HEART")
        rig.delete(ada, day.adas)
        rows() shouldContainExactlyInAnyOrder listOf(Row(day.adas, day.beaMember, "HEART"), Row(day.beas, day.adaMember, "SMILE"))
    }

    @Test
    fun `the close job reaching a withdrawn author's day before the consumer leaves no reaction on what it erases`() {
        clock.set(NOW.minusSeconds(DAY))
        val bond = rig.pair(ada, bea)
        clock.set(NOW)
        val adas = rig.submit(ada, bond, ADAS_WORDS)
        val beas = rig.submit(bea, bond, BEAS_WORDS)
        react(bea, adas, "HEART").status shouldBe 204
        react(ada, beas, "SMILE").status shouldBe 204
        rig.block(ada, bond)
        rig.postpone(bond, MIDNIGHT.plusSeconds(3_600))
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 0, failed = 0, more = false)
        rows().size shouldBe 2

        clock.set(MIDNIGHT)
        closer.closeElapsedDays(clock.instant(), 1_000).failed shouldBe 0

        // The close job did the consumer's work on that day, and the consumer has still not run.
        rig.delivery(bond)["processed_at"].shouldBeNull()
        entry(adas)["status"] shouldBe "DELETED"
        entry(adas)["text"].shouldBeNull()
        // Her reaction on his withdrawn entry went with it; his on hers was not his entry's to take.
        rows() shouldBe listOf(Row(beas, rig.memberId(bond, ada), "SMILE"))

        // The consumer, arriving second, finds nothing to do.
        clock.set(MIDNIGHT.plusSeconds(3_601))
        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        rows() shouldBe listOf(Row(beas, rig.memberId(bond, ada), "SMILE"))
    }

    // ---- the statement, by itself ----

    @Test
    fun `the statement takes only a revealed, unerased entry somebody else wrote - its author gets no row whoever asks it`() {
        val bond = pair()
        val adaMember = rig.memberId(bond, ada)
        val beaMember = rig.memberId(bond, bea)
        val adas = rig.submit(ada, bond, ADAS_WORDS)
        val id = EntryId(UUID.fromString(adas))

        // Not revealed: nothing, for either member.
        reactions.set(id, beaMember, ReactionType.HEART) shouldBe false
        reactions.set(id, adaMember, ReactionType.HEART) shouldBe false
        rows().shouldBeEmpty()

        rig.submit(bea, bond, BEAS_WORDS)
        // Revealed: still nothing for its author, whom the service would have stopped first.
        reactions.set(id, adaMember, ReactionType.HEART) shouldBe false
        rows().shouldBeEmpty()
        reactions.set(id, beaMember, ReactionType.HEART) shouldBe true
        // A second time is what was asked; another type replaces it.
        reactions.set(id, beaMember, ReactionType.HEART) shouldBe true
        rows() shouldBe listOf(Row(adas, beaMember, "HEART"))
        reactions.set(id, beaMember, ReactionType.SMILE) shouldBe true
        rows() shouldBe listOf(Row(adas, beaMember, "SMILE"))
        // Asked of the entry, with no member named: the one reaction it has.
        reactions.on(listOf(id, EntryId(UUID.randomUUID()))) shouldBe mapOf(id to ReactionType.SMILE)
        reactions.on(emptyList()) shouldBe emptyMap()

        // Erased, by either mark of an erasure: nothing.
        reactions.remove(id, beaMember) shouldBe true
        rows().shouldBeEmpty()
        // Absent is what was asked for.
        reactions.remove(id, beaMember) shouldBe true
        for (erasure in listOf("deleted_at = now()", "status = 'DELETED'")) {
            withClue(erasure) {
                jdbc.update("UPDATE entries SET deleted_at = NULL, status = 'REVEALED' WHERE id = ?::uuid", adas) shouldBe 1
                jdbc.update("UPDATE entries SET $erasure WHERE id = ?::uuid", adas) shouldBe 1
                reactions.set(id, beaMember, ReactionType.HEART) shouldBe false
                rows().shouldBeEmpty()
            }
        }
        // And an id nobody has.
        reactions.set(EntryId(UUID.randomUUID()), beaMember, ReactionType.HEART) shouldBe false
        rows().shouldBeEmpty()
    }

    @Test
    fun `the table holds one row for a member on an entry, and only one of the four types`() {
        val day = revealedDay()
        row(day.adas, day.beaMember, "HEART")

        shouldThrow<DataIntegrityViolationException> { row(day.adas, day.beaMember, "SMILE") }
        shouldThrow<DataIntegrityViolationException> { row(day.beas, day.adaMember, "LOVE") }
        shouldThrow<DataIntegrityViolationException> { row(day.beas, day.adaMember, "heart") }
        rows() shouldBe listOf(Row(day.adas, day.beaMember, "HEART"))
    }

    // ---- the reaction and the erasure, at the same time ----

    /**
     * The erasure commits **after the gate has answered and before the
     * statement runs**. The statement is the second line, and finds nothing
     * to insert. The seam is `MarkerReadLastTest`'s: something happens as the
     * request's last membership resolution returns, which is the one its
     * reader is made from. The count is checked, so a path that gains or
     * loses a resolution fails here and is looked at again.
     */
    @Test
    fun `an erasure that commits between the gate and the statement leaves no reaction, and the request is told the entry is gone`() {
        val day = revealedDay()
        // Two resolutions: the route's, then the reader's. The gate is asked of what the second returns.
        interruptible.afterResolution(2) {
            // Disarmed first, so that the delete's own resolutions are not counted as this request's.
            interruptible.disarm()
            pool.submit { rig.delete(ada, day.adas) }.get(10, TimeUnit.SECONDS)
        }

        val refused = react(bea, day.adas)

        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 2
        refused.status shouldBe 409
        json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
        entry(day.adas)["status"] shouldBe "DELETED"
        rows().shouldBeEmpty()
    }

    /**
     * The same seam, with a withdrawal instead: the gate and the bond were
     * asked before it committed, the row is still whole, and so the statement
     * takes the reaction (the plan's decision 5: the bond's lock is not
     * taken). Nothing shows it, since the gate says tombstone from here on,
     * and the erasure removes it.
     */
    @Test
    fun `a withdrawal that commits between the gate and the statement can leave a reaction, which nothing shows and the erasure removes`() {
        val day = revealedDay()
        interruptible.afterResolution(2) { pool.submit { rig.block(ada, day.bond) }.get(10, TimeUnit.SECONDS) }

        react(bea, day.adas).status shouldBe 204

        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 2
        interruptible.disarm()
        rows() shouldBe listOf(Row(day.adas, day.beaMember, "HEART"))
        for (user in listOf(ada, bea)) reactionShown(user, day.bond, day.adas) shouldBe listOf(null, null, null)

        dispatcher.dispatchDue(clock.instant(), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
        entry(day.adas)["text"].shouldBeNull()
        rows().shouldBeEmpty()
    }

    /**
     * The same seam, with an ending that withdraws **nothing**: the race the
     * plan's decision 5 accepts, and the one whose outcome is seen. The bond
     * and the gate were asked before the leave committed, no lock on the
     * bond is taken, and so the reaction lands a moment after the ending.
     * The author is shown it. Pinned here so that a change to it is noticed;
     * whether it should be so is the owner's to turn.
     */
    @Test
    fun `an ending without withdrawal that commits between the gate and the statement lets the reaction land, and the author sees it`() {
        val day = revealedDay()
        interruptible.afterResolution(2) { pool.submit { rig.leave(ada, day.bond) }.get(10, TimeUnit.SECONDS) }

        react(bea, day.adas, "MOVED").status shouldBe 204

        interruptible.fired shouldBe true
        interruptible.resolutions shouldBe 2
        interruptible.disarm()
        rows() shouldBe listOf(Row(day.adas, day.beaMember, "MOVED"))
        for (user in listOf(ada, bea)) reactionShown(user, day.bond, day.adas) shouldBe listOf("MOVED", "MOVED", "MOVED")
        // The bond has ended: the next one is refused, and this one can still be taken back.
        json.readTree(react(bea, day.adas, "HEART").contentAsString)["code"].asString() shouldBe "BOND_ARCHIVED"
        unreact(bea, day.adas).status shouldBe 204
        rows().shouldBeEmpty()
    }

    /**
     * **The marker is read last** (ADR-0035 decision 12), for this route: the
     * request reads the entry, and only then asks who has withdrawn and
     * whether the bond has ended. Here an ending commits while the request is
     * stopped at its read of the entry, which is after it resolved the
     * caller's membership for the route. Decided from that first membership,
     * the reaction would be left on an entry both people are already shown
     * as a tombstone, or on a bond that has ended.
     *
     * The request is stopped by a lock on the table, taken as its first
     * resolution returns, and the test waits until Postgres reports its read
     * of `entries` waiting behind that lock before the ending is made
     * (`FavouritesTest` has why a lock on the whole table is safe here).
     */
    @Test
    fun `who has withdrawn and whether the bond has ended are asked after the entry is read, so an ending during that read refuses`() {
        for ((ending, code) in listOf("a block that withdraws" to "ENTRY_IMMUTABLE", "a leave that keeps the entries" to "BOND_ARCHIVED")) {
            withClue(ending) {
                rig.clear()
                val day = revealedDay()
                withAnotherTransaction { holder ->
                    val holderPid = backendPidOf(holder)
                    interruptible.afterResolution(1) {
                        interruptible.disarm()
                        holder.createStatement().use { it.execute("LOCK TABLE entries IN ACCESS EXCLUSIVE MODE") }
                    }

                    val reacting = pool.submit(Callable { react(bea, day.adas) })
                    await().atMost(Duration.ofSeconds(10)).until { reacting.isDone || waitingBehind(holderPid).isNotEmpty() }
                    reacting.isDone shouldBe false
                    interruptible.fired shouldBe true
                    // Whatever else may be waiting on that lock, this request is.
                    waitingBehind(holderPid).any { "entries" in it } shouldBe true

                    if (code == "ENTRY_IMMUTABLE") rig.block(ada, day.bond) else rig.leave(ada, day.bond)
                    holder.commit()
                    val refused = reacting.get(10, TimeUnit.SECONDS)

                    refused.status shouldBe 409
                    json.readTree(refused.contentAsString)["code"].asString() shouldBe code
                    // Nothing was erased: the row is whole, and it is the request's own second look that refused.
                    entry(day.adas)["text"] shouldBe ADAS_WORDS
                    rows().shouldBeEmpty()
                }
            }
        }
    }

    /**
     * The two really at once: an erasure that has done everything but
     * commit, and a reaction that arrives while it is open. The request reads
     * the entry as it was committed, whole, and its gate says yes; what is
     * left to stop it is the statement.
     *
     * Synchronised on what happened, not on time: the erasing transaction
     * says when it has erased, and the test then waits until Postgres itself
     * reports the request's statement waiting behind that transaction. Only
     * then is the erasure allowed to commit.
     */
    @Test
    fun `a reaction that arrives while an erasure is uncommitted waits for it, and then leaves no row on the erased entry`() {
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

            val reacting = pool.submit(Callable { react(bea, day.adas) })
            await().atMost(Duration.ofSeconds(10)).until { reacting.isDone || waitingBehind(eraserPid.get()).isNotEmpty() }
            reacting.isDone shouldBe false
            waitingBehind(eraserPid.get()).any { "INSERT INTO reactions" in it } shouldBe true

            commit.countDown()
            erasing.get(10, TimeUnit.SECONDS)
            val refused = reacting.get(10, TimeUnit.SECONDS)

            refused.status shouldBe 409
            json.readTree(refused.contentAsString)["code"].asString() shouldBe "ENTRY_IMMUTABLE"
            entry(day.adas)["status"] shouldBe "DELETED"
            rows().shouldBeEmpty()
        } finally {
            commit.countDown()
        }
    }

    /**
     * The statement's lock must wait for **any** change to the entry's row,
     * not only for an eraser that takes `FOR UPDATE` first. This eraser is a
     * plain `UPDATE`, which Postgres locks `FOR NO KEY UPDATE`: `FOR KEY
     * SHARE` does not conflict with that. `FOR SHARE` does
     * (ADR-0036 decision 12, where a bookmark was left this way).
     */
    @Test
    fun `a reaction waits for an erasure that took no row lock first, and then leaves nothing on the erased entry`() {
        val day = revealedDay()
        withAnotherTransaction { erasing ->
            val eraserPid = backendPidOf(erasing)
            erasing.createStatement().use {
                it.executeUpdate("UPDATE entries SET text = NULL, status = 'DELETED', deleted_at = now() WHERE id = '${day.adas}'")
                it.executeUpdate("DELETE FROM reactions WHERE entry_id = '${day.adas}'")
            }

            val reacting = pool.submit(Callable { reactions.set(EntryId(UUID.fromString(day.adas)), day.beaMember, ReactionType.HEART) })
            await()
                .atMost(
                    Duration.ofSeconds(10),
                ).until { reacting.isDone || waitingBehind(eraserPid).any { "INSERT INTO reactions" in it } }
            reacting.isDone shouldBe false

            erasing.commit()
            reacting.get(10, TimeUnit.SECONDS) shouldBe false
        }
        entry(day.adas)["status"] shouldBe "DELETED"
        rows().shouldBeEmpty()
    }

    // ---- fixtures ----

    private data class Day(
        val bond: String,
        val adas: String,
        val beas: String,
        val adaMember: UUID,
        val beaMember: UUID,
    )

    /** A row of `reactions`, as it is in the table. */
    private data class Row(
        val entry: String,
        val member: UUID,
        val type: String,
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

    /** Every reaction there is. */
    private fun rows(): List<Row> =
        jdbc.query("SELECT entry_id::text AS entry, member_id, type FROM reactions ORDER BY entry_id, member_id") { row, _ ->
            Row(row.getString("entry"), row.getObject("member_id", UUID::class.java), row.getString("type"))
        }

    /** A reaction put there by hand: the state a request leaves when it was answered just before a withdrawal committed. */
    private fun row(
        entryId: String,
        memberId: UUID,
        type: String,
    ) {
        jdbc.update("INSERT INTO reactions (entry_id, member_id, type) VALUES (?::uuid, ?, ?)", entryId, memberId, type) shouldBe 1
    }

    private fun entry(id: String): Map<String, Any?> =
        jdbc.queryForMap("SELECT text, status, deleted_at, revealed_at FROM entries WHERE id = ?::uuid", id)

    /** [user]'s `/today`, the day of [DATE] in the feed, and that day alone: the three places an entry is shown. */
    private fun views(
        user: UUID,
        bond: String,
    ): List<JsonNode> =
        listOf(
            json.readTree(rig.today(user, bond)),
            archive.page(user, bond)["items"].single { it["date"].asString() == DATE },
            json.readTree(archive.day(user, bond, DATE).also { it.status shouldBe 200 }.getContentAsString(Charsets.UTF_8)),
        )

    /** The wide entry [entryId] as [user] is shown it in each of [views]. */
    private fun shown(
        user: UUID,
        bond: String,
        entryId: String,
    ): List<JsonNode> =
        views(user, bond).map { view ->
            listOf(view["myEntry"], view["partnerEntry"]).single { it != null && it["id"]?.asString() == entryId }
        }

    /** What `reaction` says on [entryId] in each of [views], which must carry the field; `null` for JSON null. */
    private fun reactionShown(
        user: UUID,
        bond: String,
        entryId: String,
    ): List<String?> =
        shown(user, bond, entryId).map { entry ->
            entry.has("reaction") shouldBe true
            entry["reaction"].takeUnless { it.isNull }?.asString()
        }

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

    private fun react(
        user: UUID,
        entryId: String,
        type: String = "HEART",
    ): MockHttpServletResponse = send(user, entryId, """{"type":"$type"}""")

    /** `PUT …/reaction` with [body] exactly as given, and [contentType] or none. */
    private fun send(
        user: UUID,
        entryId: String,
        body: String,
        contentType: MediaType? = MediaType.APPLICATION_JSON,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/entries/$entryId/reaction") {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                if (contentType != null) this.contentType = contentType
                content = body
            }.andReturn()
            .response

    private fun unreact(
        user: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$entryId/reaction") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
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
        val body = response.getContentAsString(Charsets.UTF_8).replace(Regex("\"instance\":\"[^\"]*\""), "\"instance\":\"-\"")
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

        /** The bond's date at [NOW]. */
        const val DATE = "2026-09-15"

        /** Two minutes after the 15th ended in Lagos: past the close job's margin. */
        val MIDNIGHT: Instant = Instant.parse("2026-09-15T23:02:00Z")
        const val DAY = 86_400L
        const val MEGABYTE = 1024 * 1024

        val TYPES = listOf("HEART", "THANK_YOU", "SMILE", "MOVED")
        val FULL_KEYS =
            listOf("id", "bondId", "date", "authorMemberId", "text", "status", "createdAt", "intendedAt", "favourited", "reaction")

        // Markers no UUID, date or hexadecimal digest can contain.
        const val ADAS_WORDS = "zq-ada-thanks-for-the-walk-home"
        const val ADAS_LATER_WORDS = "zq-ada-thanks-for-the-day-after"
        const val BEAS_WORDS = "zq-bea-thanks-for-the-tea"
    }
}
