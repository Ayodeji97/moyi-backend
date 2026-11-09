package com.moyi.identity.web

import com.moyi.common.web.Representation
import com.moyi.common.web.Representations
import com.moyi.identity.domain.LegalDocument
import com.moyi.identity.domain.LegalDocuments
import com.moyi.identity.service.LegalConsent
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /api/v1/meta/legal` (doc 06 §3.7, FR-103, issue #128, ADR-0056): the
 * current Terms of Service, Privacy Policy and data-processing summary, each a
 * version and an address.
 *
 * **Public**, because sign-up shows the documents before there is an account,
 * and the same for everyone, so `Cache-Control: public, no-cache` with an
 * `ETag`: any cache may keep it and must ask before reusing it.
 *
 * **No rate-limit bucket** (ADR-0056 decision 6): it reads two configuration
 * values and nothing else, like `/healthz`, and a bucket would make every
 * request write to Redis to protect a response that costs less than the write.
 *
 * In `identity` because identity is what records consent to these versions;
 * `GET /meta/config` (doc 06 §3.7) is a separate route, still owed, and does
 * not carry these.
 */
@RestController
@RequestMapping("/api/v1/meta")
internal class LegalController(
    private val legalConsent: LegalConsent,
    private val representations: Representations,
) {
    // Named `legalDocuments`: the method name is the API's operationId.
    @GetMapping("/legal", produces = [MediaType.APPLICATION_JSON_VALUE, ANY_JSON])
    fun legalDocuments(): ResponseEntity<Representation<LegalResponse>> = representations.shared(LegalResponse.from(legalConsent.current()))

    private companion object {
        const val ANY_JSON = "application/*+json"
    }
}

internal data class LegalResponse(
    val terms: LegalDocumentResponse,
    val privacy: LegalDocumentResponse,
    /** A plain-language reading of the Privacy Policy. Shown, never separately accepted. */
    val dataProcessingSummary: LegalDocumentResponse,
) {
    companion object {
        fun from(documents: LegalDocuments): LegalResponse =
            LegalResponse(
                terms = LegalDocumentResponse.from(documents.terms),
                privacy = LegalDocumentResponse.from(documents.privacy),
                dataProcessingSummary = LegalDocumentResponse.from(documents.dataProcessingSummary),
            )
    }
}

internal data class LegalDocumentResponse(
    /** What a consent row records and `GET /me`'s `legalAccepted` compares with. An opaque label: compare it, never parse it. */
    val version: String,
    /** Where the document is read: an absolute URL. */
    val url: String,
) {
    companion object {
        fun from(document: LegalDocument): LegalDocumentResponse = LegalDocumentResponse(document.version, document.url.toString())
    }
}
