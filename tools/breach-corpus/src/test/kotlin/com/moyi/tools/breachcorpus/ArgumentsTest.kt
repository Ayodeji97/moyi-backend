package com.moyi.tools.breachcorpus

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

internal class ArgumentsTest {
    @Test
    fun `flags are read, and defaults fill the rest`() {
        val arguments = Arguments(arrayOf("--output", "corpus.bloom", "--ranges", "300"))

        arguments.require("--output") shouldBe "corpus.bloom"
        arguments.int("--ranges", PwnedRange.TOTAL_PREFIXES) shouldBe 300
        arguments.int("--min-count", BuildOptions.DEFAULT_MIN_COUNT) shouldBe BuildOptions.DEFAULT_MIN_COUNT
        arguments.double("--fpr", BuildOptions.DEFAULT_FALSE_POSITIVE_RATE) shouldBe BuildOptions.DEFAULT_FALSE_POSITIVE_RATE
    }

    @Test
    fun `an unrecognised flag is fatal, not ignored`() {
        // The version before this silently dropped it, so a typo in --ranges
        // did not produce an error. It produced a full 1,048,576-range run:
        // an hour and ~70 GB, when a thirty-second smoke test was asked for.
        val rejected = shouldThrow<IllegalArgumentException> { Arguments(arrayOf("--output", "x", "--ranegs", "50")) }

        rejected.message shouldContain "--ranegs"
        rejected.message shouldContain "known flags are"
    }

    @Test
    fun `an odd number of arguments is fatal`() {
        // Otherwise the last flag is silently dropped, which is the same
        // failure as the typo above wearing different clothes.
        shouldThrow<IllegalArgumentException> { Arguments(arrayOf("--output", "x", "--ranges")) }
    }

    @Test
    fun `a missing required flag names itself`() {
        val missing = shouldThrow<IllegalStateException> { Arguments(emptyArray()).require("--output") }

        missing.message shouldContain "--output"
    }
}
