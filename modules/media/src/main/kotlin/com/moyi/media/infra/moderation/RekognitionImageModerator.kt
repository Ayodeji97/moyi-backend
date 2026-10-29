package com.moyi.media.infra.moderation

import com.moyi.media.domain.Finding
import com.moyi.media.domain.ModerationCategory
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rekognition.RekognitionClient
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsRequest
import software.amazon.awssdk.services.rekognition.model.Image
import software.amazon.awssdk.services.rekognition.model.ImageTooLargeException
import software.amazon.awssdk.services.rekognition.model.InvalidImageFormatException
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException
import software.amazon.awssdk.services.rekognition.model.ModerationLabel
import software.amazon.awssdk.services.rekognition.model.RekognitionException

/**
 * AWS Rekognition `DetectModerationLabels` in eu-central-1 (the owner's ruling
 * Q1), the one class that knows the vendor (doc 25:118). It is given the
 * photo's display variant as a JPEG (Rekognition reads JPEG and PNG only, not
 * WebP) and nothing else: no id, no key, no user.
 *
 * **What it switches on** (Q16: "exactly the vendor categories these need"),
 * read from moderation labels version 7. Every other label (weapons alone,
 * drugs, alcohol, gestures, gambling, hate symbols, swimwear, kissing, self-harm,
 * explosions, crashes) is not read, and not kept:
 *
 * - [ModerationCategory.ADULT_NUDITY]: the top-level `Explicit` and everything
 *   under it (explicit nudity, sexual activity, sex toys). Not the
 *   non-explicit nudity labels (a bare back, a partly exposed buttock, a
 *   kiss): nothing there needs a blur.
 * - [ModerationCategory.EXTREME_VIOLENCE]: `Weapon Violence`, `Physical
 *   Violence`, `Blood & Gore` (under `Graphic Violence`) and `Corpses` (under
 *   `Death and Emaciation`); and `Graphic Violence` itself when none of its
 *   five kinds came back with it (AWS: the third level is not always
 *   returned). Not `Self-Harm` or `Explosions and Blasts` alone: neither is
 *   Q16's "real violence, gore".
 * - [ModerationCategory.CSAM]: **nothing.** Rekognition does not detect it
 *   ("the image and video moderation APIs don't detect whether an image
 *   includes illegal content, such as CSAM", AWS's moderation page), and has
 *   no age signal. Q16's classifier half is therefore not met by the ruled
 *   vendor (ADR-0051, open question 1); the known-image list
 *   ([KnownImageMatcher]) is the only CSAM check.
 *
 * **What is an outage** ([ModeratorUnavailableException], retried by the
 * outbox): throttling, provisioned throughput, Rekognition's own 5xx, a
 * timeout or a network failure, and anything else that is not the bytes'
 * fault (a refused credential, a missing permission), so that a
 * misconfiguration holds photos back, visibly, rather than quarantining every
 * one of them. **What is a refusal** ([Moderated.Refused]): the image's format,
 * its size, a parameter, all of which the same bytes would meet again.
 *
 * Logs the error's class and status only: never the image, never AWS's message.
 */
