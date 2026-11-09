package com.moyi.bond.service

import com.moyi.bond.api.BondExports
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * The bonds a person's export carries (ADR-0059): every bond they are or were in, their own
 * settings and acts in it, their partner by display name, and never whether the partner left or
 * blocked them (ADR-0028's discreet exit).
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondExportsTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired private val exports: BondExports,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute(
            "TRUNCATE TABLE outbox_deliveries, outbox_events, bond_proposals, blocks, bond_entry_withdrawals, " +
                "bond_invites, bond_members, bonds CASCADE",
        )
        users.clear()
    }

    @Test
    fun `a bond the person blocked in is theirs, with their block, their withdrawal and the name they gave`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bond = pair(ada, bea)
        jdbc.update("UPDATE bond_members SET nickname_for_other = 'what Ada calls Bea' WHERE user_id = ?", ada)
        jdbc.update("UPDATE bond_members SET nickname_for_other = 'what Bea calls Ada' WHERE user_id = ?", bea)
        end("block", ada, bond, withdraw = true)

        val adas = exports.bondsOf(ada).single()

        adas.id shouldBe UUID.fromString(bond)
        adas.name shouldBe "Us"
        adas.status shouldBe "ARCHIVED"
        adas.role shouldBe "OWNER"
        adas.leftAt.shouldNotBeNull()
        adas.blockedAt.shouldNotBeNull()
        adas.withdrewEntriesAt.shouldNotBeNull()
        adas.nicknameForOther shouldBe "what Ada calls Bea"
        adas.partnerDisplayName shouldBe "Bea"
    }

    @Test
    fun `the person who was blocked learns nothing a leave would not have told them`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val blocked = pair(ada, bea)
        end("block", ada, blocked, withdraw = true)
        val cleo = users.verified("Cleo")
        val dee = users.verified("Dee")
        val left = pair(cleo, dee)
        end("leave", cleo, left, withdraw = true)

        val beas = exports.bondsOf(bea).single()
        val dees = exports.bondsOf(dee).single()

        // Bea was blocked and Dee was left: their exports are the same shape, field for field.
        beas.blockedAt.shouldBeNull()
        beas.withdrewEntriesAt.shouldBeNull()
        beas.leftAt.shouldBeNull()
        beas.partnerDisplayName shouldBe "Ada"
        listOf(beas.status, beas.blockedAt, beas.withdrewEntriesAt, beas.leftAt, beas.nicknameForOther) shouldBe
            listOf(dees.status, dees.blockedAt, dees.withdrewEntriesAt, dees.leftAt, dees.nicknameForOther)
        // Neither carries the name the other gave them.
        beas.nicknameForOther.shouldBeNull()
    }

    @Test
    fun `an account gone is the Former member it is everywhere, and a person with no bond has none`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        pair(ada, bea)
        users.forget(bea)

        exports.bondsOf(ada).single().partnerDisplayName shouldBe "Former member"
        exports.bondsOf(UUID.randomUUID()).shouldBeEmpty()
    }

    @Test
    fun `the accounts in a bond are both members, the one who left included, and nobody for no bond`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bond = pair(ada, bea)
        pair(ada, users.verified("Cleo"))
        end("leave", bea, bond, withdraw = false)

        exports.accountsInBond(UUID.fromString(bond)) shouldBe setOf(ada, bea)
        exports.accountsInBond(UUID.randomUUID()) shouldBe emptySet()
    }

    private fun end(
        how: String,
        user: UUID,
        bond: String,
        withdraw: Boolean,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bond/$how") {
                auth(user)
                contentType = MediaType.APPLICATION_JSON
                content = """{"withdrawEntries": $withdraw}"""
            }.andReturn()
            .response.status shouldBe 204
    }

    private fun pair(
        owner: UUID,
        joiner: UUID,
    ): String {
        val created =
            mockMvc
                .post("/api/v1/bonds") {
                    auth(owner)
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
                }.andReturn()
                .response
                .also { it.status shouldBe 201 }
        mockMvc
            .post("/api/v1/invites/${codeOf(created)}/accept") { auth(joiner) }
            .andReturn()
            .response.status shouldBe 200
        return Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.auth(userId: UUID) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(userId).token}")
    }

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]
}
