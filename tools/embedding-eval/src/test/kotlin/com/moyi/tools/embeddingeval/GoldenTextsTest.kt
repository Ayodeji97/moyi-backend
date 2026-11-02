package com.moyi.tools.embeddingeval

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

internal class GoldenTextsTest {
    @Test
    fun `every entry and every query is read with its key, entries first, in file order`() {
        val golden = GoldenTexts.read(GOLDEN)

        golden.entries.map { it.key to it.text } shouldBe listOf("e1" to "soup when ill", "e2" to "a lamp 🕯️", "e3" to "bread")
        golden.queries.map { it.key to it.text } shouldBe listOf("q1" to "being looked after", "q2" to "light")
    }

    @Test
    fun `a text is never printed`() {
        GoldenTexts
            .read(GOLDEN)
            .entries
            .first()
            .toString() shouldNotContain "soup"
    }

    @Test
    fun `a key used twice is refused, since the vectors file is keyed by it`() {
        shouldThrow<IllegalArgumentException> {
            GoldenTexts.read(GOLDEN.replace("\"e3\"", "\"e1\""))
        }.message shouldBe "golden.json uses a key twice: e1"
    }

    companion object {
        const val GOLDEN = """
            { "bonds": [
                { "key": "b1", "entries": [
                    { "key": "e1", "author": "ada", "date": "2026-01-01", "text": "soup when ill" },
                    { "key": "e2", "author": "bea", "date": "2026-01-02", "text": "a lamp 🕯️" } ] },
                { "key": "b2", "entries": [ { "key": "e3", "author": "cal", "date": "2026-01-01", "text": "bread" } ] } ],
              "queries": [
                { "key": "q1", "bond": "b1", "q": "being looked after", "relevant": ["e1"] },
                { "key": "q2", "bond": "b1", "q": "light", "relevant": [] } ] }
        """
    }
}
