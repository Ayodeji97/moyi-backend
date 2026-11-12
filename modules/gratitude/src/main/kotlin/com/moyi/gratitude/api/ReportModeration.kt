package com.moyi.gratitude.api

import java.time.Instant
import java.util.UUID

/**
 * The report queue, for the admin console (A2 spec §3, §5.1, §6, §8): V39/V48's
 * `entry_report_*` functions, the ones `scripts/moyi reports` calls. Admin-only:
 * `ArchitectureTest` lets no module but `admin` import this or its types.
 *
 * **The functions stay the one place the rules live.** What this adds is the stale check
 * (spec §6.2, §8): every decision carries the reason the moderator's page showed, and the
 * port takes the report's row `FOR UPDATE`, compares, and answers `STALE` without calling the
 * function when it changed. The lock is the report's, taken first, so the order (report,
 * then its assets) is the one the functions already use.
 *
 * Every method runs only inside a transaction it joins (`MANDATORY`): the console's, which
 * writes the decision's audit row, so that neither commits without the other.
 *
 * Reasons are their names (`HARASSMENT`, …): the set is gratitude's domain's, and a name that
 * is not one of the five is refused before any SQL runs. [actor] is what the functions write
 * to `entry_report_log.actor` (1–100 characters); the console passes `admin:<accountId>`.
 * [note] is the moderator's, written by the function to its own log and nowhere else here.
 */
interface ReportModeration {
    /** `entry_report_list`: the open reports, priority first, then oldest. Writes its `LISTED` row. */
    fun queue(actor: String): List<ReportRow>

    /** How many reports are open. Writes nothing: a count names nobody. */
    fun openCount(): Int

    /** `entry_report_read`, its rows folded into one case. Null, and no `READ` written, for an unknown id. */
    fun read(
        reportId: UUID,
        actor: String,
    ): ReportCase?

    /** `entry_report_log` for the report, oldest first. */
    fun history(reportId: UUID): List<ReportLogLine>

    /** `entry_report_dismiss`, unless the report is not open or its reason is not [expectedReason]. */
    fun dismiss(
        reportId: UUID,
        expectedReason: String,
        actor: String,
        note: String,
    ): DismissOutcome

    /**
     * `entry_report_reclassify` to [newReason], unless the report is not open or its reason is
     * not [expectedReason]. A [newReason] that is not one of the five throws
     * [IllegalArgumentException] before anything runs.
     */
    fun reclassify(
        reportId: UUID,
        expectedReason: String,
        newReason: String,
        actor: String,
        note: String,
    ): ReclassifyOutcome

    /** `entry_report_uphold` by [takedown], unless the report is not open or its reason is not [expectedReason]. */
    fun uphold(
        reportId: UUID,
        expectedReason: String,
        takedown: Takedown,
        actor: String,
        note: String,
    ): UpholdResult

    /**
     * Gratitude's half of the view rule (spec §7.4 step 2): true when every **open** report
     * among [reportIds] has a reason whose media may be looked at. Takes those reports
     * `FOR SHARE`, so that a reclassify or a decision in flight makes this wait, and none can
     * commit before the caller's transaction does. An empty collection is true: no report
     * forbids the look.
     */
    fun reviewableReasons(reportIds: Collection<UUID>): Boolean
}

/** `entry_report_dismiss`'s answers, and the port's [STALE]. */
enum class DismissOutcome { DISMISSED, NOT_OPEN, STALE }

/**
 * `entry_report_reclassify`'s answers, and the port's own [STALE] and [UNKNOWN_REASON]. A reason
 * that is not one of the five is answered, not thrown,
 * so the console's transaction is not marked rollback-only and its REFUSED audit row still commits.
 */
enum class ReclassifyOutcome { RECLASSIFIED, SAME_REASON, NOT_OPEN, STALE, UNKNOWN_REASON }

/** How a report is upheld: what becomes of the media it holds. */
enum class Takedown { DELETE, BLOCK }

/**
 * What became of one asset an upheld report held: `media_report_takedown`'s own answers and
 * those of `media_quarantine_block` and `media_quarantine_delete`, which it answers with.
 */
enum class TakedownOutcome { DELETED, BLOCKED, ALREADY_BLOCKED, BLOCKED_KEPT, GONE, NOT_QUARANTINED }

/** What [ReportModeration.uphold] answers. */
sealed interface UpholdResult {
    /** The report is not open: decided by someone else, or never was. Nothing written. */
    data object NotOpen : UpholdResult

    /** A `MINOR_SEXUAL_CONTENT` report is never upheld by `DELETE` (V39). Nothing written. */
    data object ReclassifyFirst : UpholdResult

    /** The report's reason is not the one the page showed. Nothing written. */
    data object Stale : UpholdResult

    /** Upheld: each asset the report held, with what became of it. Empty for a report of words alone. */
    data class Done(
        val media: List<Pair<UUID, TakedownOutcome>>,
    ) : UpholdResult
}

/** One row of `entry_report_list`, in its columns' order. Never the detail: only whether there is one. */
data class ReportRow(
    val priority: Boolean,
    val reportId: UUID,
    val reason: String,
    val target: String,
    val entryId: UUID?,
    val reportedMemberId: UUID?,
    val held: Int,
    val hasDetail: Boolean,
    val receivedAt: Instant,
) {
    override fun toString(): String = "ReportRow(reportId=$reportId)"
}

/**
 * One report, for the moderator deciding it (`entry_report_read`). [detail] is the reporter's
 * own words to the person who reviews (ADR-0053 decision 7), emptied when the report is
 * decided; [toString] never prints it. [priority] and [mediaReviewable] are the reason's, as
 * gratitude's domain rules them.
 */
data class ReportCase(
    val reportId: UUID,
    val reason: String,
    val priority: Boolean,
    val mediaReviewable: Boolean,
    val status: String,
    val target: String,
    val entryId: UUID?,
    val reportedMemberId: UUID?,
    val bondId: UUID,
    val receivedAt: Instant,
    val detail: String?,
    val media: List<HeldMedia>,
) {
    override fun toString(): String = "ReportCase(reportId=$reportId)"
}

/** An asset the report holds back, as `media_report_holding` describes it. */
data class HeldMedia(
    val mediaId: UUID,
    val kind: String,
    val status: String,
    val verdict: String?,
)

/** One row of `entry_report_log`. The note is the operator's; [toString] leaves it out. */
data class ReportLogLine(
    val id: UUID,
    val at: Instant,
    val action: String,
    val actor: String,
    val note: String?,
) {
    override fun toString(): String = "ReportLogLine(id=$id)"
}
