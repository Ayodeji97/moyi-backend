package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * The partner's name on every day of the archive (ADR-0036 question 5,
 * ruled 2026-10-21): `partnerDisplayName`, on the feed, the day route and
 * search, as `bond` answers it.
 *
 * What the name is in each state of a bond is `BondAccessTest`'s, in `bond`.
 * What is held here is that the archive carries that answer unchanged: on
 * every day, from the one lookup a request makes, `null` and not absent before
 * anybody joined, and read as it is now, so the conditional read's tag moves
 * with a rename. That the day is still the feed's element byte for byte is
 * `DayViewTest`'s first test, which now covers this field too.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(EntryChangesTest.TimeConfiguration::class, MarkerReadLastTest.AccessConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class ArchivePartnerNameTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val json: ObjectMapper,
    @Autowired access: BondAccess,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val rig = ArchiveRig(mockMvc, tokens, JdbcTemplate(dataSource), json)
    private val counting = access as MarkerReadLastTest.InterruptibleBondAccess

    private lateinit var ada: UUID
    private lateinit var bea: UUID

    @BeforeEach
    fun setUp() {
        rig.bonds.clear()
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.bonds.clear()
        users.clear()
        clock.set(NOW)
    }

    @Test
    fun `every day of the feed, the day route and search names the partner, from one lookup a request`() {
        val bond = pair()
        (0L until 3).forEach { bothWrite(bond, DAY_ONE.plusDays(it)) }

        for ((reader, partner) in listOf(ada to "Bea", bea to "Ada")) {
            withClue("the feed, to the partner of $partner") {
                val items = askedOnce { rig.page(reader, bond)["items"].toList() }
                items.size shouldBe 3
                items.forEach { it["partnerDisplayName"].asString() shouldBe partner }
            }
            withClue("the day, to the partner of $partner") {
                val day = askedOnce { json.readTree(rig.day(reader, bond, DAY_ONE.toString()).contentAsString) }
                day["partnerDisplayName"].asString() shouldBe partner
            }
            withClue("search, to the partner of $partner") {
                val items = askedOnce { rig.page(reader, bond, mapOf("q" to "walk"), route = "search")["items"].toList() }
                items.size shouldBe 3
                items.forEach { it["partnerDisplayName"].asString() shouldBe partner }
            }
        }
    }

    @Test
    fun `the creator's archive before anybody joined says null, and says it`() {
        clock.set(BOND_CREATED)
        val bond = rig.bonds.idOf(rig.bonds.create(ada))
        clock.set(NOW)
        rig.bonds.submit(ada, bond, "zq thanks for the walk")

        val day = rig.page(ada, bond)["items"].single()

        day["status"].asString() shouldBe "SUSPENDED"
        // A key with a null, not a missing key: the field is part of the day's one shape.
        day.has("partnerDisplayName") shouldBe true
        day["partnerDisplayName"].isNull shouldBe true
        json.readTree(rig.day(ada, bond, DAY_ONE.toString()).contentAsString)["partnerDisplayName"].isNull shouldBe true
    }

    @Test
    fun `a partner who left is still named in the archive, to both, and one identity forgot is a former member`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        rig.bonds.leave(bea, bond)

        rig.page(ada, bond)["items"].single()["partnerDisplayName"].asString() shouldBe "Bea"
        rig.page(bea, bond)["items"].single()["partnerDisplayName"].asString() shouldBe "Ada"

        users.forget(bea)
        rig.page(ada, bond)["items"].single()["partnerDisplayName"].asString() shouldBe "Former member"
    }

    @Test
    fun `a rename is in the next read, and moves the tag of the feed and of the day`() {
        val bond = pair()
        bothWrite(bond, DAY_ONE)
        val feedTag = rig.days(ada, bond).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        val dayTag = rig.day(ada, bond, DAY_ONE.toString()).getHeader(HttpHeaders.ETAG).shouldNotBeNull()
        // Unchanged, each tag is still the body's.
        rig.days(ada, bond, headers = mapOf(HttpHeaders.IF_NONE_MATCH to feedTag)).status shouldBe 304
        rig.day(ada, bond, DAY_ONE.toString(), mapOf(HttpHeaders.IF_NONE_MATCH to dayTag)).status shouldBe 304

        users.rename(bea, "Beatrice")

        val feed = rig.days(ada, bond, headers = mapOf(HttpHeaders.IF_NONE_MATCH to feedTag))
        feed.status shouldBe 200
        feed.getHeader(HttpHeaders.ETAG) shouldNotBe feedTag
        json.readTree(feed.contentAsString)["items"].single()["partnerDisplayName"].asString() shouldBe "Beatrice"
        val day = rig.day(ada, bond, DAY_ONE.toString(), mapOf(HttpHeaders.IF_NONE_MATCH to dayTag))
        day.status shouldBe 200
        day.getHeader(HttpHeaders.ETAG) shouldNotBe dayTag
        json.readTree(day.contentAsString)["partnerDisplayName"].asString() shouldBe "Beatrice"
    }

    /** [read] is one request, which must ask `bond` for the partner's name exactly once. */
    private fun <T> askedOnce(read: () -> T): T {
        counting.nameLookups.set(0)
        return read().also { counting.nameLookups.get() shouldBe 1 }
    }

    private fun pair(): String {
        clock.set(BOND_CREATED)
        return rig.bonds.pair(ada, bea).also { clock.set(NOW) }
    }

    /** Both write on [date], Ada first, so the day is revealed; both entries hold "walk", for search. */
    private fun bothWrite(
        bond: String,
        date: LocalDate,
    ) {
        clock.set(at(date))
        rig.bonds.submit(ada, bond, "zq ada thanks for the walk on $date")
        rig.bonds.submit(bea, bond, "zq bea thanks for the walk on $date")
    }

    private fun JsonNode.single(): JsonNode = toList().single()

    private companion object {
        val BOND_CREATED: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        val DAY_ONE: LocalDate = LocalDate.of(2026, 9, 15)

        /** 10:00Z: late morning of [date] in Africa/Lagos. */
        fun at(date: LocalDate): Instant = Instant.parse("${date}T10:00:00Z")
    }
}
