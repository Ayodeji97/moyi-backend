package com.moyi.gratitude.web

import com.moyi.common.security.CurrentUser
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import com.moyi.common.web.idempotency.Idempotent
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import com.moyi.gratitude.service.ChangeEntry
import com.moyi.gratitude.service.EntryNotFoundException
import com.moyi.gratitude.service.MediaChange
import com.moyi.gratitude.service.MediaSlot
import com.moyi.gratitude.service.PatchEntry
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
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
                request.media(),
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
 * one ([EntryChangesController], [FavouritesController],
 * [ReactionsController]), so the routes cannot come to differ in it.
 */
internal fun entryIdOrNotFound(raw: String): EntryId =
    runCatching { EntryId(UUID.fromString(raw)) }.getOrElse { throw EntryNotFoundException() }

/**
 * The words, which are required and replace the entry's, and since D2 a photo:
 * [imageMediaId] attaches one (FR-044a), in place of any the entry has; absent,
 * the entry's photo is left as it is. Since D3 [voiceMediaId] does the same for
 * a voice note.
 * Validation delegates to the same domain factory as submission.
 *
 * **[removeImage] and [removeVoice] take one off** (issue #164, ADR-0048).
 * Flags, not a JSON `null` on the id: the server reads an explicit `null` as
 * absent, and so does the generated Kotlin client, so `null`-means-remove is a
 * request no client could make on purpose. Removing what is not there is a
 * `200` that changes nothing. A flag with the id it would remove is `422`:
 * "attach this" and "take it off" at once is a client that has misread the
 * contract, and guessing which it meant is the server choosing for it.
 *
 * The two `@AssertTrue` properties are `private`, as `PatchBondRequest`'s are,
 * so that springdoc does not publish them as fields; the `422` names them.
 */
internal data class PatchEntryRequest(
    @field:NotNull @field:ValidEntryText val text: String? = null,
    val imageMediaId: UUID? = null,
    val voiceMediaId: UUID? = null,
    val removeImage: Boolean = false,
    val removeVoice: Boolean = false,
) {
    @get:AssertTrue(message = "removeImage and imageMediaId cannot both be given: remove the photo, or attach one in its place")
    private val removeImageWithoutImageMediaId: Boolean get() = !(removeImage && imageMediaId != null)

    @get:AssertTrue(message = "removeVoice and voiceMediaId cannot both be given: remove the voice note, or attach one in its place")
    private val removeVoiceWithoutVoiceMediaId: Boolean get() = !(removeVoice && voiceMediaId != null)

    /** What the body asks of each medium. Only after validation: a body asking for both has been refused. */
    fun media(): MediaChange = MediaChange(image = slot(imageMediaId, removeImage), voice = slot(voiceMediaId, removeVoice))

    override fun toString(): String =
        "PatchEntryRequest(text=(redacted), imageMediaId=$imageMediaId, voiceMediaId=$voiceMediaId, " +
            "removeImage=$removeImage, removeVoice=$removeVoice)"

    private companion object {
        fun slot(
            id: UUID?,
            remove: Boolean,
        ): MediaSlot =
            when {
                remove -> MediaSlot.Remove
                id != null -> MediaSlot.Attach(id)
                else -> MediaSlot.Keep
            }
    }
}
