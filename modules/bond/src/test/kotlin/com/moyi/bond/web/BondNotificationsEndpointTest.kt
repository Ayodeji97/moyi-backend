package com.moyi.bond.web

import com.moyi.bond.api.ReminderRoster
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import com.moyi.notification.api.NotificationPreferences
import com.moyi.notification.api.QuietHoursWindow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource

/**
 * `GET`/`PUT /bonds/{bondId}/members/me/notifications` (spec §4 N2, D-2,
 * ADR-0045), and the reminder roster that reads the same rows. A non-member's
 * 404 is `BondCrossTenantTest`'s, which reads every bond-scoped route.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondNotificationsEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val roster: ReminderRoster,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a member who never chose is shown every category on except on-this-day`() {
        val (bondId, ada, _) = pairedBond()

        val response = get(ada, bondId)

        response.status shouldBe 200
        response.contentAsString shouldBe
            """{"categories":{"reminder":true,"partnerJoined":true,"reveal":true,"milestone":true,"onThisDay":false,"proposal":true}}"""
        // Nothing was written to show it: the defaults are the reading of V9's `{}`.
        storedPrefs(bondId, ada) shouldBe "{}"
    }

    @Test
    fun `a put sets what it is sent and keeps what it is not`() {
        val (bondId, ada, _) = pairedBond()

        put(ada, bondId, """{"categories":{"reminder":false,"milestone":false}}""").status shouldBe 200
        // Neither reminder nor milestone is sent here: both stay off, not back to their defaults.
        val response = put(ada, bondId, """{"categories":{"onThisDay":true}}""")

        response.status shouldBe 200
        response.contentAsString shouldBe
            """{"categories":{"reminder":false,"partnerJoined":true,"reveal":true,"milestone":false,"onThisDay":true,"proposal":true}}"""
        get(ada, bondId).contentAsString shouldBe response.contentAsString
    }

    @Test
    fun `one member's toggles are theirs alone, and the other's are untouched`() {
        val (bondId, ada, bea) = pairedBond()

        put(ada, bondId, """{"categories":{"reveal":false}}""").status shouldBe 200

        get(bea, bondId).contentAsString shouldContain "\"reveal\":true"
        storedPrefs(bondId, bea) shouldBe "{}"
    }

    @Test
    fun `the settings put, which clears what it is not sent, leaves the toggles alone`() {
        // The reason this resource is separate (spec §4 N2): a client that knows nothing of toggles must not clear them.
        val (bondId, ada, _) = pairedBond()
        put(ada, bondId, """{"categories":{"onThisDay":true}}""").status shouldBe 200

        mockMvc
            .put("/api/v1/bonds/$bondId/members/me/settings") {
                header(HttpHeaders.AUTHORIZATION, bearer(ada))
                contentType = MediaType.APPLICATION_JSON
                content = """{"reminderTimeLocal":"07:30"}"""
            }.andReturn()
            .response.status shouldBe 200

        get(ada, bondId).contentAsString shouldContain "\"onThisDay\":true"
    }

    @Test
    fun `a put without categories, or with a toggle that is not a yes or no, is 422 and changes nothing`() {
        val (bondId, ada, _) = pairedBond()

        put(ada, bondId, """{}""").status shouldBe 422
        put(ada, bondId, """{"categories":null}""").status shouldBe 422
        put(ada, bondId, """{"categories":{"reveal":"sometimes"}}""").status shouldBe 400
        storedPrefs(bondId, ada) shouldBe "{}"
    }

    @Test
    fun `an ended bond's toggles can be read and not changed`() {
        val (bondId, ada, bea) = pairedBond()
        put(ada, bondId, """{"categories":{"reminder":false}}""").status shouldBe 200
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        get(ada, bondId).status shouldBe 200
        val response = put(ada, bondId, """{"categories":{"reminder":true}}""")
        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
        get(ada, bondId).contentAsString shouldContain "\"reminder\":false"
    }

    @Test
    fun `the roster names a member whose reminder fell in the window, with their settings resolved`() {
        val (bondId, ada, bea) = pairedBond()
        // Ada: 21:00 in Kathmandu (15:15Z), quiet 23:00 to 06:30, milestones off. Bea: the default 20:00 in Lagos.
        settings(
            ada,
            bondId,
            """{"reminderTimeLocal":"21:00","reminderTimezone":"Asia/Kathmandu","quietHoursStart":"23:00","quietHoursEnd":"06:30"}""",
        )
        put(ada, bondId, """{"categories":{"milestone":false}}""").status shouldBe 200

        val due = roster.remindersDueBetween(Instant.parse("2026-10-28T15:10:00Z"), Instant.parse("2026-10-28T15:15:00Z"))

        due.map { it.userId } shouldBe listOf(ada)
        val reminder = due.single()
        reminder.bondId shouldBe UUID.fromString(bondId)
        reminder.remindAt shouldBe Instant.parse("2026-10-28T15:15:00Z")
        // The bond's own day, by its anchor (Lagos, UTC+1): 16:15 on the 28th there, ending at 23:00Z.
        reminder.bondDate shouldBe LocalDate.of(2026, 10, 28)
        reminder.bondDayEndsAt shouldBe Instant.parse("2026-10-28T23:00:00Z")
        reminder.recipient.zone shouldBe ZoneId.of("Asia/Kathmandu")
        reminder.recipient.quietHours shouldBe QuietHoursWindow(LocalTime.of(23, 0), LocalTime.of(6, 30))
        reminder.recipient.preferences shouldBe NotificationPreferences(milestone = false)

        // Bea's 20:00 in Lagos is 19:00Z.
        roster.remindersDueBetween(Instant.parse("2026-10-28T18:55:00Z"), Instant.parse("2026-10-28T19:00:00Z")).map { it.userId } shouldBe
            listOf(bea)
    }

    @Test
    fun `a reminder that fell before the bond began is not due, and does not stop the others`() {
        // Found by the smoke run: a bond made at 23:57 with a reminder at 23:55 asked its timeline
        // for a day before its first, which threw, and the whole sweep, everyone's reminders, failed.
        val (_, ada, bea) = pairedBond()
        val (late, _, _) = pairedBond("Cal" to "Dee")
        // 20:00 in Lagos is 19:00Z, tomorrow's: inside the window for every member of both bonds.
        val windowEnd =
            Instant
                .now()
                .truncatedTo(ChronoUnit.DAYS)
                .plus(Duration.ofDays(1))
                .plus(Duration.ofHours(19))
        // The second bond begins after it, as a bond made between a reminder and the sweep does.
        jdbc.update(
            "UPDATE bond_anchor_intervals SET effective_from = ?, first_label = ? WHERE bond_id = ?",
            java.sql.Timestamp.from(windowEnd.plusSeconds(1)),
            windowEnd.plusSeconds(1).atZone(ZoneId.of("Africa/Lagos")).toLocalDate(),
            UUID.fromString(late),
        )

        val due = roster.remindersDueBetween(windowEnd.minusSeconds(300), windowEnd)

        due.map { it.userId } shouldContainExactlyInAnyOrder listOf(ada, bea)
    }

    @Test
    fun `the roster leaves out a bond that has ended, and a member who left`() {
        val (ended, ada, bea) = pairedBond()
        val (open, cal, dee) = pairedBond("Cal" to "Dee")
        mockMvc
            .post("/api/v1/bonds/$ended/leave") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 204

        val due = roster.remindersDueBetween(Instant.parse("2026-10-28T18:55:00Z"), Instant.parse("2026-10-28T19:00:00Z"))

        due.map { it.userId } shouldContainExactlyInAnyOrder listOf(cal, dee)
        due.map { it.bondId.toString() }.toSet() shouldBe setOf(open)
        due.filter { it.userId == ada }.shouldBeEmpty()
    }

    private data class Paired(
        val bondId: String,
        val first: UUID,
        val second: UUID,
    )

    private fun pairedBond(names: Pair<String, String> = "Ada" to "Bea"): Paired {
        val ada = users.verified(names.first)
        val bea = users.verified(names.second)
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    header(HttpHeaders.AUTHORIZATION, bearer(ada))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
        created.status shouldBe 201
        val code = Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(bea)) }
            .andReturn()
            .response.status shouldBe 200
        return Paired(Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1], ada, bea)
    }

    private fun settings(
        userId: UUID,
        bondId: String,
        body: String,
    ) {
        mockMvc
            .put("/api/v1/bonds/$bondId/members/me/settings") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response.status shouldBe 200
    }

    private fun get(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/members/me/notifications") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun put(
        userId: UUID,
        bondId: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/bonds/$bondId/members/me/notifications") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun storedPrefs(
        bondId: String,
        userId: UUID,
    ): String =
        jdbc.queryForObject(
            "SELECT notification_prefs::text FROM bond_members WHERE bond_id = ? AND user_id = ?",
            String::class.java,
            UUID.fromString(bondId),
            userId,
        )!!

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"
}
