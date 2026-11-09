package com.moyi.support.domain

/**
 * A support message's subject (ADR-0057 decision 3): what a person writes to a person, at most
 * [MAX_CHARACTERS] code points. The rule is [supportText]'s, shared with [SupportBody]; V45's
 * `CHECK` is its second line.
 *
 * **Never printed.** It is what somebody wrote about their account, their partner or their life;
 * it goes to the operator through `support_ticket_read`, which records the look, and nowhere else.
 * [toString] says only how long it is.
 */
internal class SupportSubject private constructor(
    val value: String,
) {
    override fun toString(): String = "SupportSubject(${value.codePointCount(0, value.length)} code points)"

    override fun equals(other: Any?): Boolean = other is SupportSubject && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** V45's `support_tickets_subject_check` counts the same unit: `char_length` is code points. */
        const val MAX_CHARACTERS = 120

        fun of(raw: String): SupportSubject = SupportSubject(supportText(raw, MAX_CHARACTERS))
    }
}

/** A support message's body (ADR-0057 decision 3): [SupportSubject]'s rule, at most [MAX_CHARACTERS]. Never printed. */
internal class SupportBody private constructor(
    val value: String,
) {
    override fun toString(): String = "SupportBody(${value.codePointCount(0, value.length)} code points)"

    override fun equals(other: Any?): Boolean = other is SupportBody && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** V45's `support_tickets_body_check` counts the same unit. */
        const val MAX_CHARACTERS = 5000

        fun of(raw: String): SupportBody = SupportBody(supportText(raw, MAX_CHARACTERS))
    }
}

/**
 * The one rule for a support message's text, kept exactly as sent: no U+0000 (Postgres `text` cannot
 * hold it), no unpaired surrogate (it has no UTF-8 form, and the driver would store `?`), not blank
 * (Kotlin's `isNotBlank`, which counts a non-breaking space as blank), and at most [max] code points,
 * the unit `char_length` counts, so that this and the table's `CHECK` agree on every string. Code points
 * rather than the entry's graphemes (ADR-0031 ruling 1): this is read by an operator, not shown under a
 * counter beside a partner's entry, and a database `CHECK` can state it (ADR-0053 decision 3's precedent).
 *
 * @throws IllegalArgumentException with a message the edge reports, never the text
 */
private fun supportText(
    raw: String,
    max: Int,
): String {
    require('\u0000' !in raw) { "must not contain U+0000" }
    require(!raw.hasUnpairedSurrogate()) { "must be valid Unicode" }
    require(raw.isNotBlank()) { "must not be blank" }
    require(raw.codePointCount(0, raw.length) <= max) { "must be at most $max characters" }
    return raw
}

private fun String.hasUnpairedSurrogate(): Boolean =
    indices.any { i ->
        val c = this[i]
        (c.isHighSurrogate() && (i + 1 >= length || !this[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !this[i - 1].isHighSurrogate()))
    }
