package com.moyi.gratitude.web

import com.moyi.bond.api.BondAccess
import com.moyi.common.security.CurrentUser
import com.moyi.common.web.NotFoundException
import com.moyi.gratitude.service.GetDays
import com.moyi.gratitude.service.ReconcileJoiningDay
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * `GET /api/v1/bonds/{bondId}/days` (FR-090, spec §5.2, §6.6): the archive.
 * Behind the bearer, as every bond-scoped path is.
 *
 * **The guard first, then the parameters.** A non-member, an unknown id and a
 * value that is not a UUID get the one `404` (T-02) whatever else they sent:
 * the four query parameters arrive as text and are read only after the
 * membership is resolved (`DaysQuery` has why they are not bound as typed
 * arguments). A member who sends one that cannot be read gets a `422`.
 *
 * **A read that may write first**, exactly as `GET /today` is: after the
 * guard it calls `ReconcileJoiningDay.beforeRead`. Spec §12.4 makes the
 * joining-day reconcile the work of the first gratitude operation, "every
 * one of them", and this is one. Without it a couple who both wrote on the
 * day they paired, and then opened the archive before anything else, would
 * be shown that day `SUSPENDED` with each other's entry locked. It does
 * nothing, and takes no lock, unless that day is still `SUSPENDED`.
 *
 * A member of an ended bond, one who has left, and one whose bond is counting
 * down to deletion all read it: an ended bond is its archive (ADR-0028).
 *
 * No bucket of its own: a read under the global per-user limit, as `today`
 * and `streak` are.
 */
@RestController
@RequestMapping("/api/v1/bonds")
internal class DaysController(
    private val access: BondAccess,
    private val getDays: GetDays,
    private val joining: ReconcileJoiningDay,
) {
    // Named `days`: the method name is the API's operationId (see EntriesController).
    @Suppress("LongParameterList") // The route's own parameters: Spring binds each from the request.
    @GetMapping("/{bondId}/days")
    fun days(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) until: String?,
        @RequestParam(required = false) favourites: String?,
    ): DaysResponse {
        val id = runCatching { UUID.fromString(bondId) }.getOrElse { throw NotFoundException("That bond was not found.") }
        // The guard, and whose archive this is. Not who has withdrawn: GetDays asks that again, after it has the entries.
        val membership = access.membershipOf(caller.id, id)
        val query = DaysQuery.parse(limit, cursor, until, favourites)
        joining.beforeRead(membership)
        return DaysResponse.from(getDays.page(membership, query.before, query.until, query.limit, query.favouritesOnly))
    }
}
