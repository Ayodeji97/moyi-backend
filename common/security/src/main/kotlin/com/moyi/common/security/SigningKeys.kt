package com.moyi.common.security

import com.nimbusds.jose.jwk.RSAKey
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The RSA pair that signs and verifies access tokens, and its `kid`.
 *
 * **`kid` is the RFC 7638 thumbprint of the public key** — a base64url
 * SHA-256 over the key's canonical JSON members — rather than a name from
 * configuration. Two properties follow. It cannot drift from the key it
 * names, because it is derived from it. And rotation later is additive: a
 * second key gets a second thumbprint, the encoder stamps the new one, the
 * decoder can be handed both, and tokens signed by either verify until the
 * old one is withdrawn. A `kid` that somebody types is a `kid` that somebody
 * eventually forgets to change.
 *
 * Held as a Nimbus [RSAKey] because both the encoder's `JWKSource` and the
 * decoder's public key come out of it, so the two halves of the system
 * provably hold the same key.
 */
class SigningKeys(
    val rsaKey: RSAKey,
) {
    val kid: String get() = rsaKey.keyID
    val publicKey: RSAPublicKey get() = rsaKey.toRSAPublicKey()

    companion object {
        private val log = LoggerFactory.getLogger(SigningKeys::class.java)
        private const val RSA_KEY_BITS = 2048

        fun from(properties: JwtProperties): SigningKeys =
            when (properties.keySource) {
                JwtProperties.KeySource.CONFIGURED -> configured(properties)
                JwtProperties.KeySource.EPHEMERAL -> ephemeral()
            }

        private fun configured(properties: JwtProperties): SigningKeys {
            val privatePem = properties.privateKeyPem
            val publicPem = properties.publicKeyPem
            check(!privatePem.isNullOrBlank() && !publicPem.isNullOrBlank()) {
                "moyi.security.jwt.key-source is CONFIGURED (the default) but moyi.security.jwt.private-key-pem " +
                    "and/or moyi.security.jwt.public-key-pem is not set. Supply both PEMs through the environment " +
                    "(ADR-0019 has the two openssl commands), or set the key source to EPHEMERAL for local " +
                    "development. Refusing to start rather than sign tokens with a key nobody chose."
            }
            val keyFactory = KeyFactory.getInstance("RSA")
            val privateKey =
                try {
                    keyFactory.generatePrivate(PKCS8EncodedKeySpec(decodePem(privatePem, "PRIVATE KEY"))) as RSAPrivateKey
                } catch (unusable: InvalidKeySpecException) {
                    throw IllegalStateException(
                        "moyi.security.jwt.private-key-pem is not a PKCS#8 RSA private key (-----BEGIN PRIVATE KEY-----). " +
                            "A PKCS#1 key (-----BEGIN RSA PRIVATE KEY-----) needs converting: ADR-0019.",
                        unusable,
                    )
                }
            val publicKey =
                try {
                    keyFactory.generatePublic(X509EncodedKeySpec(decodePem(publicPem, "PUBLIC KEY"))) as RSAPublicKey
                } catch (unusable: InvalidKeySpecException) {
                    throw IllegalStateException("moyi.security.jwt.public-key-pem is not an X.509 RSA public key", unusable)
                }
            check(publicKey.modulus == privateKey.modulus) {
                "moyi.security.jwt.public-key-pem is not the public half of moyi.security.jwt.private-key-pem. " +
                    "Tokens would be signed with one key and verified with another, and every request would be a 401."
            }
            return build(publicKey, privateKey).also {
                log.info("JWT signing key loaded from configuration, kid {}", it.kid)
            }
        }

        private fun ephemeral(): SigningKeys {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_BITS) }.generateKeyPair()
            return build(pair.public as RSAPublicKey, pair.private as RSAPrivateKey).also {
                // WARN and not INFO, every start: this is the line that explains
                // why everyone was signed out after the last deploy, if this
                // ever runs anywhere it should not.
                log.warn(
                    "JWT signing key is EPHEMERAL (kid {}): generated at startup, so every access token dies with " +
                        "this process. Correct for local development; wrong anywhere else.",
                    it.kid,
                )
            }
        }

        private fun build(
            publicKey: RSAPublicKey,
            privateKey: RSAPrivateKey,
        ): SigningKeys {
            val withoutId = RSAKey.Builder(publicKey).privateKey(privateKey).build()
            // computeThumbprint() is RFC 7638: SHA-256 over {"e","kty","n"}.
            val key = RSAKey.Builder(withoutId).keyID(withoutId.computeThumbprint().toString()).build()
            return SigningKeys(key)
        }

        /**
         * Strips the armour and decodes. Tolerates the line breaks a PEM has
         * and the ones an environment variable may have lost; refuses a block
         * of the wrong kind so a public key in the private-key slot is a
         * message, not a cast failure.
         */
        private fun decodePem(
            pem: String,
            kind: String,
        ): ByteArray {
            val header = "-----BEGIN $kind-----"
            val footer = "-----END $kind-----"
            check(pem.contains(header) && pem.contains(footer)) {
                "Expected a PEM block delimited by $header / $footer"
            }
            val body =
                pem
                    .substringAfter(header)
                    .substringBefore(footer)
                    .replace(Regex("\\s"), "")
            return Base64.getDecoder().decode(body)
        }
    }
}
