package com.moyi.admin.service

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.bond.api.BondAdminLookup
import com.moyi.bond.api.BondAdminView
import com.moyi.common.security.ClientAddress
import com.moyi.identity.api.UserAdminLookup
import com.moyi.identity.api.UserAdminView
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** What a lookup found, or that nothing matched. */
internal sealed interface LookupResult {
    data class Found(
        val user: UserAdminView,
        val bonds: List<BondAdminView>,
    ) : LookupResult

    data object NotFound : LookupResult
}

/**
 * The user lookup (spec §9, FR-111): an exact id or an exact email, metadata only. The audit
 * row is written in this transaction before the result is returned, so a lookup whose audit
 * write fails throws and shows no data (T-10). `SUPPORT` and above, through the role
 * hierarchy. Moves `moyi.admin.lookup{outcome}` (Prometheus: `moyi_admin_lookup_total`).
 *
 * The audit row never holds the query: a hit records the user id, a miss records only which
 * kind of query it was.
 */
@Service
@ConditionalOnAdminConsole
internal class UserLookup(
    private val users: UserAdminLookup,
    private val bonds: BondAdminLookup,
    private val audit: AuditTrail,
    meters: MeterRegistry,
) {
    private val found = meters.counter(METRIC, "outcome", "found")
    private val notFound = meters.counter(METRIC, "outcome", "not_found")

    @PreAuthorize("hasRole('SUPPORT')")
    @Transactional
    fun find(
        query: String,
        actorId: UUID,
        from: ClientAddress?,
    ): LookupResult {
        val text = query.trim()
        val id = asUuid(text)
        val user = if (id != null) users.byId(id) else users.byEmail(text)
        val kind = if (id != null) "id" else "email"
        val result =
            if (user == null) {
                audit.record(
                    action = AuditAction.USER_LOOKED_UP,
                    outcome = AuditOutcome.REFUSED,
                    actorId = actorId,
                    targetType = null,
                    targetId = null,
                    metadata = mapOf("by" to kind),
                    clientAddress = from,
                )
                LookupResult.NotFound
            } else {
                audit.record(
                    action = AuditAction.USER_LOOKED_UP,
                    outcome = AuditOutcome.SUCCEEDED,
                    actorId = actorId,
                    targetType = "USER",
                    targetId = user.id.toString(),
                    metadata = mapOf("by" to kind),
                    clientAddress = from,
                )
                LookupResult.Found(user, bonds.bondsOf(user.id))
            }
        (if (user == null) notFound else found).increment()
        return result
    }

    /** Only the canonical text of a UUID: `UUID.fromString` alone also accepts "1-1-1-1-1". */
    private fun asUuid(text: String): UUID? =
        runCatching { UUID.fromString(text) }.getOrNull()?.takeIf { it.toString() == text.lowercase() }

    private companion object {
        const val METRIC = "moyi.admin.lookup"
    }
}
