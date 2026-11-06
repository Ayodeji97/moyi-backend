package com.moyi.bond.service

import com.moyi.bond.api.BondAdminLookup
import com.moyi.bond.api.BondAdminView
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.BondStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The [BondAdminLookup] port. Scoped by the user's own id, like [ListBonds],
 * so there is no bond id to guard.
 */
@Service
internal class BondAdminLookupService(
    private val bonds: BondStore,
) : BondAdminLookup {
    @Transactional(readOnly = true)
    override fun bondsOf(userId: UUID): List<BondAdminView> =
        bonds.findAllByMember(UserId(userId)).map { bond ->
            BondAdminView(
                bondId = bond.id.value,
                status = bond.status.name,
                createdAt = bond.createdAt,
                endedAt = bond.archivedAt,
                partnerUserId =
                    bond.members
                        .firstOrNull { it.userId.value != userId }
                        ?.userId
                        ?.value,
            )
        }
}
