package com.moyi.media.domain

/**
 * Media's half of when a moderator may look at a held-back photo or voice note in the
 * admin console (A2 spec §7.4; `ops/moderation/takedown.md` §1–§3). Gratitude holds the
 * other half (what an open report's reason allows); the console composes the two.
 *
 * Takes the row's facts, not a [MediaAsset]: `purged_at` is not on the entity, and the
 * port reads every fact here under the asset's share lock.
 */
internal object ModeratorReview {
    /** Why media's half refuses a look. */
    enum class Bar {
        /** `BLOCKED`, or held as evidence: never viewed, at any step (runbook §2). */
        BLOCKED,

        /** Its objects are gone: there is nothing to look at. */
        PURGED,

        /** Neither held back nor held by an open report: nothing for a moderator to decide. */
        NOT_HELD,
    }

    /**
     * What bars the look, or null when media's half allows it:
     * - never a [ModerationVerdict.BLOCKED] verdict, nor anything [evidenceHeld];
     * - only while its objects still exist ([purged] false);
     * - only while it is held back ([MediaStatus.QUARANTINED]) or held by an open report
     *   (a deleted asset under a report's legal hold, ADR-0053 decision 8a).
     */
    fun bar(
        verdict: ModerationVerdict?,
        evidenceHeld: Boolean,
        status: MediaStatus,
        purged: Boolean,
        heldByReport: Boolean,
    ): Bar? =
        when {
            verdict == ModerationVerdict.BLOCKED || evidenceHeld -> Bar.BLOCKED
            purged -> Bar.PURGED
            status != MediaStatus.QUARANTINED && !heldByReport -> Bar.NOT_HELD
            else -> null
        }

    /** Whether media's half allows the look: [bar] is null. */
    fun allows(
        verdict: ModerationVerdict?,
        evidenceHeld: Boolean,
        status: MediaStatus,
        purged: Boolean,
        heldByReport: Boolean,
    ): Boolean = bar(verdict, evidenceHeld, status, purged, heldByReport) == null
}
