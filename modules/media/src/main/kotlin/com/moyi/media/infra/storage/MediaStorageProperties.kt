package com.moyi.media.infra.storage

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Which object storage is wired, and how to reach it (ADR-0047). The posture is
 * email's and push's (ADR-0017 decision 3, ADR-0044 decision 10): **the default
 * is the real storage, and it refuses to start without its credentials.**
 *
 * [Provider.UNAVAILABLE] has to be asked for by name. Production's
 * `config/deploy.yml` asks, until the owner has made the `moyi-media` bucket and
 * its token, in a line that says so and how to switch; every upload is then
 * refused with `503 MEDIA_STORAGE_UNAVAILABLE`, and the rest of the API is
 * untouched. The `local` profile and the tests use [Provider.R2] against MinIO.
 *
 * Every value arrives from the environment, as `MOYI_MEDIA_STORAGE_…`.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.media.storage")
internal data class MediaStorageProperties(
    val provider: Provider = Provider.R2,
    @field:Valid
    val r2: R2 = R2(),
) {
    internal enum class Provider {
        /**
         * The S3 API at [R2.endpoint]: Cloudflare R2 in production, MinIO in the
         * tests and locally. Requires the endpoint, the bucket and the key pair.
         */
        R2,

        /** No storage: every upload is refused with 503. Never the default. */
        UNAVAILABLE,
    }

    /**
     * An R2 API token scoped to the one bucket, Object Read & Write, never the
     * backup token (spec §4 D1). R2 gives a token as an access key id and a
     * secret access key; the endpoint is
     * `https://<account id>.eu.r2.cloudflarestorage.com` for a bucket in the EU
     * jurisdiction.
     */
    internal data class R2(
        val endpoint: String? = null,
        val bucket: String? = null,
        val accessKeyId: String? = null,
        val secretAccessKey: String? = null,
        /** R2 accepts `auto`; the SDK insists on a region, and SigV4 signs it. MinIO accepts any. */
        @field:NotBlank
        val region: String = DEFAULT_REGION,
        /**
         * Short, because completion reads the object's length inside the
         * request's transaction, holding a pooled connection and the asset's
         * row lock while it waits (ADR-0047).
         */
        val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
    ) {
        /** The generated one would print the secret. */
        override fun toString(): String =
            "R2(endpoint=$endpoint, bucket=$bucket, accessKeyId=$accessKeyId, " +
                "secretAccessKey=${if (secretAccessKey == null) null else "<redacted>"}, region=$region, " +
                "connectTimeout=$connectTimeout, readTimeout=$readTimeout)"

        private companion object {
            const val DEFAULT_REGION = "auto"
            val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
            val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(5)
        }
    }
}
