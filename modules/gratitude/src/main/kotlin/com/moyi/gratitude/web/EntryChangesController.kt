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

/**
 * Content changes route by entry identity; a stranger and a missing entry both get 404.
 *
 * The two routes part ways once the bond has ended: the edit is `409
 * BOND_ARCHIVED`, the delete is still the author's to make (`ChangeEntry`
 * has why).
 */
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
                entryIdOrNotFound(entryId),
                EntryText.of(checkNotNull(request.text)),
                request.imageMediaId != null || request.voiceMediaId != null,
                IdempotencyInterceptor.requestOrNull(http),
            )
        val response = ResponseEntity.ok()
        if (view.replayed) response.header(IdempotencyInterceptor.REPLAYED_HEADER, "true")
        return response.body(checkNotNull(EntryResponse.from(view.view)))
    }

    /**
     * `204` for the author, whatever has become of the bond since: ended,
     * left, or counting down to deletion. Repeating it is `204` again and
     * changes nothing. Everybody else gets the one `404`.
     */
    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteEntry(
        caller: CurrentUser,
        @PathVariable entryId: String,
    ) {
        changes.change(caller.id, entryIdOrNotFound(entryId), null)
    }
}

/**
 * The `{entryId}` of a path, or the one `404`: a value that is not a UUID
 * cannot name an entry, and is answered exactly as a UUID that names nobody's.
 * Taken as text and parsed here for that reason, by every route that carries
 * one ([EntryChangesController], [FavouritesController]), so the routes
 * cannot come to differ in it.
 */
internal fun entryIdOrNotFound(raw: String): EntryId =
    runCatching { EntryId(UUID.fromString(raw)) }.getOrElse { throw EntryNotFoundException() }

/** Text is the only editable content in C2; validation delegates to the same domain factory as submission. */
internal data class PatchEntryRequest(
    @field:NotNull @field:ValidEntryText val text: String? = null,
    val imageMediaId: UUID? = null,
    val voiceMediaId: UUID? = null,
) {
    override fun toString(): String = "PatchEntryRequest(text=(redacted))"
}
