package com.moyi.common.web

import org.springframework.http.HttpStatus

/**
 * Doc 06 §2: "not found **or not permitted to know it exists**". The one
 * exception for both, because telling them apart is the confirmation T-02
 * exists to deny: a `403` for another person's session, bond or entry says
 * the id is real. Thrown by a service when a lookup scoped to the caller
 * finds nothing; the catch-all turns it into `404 NOT_FOUND`.
 *
 * [detail] names the kind of thing, never the id — the id is already in
 * `instance`, as the path the caller typed, which is theirs to see.
 */
open class NotFoundException(
    detail: String,
) : ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, detail)
