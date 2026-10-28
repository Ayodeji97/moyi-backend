package com.moyi.identity.domain

/**
 * The rule a display name must meet **when it is written** (ADR-0015, amended 2026-10-28):
 * it holds no control character and no format character but ZWNJ and ZWJ.
 *
 * A display name is shown to the other member: in the bond, on every archive day and in
 * emails. A control character costs six octets a unit on the wire (JSON escapes it), and a
 * name is one line, so tab and newline are controls too. A bidi control (U+202A to U+202E,
 * U+2066 to U+2069, the marks) reorders the text around it on the partner's screen, which
 * makes it a spoofing tool. Both are refused here.
 *
 * - **Every `Cc`**: C0, DEL and C1, tab and newline included.
 * - **Every `Cf` except U+200C ZWNJ and U+200D ZWJ**: the bidi controls, the zero-width
 *   space, word joiner and BOM, the soft hyphen and the tag characters. ZWNJ is how
 *   Persian, Urdu and several Indic scripts are spelt correctly, and ZWJ is how emoji
 *   sequences are built: refusing either would refuse real names.
 *
 * Judged per **code point**, not per `Char`: a surrogate pair is one character, and the tag
 * block (U+E0000 to U+E007F) is outside the BMP, so a per-`Char` test would see two
 * surrogates (`Cs`) and miss it.
 *
 * **Not in [User]'s constructor, deliberately.** `User.init` runs on every row read
 * (`IdentityMappers.toDomain`), so the rule there would make an account already holding
 * such a name unreadable: every sign-in, `/me` and bond view of it a `500`. `User.init`
 * keeps only what V2's CHECK constraint says. This is called where a name is written
 * (`RegisterUser`), and the edge asks it (`web.ValidDisplayName`).
 *
 * The message names the rule and never the name, because an exception message reaches logs.
 */
internal object DisplayNames {
    const val CONTROL_OR_FORMAT_MESSAGE = "display name must not contain control or formatting characters"

    /** Throws [IllegalArgumentException] if [raw] holds a refused character. Blank and length are [User]'s. */
    fun check(raw: String) {
        require(raw.codePoints().noneMatch(::isRefused)) { CONTROL_OR_FORMAT_MESSAGE }
    }

    private fun isRefused(codePoint: Int): Boolean =
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL -> true
            Character.FORMAT -> codePoint != ZERO_WIDTH_NON_JOINER && codePoint != ZERO_WIDTH_JOINER
            else -> false
        }

    private const val ZERO_WIDTH_NON_JOINER = 0x200C
    private const val ZERO_WIDTH_JOINER = 0x200D
}
