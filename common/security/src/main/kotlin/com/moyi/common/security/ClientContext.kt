package com.moyi.common.security

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * What a controller may know about the caller's device, in the only form it
 * is allowed to keep: hashed. `consent_records.ip_hash` and
 * `user_agent_hash` (doc 07 §2, FR-011) are corroborating evidence, and the
 * column comments say why the address itself is never stored.
 *
 * Resolved like [CurrentUser] — a parameter of this type on a handler method
 * — so that no controller reads a header or touches the resolver, and the
 * address exists as a string only inside `common:security`.
 */
data class ClientContext(
    val addressHash: String,
    /** `null` when the request carried no `User-Agent`; a hash of the empty string would look like evidence. */
    val userAgentHash: String?,
)

@Component
class ClientContextArgumentResolver(
    private val addresses: ClientAddressResolver,
    private val hasher: PersonalDataHasher,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == ClientContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): ClientContext {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)!!
        return ClientContext(
            addressHash = hasher.hash(addresses.resolve(request).hostAddress),
            userAgentHash = request.getHeader(HttpHeaders.USER_AGENT)?.takeIf(String::isNotBlank)?.let(hasher::hash),
        )
    }
}
