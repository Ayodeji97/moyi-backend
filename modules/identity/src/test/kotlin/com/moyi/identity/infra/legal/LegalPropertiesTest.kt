package com.moyi.identity.infra.legal

import com.moyi.identity.domain.LegalDocument
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * The legal documents come from configuration and nowhere else (ADR-0056), and
 * a deployment that leaves one out, or gives one a value no client can open,
 * does not start. The version is what every consent row records, so a start
 * without one would be registrations recording nothing a person could be shown.
 */
internal class LegalPropertiesTest {
    private val terms = LegalProperties.Document("2026-11-08", URI("https://moyi.test/terms"))
    private val privacy = LegalProperties.Document("2026-11-08", URI("https://moyi.test/privacy"))
    private val summary = LegalProperties.Document("1", URI("https://moyi.test/data"))

    @Test
    fun `all three documents bind into the domain's value`() {
        val documents = LegalProperties(terms, privacy, summary).documents

        documents.terms shouldBe LegalDocument("2026-11-08", URI("https://moyi.test/terms"))
        documents.privacy.url shouldBe URI("https://moyi.test/privacy")
        documents.dataProcessingSummary.version shouldBe "1"
    }

    @Test
    fun `a missing document refuses the start and names the property`() {
        shouldThrow<IllegalArgumentException> { LegalProperties(terms, null, summary) }
            .message!! shouldContain "moyi.legal.privacy.version"
        shouldThrow<IllegalArgumentException> { LegalProperties(terms, privacy, null) }
            .message!! shouldContain "moyi.legal.summary.version"
    }

    @Test
    fun `a missing version or url refuses the start`() {
        shouldThrow<IllegalArgumentException> {
            LegalProperties(LegalProperties.Document(null, URI("https://moyi.test/terms")), privacy, summary)
        }.message!! shouldContain "moyi.legal.terms.version"
        shouldThrow<IllegalArgumentException> {
            LegalProperties(terms, LegalProperties.Document("1", null), summary)
        }.message!! shouldContain "moyi.legal.privacy.url"
    }

    @Test
    fun `a url that is not an absolute web address refuses the start`() {
        listOf("/terms", "mailto:legal@moyi.test", "javascript:alert(1)", "ftp://moyi.test/terms").forEach { url ->
            shouldThrow<IllegalArgumentException> {
                LegalProperties(LegalProperties.Document("1", URI(url)), privacy, summary)
            }.message!! shouldContain "moyi.legal.terms.url"
        }
    }

    @Test
    fun `a version that a consent row could not hold, or that is not a plain token, refuses the start`() {
        // consent_records_version_length_check is 1..40; a version is a label, never prose.
        listOf("", " ", "a".repeat(41), "2026 11 08", "v1\n", "<b>").forEach { version ->
            shouldThrow<IllegalArgumentException> {
                LegalProperties(LegalProperties.Document(version, URI("https://moyi.test/terms")), privacy, summary)
            }.message!! shouldNotContain "\n\n"
        }
        LegalDocument("a".repeat(40), URI("https://moyi.test/terms")).version.length shouldBe 40
        LegalDocument("2026-11-08_v2.1", URI("http://localhost:3000/terms")).version shouldBe "2026-11-08_v2.1"
    }
}
