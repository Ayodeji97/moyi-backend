package com.moyi.identity.infra.database

import com.moyi.identity.domain.ConsentRecord
import com.moyi.identity.domain.UserId
import org.springframework.stereotype.Component

/**
 * Consent records after registration, spoken in domain terms (ADR-0056):
 * read one person's back for `GET /me`, and add an acceptance for re-consent.
 * Registration's own three rows go in with the account ([AccountStore.insert]),
 * because they are one thing with it.
 *
 * Not transactional itself, as [AccountStore] is not: the boundary is the caller's.
 */
@Component
internal class ConsentStore(
    private val consents: ConsentRecordRepository,
) {
    /** Every consent row this person has, in no order. */
    fun findAll(userId: UserId): List<ConsentRecord> = consents.findAllByUserId(userId.value).map { it.toDomain() }

    /**
     * Records [record] unless this person has already accepted that version of
     * that document; `true` when a row was written. Insert-only, like every
     * consent row: an earlier acceptance is never moved.
     */
    fun recordIfAbsent(record: ConsentRecord): Boolean =
        consents.insertIfAbsent(
            id = record.id,
            userId = record.userId.value,
            document = record.document.name,
            version = record.version,
            acceptedAt = record.acceptedAt,
            ipHash = record.ipHash,
            userAgentHash = record.userAgentHash,
        ) == 1
}
