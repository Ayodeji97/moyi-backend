package com.moyi.gratitude.domain

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** An entry records the prompt it answered (doc 04:226) and forgets it with its words (ADR-0038, ruling 7). */
internal class EntryPromptTest {
    private val now = Instant.parse("2026-10-15T10:00:00Z")
    private val prompt = PromptId(UUID.fromString("0199e8a0-6a00-7000-8000-000000000001"))

    private fun entry(promptId: PromptId?) =
        Entry.submit(
            id = EntryId(UUID.randomUUID()),
            bondDayId = BondDayId(UUID.randomUUID()),
            bondId = UUID.randomUUID(),
            authorMemberId = UUID.randomUUID(),
            text = EntryText.of("thank you"),
            intendedAt = now,
            now = now,
            promptId = promptId,
        )

    @Test
    fun `a submitted entry carries the prompt it answered, or none`() {
        entry(prompt).promptId shouldBe prompt
        entry(null).promptId.shouldBeNull()
    }

    @Test
    fun `an erasure takes the prompt with the words, before or after the reveal`() {
        entry(prompt).erase(now).promptId.shouldBeNull()
        entry(prompt)
            .reveal(now)
            .erase(now)
            .promptId
            .shouldBeNull()
        // The reveal shows the words to the partner; what they answered stays with the author's entry.
        entry(prompt).reveal(now).promptId shouldBe prompt
        // An edit before the reveal changes the words, not what they answered.
        entry(prompt).edit(EntryText.of("thank you, again"), now).promptId shouldBe prompt
    }
}
