package com.moyi.common.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.security.KeyPairGenerator
import java.util.Base64

internal class SigningKeysTest {
    private val logger = LoggerFactory.getLogger(SigningKeys::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    @Test
    fun `configured keys are parsed from PEM, and the kid is the RFC 7638 thumbprint`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val keys = SigningKeys.from(configured(pem("PRIVATE KEY", pair.private.encoded), pem("PUBLIC KEY", pair.public.encoded)))

        keys.publicKey shouldBe pair.public
        // Same key, same thumbprint — derived, not configured.
        keys.kid shouldBe SigningKeys.from(configured(pem("PRIVATE KEY", pair.private.encoded), pem("PUBLIC KEY", pair.public.encoded))).kid
        keys.kid.length shouldBe 43
    }

    @Test
    fun `line breaks lost by an environment variable do not matter`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val flattened = pem("PRIVATE KEY", pair.private.encoded).replace("\n", " ")

        SigningKeys.from(configured(flattened, pem("PUBLIC KEY", pair.public.encoded))).publicKey shouldBe pair.public
    }

    @Test
    fun `the default source refuses to start without both PEMs, and says what to do`() {
        val failure = shouldThrow<IllegalStateException> { SigningKeys.from(JwtProperties()) }

        failure.message shouldContain "moyi.security.jwt.private-key-pem"
        failure.message shouldContain "EPHEMERAL"
    }

    @Test
    fun `a public key that does not match the private key is refused`() {
        // The failure mode this prevents is silent: every token signed, every
        // token rejected, and nothing in either log says why.
        val one = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val failure =
            shouldThrow<IllegalStateException> {
                SigningKeys.from(configured(pem("PRIVATE KEY", one.private.encoded), pem("PUBLIC KEY", other.public.encoded)))
            }

        failure.message shouldContain "not the public half"
    }

    @Test
    fun `a PEM of the wrong kind is a message naming the property, not a cast failure`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val failure =
            shouldThrow<IllegalStateException> {
                SigningKeys.from(configured(pem("PUBLIC KEY", pair.public.encoded), pem("PUBLIC KEY", pair.public.encoded)))
            }

        failure.message shouldContain "PRIVATE KEY"
    }

    @Test
    fun `an ephemeral key is generated and the application says so at WARN`() {
        val keys = SigningKeys.from(JwtProperties(keySource = JwtProperties.KeySource.EPHEMERAL))

        keys.kid shouldNotBe SigningKeys.from(JwtProperties(keySource = JwtProperties.KeySource.EPHEMERAL)).kid
        // Two keys generated above, two warnings: one per start, never fewer.
        val warnings = appender.list.filter { it.level == Level.WARN && it.formattedMessage.contains("EPHEMERAL") }
        warnings.size shouldBe 2
        warnings.first().formattedMessage shouldContain "dies with this process"
    }

    private fun configured(
        privatePem: String,
        publicPem: String,
    ) = JwtProperties(keySource = JwtProperties.KeySource.CONFIGURED, privateKeyPem = privatePem, publicKeyPem = publicPem)

    /** What `openssl` writes: armour, 64-column base64, armour. */
    private fun pem(
        kind: String,
        der: ByteArray,
    ): String = "-----BEGIN $kind-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $kind-----\n"
}
