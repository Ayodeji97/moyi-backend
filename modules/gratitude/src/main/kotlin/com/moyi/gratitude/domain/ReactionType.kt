package com.moyi.gratitude.domain

/**
 * What a member may leave on their partner's revealed entry (FR-048): one of
 * a small fixed set, and nothing else. No text and no count.
 *
 * **Five, by the owner's ruling of 2026-10-21** (ADR-0037 question 1),
 * which replaced the placeholder four of 2026-10-06 by adding [ME_TOO]
 * ("I'm grateful for that too") last. How each is drawn is the design's.
 * The names are the wire values, the contract's enum, and what the `CHECK`
 * holds (V23, replaced by V27), so a change to this list is a migration and
 * a breaking change for a generated client, never a rename in passing.
 */
internal enum class ReactionType {
    HEART,
    THANK_YOU,
    SMILE,
    MOVED,
    ME_TOO,
    ;

    companion object {
        /**
         * The type with exactly this name, or `null`. Exact: `heart` is not
         * [HEART], and nothing is trimmed. The one place a name that arrived
         * as text is matched against the set, so that the web edge asks this
         * and does not keep a list of its own.
         */
        fun named(name: String): ReactionType? = entries.firstOrNull { it.name == name }
    }
}
