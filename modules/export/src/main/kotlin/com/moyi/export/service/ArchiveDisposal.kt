package com.moyi.export.service

import com.moyi.export.infra.database.ArchiveDeletions
import com.moyi.media.api.ExportStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * How an archive's object goes (ADR-0059 decision 13). The storage is not in the database's
 * transaction, so an object deleted before the transaction that forgets its key commits can leave
 * a READY row pointing at nothing (if that transaction rolls back), and one deleted after it, with
 * nothing written down, is stored for ever if the storage refuses then. So the key is first
 * **owed** (V54), in the same transaction as the change that forgets it; the object is deleted
 * once that commits; and the debt is paid when it is. A deletion the storage refused stays owed
 * and is tried again by [sweep], which every build run starts with.
 *
 * Logged by count only: a key is never logged.
 */
@Component
internal class ArchiveDisposal(
    private val deletions: ArchiveDeletions,
    private val storage: ExportStorage,
    private val clock: Clock,
    transactions: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A transaction of its own: after a commit, the finished one can no longer write (Spring's `afterCommit` contract). */
    private val ownTransaction =
        TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /**
     * In the caller's transaction: [keys] are owed, and their objects are deleted once it commits.
     * If it rolls back, nothing is owed and nothing is deleted: the rows that name them come back.
     */
    fun deleteAfterCommit(keys: Collection<String>) {
        if (keys.isEmpty()) return
        check(TransactionSynchronizationManager.isSynchronizationActive()) { "an archive's key is forgotten in a transaction" }
        deletions.owe(keys, now())
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    settle(keys)
                }
            },
        )
    }

    /** Outside any transaction: [key] is owed, then deleted now. One the storage refuses stays owed for [sweep]. */
    fun deleteNow(key: String) {
        deletions.owe(listOf(key), now())
        settle(listOf(key))
    }

    /** Deletes up to [limit] objects owed, oldest first. How many went. */
    fun sweep(limit: Int): Int = settle(deletions.owed(limit))

    /** Deletes each object and pays its debt; one the storage refuses stays owed. How many went. */
    @Suppress("TooGenericExceptionCaught") // Whatever the storage throws, the debt stays written down for the next run.
    private fun settle(keys: Collection<String>): Int {
        var gone = 0
        for (key in keys) {
            try {
                storage.deleteArchive(key)
                ownTransaction.executeWithoutResult { deletions.paid(key) }
                gone++
            } catch (failure: Exception) {
                log.warn("An archive could not be deleted yet and stays owed: {}", failure.javaClass.name)
            }
        }
        return gone
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
