package com.moyi.notification.infra.push

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.moyi.common.testing.MutableClock
import com.moyi.notification.api.PushCategory
import com.moyi.notification.api.PushDelivery
import com.moyi.notification.api.PushMessage
import com.moyi.notification.api.PushToken
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.RequestMatcher
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The FCM adapter, by the HTTP it produces and how it reads what comes back,
 * against [MockRestServiceServer] bound to the real `RestClient` (the way
 * `ResendEmailSenderTest` tests email). Every answer FCM documents for a send
 * is mapped to the [PushDelivery] the consumer acts on (spec N1's tests:
 * `UNREGISTERED` prunes, `503`/`429` back off, a `400` on the payload is final).
 */
internal class FcmPushSenderTest {
    private val builder: RestClient.Builder = RestClient.builder()
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val clock = MutableClock(Instant.parse("2026-10-27T18:00:00Z"))
    private val sender = FcmPushSender.create(fcm(), builder, clock)
    private val bond = UUID.fromString("0b0e0d00-0000-4000-8000-000000000001")

    // Ninety minutes left at the clock's start: what the payload's TTL and expiry are read against.
    private val staleAfter = Instant.parse("2026-10-27T19:30:00Z")
    private val message = PushMessage(PushToken(DEVICE_TOKEN), PushCategory.REVEAL, bond, staleAfter)

    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val spring = LoggerFactory.getLogger("org.springframework") as Logger
    private val captured = ListAppender<ILoggingEvent>()

    @AfterEach
    fun detach() {
        root.detachAppender(captured)
        spring.level = null
    }

    @Test
    fun `exchanges a signed assertion for a bearer, then sends keys and ids to the device's token`() {
        server
            .expect(requestTo(TOKEN_URI))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(assertionIsValid())
            .andRespond(withSuccess(BEARER_ANSWER, MediaType.APPLICATION_JSON))
        server
            .expect(requestTo("https://fcm.test/v1/projects/moyi-test/messages:send"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer $BEARER"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.message.token").value(DEVICE_TOKEN))
            .andExpect(jsonPath("$.message.data.bondId").value(bond.toString()))
            .andExpect(jsonPath("$.message.data.kind").value("REVEAL"))
            .andExpect(jsonPath("$.message.android.collapse_key").value("REVEAL:$bond"))
            // The deadline reaches the phone: 90 minutes left at send time, and the absolute instant for APNs.
            .andExpect(jsonPath("$.message.android.ttl").value("5400s"))
            .andExpect(jsonPath("$.message.apns.headers.apns-expiration").value(staleAfter.epochSecond.toString()))
            .andExpect(jsonPath("$.message.android.notification.title_loc_key").value("push_reveal_title"))
            .andExpect(jsonPath("$.message.android.notification.body_loc_key").value("push_reveal_body"))
            .andExpect(jsonPath("$.message.apns.headers.apns-collapse-id").value("REVEAL:$bond"))
            .andExpect(jsonPath("$.message.apns.payload.aps.alert.title-loc-key").value("push_reveal_title"))
            .andExpect(jsonPath("$.message.apns.payload.aps.alert.loc-key").value("push_reveal_body"))
            // Spec §5 Q6: no words authored here, no name; nothing but keys and opaque ids.
            .andExpect(jsonPath("$.message.notification").doesNotExist())
            .andExpect(jsonPath("$.message.android.notification.title").doesNotExist())
            .andExpect(jsonPath("$.message.apns.payload.aps.alert.title").doesNotExist())
            .andRespond(withSuccess("""{"name":"projects/moyi-test/messages/0:1"}""", MediaType.APPLICATION_JSON))

        sender.send(message) shouldBe PushDelivery.Accepted("projects/moyi-test/messages/0:1")
        server.verify()
    }

    @Test
    fun `a push sent at or past its deadline asks for no time at all, never a negative one`() {
        @Suppress("UNCHECKED_CAST")
        fun ttlAt(now: Instant) =
            ((FcmPushSender.payloadOf(message, now)["message"] as Map<String, Any>)["android"] as Map<String, Any>)["ttl"]
        ttlAt(staleAfter.minusSeconds(1)) shouldBe "1s"
        ttlAt(staleAfter) shouldBe "0s"
        ttlAt(staleAfter.plusSeconds(60)) shouldBe "0s"
    }

