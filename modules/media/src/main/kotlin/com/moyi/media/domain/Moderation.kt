package com.moyi.media.domain

/**
 * Moderation's verdict on a photo or a voice note (spec §4 D4, the owner's
 * rulings Q1 and Q16; ADR-0051). Stored as `media_assets.moderation_verdict`.
 */
internal enum class ModerationVerdict {
    /** It may reach the partner. Adult nudity is passed too, marked [Decision.sensitive]. */
    PASSED,

    /** Kept from everyone but its owner, for the operator's review: extreme violence, or nothing could moderate it. */
    QUARANTINED,

    /** Child sexual abuse material: kept from everyone, owner included, never released, reported (Q16). */
    BLOCKED,
}

/**
 * What moderation looks for, in this system's words and not a vendor's (Q16).
 * A moderator maps its own labels onto these in its one class; nothing else
 * reads a vendor's label.
 */
internal enum class ModerationCategory {
    /** Child sexual abuse material, by a classifier. A hash match is not a finding but [Findings.knownImage]. */
    CSAM,

    /** Real violence or gore (Q16's "extreme illegal content"). */
    EXTREME_VIOLENCE,

    /** Adult nudity or sexual activity: allowed between members, and blurred (Q16). */
    ADULT_NUDITY,
}

/** One thing a moderator found, with its confidence from 0 to 100. */
internal data class Finding(
    val category: ModerationCategory,
    val confidence: Double,
)

/**
 * Everything one moderation of an asset found: whether a known-image list
 * matched it, and what the classifier saw. [moderator] and [matcher] name who
 * looked, for the record (`moderation_scores`), never for a decision.
 */
internal data class Findings(
    val knownImage: Boolean,
    val findings: List<Finding>,
    val moderator: String,
    val matcher: String,
    /** The vendor's model or taxonomy version, for the record. */
    val modelVersion: String? = null,
)

/** Why a verdict was given: kept in `moderation_scores` and the audit log, shown to nobody but the operator. */
internal enum class ModerationReason {
    NONE,
    KNOWN_IMAGE,
    CSAM_CLASSIFIER,
    EXTREME_VIOLENCE,

    /** Nothing could moderate it (the moderator refused the bytes): kept back for a person to look at. */
    UNMODERATABLE,
}

/** The verdict, whether to blur it, and why. */
internal data class Decision(
    val verdict: ModerationVerdict,
    val sensitive: Boolean,
    val reason: ModerationReason,
)

/**
 * **The moderation policy, in one place** (Q16, ADR-0051 decision 2). Pure: the
 * consumer gathers [Findings], this decides.
 *
 * 1. A **known image** (a hash list's match) is [ModerationVerdict.BLOCKED],
 *    whatever else was found.
 * 2. **CSAM** from a classifier at [CSAM_AT] or more is BLOCKED. Low on
 *    purpose: a wrong block costs one photo and a person's review; a missed
 *    one costs everything this rule exists for.
 * 3. **Extreme violence** at [EXTREME_VIOLENCE_AT] or more is
 *    [ModerationVerdict.QUARANTINED] for the operator. High on purpose: it
 *    hides a person's photo from their partner, and Q16 keeps back only what
 *    is extreme.
 * 4. Anything else is [ModerationVerdict.PASSED], **sensitive** when adult
 *    nudity is found at [ADULT_NUDITY_AT] or more. Low on purpose: a blur too
 *    many costs a tap.
 *
 * Nothing else is looked for: not weapons, drugs, alcohol, gestures, swimwear,
 * self-harm or hate symbols (ADR-0051 lists what was left out and why).
 */
internal object ModerationPolicy {
    const val CSAM_AT = 50.0
    const val EXTREME_VIOLENCE_AT = 80.0
    const val ADULT_NUDITY_AT = 50.0

    fun decide(found: Findings): Decision {
        val sensitive = found.atLeast(ModerationCategory.ADULT_NUDITY, ADULT_NUDITY_AT)
        return when {
            found.knownImage -> {
                Decision(ModerationVerdict.BLOCKED, sensitive, ModerationReason.KNOWN_IMAGE)
            }

            found.atLeast(ModerationCategory.CSAM, CSAM_AT) -> {
                Decision(ModerationVerdict.BLOCKED, sensitive, ModerationReason.CSAM_CLASSIFIER)
            }

            found.atLeast(ModerationCategory.EXTREME_VIOLENCE, EXTREME_VIOLENCE_AT) -> {
                Decision(ModerationVerdict.QUARANTINED, sensitive, ModerationReason.EXTREME_VIOLENCE)
            }

            else -> {
                Decision(ModerationVerdict.PASSED, sensitive, ModerationReason.NONE)
            }
        }
    }

    /**
     * The moderator could not read what it was given (a refusal it would make
     * again, never an outage): fail closed, kept back for a person to look at.
     * Never `FAILED`: the asset is fine for its owner, and the operator may
     * release it.
     */
    fun unmoderatable(): Decision = Decision(ModerationVerdict.QUARANTINED, false, ModerationReason.UNMODERATABLE)

    private fun Findings.atLeast(
        category: ModerationCategory,
        threshold: Double,
    ): Boolean = findings.any { it.category == category && it.confidence >= threshold }
}
