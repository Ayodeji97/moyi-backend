package com.moyi.gratitude.infra.database

import com.moyi.gratitude.api.DismissOutcome
import com.moyi.gratitude.api.HeldMedia
import com.moyi.gratitude.api.ReasonsReview
import com.moyi.gratitude.api.ReclassifyOutcome
import com.moyi.gratitude.api.ReportCase
import com.moyi.gratitude.api.ReportLogLine
import com.moyi.gratitude.api.ReportModeration
import com.moyi.gratitude.api.ReportRow
import com.moyi.gratitude.api.Takedown
import com.moyi.gratitude.api.TakedownOutcome
import com.moyi.gratitude.api.UpholdResult
import com.moyi.gratitude.domain.ReportReason
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * [ReportModeration] (A2 spec §6, §8): V39/V48's functions as `scripts/moyi reports` calls
 * them, and the stale check the console adds before each decision, taken under the report's
 * row lock in the caller's transaction.
 *
 * The lock is the first statement of a decision, so the order every report decision takes is
 * the functions' own: the report, then the assets it holds. The function's own `FOR UPDATE`
 * (or `UPDATE`) on the same row in the same transaction then finds the lock already held.
 *
 * A `@Component`, not a `@Repository`: `JdbcClient` already translates SQL errors, and
 * `@Repository`'s translation would turn this port's own [IllegalArgumentException] (a reason
 * that is not one of the five) and [IllegalStateException] (an answer it does not know) into
 * `InvalidDataAccessApiUsageException`, hiding which of the two the caller got.
 */
