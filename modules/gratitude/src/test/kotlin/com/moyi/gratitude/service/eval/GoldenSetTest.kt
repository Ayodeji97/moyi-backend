package com.moyi.gratitude.service.eval

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The golden set is what Task 9's Step 1 says it is, so a later edit cannot quietly make the
 * eval measure word overlap instead of meaning, or make a day stand for two entries.
 */
internal class GoldenSetTest {
    private val golden = GoldenSet.load()
    private val entries = golden.bonds.flatMap { it.entries }.associateBy { it.key }

    @Test
    fun `150 entries across 3 bonds of two writers each, and 60 queries`() {
        golden.bonds shouldHaveSize 3
        entries.size shouldBe 150
        golden.bonds.forEach { withClue(it.key) { it.authors shouldHaveSize 2 } }
        golden.queries shouldHaveSize 60
        (entries.keys + golden.queries.map { it.key }).size shouldBe 210
    }

    @Test
    fun `20 queries are ambiguous, with 2 to 4 relevant entries, and 10 have none`() {
        golden.queries.count { it.relevant.size in 2..4 } shouldBe 20
        golden.queries.count { it.relevant.isEmpty() } shouldBe 10
        golden.queries.forEach { withClue(it.key) { it.relevant.size shouldBeInRange 0..4 } }
    }

    @Test
    fun `every relevant entry is in its query's bond`() {
        golden.queries.forEach { query ->
            val bond =
                golden.bonds
                    .single { it.key == query.bond }
                    .entries
                    .map { it.key }
                    .toSet()
            withClue(query.key) { (query.relevant - bond).shouldBeEmpty() }
        }
    }

    /** The eval maps a day the API answers with back to its entry: that needs one entry a day. */
    @Test
    fun `each entry has a date of its own in its bond`() {
        golden.bonds.forEach { bond ->
            withClue(bond.key) {
                bond.entries
                    .map { it.date }
                    .toSet()
                    .size shouldBe bond.entries.size
            }
        }
    }

    /**
     * Content words compared after a crude stem (a trailing -ing, -ed, -es, -s or -ly off), so
     * that "passed" and "passing" count as one word. Stricter than the search, which matches
     * whole words, and that is the point: a paraphrase must not lean on a shared root either.
     */
    @Test
    fun `no query shares a content word with an entry relevant to it`() {
        golden.queries.forEach { query ->
            query.relevant.forEach { key ->
                withClue(
                    "${query.key} and $key",
                ) { (contentWords(query.text) intersect contentWords(entries.getValue(key).text)).shouldBeEmpty() }
            }
        }
    }

    private fun contentWords(text: String): Set<String> =
        Regex("[a-z0-9']+")
            .findAll(text.lowercase())
            .map { it.value }
            .filterNot { it in STOP_WORDS }
            .map(::stem)
            .toSet()

    private fun stem(word: String): String =
        SUFFIXES.firstOrNull { word.endsWith(it) && word.length - it.length >= MIN_STEM }?.let { word.dropLast(it.length) } ?: word

    private companion object {
        const val MIN_STEM = 3
        val SUFFIXES = listOf("ing", "ed", "es", "s", "ly")
        val STOP_WORDS =
            (
                "a an the and or but if of in on at to for with by from up down out over under about into onto off as " +
                    "is was were be been being am are it its it's this that these those i me my mine myself we us our you your " +
                    "he him his she her they them their what which who whom when where why how not no so too very just only also " +
                    "all any both each other some such than then there here can will would should could do does did doing has " +
                    "have had after before while until during because don't didn't wasn't wouldn't couldn't she's he'd"
            ).split(' ').toSet()
    }
}
