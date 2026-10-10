package com.moyi.gratitude.domain

/**
 * What a member may leave on their partner's revealed entry (FR-048): one of
 * a small fixed set, and nothing else. No text and no count.
 *
 * **A placeholder set** (the owner's ruling of 2026-10-06, and the owner's
 * word): four names to build against, not a final design. The names are the
 * wire values, the contract's enum, and what V23's `CHECK` holds, so a
 * change to this list is a migration and a breaking change for a generated
 * client, never a rename in passing.
 */
internal enum class ReactionType {
    HEART,
    THANK_YOU,
    SMILE,
    MOVED,
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
