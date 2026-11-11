package com.moyi.identity.domain

import java.net.URI
import java.time.Instant

/**
 * One legal document as it stands now: the version a consent row records, and
 * where a person reads it (FR-103, ADR-0056).
 *
 * The version is a label, never prose: letters, digits, `.`, `_` and `-`, at
 * most forty characters, which is what `consent_records_version_length_check`
 * holds, so a configured version can always be recorded. The address is an
 * absolute `http(s)` URL, because a client opens it as a link; `https` is not
 * required here only because the `local` profile points at `localhost`.
 */
internal data class LegalDocument(
    val version: String,
    val url: URI,
) {
    init {
        require(VERSION.matches(version)) { "version must be 1-40 of A-Z a-z 0-9 . _ -" }
        require(url.isAbsolute && url.scheme in WEB_SCHEMES && !url.host.isNullOrBlank()) {
            "url must be an absolute http(s) URL"
        }
    }

    private companion object {
        val VERSION = Regex("^[A-Za-z0-9._-]{1,40}$")
        val WEB_SCHEMES = setOf("http", "https")
    }
}

/**
 * The three documents FR-103 names. Terms and Privacy are consented to
 * ([ConsentDocument]); the data-processing summary is a plain-language reading
 * of the Privacy Policy and is shown, never separately accepted (ADR-0056).
 */
internal data class LegalDocuments(
    val terms: LegalDocument,
    val privacy: LegalDocument,
    val dataProcessingSummary: LegalDocument,
) {
    /**
     * The version a consent row records for [document]. The 18+ declaration is a
     * clause of the sign-up form whose terms it is accepted with, so it is recorded
     * under the Terms version (ADR-0056 decision 3).
     */
    fun versionOf(document: ConsentDocument): String =
        when (document) {
            ConsentDocument.TERMS, ConsentDocument.AGE_18 -> terms.version
            ConsentDocument.PRIVACY -> privacy.version
        }
}

/** Which version of a document a person accepted, and when. */
internal data class Acceptance(
    val version: String,
    val acceptedAt: Instant,
)

/**
 * What `GET /me` reports for one document out of a person's consent rows: the
 * row for the [currentVersion] if there is one, else the newest. `null` when
 * the person has none, which is said rather than invented (ADR-0056 decision 5).
 *
 * The current version first, because otherwise a configuration rolled back to a
 * version the person had already accepted would report a later one, the client
 * would ask again, re-consent would be a no-op (the row exists), and it would
 * ask for ever.
 */
internal fun List<ConsentRecord>.acceptanceOf(
    document: ConsentDocument,
    currentVersion: String,
): Acceptance? {
    val rows = filter { it.document == document }
    val chosen = rows.firstOrNull { it.version == currentVersion } ?: rows.maxByOrNull { it.acceptedAt }
    return chosen?.let { Acceptance(it.version, it.acceptedAt) }
}
