package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A withdrawal is unreadable from the commit of the request that made it
 * (spec §6.7: "as soon as the block transaction commits, even if the poller
 * is stopped"), through every response that returns an entry: `GET /today`,
 * and an `Idempotency-Key` replay of `POST /entries` and of `PATCH`.
 *
 * **Nothing here erases anything, and each test says so.** This context has
 * the consumer of `EntriesWithdrawn` but no poller, and no test calls the
 * dispatcher, so the delivery stays owed. When a response comes back without
 * the words, the words are still in the row: [nothingWasErased] reads them
 * there after the last response of every scenario, and finds the delivery
 * unmade. What hid them is the read gate
 * ([com.moyi.gratitude.domain.Entry.canBeReadBy]) and nothing else. A test of
 * the consumer would pass with the gate removed; these would not.
 *
 * Each response is searched whole for the withdrawn words, as a string, and
 * not only at the field they would be expected in.
 *
 * The clock is `EntryChangesTest.TimeConfiguration`'s, shared so that this
 * class runs in the context that one already built: every entry lands on
 * `2026-09-15` in `Africa/Lagos`, which is still "today" when each read is
 * made, so `GET /today` on the ended bond reaches the very entries written.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used, and there is nothing to bundle them into.
internal class WithdrawalReadTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val context: ApplicationContext,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(BOND_CREATED)
        bondId = pair(ada, bea)
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_entry_withdrawals, " +
                "bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    @Test
    fun `a block hides the blocker's revealed entry from both, on today, and leaves the partner's whole`() {
        val adas = entryOf(submit(ada, ADAS_WORDS))
        val beas = entryOf(submit(bea, BEAS_WORDS))
        val before = today(ada)
        before["bondDay"]["status"].asString() shouldBe "REVEALED"
        before["partnerEntry"]["text"].asString() shouldBe BEAS_WORDS

        block(ada).status shouldBe 204

        for (reader in listOf(ada, bea)) {
            val response = todayResponse(reader)
            response.status shouldBe 200
            response.contentAsString shouldNotContain ADAS_WORDS
            val today = json.readTree(response.contentAsString)
            val (hers, his) = if (reader == ada) today["myEntry"] to today["partnerEntry"] else today["partnerEntry"] to today["myEntry"]
            // Both could read it before, so both get the row without its words.
            hers["id"].asString() shouldBe adas.id
            hers["text"].isNull shouldBe true
            hers["status"].asString() shouldBe "DELETED"
            // Bea withdrew nothing.
            his["id"].asString() shouldBe beas.id
            his["text"].asString() shouldBe BEAS_WORDS
            his["status"].asString() shouldBe "REVEALED"
            // The marker hides words; it does not rewrite the day (spec §6.7, BR-10).
            today["bondDay"]["status"].asString() shouldBe "REVEALED"
            today["bondDay"]["date"].asString() shouldBe "2026-09-15"
        }
        nothingWasErased(adas.id to ADAS_WORDS, beas.id to BEAS_WORDS)
    }

    @Test
    fun `a replay of the blocker's own submission answers without the words, and the partner's replay with theirs`() {
        val adasKey = UUID.randomUUID().toString()
        val beasKey = UUID.randomUUID().toString()
        val adas = entryOf(submit(ada, ADAS_WORDS, key = adasKey))
        val beas = entryOf(submit(bea, BEAS_WORDS, key = beasKey))

        block(ada).status shouldBe 204

        // A replay is a read of what the key produced, and answers on an ended bond.
        val hers = submit(ada, ADAS_WORDS, key = adasKey)
        hers.status shouldBe 201
        hers.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        hers.contentAsString shouldNotContain ADAS_WORDS
        val replayed = json.readTree(hers.contentAsString)
        replayed["id"].asString() shouldBe adas.id
        replayed["text"].isNull shouldBe true
        replayed["status"].asString() shouldBe "DELETED"

        // The other member's key still answers with the words it stored nothing of and re-read whole.
        val his = submit(bea, BEAS_WORDS, key = beasKey)
        his.status shouldBe 201
        his.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        val whole = json.readTree(his.contentAsString)
        whole["id"].asString() shouldBe beas.id
        whole["text"].asString() shouldBe BEAS_WORDS

        // And a new write is still refused, for both: only the replay is a read.
        for (writer in listOf(ada, bea)) {
            val fresh = submit(writer, "something new")
            fresh.status shouldBe 409
            fresh.contentAsString shouldContain "BOND_ARCHIVED"
        }
        nothingWasErased(adas.id to ADAS_WORDS, beas.id to BEAS_WORDS)
    }

    @Test
    fun `a replay of the blocker's edit answers without the words it wrote or the ones it replaced`() {
        val adas = entryOf(submit(ada, ADAS_FIRST_WORDS))
        val key = UUID.randomUUID().toString()
        val edited = patchEntry(ada, adas.id, ADAS_WORDS, key)
        edited.status shouldBe 200
        edited.contentAsString shouldContain ADAS_WORDS
        val beas = entryOf(submit(bea, BEAS_WORDS))
        today(bea)["partnerEntry"]["text"].asString() shouldBe ADAS_WORDS

        block(ada).status shouldBe 204

        val replay = patchEntry(ada, adas.id, ADAS_WORDS, key)
        replay.status shouldBe 200
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldNotContain ADAS_WORDS
        replay.contentAsString shouldNotContain ADAS_FIRST_WORDS
        val replayed = json.readTree(replay.contentAsString)
        replayed["id"].asString() shouldBe adas.id
        replayed["text"].isNull shouldBe true
        replayed["status"].asString() shouldBe "DELETED"

        // An edit that is not a replay is a write, and the bond has ended.
        val fresh = patchEntry(ada, adas.id, "something new", UUID.randomUUID().toString())
        fresh.status shouldBe 409
        fresh.contentAsString shouldContain "BOND_ARCHIVED"

        // The partner, who had read the edited words, reads none now.
        val theirs = todayResponse(bea)
        theirs.contentAsString shouldNotContain ADAS_WORDS
        theirs.contentAsString shouldNotContain ADAS_FIRST_WORDS
        json.readTree(theirs.contentAsString)["myEntry"]["text"].asString() shouldBe BEAS_WORDS
        nothingWasErased(adas.id to ADAS_WORDS, beas.id to BEAS_WORDS)
    }

    @Test
    fun `an entry withdrawn before it was ever revealed is author and REMOVED to the partner, and nothing else`() {
        val adas = entryOf(submit(ada, ADAS_WORDS))
        val before = today(bea)
        before["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        before["partnerEntry"]["status"].asString() shouldBe "LOCKED"
        before["bondDay"]["status"].asString() shouldBe "PARTIAL"

        block(ada).status shouldBe 204

        // The partner was owed BR-8's two fields while it was live, and is owed no more now:
        // no id, no timestamps, no date, nothing by which to tell when it was written.
        val theirs = todayResponse(bea)
        theirs.contentAsString shouldNotContain ADAS_WORDS
        theirs.contentAsString shouldNotContain adas.id
        val partners = json.readTree(theirs.contentAsString)
        partners["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
        partners["partnerEntry"]["authorMemberId"].asString() shouldBe adas.authorMemberId
        partners["partnerEntry"]["status"].asString() shouldBe "REMOVED"
        partners["myEntry"].isNull shouldBe true
        partners["bondDay"]["status"].asString() shouldBe "PARTIAL"

        // The author always knew when she wrote it: the wide tombstone, without the words.
        val hers = todayResponse(ada)
        hers.contentAsString shouldNotContain ADAS_WORDS
        val own = json.readTree(hers.contentAsString)
        own["myEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder
            listOf("id", "bondId", "date", "authorMemberId", "text", "status", "createdAt", "intendedAt", "favourited")
        own["myEntry"]["favourited"].asBoolean() shouldBe false
        own["myEntry"]["id"].asString() shouldBe adas.id
        own["myEntry"]["text"].isNull shouldBe true
        own["myEntry"]["status"].asString() shouldBe "DELETED"
        own["partnerEntry"].isNull shouldBe true
        own["bondDay"]["status"].asString() shouldBe "PARTIAL"
        nothingWasErased(adas.id to ADAS_WORDS)
    }

    @Test
    fun `leaving without withdrawing changes nothing either of them reads`() {
        val adasKey = UUID.randomUUID().toString()
        val adas = entryOf(submit(ada, ADAS_WORDS, key = adasKey))
        val beas = entryOf(submit(bea, BEAS_WORDS))
        val before = listOf(ada, bea).map { todayResponse(it).contentAsString }

        leave(ada, body = null).status shouldBe 204

        listOf(ada, bea).map { todayResponse(it).contentAsString } shouldBe before
        before[0] shouldContain ADAS_WORDS
        before[1] shouldContain ADAS_WORDS
        json.readTree(submit(ada, ADAS_WORDS, key = adasKey).contentAsString)["text"].asString() shouldBe ADAS_WORDS
        nothingWasErased(adas.id to ADAS_WORDS, beas.id to BEAS_WORDS, withdrawals = 0)
    }

    @Test
    fun `leaving and withdrawing hides the leaver's entries from both, as a block does`() {
        val adasKey = UUID.randomUUID().toString()
        val adas = entryOf(submit(ada, ADAS_WORDS, key = adasKey))
        val beas = entryOf(submit(bea, BEAS_WORDS))

        leave(ada, body = """{"withdrawEntries": true}""").status shouldBe 204

        for (reader in listOf(ada, bea)) {
            val response = todayResponse(reader)
            response.contentAsString shouldNotContain ADAS_WORDS
            response.contentAsString shouldContain BEAS_WORDS
            val today = json.readTree(response.contentAsString)
            val hers = if (reader == ada) today["myEntry"] else today["partnerEntry"]
            hers["id"].asString() shouldBe adas.id
            hers["text"].isNull shouldBe true
            hers["status"].asString() shouldBe "DELETED"
            today["bondDay"]["status"].asString() shouldBe "REVEALED"
        }
        val replay = submit(ada, ADAS_WORDS, key = adasKey)
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldNotContain ADAS_WORDS
        nothingWasErased(adas.id to ADAS_WORDS, beas.id to BEAS_WORDS)
    }

    @Test
    fun `the same two people's other bond is untouched by a withdrawal in this one`() {
        // Two bonds between the same users, both with a revealed day today.
        // A withdrawal is of one bond: the marker is that bond's, and so is the
        // membership every Reader is made from.
        val other = pair(ada, bea)
        val adas = entryOf(submit(ada, ADAS_WORDS))
        val beas = entryOf(submit(bea, BEAS_WORDS))
        val adasOther = entryOf(submit(ada, ADAS_OTHER_WORDS, bond = other))
        val beasOther = entryOf(submit(bea, BEAS_OTHER_WORDS, bond = other))
        val otherBefore = listOf(ada, bea).map { todayResponse(it, bond = other).contentAsString }

        leave(ada, body = """{"withdrawEntries": true}""").status shouldBe 204

        todayResponse(bea).contentAsString shouldNotContain ADAS_WORDS
        listOf(ada, bea).map { todayResponse(it, bond = other).contentAsString } shouldBe otherBefore
        otherBefore.forEach {
            it shouldContain ADAS_OTHER_WORDS
            it shouldContain BEAS_OTHER_WORDS
        }
        nothingWasErased(
            adas.id to ADAS_WORDS,
            beas.id to BEAS_WORDS,
            adasOther.id to ADAS_OTHER_WORDS,
            beasOther.id to BEAS_OTHER_WORDS,
        )
    }

    /**
     * What makes every assertion above a test of the gate: after the last
     * response, each entry's row still holds its words and bears no mark of an
     * erasure; the withdrawal itself was recorded and published (so there was
     * something to honour); and nothing was delivered, because in this
     * context nothing could be.
     */
    private fun nothingWasErased(
        vararg entries: Pair<String, String>,
        withdrawals: Int = 1,
    ) {
        for ((id, words) in entries) {
            val row = jdbc.queryForMap("SELECT text, status, deleted_at FROM entries WHERE id = ?::uuid", id)
            row["text"] shouldBe words
            (row["status"] in setOf("SUBMITTED", "REVEALED")) shouldBe true
            row["deleted_at"].shouldBeNull()
        }
        // This bond's rows, not the tables' totals: the database is shared with other test classes.
        jdbc.queryForObject("SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id = ?::uuid", Int::class.java, bondId) shouldBe
            withdrawals
        val deliveries =
            jdbc.queryForList(
                """
                SELECT d.consumer_id, d.processed_at, d.attempts
                FROM outbox_events e LEFT JOIN outbox_deliveries d ON d.event_id = e.id
                WHERE e.event_type = 'EntriesWithdrawn' AND e.aggregate_id = ?::uuid
                """.trimIndent(),
                bondId,
            )
        // Published once, owed to the one consumer, and not delivered: nothing has run that could erase.
        deliveries.map { "${it["consumer_id"]} ${it["processed_at"]} ${it["attempts"]}" } shouldBe
            List(withdrawals) { "gratitude.withdrawal null 0" }
        // No timer in this context, so no poller.
        context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor::class.java).toList().shouldBeEmpty()
    }

    private data class Written(
        val id: String,
        val authorMemberId: String,
    )

    private fun entryOf(response: MockHttpServletResponse): Written {
        response.status shouldBe 201
        val body = json.readTree(response.contentAsString)
        return Written(body["id"].asString(), body["authorMemberId"].asString())
    }

    private fun today(
        user: UUID,
        bond: String = bondId,
    ): JsonNode = json.readTree(todayResponse(user, bond).contentAsString)

    private fun todayResponse(
        user: UUID,
        bond: String = bondId,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bond/today") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response

    private fun submit(
        caller: UUID,
        words: String,
        key: String = UUID.randomUUID().toString(),
        bond: String = bondId,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bond/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response

    private fun patchEntry(
        user: UUID,
        id: String,
        words: String,
        key: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"$words"}"""
            }.andReturn()
            .response

    /** With no body: withdrawal is what a block does unless it is declined (FR-029a). */
    private fun block(user: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/block") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response

    private fun leave(
        user: UUID,
        body: String?,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
            }.andReturn()
            .response

    /** A bond [creator] made and [joiner] accepted, and its id. */
    private fun pair(
        creator: UUID,
        joiner: UUID,
    ): String {
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(creator).token}")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        created.status shouldBe 201
        val body = json.readTree(created.contentAsString)
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(joiner).token}") }
            .andReturn()
            .response.status shouldBe 200
        return body["id"].asString()
    }

    private companion object {
        /** The clock's own instant ([EntryChangesTest.TimeConfiguration]): midday in Lagos on the 15th. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")

        const val ADAS_FIRST_WORDS = "ada-first-draft-of-thanks"
        const val ADAS_WORDS = "ada-thanks-for-the-walk-home"
        const val BEAS_WORDS = "bea-thanks-for-the-tea"
        const val ADAS_OTHER_WORDS = "ada-in-the-other-bond"
        const val BEAS_OTHER_WORDS = "bea-in-the-other-bond"
    }
}
