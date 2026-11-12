package com.moyi.media.domain

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Media's half of the view rule (A2 spec §7.4): each clause on its own. */
internal class ModeratorReviewTest {
    @Test
    fun `a photo moderation held back may be looked at`() {
        ModeratorReview.bar(ModerationVerdict.QUARANTINED, false, MediaStatus.QUARANTINED, false, false) shouldBe null
        ModeratorReview.allows(ModerationVerdict.QUARANTINED, false, MediaStatus.QUARANTINED, false, false) shouldBe true
    }

    @Test
    fun `a deleted asset a report still holds may be looked at while its objects exist`() {
        ModeratorReview.allows(ModerationVerdict.PASSED, false, MediaStatus.DELETED, purged = false, heldByReport = true) shouldBe
            true
    }

    @Test
    fun `a blocked asset is never looked at, wherever it is`() {
        ModeratorReview.bar(ModerationVerdict.BLOCKED, true, MediaStatus.QUARANTINED, false, false) shouldBe ModeratorReview.Bar.BLOCKED
        ModeratorReview.bar(ModerationVerdict.BLOCKED, true, MediaStatus.QUARANTINED, false, true) shouldBe ModeratorReview.Bar.BLOCKED
        ModeratorReview.bar(ModerationVerdict.BLOCKED, true, MediaStatus.DELETED, false, true) shouldBe ModeratorReview.Bar.BLOCKED
        // The verdict alone bars it: a row whose hold flag were somehow off is still never shown.
        ModeratorReview.bar(ModerationVerdict.BLOCKED, false, MediaStatus.QUARANTINED, false, false) shouldBe ModeratorReview.Bar.BLOCKED
    }

    @Test
    fun `evidence held is never looked at, whatever the verdict says`() {
        ModeratorReview.bar(ModerationVerdict.QUARANTINED, true, MediaStatus.QUARANTINED, false, false) shouldBe
            ModeratorReview.Bar.BLOCKED
    }

    @Test
    fun `a ready passed asset nothing holds is not the moderator's to look at`() {
        ModeratorReview.bar(ModerationVerdict.PASSED, false, MediaStatus.READY, false, false) shouldBe ModeratorReview.Bar.NOT_HELD
        ModeratorReview.bar(null, false, MediaStatus.DELETED, false, false) shouldBe ModeratorReview.Bar.NOT_HELD
    }

    @Test
    fun `a purged asset has nothing to look at, even under a report`() {
        ModeratorReview.bar(ModerationVerdict.PASSED, false, MediaStatus.DELETED, purged = true, heldByReport = true) shouldBe
            ModeratorReview.Bar.PURGED
    }

    @Test
    fun `allows is false wherever a bar applies`() {
        ModeratorReview.allows(ModerationVerdict.BLOCKED, true, MediaStatus.QUARANTINED, false, false) shouldBe false
        ModeratorReview.allows(ModerationVerdict.BLOCKED, false, MediaStatus.QUARANTINED, false, true) shouldBe false
        ModeratorReview.allows(ModerationVerdict.QUARANTINED, true, MediaStatus.QUARANTINED, false, false) shouldBe false
        ModeratorReview.allows(ModerationVerdict.PASSED, false, MediaStatus.READY, false, false) shouldBe false
        ModeratorReview.allows(ModerationVerdict.QUARANTINED, false, MediaStatus.QUARANTINED, purged = true, heldByReport = false) shouldBe
            false
    }
}
