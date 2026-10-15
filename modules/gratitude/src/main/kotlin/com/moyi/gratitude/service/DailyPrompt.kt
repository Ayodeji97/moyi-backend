package com.moyi.gratitude.service

import com.moyi.bond.api.BondMembership
import com.moyi.bond.api.BondType
import com.moyi.gratitude.domain.PromptAudience
import com.moyi.gratitude.domain.PromptId
import com.moyi.gratitude.domain.PromptLocale
import com.moyi.gratitude.domain.PromptOfTheDay
import com.moyi.gratitude.infra.database.PromptLibrary
import com.moyi.identity.api.UserDirectory
import org.springframework.stereotype.Service
import java.time.LocalDate

/** The day's prompt as one member is shown it: which, and its words in their language. */
internal data class PromptView(
    val id: PromptId,
    val text: String,
)

/**
 * The bond's prompt for a date (FR-047; ADR-0038), as the caller is shown it.
 *
 * **Which prompt** is [PromptOfTheDay]'s, from the bond, the date and the library alone.
 * **Its words** are the caller's own locale's, from their own stored locale; the partner's
 * is never read, so the prompt tells neither member the other's language (c6-corpus H-P7).
 * **Whether there is one** is the bond's and the caller's own state: none once the bond
 * takes no writes from them (ruling 6), whoever ended it and however.
 *
 * Reads no entry, no day, no mark, no reaction, no streak and no withdrawal: what either
 * member has done cannot reach this (FR-064). `ArchitectureTest` holds this file's imports
 * to that. Called inside `GetToday`'s read-only transaction.
 */
@Service
internal class DailyPrompt(
    private val library: PromptLibrary,
    private val users: UserDirectory,
) {
    fun on(
        membership: BondMembership,
        date: LocalDate,
    ): PromptView? {
        if (!membership.takesWrites) return null
        val id = PromptOfTheDay.choose(membership.bondId, date, overlayOf(membership.type), library.active())
        return id?.let { PromptView(it, textOf(it, membership)) }
    }

    /** [id]'s words in the caller's own language, from their own stored locale and nobody else's. */
    private fun textOf(
        id: PromptId,
        membership: BondMembership,
    ): String {
        val locales = PromptLocale.candidates(users.find(membership.userId)?.locale)
        // `prompts.text` is the `en` text and NOT NULL, and `en` ends every candidate list.
        return checkNotNull(PromptLocale.textIn(locales, library.textsOf(id))) { "prompt ${id.value} has no en text" }
    }

    companion object {
        /** The overlay a bond's type is offered (cADR-0013; `states.md` §2: one card covers parent, child and sibling). */
        fun overlayOf(type: BondType): PromptAudience =
            when (type) {
                BondType.COUPLE -> PromptAudience.COUPLE
                BondType.FRIENDS -> PromptAudience.FRIENDS
                BondType.FAMILY, BondType.PARENT_CHILD -> PromptAudience.FAMILY
            }
    }
}
