package com.moyi.gratitude.domain

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

/**
 * The prompt of the day (ADR-0038, controller ruling 1): a pure function of the bond, the
 * bond-local date and the library. Property-style, as `StreakPropertiesTest` is: seeded
 * randoms, so a failure is reproducible from the seed in its clue.
 *
 * What is NOT a parameter is half the point. There is no member, no entry, no day status
 * and no history in the signature: nothing anybody does can reach the answer (FR-064).
 */
internal class PromptOfTheDayTest {
    private fun id(n: Int) = PromptId(UUID.fromString("01990000-0000-7000-8000-%012x".format(n)))

    private fun library(
        random: Random,
        size: Int,
    ): List<LibraryPrompt> = List(size) { LibraryPrompt(id(it + 1), PromptAudience.entries[random.nextInt(PromptAudience.entries.size)]) }

    private fun eligible(
        library: List<LibraryPrompt>,
        overlay: PromptAudience,
    ) = library.filter { it.audience == PromptAudience.CORE || it.audience == overlay }.map { it.id }

    @Test
    fun `the same bond, date and library give the same prompt, whatever order the library is read in`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val bond = UUID(random.nextLong(), random.nextLong())
            val lib = library(random, random.nextInt(1, 40))
            val date = LocalDate.of(2026, 1, 1).plusDays(random.nextLong(0, 2000))
            val overlay = PromptAudience.entries[random.nextInt(4)]
            withClue("seed $seed") {
                PromptOfTheDay.choose(bond, date, overlay, lib) shouldBe PromptOfTheDay.choose(bond, date, overlay, lib.shuffled(random))
            }
        }
    }

    @Test
    fun `no prompt comes back within any run of days as long as the cycle, and every eligible prompt comes once`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val bond = UUID(random.nextLong(), random.nextLong())
            val lib = library(random, random.nextInt(1, 40))
            val overlay = PromptAudience.entries[random.nextInt(4)]
            val pool = eligible(lib, overlay)
            if (pool.isEmpty()) return@repeat
            // Any start, not one aligned with the cycle: the rule holds for every window.
            val start = LocalDate.of(2025, 6, 1).plusDays(random.nextLong(0, 3000))
            val run = (0 until pool.size).map { PromptOfTheDay.choose(bond, start.plusDays(it.toLong()), overlay, lib)!! }
            withClue("seed $seed") { run shouldContainExactlyInAnyOrder pool }
        }
    }

    @Test
    fun `only the shared core and the bond's own overlay are ever chosen, and an empty overlay leaves the core`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val lib = library(random, random.nextInt(1, 40))
            val overlay = PromptAudience.entries[random.nextInt(1, 4)]
            val others = lib.filter { it.audience != PromptAudience.CORE && it.audience != overlay }.map { it.id }
            val bond = UUID(random.nextLong(), random.nextLong())
            (0L until 60L).forEach { day ->
                val chosen = PromptOfTheDay.choose(bond, LocalDate.of(2026, 3, 1).plusDays(day), overlay, lib)
                withClue("seed $seed day $day") { others shouldNotContain chosen }
            }
        }
        val coreOnly = listOf(LibraryPrompt(id(1), PromptAudience.CORE), LibraryPrompt(id(2), PromptAudience.COUPLE))
        PromptOfTheDay.cycle(UUID(1, 2), PromptAudience.FAMILY, coreOnly) shouldBe listOf(id(1))
    }

    @Test
    fun `an empty library, or one with nothing for this audience, is no prompt and not an error`() {
        PromptOfTheDay.choose(UUID(1, 2), LocalDate.of(2026, 10, 15), PromptAudience.COUPLE, emptyList()).shouldBeNull()
        val onlyFamily = listOf(LibraryPrompt(id(1), PromptAudience.FAMILY))
        PromptOfTheDay.choose(UUID(1, 2), LocalDate.of(2026, 10, 15), PromptAudience.COUPLE, onlyFamily).shouldBeNull()
    }

    @Test
    fun `a library that shrinks still answers, and never with a prompt it no longer has`() {
        repeat(200) { seed ->
            val random = Random(seed)
            val lib = library(random, random.nextInt(2, 40))
            val kept = lib.filter { random.nextBoolean() }
            val bond = UUID(random.nextLong(), random.nextLong())
            val date = LocalDate.of(2026, 10, 15).plusDays(random.nextLong(0, 400))
            val overlay = PromptAudience.entries[random.nextInt(4)]
            val chosen = PromptOfTheDay.choose(bond, date, overlay, kept)
            withClue("seed $seed") {
                if (eligible(kept, overlay).isEmpty()) chosen.shouldBeNull() else eligible(kept, overlay) shouldContain chosen
            }
        }
    }

    @Test
    fun `two bonds are dealt the library in different orders`() {
        val lib = (1..12).map { LibraryPrompt(id(it), PromptAudience.CORE) }
        val orders = (1..50L).map { PromptOfTheDay.cycle(UUID(it, it), PromptAudience.COUPLE, lib) }.toSet()
        // Fifty bonds, twelve prompts: a rule that ignored the bond would deal one order to all of them.
        (orders.size > 40) shouldBe true
    }

    @Test
    fun `the order is a known answer, so a change to the hash is seen before it reshuffles every bond`() {
        // Computed independently (python3, hashlib.sha256 over the label and the two UUIDs' bytes,
        // first eight bytes unsigned big-endian; 2026-10-15 is epoch day 20741). The last-ranked
        // prompt's rank is 0xff5f72cd41f6f240: compared as a signed Long it would sort first.
        val bond = UUID.fromString("01990000-0000-7000-8000-0000000000b1")
        val lib =
            (1..7).map {
                LibraryPrompt(
                    id(it),
                    if (it <= 5) {
                        PromptAudience.CORE
                    } else if (it == 6) {
                        PromptAudience.COUPLE
                    } else {
                        PromptAudience.FRIENDS
                    },
                )
            }
        PromptOfTheDay.cycle(bond, PromptAudience.COUPLE, lib) shouldBe listOf(id(5), id(6), id(1), id(2), id(4), id(3))
        val week = (0L..6L).map { PromptOfTheDay.choose(bond, LocalDate.of(2026, 10, 15).plusDays(it), PromptAudience.COUPLE, lib) }
        week shouldBe listOf(id(3), id(5), id(6), id(1), id(2), id(4), id(3))
    }

    @Test
    fun `a prompt id is a canonical uuid, and nothing that merely parses as one`() {
        PromptId.parse("01990000-0000-7000-8000-000000000001") shouldBe id(1)
        // Upper-case hex is the same id (RFC 9562 reads it case-insensitively).
        PromptId.parse("01990000-0000-7000-8000-00000000000A") shouldBe id(10)
        PromptId.parse("1-1-1-1-1").shouldBeNull()
        PromptId.parse("not-a-uuid").shouldBeNull()
        PromptId.parse("").shouldBeNull()
        PromptId.parse("5").shouldBeNull()
        id(1) shouldNotBe id(2)
    }
}
