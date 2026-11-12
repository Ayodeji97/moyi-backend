package com.moyi.media.api

import java.io.InputStream
import java.time.Instant
import java.util.UUID

/**
 * The quarantine queue, for the admin console (A2 spec §3, §5.2, §6, §7.4): V36/V38's
 * `media_quarantine_*` functions, the ones `scripts/moyi media quarantine` calls, plus the
 * review copy of an asset. Admin-only: `ArchitectureTest` lets no module but `admin` import
 * this or its types.
 *
 * **The functions stay the one place the rules live.** What this adds are guards taken
 * under the asset's row lock, never decided on a read made before it:
 * - [delete] refuses `BLOCKED` evidence ([DeleteOutcome.EVIDENCE_SERVER_ONLY]: the server's
 *   step, runbook §2 step 4) and an asset an open report holds ([DeleteOutcome.REPORT_HELD]:
 *   decided in the report), under `FOR UPDATE`, before the function is called;
 * - [block] refuses an asset an open report holds, under `FOR UPDATE`;
 * - [reviewCopy] decides media's half of the view rule under `FOR SHARE`.
 *
 * Every method but [ownedBy] runs only inside a transaction it joins (`MANDATORY`): the
 * console's, which writes the decision's audit row, so that neither commits without the
 * other. [ownedBy] is for the data export, whose build holds no transaction (`SUPPORTS`).
 *
 * [actor] is what the functions write to `media_moderation_log.actor` (1–100 characters);
 * the console passes `admin:<accountId>`. [note] is the moderator's, written by the function
 * to its own log and nowhere else here.
 */
@Suppress("TooManyFunctions") // One queue, each of its functions once.
interface QuarantineModeration {
    /** `media_quarantine_list`: the queue in the function's order. Writes its `LISTED` row. */
    fun queue(actor: String): List<QuarantineRow>

    /** How many the queue holds, by the list's own predicate. Writes nothing: a count names nobody. */
    fun openCount(): Int

    /** The asset's row as the console shows it, unlocked; no log row. Null for an unknown id. */
    fun asset(mediaId: UUID): QuarantinedAsset?

    /** `media_moderation_log` for the asset, oldest first. */
    fun history(mediaId: UUID): List<ModerationLogLine>

    /** The open reports holding the asset (`media_report_holds`), read unlocked: the view's first step (§7.4). */
    fun reportsHolding(mediaId: UUID): List<UUID>

    /** `media_quarantine_release`, which refuses a blocked or report-held asset itself. */
    fun release(
        mediaId: UUID,
        actor: String,
        note: String,
    ): QuarantineDecision<ReleaseOutcome>

    /** Under `FOR UPDATE`: an open report's hold refuses; otherwise `media_quarantine_block`. */
    fun block(
        mediaId: UUID,
        actor: String,
        note: String,
    ): QuarantineDecision<BlockOutcome>

    /**
     * Under `FOR UPDATE`: `BLOCKED` or held evidence refuses, then an open report's hold
     * refuses; otherwise `media_quarantine_delete`, whose own update re-takes the lock this
     * transaction already holds (spec §6.3).
     */
    fun delete(
        mediaId: UUID,
        actor: String,
        note: String,
    ): QuarantineDecision<DeleteOutcome>

    /**
     * The bytes a moderator may look at, or why not (spec §7.4 step 3). Takes the asset
     * `FOR SHARE`, reads the reports holding it again, and refuses [ReviewRefusal.CHANGED]
     * when they are not [expectedReports] (the set the caller locked, step 1–2). Then
     * media's half of the rule; then the object store, only if nothing refused. The copy
     * is the served variant's, never the upload's declared type.
     */
    fun reviewCopy(
        mediaId: UUID,
        expectedReports: Set<UUID>,
    ): Review

    /**
     * Every asset [userId] owns, deleted ones included, for the export's disclosure.
     * Joins a transaction when there is one and runs without one otherwise: the export
     * build holds none.
     */
    fun ownedBy(userId: UUID): List<UUID>
}

