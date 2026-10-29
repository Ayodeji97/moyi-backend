package com.moyi.media.infra.moderation

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * Which moderators are wired (ADR-0051), each from the environment as
 * `MOYI_MEDIA_MODERATION_…`. The posture is the storage's and email's
 * (ADR-0017 decision 3, ADR-0047): **photos default to the real moderator,
 * Rekognition, which refuses to start without its credentials**, and
 * production's `config/deploy.yml` names `none` until the owner supplies
 * them. `none` fails closed: nothing gets a verdict, so nothing reaches a
 * partner. It never passes anything.
 *
 * The known-image list and voice moderation default to `none` because no
 * vendor is wired for either yet (ADR-0051 decisions 3 and 5): each needs a
 * decision of the owner's first. `stand-in` (all three) decides by colour, for
 * the `local` profile and the smoke run only.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.media.moderation")
internal data class MediaModerationProperties(
    val image: ImageSettings = ImageSettings(),
    val knownImages: KnownImageSettings = KnownImageSettings(),
    val voice: VoiceSettings = VoiceSettings(),
    @field:Valid
    val rekognition: Rekognition = Rekognition(),
) {
    internal data class ImageSettings(
        val provider: ImageProvider = ImageProvider.REKOGNITION,
    )

    internal data class KnownImageSettings(
        val provider: KnownImageProvider = KnownImageProvider.NONE,
    )

    internal data class VoiceSettings(
        val provider: VoiceProvider = VoiceProvider.NONE,
    )

    internal enum class ImageProvider {
        /** AWS Rekognition `DetectModerationLabels` (Q1). Requires the key pair. The default. */
        REKOGNITION,

        /** No photo is moderated here, and so none reaches a partner. Asked for by name. */
        NONE,

        /** Decides by colour: `local` and the smoke run only, never production. */
        STAND_IN,
    }

    internal enum class KnownImageProvider {
        /** No list is consulted (no vendor is wired yet: ADR-0051 decision 3). */
        NONE,

        /** One made-up picture (all blue) is "known": `local` and the smoke run only. */
        STAND_IN,
    }

    internal enum class VoiceProvider {
        /** No voice note is moderated, and so none reaches a partner (no vendor wired yet: ADR-0051 decision 5). */
        NONE,

        /** Passes every voice note: `local` and the smoke run only. */
        STAND_IN,
    }

    /**
     * An IAM user's access key whose only permission is
     * `rekognition:DetectModerationLabels` (ADR-0051, what the owner sets up).
     */
    internal data class Rekognition(
        val accessKeyId: String? = null,
        val secretAccessKey: String? = null,
        /** eu-central-1, and only it: anything else is refused at start (Q1). Not a setting, but checked as one. */
        @field:NotBlank
        val region: String = RekognitionImageModerator.REGION,
        /** Only for a test's stand-in server on this machine (a loopback address); any other host is refused at start. */
        val endpoint: String? = null,
        val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
        /**
         * The whole call, retries included. With the fetch of the display
         * variant (the storage's 3 s) and its conversion
         * ([com.moyi.media.infra.processing.VipsImageProcessor.MODERATION_CONVERSION_TIMEOUT],
         * 10 s), 28 s of the delivery's 60 (ADR-0035), held with the delivery's
         * row lock and a pooled connection (ADR-0051 decision 1).
         */
        val apiCallTimeout: Duration = DEFAULT_API_CALL_TIMEOUT,
        val apiCallAttemptTimeout: Duration = DEFAULT_READ_TIMEOUT,
    ) {
        private companion object {
            val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
            val DEFAULT_READ_TIMEOUT: Duration = Duration.ofSeconds(7)
            val DEFAULT_API_CALL_TIMEOUT: Duration = Duration.ofSeconds(15)
        }

        /** The generated one would print the secret. */
        override fun toString(): String =
            "Rekognition(accessKeyId=$accessKeyId, secretAccessKey=${if (secretAccessKey == null) null else "<redacted>"}, " +
                "region=$region, endpoint=$endpoint, connectTimeout=$connectTimeout, readTimeout=$readTimeout, " +
                "apiCallTimeout=$apiCallTimeout, apiCallAttemptTimeout=$apiCallAttemptTimeout)"
    }
}
