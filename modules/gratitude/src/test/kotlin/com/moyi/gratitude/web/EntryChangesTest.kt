package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.api.DayCloser
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `PATCH` and `DELETE /entries/{entryId}` (spec §5.2), through the real chain
 * against a real Postgres: who may change an entry, until when, and what is
 * left behind.
 *
 * **These two routes are not in the route-driven cross-tenant suite**, which
 * discovers routes by `{bondId}` and so cannot see a path that carries only
 * an entry id (spec §5.2 chose that path on purpose). The test below that
 * compares the `404`s is their cross-tenant test, and it asserts the exact
 * set of `{entryId}` routes — so another one added without a case fails
 * here. The favourite routes (slice C5b) are in that set and have their
 * case in `FavouritesTest`, which has the fixtures a mark needs.
 *
 * The clock is pinned by [TimeConfiguration]: every entry lands on
 * `2026-09-15` in `Africa/Lagos` unless a test moves it.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used, and there is nothing to bundle them into.
internal class EntryChangesTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val routes: org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping,
    @Autowired private val json: ObjectMapper,
    @Autowired private val closer: DayCloser,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var eve: UUID
    private lateinit var cara: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        eve = users.verified("Eve")
        cara = users.verified("Cara")

        // Created two days before NOW, so an offline draft from yesterday
        // names a time the bond already existed — a bond's calendar starts at
        // its creation, and a claim from before then is refused (see the
        // pre-creation test below).
        clock.set(BOND_CREATED)
        val created = createBond(ada)
        bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
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
    fun `an author edits before reveal and cannot edit after it`() {
        val id = entryId(submit(ada, bondId, """{"text":"before"}"""))
        patchEntry(ada, id, """{"text":"after"}""").status shouldBe 200
        jdbc.queryForObject("SELECT text FROM entries", String::class.java) shouldBe "after"
        submit(bea, bondId, """{"text":"partner"}""").status shouldBe 201
        val refused = patchEntry(ada, id, """{"text":"too late"}""")
        refused.status shouldBe 409
        refused.contentAsString shouldContain "ENTRY_IMMUTABLE"
    }

    @Test
    fun `delete frees the live slot and today picks the replacement`() {
        val id = entryId(submit(ada, bondId, """{"text":"removed words"}"""))
        deleteEntry(ada, id).status shouldBe 204
        deleteEntry(ada, id).status shouldBe 204
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE status = 'DELETED' AND deleted_at IS NOT NULL AND text IS NULL",
            Int::class.java,
        ) shouldBe
            1
        submit(ada, bondId, """{"text":"replacement words"}""").status shouldBe 201
        val today = today(ada)
        today.contentAsString shouldContain "replacement words"
        today.contentAsString shouldNotContain "removed words"
        jdbc.queryForObject("SELECT entry_count FROM bond_days", Int::class.java) shouldBe 1
    }

    @Test
    fun `post-reveal delete preserves the reveal timestamp and authoritative day`() {
        val id = entryId(submit(ada, bondId, """{"text":"removed words"}"""))
        submit(bea, bondId, """{"text":"partner words"}""").status shouldBe 201
        deleteEntry(ada, id).status shouldBe 204
        jdbc.queryForObject("SELECT status FROM bond_days", String::class.java) shouldBe "REVEALED"
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE id = ?::uuid AND revealed_at IS NOT NULL AND deleted_at IS NOT NULL",
            Int::class.java,
            id,
        ) shouldBe
            1
        today(bea).contentAsString shouldNotContain "removed words"
    }

    @Test
    fun `entry routes hide another author and another tenant exactly like a missing id`() {
        val id = entryId(submit(ada, bondId, """{"text":"private"}"""))
        // The partner, a stranger, an id nobody has, and something that is not an id.
        val targets = listOf(bea to id, eve to id, ada to UUID.randomUUID().toString(), ada to "bad-id")
        val edits = targets.map { (caller, target) -> patchEntry(caller, target, """{"text":"edit"}""") }
        val keyedEdits =
            targets.map { (caller, target) ->
                patchEntry(caller, target, """{"text":"edit"}""", key = UUID.randomUUID().toString())
            }
        val deletes = targets.map { (caller, target) -> deleteEntry(caller, target) }
        // And the partner again once they have left: still not their entry, so still not there.
        leave(bea).status shouldBe 204
        val afterLeaving = listOf(patchEntry(bea, id, """{"text":"edit"}"""), deleteEntry(bea, id))

        (edits + keyedEdits + deletes + afterLeaving).forEach { it.status shouldBe 404 }
        (edits + keyedEdits + deletes + afterLeaving).map(::comparable).toSet().size shouldBe 1
        jdbc.queryForObject("SELECT text FROM entries WHERE id = ?::uuid", String::class.java, id) shouldBe "private"

        val discovered =
            routes.handlerMethods.keys
                .flatMap { info ->
                    info.pathPatternsCondition?.patternValues.orEmpty().filter { "{entryId}" in it }.flatMap { path ->
                        info.methodsCondition.methods.map { "${it.name} $path" }
                    }
                }.toSet()
        // The two here, and the two `FavouritesTest` compares the same way (and against this one's delete).
        discovered shouldBe
            setOf(
                "PATCH /api/v1/entries/{entryId}",
                "DELETE /api/v1/entries/{entryId}",
                "PUT /api/v1/entries/{entryId}/favourite",
                "DELETE /api/v1/entries/{entryId}/favourite",
            )
    }

    /**
     * ADR-0028 made an ended bond read-only for both, and until 2026-10-06
     * this test pinned `409 BOND_ARCHIVED` for the delete as well (it was `an
     * ended bond's entries cannot be edited or deleted, by the member who
     * left or the one who stayed`, the one test ADR-0032 question 1 said
     * pinned the rule). The owner's ruling on that question turned the
     * delete: an author may always take their own words back. The edit is
     * still refused, and the author is still told so only after the author
     * check, so the `409` never answers anybody else.
     */
    @Test
    fun `an ended bond's entries cannot be edited but can be deleted by their author, the member who left or the one who stayed`() {
        val adas = entryId(submit(ada, bondId, """{"text":"hers"}"""))
        clock.set(NOW.plusSeconds(86_400))
        val beas = entryId(submit(bea, bondId, """{"text":"his"}"""))
        leave(bea).status shouldBe 204

        for ((author, id) in listOf(ada to adas, bea to beas)) {
            val edit = patchEntry(author, id, """{"text":"changed"}""")
            edit.status shouldBe 409
            edit.contentAsString shouldContain "BOND_ARCHIVED"
            // The ending is answered before the media is: on an open bond this body is a 422.
            val withMedia = patchEntry(author, id, """{"text":"changed","imageMediaId":"${UUID.randomUUID()}"}""")
            withMedia.status shouldBe 409
            withMedia.contentAsString shouldContain "BOND_ARCHIVED"
        }
        jdbc.queryForObject("SELECT count(*) FROM entries WHERE text IN ('hers', 'his') AND deleted_at IS NULL", Int::class.java) shouldBe 2

        for ((author, id) in listOf(ada to adas, bea to beas)) {
            val delete = deleteEntry(author, id)
            delete.status shouldBe 204
            delete.contentAsString shouldBe ""
        }
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE text IS NULL AND status = 'DELETED' AND deleted_at IS NOT NULL",
            Int::class.java,
        ) shouldBe 2
    }

    /**
     * The owner's ruling of 2026-10-06 (ADR-0032 question 1), in every way a
     * bond stops taking writes and for each of the two people in it.
     *
     * **This is what keeps a withdrawal discreet.** A withdrawal erases its
     * author's entries after the bond has ended. Were a single delete
     * impossible there, a tombstone appearing after the end could only mean
     * the one thing nothing is allowed to say (ADR-0028 decision 8).
     *
     * Bea is the one who ends it each time, so each ending is tried for the
     * member who left (her) and the one who stayed (Ada); and each for an
     * entry the partner had read and for one they never had, because what
     * the partner is left with differs: the wide tombstone, or `REMOVED`
     * and nothing else.
     */
    @Test
    fun `an author deletes their own entry after the bond has ended, however it ended, and nobody else can`() {
        for (ending in Ending.entries) {
            for (writers in listOf(listOf("Ada", "Bea"), listOf("Ada"), listOf("Bea"))) {
                withClue("$ending, written by $writers") {
                    clear()
                    setUp()
                    val written =
                        writers.map { name ->
                            val author = if (name == "Ada") ada else bea
                            author to entryId(submit(author, bondId, """{"text":"words of $name"}"""))
                        }
                    end(ending)

                    written.forEach { (author, id) -> onlyTheAuthorDeletes(author, id, revealed = writers.size == 2) }

                    jdbc.queryForObject("SELECT count(*) FROM entries WHERE text IS NOT NULL", Int::class.java) shouldBe 0
                }
            }
        }
    }

    /** On a bond that has ended: the edit refused, everybody else's delete the one `404`, the author's a `204` that erases. */
    private fun onlyTheAuthorDeletes(
        author: UUID,
        id: String,
        revealed: Boolean,
    ) {
        val partner = if (author == ada) bea else ada
        archivedToAnEdit(author, id)
        val refusals = listOf(deleteEntry(partner, id), deleteEntry(eve, id), deleteEntry(author, UUID.randomUUID().toString()))
        refusals.forEach { it.status shouldBe 404 }
        refusals.map(::comparable).toSet().size shouldBe 1
        val before = rowOf(id)
        before["text"].shouldNotBeNull()
        before["deleted_at"].shouldBeNull()
        val dayBefore = dayOf(id)

        val deleted = deleteEntry(author, id)
        deleted.status shouldBe 204
        deleted.contentAsString shouldBe ""

        val row = rowOf(id)
        row["text"].shouldBeNull()
        row["status"] shouldBe "DELETED"
        row["deleted_at"].shouldNotBeNull()
        row["revealed_at"] shouldBe before["revealed_at"]
        (row["revealed_at"] != null) shouldBe revealed

        // Repeatable, and the repeat is a repeat of nothing.
        clock.set(clock.instant().plusSeconds(1))
        deleteEntry(author, id).status shouldBe 204
        rowOf(id) shouldBe row

        // An unsettled day steps back, once; a revealed one is history and stays.
        val day = dayOf(id)
        if (revealed) {
            day shouldBe dayBefore
            day["status"] shouldBe "REVEALED"
        } else {
            day["entry_count"] shouldBe 0
            day["status"] shouldBe "OPEN"
        }
        tombstoneIsWhatBothRead(author, partner, id, revealed)
        // Refused for the bond, before anything is asked about the entry.
        archivedToAnEdit(author, id)
    }

    private fun archivedToAnEdit(
        author: UUID,
        id: String,
    ) {
        val edit = patchEntry(author, id, """{"text":"changed"}""")
        edit.status shouldBe 409
        edit.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    /** The wide tombstone for a partner who had read the entry; an author and `REMOVED`, and nothing else, for one who never had. */
    private fun tombstoneIsWhatBothRead(
        author: UUID,
        partner: UUID,
        id: String,
        revealed: Boolean,
    ) {
        val partners = today(partner)
        partners.contentAsString shouldNotContain "words of ${if (author == ada) "Ada" else "Bea"}"
        val theirs = json.readTree(partners.contentAsString)["partnerEntry"]
        if (revealed) {
            theirs["id"].asString() shouldBe id
            theirs["status"].asString() shouldBe "DELETED"
            theirs["text"].isNull shouldBe true
        } else {
            theirs.propertyNames().toList().sorted() shouldBe listOf("authorMemberId", "status")
            theirs["status"].asString() shouldBe "REMOVED"
        }
        val own = json.readTree(today(author).contentAsString)["myEntry"]
        own["id"].asString() shouldBe id
        own["status"].asString() shouldBe "DELETED"
        own["text"].isNull shouldBe true
    }

    @Test
    fun `a delete on an ended bond leaves a day the close job has settled exactly as it was`() {
        // Ada writes alone, the day ends and is closed with her entry shown
        // (`SOLO`), and then the bond ends. Her delete erases the words and
        // nothing else: settled history is not recounted (BR-10).
        val id = entryId(submit(ada, bondId, """{"text":"hers alone"}"""))
        clock.set(DAY_AFTER)
        closer.closeElapsedDays(DAY_AFTER, 1_000).closed shouldBe 1
        val settled = dayOf(id)
        settled["status"] shouldBe "SOLO"
        settled["closed_at"].shouldNotBeNull()
        rowOf(id)["revealed_at"].shouldNotBeNull()
        leave(bea).status shouldBe 204

        deleteEntry(ada, id).status shouldBe 204

        dayOf(id) shouldBe settled
        val row = rowOf(id)
        row["text"].shouldBeNull()
        row["status"] shouldBe "DELETED"
        row["revealed_at"].shouldNotBeNull()
    }

    @Test
    fun `editing an entry its author erased is refused without claiming it was revealed`() {
        val id = entryId(submit(ada, bondId, """{"text":"gone"}"""))
        deleteEntry(ada, id).status shouldBe 204

        val refused = patchEntry(ada, id, """{"text":"back"}""")

        refused.status shouldBe 409
        refused.contentAsString shouldContain "ENTRY_IMMUTABLE"
        refused.contentAsString shouldNotContain "revealed"
    }

    @Test
    fun `patch asks the same domain text validator and refuses unsupported media`() {
        val id = entryId(submit(ada, bondId, """{"text":"private"}"""))
        patchEntry(ada, id, """{"text":" "}""").status shouldBe 422
        patchEntry(ada, id, """{"text":"${"a".repeat(501)}"}""").status shouldBe 422
        patchEntry(ada, id, """{"text":"ok","imageMediaId":"${UUID.randomUUID()}"}""").status shouldBe 422
        patchEntry(ada, id, "{}").status shouldBe 422
    }

    @Test
    fun `a real update constraint failure never exposes entry text`(output: org.springframework.boot.test.system.CapturedOutput) {
        val id = entryId(submit(ada, bondId, """{"text":"privacy-canary-before"}"""))
        jdbc.execute("ALTER TABLE entries ADD CONSTRAINT c2_privacy_probe CHECK (updated_at = created_at)")
        try {
            clock.set(NOW.plusSeconds(1))
            val response = patchEntry(ada, id, """{"text":"privacy-canary-after"}""")
            response.status shouldBe 500
            response.contentAsString shouldNotContain "privacy-canary"
            output.all shouldNotContain "privacy-canary"
            output.all shouldNotContain "Failing row contains"
            output.all shouldContain "c2_privacy_probe"
        } finally {
            jdbc.execute("ALTER TABLE entries DROP CONSTRAINT c2_privacy_probe")
        }
    }

    @Test
    fun `the first read after pairing reconciles an elapsed joining day`() {
        val created = createBond(cara)
        val pendingId = bondIdOf(created)
        submit(cara, pendingId, """{"text":"joining words"}""").status shouldBe 201
        accept(eve, codeOf(created)).status shouldBe 200
        // Simulate two C1 submissions persisted before C2 was deployed.
        submit(eve, pendingId, """{"text":"partner joining words"}""").status shouldBe 201
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", pendingId)
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", pendingId)
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events")
        clock.set(NOW.plusSeconds(86400))
        mockMvc
            .get("/api/v1/bonds/$pendingId/today") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(cara).token}")
            }.andReturn()
            .response.status shouldBe 200
        jdbc.queryForObject("SELECT status FROM bond_days WHERE bond_id = ?::uuid", String::class.java, pendingId) shouldBe "REVEALED"
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND revealed_at IS NOT NULL",
            Int::class.java,
            pendingId,
        ) shouldBe
            2
        jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'DayRevealed'", Int::class.java) shouldBe 1
    }

    @Test
    fun `a keyed patch replays the current tombstone and never re-applies the edit`() {
        val id = entryId(submit(ada, bondId, """{"text":"original"}"""))
        val key = UUID.randomUUID().toString()

        fun edit() =
            mockMvc
                .patch("/api/v1/entries/$id") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(ada).token}")
                    header(IdempotencyInterceptor.HEADER, key)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"text":"changed"}"""
                }.andReturn()
                .response
        edit().status shouldBe 200
        deleteEntry(ada, id).status shouldBe 204
        val replay = edit()
        replay.status shouldBe 200
        replay.getHeader(IdempotencyInterceptor.REPLAYED_HEADER) shouldBe "true"
        replay.contentAsString shouldContain "\"status\":\"DELETED\""
        replay.contentAsString shouldNotContain "changed"
    }

    /** The ways a bond stops taking writes. Bea is the one who acts in each. */
    private enum class Ending { LEAVE, BLOCK_KEEPING_ENTRIES, BLOCK_WITHDRAWING, DELETION_COUNTDOWN }

    private fun end(ending: Ending) {
        when (ending) {
            Ending.LEAVE -> {
                leave(bea).status shouldBe 204
            }

            Ending.BLOCK_KEEPING_ENTRIES -> {
                block(bea, """{"withdrawEntries":false}""").status shouldBe 204
            }

            // The default: Bea's entries are withdrawn and, with no poller
            // in this context, not yet erased. Her own delete erases them now.
            Ending.BLOCK_WITHDRAWING -> {
                block(bea, null).status shouldBe 204
                jdbc.queryForObject("SELECT count(*) FROM bond_entry_withdrawals", Int::class.java) shouldBe 1
            }

            Ending.DELETION_COUNTDOWN -> {
                listOf(ada, bea).forEach { member ->
                    mockMvc
                        .post("/api/v1/bonds/$bondId/deletion-request") {
                            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(member).token}")
                        }.andReturn()
                        .response.status shouldBe 202
                }
                jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, bondId) shouldBe "PENDING_DELETION"
            }
        }
    }

    private fun block(
        userId: UUID,
        body: String?,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/block") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
            }.andReturn()
            .response

    /** The entry's row, as stored. The text is asked for only so it can be seen to be gone. */
    private fun rowOf(id: String): Map<String, Any?> =
        jdbc.queryForMap("SELECT text, status, deleted_at, revealed_at, updated_at FROM entries WHERE id = ?::uuid", id)

    private fun dayOf(entryId: String): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT d.status, d.entry_count, d.closed_at, d.revealed_at, d.version FROM bond_days d " +
                "JOIN entries e ON e.bond_day_id = d.id WHERE e.id = ?::uuid",
            entryId,
        )

    private fun entryId(response: MockHttpServletResponse): String = bondIdOf(response)

    private fun patchEntry(
        user: UUID,
        id: String,
        body: String,
        key: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                if (key != null) header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun deleteEntry(
        user: UUID,
        id: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$id") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
            }.andReturn()
            .response

    private fun today(user: UUID): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
            }.andReturn()
            .response

    private fun submit(
        caller: UUID,
        bondId: String,
        body: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(caller).token}")
                header(IdempotencyInterceptor.HEADER, idempotencyKey)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun leave(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}") }
            .andReturn()
            .response

    /**
     * Everything of a response a caller could tell two refusals apart by:
     * the body and every header. Two things are theirs to differ in and are
     * taken out — `instance`, the path the caller typed (the lesson from
     * #35's 404 body), and whatever names this one request.
     */
    private fun comparable(response: MockHttpServletResponse): String {
        val body = response.contentAsString.replace(Regex("\"instance\":\"[^\"]*\""), "\"instance\":\"-\"")
        val headers =
            response.headerNames
                .filterNot { it.equals("X-Request-Id", ignoreCase = true) || it.startsWith("X-RateLimit", ignoreCase = true) }
                .sorted()
                .joinToString { "$it=${response.getHeaders(it)}" }
        return "$headers|$body"
    }

    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(start = NOW)
    }

    private companion object {
        /** Midday UTC on the 15th is midday-plus-one in Africa/Lagos — nowhere near a midnight boundary either side. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** Two days before [NOW] — see [setUp]. */
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")

        /** The 15th has ended in Lagos (23:00Z) by more than the closer's margin. */
        val DAY_AFTER: Instant = Instant.parse("2026-09-16T10:00:00Z")
    }
}
