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
 * `export`'s share of an account deletion (FR-008, ADR-0058, ADR-0059 decision 9): every export row
 * of the person is deleted, in one statement that returns the archive keys they held, and then
 * each of those archives. An archive is the
 * person's words and their partner's, gathered in one file; nothing about it may outlive the
 * account.
 *
 * **One statement, `DELETE … RETURNING`, not a read of the keys and then a delete**: a build that
 * makes an export READY while this runs either commits before the delete reaches its row (and the
 * delete, waiting on the row, returns the key it stored) or after it (and finds its row gone, and
 * deletes its own archive). Inside the delivery's transaction: if the storage cannot be reached,
 * the delivery throws, the rows come back, and the outbox delivers it again until it can. Deleting an object
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
        exports.deleteAllOf(userId).forEach(storage::deleteArchive)
    }
}
