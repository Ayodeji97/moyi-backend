package com.moyi.common.core

/**
 * The switches an `ADMIN` turns at runtime (FR-115, ADR-0067 decisions 7 to 11). Each pauses one
 * optional effect, a send to a person or to a third party, and each "off" is a state the code already
 * has. A closed set, written once here: V67's `CHECK` on `feature_flags.key` repeats it for the
 * database and `FeatureFlagCheckTest` holds the two together.
 *
 * Only names, defaults and what each pauses live here, so that any module can read a flag without
 * depending on `admin`, which owns the table and is the only writer. No rule is in this file: what
 * "off" does is in the one place that reads the flag.
 */
enum class FeatureFlag(
    /** What a flag with no row is: the table starts empty, so every flag starts here. */
    val defaultOn: Boolean,
    /** What switching it off pauses, as the console says it. */
    val pauses: String,
) {
    /** `ReminderJob` asks for no daily reminder while off. One that falls due meanwhile is not sent later. */
    DAILY_REMINDERS(true, "Daily reminder pushes. A reminder that falls due while this is off is not sent later."),

    /** `OnThisDayJob` asks for no on-this-day push while off. One that falls due meanwhile is not sent later. */
    ON_THIS_DAY_PUSHES(true, "On-this-day pushes. A push that falls due while this is off is not sent later."),

    /**
     * The `ai` module's `EmbeddingPort` refuses every call while off, so no text reaches the embedding
     * provider: a search is keywords only, and the embedding job skips its run. Nothing stored is erased.
     */
    AI_EMBEDDINGS(
        true,
        "Sending entries and searches to the embedding provider. Searches use keywords only; nothing stored is erased.",
    ),
}

/**
 * Whether a [FeatureFlag] is on now. Every call reads the store: there is no cache, so a switch is
 * seen by the next call on every instance (ADR-0067 decision 8). A flag with no row is its
 * [FeatureFlag.defaultOn].
 */
fun interface FeatureFlags {
    fun isOn(flag: FeatureFlag): Boolean
}
