package com.moyi.export.service

import com.moyi.common.core.IdGenerator
import com.moyi.common.web.NotFoundException
import com.moyi.export.domain.DataExport
import com.moyi.export.infra.database.DataExports
import com.moyi.media.api.ExportStorage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** An export as a route answers it: the row, the moment it was read at, and its URL while it may be downloaded. */
internal class ExportView(
    val export: DataExport,
    val at: Instant,
    val downloadUrl: URI?,
) {
    override fun toString(): String = "ExportView(export=$export, downloadUrl=${downloadUrl?.let { "<redacted>" }})"
}

/**
 * `POST`, `GET /me/export` and `GET /me/export/{id}` (FR-009, FR-094, ADR-0059 decisions 1 to 4).
 *
 * **Asking is idempotent while one is being prepared**: the second request answers with the first
 * export, and V53's partial unique index is what makes two at once one row. A request after one is
 * READY, EXPIRED or FAILED is a new export: the archive is a copy as of when it is built.
 *
 * **Nothing about the person's bonds is consulted.** An ended bond, a bond the person left or was
 * blocked from, or no bond at all: anyone signed in may ask (BR-9). What goes in is decided when it
 * is built, by each module for its own data.
 */
@Service
internal class DataExportRequests(
    private val exports: DataExports,
    private val storage: ExportStorage,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    @Transactional
    fun request(userId: UUID): ExportView {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        exports.insertIfNonePreparing(ids.timeOrdered(), userId, now)
        // Ours, or the one a concurrent request inserted first: either way the one being prepared.
        val preparing = checkNotNull(exports.preparingOf(userId)) { "an export being prepared was just inserted or found" }
        return view(preparing, now)
    }

    /** @throws NotFoundException [id] is not the caller's export, or is nobody's: one answer for both. */
    @Transactional(readOnly = true)
    fun find(
        userId: UUID,
        id: UUID,
    ): ExportView = exports.find(userId, id)?.let { view(it, clock.instant()) } ?: throw ExportNotFoundException()

    /** @throws NotFoundException the caller has never asked for one. */
    @Transactional(readOnly = true)
    fun latest(userId: UUID): ExportView = exports.latestOf(userId)?.let { view(it, clock.instant()) } ?: throw ExportNotFoundException()

    /**
     * The URL is signed at the moment the archive became ready, for exactly its 24 hours: the same
     * URL however often it is asked for, and dead at `expiresAt` whatever the job has done since.
     */
    private fun view(
        export: DataExport,
        at: Instant,
    ): ExportView {
        val url =
            if (export.downloadableAt(at)) {
                val readyAt = checkNotNull(export.readyAt)
                storage.archiveUrl(checkNotNull(export.storageKey), readyAt, Duration.between(readyAt, checkNotNull(export.expiresAt)))
            } else {
                null
            }
        return ExportView(export, at, url)
    }
}

/** No such export for this caller. The message is the 404's detail, and names nothing. */
internal class ExportNotFoundException : NotFoundException("No such export.")
