package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.common.security.CurrentUser
import com.moyi.common.web.NotFoundException
import com.moyi.common.web.Representation
import com.moyi.common.web.Representations
import com.moyi.gratitude.service.DayNotFoundException
import com.moyi.gratitude.service.GetDays
import com.moyi.gratitude.service.ReconcileJoiningDay
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The archive (FR-090, spec §5.2, §6.6): `GET /api/v1/bonds/{bondId}/days`,
 * a page of it, and `GET /api/v1/bonds/{bondId}/days/{date}`, one day.
 * Behind the bearer, as every bond-scoped path is.
 *
 * **The guard first, then everything else the caller sent.** A non-member,
 * an unknown id and a value that is not a UUID get the one `404` (T-02)
 * whatever else is in the request: the query parameters and the date arrive
 * as text and are read only after the membership is resolved (`DaysQuery`
 * has why they are not bound as typed arguments). A member who sends a
 * parameter that cannot be read gets a `422`; a member who asks for a date
 * that is not in their archive gets `404 DAY_NOT_FOUND` ([day]).
 *
 * **A read that may write first**, exactly as `GET /today` is: after the
 * guard each calls `ReconcileJoiningDay.beforeRead`. Spec §12.4 makes the
 * joining-day reconcile the work of the first gratitude operation, "every
 * one of them", and these are two. Without it a couple who both wrote on
 * the day they paired, and then opened the archive before anything else,
 * would be shown that day `SUSPENDED` with each other's entry locked. It
 * does nothing, and takes no lock, unless that day is still `SUSPENDED`.
 *
 * A member of an ended bond, one who has left, and one whose bond is counting
 * down to deletion all read both: an ended bond is its archive (ADR-0028).
 *
 * **Both are conditional reads** (spec §6.6). The response is serialised
 * once and sent with a strong `ETag`, the digest of exactly those bytes, and
 * `Cache-Control: private, no-cache`; a request whose `If-None-Match` names
 * that tag is answered `304` with no body (`Representations` has the
 * mechanism, and why it is the one `GET /bonds/{bondId}` already uses).
 * Because the tag is the whole body as this caller is given it, it moves
 * when they mark or unmark a favourite, when an entry is erased or
 * withdrawn and when a day reveals; it is not the other member's tag; and
 * it does **not** move when the other member marks anything, because
 * nothing of theirs is in the body. No bond-row version could say all that.
 * The tag is made from the response, so every check a `200` passes a `304`
 * has passed too, and a refusal has none.
 *
 * **`Accept` is settled by the mapping, before the handler.** Both routes
 * declare what they produce: `application/json`, and any
 * `application/<x>+json` so that a client which lists
 * `application/problem+json` (the type of this API's errors, and so a thing
 * its clients send) is answered and not refused. What it is answered with
 * is the same bytes under the same name, `application/json`, whichever it
 * asked for (`RepresentationConverter`). A request that will take neither
 * is the API's standing `406`, and here Spring gives it **before the guard
 * runs**: it is decided from the request's own header, it is the same for a
 * member, a stranger and a bond that does not exist, and nothing has been
 * read, so there is no tag to send with it. Without the declaration the
 * `406` came after the read and carried the day's `ETag` and `private,
 * no-cache` (found by review, by asking).
 *
 * No bucket of its own: reads under the global per-user limit, as `today`
 * and `streak` are.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class DaysController(
    private val access: BondAccess,
    private val getDays: GetDays,
    private val joining: ReconcileJoiningDay,
    private val representations: Representations,
) {
    // Named `days`: the method name is the API's operationId (see EntriesController).
    @Suppress("LongParameterList") // The route's own parameters: Spring binds each from the request.
    @GetMapping("/{bondId}/days", produces = [MediaType.APPLICATION_JSON_VALUE, ANY_JSON])
    fun days(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) until: String?,
        @RequestParam(required = false) favourites: String?,
    ): ResponseEntity<Representation<DaysResponse>> {
        // The guard, and whose archive this is. Not who has withdrawn: GetDays asks that again, after it has the entries.
        val membership = membershipOf(caller, bondId)
        val query = DaysQuery.parse(limit, cursor, until, favourites)
        joining.beforeRead(membership)
        val page = getDays.page(membership, query.before, query.until, query.limit, query.favouritesOnly)
        return representations.revalidated(DaysResponse.from(page))
    }

    /**
     * One day, **as the feed gives it**: the same [DayResponse], from the
     * same function, of a day found by the feed's own rule.
     *
     * **`404 DAY_NOT_FOUND` for every date that is not in the caller's
     * archive, and nothing in the answer says which kind it was**
     * (`ErrorCode.DAY_NOT_FOUND` lists them). The date is taken as text for
     * that reason as well as for the guard's: bound as a `LocalDate`, a
     * value that is not one would be Spring's `400`, a second answer, and
     * one that quotes what was sent. What is and is not a date is
     * [strictIsoDate]'s, the feed's own reading of `until`.
     *
     * The date is read before the joining day is reconciled, so a request
     * that names no day changes nothing.
     */
    @GetMapping("/{bondId}/days/{date}", produces = [MediaType.APPLICATION_JSON_VALUE, ANY_JSON])
    fun day(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @PathVariable date: String,
    ): ResponseEntity<Representation<DayResponse>> {
        val membership = membershipOf(caller, bondId)
        val on = strictIsoDate(date) ?: throw DayNotFoundException()
        joining.beforeRead(membership)
        val day = getDays.day(membership, on) ?: throw DayNotFoundException()
        return representations.revalidated(DayResponse.from(day))
    }

    /** The guard: the caller's membership of the bond [bondId] names, or the one `404`. */
    private fun membershipOf(
        caller: CurrentUser,
        bondId: String,
    ): BondMembership {
        val id = runCatching { UUID.fromString(bondId) }.getOrElse { throw NotFoundException("That bond was not found.") }
        return access.membershipOf(caller.id, id)
    }

    private companion object {
        /** Any `application/<x>+json`: see the class's note on `Accept`. */
        const val ANY_JSON = "application/*+json"
    }
}
