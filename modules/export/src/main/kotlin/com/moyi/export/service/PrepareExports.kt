package com.moyi.export.service

import com.moyi.export.api.DataExportJobs
import com.moyi.export.domain.DataExport
import com.moyi.export.infra.database.DataExports
import com.moyi.identity.api.AccountMail
import com.moyi.media.api.ExportStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.time.Clock
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
    private val mail: AccountMail,
    private val clock: Clock,
) : DataExportJobs {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun prepareDue(limit: Int): Int = exports.claimable(clock.instant(), limit).count(::prepare)

    @Suppress("TooGenericExceptionCaught") // One export's failure, whatever it is, must not stop the rest.
    override fun expireDue(limit: Int): Int =
        exports.expired(clock.instant(), limit).count { export ->
            try {
                // The object first: an export marked EXPIRED with its archive still stored would be forgotten for ever.
                storage.deleteArchive(checkNotNull(export.storageKey))
                exports.expire(export.id, now())
            } catch (failure: RuntimeException) {
                // Still READY, so the next run tries again; the route already answers EXPIRED, and offers no URL.
                log.warn("Export {} could not be expired: {}", export.id, failure.javaClass.name)
                false
            }
        }

    private fun prepare(export: DataExport): Boolean {
        val attempt = exports.claim(export.id, now(), now().plus(DataExport.CLAIM)) ?: return false
        return build(export, attempt)
    }

    /** One attempt at [export], claimed as attempt number [attempt]. Whether it made it READY. */
    @Suppress("TooGenericExceptionCaught") // Whatever fails a build is an attempt spent, and must not stop the run.
    private fun build(
        export: DataExport,
        attempt: Int,
    ): Boolean {
        val zip = Files.createTempFile("moyi-export-", ".zip")
        return try {
            val missing = writer.write(export.id, export.userId, now(), zip)
            val key = storage.storeArchive(zip)
            val readyAt = now()
            val expiresAt = readyAt.plus(DataExport.TTL)
            val made = exports.ready(export.id, key, Files.size(zip), readyAt, expiresAt)
            if (made) {
                if (missing > 0) log.warn("Export {}: {} media files were gone before they could be copied", export.id, missing)
                val emailed = mail.sendToAccountHolder(export.userId, ExportReadyMail.of(export.id, expiresAt))
                log.info("Export {} is ready, attempt {}: email {}", export.id, attempt, emailed)
            } else {
                // An account deletion took the row while this built: what was stored goes again, and nobody is told.
                storage.deleteArchive(key)
                log.info("Export {} was no longer being prepared; its archive was deleted again", export.id)
            }
            made
        } catch (failure: RuntimeException) {
            if (attempt >= DataExport.MAX_ATTEMPTS) exports.fail(export.id, now()) else exports.release(export.id)
            log.warn("Export {} failed, attempt {} of {}: {}", export.id, attempt, DataExport.MAX_ATTEMPTS, failure.javaClass.name)
            false
        } finally {
            Files.deleteIfExists(zip)
        }
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
