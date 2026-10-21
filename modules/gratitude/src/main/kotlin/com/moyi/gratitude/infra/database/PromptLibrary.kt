package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.LibraryPrompt
import com.moyi.gratitude.domain.PromptAudience
import com.moyi.gratitude.domain.PromptId
import com.moyi.gratitude.domain.PromptLocale
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The prompt library (V24), read. Nothing in the application writes it: the library is
 * changed by migration (the owner's Q-P4).
 *
 * Three questions, each one query: the active prompts and who each is for (the choice's
 * whole input, [com.moyi.gratitude.domain.PromptOfTheDay]); one prompt's texts; and
 * whether an id names a prompt at all. Nothing here reads an entry, a day or a person.
 */
@Component
internal class PromptLibrary(
    private val jdbc: JdbcTemplate,
) {
    fun active(): List<LibraryPrompt> = jdbc.query(ACTIVE, LIBRARY_PROMPT)

    /** Keyed by lower-case locale: `en` from the prompt's own row, every other from `prompt_texts`. Empty for no such prompt. */
    fun textsOf(id: PromptId): Map<String, String> =
        jdbc
            .query(TEXTS, { rs, _ -> rs.getString("locale") to rs.getString("text") }, id.value, id.value)
            .toMap()

    /** Any prompt, active or retired, of any audience (ADR-0038 decision 8). */
    fun exists(id: PromptId): Boolean = jdbc.queryForObject(EXISTS, Boolean::class.java, id.value) == true

    private companion object {
        const val ACTIVE = "SELECT id, audience FROM prompts WHERE active"
        val TEXTS =
            """
            SELECT '${PromptLocale.FALLBACK}' AS locale, text FROM prompts WHERE id = ?
            UNION ALL
            SELECT locale, text FROM prompt_texts WHERE prompt_id = ?
            """.trimIndent()
        const val EXISTS = "SELECT EXISTS (SELECT 1 FROM prompts WHERE id = ?)"

        val LIBRARY_PROMPT =
            RowMapper { rs, _ ->
                LibraryPrompt(PromptId(rs.getObject("id", UUID::class.java)), PromptAudience.valueOf(rs.getString("audience")))
            }
    }
}
