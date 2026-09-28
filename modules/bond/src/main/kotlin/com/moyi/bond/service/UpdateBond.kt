package com.moyi.bond.service

import com.moyi.bond.domain.BondSettings
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BondStore
import com.moyi.common.web.IfMatch
import com.moyi.common.web.PreconditionFailedException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `PATCH /bonds/{bondId}` (doc 06 §1 and §3.3, ADR-0029).
 *
 * **The lock is what makes the `If-Match` check mean anything.** Comparing a
 * version and then writing is a read-check-write, and over a READ COMMITTED
 * snapshot two members can both read version 0, both find their `If-Match`
 * satisfied, and both write — the second silently erasing the first, with a
 * reassuring `200` for each. Doc 06 §1 requires the condition precisely to
 * prevent that, so the whole decision happens under the bond's row lock, the
 * same lock `AcceptInvite`, `CreateInvite` and `EndBond` take.
 *
 * **What `@Version` does and does not do here**, because the first version of
 * this slice got it wrong in both directions and the tests corrected it twice:
 *
 * - It cannot catch a *stale aggregate* handed to the store in a later
 *   transaction. `BondStore.update` re-reads the row and the mapper leaves
 *   `version` to Hibernate, so the UPDATE carries whatever the row currently
 *   holds and there is nothing to compare (`BondPersistenceTest` says so
 *   outright).
 * - Under genuine concurrency it *does* fire: two transactions each load
 *   version 0, the first commits 1, and the second's UPDATE finds no row at
 *   version 0. Removing the lock and running `BondSettingsRaceTest` shows it —
 *   as a **500**, because an optimistic-lock failure is not an answer any
 *   client asked for.
 *
 * So the column's job is to *be* the `ETag`, Hibernate's check is a backstop
 * against corruption, and **the lock is what turns a race into the `412` this
 * endpoint promises** rather than a server error. All three are doing something
 * different, which is why all three are here.
 */
@Service
internal class UpdateBond(
    private val bonds: BondStore,
    private val views: BondViews,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws BondNotFoundException the caller's membership went away between the guard and here
     * @throws BondArchivedException the bond has ended (BR-9, ADR-0028)
     * @throws PreconditionFailedException the caller's `If-Match` is not the current version
     */
    @Transactional
    @Suppress("ThrowsCount")
    fun patch(
        membership: Membership,
        settings: BondSettings,
        ifMatch: IfMatch,
    ): BondView {
        bonds.lockBond(membership.bondId)
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        // Archived before the precondition: a member of a bond that has ended
        // gets the same `409` whatever version they hold, because "this has
        // ended" is the more useful answer and it is true regardless of theirs.
        if (!bond.isOpen) throw BondArchivedException()
        if (!ifMatch.matches(bond.version)) throw PreconditionFailedException()

        bonds.update(bond.update(settings))
        log.info("Bond {} settings updated", membership.bondId.value)
        // Re-read, so the `ETag` carries the version the row now has rather than
        // the one this object was loaded with. Returning the stale one would
        // hand the client a value `If-Match` immediately rejects — the defect
        // the review of PR #38 found on accept.
        val persisted = bonds.findByMember(bond.id, membership.userId) ?: error("the bond just updated is gone")
        return views.of(persisted, membership.userId)
    }
}
