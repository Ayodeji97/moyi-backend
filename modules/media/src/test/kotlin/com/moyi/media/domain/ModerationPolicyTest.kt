package com.moyi.media.domain

import com.moyi.media.domain.ModerationCategory.ADULT_NUDITY
import com.moyi.media.domain.ModerationCategory.CSAM
import com.moyi.media.domain.ModerationCategory.EXTREME_VIOLENCE
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The owner's ruling Q16 as one pure rule (ADR-0051 decision 2), each verdict
 * and each threshold on both sides of its edge.
 */
internal class ModerationPolicyTest {
    private fun found(
        vararg findings: Finding,
        known: Boolean = false,
    ) = Findings(knownImage = known, findings = findings.toList(), moderator = "fake", matcher = "fake")

    @Test
    fun `nothing found passes, not sensitive`() {
        ModerationPolicy.decide(found()) shouldBe Decision(ModerationVerdict.PASSED, false, ModerationReason.NONE)
    }

    @Test
    fun `a known image is blocked, whatever the classifier found or did not`() {
        ModerationPolicy.decide(found(known = true)) shouldBe Decision(ModerationVerdict.BLOCKED, false, ModerationReason.KNOWN_IMAGE)
        ModerationPolicy.decide(found(Finding(ADULT_NUDITY, 99.0), known = true)).verdict shouldBe ModerationVerdict.BLOCKED
    }

    @Test
    fun `CSAM from a classifier is blocked from its threshold, and is not below it`() {
        ModerationPolicy.decide(found(Finding(CSAM, ModerationPolicy.CSAM_AT))) shouldBe
            Decision(ModerationVerdict.BLOCKED, false, ModerationReason.CSAM_CLASSIFIER)
        ModerationPolicy.decide(found(Finding(CSAM, ModerationPolicy.CSAM_AT - 0.1))).verdict shouldBe ModerationVerdict.PASSED
    }

    @Test
    fun `extreme violence is quarantined from its threshold, and passes below it`() {
        ModerationPolicy.decide(found(Finding(EXTREME_VIOLENCE, ModerationPolicy.EXTREME_VIOLENCE_AT))) shouldBe
            Decision(ModerationVerdict.QUARANTINED, false, ModerationReason.EXTREME_VIOLENCE)
        ModerationPolicy.decide(found(Finding(EXTREME_VIOLENCE, ModerationPolicy.EXTREME_VIOLENCE_AT - 0.1))).verdict shouldBe
            ModerationVerdict.PASSED
    }

    /** Q16: adult nudity between members is allowed, never quarantined, and blurred. */
    @Test
    fun `adult nudity passes, sensitive from its threshold and not below it`() {
        ModerationPolicy.decide(found(Finding(ADULT_NUDITY, ModerationPolicy.ADULT_NUDITY_AT))) shouldBe
            Decision(ModerationVerdict.PASSED, true, ModerationReason.NONE)
        ModerationPolicy.decide(found(Finding(ADULT_NUDITY, 100.0))).verdict shouldBe ModerationVerdict.PASSED
        ModerationPolicy.decide(found(Finding(ADULT_NUDITY, ModerationPolicy.ADULT_NUDITY_AT - 0.1))) shouldBe
            Decision(ModerationVerdict.PASSED, false, ModerationReason.NONE)
    }

    @Test
    fun `blocked outranks quarantined, and a held-back photo that is also nude keeps its sensitive mark`() {
        ModerationPolicy.decide(found(Finding(EXTREME_VIOLENCE, 99.0), Finding(CSAM, 99.0))).verdict shouldBe ModerationVerdict.BLOCKED
        ModerationPolicy.decide(found(Finding(EXTREME_VIOLENCE, 99.0), Finding(ADULT_NUDITY, 99.0))) shouldBe
            Decision(ModerationVerdict.QUARANTINED, true, ModerationReason.EXTREME_VIOLENCE)
    }

    @Test
    fun `what nothing could moderate is held back for a person, never passed and never failed`() {
        ModerationPolicy.unmoderatable() shouldBe Decision(ModerationVerdict.QUARANTINED, false, ModerationReason.UNMODERATABLE)
    }
}
