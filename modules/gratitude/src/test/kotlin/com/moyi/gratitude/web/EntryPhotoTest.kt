package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.ProcessedPhotos
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * A photo on an entry (spec §4 D2): attached in the entry's own transaction by
 * `media`'s claim (FR-044a), and rendered to each reader as `media` says they
 * may see it (FR-101). The FR-044a category of doc 12:56 is here as the API
 * answers it: a cross-*user* disclosure that never crosses a bond, so the
 * cross-tenant suite cannot see it. The photos are put in place by hand, as
 * `media.process` leaves them ([ProcessedPhotos]).
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
internal class EntryPhotoTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
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
    fun `an entry with the author's ready photo is created, the photo is claimed for it, and the author sees it`() {
        val photo = photos.ready(owner = ada)

        val created = submit(ada, bondId, """{"text":"look at this","imageMediaId":"$photo"}""")

        created.status shouldBe 201
        val entry = body(created)
        photos.attachedTo(photo) shouldBe UUID.fromString(entry["id"].asString())
        val image = entry["image"]
        image["id"].asString() shouldBe photo.toString()
        image["status"].asString() shouldBe "READY"
        image["width"].asInt() shouldBe 1536
        image["height"].asInt() shouldBe 2048
        image["url"].asString() shouldStartWith "http://127.0.0.1:9/moyi-media-test/v/"
        image["url"].asString() shouldContain "X-Amz-Signature="
        body(getToday(ada, bondId))["myEntry"]["image"]["status"].asString() shouldBe "READY"
    }

    @Test
    fun `an entry with no photo says so`() {
        body(submit(ada, bondId, """{"text":"just words"}"""))["image"].isNull shouldBe true
    }

    @Test
    fun `BR-8 - the partner's locked entry with a photo is still exactly an author and a status`() {
        val photo = photos.ready(owner = bea)
        val beaEntry = submit(bea, bondId, """{"text":"a secret kindness","imageMediaId":"$photo"}""")
        beaEntry.status shouldBe 201

        val today = getToday(ada, bondId).contentAsString

        body(today)["partnerEntry"].propertyNames().asSequence().toList() shouldBe listOf("authorMemberId", "status")
        today shouldNotContain photo.toString()
        today shouldNotContain "image"
        today shouldNotContain "X-Amz"
    }

    @Test
    fun `once revealed the partner sees the photo as processing, with no URL, until it has passed moderation (FR-101)`() {
        val photo = photos.ready(owner = bea)
        submit(bea, bondId, """{"text":"a secret kindness","imageMediaId":"$photo"}""").status shouldBe 201
        submit(ada, bondId, """{"text":"and mine"}""").status shouldBe 201

        val partners = body(getToday(ada, bondId))["partnerEntry"]["image"]
        partners.propertyNames().asSequence().toList() shouldBe listOf("id", "status", "url", "width", "height")
        partners["status"].asString() shouldBe "PROCESSING"
        listOf("url", "width", "height").forEach { partners[it].isNull shouldBe true }
        getToday(ada, bondId).contentAsString shouldNotContain "X-Amz"
        // The archive says the same.
        body(day(ada, bondId, "2026-09-15"))["partnerEntry"]["image"]["status"].asString() shouldBe "PROCESSING"
        // Its author is shown it all the while.
        body(getToday(bea, bondId))["myEntry"]["image"]["status"].asString() shouldBe "READY"

        jdbc.update("UPDATE media_assets SET moderation_verdict = 'PASSED' WHERE id = ?", photo)

        body(getToday(ada, bondId))["partnerEntry"]["image"]["status"].asString() shouldBe "READY"
    }

    @Test
    fun `FR-044a - another user's photo is refused on imageMediaId, and no entry is created`() {
        val beas = photos.ready(owner = bea)

        val refused = submit(ada, bondId, """{"text":"not mine","imageMediaId":"$beas"}""")

        refused.status shouldBe 422
        refused.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        refused.contentAsString shouldContain "\"field\":\"imageMediaId\""
        photos.attachedTo(beas).shouldBeNull()
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 0
    }

    @Test
    fun `FR-044a - a v7 id, a photo not ready, and a voice note named as a photo are each refused`() {
        for (image in listOf(V7_ID, photos.ready(owner = ada, status = "PROCESSING"), photos.ready(owner = ada, kind = "VOICE"))) {
            submit(ada, bondId, """{"text":"look","imageMediaId":"$image"}""").status shouldBe 422
        }
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 0
    }

    @Test
    fun `FR-044a - a photo on one entry is refused for a second, in another bond`() {
        val cara = users.verified("Cara")
        val second = pair(ada, cara)
        val photo = photos.ready(owner = ada)
        val first = body(submit(ada, bondId, """{"text":"here","imageMediaId":"$photo"}"""))

        submit(ada, second, """{"text":"and here","imageMediaId":"$photo"}""").status shouldBe 422

        photos.attachedTo(photo) shouldBe UUID.fromString(first["id"].asString())
    }

    @Test
    fun `a refused photo leaves the Idempotency-Key free for the corrected request`() {
        val key = UUID.randomUUID().toString()
        submit(ada, bondId, """{"text":"look","imageMediaId":"${photos.ready(owner = bea)}"}""", key).status shouldBe 422

        val mine = photos.ready(owner = ada)
        submit(ada, bondId, """{"text":"look","imageMediaId":"$mine"}""", key).status shouldBe 201
    }

    @Test
    fun `PATCH attaches a photo before the reveal, and a second one lets the first go`() {
        val entry = body(submit(ada, bondId, """{"text":"words"}"""))["id"].asString()
        val first = photos.ready(owner = ada)
        val second = photos.ready(owner = ada)

        val withFirst = patch(ada, entry, """{"text":"words","imageMediaId":"$first"}""")
        withFirst.status shouldBe 200
        body(withFirst)["image"]["id"].asString() shouldBe first.toString()
        photos.attachedTo(first) shouldBe UUID.fromString(entry)

        // Absent, the photo is left as it is.
        body(patch(ada, entry, """{"text":"other words"}"""))["image"]["id"].asString() shouldBe first.toString()

        body(patch(ada, entry, """{"text":"other words","imageMediaId":"$second"}"""))["image"]["id"].asString() shouldBe second.toString()
        photos.attachedTo(second) shouldBe UUID.fromString(entry)
        photos.attachedTo(first).shouldBeNull()
        // Let go, not erased: still its owner's, and the reaper's day for it counts from now (D5).
        jdbc.queryForMap("SELECT status, detached_at FROM media_assets WHERE id = ?", first) shouldBe
            mapOf("status" to "READY", "detached_at" to java.sql.Timestamp.from(clock.instant()))
    }

    @Test
    fun `PATCH of a revealed entry is immutable, and the photo it named is not claimed`() {
        val entry = body(submit(ada, bondId, """{"text":"words"}"""))["id"].asString()
        submit(bea, bondId, """{"text":"hers"}""").status shouldBe 201
        val photo = photos.ready(owner = ada)

        patch(ada, entry, """{"text":"words","imageMediaId":"$photo"}""").status shouldBe 409

        photos.attachedTo(photo).shouldBeNull()
    }

    @Test
    fun `PATCH with another user's photo is refused on imageMediaId, and the entry is unchanged`() {
        val entry = body(submit(ada, bondId, """{"text":"words"}"""))["id"].asString()

        val refused = patch(ada, entry, """{"text":"changed","imageMediaId":"${photos.ready(owner = bea)}"}""")

        refused.status shouldBe 422
        refused.contentAsString shouldContain "\"field\":\"imageMediaId\""
        jdbc.queryForObject("SELECT image_media_id FROM entries WHERE id = ?::uuid", UUID::class.java, entry).shouldBeNull()
    }

    @Test
    fun `ADR-0049 - an unverified member's photo or voice note is refused 403, and no entry is created`() {
        val photo = photos.ready(owner = ada)
        val voice = photos.ready(owner = ada, kind = "VOICE")
        users.unverify(ada)

        for (media in listOf(""""imageMediaId":"$photo"""", """"voiceMediaId":"$voice"""")) {
            val refused = submit(ada, bondId, """{"text":"look",$media}""")
            refused.status shouldBe 403
            refused.contentAsString shouldContain "\"code\":\"EMAIL_NOT_VERIFIED\""
        }
        photos.attachedTo(photo).shouldBeNull()
        photos.attachedTo(voice).shouldBeNull()
        jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 0
    }

    @Test
    fun `ADR-0049 - PATCH with an unverified member's photo is refused 403, and the entry is unchanged`() {
        val entry = body(submit(ada, bondId, """{"text":"words"}"""))["id"].asString()
        val photo = photos.ready(owner = ada)
        users.unverify(ada)

        val refused = patch(ada, entry, """{"text":"changed","imageMediaId":"$photo"}""")

        refused.status shouldBe 403
        refused.contentAsString shouldContain "\"code\":\"EMAIL_NOT_VERIFIED\""
        photos.attachedTo(photo).shouldBeNull()
        jdbc.queryForObject("SELECT image_media_id FROM entries WHERE id = ?::uuid", UUID::class.java, entry).shouldBeNull()
    }

    @Test
    fun `ADR-0049 - only media is gated - an unverified member's text-only entry is created and changed`() {
        users.unverify(ada)

        val created = submit(ada, bondId, """{"text":"just words"}""")
        created.status shouldBe 201
        patch(ada, body(created)["id"].asString(), """{"text":"other words"}""").status shouldBe 200
    }

    @Test
    fun `a deleted entry shows no photo`() {
        val entry = body(submit(ada, bondId, """{"text":"words","imageMediaId":"${photos.ready(owner = ada)}"}"""))["id"].asString()

        delete(ada, entry).status shouldBe 204

        body(getToday(ada, bondId))["myEntry"]["image"].isNull shouldBe true
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

    private fun body(response: MockHttpServletResponse): JsonNode = body(response.contentAsString)

    private fun body(text: String): JsonNode = json.readTree(text)

    private companion object {
        /** 11:00 in Africa/Lagos on the 15th: nowhere near a day boundary. */
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")

        /** A v7 id, the kind a bond or a user carries: no photo is ever one (doc 06:14). */
        val V7_ID: UUID = UUID.fromString("019a2b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b")
    }
}
