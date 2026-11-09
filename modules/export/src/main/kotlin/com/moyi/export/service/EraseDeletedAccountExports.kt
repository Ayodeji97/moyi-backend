package com.moyi.export.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.export.infra.database.DataExports
import com.moyi.identity.api.AccountDeletions
import com.moyi.media.api.ExportStorage
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * `export`'s share of an account deletion (FR-008, ADR-0058, ADR-0059 decision 9): every archive
 * the person still has stored is deleted, and then every export row of theirs. An archive is the
 * person's words and their partner's, gathered in one file; nothing about it may outlive the
 * account.
 *
 * The objects first, inside the delivery's transaction: if the storage cannot be reached, the
 * delivery throws, the rows stay, and the outbox delivers it again until it can. Deleting an object
 * already gone is not an error, so a second delivery changes nothing. A build still running for
 * the person finds its row gone and deletes what it stored ([PrepareExports]).
 */
@Component
internal class EraseDeletedAccountExports(
    private val exports: DataExports,
    private val storage: ExportStorage,
) : EventConsumer {
    override val id = "export.account-deletion"
    override val eventTypes = setOf(AccountDeletions.EVENT)
    override val startFrom = StartFrom.BEGINNING

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val userId = checkNotNull(event.references[AccountDeletions.USER_ID]) { "An ${AccountDeletions.EVENT} event carries no user." }
        exports.keysOf(userId).forEach(storage::deleteArchive)
        exports.deleteAllOf(userId)
    }
}
