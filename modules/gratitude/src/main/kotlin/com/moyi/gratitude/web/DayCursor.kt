package com.moyi.gratitude.web

import java.time.LocalDate
import java.util.Base64

/**
 * A date exactly as ISO 8601 writes a calendar date, `YYYY-MM-DD`, or `null`:
 * four digits, two, two, and a day that exists. `2026-02-30` is not one, nor
 * is `2026-2-3`, nor a date with a sign, a time or a space.
 *
 * The shape is checked before the parser is asked, because the parser alone
 * also takes a signed year of any length. One function for the `until`
 * parameter and for the date inside a cursor, so the two cannot differ.
 */
internal fun strictIsoDate(raw: String): LocalDate? =
    raw.takeIf(ISO_DATE_SHAPE::matches)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private val ISO_DATE_SHAPE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

/**
 * Where a page of the archive ended: [before], the date of its last day. The
 * next page is the days strictly before it.
 *
 * **Opaque to a client, and nothing but a date.** On the wire it is the
 * base64url, without padding, of `v1:` and the date. It is encoded so that a
 * client treats it as a token to hand back and not as a field to compute
 * with, which is what lets the form change behind a new version prefix.
 *
 * **It is not bound to a bond or to a caller, and does not need to be.**
 * There is nothing in it to protect: no id, no member, nothing the server
 * knows that the client does not. A cursor taken from another bond's feed is
 * simply a date, and means here what that date means here. A client that
 * makes one up has done no more than choose where to start reading, which
 * `until` lets it do openly. What the caller may read is decided by the
 * membership guard and the read gate on every request, cursor or no cursor.
 *
 * **Read strictly.** [parse] takes exactly what [encode] writes and nothing
 * near it: not padding, not another alphabet, not a later version, not a
 * loose date, nothing after the date. A value this API did not issue is a
 * `422`, never a guess at what was meant.
 */
@JvmInline
internal value class DayCursor(
    val before: LocalDate,
) {
    fun encode(): String = ENCODER.encodeToString("$PREFIX$before".toByteArray(Charsets.US_ASCII))

    companion object {
        private const val PREFIX = "v1:"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()

        /**
         * The cursor [raw] is, or `null` when it is not one. Decoded,
         * checked, and then **encoded again and compared**: the decoder
         * forgives padding and stray trailing bits, and a cursor has one
         * spelling.
         */
        fun parse(raw: String): DayCursor? {
            val plain = runCatching { String(DECODER.decode(raw), Charsets.US_ASCII) }.getOrNull() ?: return null
            val date = plain.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.let(::strictIsoDate)
            return date?.let(::DayCursor)?.takeIf { it.encode() == raw }
        }
    }
}
