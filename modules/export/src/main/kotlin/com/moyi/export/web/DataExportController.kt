package com.moyi.export.web

import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.export.domain.DataExportStatus
import com.moyi.export.service.DataExportRequests
import com.moyi.export.service.ExportView
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * `POST /api/v1/me/export`, `GET /api/v1/me/export` and `GET /api/v1/me/export/{exportId}` (FR-009,
 * FR-094, doc 06 §3.2, ADR-0059). Nothing here decides anything (doc 18 §4).
 *
 * `202`: nothing is built when it returns; the job builds it and the person is emailed (FR-085).
 */
@RestController
@RequestMapping("/api/v1/me/export")
internal class DataExportController(
    private val requests: DataExportRequests,
) {
    /** `account:export:user`, three a day. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RateLimited(RateLimitBucket.ACCOUNT_EXPORT_USER)
    fun requestDataExport(caller: CurrentUser): DataExportResponse = DataExportResponse.from(requests.request(caller.id))

    @GetMapping
    fun getLatestDataExport(caller: CurrentUser): DataExportResponse = DataExportResponse.from(requests.latest(caller.id))

    @GetMapping("/{exportId}")
    fun getDataExport(
        caller: CurrentUser,
        @PathVariable exportId: UUID,
    ): DataExportResponse = DataExportResponse.from(requests.find(caller.id, exportId))
}

/**
 * An export as the client sees it. [downloadUrl] and [expiresAt] only while it is READY;
 * [status] is as of the moment it was read, so an export past its 24 hours is EXPIRED even before
 * the job has deleted it.
 */
internal data class DataExportResponse(
    val id: UUID,
    val status: DataExportStatus,
    val requestedAt: Instant,
    val readyAt: Instant?,
    val expiresAt: Instant?,
    val sizeBytes: Long?,
    val downloadUrl: String?,
) {
    override fun toString(): String = "DataExportResponse(id=$id, status=$status, downloadUrl=${downloadUrl?.let { "<redacted>" }})"

    companion object {
        fun from(view: ExportView): DataExportResponse {
            val export = view.export
            val status = export.statusAt(view.at)
            val ready = status == DataExportStatus.READY
            return DataExportResponse(
                id = export.id,
                status = status,
                requestedAt = export.requestedAt,
                readyAt = export.readyAt,
                expiresAt = export.expiresAt.takeIf { ready },
                sizeBytes = export.sizeBytes.takeIf { ready },
                downloadUrl = view.downloadUrl?.toString(),
            )
        }
    }
}
