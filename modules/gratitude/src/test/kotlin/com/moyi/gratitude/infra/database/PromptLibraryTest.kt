package com.moyi.gratitude.infra.database

import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.domain.PromptAudience
import com.moyi.gratitude.domain.PromptId
import com.moyi.gratitude.infra.GratitudeTestApplication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import javax.sql.DataSource

/** V24 as it lands, and the three reads of it (ADR-0038). Never truncates `prompts`: entries reference it. */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class PromptLibraryTest(
    @Autowired private val library: PromptLibrary,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val probe = UUID.fromString("0199e8a0-7e57-7000-8000-000000000001")

    @AfterEach
    fun clear() {
        jdbc.update("DELETE FROM prompt_texts WHERE prompt_id = ?", probe)
        jdbc.update("DELETE FROM prompts WHERE id = ?", probe)
    }

    @Test
    fun `the seed is twenty-four prompts, all active, twelve shared and four for each overlay, in en only`() {
        val seed = library.active()
        seed.size shouldBe 24
        seed.groupingBy { it.audience }.eachCount() shouldBe
            mapOf(PromptAudience.CORE to 12, PromptAudience.COUPLE to 4, PromptAudience.FRIENDS to 4, PromptAudience.FAMILY to 4)
        jdbc.queryForObject("SELECT count(*) FROM prompts WHERE NOT active", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM prompt_texts", Int::class.java) shouldBe 0
        library.textsOf(PromptId(UUID.fromString("0199e8a0-6a00-7000-8000-000000000001"))) shouldBe
            mapOf("en" to "What did they make easier today?")
    }

    @Test
    fun `a prompt's texts are its en text and every translation, and any prompt exists, retired or not`() {
        insertProbe(active = false)
        jdbc.update("INSERT INTO prompt_texts (prompt_id, locale, text) VALUES (?, 'pt-pt', 'Sonda')", probe)

        library.textsOf(PromptId(probe)) shouldBe mapOf("en" to "Probe", "pt-pt" to "Sonda")
        library.exists(PromptId(probe)) shouldBe true
        library.exists(PromptId(UUID.randomUUID())) shouldBe false
        (probe in library.active().map { it.id.value }) shouldBe false
    }

    @Test
    fun `every refusal is a named constraint`() {
        refusal("INSERT INTO prompts (id, audience, text) VALUES (?, 'PARENT_CHILD', 'x')", probe) shouldContain "prompts_audience_check"
        refusal("INSERT INTO prompts (id, audience, text) VALUES (?, 'CORE', ' padded ')", probe) shouldContain "prompts_text_check"
        refusal("INSERT INTO prompts (id, audience, text) VALUES (?, 'CORE', repeat('x', 201))", probe) shouldContain "prompts_text_check"
        insertProbe(active = true)
        refusal("INSERT INTO prompt_texts (prompt_id, locale, text) VALUES (?, 'en', 'x')", probe) shouldContain "prompt_texts_locale_check"
        refusal("INSERT INTO prompt_texts (prompt_id, locale, text) VALUES (?, 'pt-PT', 'x')", probe) shouldContain
            "prompt_texts_locale_check"
        refusal("INSERT INTO prompt_texts (prompt_id, locale, text) VALUES (?, 'fr', '')", probe) shouldContain "prompt_texts_text_check"
        // The reference from entries, by the catalogue: no entry is needed to know it is there.
        // What it refuses on the wire is Task 3's (an id no prompt has is a 422, never this).
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE conname = 'entries_prompt_fk' AND contype = 'f'",
            Int::class.java,
        ) shouldBe 1
    }

    private fun insertProbe(active: Boolean) {
        jdbc.update("INSERT INTO prompts (id, audience, text, active) VALUES (?, 'CORE', 'Probe', ?)", probe, active)
    }

    private fun refusal(
        sql: String,
        arg: UUID,
    ): String =
        shouldThrow<DataIntegrityViolationException> { jdbc.update(sql, arg) }
            .mostSpecificCause.message
            .orEmpty()
}
