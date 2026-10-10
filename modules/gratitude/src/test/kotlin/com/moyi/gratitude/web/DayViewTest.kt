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
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `GET /bonds/{bondId}/days/{date}`: one day of the archive.
 *
 * Three things are held here. **The day is the feed's day**: the body is,
 * byte for byte, the element the feed gives the same caller for that date.
 * **Every date that is not in the caller's archive is one answer**, whole
 * response for whole response, whatever the reason: above all a day on which
 * only the other person has written, which any other answer would announce.
 * And **a withdrawal hides its author's words here from its commit**, as it
 * does in the feed.
 *
 * What each entry looks like over every state a day can be in is
 * `ArchiveGateTest`'s, through the feed; the first test here is what carries
 * that over to this route.
 *
 * Days are `Africa/Lagos` days; the instant used for each is 10:00Z. The
 * words are markers no UUID, date or hexadecimal digest can contain (`zq…`),
 * so a body and a header can be searched for them.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList", "LargeClass") // What Spring hands the test; and one route's rules, kept where they can be read together.
internal class DayViewTest(
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

    // ---- the day is the feed's day ----

    @Test
    fun `a day is, byte for byte, what the feed gives the same caller for that date`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        // A tombstone both once read, a bookmark of hers, and a today only she has written on.
        rig.bonds.delete(ada, bothWrite(bond, DAY_ONE.plusDays(1)).adas)
        rig.favourite(ada, bothWrite(bond, DAY_ONE.plusDays(2)).beas)
        clock.set(at(DAY_ONE.plusDays(3)))
        rig.bonds.submit(ada, bond, words("ada", DAY_ONE.plusDays(3)))

        val listed = mapOf(ada to 4, bea to 3)
        for ((reader, count) in listed) {
            val feed = rig.days(reader, bond).also { it.status shouldBe 200 }.contentAsString
            val dates = rig.datesOf(json.readTree(feed)["items"].toList())
            dates.size shouldBe count

            val days = dates.map { rawDay(reader, bond, it) }

            feed shouldBe """{"items":[${days.joinToString(",")}],"nextCursor":null}"""
        }
        val one = json.readTree(rawDay(ada, bond, "2026-09-17"))
        one.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("date", "status", "myEntry", "partnerEntry")
        one["date"].asString() shouldBe "2026-09-17"
        one["status"].asString() shouldBe "REVEALED"
        one["myEntry"]["text"].asString() shouldBe words("ada", DAY_ONE.plusDays(2))
        one["partnerEntry"]["text"].asString() shouldBe words("bea", DAY_ONE.plusDays(2))
        one["partnerEntry"]["favourited"].asBoolean() shouldBe true
        // Her bookmark is hers: the same day from his side says nothing is kept.
        json.readTree(rawDay(bea, bond, "2026-09-17"))["myEntry"]["favourited"].asBoolean() shouldBe false
    }

    @Test
    fun `reading a day writes nothing`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        clock.set(at(DAY_ONE.plusDays(3)))
        val before = rig.bonds.wholeDays(bond) to rig.bonds.wholeEntries(bond)

        rawDay(ada, bond, "2026-09-15")
        rig.day(bea, bond, "2026-09-16").status shouldBe 404
        rig.day(bea, bond, "2026-09-18").status shouldBe 404

        (rig.bonds.wholeDays(bond) to rig.bonds.wholeEntries(bond)) shouldBe before
    }

    // ---- who has written is not this route's to say ----

    @Test
    fun `a day only one of them has written on is 404 to the other and the writer's own day to the writer`() {
        val bond = pair()
        val beas = rig.bonds.submit(bea, bond, words("bea", DAY_ONE))
        rig.dayStatus(bond, "2026-09-15") shouldBe "PARTIAL"

        val adas = rig.day(ada, bond, "2026-09-15")

        adas.status shouldBe 404
        adas.contentAsString shouldContain "\"code\":\"DAY_NOT_FOUND\""
        adas.contentAsString shouldNotContain "zq-"
        // And it is the answer a day nobody wrote on gets, to the byte.
        whole(adas) shouldBe whole(rig.day(ada, bond, "2026-09-14"))

        val hers = json.readTree(rawDay(bea, bond, "2026-09-15"))
        hers["status"].asString() shouldBe "PARTIAL"
        hers["myEntry"]["id"].asString() shouldBe beas
        hers["myEntry"]["text"].asString() shouldBe words("bea", DAY_ONE)
        hers["partnerEntry"].isNull shouldBe true
    }

    @Test
    fun `a day both have written on that waits for its hour is each one's own entry and an author and a status of the other's`() {
        val bond = pair()
        // A reveal time that has not come: the second entry does not reveal the day.
        jdbc.update("UPDATE bonds SET reveal_time_local = '23:59:59' WHERE id = ?::uuid", bond) shouldBe 1
        val adas = rig.bonds.submit(ada, bond, words("ada", DAY_ONE))
        val beas = rig.bonds.submit(bea, bond, words("bea", DAY_ONE))
        rig.dayStatus(bond, "2026-09-15") shouldBe "PENDING_REVEAL"

        for ((reader, mine, theirs) in listOf(Triple(ada, adas, bea), Triple(bea, beas, ada))) {
            val raw = rawDay(reader, bond, "2026-09-15")
            val day = json.readTree(raw)
            day["status"].asString() shouldBe "PENDING_REVEAL"
            day["myEntry"]["id"].asString() shouldBe mine
            day["myEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
            day["partnerEntry"].propertyNames().toList() shouldContainExactlyInAnyOrder listOf("authorMemberId", "status")
            day["partnerEntry"]["status"].asString() shouldBe "LOCKED"
            day["partnerEntry"]["authorMemberId"].asString() shouldBe rig.bonds.memberId(bond, theirs).toString()
            raw shouldNotContain (if (reader == ada) "zq-bea" else "zq-ada")
            raw shouldNotContain (if (reader == ada) beas else adas)
        }
    }

    @Test
    fun `a solo day that closed after the bond had ended is its author's alone - 200 to her, the one 404 to him`() {
        val bond = pair()
        rig.bonds.submit(ada, bond, words("ada", DAY_ONE))
        clock.set(at(DAY_ONE).plusSeconds(7_200))
        rig.bonds.leave(bea, bond)
        close(bond, DAY_ONE)
        rig.dayStatus(bond, "2026-09-15") shouldBe "SOLO"

        val hers = json.readTree(rawDay(ada, bond, "2026-09-15"))
        hers["status"].asString() shouldBe "SOLO"
        hers["myEntry"]["text"].asString() shouldBe words("ada", DAY_ONE)
        hers["partnerEntry"].isNull shouldBe true

        val his = rig.day(bea, bond, "2026-09-15")
        his.status shouldBe 404
        whole(his) shouldBe whole(rig.day(bea, bond, "2026-09-14"))
    }

    // ---- one 404 ----

    @Test
    fun `every date that is not in the caller's archive is one 404 - the same status, headers and body, whatever the reason`() {
        val bond = pair()
        // The 15th: only Bea wrote, and the day is long over. The 17th: Bea wrote and deleted, so it holds a tombstone that
        // was never anybody's but hers. The 18th: a closed EMPTY day with no entry at all. The 19th, today: only Bea has written.
        rig.bonds.submit(bea, bond, words("bea", DAY_ONE))
        clock.set(at(DAY_ONE.plusDays(2)))
        rig.bonds.delete(bea, rig.bonds.submit(bea, bond, words("bea", DAY_ONE.plusDays(2))))
        rig.insertDay(bond, DAY_ONE.plusDays(3), "EMPTY")
        clock.set(at(DAY_ONE.plusDays(4)))
        rig.bonds.submit(bea, bond, words("bea", DAY_ONE.plusDays(4)))
        // What Ada's archive is: nothing. And Bea's, so that each date below is known to be a real row or a real absence.
        rig.datesOf(rig.walk(ada, bond)) shouldBe emptyList()
        rig.datesOf(rig.walk(bea, bond)) shouldBe listOf("2026-09-19", "2026-09-17", "2026-09-15")
        listOf("2026-09-15", "2026-09-17", "2026-09-18", "2026-09-19").forEach { rig.dayId(bond, it) }

        val notInHerArchive =
            listOf(
                "the partner's unrevealed entry, on a day that is over" to "2026-09-15",
                "the partner's tombstone, never revealed" to "2026-09-17",
                "an EMPTY day with no entry" to "2026-09-18",
                "today, on which only the partner has written" to "2026-09-19",
                "no row: a day inside the bond's life nobody wrote on" to "2026-09-16",
                "no row: the day after the bond was made" to "2026-09-14",
                "tomorrow" to "2026-09-20",
                "a far future date" to "9999-12-31",
                "before the bond existed" to "2026-09-01",
                "before anything existed" to "0001-01-01",
                "a day that does not exist" to "2026-02-30",
                "a month that does not exist" to "2026-13-01",
                "a loose date" to "2026-2-3",
                "a compact date" to "20260915",
                "a signed year" to "+2026-09-15",
                "a year too long" to "12026-09-15",
                "a date and a time" to "2026-09-15T00:00:00Z",
                "a word" to "zqwyesterday",
                "today, the word" to "today",
                "a space" to " ",
                "a date with a space before it" to " 2026-09-15",
                "digits that are not ASCII" to "٢٠٢٦-٠٩-١٥",
                "an id" to UUID.randomUUID().toString(),
                "four hundred characters" to "zqw".repeat(134).take(400),
            )

        val answers = notInHerArchive.map { (why, date) -> why to rig.day(ada, bond, date) }

        val first = answers.first().second
        first.status shouldBe 404
        json.readTree(first.contentAsString).let { problem ->
            problem.propertyNames().toList() shouldContainExactlyInAnyOrder listOf("type", "title", "status", "detail", "instance", "code")
            problem["code"].asString() shouldBe "DAY_NOT_FOUND"
            problem["status"].asInt() shouldBe 404
            problem["detail"].asString() shouldBe "That day was not found."
        }
        first.getHeader(HttpHeaders.ETAG).shouldBeNull()
        answers.forEach { (why, answer) ->
            withClue(why) {
                // Whole response for whole response. Only `instance` is taken out: it is the path the caller typed.
                whole(answer) shouldBe whole(first)
                // And outside `instance` nothing of what was asked comes back.
                answer.contentAsString.withoutInstance() shouldNotContain "zq"
                answer.contentAsString.withoutInstance() shouldNotContain "2026"
            }
        }
        // The same dates from the other side, where they are days: the 404 above was about the caller and not the date.
        listOf("2026-09-15", "2026-09-17", "2026-09-19").forEach { rig.day(bea, bond, it).status shouldBe 200 }
        whole(rig.day(bea, bond, "2026-09-18")) shouldBe whole(first)
    }

    /**
     * The test above cannot see a forgiving reader of dates: every date in
     * it is one the caller has nothing on, so a loose spelling read as the
     * date it resembles is still a `404`. Here **the day each value would
     * mean is a day the caller can read**, so reading it at all is a `200`.
     * The 15th for the spellings of it; the 30th and the 1st for a 31st of
     * September, which one kind of parser pulls back to the last day of the
     * month and another rolls into the next.
     */
    @Test
    fun `a date has one spelling - a loose one is the 404 even when the day it resembles is the caller's to read`() {
        val bond = pair()
        val hers = listOf("2026-09-15", "2026-09-30", "2026-10-01")
        hers.forEach { bothWrite(bond, LocalDate.parse(it)) }
        hers.forEach { rig.day(ada, bond, it).status shouldBe 200 }
        val noSuchDay = rig.day(ada, bond, "2026-09-14")
        noSuchDay.status shouldBe 404

        listOf(
            "a month of one digit" to "2026-9-15",
            "a space before" to " 2026-09-15",
            "a space after" to "2026-09-15 ",
            "a tab after" to "2026-09-15\t",
            "a signed year" to "+2026-09-15",
            "a year of five digits" to "02026-09-15",
            "a day of three digits" to "2026-09-015",
            "no hyphens" to "20260915",
            "a time" to "2026-09-15T10:00:00Z",
            "a zone" to "2026-09-15Z",
            "an offset" to "2026-09-15+01:00",
            "the day of the year" to "2026-258",
            "the day of the week" to "2026-W38-2",
            "other separators" to "2026_09_15",
            "digits that are not ASCII" to "٢٠٢٦-٠٩-١٥",
            "full-width digits" to "２０２６-０９-１５",
            "a day that does not exist, between two that are hers" to "2026-09-31",
            "a day nought" to "2026-10-00",
        ).forEach { (why, date) ->
            withClue(why) {
                val answer = rig.day(ada, bond, date)
                answer.status shouldBe 404
                answer.contentAsString shouldContain "\"code\":\"DAY_NOT_FOUND\""
                whole(answer) shouldBe whole(noSuchDay)
            }
        }
    }

    /**
     * **The one header in which two `404`s of this route differ, stated
     * rather than left out of the lists above.** A last path segment with a
     * dot in it looks to Spring MVC like a file name with an extension, and
     * for an extension it does not know to be harmless it adds
     * `Content-Disposition: inline;filename=f.txt` to whatever it writes:
     * its guard against a reflected file download, applied to every route
     * of every Spring application, error bodies included. `2026.09.15` is
     * such a segment; `2026-09-15.json` is not (`json` is on its list).
     *
     * It is accepted, and not suppressed, because **it is a function of the
     * characters the caller typed and of nothing else**: not of the bond,
     * not of the caller, not of whether the day the value resembles exists
     * or is theirs. That is what is held here: the same value gets the
     * same whole response whether it resembles a day that is hers or a day
     * that is nobody's; without that one header it is the `404` of every
     * other date; and a stranger typing it gets the bond's `404` with the
     * same header, so it does not tell a member from a stranger either.
     * There is no local way to turn it off: the guard has no switch, and a
     * filter that removed the header here would be a second mechanism to
     * keep in step with the framework's for no observable gain.
     */
    @Test
    fun `a date typed with a dot is the same 404 with one header more, and that header follows from what was typed alone`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        rig.day(ada, bond, "2026-09-15").status shouldBe 200
        val noSuchDay = rig.day(ada, bond, "2026-09-14")
        noSuchDay.status shouldBe 404
        noSuchDay.getHeader(HttpHeaders.CONTENT_DISPOSITION).shouldBeNull()
        val strangers = rig.day(eve, bond, "2026-09-14")
        strangers.status shouldBe 404
        val guard = listOf("inline;filename=f.txt")

        // Each value twice: once resembling the 15th, which is hers to read, and once the 14th, which is nobody's.
        listOf(
            Triple("2026.09.15", "2026.09.14", guard),
            Triple("2026-09.15", "2026-09.14", guard),
            Triple("2026-09-15.exe", "2026-09-14.exe", guard),
            Triple("2026-09-15.zqw", "2026-09-14.zqw", guard),
            // An extension the framework takes to be harmless: no header, and so the 404 of every other date entire.
            Triple("2026-09-15.json", "2026-09-14.json", emptyList()),
            Triple("2026-09-15.txt", "2026-09-14.txt", emptyList()),
        ).forEach { (resemblesHers, resemblesNothing, disposition) ->
            withClue(resemblesHers) {
                val answer = rig.day(ada, bond, resemblesHers)

                answer.status shouldBe 404
                answer.contentAsString shouldContain "\"code\":\"DAY_NOT_FOUND\""
                answer.getHeaders(HttpHeaders.CONTENT_DISPOSITION) shouldBe disposition
                // Whether the day it resembles is hers or nobody's: the same response, this header included.
                whole(answer) shouldBe whole(rig.day(ada, bond, resemblesNothing))
                // And but for that header it is the one 404.
                whole(answer).without(HttpHeaders.CONTENT_DISPOSITION) shouldBe whole(noSuchDay)
                // A stranger who types it gets the bond's 404 with the same header: it is about the path, not the asker.
                val stranger = rig.day(eve, bond, resemblesHers)
                stranger.getHeaders(HttpHeaders.CONTENT_DISPOSITION) shouldBe disposition
                whole(stranger).without(HttpHeaders.CONTENT_DISPOSITION) shouldBe whole(strangers)
            }
        }
    }

    @Test
    fun `a path with no date at all is no route of this API, for a member and a stranger alike`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)

        val answers =
            listOf(ada, eve).map { user ->
                mockMvc
                    .get("/api/v1/bonds/$bond/days/") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
                    .andReturn()
                    .response
            }

        // Not this route's 404 and not the bond's: the one any path the API does not have gets, which depends on no bond.
        answers.forEach {
            it.status shouldBe 404
            it.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        }
        whole(answers[0]) shouldBe whole(answers[1])
    }

    @Test
    fun `a stranger, a missing bond and a value that is no id get the bond's 404, decided before the date is read`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        val dates = listOf("2026-09-15", "2026-09-14", "2026-02-30", "zqwyesterday", "9999-12-31")

        val theBonds = rig.day(eve, bond, "2026-09-15")
        val others =
            dates.map { rig.day(eve, bond, it) } +
                dates.map { rig.day(ada, UUID.randomUUID().toString(), it) } +
                dates.map { rig.day(ada, "not-a-bond", it) }

        theBonds.status shouldBe 404
        theBonds.contentAsString shouldContain "\"code\":\"NOT_FOUND\""
        theBonds.contentAsString shouldContain "\"detail\":\"That bond was not found.\""
        theBonds.getHeader(HttpHeaders.ETAG).shouldBeNull()
        others.forEach { whole(it) shouldBe whole(theBonds) }
        // It is the answer the bond itself gives a stranger, and the feed.
        val bondsOwn =
            mockMvc
                .get("/api/v1/bonds/$bond") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(eve).token}") }
                .andReturn()
                .response
        whole(bondsOwn) shouldBe whole(theBonds)
        whole(rig.days(eve, bond)) shouldBe whole(theBonds)
        // And it is not the member's "no such day": that one names a day, which only a member is told about.
        val noSuchDay = rig.day(ada, bond, "2026-09-14")
        noSuchDay.contentAsString shouldContain "\"code\":\"DAY_NOT_FOUND\""
        whole(noSuchDay) shouldNotBe whole(theBonds)
    }

    // ---- who may read ----

    @Test
    fun `a bond that has ended, and one counting down to its deletion, are still read a day at a time by both`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        bothWrite(bond, DAY_ONE.plusDays(1))
        val before = listOf(ada, bea).associateWith { rawDay(it, bond, "2026-09-15") }
        clock.set(at(DAY_ONE.plusDays(2)))

        rig.bonds.leave(bea, bond)

        listOf(ada, bea).forEach { rawDay(it, bond, "2026-09-15") shouldBe before.getValue(it) }

        val counting = pair()
        bothWrite(counting, DAY_ONE.plusDays(3))
        val whole = listOf(ada, bea).associateWith { rawDay(it, counting, "2026-09-18") }
        listOf(ada, bea).forEach { user ->
            mockMvc
                .post(
                    "/api/v1/bonds/$counting/deletion-request",
                ) { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
                .andReturn()
                .response.status shouldBe 202
        }
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, counting) shouldBe "PENDING_DELETION"

        listOf(ada, bea).forEach { rawDay(it, counting, "2026-09-18") shouldBe whole.getValue(it) }
    }

    // ---- withdrawal ----

    @Test
    fun `with no dispatcher run, a withdrawn author's entry is a tombstone in the day for both, while the row holds its words`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        // And today, which he cannot read yet: it was never his, and stays the one 404.
        clock.set(at(DAY_ONE.plusDays(1)))
        rig.bonds.submit(ada, bond, words("ada", DAY_ONE.plusDays(1)))

        rig.bonds.block(ada, bond)

        wholeEntriesOf(bond, ada) shouldBe 2
        for (reader in listOf(ada, bea)) {
            val raw = rawDay(reader, bond, "2026-09-15")
            raw shouldNotContain "zq-ada"
            raw shouldContain "zq-bea"
            val hers = json.readTree(raw)[if (reader == ada) "myEntry" else "partnerEntry"]
            hers.propertyNames().toList() shouldContainExactlyInAnyOrder FULL_KEYS
            hers["status"].asString() shouldBe "DELETED"
            hers["text"].isNull shouldBe true
        }
        json.readTree(rawDay(ada, bond, "2026-09-16"))["myEntry"]["status"].asString() shouldBe "DELETED"
        rig.day(bea, bond, "2026-09-16").let {
            it.status shouldBe 404
            it.contentAsString shouldContain "\"code\":\"DAY_NOT_FOUND\""
        }
    }

    @Test
    fun `a day asked with a membership taken before the block answers the tombstone to both`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        val before = listOf(ada, bea).associateWith { access.membershipOf(it, UUID.fromString(bond)) }
        before.values.forEach { it.withdrawnMemberIds shouldBe emptySet() }

        rig.bonds.block(ada, bond)

        for ((reader, membership) in before) {
            val day = getDays.day(membership, DAY_ONE).shouldNotBeNull()
            val hers = if (reader == ada) day.myEntry else day.partnerEntry
            hers.shouldNotBeNull().readability shouldBe Readability.TOMBSTONE
            hers.disclosed
                .shouldNotBeNull()
                .text
                .shouldBeNull()
            val rendered = json.writeValueAsString(DayResponse.from(day))
            rendered shouldNotContain "zq-ada"
            rendered shouldContain "zq-bea"
        }
        wholeEntriesOf(bond, ada) shouldBe 1
    }

    @Test
    fun `the day shows the blocker none of her words when the block commits after her membership is resolved`() {
        dayInterruptedFor { ada }
    }

    @Test
    fun `the day shows the partner none of the blocker's words when the block commits after his membership is resolved`() {
        dayInterruptedFor { bea }
    }

    /** The route resolves the membership once before it loads the entries: in the controller. The ending follows that one. */
    private fun dayInterruptedFor(reader: () -> UUID) {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        interruptible.afterResolution(1) {
            pool.submit { rig.bonds.block(ada, bond) }.get(10, TimeUnit.SECONDS)
            jdbc.queryForObject("SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id = ?::uuid", Int::class.java, bond) shouldBe 1
        }

        val response = rig.day(reader(), bond, "2026-09-15")

        response.status shouldBe 200
        response.contentAsString shouldNotContain "zq-ada"
        response.contentAsString shouldContain "zq-bea"
        interruptible.fired shouldBe true
        // The controller's, and the one made after the entries were loaded.
        interruptible.resolutions shouldBe 2
        wholeEntriesOf(bond, ada) shouldBe 1
    }

    /**
     * A request that waits on a lock while the bond ends: as the
     * controller's resolution returns, another transaction takes `entries`
     * exclusively, so the day's first statement that reads entries waits.
     * Only when Postgres reports that wait is the bond ended, and then the
     * table let go. A request that had already asked who has withdrawn goes
     * on with an answer from before the ending and a row that is still
     * whole, and shows the words.
     *
     * **What this does not hold**, though it was first written to: the order
     * of the two reads inside `GetDays.read`. The statement that waits is
     * the one that *finds* the day, which reads `entries` in its filter, so
     * both of those reads come after the ending whichever is first. This
     * test fails for a reader made before the day is found.
     * `ArchiveReadOrderTest` is the one that fails when the two are swapped.
     *
     * The table lock stalls every reader of `entries` while it is held. That
     * is safe here because this module's test classes run one after another
     * in one JVM, and the lock is held only while this test's own request
     * waits on it.
     */
    @Test
    fun `who has withdrawn is asked after the day's entries are read, so an ending that commits during that read hides the words`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        withAnotherTransaction { holder ->
            val holderPid = backendPidOf(holder)
            interruptible.afterResolution(1) {
                interruptible.disarm()
                holder.createStatement().use { it.execute("LOCK TABLE entries IN ACCESS EXCLUSIVE MODE") }
            }

            val reading = pool.submit<MockHttpServletResponse> { rig.day(bea, bond, "2026-09-15") }
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

    // ---- the joining day ----

    @Test
    fun `the day is a gratitude operation like any other - a joining day left SUSPENDED is resumed and revealed before it is read`() {
        // What slice C1 committed for a couple who both wrote on the day they paired: SUSPENDED, two entries, neither revealed.
        clock.set(at(DAY_ONE))
        val created = rig.bonds.create(ada)
        val bond = rig.bonds.idOf(created)
        rig.bonds.submit(ada, bond, words("ada", DAY_ONE))
        rig.bonds.accept(bea, rig.bonds.codeOf(created))
        rig.bonds.submit(bea, bond, words("bea", DAY_ONE))
        jdbc.update("UPDATE bond_days SET status = 'SUSPENDED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 1
        jdbc.update("UPDATE entries SET status = 'SUBMITTED', revealed_at = NULL WHERE bond_id = ?::uuid", bond) shouldBe 2

        val first = json.readTree(rawDay(bea, bond, "2026-09-15"))

        first["status"].asString() shouldBe "REVEALED"
        first["myEntry"]["text"].asString() shouldBe words("bea", DAY_ONE)
        first["partnerEntry"]["text"].asString() shouldBe words("ada", DAY_ONE)
        rig.dayStatus(bond, "2026-09-15") shouldBe "REVEALED"
    }

    // ---- helpers ----

    private fun pair(): String {
        clock.set(BOND_CREATED)
        return rig.bonds.pair(ada, bea).also { clock.set(NOW) }
    }

    /** Both write on [date], Ada first, so the day is revealed (unless the bond's reveal time has not come). */
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

    /** The body of a day that must be a `200`, as the bytes it was sent as. */
    private fun rawDay(
        user: UUID,
        bond: String,
        date: String,
    ): String = rig.day(user, bond, date).also { withClue("$date: ${it.contentAsString}") { it.status shouldBe 200 } }.contentAsString

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

    /**
     * A response as a caller can tell it from another: its status, every
     * header with every value, and its body. **One thing is taken out of the
     * body**, `instance`, which is the path the caller asked for and so
     * differs wherever the question did.
     */
    private fun whole(response: MockHttpServletResponse): Triple<Int, Map<String, List<String>>, String> =
        Triple(
            response.status,
            response.headerNames.sorted().associateWith { response.getHeaders(it) },
            response.contentAsString.withoutInstance(),
        )

    private fun Triple<Int, Map<String, List<String>>, String>.without(header: String) = copy(second = second - header)

    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    private data class Written(
        val adas: String,
        val beas: String,
    )

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)

        val FULL_KEYS = listOf("id", "bondId", "date", "authorMemberId", "text", "status", "createdAt", "intendedAt", "favourited")

        /** 10:00Z: late morning of [date] in Africa/Lagos. */
        fun at(date: LocalDate): Instant = Instant.parse("${date}T10:00:00Z")

        fun words(
            who: String,
            date: LocalDate,
        ): String = "zq-$who-words-of-$date"
    }
}
