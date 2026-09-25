package com.moyi.bond.infra.database

import com.moyi.bond.domain.BondStatus
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.UUID

/**
 * Bonds.
 *
 * On Spring Data's bare [Repository] marker rather than `JpaRepository`, for
 * the reason `CredentialsRepository` gives: **what a repository cannot do is
 * part of its design.** `JpaRepository` would inherit `findAll()`,
 * `getReferenceById()` and a dozen more, each of them a supported way to pull
 * every bond in the system into memory. Declaring the handful of methods this
 * module actually makes is what keeps doc 05 §5.5's third layer true — there
 * is no query here that returns bond-scoped data without a bond id or a user
 * id in its predicate, and [BondStore] is the only caller of any of them.
 */
internal interface BondRepository : Repository<BondEntity, UUID> {
    fun save(bond: BondEntity): BondEntity

    fun findById(id: UUID): BondEntity?

    fun findAllByIdIn(ids: Collection<UUID>): List<BondEntity>

    /**
     * Serialises "count my open bonds, then create one" for the rest of the
     * current transaction, and is the reason FR-025's limit cannot be raced.
     *
     * Without it the count and the insert are two statements over a READ
     * COMMITTED snapshot: two concurrent creates both see two bonds, both
     * decide there is room, and the user ends with four. The same shape as the
     * refresh-token race Codex found on PR #32, and the same answer —
     * a transaction-scoped advisory lock keyed on the user, released
     * automatically at commit or rollback.
     *
     * Namespace `2` in the two-key form; identity's sessions lock is `1`, so
     * the two cannot collide by accident.
     */
    @Query(nativeQuery = true, value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(2, hashtext(CAST(:userId AS text)))) AS held")
    fun lockBondsOf(userId: UUID): Int
}

/** Memberships. Every finder is scoped by bond or by user — there is no "all members" query. */
internal interface BondMemberRepository : Repository<BondMemberEntity, UUID> {
    fun saveAll(members: Iterable<BondMemberEntity>): List<BondMemberEntity>

    fun findAllByBondId(bondId: UUID): List<BondMemberEntity>

    fun findAllByBondIdIn(bondIds: Collection<UUID>): List<BondMemberEntity>

    fun findAllByUserId(userId: UUID): List<BondMemberEntity>

    /**
     * FR-025's count: memberships this user has not left, in bonds whose
     * status is one of [statuses].
     *
     * Counted in the database rather than by loading the bonds and filtering
     * in Kotlin, because the answer is a number and the rows are nobody's
     * business here.
     */
    @Query(
        """
        SELECT COUNT(m) FROM BondMemberEntity m, BondEntity b
         WHERE b.id = m.bondId
           AND m.userId = :userId
           AND m.leftAt IS NULL
           AND b.status IN :statuses
        """,
    )
    fun countByUserIdAndBondStatusIn(
        userId: UUID,
        statuses: Collection<BondStatus>,
    ): Long
}

/** Invites. */
internal interface BondInviteRepository : Repository<BondInviteEntity, UUID> {
    fun save(invite: BondInviteEntity): BondInviteEntity

    /**
     * The live invite of each listed bond — at most one per bond once slice B2
     * enforces "a new invite revokes the outstanding one" (doc 06 §3.3).
     *
     * Batched over a list rather than called per bond: `GET /bonds` returns up
     * to three bonds and this is the query that would otherwise be the N+1.
     */
    @Query(
        """
        SELECT i FROM BondInviteEntity i
         WHERE i.bondId IN :bondIds
           AND i.usedAt IS NULL
           AND i.revokedAt IS NULL
           AND i.expiresAt > :now
        """,
    )
    fun findAllLiveByBondIdIn(
        bondIds: Collection<UUID>,
        now: Instant,
    ): List<BondInviteEntity>
}
