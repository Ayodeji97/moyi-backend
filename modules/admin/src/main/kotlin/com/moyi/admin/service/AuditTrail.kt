package com.moyi.admin.service

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.infra.AuditLogEntity
import com.moyi.admin.infra.AuditLogRepository
import com.moyi.common.core.IdGenerator
import com.moyi.common.security.ClientAddress
import com.moyi.common.security.PersonalDataHasher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

/**
 * Writes the audit row (spec §8). `MANDATORY`: it joins its caller's
 * transaction or refuses to run, so an audited act and its record commit
 * together or not at all, and a lookup whose audit write fails never returns
 * its data.
 *
 * What goes in [metadata] is the caller's responsibility and the spec's rule:
 * never an email, a password, a code, a token, a session id or a search text.
 * The address is stored only as the keyed hash [PersonalDataHasher] gives.
 */
@Service
internal class AuditTrail(
    private val rows: AuditLogRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val hasher: PersonalDataHasher,
    private val json: JsonMapper,
) {
    // Eight parameters is the audit row's shape (spec §8); a parameter object would only move them.
    @Suppress("LongParameterList")
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        action: AuditAction,
        outcome: AuditOutcome,
        actorId: UUID?,
        targetType: String?,
        targetId: String?,
        justification: String? = null,
        metadata: Map<String, Any> = emptyMap(),
        clientAddress: ClientAddress?,
    ) {
        rows.save(
            AuditLogEntity(
                id = ids.timeOrdered(),
                occurredAt = clock.instant(),
                actorType = ACTOR_ADMIN,
                actorId = actorId,
                action = action,
                targetType = targetType,
                targetId = targetId,
                justification = justification,
                outcome = outcome,
                metadata = json.writeValueAsString(metadata),
                ipHash = clientAddress?.let { hasher.mac(it.hostAddress.toByteArray(Charsets.UTF_8)) },
            ),
        )
        // Written now, not at commit: a failed write then throws inside the audited method,
        // before it returns what it found (a lookup's data, a review copy's bytes).
        rows.flush()
    }

    private companion object {
        const val ACTOR_ADMIN = "ADMIN"
    }
}
