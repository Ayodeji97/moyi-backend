package com.moyi.common.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * RFC 9110 §13.1.1 is short, and every clause of it is a decision this test
 * pins — including the two places `Preconditions.kt` knowingly differs from the
 * letter of it, which are recorded there and in ADR-0029.
 *
 * The asymmetry worth keeping in mind while reading: a false "does not match"
 * costs a client one retry, and a false "matches" is a silently lost update. So
 * every ambiguous input is asserted to *fail* the match.
 */
internal class IfMatchTest {
    @Test
    fun `opaque tags compare exactly and commas inside quotes are not separators`() {
        listOf("\"03\"", "\"+3\"", "\"prefix, \"3\"", "garbage, \"3\"").forEach {
            IfMatch.parse(it).matches(3) shouldBe false
        }
        IfMatch.parse("W/\"abc\", \"a,b\", \"abc\"").matches("\"a,b\"") shouldBe true
        IfMatch.parse("\"abc\"").matches("\"abc\"") shouldBe true
        IfMatch.parse("W/\"abc\"").matches("\"abc\"") shouldBe false
    }

    @Test
    fun `a quoted version matches that version and no other`() {
        val ifMatch = IfMatch.parse("\"3\"")

        ifMatch.matches(3) shouldBe true
        ifMatch.matches(4) shouldBe false
        ifMatch.matches(0) shouldBe false
    }

    @Test
    fun `a list matches if any member matches`() {
        // RFC 9110: `If-Match: "1", "3"` is a list, and any match is a match.
        val ifMatch = IfMatch.parse("\"1\", \"3\"")

        ifMatch.matches(1) shouldBe true
        ifMatch.matches(3) shouldBe true
        ifMatch.matches(2) shouldBe false
    }

    @Test
    fun `a weak validator never matches`() {
        // RFC 9110 §13.1.1: If-Match uses the strong comparison function, and a
        // weak validator can never be strongly compared. 412 rather than 428 —
        // the client did send a precondition, it just cannot be honoured.
        IfMatch.parse("W/\"3\"").matches(3) shouldBe false
    }

    @Test
    fun `anything unparseable matches nothing rather than everything`() {
        listOf("3", "\"\"", "\"abc\"", "\"3", "3\"", "\" 3 \"", "\"-1\"", "\"3\" \"4\"").forEach { header ->
            IfMatch.parse(header).matches(3) shouldBe false
        }
    }

    @Test
    fun `an absent or blank header is a 428`() {
        shouldThrow<PreconditionRequiredException> { IfMatch.parse(null) }
        shouldThrow<PreconditionRequiredException> { IfMatch.parse("") }
        shouldThrow<PreconditionRequiredException> { IfMatch.parse("   ") }
    }

    @Test
    fun `a star is a 428, deliberately`() {
        // RFC 9110 says `*` matches any current representation, so a compliant
        // server would let the write through. We refuse it: doc 06 §1 requires
        // the condition in order to prevent a lost update, and `*` asks to skip
        // exactly that. ADR-0029 records the deviation.
        shouldThrow<PreconditionRequiredException> { IfMatch.parse("*") }
        shouldThrow<PreconditionRequiredException> { IfMatch.parse(" * ") }
    }
}
