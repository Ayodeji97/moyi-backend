package com.moyi.media.service

import com.moyi.media.api.MediaReports
import com.moyi.media.infra.database.MediaAssetStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [MediaReports] (ADR-0053): the database's own `media_report_hold` (V38), in
 * the report's transaction (`MANDATORY`: outside one, Spring refuses the
 * call). A function rather than a statement written here, because the
 * operator's commands that later give the hold back or take the media down
 * are functions beside it, and the three must agree on what a hold is: V36's
 * `media_quarantine_hold`, which now records what the asset was held from, so
 * that a dismissal can put it back exactly.
 *
 * The audit row it writes (`media_moderation_log`, action `QUARANTINED`)
 * names the actor [ACTOR] and the note `report <id>`: never who reported, nor
 * why.
 */
@Service
internal class HoldReportedMedia(
    private val assets: MediaAssetStore,
) : MediaReports {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun holdFor(
        entryId: UUID,
        reportId: UUID,
    ): Int = assets.holdForReport(entryId, reportId, ACTOR)

    internal companion object {
        /** Who held it, in `media_moderation_log`: a member's report, never which member. */
        const val ACTOR = "member-report"
    }
}