@Component
@Suppress("TooManyFunctions") // One port, each of its functions once, and the stale check they share.
internal class ReportModerationStore(
    jdbcTemplate: JdbcTemplate,
) : ReportModeration {
    private val jdbc = JdbcClient.create(jdbcTemplate)

    /** The domain's own list, read, never restated: no SQL, and no transaction needed. */
    override fun reasons(): List<String> = ReportReason.entries.map { it.name }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun queue(actor: String): List<ReportRow> =
        jdbc
            .sql(
                "SELECT priority, report_id, reason, target, entry_id, reported_member_id, held, detail, received_at " +
                    "FROM entry_report_list(:actor)",
            ).param("actor", actor)
            .query(QUEUE_ROW)
            .list()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun openCount(): Int =
        jdbc
            .sql("SELECT count(*) FROM entry_reports WHERE status = 'OPEN'")
            .query(Int::class.java)
            .single()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun read(
        reportId: UUID,
        actor: String,
    ): ReportCase? {
        val rows =
            jdbc
                .sql(
                    "SELECT report_id, reason, status, target, entry_id, reported_member_id, bond_id, received_at, detail, " +
                        "media_id, media_kind, media_status, media_verdict FROM entry_report_read(:id, :actor)",
                ).param("id", reportId)
                .param("actor", actor)
                .query(READ_ROW)
                .list()
        return rows.firstOrNull()?.let { first ->
            val reason = reasonOf(first.reason)
            ReportCase(
                reportId = first.reportId,
                reason = first.reason,
                priority = reason.priority,
                mediaReviewable = reason.mediaReviewable,
                status = first.status,
                target = first.target,
                entryId = first.entryId,
                reportedMemberId = first.reportedMemberId,
                bondId = first.bondId,
                receivedAt = first.receivedAt,
                detail = first.detail,
                media = rows.mapNotNull { it.media },
            )
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun history(reportId: UUID): List<ReportLogLine> =
        jdbc
            .sql("SELECT id, at, action, actor, note FROM entry_report_log WHERE report_id = :id ORDER BY at, id")
            .param("id", reportId)
            .query(LOG_ROW)
            .list()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun dismiss(
        reportId: UUID,
        expectedReason: String,
        actor: String,
        note: String,
    ): DismissOutcome =
        when (gate(reportId, expectedReason)) {
            Gate.NOT_OPEN -> {
                DismissOutcome.NOT_OPEN
            }

            Gate.STALE -> {
                DismissOutcome.STALE
            }

            Gate.CURRENT -> {
                answer(
                    "entry_report_dismiss",
                    "SELECT entry_report_dismiss(:id, :actor, :note)",
                    mapOf("id" to reportId, "actor" to actor, "note" to note),
                )
            }
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun reclassify(
        reportId: UUID,
        expectedReason: String,
        newReason: String,
        actor: String,
        note: String,
    ): ReclassifyOutcome {
        // The rule is the domain's. Answered before any SQL, and not thrown: a throw inside this
        // MANDATORY participant would mark the console's transaction rollback-only.
        if (ReportReason.named(newReason) == null) return ReclassifyOutcome.UNKNOWN_REASON
        return when (gate(reportId, expectedReason)) {
            Gate.NOT_OPEN -> {
                ReclassifyOutcome.NOT_OPEN
            }

            Gate.STALE -> {
                ReclassifyOutcome.STALE
            }

            Gate.CURRENT -> {
                answer(
                    "entry_report_reclassify",
                    "SELECT entry_report_reclassify(:id, :reason, :actor, :note)",
                    mapOf("id" to reportId, "reason" to newReason, "actor" to actor, "note" to note),
                )
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun uphold(
        reportId: UUID,
        expectedReason: String,
        takedown: Takedown,
        actor: String,
        note: String,
    ): UpholdResult =
        when (gate(reportId, expectedReason)) {
            Gate.NOT_OPEN -> UpholdResult.NotOpen
            Gate.STALE -> UpholdResult.Stale
            Gate.CURRENT -> upheld(reportId, takedown, actor, note)
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun reviewableReasons(reportIds: Collection<UUID>): ReasonsReview {
        val barred =
            jdbc
                // In id order, so that two looks at once take their share locks in one order.
                .sql("SELECT reason FROM entry_reports WHERE id = ANY(:ids) AND status = 'OPEN' ORDER BY id FOR SHARE")
                .param("ids", reportIds.toTypedArray())
                .query(String::class.java)
                .list()
                .map { reasonOf(checkNotNull(it) { "entry_reports holds a report with no reason" }) }
                .filterNot { it.mediaReviewable }
        return when {
            barred.any { it.priority } -> ReasonsReview.PRIORITY_REPORT
            barred.isNotEmpty() -> ReasonsReview.CONSENT_REPORT
            else -> ReasonsReview.REVIEWABLE
        }
    }

    /**
     * The stale check (spec §6.2): the report's row `FOR UPDATE`, then its reason against the
     * page's. At READ COMMITTED a lock that waited on a decision re-reads the row as committed:
     * a report dismissed meanwhile is no longer `OPEN` and is not found; one reclassified
     * meanwhile has its new reason.
     */
    private fun gate(
        reportId: UUID,
        expectedReason: String,
    ): Gate {
        val reason =
            jdbc
                .sql("SELECT reason FROM entry_reports WHERE id = :id AND status = 'OPEN' FOR UPDATE")
                .param("id", reportId)
                .query(String::class.java)
                .optional()
                .orElse(null)
        return when (reason) {
            null -> Gate.NOT_OPEN
            expectedReason -> Gate.CURRENT
            else -> Gate.STALE
        }
    }

    /**
     * `entry_report_uphold`: one row with no asset and a refusal, or a row per asset taken down
     * (none for a report of words alone).
     */
    private fun upheld(
        reportId: UUID,
        takedown: Takedown,
        actor: String,
        note: String,
    ): UpholdResult {
        val rows =
            jdbc
                .sql("SELECT media_id, outcome FROM entry_report_uphold(:id, :action, :actor, :note)")
                .param("id", reportId)
                .param("action", takedown.name)
                .param("actor", actor)
                .param("note", note)
                .query { rs, _ -> rs.getObject("media_id", UUID::class.java) to rs.getString("outcome") }
                .list()
        val refusal = rows.singleOrNull()?.takeIf { (mediaId, _) -> mediaId == null }?.second
        return when (refusal) {
            null -> {
                UpholdResult.Done(
                    rows.map { (mediaId, outcome) ->
                        checkNotNull(mediaId) { "entry_report_uphold answered an asset with no id" } to
                            outcomeOf<TakedownOutcome>("media_report_takedown", outcome)
                    },
                )
            }

            else -> {
                UPHOLD_REFUSALS[refusal] ?: error("entry_report_uphold answered a refusal the port does not know: $refusal")
            }
        }
    }

    private inline fun <reified O : Enum<O>> answer(
        function: String,
        sql: String,
        params: Map<String, Any>,
    ): O =
        outcomeOf(
            function,
            jdbc
                .sql(sql)
                .params(params)
                .query(String::class.java)
                .single(),
        )

    /** Whether the page's reason is the report's. */
    private enum class Gate { CURRENT, NOT_OPEN, STALE }

    /** One row of `entry_report_read`: the report's columns, repeated, and one held asset or none. */
    @Suppress("LongParameterList") // One function's result row, column for column.
    private class ReadRow(
        val reportId: UUID,
        val reason: String,
        val status: String,
        val target: String,
        val entryId: UUID?,
        val reportedMemberId: UUID?,
        val bondId: UUID,
        val receivedAt: Instant,
        val detail: String?,
        val media: HeldMedia?,
    )

    internal companion object {
        /** `entry_report_uphold`'s two refusals, answered as a row with no asset. `ReportOutcomeNamesTest` holds them to its source. */
        val UPHOLD_REFUSALS: Map<String, UpholdResult> =
            mapOf("NOT_OPEN" to UpholdResult.NotOpen, "RECLASSIFY_FIRST" to UpholdResult.ReclassifyFirst)

        /**
         * [answer] as [O], or an [IllegalStateException] naming the function and the answer,
         * never the report: an outcome the enum does not know is a function that changed.
         */
        inline fun <reified O : Enum<O>> outcomeOf(
            function: String,
            answer: String?,
        ): O =
            enumValues<O>().firstOrNull { it.name == answer }
                ?: error("$function answered an outcome ${O::class.simpleName} does not know: $answer")

        /** V39's `CHECK` holds the same five names; one it does not know is a schema that changed. */
        private fun reasonOf(name: String): ReportReason =
            ReportReason.named(name) ?: error("entry_reports holds a reason ReportReason does not know")

        private val QUEUE_ROW =
            RowMapper { rs, _ ->
                ReportRow(
                    priority = rs.getBoolean("priority"),
                    reportId = rs.getObject("report_id", UUID::class.java),
                    reason = rs.getString("reason"),
                    target = rs.getString("target"),
                    entryId = rs.getObject("entry_id", UUID::class.java),
                    reportedMemberId = rs.getObject("reported_member_id", UUID::class.java),
                    held = rs.getInt("held"),
                    hasDetail = rs.getBoolean("detail"),
                    receivedAt = rs.getTimestamp("received_at").toInstant(),
                )
            }

        private val READ_ROW =
            RowMapper { rs, _ ->
                ReadRow(
                    reportId = rs.getObject("report_id", UUID::class.java),
                    reason = rs.getString("reason"),
                    status = rs.getString("status"),
                    target = rs.getString("target"),
                    entryId = rs.getObject("entry_id", UUID::class.java),
                    reportedMemberId = rs.getObject("reported_member_id", UUID::class.java),
                    bondId = rs.getObject("bond_id", UUID::class.java),
                    receivedAt = rs.getTimestamp("received_at").toInstant(),
                    detail = rs.getString("detail"),
                    media =
                        rs.getObject("media_id", UUID::class.java)?.let {
                            HeldMedia(it, rs.getString("media_kind"), rs.getString("media_status"), rs.getString("media_verdict"))
                        },
                )
            }

        private val LOG_ROW =
            RowMapper { rs, _ ->
                ReportLogLine(
                    id = rs.getObject("id", UUID::class.java),
                    at = rs.getTimestamp("at").toInstant(),
                    action = rs.getString("action"),
                    actor = rs.getString("actor"),
                    note = rs.getString("note"),
                )
            }
    }
}
