package com.moyi.gratitude.web

import com.moyi.common.security.CurrentUser
import com.moyi.gratitude.service.ReactToEntry
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `PUT` and `DELETE /api/v1/entries/{entryId}/reaction` (FR-048): a member
 * leaves one small acknowledgement on their partner's entry, changes it, or
 * takes it back. Behind the bearer, as everything outside the public auth
 * endpoints is.
 *
 * **One reaction per member per entry, so a resource the caller sets and
 * unsets**: `PUT` with `{"type": …}` sets or replaces it, `DELETE` removes
 * it, both `204` and both repeatable in sequence, the shape of the favourite
 * routes beside these. Not at once: a `DELETE` that overlaps the member's own
 * other `DELETE`, or their own `PUT`, on an entry they read in full can be
 * `409 REACTION_BUSY`, and is retried. Doc 06 has `POST …/reactions` and `DELETE
 * …/reactions/{type}`, which is the shape for several at once. Neither
 * takes an `Idempotency-Key`, because a repeat already changes nothing, and
 * the entry's `reaction` is where a client reads it back.
 *
 * **By entry id, with the one `404`** for an entry the caller was never
 * shown: no such id, not an id, another bond's entry, and a partner's entry
 * that is still locked. Who may react to what is `ReactToEntry`'s to say;
 * nothing is decided here. A body that is not a reaction is refused before
 * that, from the request alone, and so the same for everybody.
 *
 * **No bucket of their own**, for the reasons `FavouritesController` gives.
 *
 * The method names are the API's `operationId`s, and
 * `contracts.OpenApiConfiguration` lists `reactToEntry` among the operations
 * that can answer `409`, together with `unreactToEntry` for its retryable
 * lock conflict.
 */
@RestController
@RequestMapping("/api/v1/entries")
internal class ReactionsController(
    private val reaction: ReactToEntry,
) {
    /**
     * `204` once the caller's reaction on the entry is the type sent,
     * whatever it was before. `409 REACTION_ON_OWN_ENTRY` for an entry the
     * caller wrote; `409 ENTRY_IMMUTABLE` for a tombstone, and for an entry
     * whose row an erasure holds past two seconds; `409 BOND_ARCHIVED` once
     * the bond has ended.
     */
    @PutMapping("/{entryId}/reaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reactToEntry(
        caller: CurrentUser,
        @PathVariable entryId: String,
        @Valid @RequestBody request: ReactionRequest,
    ) {
        reaction.react(caller.id, entryIdOrNotFound(entryId), request.reactionType())
    }

    /**
     * `204` when absent or removed, whatever has become of the entry or the
     * bond; `409 REACTION_BUSY` when another operation holds it on an entry
     * the caller reads in full, the caller's own overlapping request
     * included.
     */
    @DeleteMapping("/{entryId}/reaction")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unreactToEntry(
        caller: CurrentUser,
        @PathVariable entryId: String,
    ) {
        reaction.unreact(caller.id, entryIdOrNotFound(entryId))
    }
}
