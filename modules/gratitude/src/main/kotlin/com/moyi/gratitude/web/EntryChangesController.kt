package com.moyi.gratitude.web

import com.moyi.common.security.CurrentUser
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.common.web.idempotency.Idempotent
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.service.ChangeEntry
import com.moyi.gratitude.service.EntryNotFoundException
import com.moyi.gratitude.service.PatchEntry
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Content changes route by entry identity; a stranger and a missing entry both get 404. */
@RestController
@RequestMapping("/api/v1/entries")
internal class EntryChangesController(
    private val changes: ChangeEntry,
    private val patch: PatchEntry,
) {
    @Idempotent(required = false)
    @PatchMapping("/{entryId}")
    fun patchEntry(
        caller: CurrentUser,
        @PathVariable entryId: String,
        @Valid @RequestBody request: PatchEntryRequest,
        http: HttpServletRequest,
    ): ResponseEntity<EntryResponse> {
        val view =
            patch.patch(
                caller.id,
                idOrNotFound(entryId),
                EntryText.of(checkNotNull(request.text)),
                request.imageMediaId != null || request.voiceMediaId != null,
                IdempotencyInterceptor.requestOrNull(http),
            )
        val response = ResponseEntity.ok()
        if (view.replayed) response.header(IdempotencyInterceptor.REPLAYED_HEADER, "true")
        return response.body(checkNotNull(EntryResponse.from(view.view)))
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteEntry(
        caller: CurrentUser,
        @PathVariable entryId: String,
    ) {
        changes.change(caller.id, idOrNotFound(entryId), null)
    }

    private fun idOrNotFound(raw: String): EntryId =
        runCatching { EntryId(UUID.fromString(raw)) }.getOrElse { throw EntryNotFoundException() }
}

/** Text is the only editable content in C2; validation delegates to the same domain factory as submission. */
internal data class PatchEntryRequest(
    @field:NotNull @field:ValidEntryText val text: String? = null,
    val imageMediaId: UUID? = null,
    val voiceMediaId: UUID? = null,
) {
    override fun toString(): String = "PatchEntryRequest(text=(redacted))"
}
