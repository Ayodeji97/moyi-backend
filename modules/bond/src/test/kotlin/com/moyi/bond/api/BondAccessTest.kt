package com.moyi.bond.api

import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.bond.service.AcceptInvite
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.CreateBond
import com.moyi.bond.service.EndBond
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.NotFoundException
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

/**
 * `bond.api.BondAccess` through the real beans `app` would wire — the port
 * every later task in this slice reaches `bond` through, rather than the
 * `BondAccessGuard` it wraps, which `gratitude` cannot see at all.
 *
 * Mirrors [com.moyi.bond.web.BondsEndpointTest]'s harness: a fake identity
 * port, a real Postgres, and a truncate between tests, because `BondAccess`
 * is `internal` to this module and nothing in `app` could exercise it yet.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@Suppress("LongParameterList") // A test's collaborators, each named; nothing to bundle them into.
internal class BondAccessTest(
    @Autowired private val access: BondAccess,
    @Autowired private val create: CreateBond,
    @Autowired private val acceptInvite: AcceptInvite,
    @Autowired private val guard: BondAccessGuard,
    @Autowired private val endBond: EndBond,
    @Autowired private val transactions: TransactionTemplate,
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
    fun `a member gets the four facts gratitude needs, and nobody else gets anything`() {
        val ada = users.verified("Ada")
        val bond = create.create(draft(ada))

        val membership = access.membershipOf(ada, bond.bond.id.value)

        membership.userId shouldBe ada
        membership.anchorTimezone shouldBe "Africa/Lagos"
        membership.revealTimeLocal.shouldBeNull()
        membership.strictMode shouldBe false
        membership.isOpen shouldBe true
        membership.hasLeft shouldBe false
        // Freshly created, waiting for its second member — the fact
        // `gratitude`'s SubmitEntry uses to open a day SUSPENDED (doc 04
        // §8.3a).
        membership.awaitingSecondMember shouldBe true

        // Doc 06 §2 and T-02: one answer for a stranger and for an id that
        // names nobody, and it is the same one the bond routes give.
        shouldThrow<NotFoundException> { access.membershipOf(users.verified("Eve"), bond.bond.id.value) }
        shouldThrow<NotFoundException> { access.membershipOf(ada, UUID.randomUUID()) }
    }

    @Test
    fun `a withdrawal is reported to both members, by both reads, whoever has left`() {
        // `gratitude`'s read gate asks this of whoever is reading: the
        // withdrawer must stop seeing their own words too, and the other
        // member must stop seeing them at once (spec §6.7).
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bondId = bondForTwo(ada, bea)
        val adaMember = access.membershipOf(ada, bondId).memberId
        val beaMember = access.membershipOf(bea, bondId).memberId

        withClue("before anyone has withdrawn") {
            everyReadOf(bondId, ada, bea).forEach { it.withdrawnMemberIds.shouldBeEmpty() }
        }

        // Ada leaves and keeps her words: still nobody.
        endBond.leave(guard.membershipOf(UserId(ada), BondId(bondId)), withdrawEntries = false)
        everyReadOf(bondId, ada, bea).forEach { it.withdrawnMemberIds.shouldBeEmpty() }

        // Then takes them back, as a member who has left.
        endBond.block(guard.membershipOf(UserId(ada), BondId(bondId)), withdrawEntries = true)
        access.membershipOf(ada, bondId).hasLeft shouldBe true
        everyReadOf(bondId, ada, bea).forEach { it.withdrawnMemberIds shouldBe setOf(adaMember) }

        // And Bea, who never left, takes hers back as well.
        endBond.block(guard.membershipOf(UserId(bea), BondId(bondId)), withdrawEntries = true)
        access.membershipOf(bea, bondId).hasLeft shouldBe false
        everyReadOf(bondId, ada, bea).forEach { it.withdrawnMemberIds shouldBe setOf(adaMember, beaMember) }
    }

    @Test
    fun `a withdrawal in one bond is not reported in another`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val withdrawn = bondForTwo(ada, bea)
        val untouched = bondForTwo(ada, bea)

        endBond.block(guard.membershipOf(UserId(ada), BondId(withdrawn)), withdrawEntries = true)

        everyReadOf(withdrawn, ada, bea).forEach { it.withdrawnMemberIds shouldBe setOf(access.membershipOf(ada, withdrawn).memberId) }
        everyReadOf(untouched, ada, bea).forEach { it.withdrawnMemberIds.shouldBeEmpty() }
    }

    @Test
    fun `the closer's view reports a withdrawal too, by both reads, and only that bond's`() {
        // The closer reveals entries for nobody's request, so no membership
        // tells it who withdrew: between the ending's commit and the
        // erasure it would stamp a withdrawn author's entry as revealed.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bondId = bondForTwo(ada, bea)
        val other = bondForTwo(ada, bea)
        val adaMember = access.membershipOf(ada, bondId).memberId
        val beaMember = access.membershipOf(bea, bondId).memberId

        bothClosingViewsOf(bondId).forEach { it.withdrawnMemberIds.shouldBeEmpty() }

        endBond.leave(guard.membershipOf(UserId(ada), BondId(bondId)), withdrawEntries = false)
        bothClosingViewsOf(bondId).forEach { it.withdrawnMemberIds.shouldBeEmpty() }

        endBond.block(guard.membershipOf(UserId(ada), BondId(bondId)), withdrawEntries = true)
        bothClosingViewsOf(bondId).forEach { it.withdrawnMemberIds shouldBe setOf(adaMember) }

        endBond.block(guard.membershipOf(UserId(bea), BondId(bondId)), withdrawEntries = true)
        bothClosingViewsOf(bondId).forEach { it.withdrawnMemberIds shouldBe setOf(adaMember, beaMember) }
        // The same set a member is told: one fact, two views of it.
        bothClosingViewsOf(bondId).forEach { it.withdrawnMemberIds shouldBe access.membershipOf(bea, bondId).withdrawnMemberIds }

        bothClosingViewsOf(other).forEach { it.withdrawnMemberIds.shouldBeEmpty() }
    }

    private fun bothClosingViewsOf(bondId: UUID): List<BondClosingView> =
        listOf(access.closingViewOf(bondId)!!, transactions.execute { access.lockClosingViewOf(bondId) }!!)

    /** Each member's membership, by the plain read and by the locking one: the four answers that must agree. */
    private fun everyReadOf(
        bondId: UUID,
        vararg members: UUID,
    ): List<BondMembership> =
        members.flatMap { member ->
            listOf(access.membershipOf(member, bondId), transactions.execute { access.lockMembershipOf(member, bondId) })
        }

    private fun bondForTwo(
        creator: UUID,
        joiner: UUID,
    ): UUID {
        val view = create.create(draft(creator))
        acceptInvite.accept(UserId(joiner), view.invite!!.invite.code)
        return view.bond.id.value
    }

    /** The same shape `BondPersistenceTest` builds by hand: a couple, anchored in Lagos, with no reveal time set. */
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
