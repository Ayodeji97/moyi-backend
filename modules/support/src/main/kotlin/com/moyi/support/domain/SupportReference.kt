package com.moyi.support.domain

/**
 * The person's error reference (ADR-0057 decision 11, issue #168): what the app showed them beside an
 * error, sent along when they opened support from it, so the operator can find the failing request.
 * Usually that error's `traceId`, but nothing here promises it is one: it is whatever the client sent,
 * kept exactly as sent.
 *
 * **1 to [MAX_CHARACTERS] visible ASCII characters** (0x21 `!` to 0x7E `~`): no space, no control
 * character, nothing past ASCII. A trace id needs no more, and a value that cannot carry a new line,
 * an escape or a look-alike letter cannot forge a line in the operator's terminal. V55's `CHECK` is
 * this rule's second line.
 *
 * **Never printed**, as the subject and body are not: it goes to the operator through
 * `support_ticket_read` and to the person in their own export, and nowhere else; never into the
 * acknowledgement email, never into a log. [toString] says only how long it is.
 */
internal class SupportReference private constructor(
    val value: String,
) {
    override fun toString(): String = "SupportReference(${value.length} characters)"

    override fun equals(other: Any?): Boolean = other is SupportReference && other.value == value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** V55's `support_tickets_reference_check` states the same bound. */
        const val MAX_CHARACTERS = 64

        /** @throws IllegalArgumentException with a message the edge reports, never the value */
        fun of(raw: String): SupportReference {
            require(raw.length in 1..MAX_CHARACTERS && raw.all { it in VISIBLE_ASCII }) {
                "must be 1 to $MAX_CHARACTERS visible ASCII characters"
            }
            return SupportReference(raw)
        }

        private val VISIBLE_ASCII = '!'..'~'
    }
}
