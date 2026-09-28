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
 * It is worth being explicit about what does **not** protect this, because the
 * first version of this slice assumed it did: `@Version` on `BondEntity` does
 * not catch the second writer. `BondStore.update` re-reads the row inside this
 * transaction and the mapper deliberately leaves `version` to Hibernate, so the
 * UPDATE always carries the row's current version and the optimistic check has
 * nothing to compare. The column's job here is to *be* the `ETag`; the lock's
 * job is to make the check atomic. `BondSettingsRaceTest` is what proves it.
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
