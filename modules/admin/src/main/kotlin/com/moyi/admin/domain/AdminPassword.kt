package com.moyi.admin.domain

/**
 * An operator password that passed the length policy (spec §6): 15 to 128 Unicode
 * code points, no composition rules. Counted in code points so an emoji is one.
 * Never printed: [toString] hides the value.
 */
@JvmInline
internal value class AdminPassword private constructor(
    val value: String,
) {
    override fun toString() = "AdminPassword(****)"

    companion object {
        const val MIN_LENGTH = 15
        const val MAX_LENGTH = 128

        fun of(raw: String): AdminPassword? = if (raw.codePointCount(0, raw.length) in MIN_LENGTH..MAX_LENGTH) AdminPassword(raw) else null
    }
}
