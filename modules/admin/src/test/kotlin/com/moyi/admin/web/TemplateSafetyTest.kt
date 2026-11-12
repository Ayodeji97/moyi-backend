package com.moyi.admin.web

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Spec §7: admin pages print with `th:text` only (it escapes), carry no inline script and no
 * `on…=` handler attribute. The CSP would block the last two, so they would be dead code that
 * reads like working code.
 */
internal class TemplateSafetyTest {
    private val templates =
        // Walked, not listed: A2's decision form is a fragment in a folder of its own.
        File("src/main/resources/templates/admin").walkTopDown().filter { it.isFile && it.extension == "html" }.toList()

    @Test
    fun `no admin template prints unescaped, scripts inline or binds an event attribute`() {
        templates.shouldNotBeEmpty()
        templates.forEach { file ->
            val body = file.readText().replace(COMMENT, "")
            withClue("${file.name} th:utext") { UTEXT.containsMatchIn(body) shouldBe false }
            withClue("${file.name} inline script") { INLINE_SCRIPT.containsMatchIn(body) shouldBe false }
            withClue("${file.name} on…= attribute") { EVENT_ATTRIBUTE.containsMatchIn(body) shouldBe false }
        }
    }

    private companion object {
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val UTEXT = Regex("""(th:utext|\[\()""")
        val INLINE_SCRIPT = Regex("""<script(?![^>]*\bsrc=)[^>]*>\s*\S""", RegexOption.IGNORE_CASE)
        val EVENT_ATTRIBUTE = Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE)
    }
}
