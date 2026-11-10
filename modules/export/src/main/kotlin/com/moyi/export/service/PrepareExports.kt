package com.moyi.export.service

import com.moyi.export.api.DataExportJobs
import com.moyi.export.domain.DataExport
import com.moyi.export.infra.database.DataExports
import com.moyi.identity.api.AccountMail
import com.moyi.media.api.ExportStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [DataExportJobs] (ADR-0059 decisions 2, 9 and 10).
 *
 * **A build holds no transaction.** It reads through five modules' `api`s, each in its own
 * short read, writes a zip to a temporary file, and stores it; only then does one statement make
 * the export READY, **and only if it is still PREPARING**. An account deletion that removed the
 * row while the build ran wins: the stored archive is deleted again and nothing is sent.
 *
 * **One export at a time per runner, and a claim per export.** The claim counts the attempt and
 * holds the export for [DataExport.CLAIM], so a second runner, or the next run after a crash, does
 * not start it again before the first could have finished; the third failed attempt makes it
 * FAILED. Logged by id, attempt and exception class only: never a key, a name or a word.
 */
@Service
internal class PrepareExports(
    private val exports: DataExports,
    private val writer: ArchiveWriter,
    private val storage: ExportStorage,
    private val archives: ArchiveDisposal,
    private val mail: AccountMail,
    private val clock: Clock,
) : DataExportJobs {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * First, the archives still owed a deletion are deleted ([ArchiveDisposal]): ones the storage
     * refused when their rows let them go. Then every export whose attempts are spent and whose last
     * claim has lapsed is FAILED: a process that died mid-build leaves its row PREPARING, and it must
     * not stay so for ever. Then each claimable export is tried, **each on its own**: nothing one
     * export throws stops the rest of the batch.
     */
    @Suppress("TooGenericExceptionCaught") // One export's failure, whatever it is, must not stop the rest.
    override fun prepareDue(limit: Int): Int {
        archives.sweep(OWED_PER_RUN)
        val exhausted = exports.failExhausted(now(), DataExport.MAX_ATTEMPTS)
        if (exhausted > 0) log.warn("{} exports had used their attempts and are FAILED", exhausted)
        return exports.claimable(now(), DataExport.MAX_ATTEMPTS, limit).count { export ->
            try {
                prepare(export)
            } catch (failure: Exception) {
                log.warn("Export {} could not be tried: {}", export.id, failure.javaClass.name)
                false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // One export's failure, whatever it is, must not stop the rest.
    override fun expireDue(limit: Int): Int =
        exports.expired(clock.instant(), limit).count { export ->
            try {
                // The object first: an export marked EXPIRED with its archive still stored would be forgotten for ever.
                storage.deleteArchive(checkNotNull(export.storageKey))
                exports.expire(export.id, now())
            } catch (failure: Exception) {
                // Still READY, so the next run tries again; the route already answers EXPIRED, and offers no URL.
                log.warn("Export {} could not be expired: {}", export.id, failure.javaClass.name)
                false
            }
        }

    private fun prepare(export: DataExport): Boolean {
        val claim = now().plus(DataExport.CLAIM)
        val attempt = exports.claim(export.id, now(), claim, DataExport.MAX_ATTEMPTS) ?: return false
        return build(export, attempt, claim)
    }

    /**
     * One attempt at [export], claimed as attempt number [attempt] until [claim]. Whether it made it
     * READY. **Every failure is caught here**, a checked `IOException` (a full disk, a storage stream
     * cut off) as much as a runtime one, so the attempt is always either given back or, the last
     * one, made FAILED; an interrupt is passed on.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever fails a build is an attempt spent, and must not stop the run.
    private fun build(
        export: DataExport,
        attempt: Int,
        claim: Instant,
    ): Boolean {
        var zip: Path? = null
        return try {
            zip = Files.createTempFile("moyi-export-", ".zip")
            val missing = writer.write(export.id, export.userId, now(), zip)
            val key = storage.storeArchive(zip)
            val readyAt = now()
            val expiresAt = readyAt.plus(DataExport.TTL)
            val made = exports.ready(export.id, claim, key, Files.size(zip), readyAt, expiresAt)
            if (made) {
                if (missing > 0) log.warn("Export {}: {} media files were gone before they could be copied", export.id, missing)
                tell(export, attempt, expiresAt)
            } else {
                // An account deletion took the row, or a withdrawal restarted it, while this built: what was
                // stored goes again, and nobody is told. Owed first, so a storage that refuses now cannot
                // leave it stored with nothing naming it (ADR-0059 decision 13).
                archives.deleteNow(key)
                log.info("Export {} was no longer this build's; its archive is deleted again", export.id)
            }
            made
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            spent(export, attempt, claim, interrupted)
        } catch (failure: Exception) {
            spent(export, attempt, claim, failure)
        } finally {
            zip?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /**
     * The ready email, for an export already READY. **A send that throws is not a failed build**: the
     * export is READY, its row no longer this build's to give back, and the app shows it whether or
     * not the email went. Logged and counted as made; the email is not tried again.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever the mail throws, the export it is about is already made.
    private fun tell(
        export: DataExport,
        attempt: Int,
        expiresAt: Instant,
    ) {
        try {
            // identity sends nothing to an account that is deleted or being deleted (AccountMail's NO_ACCOUNT).
            val emailed = mail.sendToAccountHolder(export.userId, ExportReadyMail.of(export.id, expiresAt))
            log.info("Export {} is ready, attempt {}: email {}", export.id, attempt, emailed)
        } catch (failure: Exception) {
            log.warn("Export {} is ready, attempt {}, but its email could not be sent: {}", export.id, attempt, failure.javaClass.name)
        }
    }

    /** A failed attempt: given back for a later run, or, the last, FAILED; either only while the claim is still this build's. */
    private fun spent(
        export: DataExport,
        attempt: Int,
        claim: Instant,
        failure: Exception,
    ): Boolean {
        if (attempt >= DataExport.MAX_ATTEMPTS) exports.fail(export.id, claim, now()) else exports.release(export.id, claim)
        log.warn("Export {} failed, attempt {} of {}: {}", export.id, attempt, DataExport.MAX_ATTEMPTS, failure.javaClass.name)
        return false
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        /** Owed deletions tried per build run: a storage outage leaves at most a handful, and the next minute takes the rest. */
        const val OWED_PER_RUN = 20
    }
}