/**
 * A decision's outcome, and whether it was report-driven (spec §9.2): an open report held
 * the asset, or its hold's reason is `REPORTED` (an operator's hold for a report that came
 * by email). Read under the same row lock as the act.
 */
data class QuarantineDecision<O : Enum<O>>(
    val outcome: O,
    val reportDriven: Boolean,
)

/** `media_quarantine_release`'s answers, and `media_give_back`'s, which it can return. */
enum class ReleaseOutcome { RELEASED, REMODERATED, NEVER_RELEASED, REPORT_HELD, NOT_QUARANTINED, NOT_HELD_BACK }

/** `media_quarantine_block`'s answers, and the port's [REPORT_HELD]. */
enum class BlockOutcome { BLOCKED, ALREADY_BLOCKED, GONE, REPORT_HELD }

/** `media_quarantine_delete`'s answers, and the port's [EVIDENCE_SERVER_ONLY] and [REPORT_HELD]. */
enum class DeleteOutcome { DELETED, NOT_QUARANTINED, EVIDENCE_SERVER_ONLY, REPORT_HELD }

/** One row of `media_quarantine_list`, in its columns' order. */
data class QuarantineRow(
    val verdict: String?,
    val mediaId: UUID,
    val kind: String,
    val status: String,
    val reason: String?,
    val attached: Boolean,
    val moderatedAt: Instant?,
    val reports: Int,
) {
    override fun toString(): String = "QuarantineRow(mediaId=$mediaId)"
}

/** The asset's row, as the quarantine page shows it. Never a key or a content type. */
data class QuarantinedAsset(
    val mediaId: UUID,
    val status: String,
    val verdict: String?,
    val reason: String?,
    val kind: String,
    val attached: Boolean,
    val moderatedAt: Instant?,
    val evidenceHeld: Boolean,
    val purged: Boolean,
    val heldByReport: Boolean,
    val reportDriven: Boolean,
) {
    override fun toString(): String = "QuarantinedAsset(mediaId=$mediaId)"
}

/** One row of `media_moderation_log`. The note is the operator's; [toString] leaves it out. */
data class ModerationLogLine(
    val id: UUID,
    val at: Instant,
    val action: String,
    val actor: String,
    val note: String?,
) {
    override fun toString(): String = "ModerationLogLine(id=$id)"
}

/** What [QuarantineModeration.reviewCopy] answers. */
sealed interface Review {
    data class Refused(
        val reason: ReviewRefusal,
    ) : Review

    /** The look is allowed; the caller closes [copy]. */
    class Allowed(
        val reportDriven: Boolean,
        val copy: ReviewCopy,
    ) : Review {
        override fun toString(): String = "Allowed(reportDriven=$reportDriven, $copy)"
    }
}

/**
 * Why a look is refused, as the console audits it (spec §7.4). Media's own: [BLOCKED],
 * [PURGED], [NOT_HELD], [MISSING], [CHANGED]; gratitude's half answers the reports' reasons.
 */
enum class ReviewRefusal(
    val code: String,
) {
    BLOCKED("blocked"),
    PURGED("purged"),
    NOT_HELD("not_held"),

    /** The variant is not in the store (purged under a race, or never made): a refusal, not a `500`. */
    MISSING("missing"),

    /** The reports holding it are not the set the caller locked: reload and look again. */
    CHANGED("changed"),
}

/**
 * The served variant's bytes and **its** content type (`image/webp`, `audio/mp4`,
 * `audio/ogg`), never the upload's declared one. No key and no URL: the bytes come
 * through the application. The caller closes it.
 */
class ReviewCopy(
    val mediaId: UUID,
    val contentType: String,
    val content: InputStream,
) : AutoCloseable {
    override fun close() = content.close()

    override fun toString(): String = "ReviewCopy(mediaId=$mediaId, contentType=$contentType)"
}
