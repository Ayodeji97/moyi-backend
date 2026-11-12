package com.moyi.admin.service

import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.common.security.ClientAddress
import com.moyi.gratitude.api.DismissOutcome
import com.moyi.gratitude.api.ReasonsReview
import com.moyi.gratitude.api.ReclassifyOutcome
import com.moyi.gratitude.api.ReportCase
import com.moyi.gratitude.api.ReportLogLine
import com.moyi.gratitude.api.ReportModeration
import com.moyi.gratitude.api.ReportRow
import com.moyi.gratitude.api.Takedown
import com.moyi.gratitude.api.UpholdResult
import com.moyi.media.api.BlockOutcome
import com.moyi.media.api.DeleteOutcome
import com.moyi.media.api.ModerationLogLine
import com.moyi.media.api.QuarantineDecision
import com.moyi.media.api.QuarantineModeration
import com.moyi.media.api.QuarantineRow
import com.moyi.media.api.QuarantinedAsset
import com.moyi.media.api.ReleaseOutcome
import com.moyi.media.api.Review
import com.moyi.media.api.ReviewCopy
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A report's page: the case `entry_report_read` gave, and its log. Never printed with the detail. */
internal class ReportPage(
    val case: ReportCase,
    val history: List<ReportLogLine>,
) {
    override fun toString(): String = "ReportPage(${case.reportId})"
}

/** A held-back asset's page: its row, the open reports holding it, and its log. */
internal class QuarantinePage(
    val asset: QuarantinedAsset,
    val reports: List<UUID>,
    val history: List<ModerationLogLine>,
) {
    override fun toString(): String = "QuarantinePage(${asset.mediaId})"
}

/**
 * The moderation console (A2 spec §5, §6, §7.4, §9.2, §10.2): the report queue and the
 * quarantine queue, decided through the two admin-only ports, which call the functions
 * `scripts/moyi` calls. **The functions stay the one place the moderation rules live**; this
 * adds the role check, the note's rule, the step-up, the actor string and the audit row.
 *
 * Every method is `MODERATOR` (and so `ADMIN`, through the role hierarchy) and one
 * transaction at the database's default isolation, READ COMMITTED, which
 * `media_quarantine_delete` requires: the port call and its `audit_log` row commit together
 * or not at all. Everything that refuses by throwing (the role, the step-up, the note) does
 * so before any port is called; everything after answers, so that a refusal's audit row
 * commits.
 *
 * Never in a log line, a metric tag or audit `metadata`: the note (it is the row's
 * `justification`), the reporter's detail, a storage key, a URL. Outcomes are closed enums,
 * so a tag or a `result` is one of a known few names.
 */
