package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.security.PersonalDataHasher
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * Conditional reads of the archive (spec §6.6): `ETag`, `If-None-Match` and
 * `Cache-Control` on `GET /bonds/{bondId}/days` and `GET
 * /bonds/{bondId}/days/{date}`.
 *
 * **Every rule is asked of both routes**, through [reads]: the two are one
 * mechanism and a rule held for one and not the other is how they would come
 * apart.
 *
 * The tag is checked against a digest **this test computes from the bytes
 * it received** and the application's secret, not against whatever the
 * server says twice: a tag made from anything less than the body would agree
 * with itself and not with that. And it is checked **not** to be the plain
 * SHA-256 of those bytes, which a reader of a log could test a guess against.
 *
 * The words are markers no UUID, date or hexadecimal digest can contain
 * (`zq…`); a tag is searched for them too.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class DaysConditionalTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val hasher: PersonalDataHasher,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var eve: UUID

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        eve = users.verified("Eve")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    // ---- what a 200 carries ----

    @Test
    fun `a read carries a strong quoted ETag that is a keyed digest of the body's bytes, and private no-cache alone`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val response = read(ada, emptyMap())

                response.status shouldBe 200
                val tag = response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
                // Quoted, not weak, and sixty-four hexadecimal digits: nothing a word could be found in.
                tag shouldMatch Regex("\"[0-9a-f]{64}\"")
                tag shouldBe keyedTagOf(response.contentAsByteArray)
                // Not the bare hash, which anyone who saw the header could check a guess at the words against.
                tag shouldNotBe "\"${sha256(response.contentAsByteArray)}\""
                response.getHeaders(HttpHeaders.ETAG).size shouldBe 1
                // One Cache-Control, and none of the three headers that would say "never store this".
                response.getHeaders(HttpHeaders.CACHE_CONTROL) shouldBe listOf("private, no-cache")
                response.getHeader(HttpHeaders.PRAGMA).shouldBeNull()
                response.getHeader(HttpHeaders.EXPIRES).shouldBeNull()
                response.getHeaderNames() shouldNotContain HttpHeaders.VARY
                response.contentType shouldBe "application/json"
                response.getHeader(HttpHeaders.CONTENT_LENGTH) shouldBe response.contentAsByteArray.size.toString()
                // The body is the entries' words; the tag is not a place they can be read from.
                response.contentAsString shouldContain "zq-ada"
                tag shouldNotContain "zq"
            }
        }
    }

    @Test
    fun `two reads of the same thing carry the same tag, and so does a read after the clock has moved`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        bothWrite(bond, DAY_ONE.plusDays(1))

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val first = read(ada, emptyMap())
                val second = read(ada, emptyMap())
                clock.set(clock.instant().plusSeconds(3_600))
                val later = read(ada, emptyMap())

                second.contentAsString shouldBe first.contentAsString
                tagOf(second) shouldBe tagOf(first)
                tagOf(later) shouldBe tagOf(first)
            }
        }
    }

    // ---- If-None-Match ----

    @Test
    fun `If-None-Match with the current tag is 304 with no body, the same ETag and the same Cache-Control`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val tag = tagOf(read(ada, emptyMap()))

                val response = read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to tag))

                response.status shouldBe 304
                response.contentAsByteArray.size shouldBe 0
                response.getHeaders(HttpHeaders.ETAG) shouldBe listOf(tag)
                response.getHeaders(HttpHeaders.CACHE_CONTROL) shouldBe listOf("private, no-cache")
                response.getHeader(HttpHeaders.PRAGMA).shouldBeNull()
                response.getHeader(HttpHeaders.EXPIRES).shouldBeNull()
            }
        }
    }

    @Test
    fun `If-None-Match is compared weakly and over a list, as the bond's own conditional read compares it`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        reads(bond).forEach { (route, read) ->
            val tag = tagOf(read(ada, emptyMap()))
            val other = "\"${"0".repeat(64)}\""
            listOf("W/$tag", "$other, $tag", "$other, W/$tag, \"zqw\"").forEach { sent ->
                withClue("$route: $sent") {
                    val response = read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to sent))
                    response.status shouldBe 304
                    response.getHeader(HttpHeaders.ETAG) shouldBe tag
                    response.contentAsByteArray.size shouldBe 0
                }
            }
        }
    }

    /**
     * The one place this API's conditional read is not RFC 9110's: `*` may
     * be answered `304` on a read, and Spring MVC, which makes the
     * comparison for this route and for `GET /bonds/{bondId}`, answers it in
     * full. Held here so that the two routes cannot come to differ, and so
     * that a framework that starts to honour it is noticed.
     */
    @Test
    fun `If-None-Match star is answered in full on a read, here as on the bond`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        val star = mapOf(HttpHeaders.IF_NONE_MATCH to "*")

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val plain = read(ada, emptyMap())
                val response = read(ada, star)
                response.status shouldBe 200
                response.contentAsString shouldBe plain.contentAsString
                response.getHeader(HttpHeaders.ETAG) shouldBe tagOf(plain)
            }
        }
        val bondsOwn =
            mockMvc
                .get("/api/v1/bonds/$bond") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(ada).token}")
                    header(HttpHeaders.IF_NONE_MATCH, "*")
                }.andReturn()
                .response
        bondsOwn.status shouldBe 200
    }

    @Test
    fun `If-None-Match that names another tag, or cannot be read as one, is ignored - the whole 200`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        reads(bond).forEach { (route, read) ->
            val plain = read(ada, emptyMap())
            val tag = tagOf(plain)
            val bare = tag.trim('"')
            listOf(
                "\"${"0".repeat(64)}\"",
                // The digest without its quotes, half of it, an empty value, nonsense, and the tag with something after it.
                bare,
                "\"${bare.take(32)}\"",
                "",
                "zqw-not-a-tag",
                "\"$bare-gzip\"",
                "W/",
                "\"",
            ).forEach { sent ->
                withClue("$route: [$sent]") {
                    val response = read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to sent))
                    response.status shouldBe 200
                    response.contentAsString shouldBe plain.contentAsString
                    response.getHeader(HttpHeaders.ETAG) shouldBe tag
                }
            }
        }
    }

    @Test
    fun `If-Match is not a condition of a read - a stale one changes nothing`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                read(ada, mapOf(HttpHeaders.IF_MATCH to "\"${"0".repeat(64)}\"")).status shouldBe 200
            }
        }
    }

    // ---- what changes the tag, and what must not ----

    @Test
    fun `the tag changes when the caller marks a favourite and comes back when they unmark it`() {
        val bond = pair()
        val day = bothWrite(bond, DAY_ONE)

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val before = tagOf(read(ada, emptyMap()))

                rig.favourite(ada, day.beas)
                val marked = read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to before))

                // The stale tag is answered with the whole body and the new tag.
                marked.status shouldBe 200
                marked.contentAsString shouldContain "\"favourited\":true"
                tagOf(marked) shouldNotBe before
                tagOf(marked) shouldBe keyedTagOf(marked.contentAsByteArray)
                read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to tagOf(marked))).status shouldBe 304

                rig.unfavourite(ada, day.beas)
                tagOf(read(ada, emptyMap())) shouldBe before
            }
        }
    }

    @Test
    fun `the partner marking and unmarking changes no byte and no tag of the other member's reads`() {
        val bond = pair()
        val day = bothWrite(bond, DAY_ONE)
        rig.favourite(ada, day.beas)

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val before = read(ada, emptyMap())
                val tag = tagOf(before)

                rig.favourite(bea, day.adas)
                rig.favourite(bea, day.beas)
                marksOf(bond) shouldBe 3

                val marked = read(ada, emptyMap())
                marked.contentAsString shouldBe before.contentAsString
                tagOf(marked) shouldBe tag
                read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to tag)).status shouldBe 304

                rig.unfavourite(bea, day.adas)
                rig.unfavourite(bea, day.beas)
                marksOf(bond) shouldBe 1
                tagOf(read(ada, emptyMap())) shouldBe tag
                read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to tag)).status shouldBe 304
            }
        }
    }

    @Test
    fun `the tag changes when the day reveals`() {
        val bond = pair()
        rig.bonds.submit(ada, bond, words("ada", DAY_ONE))
        val before = reads(bond).associate { (route, read) -> route to tagOf(read(ada, emptyMap())) }

        rig.bonds.submit(bea, bond, words("bea", DAY_ONE))

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val revealed = read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to before.getValue(route)))
                revealed.status shouldBe 200
                revealed.contentAsString shouldContain "zq-bea"
                tagOf(revealed) shouldNotBe before.getValue(route)
            }
        }
    }

    @Test
    fun `the tag changes when an entry is erased, the caller's own or the partner's`() {
        val bond = pair()
        val day = bothWrite(bond, DAY_ONE)
        val whole = reads(bond).associate { (route, read) -> route to tagOf(read(ada, emptyMap())) }

        rig.bonds.delete(bea, day.beas)

        val afterHis =
            reads(bond).associate { (route, read) ->
                val response = read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to whole.getValue(route)))
                withClue(route) {
                    response.status shouldBe 200
                    response.contentAsString shouldNotContain "zq-bea"
                    tagOf(response) shouldNotBe whole.getValue(route)
                }
                route to tagOf(response)
            }

        rig.bonds.delete(ada, day.adas)

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val response = read(ada, mapOf(HttpHeaders.IF_NONE_MATCH to afterHis.getValue(route)))
                response.status shouldBe 200
                response.contentAsString shouldNotContain "zq-ada"
                tagOf(response) shouldNotBe afterHis.getValue(route)
            }
        }
    }

    @Test
    fun `the tag changes when the partner withdraws, before anything has erased a row`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        val whole = reads(bond).associate { (route, read) -> route to tagOf(read(bea, emptyMap())) }

        // A block withdraws. No dispatcher runs in this module's tests: the rows still hold their words.
        rig.bonds.block(ada, bond)

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val response = read(bea, mapOf(HttpHeaders.IF_NONE_MATCH to whole.getValue(route)))
                // A 304 here would tell a client to go on showing words that have been taken back.
                response.status shouldBe 200
                response.contentAsString shouldNotContain "zq-ada"
                tagOf(response) shouldNotBe whole.getValue(route)
            }
        }
    }

    @Test
    fun `the two members' tags differ for the same revealed day, and neither's is a 304 for the other`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        reads(bond).forEach { (route, read) ->
            withClue(route) {
                val hers = read(ada, emptyMap())
                val his = read(bea, emptyMap())

                tagOf(hers) shouldNotBe tagOf(his)
                val crossed = read(bea, mapOf(HttpHeaders.IF_NONE_MATCH to tagOf(hers)))
                crossed.status shouldBe 200
                crossed.contentAsString shouldBe his.contentAsString
            }
        }
    }

    @Test
    fun `the feed's tag covers the page - a cursor that appears changes it though no day of the page did`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE.plusDays(1))
        val one = mapOf("limit" to "1")
        val before = rig.days(ada, bond, one)
        json.readTree(before.contentAsString)["nextCursor"].isNull shouldBe true

        // An older day, put down by hand: the page's one day is untouched and there is now something after it.
        val older = rig.insertDay(bond, DAY_ONE, "REVEALED")
        rig.insertEntry(older, bond, rig.bonds.memberId(bond, ada), EntryState.REVEALED, words("ada", DAY_ONE), at(DAY_ONE))

        val after = rig.days(ada, bond, one, mapOf(HttpHeaders.IF_NONE_MATCH to tagOf(before)))
        after.status shouldBe 200
        json.readTree(after.contentAsString).let { page ->
            page["items"] shouldBe json.readTree(before.contentAsString)["items"]
            page["nextCursor"].isNull shouldBe false
        }
        tagOf(after) shouldNotBe tagOf(before)
        // And each page of a walk is its own representation.
        val next = rig.days(ada, bond, one + ("cursor" to json.readTree(after.contentAsString)["nextCursor"].asString()))
        tagOf(next) shouldNotBe tagOf(after)
        rig.days(ada, bond, one, mapOf(HttpHeaders.IF_NONE_MATCH to tagOf(next))).status shouldBe 200
    }

    // ---- a condition is not a way past anything ----

    @Test
    fun `a refusal carries no ETag and is no 304, whatever If-None-Match says`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        // The partner's unrevealed entry, on the day after: the 404 that must stay a 404.
        clock.set(at(DAY_ONE.plusDays(1)))
        rig.bonds.submit(bea, bond, words("bea", DAY_ONE.plusDays(1)))
        val feedTag = tagOf(rig.days(ada, bond))
        val dayTag = tagOf(rig.day(ada, bond, "2026-09-15"))
        val conditions = listOf("*", feedTag, dayTag, "W/$dayTag").map { mapOf(HttpHeaders.IF_NONE_MATCH to it) }

        conditions.forEach { condition ->
            withClue(condition.toString()) {
                val refusals =
                    listOf(
                        // A member, a date not in her archive.
                        rig.day(ada, bond, "2026-09-16", condition) to "DAY_NOT_FOUND",
                        rig.day(ada, bond, "2026-09-14", condition) to "DAY_NOT_FOUND",
                        rig.day(ada, bond, "zqwyesterday", condition) to "DAY_NOT_FOUND",
                        // A stranger, on a day and a feed that exist; and bonds that do not.
                        rig.day(eve, bond, "2026-09-15", condition) to "NOT_FOUND",
                        rig.days(eve, bond, emptyMap(), condition) to "NOT_FOUND",
                        rig.day(ada, UUID.randomUUID().toString(), "2026-09-15", condition) to "NOT_FOUND",
                        rig.days(ada, "not-a-bond", emptyMap(), condition) to "NOT_FOUND",
                    )
                refusals.forEach { (response, code) ->
                    response.status shouldBe 404
                    response.contentAsString shouldContain "\"code\":\"$code\""
                    response.getHeader(HttpHeaders.ETAG).shouldBeNull()
                    response.getHeaders(HttpHeaders.CACHE_CONTROL) shouldNotContain "private, no-cache"
                }
                // A parameter that cannot be read is still a 422.
                rig.days(ada, bond, mapOf("limit" to "0"), condition).let {
                    it.status shouldBe 422
                    it.getHeader(HttpHeaders.ETAG).shouldBeNull()
                }
            }
        }
        // With or without a condition a refusal is the same response.
        whole(rig.day(ada, bond, "2026-09-16", conditions.first())) shouldBe whole(rig.day(ada, bond, "2026-09-16"))
        whole(rig.day(eve, bond, "2026-09-15", conditions.first())) shouldBe whole(rig.day(eve, bond, "2026-09-15"))
    }

    @Test
    fun `a member who has been given a 304 gets the 404 once the day is no longer theirs to see`() {
        val bond = pair()
        // Hers alone, then gone from every archive: a tag once good for it is good for nothing.
        val adas = rig.bonds.submit(ada, bond, words("ada", DAY_ONE))
        val tag = tagOf(rig.day(ada, bond, "2026-09-15"))
        rig.day(ada, bond, "2026-09-15", mapOf(HttpHeaders.IF_NONE_MATCH to tag)).status shouldBe 304
        jdbc.update("DELETE FROM entries WHERE id = ?::uuid", adas) shouldBe 1

        val response = rig.day(ada, bond, "2026-09-15", mapOf(HttpHeaders.IF_NONE_MATCH to tag))

        response.status shouldBe 404
        response.contentAsString shouldContain "\"code\":\"DAY_NOT_FOUND\""
    }

    // ---- helpers ----

    /** Both archive routes, each as a way to ask it as a user with some headers: the feed's first page, and [date]. */
    private fun reads(
        bond: String,
        date: String = "2026-09-15",
    ): List<Pair<String, (UUID, Map<String, String>) -> MockHttpServletResponse>> =
        listOf(
            "the feed" to { user, headers -> rig.days(user, bond, emptyMap(), headers) },
            "the day" to { user, headers -> rig.day(user, bond, date, headers) },
        )

    private fun tagOf(response: MockHttpServletResponse): String {
        response.status shouldBe 200
        return response.getHeader(HttpHeaders.ETAG).shouldNotBeNull()
    }

    /**
     * What the tag of [body] must be, worked out here: the HMAC-SHA256 of a
     * label and the bytes received, under the application's personal-data
     * secret, in hexadecimal and quoted.
     */
    private fun keyedTagOf(body: ByteArray): String = "\"${hasher.mac("moyi-etag-v1\n".toByteArray() + body).toHexString()}\""

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun marksOf(bond: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_favourites f JOIN entries e ON e.id = f.entry_id WHERE e.bond_id = ?::uuid",
            Int::class.java,
            bond,
        )!!

    /** Status, every header and the body, with only `instance` (the path that was asked for) taken out. */
    private fun whole(response: MockHttpServletResponse): Triple<Int, Map<String, List<String>>, String> =
        Triple(
            response.status,
            response.headerNames.sorted().associateWith { response.getHeaders(it) },
            response.contentAsString.replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\""),
        )

    private fun pair(): String {
        clock.set(BOND_CREATED)
        return rig.bonds.pair(ada, bea).also { clock.set(NOW) }
    }

    /** Both write on [date], Ada first, so the day is revealed. */
    private fun bothWrite(
        bond: String,
        date: LocalDate,
    ): Written {
        clock.set(at(date))
        return Written(rig.bonds.submit(ada, bond, words("ada", date)), rig.bonds.submit(bea, bond, words("bea", date)))
    }

    private data class Written(
        val adas: String,
        val beas: String,
    )

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)

        /** 10:00Z: late morning of [date] in Africa/Lagos. */
        fun at(date: LocalDate): Instant = Instant.parse("${date}T10:00:00Z")

        fun words(
            who: String,
            date: LocalDate,
        ): String = "zq-$who-words-of-$date"
    }
}
