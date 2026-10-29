package com.moyi.media.infra.moderation

import com.moyi.media.domain.Finding
import com.moyi.media.domain.ModerationCategory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The wiring (ADR-0051), as the storage's is held (ADR-0047): Rekognition is
 * the default and an environment without its key does not start; `none`,
 * which fails closed, has to be asked for by name; the stand-ins decide by
 * colour and are for local runs only.
 */
internal class MediaModerationConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(MediaModerationConfiguration::class.java)

    @Test
    fun `with no configuration at all, the application refuses to start and names the way out`() {
        runner.run { context ->
            context.startupFailure.shouldBeInstanceOf<Throwable>()
            val messages = generateSequence(context.startupFailure) { it.cause }.joinToString { it.message.orEmpty() }
            messages shouldContain "moyi.media.moderation.rekognition.access-key-id"
            messages shouldContain "none"
        }
    }

    @Test
    fun `with its key, Rekognition is wired, and the known-image list and voice moderation are none unless named`() {
        runner
            .withPropertyValues(
                "moyi.media.moderation.rekognition.access-key-id=AKIAFAKEFAKEFAKE0000",
                "moyi.media.moderation.rekognition.secret-access-key=fake-secret",
            ).run { context ->
                context.getBean(ImageModerator::class.java).shouldBeInstanceOf<RekognitionImageModerator>()
                context.getBean(KnownImageMatcher::class.java).shouldBeInstanceOf<NoKnownImageList>()
                context.getBean(VoiceModerator::class.java).shouldBeInstanceOf<NoVoiceModeration>()
            }
    }

    /** As config/deploy.yml writes it: lower case, and through the environment's names. */
    @Test
    fun `none, asked for by name, starts with no credentials and moderates nothing`() {
        runner
            .withPropertyValues(
                "moyi.media.moderation.image.provider=none",
                "moyi.media.moderation.known-images.provider=none",
                "moyi.media.moderation.voice.provider=none",
            ).run { context ->
                val image = context.getBean(ImageModerator::class.java)
                image.shouldBeInstanceOf<NoImageModeration>()
                image.moderatesHere shouldBe false
                context.getBean(VoiceModerator::class.java).moderatesHere shouldBe false
                context.getBean(KnownImageMatcher::class.java).matches(KnownImageCandidate(byteArrayOf(), "fake-sha")) shouldBe false
            }
    }

    @Test
    fun `the stand-ins decide by colour, and find nothing in anything else`() {
        runner
            .withPropertyValues(
                "moyi.media.moderation.image.provider=stand-in",
                "moyi.media.moderation.known-images.provider=stand-in",
                "moyi.media.moderation.voice.provider=stand-in",
            ).run { context ->
                val image = context.getBean(ImageModerator::class.java)
                val list = context.getBean(KnownImageMatcher::class.java)
                image.moderate(solid(Color(220, 20, 20))) shouldBe
                    Moderated.Found(listOf(Finding(ModerationCategory.EXTREME_VIOLENCE, 99.0)))
                image.moderate(solid(Color(220, 20, 220))) shouldBe Moderated.Found(listOf(Finding(ModerationCategory.ADULT_NUDITY, 99.0)))
                image.moderate(solid(Color(120, 160, 90))) shouldBe Moderated.Found(emptyList())
                image.moderate(byteArrayOf(1, 2, 3)) shouldBe Moderated.Refused("UNREADABLE")
                list.matches(KnownImageCandidate(solid(Color(20, 20, 220)), "fake-sha")) shouldBe true
                list.matches(KnownImageCandidate(solid(Color(220, 20, 20)), "fake-sha")) shouldBe false
                context.getBean(VoiceModerator::class.java).moderate(byteArrayOf(), 1_000) shouldBe Moderated.Found(emptyList())
            }
    }

    private fun solid(colour: Color): ByteArray {
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = colour
            fillRect(0, 0, 64, 48)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }
}
