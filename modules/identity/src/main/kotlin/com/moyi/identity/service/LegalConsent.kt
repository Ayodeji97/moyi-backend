package com.moyi.identity.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.security.ClientContext
import com.moyi.identity.domain.Acceptance
import com.moyi.identity.domain.ConsentDocument
import com.moyi.identity.domain.ConsentRecord
import com.moyi.identity.domain.LegalDocuments
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.acceptanceOf
import com.moyi.identity.infra.database.AccountStore
import com.moyi.identity.infra.database.ConsentStore
import com.moyi.identity.infra.legal.LegalProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Which Terms and Privacy Policy are current, which ones a person accepted, and
 * accepting the current ones again (FR-011, FR-103, ADR-0056).
 *
 * **The version recorded is always the configured one.** Nothing a client
 * sends names it: a version taken from a request is a claim about what the
 * person was shown, made by the party with a reason to get it wrong, and the
 * row it fills is the evidence of consent.
 */
@Service
internal class LegalConsent(
    private val accounts: AccountStore,
    private val consents: ConsentStore,
    private val legal: LegalProperties,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun current(): LegalDocuments = legal.documents

    /** Terms and Privacy only: the data-processing summary is read, never accepted (ADR-0056 decision 3). */
    @Transactional(readOnly = true)
    fun acceptedBy(userId: UserId): AcceptedLegal {
        val rows = consents.findAll(userId)
        val documents = legal.documents
        return AcceptedLegal(
            terms = rows.acceptanceOf(ConsentDocument.TERMS, documents.terms.version),
            privacy = rows.acceptanceOf(ConsentDocument.PRIVACY, documents.privacy.version),
        )
    }

    /**
     * Records the current Terms and Privacy versions as accepted now, for each
     * one this person has not already accepted. Idempotent: a version accepted
     * before keeps the moment it was first accepted.
     *
     * @throws AuthenticatedUserMissingException when the token's user is gone,
     *   which `GET /me` answers the same way: a `401`.
     */
    @Transactional
    fun acceptCurrent(
        userId: UserId,
        client: ClientContext,
    ) {
        accounts.findById(userId) ?: throw AuthenticatedUserMissingException()
        // Truncated, so the moment read back by GET /me is the one written (CLAUDE.md).
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        RECONSENTED.forEach { document ->
            consents.recordIfAbsent(
                ConsentRecord(
                    id = ids.timeOrdered(),
                    userId = userId,
                    document = document,
                    version = legal.documents.versionOf(document),
                    acceptedAt = now,
                    ipHash = client.addressHash,
                    userAgentHash = client.userAgentHash,
                ),
            )
        }
    }

    private companion object {
        /** Not the 18+ declaration: it is made once, at sign-up, and a new Terms version does not unmake it. */
        val RECONSENTED = listOf(ConsentDocument.TERMS, ConsentDocument.PRIVACY)
    }
}

internal data class AcceptedLegal(
    val terms: Acceptance?,
    val privacy: Acceptance?,
)
