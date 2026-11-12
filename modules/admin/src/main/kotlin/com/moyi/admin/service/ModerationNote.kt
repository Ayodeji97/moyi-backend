package com.moyi.admin.service

/**
 * The moderator's note (A2 spec §5.1, §10.3): required, 1–[MAX_CODE_POINTS] code points after
 * trimming, and no NUL (Postgres refuses one in text). The rule is written here and nowhere
 * else; a form asks [of] for its answer.
 *
 * Why 400: `entry_report_uphold` writes `left(action || ': ' || note, 500)` and
 * `entry_report_reclassify` prefixes two reason names, so 400 keeps every note whole inside
 * the 500 the log's `CHECK` allows. A longer note would reach that `CHECK`, whose violation
 * pgjdbc reports with the failing row, the note in it, in the exception's message.
 *
 * **Never printed.** [toString] says only that a note is there.
 */
@JvmInline
internal value class ModerationNote private constructor(
    val text: String,
) {
    override fun toString(): String = "ModerationNote(<redacted>)"

    companion object {
        const val MAX_CODE_POINTS = 400

        /** The note, trimmed, or null when it breaks the rule. */
        fun of(raw: String?): ModerationNote? =
            raw
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= MAX_CODE_POINTS && '\u0000' !in it }
                ?.let(::ModerationNote)

        /** [of], or [InvalidModerationNote]: for the console's methods, before any port is called. */
        fun require(raw: String?): ModerationNote = of(raw) ?: throw InvalidModerationNote()
    }
}

/** A decision's note broke [ModerationNote]'s rule. Its message never carries the note. */
internal class InvalidModerationNote :
    IllegalArgumentException(
        "A moderation note is required: 1 to ${ModerationNote.MAX_CODE_POINTS} characters, no NUL.",
    )
