package com.moyi.gratitude.domain

/**
 * Which of a prompt's texts a member is shown (FR-122; ADR-0038, controller ruling 3).
 *
 * The member's own stored locale (`users.locale`), exactly, then its language alone, then
 * [FALLBACK]. Compared lower case: a tag is case-insensitive (BCP 47), and `users.locale`
 * keeps whatever case it was registered in. Which prompt a bond is offered never depends
 * on this: every prompt has an `en` text, so a translation only changes the words.
 */
internal object PromptLocale {
    /** The launch locale (FR-122), and the text every prompt is written in first. */
    const val FALLBACK = "en"

    fun candidates(tag: String?): List<String> {
        val exact = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(exact, exact?.substringBefore('-'), FALLBACK).distinct()
    }

    /** The text of the first of [candidates] that [texts] holds (keys lower case), or null. */
    fun textIn(
        candidates: List<String>,
        texts: Map<String, String>,
    ): String? = candidates.firstNotNullOfOrNull { texts[it] }
}
