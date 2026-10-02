package com.moyi.bond.infra.database

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.Member
import com.moyi.bond.domain.UserId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * The Bond and its members, spoken in domain terms — the `AccountStore`
 * precedent. It keeps the mappers where they belong: a service deals in
 * domain objects and never imports a `toEntity`.
 *
 * **Invites live in [InviteStore], not here**, which is what the Phase 2
 * design said and what detekt insisted on when this class reached fourteen
 * methods in slice B2. The split is not arbitrary: an invite has a lifecycle
 * of its own — issued, revoked, replaced, spent — that no invariant of the
 * bond depends on, and the two are written together only at creation, in one
 * transaction the service owns.
 *
 * **Every read is scoped to a user** (doc 05 §5.5 layer 3): there is
 * deliberately no `find(bondId)` on this class. A caller who cannot say who
 * is asking cannot ask — which is the repository-level half of T-02's
 * defence, and the half that keeps working when somebody later writes a
 * service and forgets the guard.
 *
 * Not transactional itself: the boundary is the caller's (doc 18 §4), because
 * the interesting operations here have to sit inside one the caller opened —
 * the FR-025 count under its advisory lock, and B2's accept.
 */
@Component
internal class BondStore(
    private val bonds: BondRepository,
    private val members: BondMemberRepository,
    private val entityManager: EntityManager,
) {
    /** A new bond and its member rows. Its first invite is [InviteStore]'s, written in the same transaction. */
    fun insert(bond: Bond) {
        bonds.save(bond.toEntity())
        members.saveAll(bond.members.map { it.toEntity() })
    }

    /**
     * The bond if [userId] holds a membership row in it — current **or**
     * left — and `null` otherwise, which the guard turns into the 404 that
     * does not confirm the id exists (doc 06 §2).
     *
     * The member rows are read first because they *are* the authorisation:
     * the bond row is fetched only once the caller is known to be entitled to
     * see it, so a stranger's request never loads the object it is not allowed
     * to know about.
     */
    fun findByMember(
        bondId: BondId,
        userId: UserId,
    ): Bond? {
        val rows = members.findAllByBondId(bondId.value)
        if (rows.none { it.userId == userId.value }) return null
        return bonds.findById(bondId.value)?.toDomain(rows)
    }

    /**
     * Every bond the user holds a membership row in, newest first
     * (`GET /bonds`). Three queries regardless of how many bonds come back,
     * rather than one per bond.
     */
    fun findAllByMember(userId: UserId): List<Bond> {
        val mine = members.findAllByUserId(userId.value).map { it.bondId }.distinct()
        if (mine.isEmpty()) return emptyList()
        val rows = members.findAllByBondIdIn(mine).groupBy { it.bondId }
        return bonds
            .findAllByIdIn(mine)
            .map { it.toDomain(rows[it.getId()].orEmpty()) }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Must be called before [countOpenBondsOf] in the transaction that then
     * creates or joins — see [BondRepository.lockBondsOf] for the race it
     * closes.
     */
    fun lockBondsOf(userId: UserId) {
        bonds.lockBondsOf(userId.value)
    }

    /** FR-025's number: bonds this user is still in and can still be written to. */
    fun countOpenBondsOf(userId: UserId): Int = members.countByUserIdAndBondStatusIn(userId.value, OPEN_STATUSES).toInt()

    /**
     * The bond an invite points at, **not** scoped to a member — the one read
     * in this class that is not, and the exception needs its reason written
     * down.
     *
     * `POST /invites/{code}/accept` is answered by someone who is *not yet* a
     * member, so a membership predicate would refuse every legitimate join.
     * What authorises the read instead is the code: the caller presented a
     * live, unexpired, unspent invite, which is a capability this system
     * issued. The name says `ForInvite` so that a future caller reaching for
     * it without one has to explain themselves, and `BondAccessGuard` is
     * unaffected — it still goes through [findByMember].
     */
    fun findAnyForInvite(bondId: BondId): Bond? {
        val rows = members.findAllByBondId(bondId.value)
        return bonds.findById(bondId.value)?.toDomain(rows)
    }

    /**
     * Holds the bond's row until this transaction ends. Taken before reading
     * the state an accept decides on, so that two accepts of one code cannot
     * both see a free seat (slice B2).
     *
     * **[refreshReads]: the lock alone does not make a re-read fresh.** A
     * caller that ran the guard first has already loaded the bond and its
     * member rows into this transaction's persistence context, and Hibernate
     * answers a later `findById` from that identity map — and hands back the
     * *same managed instances*, old state and all, from a query — lock or no
     * lock. So a leave or an archive that committed while the caller queued on
     * `FOR UPDATE` is invisible to its "re-read under the lock" unless those
     * instances are refreshed from the row it now holds.
     * `SubmitEntryBondLockTest` (`gratitude`) found it: an entry committed onto
     * a bond that had ended while the submission waited. `BondDayStore.lockAndFind`
     * met the same trap for the same reason.
     *
     * `true` only for `BondAccess.lockMembershipOf` today. The other callers
     * all *write* the bond afterwards, so a stale read meets `@Version` as a
     * conflict rather than passing silently; whether they should refresh too
     * is a question for those callers, not settled here.
     */
    fun lockBond(
        bondId: BondId,
        refreshReads: Boolean = false,
    ) {
        bonds.lockRow(bondId.value)
        if (refreshReads) {
            bonds.findById(bondId.value)?.let(entityManager::refresh)
            members.findAllByBondId(bondId.value).forEach(entityManager::refresh)
        }
    }

    /**
     * The second member joins: the member row and the bond's new status,
     * written together. [bond] is the aggregate *after* `accept`, so the two
     * cannot disagree about what was decided.
     */
    fun addMember(
        bond: Bond,
        member: Member,
    ) {
        members.saveAll(listOf(member.toEntity()))
        val entity = bonds.findById(bond.id.value) ?: error("cannot add a member to a bond that does not exist")
        bond.applyTo(entity)
        bonds.save(entity)
    }

    /**
     * Writes a bond that has ended: the status and `archived_at` on the bond
     * row, and `left_at` on whichever member rows the aggregate now says have
     * left — together, so the two cannot disagree about what happened.
     *
     * [bond] is the aggregate *after* `leave` or `end`. Nothing is written for
     * a row that already matches: Hibernate's dirty check is what keeps
     * `version` — and therefore the `ETag` — still after a repeat block, which
     * doc 26 §2.1 requires (`BondPersistenceTest` holds it).
     */
    fun archive(bond: Bond) {
        val rows = members.findAllByBondId(bond.id.value).associateBy { it.getId() }
        bond.members.forEach { member -> rows[member.id.value]?.let(member::applyTo) }
        members.saveAll(rows.values)
        val entity = bonds.findById(bond.id.value) ?: error("cannot archive a bond that does not exist")
        bond.applyTo(entity)
        bonds.save(entity)
    }

    /**
     * Writes a settings change to the bond row. [bond] is the aggregate *after*
     * `update`.
     *
     * **This is not the layer that prevents a lost update, and it cannot be.**
     * It re-reads the row inside the caller's transaction and `applyTo` does not
     * copy `version` — which is right, the version is Hibernate's to increment —
     * so the UPDATE always carries the row's current version and the optimistic
     * check has nothing to catch. Hand this a stale aggregate and it will
     * happily overwrite newer values.
     *
     * The protection is one layer up: `UpdateBond` takes [lockBond] and then
     * compares `If-Match`, so the read, the check and the write are one
     * serialised decision (ADR-0029). `BondPersistenceTest` states this
     * explicitly so nobody rediscovers it the hard way.
     *
     * It does flush, for a different reason: `@Version` is incremented at flush,
     * and a caller that writes and then re-reads to build its response would
     * otherwise report the *old* version as the `ETag` — a value the client's
     * next `If-Match` would be refused with (see
     * [BondRepository.saveAndFlush]).
     */
    fun update(bond: Bond) {
        val entity = bonds.findById(bond.id.value) ?: error("cannot update a bond that does not exist")
        bond.applyTo(entity)
        bonds.saveAndFlush(entity)
    }

    private companion object {
        /**
         * FR-025 counts a bond waiting for its partner exactly as much as one
         * that has them — and one counting down to deletion as much again.
         *
         * `PENDING_DELETION` is in this list because the cooling-off is
         * cancellable: leaving it out let a user at the three-bond limit start a
         * deletion (freeing a slot), create a fourth bond, and then cancel the
         * deletion to hold four. Found by the review of PR #41. A bond you might
         * still get back is a bond you are in.
         */
        val OPEN_STATUSES = listOf(BondStatus.PENDING_MEMBER, BondStatus.ACTIVE, BondStatus.PENDING_DELETION)
    }
}
