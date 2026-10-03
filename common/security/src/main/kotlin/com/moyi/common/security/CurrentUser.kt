package com.moyi.common.security

import com.moyi.common.security.ratelimit.RateLimitInterceptor
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.UUID

/**
 * Who is calling. A controller declares a parameter of this type and gets
 * the authenticated user's id; nothing else about the token reaches it.
 *
 * A [HandlerMethodArgumentResolver] rather than `@AuthenticationPrincipal Jwt`
 * on every method, for one reason: the alternative puts
 * `org.springframework.security.oauth2.jwt.Jwt` in the signature of every
 * authenticated endpoint and a `UUID.fromString(jwt.subject)` in its body.
 * That is Spring Security's type and a parsing step repeated in every
 * controller — doc 18 §4 keeps controllers to mapping HTTP onto the service
 * and back. Here the parse happens once, and a controller's signature says
 * `CurrentUser`, which is what it means.
 *
 * A plain class, not a `value class`, and that is deliberate: Kotlin compiles
 * a `value class` parameter down to its underlying `UUID` with a mangled
 * method name, so Spring MVC sees a `UUID` parameter, this resolver never
 * matches it, and the request is a 500 — "Parameter specified as non-null is
 * null", found on the first run of the chain test. The wrapping still buys
 * what it is for — a `CurrentUser` cannot be passed where a bond id or a
 * media id is expected — at the cost of one small allocation per request.
 */
data class CurrentUser(
    val id: UUID,
    /** The refresh-token family the token was minted for (`sid`), or `null` for a token minted outside a session. */
    val sessionId: UUID? = null,
)

@Component
class CurrentUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == CurrentUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): CurrentUser {
        // The filter chain has already refused anything without a valid
        // bearer token, so this only fires if a `CurrentUser` parameter is
        // placed on an endpoint the chain treats as public — a wiring
        // mistake, surfaced as a 401 rather than a null dereference.
        val jwt =
            SecurityContextHolder.getContext().authentication?.principal as? Jwt
                ?: throw AuthenticationCredentialsNotFoundException("No authenticated user on a request that requires one")
        return CurrentUser(
            id = UUID.fromString(jwt.subject),
            sessionId = jwt.getClaimAsString(AccessTokenIssuer.SESSION_CLAIM)?.let(UUID::fromString),
        )
    }
}

/** Plugs this module's two argument resolvers and its interceptor into MVC. */
@Component
class SecurityWebMvcConfigurer(
    private val currentUser: CurrentUserArgumentResolver,
    private val clientContext: ClientContextArgumentResolver,
    private val rateLimits: RateLimitInterceptor,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUser)
        resolvers.add(clientContext)
    }

    /**
     * Fix round 1, I4: an explicit order, not the registration order two
     * unrelated `WebMvcConfigurer` beans across two modules happen to run
     * in. [RateLimitInterceptor] must run before `common.web.idempotency.IdempotencyInterceptor`:
     * a caller with no tokens left must never have its body buffered and
     * fingerprinted for a request that was never going to run. (This note
     * once gave a second reason — a reservation made before the limiter
     * refused could be stored and replayed. That cannot happen any more:
     * since the C1 rework the interceptor writes nothing, and the key is
     * reserved inside the handler's own transaction, ADR-0031 decision 8.)
     *
     * `common.web.idempotency.IdempotencyConfiguration.ORDER` is a fixed,
     * generously late value (`1000`), not an offset from [RATE_LIMIT_ORDER]
     * — `common:web` cannot import this class (`common:security` depends on
     * `common:web`, not the other way round; see `IdempotencyInterceptor.callerId`'s
     * own KDoc for the same reason), so that file states its own reasoning
     * rather than referencing this constant. [RATE_LIMIT_ORDER] only needs
     * to stay well below `1000` for the two to agree; it is not read by
     * that other module.
     */
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(rateLimits).order(RATE_LIMIT_ORDER)
    }

    companion object {
        /** See [addInterceptors]'s own KDoc for why every `@Idempotent`-registering site must use a later order than this. */
        const val RATE_LIMIT_ORDER = 0
    }
}
