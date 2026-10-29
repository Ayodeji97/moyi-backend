package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.ProcessedPhotos
import com.moyi.gratitude.infra.ProcessedVoices
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A voice note on an entry (spec §4 D3): attached in the entry's own
 * transaction by `media`'s claim (FR-044a), as a photo is, and played to both
 * members once it is ready, since voice is not moderated (ADR-0049). The
 * FR-044a category of doc 12:56 is here for voice notes as the API answers it.
 * The voice notes are put in place by hand, as `media.process` leaves them
 * ([ProcessedVoices]).
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
internal class EntryVoiceTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val voices = ProcessedVoices(jdbc)
    private val photos = ProcessedPhotos(jdbc)
    private val json = JsonMapper.builder().build()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bondId: String

    @BeforeEach
    fun setUp() {
        clock.set(NOW.minusSeconds(86_400))
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        bondId = pair(ada, bea)
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, media_assets")
        jdbc.execute("TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `an entry with the author's ready voice note is created, the voice note is claimed for it, and the author can play it`() {
        val voice = voices.ready(owner = ada)

        val created = submit(ada, bondId, """{"text":"listen to this","voiceMediaId":"$voice"}""")

        created.status shouldBe 201
        val entry = body(created)
        voices.attachedTo(voice) shouldBe UUID.fromString(entry["id"].asString())
        val played = entry["voice"]
        played.propertyNames().asSequence().toList() shouldBe listOf("id", "status", "url", "durationMs")
        played["id"].asString() shouldBe voice.toString()
        played["status"].asString() shouldBe "READY"
        played["durationMs"].asInt() shouldBe ProcessedVoices.DURATION_MS
        played["url"].asString() shouldStartWith "http://127.0.0.1:9/moyi-media-test/v/"
        played["url"].asString().substringBefore("?") shouldEndWith "/voice.ogg"
        played["url"].asString() shouldContain "X-Amz-Signature="
        // Spec D-10: the length is the asset's, never written on the entry.
        jdbc.queryForObject("SELECT voice_duration_ms FROM entries", Int::class.java).shouldBeNull()
        body(getToday(ada, bondId))["myEntry"]["voice"]["status"].asString() shouldBe "READY"
    }

    @Test
    fun `an entry with no voice note says so`() {
        body(submit(ada, bondId, """{"text":"just words"}"""))["voice"].isNull shouldBe true
    }

    @Test
    fun `an entry can carry a photo and a voice note at once, each claimed`() {
        val photo = photos.ready(owner = ada)
        val voice = voices.ready(owner = ada)

        val entry = body(submit(ada, bondId, """{"text":"both","imageMediaId":"$photo","voiceMediaId":"$voice"}"""))

        entry["image"]["id"].asString() shouldBe photo.toString()
        entry["voice"]["id"].asString() shouldBe voice.toString()
        photos.attachedTo(photo) shouldBe UUID.fromString(entry["id"].asString())
        voices.attachedTo(voice) shouldBe UUID.fromString(entry["id"].asString())
    }

    @Test
    fun `once revealed the partner can play the voice note too, with no moderation to wait on`() {
        val voice = voices.ready(owner = bea)
        submit(bea, bondId, """{"text":"a secret kindness","voiceMediaId":"$voice"}""").status shouldBe 201
        submit(ada, bondId, """{"text":"and mine"}""").status shouldBe 201

        val partners = body(getToday(ada, bondId))["partnerEntry"]["voice"]
        partners["status"].asString() shouldBe "READY"
        partners["durationMs"].asInt() shouldBe ProcessedVoices.DURATION_MS
        partners["url"].asString() shouldContain "X-Amz-Signature="
        // The archive says the same, with the same URL all hour.
        body(day(ada, bondId, "2026-09-15"))["partnerEntry"]["voice"]["url"].asString() shouldBe partners["url"].asString()
    }

    @Test
    fun `a voice note not yet transcoded is processing, with no URL and no length, to its author and to the partner`() {
        val voice = voices.ready(owner = bea, status = "PROCESSING")
        // Attached as it would have been once ready: the entry is the subject here, not the claim.
        submit(bea, bondId, """{"text":"soon"}""").status shouldBe 201
        jdbc.update("UPDATE entries SET voice_media_id = ?", voice)
        submit(ada, bondId, """{"text":"and mine"}""").status shouldBe 201

        for ((reader, field) in listOf(bea to "myEntry", ada to "partnerEntry")) {
            val shown = body(getToday(reader, bondId))[field]["voice"]
            shown["status"].asString() shouldBe "PROCESSING"
            shown["url"].isNull shouldBe true
            shown["durationMs"].isNull shouldBe true
        }
    }

    @Test
    fun `FR-044a - another user's voice note is refused on voiceMediaId, and no entry is created`() {
        val beas = voices.ready(owner = bea)

        val refused = submit(ada, bondId, """{"text":"not mine","voiceMediaId":"$beas"}""")

        refused.status shouldBe 422
        refused.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        refused.contentAsString shouldContain "\"field\":\"voiceMediaId\""
        refused.contentAsString shouldNotContain beas.toString()
        voices.attachedTo(beas).shouldBeNull()
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 0
    }

    @Test
    fun `FR-044a - a v7 id, a voice note not ready, and a photo named as a voice note are each refused`() {
        for (voice in listOf(V7_ID, voices.ready(owner = ada, status = "PROCESSING"), photos.ready(owner = ada))) {
            val refused = submit(ada, bondId, """{"text":"listen","voiceMediaId":"$voice"}""")
            refused.status shouldBe 422
            refused.contentAsString shouldContain "\"field\":\"voiceMediaId\""
        }
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 0
    }

    @Test
    fun `FR-044a - a voice note on one entry is refused for a second, in another bond`() {
        val cara = users.verified("Cara")
        val second = pair(ada, cara)
        val voice = voices.ready(owner = ada)
        val first = body(submit(ada, bondId, """{"text":"here","voiceMediaId":"$voice"}"""))

        submit(ada, second, """{"text":"and here","voiceMediaId":"$voice"}""").status shouldBe 422

        voices.attachedTo(voice) shouldBe UUID.fromString(first["id"].asString())
    }

    @Test
    fun `a refused voice note rolls back the photo claimed before it`() {
        val photo = photos.ready(owner = ada)

        submit(ada, bondId, """{"text":"both","imageMediaId":"$photo","voiceMediaId":"${voices.ready(owner = bea)}"}""").status shouldBe 422

        photos.attachedTo(photo).shouldBeNull()
    }

    @Test
    fun `PATCH attaches a voice note before the reveal, and a second one lets the first go`() {
        val entry = body(submit(ada, bondId, """{"text":"words"}"""))["id"].asString()
        val first = voices.ready(owner = ada)
        val second = voices.ready(owner = ada)

        val withFirst = patch(ada, entry, """{"text":"words","voiceMediaId":"$first"}""")
        withFirst.status shouldBe 200
        body(withFirst)["voice"]["id"].asString() shouldBe first.toString()
        voices.attachedTo(first) shouldBe UUID.fromString(entry)

        // Absent, the voice note is left as it is.
        body(patch(ada, entry, """{"text":"other words"}"""))["voice"]["id"].asString() shouldBe first.toString()

        body(patch(ada, entry, """{"text":"other words","voiceMediaId":"$second"}"""))["voice"]["id"].asString() shouldBe second.toString()
        voices.attachedTo(second) shouldBe UUID.fromString(entry)
        voices.attachedTo(first).shouldBeNull()
    }

    @Test
    fun `PATCH of a revealed entry is immutable, and the voice note it named is not claimed`() {
        val entry = body(submit(ada, bondId, """{"text":"words"}"""))["id"].asString()
        submit(bea, bondId, """{"text":"hers"}""").status shouldBe 201
        val voice = voices.ready(owner = ada)

        patch(ada, entry, """{"text":"words","voiceMediaId":"$voice"}""").status shouldBe 409

        voices.attachedTo(voice).shouldBeNull()
    }

    @Test
    fun `PATCH with another user's voice note is refused on voiceMediaId, and the entry is unchanged`() {
        val entry = body(submit(ada, bondId, """{"text":"words"}"""))["id"].asString()

        val refused = patch(ada, entry, """{"text":"changed","voiceMediaId":"${voices.ready(owner = bea)}"}""")

        refused.status shouldBe 422
        refused.contentAsString shouldContain "\"field\":\"voiceMediaId\""
        jdbc.queryForObject("SELECT voice_media_id FROM entries WHERE id = ?::uuid", UUID::class.java, entry).shouldBeNull()
    }

    @Test
    fun `a deleted entry shows no voice note, and its row no longer names one`() {
        val entry = body(submit(ada, bondId, """{"text":"words","voiceMediaId":"${voices.ready(owner = ada)}"}"""))["id"].asString()

        delete(ada, entry).status shouldBe 204

        body(getToday(ada, bondId))["myEntry"]["voice"].isNull shouldBe true
        jdbc.queryForObject("SELECT voice_media_id FROM entries WHERE id = ?::uuid", UUID::class.java, entry).shouldBeNull()
    }

    private fun pair(
        creator: UUID,
        joiner: UUID,
    ): String {
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(creator))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        val code = Regex(""""code":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(joiner)) }
            .andReturn()
            .response.status shouldBe 200
        return Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
    }

    private fun submit(
        caller: UUID,
        bondId: String,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds/$bondId/entries") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                header(IdempotencyInterceptor.HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun patch(
        caller: UUID,
        entryId: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/entries/$entryId") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun delete(
        caller: UUID,
        entryId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/entries/$entryId") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
            }.andReturn()
            .response

    private fun getToday(
        caller: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/today") {
                header(HttpHeaders.AUTHORIZATION, bearer(caller))
            }.andReturn()
            .response

    private fun day(
        caller: UUID,
        bondId: String,
        date: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId/days/$date") { header(HttpHeaders.AUTHORIZATION, bearer(caller)) }.andReturn().response

    private fun bearer(user: UUID) = "Bearer ${tokens.issue(user).token}"

    private fun body(response: MockHttpServletResponse): JsonNode = json.readTree(response.contentAsString)

    private companion object {
        /** 11:00 in Africa/Lagos on the 15th: nowhere near a day boundary. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** A v7 id, the kind a bond or a user carries: no voice note is ever one (doc 06:14). */
        val V7_ID: UUID = UUID.fromString("019a2b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b")
    }
}
