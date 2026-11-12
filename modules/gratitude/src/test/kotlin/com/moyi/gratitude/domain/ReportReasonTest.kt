package com.moyi.gratitude.domain

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test

/**
 * Gratitude's half of the view rule (A2 spec §7.4): which reasons let a moderator look at
 * the media a report holds. Never for sexual content involving a minor (runbook §2, §3.1:
 * never viewed, at any step) and never for non-consensual imagery (runbook §3.2: looking is
 * the harm itself; the owner's ruling §14a Q3).
 */
internal class ReportReasonTest {
    @Test
    fun `the media of a priority or a consent report is never reviewable, of the other three it is`() {
        ReportReason.entries.filterNot { it.mediaReviewable } shouldContainExactlyInAnyOrder
            listOf(ReportReason.MINOR_SEXUAL_CONTENT, ReportReason.NON_CONSENSUAL)
        ReportReason.entries.filter { it.mediaReviewable } shouldContainExactlyInAnyOrder
            listOf(ReportReason.VIOLENCE_OR_SELF_HARM, ReportReason.HARASSMENT, ReportReason.OTHER)
    }

    @Test
    fun `every priority reason is one whose media is never viewed`() {
        ReportReason.entries.filter { it.priority }.filter { it.mediaReviewable } shouldContainExactlyInAnyOrder emptyList()
    }
}
