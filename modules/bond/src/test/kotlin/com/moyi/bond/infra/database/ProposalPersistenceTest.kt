package com.moyi.bond.infra.database

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.ProposalKind
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * V10 and [ProposalStore] against a real Postgres.
 *
 * Three things are on trial that no unit test can reach: the partial unique
 * index that holds "one open proposal per kind", the conditional updates that
 * make confirming a compare-and-set, and the fact that **lapsing is a predicate
 * rather than a state** — which is convenient everywhere except that index, and
 * this is where that tension is pinned down (ADR-0030).
 */
@SpringBootTest(classes = [BondTestApplication::class])
internal class ProposalPersistenceTest(
    @Autowired private val proposals: ProposalStore,
    @Autowired private val bonds: BondStore,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val ids = DeterministicIdGenerator()
    private val now = Instant.parse("2026-09-28T20:00:00Z")
    private val lagos = RegionZone.of("Africa/Lagos")
    private val london = RegionZone.of("Europe/London")

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE bond_proposals, blocks, bond_invites, bond_members, bonds CASCADE")
    }

    private fun insertedBond(): Bond {
        val draft = BondDraft(UserId(ids.timeOrdered()), BondType.COUPLE, "Us", lagos, null, null)
        val bond = Bond.create(BondId(ids.timeOrdered()), MemberId(ids.timeOrdered()), draft, now)
        transactions.executeWithoutResult { bonds.insert(bond) }
        return bond
    }

    private fun timezoneProposal(
        bond: Bond,
        at: Instant = now,
    ): Proposal = Proposal.timezoneChange(ProposalId(ids.timeOrdered()), bond.id, london, bond.members.single().id, at)

    @Test
    fun `a proposal round-trips, and lapses by the clock rather than by a write`() {
        val bond = insertedBond()
        val proposal = timezoneProposal(bond)

        transactions.executeWithoutResult { proposals.insert(proposal) }

        val loaded = transactions.execute { proposals.findLive(bond.id, ProposalKind.TIMEZONE_CHANGE, now) }.shouldNotBeNull()
        loaded shouldBe proposal
        loaded.proposedZone() shouldBe london
        loaded.expiresAt shouldBe now.plus(Proposal.TTL)
        // Seven days and a second later it is gone from every read, and the row
        // has not been touched: the only thing that changed is the time.
        transactions
            .execute { proposals.findLive(bond.id, ProposalKind.TIMEZONE_CHANGE, now.plus(Proposal.TTL)) }
            .shouldBeNull()
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE cancelled_at IS NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `V10 permits one open proposal per kind and refuses a second`() {
        val bond = insertedBond()
        transactions.executeWithoutResult { proposals.insert(timezoneProposal(bond)) }

        shouldThrow<DataIntegrityViolationException> {
            transactions.executeWithoutResult { proposals.insert(timezoneProposal(bond)) }
        }

        // The other kind is a different slot, which is the point of keying the
        // index on `kind` rather than on the bond alone.
        transactions.executeWithoutResult {
            proposals.insert(Proposal.deletion(ProposalId(ids.timeOrdered()), bond.id, bond.members.single().id, now))
        }
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals", Int::class.java) shouldBe 2
    }

    @Test
    fun `a lapsed proposal still holds its slot until something closes it`() {
        // The tension ADR-0030 resolves: an index cannot ask what time it is, so
        // a proposal that every *read* ignores is still, to the database, the one
        // open proposal of its kind. Proposing therefore closes it first.
        val bond = insertedBond()
        val lapsed = timezoneProposal(bond, at = now.minus(Duration.ofDays(8)))
        transactions.executeWithoutResult { proposals.insert(lapsed) }

        transactions.execute { proposals.findLive(bond.id, ProposalKind.TIMEZONE_CHANGE, now) }.shouldBeNull()
        shouldThrow<DataIntegrityViolationException> {
            transactions.executeWithoutResult { proposals.insert(timezoneProposal(bond)) }
        }

        transactions.execute { proposals.closeLapsed(bond.id, ProposalKind.TIMEZONE_CHANGE, now) } shouldBe true
        transactions.executeWithoutResult { proposals.insert(timezoneProposal(bond)) }

        transactions.execute { proposals.findLive(bond.id, ProposalKind.TIMEZONE_CHANGE, now) }.shouldNotBeNull()
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE cancelled_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `closing lapsed proposals leaves a live one alone`() {
        val bond = insertedBond()
        transactions.executeWithoutResult { proposals.insert(timezoneProposal(bond)) }

        transactions.execute { proposals.closeLapsed(bond.id, ProposalKind.TIMEZONE_CHANGE, now) } shouldBe false

        transactions.execute { proposals.findLive(bond.id, ProposalKind.TIMEZONE_CHANGE, now) }.shouldNotBeNull()
    }

    @Test
    fun `confirming succeeds exactly once`() {
        val bond = insertedBond()
        val proposal = timezoneProposal(bond)
        val other = MemberId(ids.timeOrdered())
        transactions.executeWithoutResult { proposals.insert(proposal) }

        transactions.execute { proposals.confirm(proposal.id, bond.members.single().id, now) } shouldBe true
        transactions.execute { proposals.confirm(proposal.id, other, now) } shouldBe false

        val row = jdbc.queryForMap("SELECT confirmed_by_member_id, confirmed_at FROM bond_proposals")
        row["confirmed_by_member_id"] shouldBe
            bond.members
                .single()
                .id.value
        row["confirmed_at"].shouldNotBeNull()
        // And the slot is free again: a confirmed proposal is closed.
        transactions.executeWithoutResult { proposals.insert(timezoneProposal(bond)) }
    }

    @Test
    fun `a lapsed proposal cannot be confirmed, and can still be cancelled`() {
        val bond = insertedBond()
        val lapsed = timezoneProposal(bond, at = now.minus(Duration.ofDays(8)))
        transactions.executeWithoutResult { proposals.insert(lapsed) }

        transactions.execute { proposals.confirm(lapsed.id, bond.members.single().id, now) } shouldBe false
        // Cancelling has no `expires_at` predicate on purpose: the pending screen
        // may still be showing it, and "cancel" is the honest answer to that.
        transactions.execute { proposals.cancel(lapsed.id, now) } shouldBe true
        transactions.execute { proposals.cancel(lapsed.id, now) } shouldBe false
    }

    @Test
    fun `ending a bond cancels every live proposal and leaves the closed ones alone`() {
        // ADR-0028's obligation, which waited for this table to exist.
        val bond = insertedBond()
        val confirmed = timezoneProposal(bond, at = now.minus(Duration.ofDays(1)))
        transactions.executeWithoutResult {
            proposals.insert(confirmed)
            proposals.confirm(confirmed.id, bond.members.single().id, now)
            proposals.insert(Proposal.deletion(ProposalId(ids.timeOrdered()), bond.id, bond.members.single().id, now))
        }

        transactions.execute { proposals.cancelLiveOf(bond.id, now) } shouldBe 1

        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE cancelled_at IS NOT NULL", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals WHERE confirmed_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    @Test
    fun `V10 refuses a confirmation with no member, both endings at once, and an unknown kind`() {
        val bond = insertedBond()
        val member =
            bond.members
                .single()
                .id.value

        // Written as literal SQL rather than bound parameters: a bound `null`
        // with no type is what Postgres cannot infer, and the subject here is
        // the CHECK constraints rather than the driver.
        fun insert(
            confirmedBy: String,
            confirmedAt: String,
            cancelledAt: String,
            kind: String = "TIMEZONE_CHANGE",
            payload: String = "'Europe/London'",
            expires: String = "now() + interval '7 days'",
        ) = jdbc.update(
            """
            INSERT INTO bond_proposals
                (id, bond_id, kind, payload, proposed_by_member_id, proposed_at, expires_at,
                 confirmed_by_member_id, confirmed_at, cancelled_at)
            VALUES ('${ids.timeOrdered()}', '${bond.id.value}', '$kind', $payload, '$member',
                    now(), $expires, $confirmedBy, $confirmedAt, $cancelledAt)
            """.trimIndent(),
        )

        // A confirmation time with nobody attached to it.
        shouldThrow<DataIntegrityViolationException> { insert("NULL", "now()", "NULL") }
        // A member with no time is the same violation from the other side.
        shouldThrow<DataIntegrityViolationException> { insert("'$member'", "NULL", "NULL") }
        // Confirmed and cancelled at once: those are the two ways it ends, not both.
        shouldThrow<DataIntegrityViolationException> { insert("'$member'", "now()", "now()") }
        // A kind the enum does not have.
        shouldThrow<DataIntegrityViolationException> { insert("NULL", "NULL", "NULL", kind = "RENAME") }
        // A timezone proposal with no zone in it. Added after the review of PR
        // #41: the domain type's `require` was the only guard, and a row
        // inserted around it makes `GET /bonds` a 500 for both members, because
        // the mapper throws inside the response assembler.
        shouldThrow<DataIntegrityViolationException> { insert("NULL", "NULL", "NULL", payload = "NULL") }
        // An expiry before the proposal.
        shouldThrow<DataIntegrityViolationException> { insert("NULL", "NULL", "NULL", expires = "now() - interval '1 day'") }

        jdbc.queryForObject("SELECT count(*) FROM bond_proposals", Int::class.java) shouldBe 0
    }
}
