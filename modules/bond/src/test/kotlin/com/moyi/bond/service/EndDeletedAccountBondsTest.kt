package com.moyi.bond.service

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.events.DispatchResult
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
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
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/**
 * `bond`'s share of an account deletion (ADR-0058 decision 7), through the real outbox: an
 * `AccountDeleted` event as identity publishes it, the real dispatcher, and the bonds as their
 * members see them afterwards.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
@Suppress("LongParameterList") // What Spring hands the test; each is used.
internal class EndDeletedAccountBondsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val clock: Clock,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute(
            "TRUNCATE TABLE outbox_deliveries, outbox_events, bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    @Test
    fun `every bond the person is still in ends as a leave would end it, and nobody else's`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val cleo = users.verified("Cleo")
        val paired = pair(ada, bea)
        val solo = bondIdOf(createBond(ada))
        val beas = pair(bea, cleo)

        deleteAccount(ada)

        getBond(bea, paired).contentAsString shouldContain "\"status\":\"ARCHIVED\""
        statusOf(solo) shouldBe "ARCHIVED"
        jdbc.queryForObject(
            "SELECT count(*) FROM bond_invites WHERE bond_id = ?::uuid AND revoked_at IS NULL",
            Int::class.java,
            solo,
        ) shouldBe
            0
        statusOf(beas) shouldBe "ACTIVE"
        // A leave and not a block: nothing stops Bea from meeting a new account of the same person.
        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE user_id = ? AND left_at IS NULL", Int::class.java, ada) shouldBe 0
        // The rows stay: gratitude finds the person's memberships by them.
        jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE user_id = ?", Int::class.java, ada) shouldBe 2
    }

    @Test
    fun `a bond counting down to its deletion keeps counting down`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bond = pair(ada, bea)
        listOf(ada, bea).forEach {
            mockMvc
                .post("/api/v1/bonds/$bond/deletion-request") { auth(it) }
                .andReturn()
                .response.status shouldBe
                202
        }

        deleteAccount(ada)

        statusOf(bond) shouldBe "PENDING_DELETION"
        jdbc.queryForObject("SELECT left_at IS NOT NULL FROM bond_members WHERE user_id = ?", Boolean::class.java, ada) shouldBe true
    }

    @Test
    fun `delivered twice it ends nothing the second time, and a bond left earlier is not touched`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val left = pair(ada, bea)
        mockMvc
            .post("/api/v1/bonds/$left/leave") { auth(ada) }
            .andReturn()
            .response.status shouldBe 204
        val leftAtBefore = jdbc.queryForObject("SELECT left_at FROM bond_members WHERE user_id = ?", java.sql.Timestamp::class.java, ada)
        val versionBefore = jdbc.queryForObject("SELECT version FROM bonds WHERE id = ?::uuid", Int::class.java, left)

        deleteAccount(ada)
        deleteAccount(ada)

        jdbc.queryForObject("SELECT left_at FROM bond_members WHERE user_id = ?", java.sql.Timestamp::class.java, ada) shouldBe leftAtBefore
        jdbc.queryForObject("SELECT version FROM bonds WHERE id = ?::uuid", Int::class.java, left) shouldBe versionBefore
    }

    private fun deleteAccount(userId: UUID) {
        // What the set-up published (a join's pushes) is delivered first, so the count below is this event's alone.
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), 100)
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("User", userId, "AccountDeleted", mapOf("userId" to userId), clock.instant()))
        }
        dispatcher.dispatchDue(clock.instant().plusSeconds(1), 10) shouldBe DispatchResult(delivered = 1, failed = 0, more = false)
    }

    private fun pair(
        owner: UUID,
        joiner: UUID,
    ): String {
        val created = createBond(owner)
        mockMvc
            .post("/api/v1/invites/${codeOf(created)}/accept") { auth(joiner) }
            .andReturn()
            .response.status shouldBe 200
        return bondIdOf(created)
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                auth(userId)
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse = mockMvc.get("/api/v1/bonds/$bondId") { auth(userId) }.andReturn().response

    private fun statusOf(bondId: String): String =
        jdbc.queryForObject("SELECT status FROM bonds WHERE id = ?::uuid", String::class.java, bondId)!!

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.auth(userId: UUID) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
    }

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]
}
