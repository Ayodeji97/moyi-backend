package com.moyi.media.infra.database

import com.moyi.common.core.IdGenerator
import com.moyi.media.domain.ModerationReason
import com.moyi.media.domain.ModerationVerdict
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `media_moderation_log` (V36): the record the Phase 5 console will read of
 * what was decided about an asset's moderation and by whom (spec §4 D4). This
 * writes moderation's own verdicts; the operator's commands write theirs in
 * the database functions `scripts/moyi media quarantine` calls. A row names
 * the asset and the reason, never a key, a URL or a vendor's label.
 */
@Repository
internal class ModerationLog(
    private val jdbc: JdbcTemplate,
    private val ids: IdGenerator,
) {
    fun verdict(
        mediaId: UUID,
        verdict: ModerationVerdict,
        reason: ModerationReason,
        moderator: String,
        at: Instant,
    ) {
        jdbc.update(
            "INSERT INTO media_moderation_log (id, media_id, action, actor, note, at) VALUES (?, ?, ?, ?, ?, ?)",
            ids.timeOrdered(),
            mediaId,
            verdict.name,
            "moderation:$moderator",
            reason.name,
            Timestamp.from(at),
        )
    }
}