    @Test
    fun `the bearer is kept until five minutes before it expires, then fetched again`() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI)).andRespond(withSuccess(BEARER_ANSWER, MediaType.APPLICATION_JSON))
        server.expect(ExpectedCount.twice(), requestTo(SEND_URI)).andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Accepted>()
        clock.advance(Duration.ofMinutes(54))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Accepted>()
        server.verify()

        server.reset()
        server
            .expect(requestTo(TOKEN_URI))
            .andRespond(withSuccess("""{"access_token":"ya29.second","expires_in":3599}""", MediaType.APPLICATION_JSON))
        server
            .expect(requestTo(SEND_URI))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer ya29.second"))
            .andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON))
        clock.advance(Duration.ofMinutes(1))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Accepted>()
        server.verify()
    }

    @Test
    fun `UNREGISTERED means the token is gone, and the consumer will forget it`() {
        answer(HttpStatus.NOT_FOUND, error(404, "NOT_FOUND", "Requested entity was not found.", fcmCode = "UNREGISTERED"))
        sender.send(message).shouldBeInstanceOf<PushDelivery.TokenInvalid>()
    }

    @Test
    fun `a 404 that is not FCM's is a refusal, and no token is called invalid for it`() {
        // A wrong base URL, or a proxy's page: every token would get this answer, and forgetting them all on it
        // would silence every phone.
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(BEARER_ANSWER, MediaType.APPLICATION_JSON))
        server
            .expect(requestTo(SEND_URI))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_HTML).body("<html><h1>404 Not Found</h1></html>"))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Rejected>()

        server.reset()
        answer(HttpStatus.NOT_FOUND, error(404, "NOT_FOUND", "Requested entity was not found."), fetchBearer = false)
        sender.send(message).shouldBeInstanceOf<PushDelivery.Rejected>()
    }

    @Test
    fun `a token of another Firebase project is no good here either`() {
        answer(HttpStatus.FORBIDDEN, error(403, "PERMISSION_DENIED", "SenderId mismatch", fcmCode = "SENDER_ID_MISMATCH"))
        sender.send(message).shouldBeInstanceOf<PushDelivery.TokenInvalid>()
    }

    @Test
    fun `a 400 that names the token is an invalid token, whether by a field violation or in its prose`() {
        answer(
            HttpStatus.BAD_REQUEST,
            error(400, "INVALID_ARGUMENT", "Invalid value", fieldViolation = "message.token"),
        )
        sender.send(message).shouldBeInstanceOf<PushDelivery.TokenInvalid>()

        server.reset()
        answer(
            HttpStatus.BAD_REQUEST,
            error(400, "INVALID_ARGUMENT", "The registration token is not a valid FCM registration token", fcmCode = "INVALID_ARGUMENT"),
            fetchBearer = false,
        )
        sender.send(message).shouldBeInstanceOf<PushDelivery.TokenInvalid>()
    }

    @Test
    fun `a 400 about the payload is final and is not the token's fault`() {
        answer(
            HttpStatus.BAD_REQUEST,
            error(400, "INVALID_ARGUMENT", "Invalid JSON payload received.", fieldViolation = "message"),
        )
        sender.send(message).shouldBeInstanceOf<PushDelivery.Rejected>()
    }

    @Test
    fun `a 429 and a 5xx are worth retrying`() {
        answer(HttpStatus.TOO_MANY_REQUESTS, error(429, "RESOURCE_EXHAUSTED", "Quota exceeded", fcmCode = "QUOTA_EXCEEDED"))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Unavailable>()

        server.reset()
        answer(HttpStatus.SERVICE_UNAVAILABLE, error(503, "UNAVAILABLE", "The service is unavailable", fcmCode = "UNAVAILABLE"), false)
        sender.send(message).shouldBeInstanceOf<PushDelivery.Unavailable>()
    }

    @Test
    fun `a bearer FCM refuses is dropped, and the retry fetches a new one`() {
        answer(HttpStatus.UNAUTHORIZED, error(401, "UNAUTHENTICATED", "Request had invalid authentication credentials."))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Unavailable>()
        server.verify()

        server.reset()
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(BEARER_ANSWER, MediaType.APPLICATION_JSON))
        server.expect(requestTo(SEND_URI)).andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Accepted>()
        server.verify()
    }

    @Test
    fun `APNs credentials FCM cannot use are a refusal no retry will change`() {
        answer(HttpStatus.UNAUTHORIZED, error(401, "UNAUTHENTICATED", "Auth error from APNS", fcmCode = "THIRD_PARTY_AUTH_ERROR"))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Rejected>()
    }

    @Test
    fun `an unreadable error body, a dropped connection and a 2xx that is not JSON are handled, never thrown`() {
        answer(HttpStatus.BAD_REQUEST, "<html>bad gateway page</html>")
        sender.send(message).shouldBeInstanceOf<PushDelivery.Rejected>()

        server.reset()
        server.expect(requestTo(SEND_URI)).andRespond(withException(IOException("connection reset")))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Unavailable>()

        server.reset()
        server.expect(requestTo(SEND_URI)).andRespond(withSuccess("<html>captive portal</html>", MediaType.TEXT_HTML))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Unavailable>()
    }

    @Test
    fun `Google's token endpoint refusing the service account is final, and its being down is not`() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""{"error":"invalid_grant"}"""))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Rejected>()

        server.reset()
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Unavailable>()

        // Rate-limited: an outage by nature, retried with backoff, never FAILED_PERMANENT.
        server.reset()
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Unavailable>()

        server.reset()
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("""{"token_type":"Bearer"}""", MediaType.APPLICATION_JSON))
        sender.send(message).shouldBeInstanceOf<PushDelivery.Unavailable>()
    }

    @Test
    fun `with Spring's HTTP client at TRACE, neither the token, the bearer nor the assertion reaches a log line`() {
        // DefaultRestClient prints an object body whole at DEBUG ("Writing [...]"). The sender hands it bytes.
        captured.start()
        root.addAppender(captured)
        spring.level = Level.TRACE
        val assertions = mutableListOf<String>()
        server
            .expect(requestTo(TOKEN_URI))
            .andExpect { request -> assertions += form(request)["assertion"]!! }
            .andRespond(withSuccess(BEARER_ANSWER, MediaType.APPLICATION_JSON))
        server.expect(requestTo(SEND_URI)).andRespond(withSuccess(ACCEPTED, MediaType.APPLICATION_JSON))

        sender.send(message).shouldBeInstanceOf<PushDelivery.Accepted>()

        val lines = captured.list.map { it.formattedMessage }
        // The client did log at DEBUG ("Reading to [...]" for each answer): this test saw it write.
        (lines.any { it.startsWith("Reading to") }) shouldBe true
        val secrets = listOf(DEVICE_TOKEN, BEARER, assertions.single())
        lines.filter { line -> secrets.any { it in line } }.shouldBeEmpty()
    }

    @Test
    fun `refuses to be built without its three credentials, naming them, and reads a key file's escaped line breaks`() {
        val missing = shouldThrow<IllegalStateException> { FcmPushSender.create(fcm(projectId = null), builder, clock) }
        missing.message!! shouldContain "moyi.notification.push.fcm.project-id"
        missing.message!! shouldContain "LOG"

        val notPem = shouldThrow<IllegalStateException> { FcmPushSender.create(fcm(pem = "not a key"), builder, clock) }
        notPem.message!! shouldContain "is not a PEM block"
        val garbage = "$PEM_HEADER\nAAAA\n$PEM_FOOTER"
        shouldThrow<IllegalStateException> { FcmPushSender.create(fcm(pem = garbage), builder, clock) }
            .message!! shouldContain "is not a PKCS#8 RSA private key"
        shouldThrow<IllegalStateException> { FcmPushSender.create(fcm(pem = garbage.replace("AAAA", "%%%")), builder, clock) }
            .message!! shouldContain "is not valid base64"

        // As the JSON key file writes it: one line, `\n` spelt out.
        FcmPushSender.create(fcm(pem = PRIVATE_KEY_PEM.replace("\n", "\\n")), builder, clock).shouldBeInstanceOf<FcmPushSender>()
    }

    @Test
    fun `the properties never print the private key`() {
        fcm().toString() shouldNotContain "PRIVATE KEY"
        fcm().toString() shouldContain "privateKeyPem=<redacted>"
        fcm(pem = null).toString() shouldContain "privateKeyPem=null"
    }

    private fun answer(
        status: HttpStatus,
        body: String,
        fetchBearer: Boolean = true,
    ) {
        if (fetchBearer) server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess(BEARER_ANSWER, MediaType.APPLICATION_JSON))
        server.expect(requestTo(SEND_URI)).andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body))
    }

    /** The assertion is RS256-signed by the service account's key, for Google's token endpoint, asking for FCM's scope. */
    private fun assertionIsValid() =
        RequestMatcher { request ->
            val fields = form(request)
            fields["grant_type"] shouldBe "urn:ietf:params:oauth:grant-type:jwt-bearer"
            val jwt = SignedJWT.parse(fields["assertion"])
            jwt.verify(RSASSAVerifier(PUBLIC_KEY)) shouldBe true
            jwt.header.algorithm.name shouldBe "RS256"
            jwt.header.keyID shouldBe "key-1"
            val claims = jwt.jwtClaimsSet
            claims.issuer shouldBe "push@moyi-test.iam.gserviceaccount.com"
            claims.audience shouldBe listOf(TOKEN_URI)
            claims.getStringClaim("scope") shouldBe "https://www.googleapis.com/auth/firebase.messaging"
            claims.issueTime.toInstant() shouldBe clock.instant()
            claims.expirationTime.toInstant() shouldBe clock.instant().plus(Duration.ofHours(1))
        }

    private fun form(request: org.springframework.http.client.ClientHttpRequest): Map<String, String> =
        (request as MockClientHttpRequest)
            .bodyAsString
            .split('&')
            .associate { pair -> pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8) }

    internal companion object {
        const val TOKEN_URI = "https://oauth2.test/token"
        const val SEND_URI = "https://fcm.test/v1/projects/moyi-test/messages:send"
        const val DEVICE_TOKEN = "dvc-token-0123456789abcdef"
        const val BEARER = "ya29.test-bearer"
        const val BEARER_ANSWER = """{"access_token":"$BEARER","expires_in":3599,"token_type":"Bearer"}"""
        const val ACCEPTED = """{"name":"projects/moyi-test/messages/0:1"}"""

        // The armour as constants, each closed by its quote, so that no line of this file has the shape of a
        // key for the secret scanner (.gitleaks.toml): the key itself is generated when the test runs.
        private const val PEM_HEADER = "-----BEGIN PRIVATE KEY-----"
        private const val PEM_FOOTER = "-----END PRIVATE KEY-----"
        private val KEYS = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val PUBLIC_KEY = KEYS.public as RSAPublicKey
        val PRIVATE_KEY_PEM =
            PEM_HEADER + "\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(KEYS.private.encoded) +
                "\n" + PEM_FOOTER + "\n"

        fun fcm(
            projectId: String? = "moyi-test",
            pem: String? = PRIVATE_KEY_PEM,
        ) = PushProperties.Fcm(
            projectId = projectId,
            clientEmail = "push@moyi-test.iam.gserviceaccount.com",
            privateKeyPem = pem,
            privateKeyId = "key-1",
            baseUrl = "https://fcm.test",
            tokenUri = TOKEN_URI,
        )

        fun error(
            code: Int,
            status: String,
            message: String,
            fcmCode: String? = null,
            fieldViolation: String? = null,
        ): String {
            val details =
                listOfNotNull(
                    fcmCode?.let { """{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$it"}""" },
                    fieldViolation?.let {
                        """{"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{"field":"$it","description":"x"}]}"""
                    },
                )
            return """{"error":{"code":$code,"message":"$message","status":"$status","details":[${details.joinToString(",")}]}}"""
        }
    }
}
