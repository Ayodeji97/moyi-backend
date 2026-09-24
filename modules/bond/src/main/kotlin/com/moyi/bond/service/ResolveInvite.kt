package com.moyi.bond.service

import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.InviteStore
import com.moyi.identity.api.UserDirectory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * `GET /invites/{code}` (doc 06 §3.3): who you would be joining, before you
 * spend the invite.
 *
 * **Why this screen exists.** An invite is single-use (FR-023), so accepting
 * is destructive of it: a mistyped-but-valid code would silently consume
 * itself and bond someone to a stranger, with the real invite now spent. The
 * confirm step is what makes that recoverable (`states.md` §2).
 *
 * **Why showing a name here is safe**, when doc 06 calls an unauthenticated
 * version of this endpoint an oracle: the caller is already authenticated
 * *and* holds a live code, which is the same bar doc 06 sets for the endpoint
 * itself. What made the unauthenticated form dangerous was that anybody
 * guessing codes got a real person's name on a hit (T-06); here they must
 * also have an account, and the per-IP bucket bounds the guessing.
 *
 * It applies only the checks that are about the code and the bond — not the
 * caller's own state. Verification and the three-bond limit are refusals for
 * `accept` to make, because telling somebody they cannot join before they
 * have decided to is worse copy and no safer.
 */
@Service
internal class ResolveInvite(
    private val invites: InviteStore,
    private val bonds: BondStore,
    private val users: UserDirectory,
    private val clock: Clock,
) {
    /** @throws InviteNotUsableException for every way the code might not work (FR-024) */
    @Transactional(readOnly = true)
    // Three guard clauses, all throwing the *same* exception on purpose:
    // that is FR-024's one answer, and the rule counts statements rather than
    // outcomes.
    @Suppress("ThrowsCount")
    fun resolve(code: InviteCode): InvitePreview {
        val now = clock.instant()
        val invite = invites.findLiveByCode(code, now) ?: throw InviteNotUsableException()
        val bond = bonds.findAnyForInvite(invite.bondId) ?: throw InviteNotUsableException()
        if (!bond.hasRoom) throw InviteNotUsableException()

        val inviter = bond.members.firstOrNull { it.id == invite.createdByMemberId }
        return InvitePreview(
            bondName = bond.name,
            bondType = bond.type,
            // The member who made the invite, not the bond's creator — the
            // same person today, and not once a bond can be re-invited into.
            inviterDisplayName = inviter?.let { users.find(it.userId.value)?.displayName } ?: UNKNOWN_INVITER,
        )
    }

    private companion object {
        /** An inviter identity no longer knows. The bond is still joinable; the name is simply gone. */
        const val UNKNOWN_INVITER = "Someone"
    }
}

/** What the confirm screen shows before anything is spent (`states.md` §2). */
internal data class InvitePreview(
    val bondName: String,
    val bondType: BondType,
    val inviterDisplayName: String,
)
