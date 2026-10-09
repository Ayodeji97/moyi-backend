package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.domain.BondDayId
import com.moyi.gratitude.domain.Readability
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.service.CloseDay
import com.moyi.gratitude.service.GetDays
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `GET /bonds/{bondId}/days` (FR-090): the archive, a page at a time.
 *
 * What is listed and what each entry looks like, over every state a day can
 * be in, is `ArchiveGateTest`'s. This is the feed as a feed: its order, its
 * pages and their two bounds (a count and a size), what it refuses, who may
 * read it, and that a withdrawal hides words here from its commit.
 *
 * Every day is written by real requests with the clock moved to that day; a
 * day both wrote on is revealed by the second entry. Days are `Africa/Lagos`
 * days, so the instant used for each is 10:00Z, late morning there.
 *
 * The words are markers that cannot occur in a UUID or a date (`zq…`), so a
 * body can be searched for them.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // What Spring hands the test; and one route's rules, kept where they can be read together.
internal class DaysFeedTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired private val dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired private val access: BondAccess,
    @Autowired private val getDays: GetDays,
    @Autowired private val closeDay: CloseDay,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ArchiveRig(mockMvc, tokens, jdbc, json)
    private val interruptible = access as MarkerReadLastTest.InterruptibleBondAccess
    private val pool = Executors.newCachedThreadPool()

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var eve: UUID

    /** Before as well as after: the close job and the dispatcher work on every bond another test class left behind. */
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
        interruptible.disarm()
        pool.shutdownNow()
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    // ---- the shape, and the order ----

    @Test
    fun `the feed is the bond's days newest first, each with the two entries as today renders them`() {
        val bond = pair()
        val first = bothWrite(bond, DAY_ONE)
        bothWrite(bond, DAY_ONE.plusDays(1))
        bothWrite(bond, DAY_ONE.plusDays(2))

        val body = rig.page(ada, bond)

        body.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("items", "nextCursor")
        body["nextCursor"].isNull shouldBe true
        rig.datesOf(body["items"].toList()) shouldBe listOf("2026-09-17", "2026-09-16", "2026-09-15")
        body["items"].forEach { day ->
            day.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("date", "status", "myEntry", "partnerEntry")
            day["status"].asString() shouldBe "REVEALED"
            day["myEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
            day["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
        }
        // The oldest day, entry for entry, and from the other side.
        val oldest = body["items"].last()
        oldest["myEntry"]["id"].asString() shouldBe first.adas
        oldest["myEntry"]["text"].asString() shouldBe words("ada", DAY_ONE)
        oldest["myEntry"]["date"].asString() shouldBe "2026-09-15"
        oldest["partnerEntry"]["id"].asString() shouldBe first.beas
        oldest["partnerEntry"]["text"].asString() shouldBe words("bea", DAY_ONE)
        val hers = rig.page(bea, bond)["items"].last()
        hers["myEntry"]["id"].asString() shouldBe first.beas
        hers["partnerEntry"]["id"].asString() shouldBe first.adas
    }

    @Test
    fun `today is listed on the same rule as any day - to the one who has written, and not yet to the one who has not`() {
        val bond = pair()
        val adas = rig.bonds.submit(ada, bond, words("ada", DAY_ONE))

        val hers = rig.page(ada, bond)["items"].toList()
        hers.size shouldBe 1
        hers.single()["status"].asString() shouldBe "PARTIAL"
        hers.single()["myEntry"]["id"].asString() shouldBe adas
        hers.single()["partnerEntry"].isNull shouldBe true
        // Listing it for him would say she wrote today in a place that is not `today`.
        raw(bea, bond) shouldBe """{"items":[],"nextCursor":null}"""
    }

    @Test
    fun `reading the feed writes nothing`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        clock.set(at(DAY_ONE.plusDays(3)))
        val before = rig.bonds.wholeDays(bond) to rig.bonds.wholeEntries(bond)

        rig.page(ada, bond)
        rig.page(bea, bond, mapOf("favourites" to "true"))

        (rig.bonds.wholeDays(bond) to rig.bonds.wholeEntries(bond)) shouldBe before
    }

    @Test
    fun `no response carries when an entry was erased or last changed, on any shape`() {
        val bond = pair()
        val day = bothWrite(bond, DAY_ONE)
        rig.bonds.delete(ada, day.adas)
        clock.set(at(DAY_ONE.plusDays(1)))
        rig.bonds.delete(ada, rig.bonds.submit(ada, bond, words("ada", DAY_ONE.plusDays(1))))

        listOf(ada, bea).forEach { reader ->
            val raw = raw(reader, bond)
            raw shouldNotContain "deletedAt"
            raw shouldNotContain "updatedAt"
            raw shouldNotContain "revealedAt"
            raw shouldNotContain "version"
        }
        // Her own tombstones, both wide; his view of the revealed one is wide too, and the unrevealed one is not listed for him.
        rig.page(ada, bond)["items"].toList().map { it["myEntry"]["status"].asString() } shouldBe listOf("DELETED", "DELETED")
        val his = rig.page(bea, bond)["items"].toList()
        rig.datesOf(his) shouldBe listOf("2026-09-15")
        his.single()["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
        his.single()["partnerEntry"]["text"].isNull shouldBe true
    }

    // ---- pages by count ----

    @Test
    fun `a page is twenty days unless asked otherwise, and the cursor walks every day once to a null`() {
        val bond = pair()
        val dates =
            (0L until 25)
                .map { DAY_ONE.plusDays(it) }
                .onEach { bothWrite(bond, it) }
                .map { it.toString() }
                .reversed()

        val first = rig.page(ada, bond)
        rig.datesOf(first["items"].toList()) shouldBe dates.take(20)
        // The cursor is the last day given, in the documented form: opaque to a client, not to this test.
        first["nextCursor"].asString() shouldBe rig.cursorBefore(dates[19])
        val second = rig.page(ada, bond, mapOf("cursor" to first["nextCursor"].asString()))
        rig.datesOf(second["items"].toList()) shouldBe dates.drop(20)
        second["nextCursor"].isNull shouldBe true

        for (limit in listOf(1, 7, 24, 25, 26, 50)) {
            withClue("limit $limit") {
                val pages = rig.pages(ada, bond, mapOf("limit" to "$limit"))
                pages.flatMap { rig.datesOf(it["items"].toList()) } shouldBe dates
                pages.dropLast(1).forEach { it["items"].size() shouldBe limit }
                // A page that ends exactly on the last day says so: no empty page follows it.
                pages.size shouldBe (25 + limit - 1) / limit
            }
        }
    }

    @Test
    fun `until starts a page at that date or the nearest earlier day, and holds across the pages that follow`() {
        val bond = pair()
        // The 15th, 16th, 17th, then a gap, then the 21st and 22nd.
        listOf(0L, 1, 2, 6, 7).forEach { bothWrite(bond, DAY_ONE.plusDays(it)) }

        fun from(
            until: String,
            vararg more: Pair<String, String>,
        ) = rig.datesOf(rig.walk(ada, bond, mapOf("until" to until) + more))

        from("2026-09-22") shouldBe listOf("2026-09-22", "2026-09-21", "2026-09-17", "2026-09-16", "2026-09-15")
        from("2026-09-21") shouldBe listOf("2026-09-21", "2026-09-17", "2026-09-16", "2026-09-15")
        // A date with no day: the nearest earlier one.
        from("2026-09-19") shouldBe listOf("2026-09-17", "2026-09-16", "2026-09-15")
        from("2026-09-19", "limit" to "1") shouldBe listOf("2026-09-17", "2026-09-16", "2026-09-15")
        from("2026-09-14").shouldBeEmpty()
        from("9999-12-31").size shouldBe 5
        from("0001-01-01").shouldBeEmpty()
        // With a cursor as well, both bounds apply.
        from("2026-09-21", "cursor" to rig.cursorBefore("2026-09-17")) shouldBe listOf("2026-09-16", "2026-09-15")
        from("2026-09-16", "cursor" to rig.cursorBefore("2026-09-22")) shouldBe listOf("2026-09-16", "2026-09-15")
    }

    @Test
    fun `a cursor is only a date, so one from another bond is read as that date and gives nothing away`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        bothWrite(bond, DAY_ONE.plusDays(1))
        // Another couple's bond, with a day of its own a week later.
        val cara = users.verified("Cara")
        val other = rig.bonds.pair(cara, eve)
        clock.set(at(DAY_ONE.plusDays(7)))
        rig.bonds.submit(cara, other, "zqw-cara-words")
        rig.bonds.submit(eve, other, "zqw-eve-words")
        val foreign = rig.page(cara, other, mapOf("limit" to "1"))["nextCursor"]
        // One day there, so no cursor; ask as the walk would have, for the cursor that day makes.
        foreign.isNull shouldBe true
        val theirs = rig.cursorBefore("2026-09-22")

        val body = rig.days(ada, bond, mapOf("cursor" to theirs))

        body.status shouldBe 200
        rig.datesOf(json.readTree(body.contentAsString)["items"].toList()) shouldBe listOf("2026-09-16", "2026-09-15")
        body.contentAsString shouldNotContain "zqw-"
    }

    // ---- what is refused ----

    @Test
    fun `a limit, a cursor, a date or a flag that cannot be read is 422 naming the parameter, and never repeats what was sent`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        for ((parameter, values) in unreadable()) {
            for (value in values) {
                withClue("$parameter=[$value]") {
                    val response = rig.days(ada, bond, mapOf(parameter to value))
                    response.status shouldBe 422
                    response.contentType.shouldNotBeNull() shouldContain "application/problem+json"
                    val problem = json.readTree(response.contentAsString)
                    problem.propertyNames().toList() shouldContainExactlyInAnyOrder PROBLEM_KEYS
                    problem["code"].asString() shouldBe "VALIDATION_FAILED"
                    problem["status"].asInt() shouldBe 422
                    problem["errors"].toList().map { it["field"].asString() } shouldBe listOf(parameter)
                    problem["errors"]
                        .toList()
                        .single()
                        .propertyNames()
                        .toList() shouldContainExactlyInAnyOrder
                        listOf("field", "code", "message")
                    // Not asked of a value that could be part of the bond's id, which `instance` carries.
                    if (value.length > 2 && !value.all { it in HEX }) response.contentAsString shouldNotContain value
                    response.contentAsString shouldNotContain "zqw"
                }
            }
        }
    }

    /**
     * A cursor is measured before it is decoded, and the test above cannot
     * tell: a value that long is refused after decoding too, with the same
     * answer. What can be held is the other side of the bound, that it
     * refuses nothing this API issues, for any date there can be, and that
     * it is small.
     */
    @Test
    fun `the length a cursor is measured against admits every cursor the API can issue, and little else`() {
        val issued = listOf("0000-01-01", "2026-09-16", "9999-12-31").map { DayCursor(LocalDate.parse(it)).encode() }

        issued.forEach {
            it.length shouldBe 18
            it.length shouldBeLessThan DayCursor.MAX_LENGTH + 1
            DayCursor.parse(it).shouldNotBeNull().encode() shouldBe it
        }
        DayCursor.MAX_LENGTH shouldBeLessThan 65
        DayCursor.parse("A".repeat(DayCursor.MAX_LENGTH + 1)).shouldBeNull()
    }

    @Test
    fun `every parameter that cannot be read is named in the one answer`() {
        val bond = pair()

        val response = rig.days(ada, bond, mapOf("limit" to "0", "cursor" to "zqw!", "until" to "zqw", "favourites" to "zqw"))

        response.status shouldBe 422
        json
            .readTree(response.contentAsString)["errors"]
            .toList()
            .map { it["field"].asString() }
            .toSet() shouldBe
            setOf("limit", "cursor", "until", "favourites")
    }

    @Test
    fun `the bounds of a limit and the two words of a flag are accepted`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        listOf("limit" to "1", "limit" to "7", "limit" to "50", "favourites" to "true", "favourites" to "false").forEach { (name, value) ->
            withClue("$name=$value") { rig.days(ada, bond, mapOf(name to value)).status shouldBe 200 }
        }
    }

    @Test
    fun `the service holds the page's bound itself, whoever calls it`() {
        val bond = pair()
        val membership = access.membershipOf(ada, UUID.fromString(bond))

        listOf(0, -1, 51).forEach { limit ->
            shouldThrow<IllegalArgumentException> { getDays.page(membership, null, null, limit, false) }
        }
        getDays.page(membership, null, null, 50, false).days.shouldBeEmpty()
    }

    @Test
    fun `a stranger who sends a parameter that cannot be read gets the bond's 404, exactly as with one that can`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        val plain = rig.days(eve, bond)
        val bad =
            listOf(
                mapOf("cursor" to "zqw!"),
                mapOf("limit" to "0"),
                mapOf("until" to "2026-02-30"),
                mapOf("favourites" to "zqw"),
            ).map { rig.days(eve, bond, it) }
        val missing = rig.days(ada, UUID.randomUUID().toString(), mapOf("cursor" to "zqw!"))
        val notAnId = rig.days(ada, "not-a-bond", mapOf("limit" to "0"))

        plain.status shouldBe 404
        (bad + missing + notAnId).forEach {
            it.status shouldBe 404
            it.contentAsString.withoutInstance() shouldBe plain.contentAsString.withoutInstance()
        }
    }

    // ---- pages by size ----

    @Test
    fun `a page of the largest entries is cut short by its size, stays under 256 KB, and the walk still visits every day once`() {
        val bond = pair()
        val largest = " ".repeat(92) + FAMILY.repeat(324)
        largest.toByteArray(Charsets.UTF_8).size shouldBe 8_192
        val dates =
            (0L until 26)
                .map { DAY_ONE.plusDays(it) }
                .onEach { date ->
                    clock.set(at(date))
                    rig.bonds.submit(ada, bond, largest)
                    rig.bonds.submit(bea, bond, largest)
                }.map { it.toString() }
                .reversed()

        for (reader in listOf(ada, bea)) {
            val responses = responsesOfWalk(reader, bond, mapOf("limit" to "50"))
            responses.forEach { it.contentAsByteArray.size shouldBeLessThan MAX_RESPONSE }
            val pages = responses.map { json.readTree(it.getContentAsString(Charsets.UTF_8)) }
            pages.flatMap { rig.datesOf(it["items"].toList()) } shouldBe dates
            // 196,608 octets of text are twelve days of two full entries, exactly.
            pages.map { it["items"].size() } shouldBe listOf(12, 12, 2)
            pages[0]["nextCursor"].asString() shouldBe rig.cursorBefore(dates[11])
        }
        // A smaller limit than the size allows is still the limit.
        rig.pages(ada, bond, mapOf("limit" to "5")).map { it["items"].size() } shouldBe listOf(5, 5, 5, 5, 5, 1)
    }

    @Test
    fun `text that grows when it is written as JSON is counted as it will be sent, so such a page is under 256 KB too`() {
        val bond = pair()
        // 500 characters, 500 octets, and 2,995 once each control character is six: fifty such days are 300 KB on the wire.
        val escapes = "a" + "\\u0001".repeat(499)
        val dates =
            (0L until 50)
                .map { DAY_ONE.plusDays(it) }
                .onEach { date ->
                    clock.set(at(date))
                    rig.bonds.submit(ada, bond, escapes)
                    rig.bonds.submit(bea, bond, escapes)
                }.map { it.toString() }
                .reversed()

        val responses = responsesOfWalk(ada, bond, mapOf("limit" to "50"))

        responses.forEach { it.contentAsByteArray.size shouldBeLessThan MAX_RESPONSE }
        responses.size shouldBe 2
        responses.flatMap { rig.datesOf(json.readTree(it.contentAsString)["items"].toList()) } shouldBe dates
    }

    /**
     * The largest page there can be, built and measured. Found by the review
     * of this slice, which proved the arithmetic first written beside the
     * bound wrong by five times.
     *
     * An entry is bounded at 8,192 octets as it arrives. `a` followed by
     * 8,191 U+001F is that; it is one character to the limit of 500, which
     * is counted on the text trimmed, and a trailing U+001F is trimmed; and
     * it is 49,147 octets as JSON, where each control is six. Two such days
     * are 196,588 octets of text, twenty short of the page's bound, so both
     * fit in one page and no third can. What can still join them is a day
     * that sends no text at all: forty-eight days on which both wrote and
     * both erased, two wide tombstones each. Fifty days, the most a page may
     * be asked for, carrying all the text a page may carry.
     *
     * The size is written to `build/archive-largest-page.txt` for a person.
     */
    @Test
    fun `the largest page there can be - two days of the largest entries among forty-eight of tombstones - is under 256 KB`() {
        val bond = pair()
        val largest = "a" + "\\u001f".repeat(8_191)
        val heavy = setOf(DAY_ONE.plusDays(20), DAY_ONE.plusDays(40))
        val dates =
            (0L until 50)
                .map { DAY_ONE.plusDays(it) }
                .onEach { date ->
                    clock.set(at(date))
                    if (date in heavy) {
                        rig.bonds.submit(ada, bond, largest)
                        rig.bonds.submit(bea, bond, largest)
                    } else {
                        val written = bothWrite(bond, date)
                        rig.bonds.delete(ada, written.adas)
                        rig.bonds.delete(bea, written.beas)
                    }
                }.map { it.toString() }
                .reversed()
        // What was accepted and stored is what was meant: four entries of 8,192 octets ending in the control.
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND octet_length(text) = 8192 AND right(text, 1) = chr(31)",
            Int::class.java,
            bond,
        ) shouldBe 4

        val sizes =
            listOf(ada, bea).map { reader ->
                val response = rig.days(reader, bond, mapOf("limit" to "50"))

                response.status shouldBe 200
                val page = json.readTree(response.getContentAsString(Charsets.UTF_8))
                // All fifty in the one response: this is the page the bound has to hold for.
                rig.datesOf(page["items"].toList()) shouldBe dates
                page["nextCursor"].isNull shouldBe true
                val size = response.contentAsByteArray.size
                // Four entries of 49,147 octets each on the wire, and forty-eight days of tombstones around them.
                size shouldBeGreaterThan 4 * 49_147 + 48 * 400
                size shouldBeLessThan MAX_RESPONSE
                size
            }
        File("build").mkdirs()
        File("build/archive-largest-page.txt").writeText("${sizes.max()} octets, of the $MAX_RESPONSE a response may be\n")

        // One more day with any text at all, and the page must stop short of a heavy day: the walk still visits each day once.
        bothWrite(bond, DAY_ONE.plusDays(50))
        for (reader in listOf(ada, bea)) {
            val responses = responsesOfWalk(reader, bond, mapOf("limit" to "50"))
            responses.forEach { it.contentAsByteArray.size shouldBeLessThan MAX_RESPONSE }
            val pages = responses.map { rig.datesOf(json.readTree(it.getContentAsString(Charsets.UTF_8))["items"].toList()) }
            pages.flatten() shouldBe listOf("2026-11-04") + dates
            // The 4th of November back to the 6th of October, then the 5th (the older heavy day) and the rest.
            pages.map { it.size } shouldBe listOf(30, 21)
        }
    }

    @Test
    fun `a day larger than a whole page's text is still given, alone, so the walk cannot stall on it`() {
        val bond = pair()
        val dates = (0L until 3).map { DAY_ONE.plusDays(it) }.onEach { bothWrite(bond, it) }.reversed()
        val membership = access.membershipOf(ada, UUID.fromString(bond))

        // No entry can be this large against the real bound, so the bound is made small: one octet.
        val seen = mutableListOf<LocalDate>()
        var before: LocalDate? = null
        do {
            val page = getDays.page(membership, before, null, 50, false, textOctets = 1)
            page.days.size shouldBe 1
            seen += page.days.single().date
            before = page.next
        } while (before != null && seen.size < 10)

        seen shouldBe dates
    }

    // ---- between two pages ----

    @Test
    fun `a day erased and a day added between two pages are neither repeated nor skipped`() {
        val bond = pair()
        val days = (0L until 6).map { DAY_ONE.plusDays(it) }.associateWith { bothWrite(bond, it) }
        val dates = days.keys.map { it.toString() }.reversed()

        val first = rig.page(bea, bond, mapOf("limit" to "3"))
        rig.datesOf(first["items"].toList()) shouldBe dates.take(3)

        // On a day not fetched yet, both entries are erased; and a new day is written, newer than everything.
        val unfetched = days.getValue(DAY_ONE.plusDays(1))
        rig.bonds.delete(ada, unfetched.adas)
        rig.bonds.delete(bea, unfetched.beas)
        bothWrite(bond, DAY_ONE.plusDays(6))

        val second = rig.page(bea, bond, mapOf("limit" to "3", "cursor" to first["nextCursor"].asString()))

        rig.datesOf(second["items"].toList()) shouldBe dates.drop(3)
        second["nextCursor"].isNull shouldBe true
        // The erased day is still a day she could once read: both tombstones, wide.
        val erased = second["items"].toList()[1]
        erased["date"].asString() shouldBe "2026-09-16"
        erased["myEntry"]["status"].asString() shouldBe "DELETED"
        erased["myEntry"]["text"].isNull shouldBe true
        erased["partnerEntry"]["status"].asString() shouldBe "DELETED"
        // A fresh walk has the new day at its head.
        rig.datesOf(rig.walk(bea, bond)) shouldBe listOf("2026-09-21") + dates
    }

    // ---- favourites ----

    @Test
    fun `favourites lists the days holding an entry the caller has marked, and the partner's marks are nobody else's`() {
        val bond = pair()
        val days = (0L until 5).map { bothWrite(bond, DAY_ONE.plusDays(it)) }
        rig.favourite(bea, days[0].adas)
        rig.favourite(bea, days[2].beas)
        rig.favourite(bea, days[3].adas)
        rig.favourite(ada, days[4].beas)
        rig.favourite(ada, days[3].adas)

        val hers = rig.walk(bea, bond, mapOf("favourites" to "true"))
        rig.datesOf(hers) shouldBe listOf("2026-09-18", "2026-09-17", "2026-09-15")
        hers.map { it["myEntry"]["favourited"].asBoolean() to it["partnerEntry"]["favourited"].asBoolean() } shouldBe
            listOf(false to true, true to false, false to true)
        rig.datesOf(rig.walk(bea, bond, mapOf("favourites" to "true", "limit" to "1"))) shouldBe rig.datesOf(hers)
        rig.datesOf(rig.walk(ada, bond, mapOf("favourites" to "true"))) shouldBe listOf("2026-09-19", "2026-09-18")
        // `false` is the whole archive, as no parameter is; and each day there says which entries are the caller's marks.
        raw(bea, bond, mapOf("favourites" to "false")) shouldBe raw(bea, bond)
        rig.walk(bea, bond).map { it["partnerEntry"]["favourited"].asBoolean() } shouldBe listOf(false, true, false, false, true)
    }

    @Test
    fun `the partner marking and unmarking changes no byte of the other member's feed`() {
        val bond = pair()
        val days = (0L until 3).map { bothWrite(bond, DAY_ONE.plusDays(it)) }
        rig.favourite(ada, days[1].beas)
        val whole = raw(ada, bond)
        val kept = raw(ada, bond, mapOf("favourites" to "true"))

        days.forEach {
            rig.favourite(bea, it.adas)
            rig.favourite(bea, it.beas)
        }

        raw(ada, bond) shouldBe whole
        raw(ada, bond, mapOf("favourites" to "true")) shouldBe kept
    }

    @Test
    fun `a marked entry whose author has withdrawn is no favourite from the withdrawal's commit, and the walk still ends`() {
        val bond = pair()
        val days = (0L until 4).map { bothWrite(bond, DAY_ONE.plusDays(it)) }
        rig.favourite(bea, days[0].adas)
        rig.favourite(bea, days[1].beas)
        rig.favourite(bea, days[2].adas)
        rig.datesOf(rig.walk(bea, bond, mapOf("favourites" to "true"))) shouldBe listOf("2026-09-17", "2026-09-16", "2026-09-15")

        // A block withdraws. No dispatcher runs in this module's tests: the rows, and the marks on them, are whole.
        rig.bonds.block(ada, bond)
        marksOf(bond) shouldBe 3
        wholeEntriesOf(bond, ada) shouldBe 4

        val pages = rig.pages(bea, bond, mapOf("favourites" to "true", "limit" to "1"))

        // Each withdrawn day was a row match and is dropped after the gate: its page is empty and still says where to go on.
        pages.map { rig.datesOf(it["items"].toList()) } shouldBe listOf(emptyList(), listOf("2026-09-16"), emptyList())
        pages.map { it["nextCursor"].isNull } shouldBe listOf(false, false, true)
        val whole = rig.walk(bea, bond, mapOf("favourites" to "true"))
        rig.datesOf(whole) shouldBe listOf("2026-09-16")
        whole.single()["myEntry"]["favourited"].asBoolean() shouldBe true
        whole.single()["partnerEntry"]["status"].asString() shouldBe "DELETED"
        // And in the whole archive nothing of hers says it is kept.
        rig.walk(bea, bond).forEach { it["partnerEntry"]["favourited"].asBoolean() shouldBe false }
    }

    // ---- who may read ----

    @Test
    fun `a bond that has ended is still read by both, the one who left and the one who stayed`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        bothWrite(bond, DAY_ONE.plusDays(1))
        val before = listOf(ada, bea).associateWith { raw(it, bond) }
        clock.set(at(DAY_ONE.plusDays(2)))

        rig.bonds.leave(bea, bond)

        listOf(ada, bea).forEach { raw(it, bond) shouldBe before.getValue(it) }
    }

    @Test
    fun `a bond counting down to its deletion is still read by both`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        val before = listOf(ada, bea).associateWith { raw(it, bond) }

        listOf(ada, bea).forEach { user ->
            mockMvc
                .post("/api/v1/bonds/$bond/deletion-request") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
                .andReturn()
                .response.status shouldBe 202
        }
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, bond) shouldBe "PENDING_DELETION"

        listOf(ada, bea).forEach { raw(it, bond) shouldBe before.getValue(it) }
    }

    // ---- withdrawal ----

    @Test
    fun `with no dispatcher run, a withdrawn author's entries are tombstones in the feed for both, while the rows hold their words`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        bothWrite(bond, DAY_ONE.plusDays(1))
        // A third day she wrote alone, closed while the bond was whole: revealed to him at the close.
        val alone = DAY_ONE.plusDays(2)
        clock.set(at(alone))
        rig.bonds.submit(ada, bond, words("ada", alone))
        close(bond, alone)
        // And today, which he cannot read yet.
        val today = DAY_ONE.plusDays(3)
        clock.set(at(today))
        rig.bonds.submit(ada, bond, words("ada", today))

        rig.bonds.block(ada, bond)

        wholeEntriesOf(bond, ada) shouldBe 4
        for (reader in listOf(ada, bea)) {
            val raw = raw(reader, bond)
            raw shouldNotContain "zq-ada"
            raw shouldContain "zq-bea"
        }
        val hers = rig.walk(ada, bond)
        rig.datesOf(hers) shouldBe listOf("2026-09-18", "2026-09-17", "2026-09-16", "2026-09-15")
        hers.forEach {
            it["myEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
            it["myEntry"]["status"].asString() shouldBe "DELETED"
            it["myEntry"]["text"].isNull shouldBe true
        }
        // For him: today's was never his to see and is not listed; the rest are the wide tombstone.
        val his = rig.walk(bea, bond)
        rig.datesOf(his) shouldBe listOf("2026-09-17", "2026-09-16", "2026-09-15")
        his.forEach {
            it["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
            it["partnerEntry"]["status"].asString() shouldBe "DELETED"
            it["partnerEntry"]["text"].isNull shouldBe true
        }
    }

    @Test
    fun `a page asked with a membership taken before the block answers the tombstone to both`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        val before = listOf(ada, bea).associateWith { access.membershipOf(it, UUID.fromString(bond)) }
        before.values.forEach { it.withdrawnMemberIds shouldBe emptySet() }

        rig.bonds.block(ada, bond)

        for ((reader, membership) in before) {
            val day = getDays.page(membership, null, null, 20, false).days.single()
            val hers = if (reader == ada) day.myEntry else day.partnerEntry
            hers.shouldNotBeNull().readability shouldBe Readability.TOMBSTONE
            hers.disclosed
                .shouldNotBeNull()
                .text
                .shouldBeNull()
            val rendered = json.writeValueAsString(DaysResponse.from(getDays.page(membership, null, null, 20, false)))
            rendered shouldNotContain "zq-ada"
            rendered shouldContain "zq-bea"
        }
        wholeEntriesOf(bond, ada) shouldBe 1
    }

    @Test
    fun `the feed shows the blocker none of her words when the block commits after her membership is resolved`() {
        feedInterruptedFor { ada }
    }

    @Test
    fun `the feed shows the partner none of the blocker's words when the block commits after his membership is resolved`() {
        feedInterruptedFor { bea }
    }

    /** The route resolves the membership once before it loads the entries: in the controller. The ending follows that one. */
    private fun feedInterruptedFor(reader: () -> UUID) {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        bothWrite(bond, DAY_ONE.plusDays(1))
        interruptible.afterResolution(1) {
            pool.submit { rig.bonds.block(ada, bond) }.get(10, TimeUnit.SECONDS)
            jdbc.queryForObject("SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id = ?::uuid", Int::class.java, bond) shouldBe 1
        }

        val response = rig.days(reader(), bond)

        response.status shouldBe 200
        response.contentAsString shouldNotContain "zq-ada"
        response.contentAsString shouldContain "zq-bea"
        interruptible.fired shouldBe true
        // The controller's, and the one made after the entries were loaded.
        interruptible.resolutions shouldBe 2
        wholeEntriesOf(bond, ada) shouldBe 2
    }

    /**
     * The order itself, which the two tests above cannot see: there the
     * ending commits before the page is read at all, so a reader made too
     * early is still made after it.
     *
     * Here the request is stopped **between** its two reads. As the
     * controller's resolution returns, another transaction takes `entries`
     * exclusively, so the page's first statement waits. Only when Postgres
     * reports that wait is the bond ended, and then the table let go. A
     * request that had already asked who has withdrawn goes on with an
     * answer from before the ending and rows that are still whole, and shows
     * the words. One that asks last does not.
     *
     * **What this does not hold**, though it was first written to: the order
     * of the two reads inside `GetDays.read`. The statement that waits is
     * the one that *lists* the days, which reads `entries` in its filter, so
     * both of those reads come after the ending whichever is first. This
     * test fails for a reader made before the days are listed.
     * `ArchiveReadOrderTest` is the one that fails when the two are swapped.
     *
     * The table lock stalls every reader of `entries` while it is held. That
     * is safe here because this module's test classes run one after another
     * in one JVM (no parallel execution is configured), and the lock is held
     * only while this test's own request waits on it.
     */
    @Test
    fun `who has withdrawn is asked after the page's entries are read, so an ending that commits during that read hides the words`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        withAnotherTransaction { holder ->
            val holderPid = backendPidOf(holder)
            interruptible.afterResolution(1) {
                interruptible.disarm()
                holder.createStatement().use { it.execute("LOCK TABLE entries IN ACCESS EXCLUSIVE MODE") }
            }

            val reading = pool.submit<MockHttpServletResponse> { rig.days(bea, bond) }
            await().atMost(Duration.ofSeconds(10)).until { reading.isDone || waitingBehind(holderPid).isNotEmpty() }
            reading.isDone shouldBe false
            interruptible.fired shouldBe true
            waitingBehind(holderPid).forEach { it shouldContain "entries" }

            rig.bonds.block(ada, bond)
            holder.commit()
            val response = reading.get(10, TimeUnit.SECONDS)

            response.status shouldBe 200
            response.contentAsString shouldNotContain "zq-ada"
            response.contentAsString shouldContain "zq-bea"
            // Nothing was erased: the row is whole, and it is the gate that hid the words.
            wholeEntriesOf(bond, ada) shouldBe 1
        }
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

    // ---- the joining day ----

    @Test
    fun `the feed is a gratitude operation like any other - a joining day left SUSPENDED is resumed and revealed before it is read`() {
        // What slice C1 committed for a couple who both wrote on the day they paired: SUSPENDED, two entries, neither revealed.
        clock.set(at(DAY_ONE))
        val created = rig.bonds.create(ada)
        val bond = rig.bonds.idOf(created)
        rig.bonds.submit(ada, bond, words("ada", DAY_ONE))
        rig.bonds.accept(bea, rig.bonds.codeOf(created))
        rig.bonds.submit(bea, bond, words("bea", DAY_ONE))
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 1
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 2

        val first = rig.page(bea, bond)["items"].single()

        first["status"].asString() shouldBe "REVEALED"
        first["myEntry"]["text"].asString() shouldBe words("bea", DAY_ONE)
        first["partnerEntry"]["text"].asString() shouldBe words("ada", DAY_ONE)
        rig.dayStatus(bond, "2026-09-15") shouldBe "REVEALED"
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND revealed_at IS NOT NULL",
            Int::class.java,
            bond,
        ) shouldBe 2
    }

    /** Values each parameter must refuse. Each `zqw` is there to be looked for in the answer. */
    private fun unreadable(): List<Pair<String, List<String>>> {
        val good = rig.cursorBefore("2026-09-16")
        return listOf(
            "limit" to UNREADABLE_LIMITS,
            "cursor" to
                listOf(
                    "zqw!!notbase64",
                    "",
                    // Not this version, not a date, a date that does not exist, a loose date.
                    rig.encoded("v2:2026-09-16"),
                    rig.encoded("2026-09-16"),
                    rig.encoded("v1:zqwyesterday"),
                    rig.encoded("v1:2026-02-30"),
                    rig.encoded("v1:2026-9-16"),
                    rig.encoded("v1:+2026-09-16"),
                    // Trailing junk: inside the encoding, after it, and as padding.
                    rig.encoded("v1:2026-09-16zqw"),
                    rig.encoded("v1:2026-09-16\n"),
                    good + "A",
                    good + "=",
                    // The same bytes spelt another way: padded, and with the unused bits of the last character set.
                    "$good==",
                    good.dropLast(1) + URL_ALPHABET[URL_ALPHABET.indexOf(good.last()) xor 1],
                    rig.encoded("v1:+12026-09-16"),
                    "$good,$good",
                    // Longer than any cursor is: one character over the bound, a real cursor with a tail, and 100 KB twice.
                    "A".repeat(DayCursor.MAX_LENGTH + 1),
                    good + "A".repeat(DayCursor.MAX_LENGTH),
                    rig.encoded("v1:2026-09-16" + "9".repeat(75_000)),
                    "zqw".repeat(33_334),
                ),
            "until" to
                listOf(
                    "2026-02-30",
                    "2026-2-3",
                    "zqwyesterday",
                    "",
                    "2026-09-15T00:00:00Z",
                    "+2026-09-15",
                    "+12026-09-15",
                    "20260915",
                    " 2026-09-15",
                    "2026-13-01",
                ),
            "favourites" to listOf("zqwyes", "TRUE", "True", "1", "0", "", "true,false", "true\t"),
        )
    }

    // ---- helpers ----

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

    /** The close job's one step for [date], at the first instant that Lagos day is over. */
    private fun close(
        bond: String,
        date: LocalDate,
    ) {
        closeDay.settle(BondDayId(rig.dayId(bond, date.toString())), at(date).plus(Duration.ofHours(13))) shouldBe CloseDay.Outcome.CLOSED
    }

    /** Every response of a walk, so that each can be measured as bytes. */
    private fun responsesOfWalk(
        user: UUID,
        bond: String,
        parameters: Map<String, String>,
    ): List<MockHttpServletResponse> {
        val responses = mutableListOf<MockHttpServletResponse>()
        var cursor: String? = null
        do {
            val response = rig.days(user, bond, parameters + listOfNotNull(cursor?.let { "cursor" to it }))
            response.status shouldBe 200
            responses.add(response)
            cursor = json.readTree(response.getContentAsString(Charsets.UTF_8))["nextCursor"].takeUnless { it.isNull }?.asString()
            responses.size shouldBeLessThan 100
        } while (cursor != null)
        return responses
    }

    /** The body of a page that must be a `200`, as the bytes it was sent as. */
    private fun raw(
        user: UUID,
        bond: String,
        parameters: Map<String, String> = emptyMap(),
    ): String = rig.days(user, bond, parameters).also { it.status shouldBe 200 }.contentAsString

    private fun marksOf(bond: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM entry_favourites f JOIN entries e ON e.id = f.entry_id WHERE e.bond_id = ?::uuid",
            Int::class.java,
            bond,
        )!!

    /** How many of [author]'s entries in [bond] still hold their words. */
    private fun wholeEntriesOf(
        bond: String,
        author: UUID,
    ): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM entries WHERE bond_id = ?::uuid AND author_member_id = ? AND text LIKE 'zq-%' AND deleted_at IS NULL",
            Int::class.java,
            bond,
            rig.bonds.memberId(bond, author),
        )!!

    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    private data class Written(
        val adas: String,
        val beas: String,
    )

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)

        /** NFR-008. */
        const val MAX_RESPONSE = 262_144

        const val HEX = "0123456789abcdef"

        /** From `007` on: a number has one spelling, so one written another way is not read (`007` was once a 7). */
        val UNREADABLE_LIMITS =
            listOf("0", "51", "-1", "zqwlimit", "", " 5", "5 ", "1.5", "1e1", "+5", "٥", "99999999999999999999") +
                listOf("007", "07", "050", "00", "+50", "5\t", "５", "0x5", "5.0")

        const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** One user-perceived character, twenty-five octets. */
        const val FAMILY = "👨‍👩‍👧‍👦"

        val FULL_KEYS = listOf("id", "bondId", "date", "authorMemberId", "text", "status", "createdAt", "intendedAt", "favourited")
        val PROBLEM_KEYS = listOf("type", "title", "status", "detail", "instance", "code", "errors")

        /** 10:00Z: late morning of [date] in Africa/Lagos. */
        fun at(date: LocalDate): Instant = Instant.parse("${date}T10:00:00Z")

        fun words(
            who: String,
            date: LocalDate,
        ): String = "zq-$who-words-of-$date"
    }
}
