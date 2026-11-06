package com.moyi.bond.service

import com.moyi.bond.api.BondAdminLookup
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(classes = [BondTestApplication::class])
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class BondAdminLookupServiceTest(
    @Autowired private val lookup: BondAdminLookup,
    @Autowired private val create: CreateBond,
    @Autowired private val acceptInvite: AcceptInvite,
    @Autowired private val guard: BondAccessGuard,
    @Autowired private val endBond: EndBond,
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
    fun `an active and an ended bond are two rows, the ended one with its end and both with the partner`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val cy = users.verified("Cy")
        val ended = bondForTwo(ada, cy)
        endBond.leave(guard.membershipOf(UserId(cy), BondId(ended)), withdrawEntries = false)
        val active = bondForTwo(ada, bea)

        val rows = lookup.bondsOf(ada).associateBy { it.bondId }

        rows shouldHaveSize 2
        rows.getValue(active).status shouldBe "ACTIVE"
        rows.getValue(active).endedAt.shouldBeNull()
        rows.getValue(active).partnerUserId shouldBe bea
        rows.getValue(ended).status shouldBe "ARCHIVED"
        rows.getValue(ended).endedAt.shouldNotBeNull()
        rows.getValue(ended).partnerUserId shouldBe cy
        rows.getValue(active).toString() shouldBe "BondAdminView(bondId=$active)"
    }

    @Test
    fun `a pending bond has no partner, and a user in no bond has no rows`() {
        val ada = users.verified("Ada")
        val bond = create.create(draft(ada))

        lookup.bondsOf(ada).single().let {
            it.bondId shouldBe bond.bond.id.value
            it.status shouldBe "PENDING_MEMBER"
            it.partnerUserId.shouldBeNull()
        }
        lookup.bondsOf(UUID.randomUUID()).shouldBeEmpty()
    }

    private fun bondForTwo(
        creator: UUID,
        joiner: UUID,
    ): UUID {
        val view = create.create(draft(creator))
        acceptInvite.accept(UserId(joiner), view.invite!!.invite.code)
        return view.bond.id.value
    }

    private fun draft(creator: UUID) =
        BondDraft(
            creator = UserId(creator),
            type = BondType.COUPLE,
            name = "Us",
            anchorTimezone = RegionZone.of("Africa/Lagos"),
            revealTimeLocal = null,
            reminderTimezone = null,
        )
}
