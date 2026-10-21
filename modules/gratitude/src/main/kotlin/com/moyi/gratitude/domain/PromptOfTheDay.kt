package com.moyi.gratitude.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * Who a prompt is written for (cADR-0013): the core every bond shares, or one bond type's
 * overlay over it. `PARENT_CHILD` has no overlay of its own and is given FAMILY's
 * (`states.md` §2); that mapping is the service's, which knows bond types.
 */
internal enum class PromptAudience { CORE, COUPLE, FRIENDS, FAMILY }

/** A prompt as the choice sees it: which it is and who it is for. Never its words. */
internal data class LibraryPrompt(
    val id: PromptId,
    val audience: PromptAudience,
)

/**
 * Which prompt a bond is offered on a date (FR-047; ADR-0038, controller ruling 1).
 *
 * **A function of the bond, the date and the library, and of nothing else.** No member,
 * no entry, no day, no history and no record of what was shown: there is no parameter
 * that could carry one. So one member opening the app, writing or reading cannot move
 * what the other is offered (FR-064, T-09), both members are offered the same prompt
 * (the owner's Q-P1), and "recent" is computed, never recorded (ruling 2).
 *
 * **The cycle** is the prompts eligible for the bond (the core and the bond's overlay),
 * dealt into an order of the bond's own: each ranked by the first eight bytes, unsigned,
 * of `SHA-256(LABEL ‖ bondId ‖ promptId)`, ties by id. The date's epoch day indexes it,
 * modulo its length. For one library the sequence repeats with that period, so no
 * prompt comes back within any run of days as long as the cycle.
 *
 * **Not keyed.** The application's one secret would make the order change on every
 * local boot and on every rotation of a security control, and the order protects
 * nothing: knowing tomorrow's prompt tells nobody anything about anybody (ADR-0038,
 * decision 1). [LABEL] keeps the hash this use's alone.
 */
internal object PromptOfTheDay {
    /** The prompt of [date] for [bondId], or null when the library holds nothing for it. */
    fun choose(
        bondId: UUID,
        date: LocalDate,
        overlay: PromptAudience,
        library: Collection<LibraryPrompt>,
    ): PromptId? {
        val cycle = cycle(bondId, overlay, library)
        if (cycle.isEmpty()) return null
        return cycle[Math.floorMod(date.toEpochDay(), cycle.size.toLong()).toInt()]
    }

    /** The bond's order of its eligible prompts, whatever order [library] arrived in. */
    fun cycle(
        bondId: UUID,
        overlay: PromptAudience,
        library: Collection<LibraryPrompt>,
    ): List<PromptId> {
        val eligible =
            library
                .filter { it.audience == PromptAudience.CORE || it.audience == overlay }
                .map { it.id }
                .distinct()
        val ranks = eligible.associateWith { rank(bondId, it) }
        return eligible.sortedWith(compareBy<PromptId> { ranks.getValue(it) }.thenBy { it.value })
    }

    private fun rank(
        bondId: UUID,
        promptId: PromptId,
    ): ULong {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(LABEL)
        digest.update(bytesOf(bondId))
        digest.update(bytesOf(promptId.value))
        return ByteBuffer.wrap(digest.digest()).long.toULong()
    }

    private fun bytesOf(id: UUID): ByteArray =
        ByteBuffer
            .allocate(UUID_BYTES)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()

    private const val UUID_BYTES = 16
    private val LABEL = "moyi.prompt-order.v1".toByteArray(Charsets.UTF_8)
}
