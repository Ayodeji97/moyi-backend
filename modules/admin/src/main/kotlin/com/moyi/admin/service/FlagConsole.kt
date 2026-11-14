package com.moyi.admin.service

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.infra.FeatureFlagStore
import com.moyi.admin.infra.FlagState
import com.moyi.common.core.FeatureFlag
import com.moyi.common.security.ClientAddress
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The feature flags page (FR-115, ADR-0067 decisions 7 to 11): an `ADMIN` switches a flag with a
 * justification; the change and its `FEATURE_FLAG_CHANGED` row commit together. A change of one
 * flag waits for any other change of it ([FeatureFlagStore.lockAndRead]), so its `from` is always
 * what it replaced, and switching a flag to the value it has is `UNCHANGED`, refused, and writes
 * nothing to the flag.
 */
@Service
@ConditionalOnAdminConsole
internal class FlagConsole(
    private val store: FeatureFlagStore,
    private val audit: AuditTrail,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    fun states(): List<FlagState> = store.states()

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    fun set(
        flag: FeatureFlag,
        enabled: Boolean,
        justification: String,
        adminId: UUID,
        from: ClientAddress?,
    ): FlagChange {
        val checked = ModerationNote.require(justification)
        val was = store.lockAndRead(flag)
        val change = if (was == enabled) FlagChange.UNCHANGED else FlagChange.CHANGED
        if (change == FlagChange.CHANGED) store.write(flag, enabled, clock.instant().truncatedTo(ChronoUnit.MICROS), adminId)
        audit.record(
            action = AuditAction.FEATURE_FLAG_CHANGED,
            outcome = if (change == FlagChange.CHANGED) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED,
            actorId = adminId,
            targetType = FLAG,
            targetId = flag.name,
            justification = checked.text,
            metadata = linkedMapOf("result" to change.name, "from" to was, "to" to enabled),
            clientAddress = from,
        )
        meters.counter(METRIC, "flag", flag.name, "result", change.name).increment()
        log.info("feature flag admin={} flag={} from={} to={} result={}", adminId, flag, was, enabled, change)
        return change
    }

    private companion object {
        const val FLAG = "FLAG"
        const val METRIC = "moyi.admin.flag.change"
    }
}

internal enum class FlagChange {
    CHANGED,

    /** The flag already had that value: nothing was written to it. */
    UNCHANGED,
}
