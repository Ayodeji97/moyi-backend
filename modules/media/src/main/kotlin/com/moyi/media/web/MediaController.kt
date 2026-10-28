package com.moyi.media.web

import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.common.web.idempotency.Idempotent
import com.moyi.media.service.CompleteUpload
import com.moyi.media.service.CreateUploadIntent
import com.moyi.media.service.MediaNotFoundException
import com.moyi.media.service.OwnMedia
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `/api/v1/media` (doc 06 §3.6, spec §4 D1): the four routes of an upload that
 * never passes through this server (FR-044, NFR-009). Behind the bearer, as
 * everything under `/api/v1` but the public auth routes is.
 *
 * **Owner only, and one `404` for everyone else.** Every read is by id and
 * owner together, so another person's asset is not found rather than found and
 * refused; an id that is not a UUID is the same `404`, as `EntriesController`
 * answers a malformed bond id. `MediaCrossUserTest` holds every `{id}` route to
 * it.
 *
 * The method names are the API's `operationId`s (springdoc derives them from
 * the method alone), so each is unique across the application.
 */
@RestController
@RequestMapping("/api/v1/media")
internal class MediaController(
    private val intents: CreateUploadIntent,
    private val completions: CompleteUpload,
    private val own: OwnMedia,
) {
    // @ResponseStatus alongside the 201 below: springdoc reads the annotation,
    // not the return value (EntriesController.submitEntry has the account).
    @RateLimited(RateLimitBucket.MEDIA_INTENT_USER)
    @PostMapping("/upload-intent")
    @ResponseStatus(HttpStatus.CREATED)
    fun createMediaUploadIntent(
        caller: CurrentUser,
        @RequestBody request: UploadIntentRequest,
    ): ResponseEntity<UploadIntentResponse> {
        val intent = intents.create(caller.id, request.kind, request.contentType, request.sizeBytes, request.checksum)
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadIntentResponse.from(intent))
    }

    /** Idempotency-Key required (doc 06:16). The body, if any, is ignored; the key's fingerprint covers it. */
    @Idempotent
    @PostMapping("/{mediaId}/complete")
    fun completeMediaUpload(
        caller: CurrentUser,
        @PathVariable mediaId: String,
        http: HttpServletRequest,
    ): ResponseEntity<MediaResponse> {
        val completion = completions.complete(caller.id, mediaIdOrNotFound(mediaId), IdempotencyInterceptor.requestOf(http))
        val response = ResponseEntity.ok()
        if (completion.replayed) response.header(IdempotencyInterceptor.REPLAYED_HEADER, "true")
        return response.body(MediaResponse.from(own.viewOf(completion.asset)))
    }

    @GetMapping("/{mediaId}")
    fun getMedia(
        caller: CurrentUser,
        @PathVariable mediaId: String,
    ): MediaResponse = MediaResponse.from(own.get(caller.id, mediaIdOrNotFound(mediaId)))

    @DeleteMapping("/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMedia(
        caller: CurrentUser,
        @PathVariable mediaId: String,
    ) {
        own.delete(caller.id, mediaIdOrNotFound(mediaId))
    }

    private fun mediaIdOrNotFound(raw: String): UUID = runCatching { UUID.fromString(raw) }.getOrElse { throw MediaNotFoundException() }
}
