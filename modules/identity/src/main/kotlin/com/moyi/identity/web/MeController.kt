package com.moyi.identity.web

import com.moyi.common.security.CurrentUser
import com.moyi.identity.domain.User
import com.moyi.identity.domain.UserId
import com.moyi.identity.domain.UserStatus
import com.moyi.identity.service.GetProfile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
) {
    @GetMapping
    fun me(caller: CurrentUser): UserResponse = UserResponse.from(getProfile.byId(UserId(caller.id)))
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
