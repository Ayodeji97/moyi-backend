package com.moyi.identity.infra.legal

import com.moyi.identity.domain.LegalDocument
import com.moyi.identity.domain.LegalDocuments
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * The current Terms of Service, Privacy Policy and data-processing summary:
 * `moyi.legal.{terms,privacy,summary}.{version,url}` (FR-103, ADR-0056).
 *
 * **Required, with no defaults, as the link bases are (ADR-0018).** Every
 * consent row records one of these versions, so a value in `application.yml`
 * would be a version every deployment that forgot its own would record as
 * accepted, for a document nobody chose. The `local` profile and the test
 * contexts carry their own; production's are in `config/deploy.yml`.
 *
 * `summary` and not `data-processing-summary` in the property name, because
 * the environment variable is what a deployment writes, and relaxed binding
 * would have made it `MOYI_LEGAL_DATAPROCESSINGSUMMARY_URL`.
 *
 * Checked at binding: a missing value or one the domain refuses is a failed
 * start, naming the property.
 */
@ConfigurationProperties(prefix = "moyi.legal")
internal class LegalProperties(
    terms: Document?,
    privacy: Document?,
    summary: Document?,
) {
    /** Nullable throughout so that a missing value reaches the message below, not Kotlin's own null check. */
    class Document(
        val version: String?,
        val url: URI?,
    )

    val documents: LegalDocuments =
        LegalDocuments(
            terms = document(terms, "terms"),
            privacy = document(privacy, "privacy"),
            dataProcessingSummary = document(summary, "summary"),
        )

    private companion object {
        fun document(
            value: Document?,
            name: String,
        ): LegalDocument {
            val version = requireNotNull(value?.version) { "moyi.legal.$name.version is required (from the environment)" }
            val url = requireNotNull(value?.url) { "moyi.legal.$name.url is required (from the environment)" }
            // The domain's own rule, reported with the property's name: not restated here.
            return try {
                LegalDocument(version, url)
            } catch (refused: IllegalArgumentException) {
                throw IllegalArgumentException("moyi.legal.$name.${refused.message}", refused)
            }
        }
    }
}
