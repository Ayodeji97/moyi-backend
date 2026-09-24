package com.moyi.bond.service

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.domain.Member
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.BlockStore
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.InviteStore
import com.moyi.common.core.IdGenerator
import com.moyi.identity.api.UserDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * `POST /invites/{code}/accept` (FR-022) — **milestone M2**: the moment two
 * accounts become a bond.
 *
 * One transaction, and the order of the checks is the design. It runs:
 *
 * 1. **Is the caller verified?** (FR-002) Their own state, refused by name,
 *    and first because it costs nothing and an unverified account should not
 *    reach any of the rest.
 * 2. **Is the code live?** If not, the one 404 — and nothing below has run,
 *    so a stranger guessing codes has learnt only that.
 * 3. **Lock the bond's row.** Everything after this reads state that another
 *    accept could otherwise change underneath it.
 * 4. **Is the caller already in it?** Their own membership, refused by name;
 *    the creator scanning their own code is the ordinary case.
 * 5. **Are they at the three-bond limit?** (FR-025) Their own count, under
 *    the same per-user lock `CreateBond` takes, so the two cannot race.
 * 6. **Does the bond have a seat?** The one 404 — a full bond and a revoked
 *    code are indistinguishable by design.
 * 7. **Is there a block either way?** (FR-029) The one 404, for the same
 *    reason and more urgently: the blocked party must not be able to tell
 *    that they were blocked rather than simply given a dead code (doc 26
 *    §2.1, T-09).
 * 8. **Spend the code**, compare-and-set. Losing that race is the one 404
 *    too, because from the loser's side the code was used — which it was.
 *
 * Steps 2, 6, 7 and 8 are deliberately one answer (FR-024). Steps 1, 4 and 5
 * are specific because they are facts about the caller's own account, which
 * they can already discover.
 */
@Service
internal class AcceptInvite(
    private val invites: InviteStore,
    private val bonds: BondStore,
    private val blocks: BlockStore,
    private val users: UserDirectory,
    private val views: BondViews,
    private val support: AcceptSupport,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws EmailNotVerifiedException the caller has not confirmed their address (FR-002)
     * @throws AlreadyMemberException the caller is already in this bond
     * @throws BondLimitReachedException the caller is already in three (FR-025)
     * @throws InviteNotUsableException every other refusal (FR-024)
     */
    @Transactional
    // Eight guard clauses, and the *order* of them is the security design —
    // see the class KDoc. Collapsing them to satisfy the rule would hide the
    // one property this function has to have: that nothing below a refusal
    // has run, so a stranger guessing codes learns only what the earliest
    // failing check tells them. The `ApplyPasswordReset` precedent.
    @Suppress("ThrowsCount")
    fun accept(
        caller: UserId,
        code: InviteCode,
    ): BondView {
        val now = support.clock.instant()
        val joiner = users.find(caller.value)
        if (joiner == null || !joiner.emailVerified) throw EmailNotVerifiedException()

        val invite = invites.findLiveByCode(code, now) ?: throw InviteNotUsableException()
        bonds.lockBond(invite.bondId)
        val bond = bonds.findAnyForInvite(invite.bondId) ?: throw InviteNotUsableException()

        if (bond.memberOf(caller)?.isActive == true) throw AlreadyMemberException()
        bonds.lockBondsOf(caller)
        if (bonds.countOpenBondsOf(caller) >= Bond.MAX_OPEN_BONDS_PER_USER) throw BondLimitReachedException()
        if (!bond.hasRoom) throw InviteNotUsableException()
        if (blocks.existsBetween(caller, bonds.memberUserIdsEverOf(bond.id))) throw InviteNotUsableException()

        if (!invites.consume(invite.id, caller, now)) throw InviteNotUsableException()

        val member = Member.member(MemberId(support.ids.timeOrdered()), bond.id, caller, bond.anchorTimezone, now)
        val joined = bond.accept(member)
        bonds.addMember(joined, member)
        log.info("User {} joined bond {}", caller.value, bond.id.value)
        return views.of(joined, caller)
    }
}

/**
 * The two ports this service needs beyond its collaborators, behind one
 * dependency — detekt's constructor limit is six, and a clock and an id
 * generator are the least interesting six-and-seventh arguments imaginable.
 */
@Service
internal class AcceptSupport(
    val clock: Clock,
    val ids: IdGenerator,
)
