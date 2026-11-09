package com.moyi.export.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Where an export is (ADR-0059 decision 2). */
internal enum class DataExportStatus {
    /** Asked for; the job has not finished building it. */
    PREPARING,

    /** Built and stored; downloadable until [DataExport.expiresAt]. */
    READY,

    /** Its 24 hours are over and its archive is, or is about to be, deleted. */
    EXPIRED,

    /** The job gave up on it after [DataExport.MAX_ATTEMPTS] tries. A new one may be asked for. */
    FAILED,
}

/**
 * One data export (FR-009, FR-094, ADR-0059): a request and, once built, its archive. Holds ids,
 * instants, a size and an opaque key, never anything anybody wrote. [toString] leaves the key out:
 * whoever holds it, and the storage's credentials, holds the archive.
 */
@Suppress("LongParameterList") // A row's columns, each named where it is read.
internal class DataExport(
    val id: UUID,
    val userId: UUID,
    private val stored: DataExportStatus,
    val requestedAt: Instant,
    val readyAt: Instant?,
    val expiresAt: Instant?,
    val storageKey: String?,
    val sizeBytes: Long?,
    val attempts: Int,
) {
    /**
     * The status as it is at [now]: a READY export whose 24 hours are over is EXPIRED from that
     * instant, whether or not the job has deleted its archive yet. The URL is never offered past
     * [expiresAt], so a late job cannot lengthen what FR-094 bounds.
     */
    fun statusAt(now: Instant): DataExportStatus =
        if (stored == DataExportStatus.READY && !now.isBefore(checkNotNull(expiresAt))) DataExportStatus.EXPIRED else stored

    /** Whether a download URL may be given at [now]. */
    fun downloadableAt(now: Instant): Boolean = statusAt(now) == DataExportStatus.READY && storageKey != null

    override fun toString(): String = "DataExport(id=$id, status=$stored, attempts=$attempts)"

    companion object {
        /** FR-094: "a signed URL valid 24h". The archive is kept exactly as long. */
        val TTL: Duration = Duration.ofHours(24)

        /** A build is tried this many times, then the export is FAILED. */
        const val MAX_ATTEMPTS = 3

        /** How long one build holds its export against another runner. */
        val CLAIM: Duration = Duration.ofMinutes(15)
    }
}
