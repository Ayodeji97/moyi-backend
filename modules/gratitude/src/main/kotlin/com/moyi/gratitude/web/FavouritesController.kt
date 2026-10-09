package com.moyi.gratitude.web

import com.moyi.common.security.CurrentUser
import com.moyi.gratitude.service.FavouriteEntry
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * `PUT` and `DELETE /api/v1/entries/{entryId}/favourite` (FR-093, spec §5.2,
 * §6.6): a member keeps an entry, or stops keeping it. Behind the bearer, as
 * everything outside the public auth endpoints is.
 *
 * **A resource the caller sets and unsets, so `PUT` and `DELETE`, both `204`
 * and both repeatable.** There is no body in either direction: the mark has
 * no content, and the entry's `favourited` is where a client reads it back.
 * Neither takes an `Idempotency-Key`, because a repeat already changes
 * nothing.
 *
 * **By entry id, like the two routes beside it**, and with their one `404`
 * for an entry the caller was never shown: no such id, not an id, another
 * bond's entry, and a partner's entry that is still locked. Who may mark
 * what is `FavouriteEntry`'s to say; nothing is decided here.
 *
 * **No bucket of their own.** Both sit under the global per-user limit that
 * every authenticated request passes through and carry no `@RateLimited`: a
 * mark is one small statement, repeating it changes nothing, and it is not a
 * place where an attacker gains by volume (an id that was never shown is the
 * same `404` however many are tried).
 *
 * The method names are the API's `operationId`s (`EntriesController` has
 * why), and `contracts.OpenApiConfiguration` lists `favouriteEntry` among
 * the operations that can answer `409`, and deliberately not
 * `unfavouriteEntry`.
 */
@RestController
@RequestMapping("/api/v1/entries")
internal class FavouritesController(
    private val favourite: FavouriteEntry,
) {
    /**
     * `204` once the caller's mark is on the entry, whether or not it was
     * already. `409 ENTRY_NOT_REVEALED` for the caller's own entry that has
     * not been revealed; `409 ENTRY_IMMUTABLE` for a tombstone, and for an
     * entry whose row an erasure holds past the mark's two seconds.
     */
    @PutMapping("/{entryId}/favourite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun favouriteEntry(
        caller: CurrentUser,
        @PathVariable entryId: String,
    ) {
        favourite.mark(caller.id, entryIdOrNotFound(entryId))
    }

    /** `204` once the caller has no mark on the entry, whether or not there was one, and whatever has become of the entry. */
    @DeleteMapping("/{entryId}/favourite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unfavouriteEntry(
        caller: CurrentUser,
        @PathVariable entryId: String,
    ) {
        favourite.unmark(caller.id, entryIdOrNotFound(entryId))
    }
}
