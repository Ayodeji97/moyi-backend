package com.moyi.bond.api

import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.bond.service.CreateBond
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.web.NotFoundException
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
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
internal class BondAccessTest(
    @Autowired private val access: BondAccess,
    @Autowired private val create: CreateBond,
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

        // Doc 06 §2 and T-02: one answer for a stranger and for an id that
        // names nobody, and it is the same one the bond routes give.
        shouldThrow<NotFoundException> { access.membershipOf(users.verified("Eve"), bond.bond.id.value) }
        shouldThrow<NotFoundException> { access.membershipOf(ada, UUID.randomUUID()) }
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
