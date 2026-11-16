package com.moyi.bond.infra.database

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondDraft
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.Member
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.Proposal
import com.moyi.bond.domain.ProposalId
import com.moyi.bond.domain.ProposalKind
import com.moyi.bond.domain.ProposalOutcome
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.BondTestApplication
import com.moyi.common.testing.DeterministicIdGenerator
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import java.util.UUID
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
        val member = bond.members.single().id
        transactions.execute { proposals.cancel(lapsed.id, member, now) } shouldBe true
        transactions.execute { proposals.cancel(lapsed.id, member, now) } shouldBe false
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

    /** A bond with both members in it: who called a proposal off is one of two people. */
    private fun pairedBond(): Pair<Bond, Member> {
        val bond = insertedBond()
        val joiner = Member.member(MemberId(ids.timeOrdered()), bond.id, UserId(UUID.randomUUID()), lagos, now)
        transactions.executeWithoutResult { bonds.addMember(bond.accept(joiner), joiner) }
        return bond to joiner
    }

    private fun reread(proposal: Proposal): Proposal =
        transactions.execute { proposals.findInBond(proposal.bondId, proposal.id) }.shouldNotBeNull()

    private fun storedEnding(proposal: Proposal): Pair<Any?, Any?> =
        jdbc
            .queryForMap("SELECT cancel_cause, cancelled_by_member_id FROM bond_proposals WHERE id = ?", proposal.id.value)
            .let { it["cancel_cause"] to it["cancelled_by_member_id"] }

    @Test
    fun `a member's cancel is recorded as theirs, the proposer's a withdrawal, the other's a refusal`() {
        val (bond, joiner) = pairedBond()
        val proposer = bond.members.single().id
        val withdrawn = timezoneProposal(bond)
        transactions.executeWithoutResult { proposals.insert(withdrawn) }
        transactions.execute { proposals.cancel(withdrawn.id, proposer, now) } shouldBe true
        val declined = timezoneProposal(bond, at = now.plusSeconds(1))
        transactions.executeWithoutResult { proposals.insert(declined) }
        transactions.execute { proposals.cancel(declined.id, joiner.id, now.plusSeconds(2)) } shouldBe true

        storedEnding(withdrawn) shouldBe ("MEMBER" to proposer.value)
        reread(withdrawn).outcome shouldBe ProposalOutcome.WITHDRAWN
        storedEnding(declined) shouldBe ("MEMBER" to joiner.id.value)
        reread(declined).outcome shouldBe ProposalOutcome.DECLINED
        reread(declined).closedBy shouldBe joiner.id
    }

    @Test
    fun `a cancel that reaches a lapsed row records a lapse and names nobody`() {
        // C5: the same history V70's backfill reads as LAPSED is never written as a refusal.
        val (bond, joiner) = pairedBond()
        val lapsed = timezoneProposal(bond, at = now.minus(Duration.ofDays(8)))
        transactions.executeWithoutResult { proposals.insert(lapsed) }

        transactions.execute { proposals.cancel(lapsed.id, joiner.id, now) } shouldBe true

        storedEnding(lapsed) shouldBe ("LAPSED" to null)
        reread(lapsed).outcome shouldBe ProposalOutcome.LAPSED
    }

    @Test
    fun `closing a lapsed row records a lapse, naming nobody`() {
        val bond = insertedBond()
        val lapsed = timezoneProposal(bond, at = now.minus(Duration.ofDays(8)))
        transactions.executeWithoutResult { proposals.insert(lapsed) }

        transactions.execute { proposals.closeLapsed(bond.id, ProposalKind.TIMEZONE_CHANGE, now) } shouldBe true

        storedEnding(lapsed) shouldBe ("LAPSED" to null)
    }

    @Test
    fun `a bond ending records BOND_ENDED and names no member, a lapsed row a lapse`() {
        // T-09: whoever's leave or block it was, nobody is recorded as having called it off.
        val (bond, _) = pairedBond()
        val proposer = bond.members.single().id
        val open = Proposal.deletion(ProposalId(ids.timeOrdered()), bond.id, proposer, now)
        val lapsed = timezoneProposal(bond, at = now.minus(Duration.ofDays(8)))
        transactions.executeWithoutResult {
            proposals.insert(open)
            proposals.insert(lapsed)
        }

        transactions.execute { proposals.cancelLiveOf(bond.id, now) } shouldBe 2

        storedEnding(open) shouldBe ("BOND_ENDED" to null)
        reread(open).outcome shouldBe ProposalOutcome.BOND_ENDED
        reread(open).closedBy.shouldBeNull()
        storedEnding(lapsed) shouldBe ("LAPSED" to null)
    }

    @Test
    fun `turning search off records the request as called off by the member who did it`() {
        val (bond, joiner) = pairedBond()
        val proposer = bond.members.single().id
        val asked = Proposal.semanticSearchOn(ProposalId(ids.timeOrdered()), bond.id, proposer, now)
        val zone = timezoneProposal(bond)
        transactions.executeWithoutResult {
            proposals.insert(asked)
            proposals.insert(zone)
        }

        transactions.execute { proposals.cancelLive(bond.id, ProposalKind.SEMANTIC_SEARCH_ON, joiner.id, now) } shouldBe 1

        storedEnding(asked) shouldBe ("MEMBER" to joiner.id.value)
        reread(asked).outcome shouldBe ProposalOutcome.DECLINED
        // The other kind is untouched: still open, no cause.
        storedEnding(zone) shouldBe (null to null)
    }

    @Test
    fun `the latest ended proposal of each kind, an unclosed lapse included, and never a live one`() {
        val (bond, joiner) = pairedBond()
        val proposer = bond.members.single().id
        val day = Duration.ofDays(1)
        // Timezone: an older acceptance, then a newer refusal. The refusal is the latest.
        val older = timezoneProposal(bond, at = now.minus(day.multipliedBy(20)))
        val newer = timezoneProposal(bond, at = now.minus(day.multipliedBy(10)))
        // Deletion: one lapsed and never closed, so its ending is its expiry.
        val unclosed = Proposal.deletion(ProposalId(ids.timeOrdered()), bond.id, proposer, now.minus(day.multipliedBy(9)))
        // Semantic search: live, so there is no ended one of that kind to return.
        val live = Proposal.semanticSearchOn(ProposalId(ids.timeOrdered()), bond.id, proposer, now)
        transactions.executeWithoutResult {
            proposals.insert(older)
            proposals.confirm(older.id, joiner.id, now.minus(day.multipliedBy(19)))
            proposals.insert(newer)
            proposals.cancel(newer.id, joiner.id, now.minus(day.multipliedBy(9)))
            proposals.insert(unclosed)
            proposals.insert(live)
        }
        val other = insertedBond()

        val ended = transactions.execute { proposals.findLatestEndedOf(listOf(bond.id, other.id), now) }.shouldNotBeNull()

        ended.keys shouldBe setOf(bond.id)
        ended.getValue(bond.id).map { it.id to it.outcome } shouldContainExactlyInAnyOrder
            listOf(newer.id to ProposalOutcome.DECLINED, unclosed.id to ProposalOutcome.LAPSED)
        // A second before it lapses, the unclosed deletion is still live and is not returned.
        transactions
            .execute { proposals.findLatestEndedOf(listOf(bond.id), unclosed.expiresAt.minusSeconds(1)) }
            .shouldNotBeNull()
            .getValue(bond.id)
            .map { it.id } shouldBe listOf(newer.id)
        transactions
            .execute { proposals.findLatestEndedOf(listOf(other.id), now) }
            .shouldNotBeNull()
            .keys
            .shouldBeEmpty()
    }

    @Test
    fun `findInBond answers only for the proposal's own bond`() {
        val bond = insertedBond()
        val proposal = timezoneProposal(bond)
        transactions.executeWithoutResult { proposals.insert(proposal) }

        transactions.execute { proposals.findInBond(bond.id, proposal.id) } shouldBe proposal
        transactions.execute { proposals.findInBond(insertedBond().id, proposal.id) }.shouldBeNull()
    }

    @Test
    fun `V70 refuses a cause on an open row, a member beside a bond ending, and a member cancel naming nobody`() {
        val (bond, joiner) = pairedBond()

        fun insert(
            cause: String,
            by: String,
            cancelledAt: String = "now()",
        ) = jdbc.update(
            """
            INSERT INTO bond_proposals
                (id, bond_id, kind, payload, proposed_by_member_id, proposed_at, expires_at,
                 cancelled_at, cancel_cause, cancelled_by_member_id)
            VALUES ('${ids.timeOrdered()}', '${bond.id.value}', 'DELETION', NULL, '${joiner.id.value}',
                    now(), now() + interval '7 days', $cancelledAt, $cause, $by)
            """.trimIndent(),
        )

        shouldThrow<DataIntegrityViolationException> { insert("'LAPSED'", "NULL", cancelledAt = "NULL") }
        shouldThrow<DataIntegrityViolationException> { insert("'BOND_ENDED'", "'${joiner.id.value}'") }
        shouldThrow<DataIntegrityViolationException> { insert("'MEMBER'", "NULL") }
        shouldThrow<DataIntegrityViolationException> { insert("NULL", "'${joiner.id.value}'") }
        shouldThrow<DataIntegrityViolationException> { insert("'DECLINED'", "NULL") }
        jdbc.queryForObject("SELECT count(*) FROM bond_proposals", Int::class.java) shouldBe 0
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
