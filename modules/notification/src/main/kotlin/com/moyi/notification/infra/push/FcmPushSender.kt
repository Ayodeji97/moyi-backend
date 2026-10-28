package com.moyi.notification.infra.push

import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushSender
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.StreamingHttpOutputMessage
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * [PushSender] over FCM's HTTP v1 API, the one class here that knows Firebase
 * or Google exist (doc 25:118, ADR-0044).
 *
 * **Two calls, both by `RestClient`.** FCM v1 takes an OAuth2 bearer, which a
 * service account gets by signing a JWT assertion (RS256, signed by Nimbus,
 * already on the classpath: no hand-written crypto, doc 25 D5) and trading it
 * at Google's token endpoint. The bearer lasts an hour and is kept until five
 * minutes before that. Google's auth library would do the same in more
 * dependencies; the legacy FCM API that needed none is gone (doc 05:67).
 *
 * **A body is handed to the client as bytes, never as an object.** Spring's
 * `DefaultRestClient` writes every object body it serialises into its DEBUG
 * line ("Writing [...]"), and this body holds the device's token, and the
 * token request holds a signed credential. Bytes written through
 * [StreamingHttpOutputMessage.Body] pass no converter and are never printed.
 * `FcmPushSenderTest` runs a send with that logger at TRACE.
 *
 * **What a phone is sent.** Localisation keys chosen by category for both
 * platforms (`title_loc_key`/`body_loc_key` on Android, `title-loc-key`/
 * `loc-key` on iOS), `data: {bondId, kind}`, and a collapse key of category
 * and Bond, so that a push held back and then sent replaces its predecessor
 * rather than stacking (spec N1, Q6). No text authored here, no name.
 *
 * **Failures are classified, never thrown** ([PushDelivery]):
 * - the token is no good, **in FCM's own words**: the error code
 *   `UNREGISTERED` or `SENDER_ID_MISMATCH`, or a `400` that names the token →
 *   [PushDelivery.TokenInvalid]. A bare `404` is not enough: a wrong base URL
 *   or a proxy answers one for every token, and forgetting every token on it
 *   would silence every phone (review of #96);
 * - a `429` or a `5xx`, from FCM or from Google's token endpoint, a timeout,
 *   or a bearer FCM no longer accepts → [PushDelivery.Unavailable];
 * - any other refusal, by FCM or by the token endpoint →
 *   [PushDelivery.Rejected].
 *
 * Logged: status codes, FCM's error code, and nothing a provider wrote in
 * prose, because its messages can quote what was sent.
 */
internal class FcmPushSender(
    private val client: RestClient,
    private val account: ServiceAccount,
    private val tokenUri: String,
    private val clock: Clock,
) : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var bearer: Bearer? = null

    override fun send(message: PushMessage): PushDelivery =
        try {
            val response =
                client
                    .post()
                    .uri("${account.fcmBaseUrl}/v1/projects/{project}/messages:send", account.projectId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${bearerToken()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(bytes(MAPPER.writeValueAsBytes(payloadOf(message, clock.instant()))))
                    .retrieve()
                    .body(JsonNode::class.java)
            log.debug("FCM accepted a push, category {}", message.category)
            PushDelivery.Accepted(response?.get("name")?.takeIf(JsonNode::isString)?.asString())
        } catch (refused: TokenEndpointRefused) {
            rejected("Google's token endpoint refused the service account (HTTP ${refused.status})")
        } catch (failed: TokenEndpointUnavailable) {
            unavailable("Google's token endpoint unavailable (${failed.what})")
        } catch (refused: HttpClientErrorException) {
            classify(refused)
        } catch (failed: HttpServerErrorException) {
            unavailable("FCM failed (HTTP ${failed.statusCode.value()})")
        } catch (unreachable: ResourceAccessException) {
            unavailable("FCM unreachable (${unreachable.cause?.javaClass?.simpleName ?: "I/O failure"})")
        } catch (unexpected: RestClientException) {
            unavailable("FCM responded unexpectedly (${unexpected.javaClass.simpleName})")
        }

    /** A 4xx from FCM, read by the one field that says what kind it is. */
    private fun classify(refused: RestClientResponseException): PushDelivery {
        val status = refused.statusCode.value()
        val error = errorOf(refused)
        val code = error.fcmErrorCode
        return when {
            status == HTTP_TOO_MANY_REQUESTS -> {
                unavailable("FCM rate-limited the request (HTTP 429, $code)")
            }

            code == "UNREGISTERED" -> {
                tokenInvalid("FCM: the token is no longer registered (HTTP $status, $code)")
            }

            code == "SENDER_ID_MISMATCH" -> {
                tokenInvalid("FCM: the token belongs to another project (HTTP $status)")
            }

            status == HTTP_BAD_REQUEST && error.namesTheToken -> {
                tokenInvalid("FCM: the token is not a valid token (HTTP 400)")
            }

            status == HTTP_UNAUTHORIZED && code != "THIRD_PARTY_AUTH_ERROR" -> {
                // Our bearer, refused: revoked or expired early. The next attempt fetches another.
                bearer = null
                unavailable("FCM refused the bearer (HTTP 401)")
            }

            else -> {
                rejected("FCM refused the message (HTTP $status, $code)")
            }
        }
    }

    /**
     * The bearer for FCM, from the cache while it has five minutes left,
     * otherwise from Google's token endpoint. Synchronised so that a burst of
     * sends after expiry makes one exchange, not one each.
     */
    @Synchronized
    private fun bearerToken(): String {
        val now = clock.instant()
        bearer?.takeIf { now.isBefore(it.refreshAfter) }?.let { return it.value }
        val answer = exchange(now)
        val value = answer?.get("access_token")?.takeIf(JsonNode::isString)?.asString()
        val lifetime = answer?.get("expires_in")?.takeIf(JsonNode::isNumber)?.asLong() ?: DEFAULT_LIFETIME_SECONDS
        if (value.isNullOrBlank()) throw TokenEndpointUnavailable("no access_token in the answer")
        bearer = Bearer(value, refreshAfter = now.plusSeconds(lifetime).minus(REFRESH_MARGIN))
        return value
    }

    /**
     * The assertion, traded at Google's token endpoint. A refusal and an
     * outage leave as two exceptions of this class's own, carrying the client's
     * exception as their cause and never into a log: [send] reports the status
     * alone, because the cause's message holds the endpoint's answer.
     */
    private fun exchange(now: Instant): JsonNode? {
        val form = "grant_type=${encode(JWT_BEARER_GRANT)}&assertion=${encode(assertion(now))}"
        return try {
            client
                .post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(bytes(form.toByteArray()))
                .retrieve()
                .body(JsonNode::class.java)
        } catch (refused: HttpClientErrorException) {
            // A 429 is a 4xx by number and an outage by nature, as for FCM itself: retried, not final.
            val status = refused.statusCode.value()
            val rateLimited = status == HTTP_TOO_MANY_REQUESTS
            throw if (rateLimited) TokenEndpointUnavailable("HTTP 429", refused) else TokenEndpointRefused(status, refused)
        } catch (failed: RestClientException) {
            throw TokenEndpointUnavailable(failed.javaClass.simpleName, failed)
        }
    }

    /** The service account's claim to a bearer (Google's "OAuth 2.0 for Server to Server Applications"). */
    private fun assertion(now: Instant): String {
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .apply { account.keyId?.let(::keyID) }
                .build()
        // Numbers, not java.util.Date: the claims are seconds since the epoch on the wire either way.
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(account.clientEmail)
                .audience(tokenUri)
                .claim("scope", MESSAGING_SCOPE)
                .claim("iat", now.epochSecond)
                .claim("exp", now.plus(ASSERTION_LIFETIME).epochSecond)
                .build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(account.privateKey)) }.serialize()
    }

    private fun tokenInvalid(reason: String): PushDelivery.TokenInvalid {
        log.info("Push token refused by the provider and will be forgotten: {}", reason)
        return PushDelivery.TokenInvalid(reason)
    }

    private fun rejected(reason: String): PushDelivery.Rejected {
        log.warn("Push rejected by provider: {}", reason)
        return PushDelivery.Rejected(reason)
    }

    private fun unavailable(reason: String): PushDelivery.Unavailable {
        log.warn("Push provider unavailable: {}", reason)
        return PushDelivery.Unavailable(reason)
    }

    /** Credentials and where they are spent. [privateKey] is never printed: this class is not a data class. */
    internal class ServiceAccount(
        val projectId: String,
        val clientEmail: String,
        val privateKey: RSAPrivateKey,
        val keyId: String?,
        val fcmBaseUrl: String,
    )

    private class Bearer(
        val value: String,
        val refreshAfter: Instant,
    )

    /** What FCM's error body says, as far as classification needs it. Read leniently: an unreadable body says nothing. */
    private class FcmError(
        val fcmErrorCode: String?,
        val namesTheToken: Boolean,
    )

    private fun errorOf(refused: RestClientResponseException): FcmError {
        val error =
            try {
                MAPPER.readTree(refused.responseBodyAsByteArray)?.get("error")
            } catch (_: JacksonException) {
                null
            }
        val details =
            error
                ?.get("details")
                ?.takeIf(JsonNode::isArray)
                ?.toList()
                .orEmpty()
        val code =
            details.firstNotNullOfOrNull { detail -> detail.get("errorCode")?.takeIf(JsonNode::isString)?.asString() }
                ?: error?.get("status")?.takeIf(JsonNode::isString)?.asString()
        val violatedFields =
            details.flatMap { detail ->
                detail
                    .get("fieldViolations")
                    ?.takeIf(JsonNode::isArray)
                    ?.mapNotNull { it.get("field")?.asString() }
                    .orEmpty()
            }
        // [inferred, ADR-0044] FCM names a malformed token either as a field
        // violation on `message.token` or only in its prose. The prose is read
        // for that one phrase and never logged or kept.
        val prose =
            error
                ?.get("message")
                ?.takeIf(JsonNode::isString)
                ?.asString()
                .orEmpty()
        val namesTheToken = "message.token" in violatedFields || prose.contains("registration token", ignoreCase = true)
        return FcmError(code, namesTheToken)
    }

    private class TokenEndpointRefused(
        val status: Int,
        cause: Throwable,
    ) : RuntimeException("token endpoint refused", cause)

    private class TokenEndpointUnavailable(
        val what: String,
        cause: Throwable? = null,
    ) : RuntimeException("token endpoint unavailable", cause)

    internal companion object {
        private val MAPPER: JsonMapper = JsonMapper.builder().build()
        private const val MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        private const val JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        private val ASSERTION_LIFETIME: Duration = Duration.ofHours(1)
        private val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)
        private const val DEFAULT_LIFETIME_SECONDS = 3600L
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * The localisation keys a phone shows for [category]: `push_<category>_title`
         * and `push_<category>_body`. The owner writes the words in the apps'
         * string tables (Q6); the server sends only the keys.
         */
        fun locKeys(category: PushCategory): Pair<String, String> {
            val stem = "push_${category.name.lowercase()}"
            return "${stem}_title" to "${stem}_body"
        }

        /** The same for every phone of a category and Bond, so a later push replaces an earlier one. */
        fun collapseKey(message: PushMessage): String = "${message.category.name}:${message.bondId}"

        /**
         * FCM v1's `messages:send` body. Maps, because the wire names mix snake case and hyphens.
         *
         * **The deadline travels with it.** Android's `ttl` is the push's remaining lifetime at
         * [now], in whole seconds and never negative (FCM's default is four weeks); APNs'
         * `apns-expiration` is the deadline itself, in epoch seconds. A phone that is offline
         * when the push is accepted is then not shown it after [PushMessage.staleAfter].
         */
        fun payloadOf(
            message: PushMessage,
            now: Instant,
        ): Map<String, Any> {
            val (title, body) = locKeys(message.category)
            val collapse = collapseKey(message)
            val remaining = Duration.between(now, message.staleAfter).seconds.coerceAtLeast(0)
            return mapOf(
                "message" to
                    mapOf(
                        "token" to message.token.value,
                        "data" to mapOf("bondId" to message.bondId.toString(), "kind" to message.category.name),
                        "android" to
                            mapOf(
                                "collapse_key" to collapse,
                                "ttl" to "${remaining}s",
                                "notification" to mapOf("title_loc_key" to title, "body_loc_key" to body),
                            ),
                        "apns" to
                            mapOf(
                                "headers" to
                                    mapOf(
                                        "apns-collapse-id" to collapse,
                                        "apns-expiration" to message.staleAfter.epochSecond.toString(),
                                    ),
                                "payload" to mapOf("aps" to mapOf("alert" to mapOf("title-loc-key" to title, "loc-key" to body))),
                            ),
                    ),
            )
        }

        private fun bytes(content: ByteArray) = StreamingHttpOutputMessage.Body { it.write(content) }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

        /**
         * Builds the sender from configuration, refusing to build one that
         * could not send: FCM is the default provider (ADR-0017's posture), so
         * a missing credential is a failed start, not a silent service.
         */
        fun create(
            fcm: PushProperties.Fcm,
            builder: RestClient.Builder,
            clock: Clock,
        ): FcmPushSender {
            val projectId = fcm.projectId
            val clientEmail = fcm.clientEmail
            val pem = fcm.privateKeyPem
            check(!projectId.isNullOrBlank() && !clientEmail.isNullOrBlank() && !pem.isNullOrBlank()) {
                "moyi.notification.push.provider is FCM (the default) but moyi.notification.push.fcm.project-id, " +
                    "moyi.notification.push.fcm.client-email and moyi.notification.push.fcm.private-key-pem are not all set. " +
                    "Supply the Firebase service account through the environment, or set the provider to LOG where " +
                    "no push may be sent. Refusing to start rather than run without a way to send a push."
            }
            val account = ServiceAccount(projectId, clientEmail, privateKeyOf(pem), fcm.privateKeyId, fcm.baseUrl)
            return FcmPushSender(builder.build(), account, fcm.tokenUri, clock)
        }

        /** PKCS#8, as Google's key file holds it, with its line breaks either real or written as `\n`. */
        private fun privateKeyOf(pem: String): RSAPrivateKey {
            val body =
                pem
                    .replace("\\n", "\n")
                    .substringAfter(PEM_HEADER, missingDelimiterValue = "")
                    .substringBefore(PEM_FOOTER, missingDelimiterValue = "")
                    .filterNot(Char::isWhitespace)
            check(body.isNotEmpty()) { "moyi.notification.push.fcm.private-key-pem is not a PEM block ($PEM_HEADER … $PEM_FOOTER)." }
            return try {
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))) as RSAPrivateKey
            } catch (unusable: java.security.GeneralSecurityException) {
                // The key's bytes are not in the message; the cause names only the kind of failure.
                throw IllegalStateException("moyi.notification.push.fcm.private-key-pem is not a PKCS#8 RSA private key.", unusable)
            } catch (unusable: IllegalArgumentException) {
                throw IllegalStateException("moyi.notification.push.fcm.private-key-pem is not valid base64.", unusable)
            }
        }

        private const val PEM_HEADER = "-----BEGIN PRIVATE KEY-----"
        private const val PEM_FOOTER = "-----END PRIVATE KEY-----"
    }
}