@Service
@ConditionalOnAdminConsole
@Suppress("TooManyFunctions") // One method per console action of spec §6.1, and the audit helpers they share.
internal class ModerationConsole(
    private val reports: ReportModeration,
    private val media: QuarantineModeration,
    private val audit: AuditTrail,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Both queues' open counts for the home page (spec §5). No log row and no audit row: a count names nobody. */
    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional(readOnly = true)
    fun counts(): QueueCounts = QueueCounts(reports = reports.openCount(), quarantine = media.openCount())

    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun reportQueue(
        adminId: UUID,
        from: ClientAddress?,
    ): List<ReportRow> {
        val rows = reports.queue(ModerationActor.of(adminId))
        audit.record(AuditAction.REPORT_QUEUE_VIEWED, AuditOutcome.SUCCEEDED, adminId, null, null, clientAddress = from)
        return rows
    }

    /** One report, its `READ` logged by the function; null for an unknown id, which writes no `READ`. */
    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun report(
        reportId: UUID,
        adminId: UUID,
        from: ClientAddress?,
    ): ReportPage? {
        val case = reports.read(reportId, ModerationActor.of(adminId))
        audit.record(
            action = AuditAction.REPORT_READ,
            outcome = if (case != null) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED,
            actorId = adminId,
            targetType = REPORT,
            targetId = reportId.toString(),
            metadata = if (case != null) emptyMap() else mapOf(RESULT to NOT_FOUND),
            clientAddress = from,
        )
        return case?.let { ReportPage(it, reports.history(reportId)) }
    }

    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun dismiss(
        reportId: UUID,
        expectedReason: String,
        note: String,
        adminId: UUID,
        from: ClientAddress?,
    ): DismissOutcome {
        val checked = ModerationNote.require(note)
        val outcome = reports.dismiss(reportId, expectedReason, ModerationActor.of(adminId), checked.text)
        decided(
            Decided(AuditAction.REPORT_DISMISSED, REPORT, reportId, outcome == DismissOutcome.DISMISSED, outcome.name),
            checked,
            adminId,
            from,
            mapOf(RESULT to outcome.name),
        )
        return outcome
    }

    /**
     * The reasons go into `metadata` only when the function has vouched for them: both are one of
     * the five when it answered `RECLASSIFIED` or `SAME_REASON`. A stale, closed or unknown answer
     * records only its result, since the names it came with are whatever the form sent.
     */
    @Suppress("LongParameterList") // The decision's four inputs, and who asked from where.
    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun reclassify(
        reportId: UUID,
        expectedReason: String,
        newReason: String,
        note: String,
        adminId: UUID,
        from: ClientAddress?,
    ): ReclassifyOutcome {
        val checked = ModerationNote.require(note)
        val outcome = reports.reclassify(reportId, expectedReason, newReason, ModerationActor.of(adminId), checked.text)
        val vouched = outcome == ReclassifyOutcome.RECLASSIFIED || outcome == ReclassifyOutcome.SAME_REASON
        decided(
            Decided(AuditAction.REPORT_RECLASSIFIED, REPORT, reportId, outcome == ReclassifyOutcome.RECLASSIFIED, outcome.name),
            checked,
            adminId,
            from,
            if (vouched) mapOf(RESULT to outcome.name, "from" to expectedReason, "to" to newReason) else mapOf(RESULT to outcome.name),
        )
        return outcome
    }

    /**
     * `entry_report_uphold` by [takedown], behind the step-up. **One** `REPORT_UPHELD` row (spec
     * §9.2), whose `media` lists each asset taken down and its outcome. No `MEDIA_*` row: a report
     * drove the takedown, and the export's disclosure must never show it (spec §9.3), which a
     * `REPORT_UPHELD` row never is.
     */
    @Suppress("LongParameterList") // The decision's four inputs, the step-up proof, and who asked from where.
    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun uphold(
        reportId: UUID,
        expectedReason: String,
        takedown: Takedown,
        note: String,
        secondFactorAt: Instant?,
        adminId: UUID,
        from: ClientAddress?,
    ): UpholdResult {
        stepUp(secondFactorAt)
        val checked = ModerationNote.require(note)
        val result = reports.uphold(reportId, expectedReason, takedown, ModerationActor.of(adminId), checked.text)
        val (name, media) =
            when (result) {
                is UpholdResult.Done -> UPHELD to result.media
                UpholdResult.NotOpen -> "NOT_OPEN" to emptyList()
                UpholdResult.ReclassifyFirst -> "RECLASSIFY_FIRST" to emptyList()
                UpholdResult.Stale -> "STALE" to emptyList()
            }
        decided(
            Decided(AuditAction.REPORT_UPHELD, REPORT, reportId, result is UpholdResult.Done, name),
            checked,
            adminId,
            from,
            mapOf(
                RESULT to name,
                "takedown" to takedown.name,
                "media" to media.map { (id, outcome) -> mapOf("id" to id.toString(), "outcome" to outcome.name) },
            ),
        )
        return result
    }

    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun quarantineQueue(
        adminId: UUID,
        from: ClientAddress?,
    ): List<QuarantineRow> {
        val rows = media.queue(ModerationActor.of(adminId))
        audit.record(AuditAction.QUARANTINE_QUEUE_VIEWED, AuditOutcome.SUCCEEDED, adminId, null, null, clientAddress = from)
        return rows
    }

    /**
     * One held-back asset (spec §5.2). It has no log of its own in `media_moderation_log`, so the
     * audit row is the record of the look: `QUARANTINE_QUEUE_VIEWED` aimed at the asset. Null, and
     * refused `NOT_FOUND`, for an unknown id and for an asset that is not held back: neither
     * `QUARANTINED`, nor held as evidence, nor held by a report. The page shows nothing else.
     */
    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun quarantined(
        mediaId: UUID,
        adminId: UUID,
        from: ClientAddress?,
    ): QuarantinePage? {
        val asset = media.asset(mediaId)?.takeIf { it.status == QUARANTINED || it.evidenceHeld || it.heldByReport }
        audit.record(
            action = AuditAction.QUARANTINE_QUEUE_VIEWED,
            outcome = if (asset != null) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED,
            actorId = adminId,
            targetType = MEDIA,
            targetId = mediaId.toString(),
            metadata = if (asset != null) emptyMap() else mapOf(RESULT to NOT_FOUND),
            clientAddress = from,
        )
        return asset?.let { QuarantinePage(it, media.reportsHolding(mediaId), media.history(mediaId)) }
    }

    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun release(
        mediaId: UUID,
        note: String,
        adminId: UUID,
        from: ClientAddress?,
    ): QuarantineDecision<ReleaseOutcome> {
        val checked = ModerationNote.require(note)
        val decision = media.release(mediaId, ModerationActor.of(adminId), checked.text)
        val succeeded = decision.outcome == ReleaseOutcome.RELEASED || decision.outcome == ReleaseOutcome.REMODERATED
        mediaDecided(AuditAction.MEDIA_RELEASED, mediaId, decision, succeeded, checked, adminId, from)
        return decision
    }

    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun block(
        mediaId: UUID,
        note: String,
        secondFactorAt: Instant?,
        adminId: UUID,
        from: ClientAddress?,
    ): QuarantineDecision<BlockOutcome> {
        stepUp(secondFactorAt)
        val checked = ModerationNote.require(note)
        val decision = media.block(mediaId, ModerationActor.of(adminId), checked.text)
        mediaDecided(AuditAction.MEDIA_BLOCKED, mediaId, decision, decision.outcome == BlockOutcome.BLOCKED, checked, adminId, from)
        return decision
    }

    /**
     * `EVIDENCE_SERVER_ONLY` and `REPORT_HELD` are the port's, decided under the asset's row lock
     * (spec §6.3). This reads nothing first and decides nothing itself: it audits what it is told.
     */
    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun delete(
        mediaId: UUID,
        note: String,
        secondFactorAt: Instant?,
        adminId: UUID,
        from: ClientAddress?,
    ): QuarantineDecision<DeleteOutcome> {
        stepUp(secondFactorAt)
        val checked = ModerationNote.require(note)
        val decision = media.delete(mediaId, ModerationActor.of(adminId), checked.text)
        mediaDecided(AuditAction.MEDIA_DELETED, mediaId, decision, decision.outcome == DeleteOutcome.DELETED, checked, adminId, from)
        return decision
    }

    /**
     * The review copy (spec §7.4), or null when the look is refused. In this one transaction, in
     * the order every report decision takes its locks, report first and asset second:
     * 1. the reports holding the asset, read unlocked;
     * 2. their reasons, under `FOR SHARE` on those reports (gratitude's half);
     * 3. the asset under `FOR SHARE`, its holds re-read against step 1's, and media's half.
     *
     * The `MEDIA_VIEWED` row is written (and flushed) before the copy is returned, and the
     * transaction commits as this returns, before the caller streams a byte. The caller closes
     * the copy. A look that is not recorded hands back no bytes: the copy is closed here when the
     * row's write fails, and when the transaction does not commit after it (a failed commit, or
     * an outer transaction that rolls back).
     */
    @PreAuthorize("hasRole('MODERATOR')")
    @Transactional
    fun openForReview(
        mediaId: UUID,
        adminId: UUID,
        from: ClientAddress?,
    ): ReviewCopy? {
        val holding = media.reportsHolding(mediaId)
        val look =
            when (reports.reviewableReasons(holding)) {
                ReasonsReview.PRIORITY_REPORT -> Look.Refused(PRIORITY_REPORT)
                ReasonsReview.CONSENT_REPORT -> Look.Refused(CONSENT_REPORT)
                ReasonsReview.REVIEWABLE -> media.reviewCopy(mediaId, holding.toSet()).let(::asLook)
            }
        val copy = (look as? Look.Served)?.copy
        copy?.let(::closeUnlessCommitted)
        var recorded = false
        try {
            recordLook(mediaId, holding, look, adminId, from)
            recorded = true
        } finally {
            // A look whose record failed shows nothing: its bytes are never handed back.
            if (!recorded) copy?.close()
        }
        val outcome = if (copy != null) "served" else "refused"
        meters.counter(REVIEW_METRIC, OUTCOME, outcome).increment()
        log.info("Moderation review: admin={} media={} outcome={}", adminId, mediaId, outcome)
        return copy
    }

    /**
     * The `MEDIA_VIEWED` row (spec §9.2): which page the look came through, whether a report drove
     * it, and a refusal's reason. A refused look has no answer of the port's to say whether a report
     * drove it, so the row reads it from the holds and the asset's own hold reason.
     */
    private fun recordLook(
        mediaId: UUID,
        holding: List<UUID>,
        look: Look,
        adminId: UUID,
        from: ClientAddress?,
    ) {
        val via = holding.firstOrNull()?.let { mapOf("via" to "REPORT", "reportId" to it.toString()) } ?: mapOf("via" to "QUARANTINE")
        val (outcome, facts) =
            when (look) {
                is Look.Served -> {
                    AuditOutcome.SUCCEEDED to mapOf(REPORT_DRIVEN to look.reportDriven)
                }

                is Look.Refused -> {
                    val reportDriven = holding.isNotEmpty() || media.asset(mediaId)?.reportDriven == true
                    AuditOutcome.REFUSED to mapOf(REPORT_DRIVEN to reportDriven, "reason" to look.reason)
                }
            }
        audit.record(
            action = AuditAction.MEDIA_VIEWED,
            outcome = outcome,
            actorId = adminId,
            targetType = MEDIA,
            targetId = mediaId.toString(),
            metadata = via + facts,
            clientAddress = from,
        )
    }

    /**
     * Closes [copy] when the transaction ends without committing. Any status but `COMMITTED`: a
     * commit that fails in the driver ends `STATUS_UNKNOWN`, not `STATUS_ROLLED_BACK`, and its
     * row is no more recorded than a rolled-back one.
     */
    private fun closeUnlessCommitted(copy: ReviewCopy) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) copy.close()
                    }
                },
            )
        }
    }

    private fun stepUp(secondFactorAt: Instant?) {
        if (!StepUp.isFresh(secondFactorAt, clock.instant())) throw StepUpRequired()
    }

    @Suppress("LongParameterList") // The act, its asset and answer, and the decision's note and author.
    private fun <O : Enum<O>> mediaDecided(
        action: AuditAction,
        mediaId: UUID,
        decision: QuarantineDecision<O>,
        succeeded: Boolean,
        note: ModerationNote,
        adminId: UUID,
        from: ClientAddress?,
    ) = decided(
        Decided(action, MEDIA, mediaId, succeeded, decision.outcome.name),
        note,
        adminId,
        from,
        mapOf(RESULT to decision.outcome.name, REPORT_DRIVEN to decision.reportDriven),
    )

    /** A decision's audit row, its counter and its log line. The note goes only to `justification`. */
    private fun decided(
        what: Decided,
        note: ModerationNote,
        adminId: UUID,
        from: ClientAddress?,
        metadata: Map<String, Any>,
    ) {
        audit.record(
            action = what.action,
            outcome = if (what.succeeded) AuditOutcome.SUCCEEDED else AuditOutcome.REFUSED,
            actorId = adminId,
            targetType = what.targetType,
            targetId = what.targetId.toString(),
            justification = note.text,
            metadata = metadata,
            clientAddress = from,
        )
        val queue = if (what.targetType == REPORT) "reports" else "quarantine"
        val decision = DECISIONS.getValue(what.action)
        meters.counter(DECISION_METRIC, "queue", queue, "decision", decision, OUTCOME, what.outcome).increment()
        log.info(
            "Moderation decision: admin={} {}={} decision={} outcome={}",
            adminId,
            what.targetType.lowercase(),
            what.targetId,
            decision,
            what.outcome,
        )
    }

    /** What a decision's row says, before the note, the author and the metadata are added. */
    private class Decided(
        val action: AuditAction,
        val targetType: String,
        val targetId: UUID,
        val succeeded: Boolean,
        val outcome: String,
    )

    /** What became of a look: served with its copy, or refused with the reason the row records. */
    private sealed interface Look {
        class Served(
            val reportDriven: Boolean,
            val copy: ReviewCopy,
        ) : Look

        class Refused(
            val reason: String,
        ) : Look
    }

    private fun asLook(review: Review): Look =
        when (review) {
            is Review.Allowed -> Look.Served(review.reportDriven, review.copy)
            is Review.Refused -> Look.Refused(review.reason.code)
        }

    private companion object {
        const val REPORT = "REPORT"
        const val MEDIA = "MEDIA"
        const val RESULT = "result"
        const val REPORT_DRIVEN = "reportDriven"
        const val NOT_FOUND = "NOT_FOUND"
        const val QUARANTINED = "QUARANTINED"
        const val UPHELD = "UPHELD"
        const val PRIORITY_REPORT = "priority_report"
        const val CONSENT_REPORT = "consent_report"
        const val OUTCOME = "outcome"
        const val DECISION_METRIC = "moyi.admin.moderation.decision"
        const val REVIEW_METRIC = "moyi.admin.media.review"

        val DECISIONS =
            mapOf(
                AuditAction.REPORT_DISMISSED to "dismiss",
                AuditAction.REPORT_RECLASSIFIED to "reclassify",
                AuditAction.REPORT_UPHELD to "uphold",
                AuditAction.MEDIA_RELEASED to "release",
                AuditAction.MEDIA_BLOCKED to "block",
                AuditAction.MEDIA_DELETED to "delete",
            )
    }
}

/** The two queues' open counts, for the home page. */
internal data class QueueCounts(
    val reports: Int,
    val quarantine: Int,
)
