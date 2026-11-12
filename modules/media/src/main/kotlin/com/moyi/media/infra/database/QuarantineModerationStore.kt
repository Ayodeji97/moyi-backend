package com.moyi.media.infra.database

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
import com.moyi.media.api.ReviewRefusal
import com.moyi.media.domain.ImageVariant
import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.MediaStatus
import com.moyi.media.domain.ModerationVerdict
import com.moyi.media.domain.ModeratorReview
import com.moyi.media.domain.StorageKey
import com.moyi.media.infra.storage.ObjectStore
import com.moyi.media.infra.storage.ObjectStoreUnavailableException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [QuarantineModeration] (A2 spec §6, §7.4): V36/V38's functions as `scripts/moyi media
 * quarantine` calls them, and the guards the console adds around them, each taken under the
 * asset's row lock in the caller's transaction.
 *
 * The lock is taken by its own statement, and every fact the guard decides on that can change
 * under it (the reports holding the asset) is read by a **later** statement: at READ
 * COMMITTED a statement that waited for a lock sees the locked row as committed, but a
 * subquery in it still reads its snapshot from before the wait.
 *
 * A `@Component`, not a `@Repository`: with JPA on the classpath (the app), `@Repository`'s
 * exception translation would wrap this port's own `IllegalStateException` in
 * `InvalidDataAccessApiUsageException`.
 */
@Component
@Suppress("TooManyFunctions") // One port, each of its functions once.
internal class QuarantineModerationStore(
    jdbcTemplate: JdbcTemplate,
    private val store: ObjectStore,
) : QuarantineModeration {
    private val jdbc = JdbcClient.create(jdbcTemplate)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun queue(actor: String): List<QuarantineRow> =
        jdbc
            .sql(
                "SELECT verdict, media_id, kind, status, reason, attached, moderated_at, reports " +
                    "FROM media_quarantine_list(:actor)",
            ).param("actor", actor)
            .query(QUEUE_ROW)
            .list()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun openCount(): Int =
        jdbc
            .sql("SELECT count(*) FROM media_assets WHERE status = 'QUARANTINED' OR evidence_held")
            .query(Int::class.java)
            .single()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun asset(mediaId: UUID): QuarantinedAsset? =
        jdbc
            .sql(
                "SELECT a.id, a.status, a.moderation_verdict, a.moderation_scores ->> 'reason' AS reason, a.kind, " +
                    "a.attached_entry_id IS NOT NULL AS attached, a.moderated_at, a.evidence_held, " +
                    "a.purged_at IS NOT NULL AS purged, " +
                    "EXISTS (SELECT 1 FROM media_report_holds h WHERE h.media_id = a.id) AS held_by_report " +
                    "FROM media_assets a WHERE a.id = :id",
            ).param("id", mediaId)
            .query(ASSET_ROW)
            .optional()
            .orElse(null)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun history(mediaId: UUID): List<ModerationLogLine> =
        jdbc
            .sql("SELECT id, at, action, actor, note FROM media_moderation_log WHERE media_id = :id ORDER BY at, id")
            .param("id", mediaId)
            .query(LOG_ROW)
            .list()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun reportsHolding(mediaId: UUID): List<UUID> = holds(mediaId)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun release(
        mediaId: UUID,
        actor: String,
        note: String,
    ): QuarantineDecision<ReleaseOutcome> {
        // Locked here only to read `reportDriven` under the lock the function then re-takes:
        // a release can hand the asset back to moderation and clear the reason it read.
        val facts = lock(mediaId, FOR_UPDATE)
        val reportDriven = reportDriven(facts, holds(mediaId))
        val outcome = call<ReleaseOutcome>("media_quarantine_release", mediaId, actor, note)
        return QuarantineDecision(outcome, reportDriven)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun block(
        mediaId: UUID,
        actor: String,
        note: String,
    ): QuarantineDecision<BlockOutcome> {
        val facts = lock(mediaId, FOR_UPDATE)
        val holds = holds(mediaId)
        // Spec §9.3: a removal can never be followed by a block. V36's function still accepts a
        // DELETED row its purge has not reached; the console refuses it here, under the lock, so a
        // delete committed while this waited is seen (ADR-0063 Owed: the script path is closed
        // only by a change to the function).
        val outcome =
            when {
                holds.isNotEmpty() -> BlockOutcome.REPORT_HELD
                facts != null && facts.status == MediaStatus.DELETED && !facts.evidenceHeld -> BlockOutcome.GONE
                else -> call("media_quarantine_block", mediaId, actor, note)
            }
        return QuarantineDecision(outcome, reportDriven(facts, holds))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun delete(
        mediaId: UUID,
        actor: String,
        note: String,
    ): QuarantineDecision<DeleteOutcome> {
        val facts = lock(mediaId, FOR_UPDATE)
        val holds = holds(mediaId)
        val outcome =
            when {
                facts != null && (facts.verdict == ModerationVerdict.BLOCKED || facts.evidenceHeld) -> DeleteOutcome.EVIDENCE_SERVER_ONLY
                holds.isNotEmpty() -> DeleteOutcome.REPORT_HELD
                else -> call("media_quarantine_delete", mediaId, actor, note)
            }
        return QuarantineDecision(outcome, reportDriven(facts, holds))
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun reviewCopy(
        mediaId: UUID,
        expectedReports: Set<UUID>,
    ): Review {
        val facts = lock(mediaId, FOR_SHARE) ?: return Review.Refused(ReviewRefusal.NOT_HELD)
        val holds = holds(mediaId)
        // Spec §7.4 step 3's order: media's half first (a blocked asset is refused as blocked,
        // whatever else changed), then the holds against the set the caller locked, and the
        // object store only when neither refused.
        val bar = ModeratorReview.bar(facts.verdict, facts.evidenceHeld, facts.status, facts.purged, holds.isNotEmpty())
        val refusal =
            when {
                bar != null -> REFUSAL.getValue(bar)
                holds.toSet() != expectedReports -> ReviewRefusal.CHANGED
                else -> null
            }
        val served = if (refusal == null) served(facts) else null
        var unavailable = false
        val content =
            served?.let { (key, _) ->
                try {
                    store.open(key)
                } catch (_: ObjectStoreUnavailableException) {
                    unavailable = true
                    null
                }
            }
        return when {
            refusal != null -> Review.Refused(refusal)
            unavailable -> Review.Refused(ReviewRefusal.UNAVAILABLE)
            served == null || content == null -> Review.Refused(ReviewRefusal.MISSING)
            else -> Review.Allowed(reportDriven(facts, holds), ReviewCopy(mediaId, served.second, content))
        }
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    override fun ownedBy(userId: UUID): List<UUID> =
        jdbc
            .sql("SELECT id FROM media_assets WHERE owner_user_id = :owner ORDER BY id")
            .param("owner", userId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    /** The asset's row under [mode] (`FOR UPDATE` or `FOR SHARE`), in a statement of its own. */
    private fun lock(
        mediaId: UUID,
        mode: String,
    ): LockedFacts? =
        jdbc
            .sql(
                "SELECT moderation_verdict, evidence_held, status, purged_at IS NOT NULL AS purged, " +
                    "moderation_scores ->> 'reason' AS reason, kind, storage_key, aac_key " +
                    "FROM media_assets WHERE id = :id $mode",
            ).param("id", mediaId)
            .query(FACTS_ROW)
            .optional()
            .orElse(null)

    /** The reports holding the asset, read by a statement after the lock's, so it sees what committed before it. */
    private fun holds(mediaId: UUID): List<UUID> =
        jdbc
            .sql("SELECT report_id FROM media_report_holds WHERE media_id = :id ORDER BY report_id")
            .param("id", mediaId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    private inline fun <reified O : Enum<O>> call(
        function: String,
        mediaId: UUID,
        actor: String,
        note: String,
    ): O {
        val answer =
            jdbc
                .sql("SELECT $function(:id, :actor, :note)")
                .param("id", mediaId)
                .param("actor", actor)
                .param("note", note)
                .query(String::class.java)
                .single()
        return outcomeOf(function, answer)
    }

    @Suppress("LongParameterList") // Every fact a guard or the copy reads, from one locked row.
    private class LockedFacts(
        val verdict: ModerationVerdict?,
        val evidenceHeld: Boolean,
        val status: MediaStatus,
        val purged: Boolean,
        val reported: Boolean,
        val kind: MediaKind,
        val storageKey: StorageKey,
        val aacKey: StorageKey?,
    )

    internal companion object {
        private const val FOR_UPDATE = "FOR UPDATE"
        private const val FOR_SHARE = "FOR SHARE"

        const val WEBP = "image/webp"
        const val AAC = "audio/mp4"
        const val OPUS = "audio/ogg"

        /**
         * [answer] as [O], or an [IllegalStateException] naming the function and the answer,
         * never the asset: an outcome the enum does not know is a function that changed.
         */
        inline fun <reified O : Enum<O>> outcomeOf(
            function: String,
            answer: String?,
        ): O =
            enumValues<O>().firstOrNull { it.name == answer }
                ?: error("$function answered an outcome ${O::class.simpleName} does not know: $answer")

        private val REFUSAL =
            mapOf(
                ModeratorReview.Bar.BLOCKED to ReviewRefusal.BLOCKED,
                ModeratorReview.Bar.PURGED to ReviewRefusal.PURGED,
                ModeratorReview.Bar.NOT_HELD to ReviewRefusal.NOT_HELD,
            )

        /** Spec §9.2: an open report holds it, or its hold's reason is `REPORTED`. */
        private fun reportDriven(
            facts: LockedFacts?,
            holds: List<UUID>,
        ): Boolean = holds.isNotEmpty() || facts?.reported == true

        /**
         * The variant a moderator is shown and **its** content type (spec §7.4): a photo's
         * display variant, the one moderation looked at; a voice note's AAC copy when it has
         * one, else its Opus. Never `media_assets.content_type`, the upload's declared type.
         */
        private fun served(facts: LockedFacts): Pair<StorageKey, String> =
            when (facts.kind) {
                MediaKind.IMAGE -> StorageKey.forVariant(facts.storageKey, ImageVariant.DISPLAY) to WEBP
                MediaKind.VOICE -> facts.aacKey?.let { it to AAC } ?: (StorageKey.forVoice(facts.storageKey) to OPUS)
            }

        private val QUEUE_ROW =
            RowMapper { rs, _ ->
                QuarantineRow(
                    verdict = rs.getString("verdict"),
                    mediaId = rs.getObject("media_id", UUID::class.java),
                    kind = rs.getString("kind"),
                    status = rs.getString("status"),
                    reason = rs.getString("reason"),
                    attached = rs.getBoolean("attached"),
                    moderatedAt = rs.getTimestamp("moderated_at")?.toInstant(),
                    reports = rs.getInt("reports"),
                )
            }

        private val ASSET_ROW =
            RowMapper { rs, _ ->
                val heldByReport = rs.getBoolean("held_by_report")
                val reason = rs.getString("reason")
                QuarantinedAsset(
                    mediaId = rs.getObject("id", UUID::class.java),
                    status = rs.getString("status"),
                    verdict = rs.getString("moderation_verdict"),
                    reason = reason,
                    kind = rs.getString("kind"),
                    attached = rs.getBoolean("attached"),
                    moderatedAt = rs.getTimestamp("moderated_at")?.toInstant(),
                    evidenceHeld = rs.getBoolean("evidence_held"),
                    purged = rs.getBoolean("purged"),
                    heldByReport = heldByReport,
                    reportDriven = heldByReport || reason == REPORTED,
                )
            }

        private val LOG_ROW =
            RowMapper { rs, _ ->
                ModerationLogLine(
                    id = rs.getObject("id", UUID::class.java),
                    at = rs.getTimestamp("at").toInstant(),
                    action = rs.getString("action"),
                    actor = rs.getString("actor"),
                    note = rs.getString("note"),
                )
            }

        private val FACTS_ROW =
            RowMapper { rs, _ ->
                LockedFacts(
                    verdict = rs.getString("moderation_verdict")?.let(ModerationVerdict::valueOf),
                    evidenceHeld = rs.getBoolean("evidence_held"),
                    status = MediaStatus.valueOf(rs.getString("status")),
                    purged = rs.getBoolean("purged"),
                    reported = rs.getString("reason") == REPORTED,
                    kind = MediaKind.valueOf(rs.getString("kind")),
                    storageKey = StorageKey(rs.getString("storage_key")),
                    aacKey = rs.getString("aac_key")?.let(::StorageKey),
                )
            }

        private const val REPORTED = "REPORTED"
    }
}
