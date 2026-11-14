package com.moyi.admin.service

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.common.security.ClientAddress
import com.moyi.identity.api.AccountSuspension
import com.moyi.identity.api.SuspensionOutcome
import com.moyi.identity.api.SuspensionResult
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The one door in the console to suspending a member's account and lifting the suspension (A3
 * spec §5, §6, FR-113): an `ADMIN`, with a typed justification and a code proved in this request
 * ([FreshStepUp]). The act and its `ACCOUNT_SUSPENDED` or `ACCOUNT_UNSUSPENDED` row commit in the
 * same transaction, or neither does.
 *
 * In order, each before the next: the role (`@PreAuthorize`, before any of this runs); the
 * justification (`ModerationNote`'s rule, [InvalidModerationNote] before the port); the proof is
 * this admin's; then the port, then the audit row. The proof was made, and its `ADMIN_STEP_UP`
 * row committed, by the caller before this transaction began, so a failed act never un-spends
 * the code. This never steps up itself and never looks at the session's window.
 *
 * Every answer of the port is audited, the refusals as `REFUSED`: only [SuspensionOutcome.SUSPENDED]
 * and [SuspensionOutcome.LIFTED] changed anything. The row's metadata is the outcome, the status
 * the account had (when it has one) and whether a deletion is pending; never the justification,
 * which is the row's `justification` and nothing else, and is in no log line, metric tag or
 * exception message either.
 */
@Service
@ConditionalOnAdminConsole
internal class SuspensionConsole(
    private val accounts: AccountSuspension,
    private val audit: AuditTrail,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("LongParameterList") // The act's target and note, the proof, and who asked from where.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    fun suspend(
        userId: UUID,
        justification: String,
        stepUp: FreshStepUp,
        adminId: UUID,
        from: ClientAddress?,
    ): SuspensionResult =
        act(Act.SUSPEND, userId, justification, stepUp, adminId, from) { accounts.suspend(userId) }

    @Suppress("LongParameterList") // The act's target and note, the proof, and who asked from where.
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    fun lift(
        userId: UUID,
        justification: String,
        stepUp: FreshStepUp,
        adminId: UUID,
        from: ClientAddress?,
    ): SuspensionResult =
        act(Act.LIFT, userId, justification, stepUp, adminId, from) { accounts.lift(userId) }

    @Suppress("LongParameterList") // Both acts' shared inputs and the port call itself.
    private fun act(
        act: Act,
        userId: UUID,
        justification: String,
        stepUp: FreshStepUp,
        adminId: UUID,
        from: ClientAddress?,
        port: () -> SuspensionResult,
    ): SuspensionResult {
        val checked = ModerationNote.require(justification)
        require(stepUp.adminId == adminId) { "The step-up was proved by another admin." }
        val result = port()
        val known = result.outcome != SuspensionOutcome.NO_SUCH_ACCOUNT
        val metadata = linkedMapOf<String, Any>("result" to result.outcome.name)
        result.statusBefore?.let { metadata["statusBefore"] = it.name }
        metadata["deletionPending"] = result.deletionDueAt != null
        audit.record(
            action = act.action,
            outcome = if (result.outcome in COMPLETED) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED,
            actorId = adminId,
            targetType = if (known) USER else null,
            targetId = if (known) userId.toString() else null,
            justification = checked.text,
            metadata = metadata,
            clientAddress = from,
        )
        meters.counter(METRIC, "action", act.tag, "result", result.outcome.name).increment()
        log.info("suspension admin={} user={} result={}", adminId, userId, result.outcome)
        return result
    }

    private enum class Act(
        val action: AuditAction,
        val tag: String,
    ) {
        SUSPEND(AuditAction.ACCOUNT_SUSPENDED, "suspend"),
        LIFT(AuditAction.ACCOUNT_UNSUSPENDED, "lift"),
    }

    private companion object {
        const val USER = "USER"
        const val METRIC = "moyi.admin.suspension"
        val COMPLETED = setOf(SuspensionOutcome.SUSPENDED, SuspensionOutcome.LIFTED)
    }
}
