package com.moyi.bond.service

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.Membership
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.BondStore
import com.moyi.common.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Doc 05 §5.5 layer 2, doc 09 §4: **the** place a caller's membership of a
 * bond is decided.
 *
 * Every bond-scoped controller method calls this first and passes the
 * [Membership] it returns to the service; every bond-scoped service function
 * takes a `Membership` and never a bare [BondId]. Two Konsist rules in `app`
 * hold the halves the type system cannot — that nothing else constructs a
 * `Membership`, and that no service function names a bond without one.
 *
 * **A non-member, an unknown id and a malformed id are one answer**: 404,
 * doc 06 §2's "not found **or not permitted to know it exists**". A 403 would
 * confirm that the bond is real, which is precisely the confirmation T-02
 * exists to deny — and it would do so to the one person who must not have it,
 * someone probing for a bond that is not theirs.
 *
 * A member who has **left** still gets a membership, flagged [Membership.left]:
 * `states.md` §9 keeps the archive readable for both members after a bond
 * ends, so the answer to "are you in this bond" stays yes, and it is the write
 * paths (slices B3–B5) that refuse them.
 */
@Service
internal class BondAccessGuard(
    private val bonds: BondStore,
) {
    /** @throws BondNotFoundException the caller holds no membership row in [bondId], or there is no such bond */
    @Transactional(readOnly = true)
    fun membershipOf(
        caller: UserId,
        bondId: BondId,
    ): Membership {
        // The store's read is already scoped to the caller — there is no way
        // to ask it for a bond without saying who is asking — so this is one
        // query, not a fetch followed by a check somebody could reorder.
        val bond = bonds.findByMember(bondId, caller) ?: throw BondNotFoundException()
        val member = bond.memberOf(caller) ?: throw BondNotFoundException()
        return Membership(
            bondId = bond.id,
            memberId = member.id,
            userId = member.userId,
            role = member.role,
            left = !member.isActive,
        )
    }
}

/**
 * One answer for "not yours", "not real" and "not an id" (doc 06 §2).
 *
 * Through the shared [NotFoundException], so the catch-all in `common:web`
 * writes the 404 and this module needs no advice of its own. The detail names
 * the kind of thing and never the id — the id is already in `instance`, as the
 * path the caller typed, which is theirs to see.
 */
internal class BondNotFoundException : NotFoundException("That bond was not found.")
