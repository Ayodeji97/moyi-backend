package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.AcceptInvite
import com.moyi.bond.service.ResolveInvite
import com.moyi.common.security.CurrentUser
import com.moyi.common.security.ratelimit.RateLimitBucket
import com.moyi.common.security.ratelimit.RateLimited
import com.moyi.common.web.ErrorCode
import com.moyi.common.web.FieldViolation
import com.moyi.common.web.ProblemDetails
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import java.net.URI

/**
 * `/api/v1/invites/{code}` — resolving a code and joining with it
 * (doc 06 §3.3).
 *
 * **Both are authenticated**, and neither is in the chain's public list. Doc
 * 06 is explicit about why: an unauthenticated resolve is an oracle for
 * T-06's brute force that returns a real person's display name on a hit. The
 * order is open the link, install, sign up or sign in, verify, *then* the code
 * resolves; the `/i/{code}` page the link lands on performs no lookup at all
 * (`states.md` §2).
 *
 * Not bond-scoped, and so not behind `BondAccessGuard`: the caller cannot
 * name the bond, and is not a member of it yet. What authorises them is the
 * code, which is why the per-IP bucket both methods share is the control that
 * matters here.
 *
 * Both methods need to know *who* is asking, not merely that somebody is:
 * a block is between two accounts, and a preview that ignored it would show a
 * blocked person the bond's name before refusing them (see [ResolveInvite]).
 */
@RestController
@RequestMapping("/api/v1/invites")
internal class InvitesController(
    private val resolveInvite: ResolveInvite,
    private val acceptInvite: AcceptInvite,
    private val problems: ProblemDetails,
) {
    /**
     * The confirm screen's data (`states.md` §2). Two buckets: ten an hour for
     * this caller, above the twenty an hour this address shares with accept.
     */
    @GetMapping("/{code}")
    @RateLimited(RateLimitBucket.INVITE_LOOKUP_USER, RateLimitBucket.INVITE_CODE_IP)
    fun resolve(
        caller: CurrentUser,
        @PathVariable code: String,
    ): InvitePreviewResponse = InvitePreviewResponse.from(resolveInvite.resolve(UserId(caller.id), parse(code)))

    /**
     * FR-022, and milestone M2. The per-IP bucket is doc 06 §4's "combined"
     * twenty an hour, shared with [resolve]: 30^6 codes is a large space, and
     * that limit is what makes it a safe one in practice (T-06).
     */
    @PostMapping("/{code}/accept")
    @RateLimited(RateLimitBucket.INVITE_CODE_IP)
    fun accept(
        caller: CurrentUser,
        @PathVariable code: String,
    ): BondResponse = BondResponse.from(acceptInvite.accept(UserId(caller.id), parse(code)))

    /**
     * A code from the path, normalised.
     *
     * A malformed one is a `422` naming the field — **not** the invite 404 —
     * because it is a fact about what the person typed rather than about any
     * bond: the client can put the message under the input, and nothing is
     * disclosed by saying that six characters from a known alphabet is the
     * shape. `InviteCode.parse` upper-cases and trims first, so a pasted or
     * retyped lowercase code is not a failure.
     */
    private fun parse(code: String): InviteCode =
        runCatching { InviteCode.parse(code) }.getOrElse { rejected -> throw MalformedInviteCodeException(rejected.message) }

    /**
     * Local to this controller, because it is the only place a code arrives in
     * a path. The message is the domain's own — written not to echo its input
     * (doc 18 §5), which matters here because the input is a credential guess.
     */
    @ExceptionHandler(MalformedInviteCodeException::class)
    fun handleMalformedCode(
        exception: MalformedInviteCodeException,
        request: WebRequest,
    ): ResponseEntity<Any> =
        ResponseEntity.unprocessableEntity().body(
            problems.of(
                status = HttpStatus.UNPROCESSABLE_ENTITY,
                errorCode = ErrorCode.VALIDATION_FAILED,
                detail = "One or more fields are not valid.",
                instance = (request as? ServletWebRequest)?.request?.requestURI?.let(URI::create),
                errors = listOf(FieldViolation(field = "code", code = "VALID_INVITE_CODE", message = exception.reason)),
            ),
        )
}

internal class MalformedInviteCodeException(
    reason: String?,
) : RuntimeException(reason) {
    val reason: String = reason ?: "is not a valid invite code"
}
