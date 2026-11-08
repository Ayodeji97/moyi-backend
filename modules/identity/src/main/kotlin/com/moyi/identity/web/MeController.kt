package com.moyi.identity.web

import com.moyi.common.security.ClientContext
import com.moyi.common.security.CurrentUser
import com.moyi.identity.domain.Acceptance
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.service.AcceptedLegal
import com.moyi.identity.service.GetProfile
import com.moyi.identity.service.LegalConsent
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/v1/me` (doc 06 §3.2) — the profile half of it.
 *
 * Doc 06 describes `/me` as "profile, bonds summary, unread state". The
 * bonds summary and unread state belong to modules that do not exist yet,
 * and they arrive as additional fields on this same response, which is not
 * a breaking change for a client. What ships now is what a signed-in client
 * needs before it has a Bond: who it is, and whether the address is
 * verified — the latter is what screen 3's "Check again" asks (FR-002).
 *
 * The first endpoint behind the filter chain. The `CurrentUser` parameter is
 * resolved from the bearer token; there is no user id in the path, because
 * "me" is whoever proved they are.
 */
@RestController
@RequestMapping("/api/v1/me")
internal class MeController(
    private val getProfile: GetProfile,
    private val legalConsent: LegalConsent,
) {
    @GetMapping
    fun me(caller: CurrentUser): ProfileResponse {
        val id = UserId(caller.id)
        return ProfileResponse.from(getProfile.byId(id), legalConsent.acceptedBy(id))
    }

    /**
     * `POST /me/legal-consent` (FR-011, FR-103, ADR-0056): the caller accepts
     * the **current** Terms and Privacy Policy, the versions `GET /meta/legal`
     * names. No body: what is recorded is decided here, never by the client.
     * Idempotent; `204` whether or not anything was new.
     */
    @PostMapping("/legal-consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun acceptLegalDocuments(
        caller: CurrentUser,
        client: ClientContext,
    ) {
        legalConsent.acceptCurrent(UserId(caller.id), client)
    }
}

/**
 * `GET /me`'s body: [UserResponse]'s fields, and the legal documents the
 * person accepted. Its own type rather than a field on [UserResponse], which
 * sign-in and refresh also send and which would then cost both a read they do
 * not need (ADR-0056 decision 5).
 */
internal data class ProfileResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
    val locale: String,
    val emailVerified: Boolean,
    val status: UserStatus,
    val legalAccepted: LegalAcceptedResponse,
) {
    companion object {
        fun from(
            user: User,
            legal: AcceptedLegal,
        ): ProfileResponse {
            val profile = UserResponse.from(user)
            return ProfileResponse(
                id = profile.id,
                email = profile.email,
                displayName = profile.displayName,
                locale = profile.locale,
                emailVerified = profile.emailVerified,
                status = profile.status,
                legalAccepted = LegalAcceptedResponse(legal.terms?.let(::AcceptanceResponse), legal.privacy?.let(::AcceptanceResponse)),
            )
        }
    }
}

/**
 * Which version of each consented document the person accepted, and when. A
 * version that differs from `GET /meta/legal`'s is the client's cue to ask
 * again; `null` means no acceptance is recorded, and is not a version.
 */
internal data class LegalAcceptedResponse(
    val terms: AcceptanceResponse?,
    val privacy: AcceptanceResponse?,
)

internal data class AcceptanceResponse(
    val version: String,
    val acceptedAt: Instant,
) {
    constructor(acceptance: Acceptance) : this(acceptance.version, acceptance.acceptedAt)
}

/**
 * The client's view of a user — its own. Reused by `POST /auth/login`'s
 * `user` field (doc 06 §3.1) when that lands, so the two agree by
 * construction.
 *
 * No `tokensInvalidBefore`, no `createdAt`, no internal state: the fields a
 * client renders and nothing it would have to be told not to show.
 */
internal data class UserResponse(
    val id: UUID,
    val email: String,
    val displayName: String,
    val locale: String,
    val emailVerified: Boolean,
    val status: UserStatus,
) {
    companion object {
        fun from(user: User): UserResponse =
            UserResponse(
                id = user.id.value,
                email = user.email.value,
                displayName = user.displayName,
                locale = user.locale,
                emailVerified = user.isEmailVerified,
                status = user.status,
            )
    }
}