internal class RekognitionImageModerator(
    private val client: RekognitionClient,
) : ImageModerator {
    private val log = LoggerFactory.getLogger(javaClass)

    override val moderatesHere: Boolean = true
    override val name: String = "rekognition"

    @Suppress("ReturnCount") // One answer per kind of refusal, each where its exception is caught.
    override fun moderate(jpeg: ByteArray): Moderated {
        val request =
            DetectModerationLabelsRequest
                .builder()
                .image(Image.builder().bytes(SdkBytes.fromByteArray(jpeg)).build())
                .minConfidence(MIN_CONFIDENCE)
                .build()
        val labels =
            try {
                client.detectModerationLabels(request).moderationLabels()
            } catch (refused: InvalidImageFormatException) {
                return refusal(refused)
            } catch (refused: ImageTooLargeException) {
                return refusal(refused)
            } catch (refused: InvalidParameterException) {
                return refusal(refused)
            } catch (outage: RekognitionException) {
                log.warn("Rekognition answered {} ({}): retried later", outage.statusCode(), outage.javaClass.simpleName)
                throw ModeratorUnavailableException("Rekognition answered ${outage.statusCode()}", outage)
            } catch (outage: SdkClientException) {
                log.warn("Rekognition could not be reached ({}): retried later", outage.javaClass.simpleName)
                throw ModeratorUnavailableException("Rekognition could not be reached", outage)
            }
        return Moderated.Found(findingsOf(labels))
    }

    private fun refusal(refused: RekognitionException): Moderated.Refused {
        log.warn("Rekognition refused the image ({})", refused.javaClass.simpleName)
        return Moderated.Refused(refused.javaClass.simpleName)
    }

    internal companion object {
        /** Rekognition's own default, and the lowest threshold the policy reads (`ModerationPolicy`). */
        const val MIN_CONFIDENCE = 50f

        /** The default region of the owner's ruling Q1. */
        const val REGION = "eu-central-1"

        private const val EXPLICIT = "Explicit"
        private const val GRAPHIC_VIOLENCE = "Graphic Violence"
        private val VIOLENT = setOf("Weapon Violence", "Physical Violence", "Blood & Gore", "Corpses")

        /** Rekognition's labels in this system's categories; every other label is dropped here. */
        fun findingsOf(labels: List<ModerationLabel>): List<Finding> {
            val nudity =
                labels
                    .filter { it.name() == EXPLICIT || it.parentName() == EXPLICIT || it.isUnder(EXPLICIT, labels) }
                    .map { Finding(ModerationCategory.ADULT_NUDITY, it.confidence().toDouble()) }
            val graphicKinds = labels.filter { it.parentName() == GRAPHIC_VIOLENCE }
            val violence =
                labels
                    .filter { it.name() in VIOLENT || (it.name() == GRAPHIC_VIOLENCE && graphicKinds.isEmpty()) }
                    .map { Finding(ModerationCategory.EXTREME_VIOLENCE, it.confidence().toDouble()) }
            return nudity + violence
        }

        /** Whether [this] is a third-level label whose parent's parent is [top]. */
        private fun ModerationLabel.isUnder(
            top: String,
            labels: List<ModerationLabel>,
        ): Boolean = labels.any { it.name() == parentName() && it.parentName() == top }

        /**
         * The client, or a failed start: the default provider is the real one,
         * and it refuses to start without its credentials (ADR-0017 decision 3,
         * #96's lesson). Production names `none` until the owner supplies them.
         */
        fun create(properties: MediaModerationProperties.Rekognition): RekognitionImageModerator {
            val keyId = properties.accessKeyId
            val secret = properties.secretAccessKey
            require(!keyId.isNullOrBlank() && !secret.isNullOrBlank()) {
                "Photo moderation is Rekognition (the default) but moyi.media.moderation.rekognition.access-key-id and " +
                    "secret-access-key are not both set. Set them (MOYI_MEDIA_MODERATION_REKOGNITION_…), or set " +
                    "moyi.media.moderation.image.provider to none, which moderates nothing and shows no partner any photo (ADR-0051)."
            }
            val client =
                RekognitionClient
                    .builder()
                    .region(Region.of(properties.region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret)))
                    .httpClientBuilder(
                        UrlConnectionHttpClient
                            .builder()
                            .connectionTimeout(properties.connectTimeout)
                            .socketTimeout(properties.readTimeout),
                    ).overrideConfiguration {
                        it
                            .apiCallTimeout(properties.apiCallTimeout)
                            .apiCallAttemptTimeout(properties.apiCallAttemptTimeout)
                            .retryStrategy(RetryMode.STANDARD)
                    }.apply { properties.endpoint?.let { endpointOverride(java.net.URI.create(it)) } }
                    .build()
            return RekognitionImageModerator(client)
        }
    }
}
