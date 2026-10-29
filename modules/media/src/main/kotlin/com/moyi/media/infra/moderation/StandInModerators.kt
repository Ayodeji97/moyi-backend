package com.moyi.media.infra.moderation

import com.moyi.media.domain.Finding
import com.moyi.media.domain.ModerationCategory
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * The moderators the `local` profile and the smoke run select
 * (`provider: stand-in`), so that every verdict can be reached on the wire
 * without a vendor (spec §4 D4: "the fake is selected by profile, as LOG is
 * for email"). **Never in production**: `config/deploy.yml` names `none`, and
 * `ProductionStartsWithoutMediaSecretsTest` holds it to that.
 *
 * They decide by colour, so that a test or the smoke run chooses a verdict by
 * the photo it uploads, and nothing else about a photo matters: a photo almost
 * all **red** is extreme violence, almost all **magenta** is adult nudity;
 * anything else is found to be nothing. Plain made-up pictures, plainly fake.
 */
internal class StandInImageModerator : ImageModerator {
    override val moderatesHere: Boolean = true
    override val name: String = "stand-in"

    override fun moderate(jpeg: ByteArray): Moderated {
        val colour = Colour.mean(jpeg) ?: return Moderated.Refused("UNREADABLE")
        return Moderated.Found(
            when {
                colour.isRed -> listOf(Finding(ModerationCategory.EXTREME_VIOLENCE, SURE))
                colour.isMagenta -> listOf(Finding(ModerationCategory.ADULT_NUDITY, SURE))
                else -> emptyList()
            },
        )
    }
}

/** A known-image list of one made-up picture: a photo almost all **blue** matches it. Never in production. */
internal class StandInKnownImageList : KnownImageMatcher {
    override val name: String = "stand-in"

    override fun matches(candidate: KnownImageCandidate): Boolean = Colour.mean(candidate.jpeg)?.isBlue == true
}

/** Finds nothing in any voice note: every one passes. Never in production, where `none` passes nothing. */
internal class StandInVoiceModerator : VoiceModerator {
    override val moderatesHere: Boolean = true
    override val name: String = "stand-in"

    override fun moderate(
        ogg: ByteArray,
        durationMs: Int,
    ): Moderated = Moderated.Found(emptyList())
}

private const val SURE = 99.0

/** The mean colour of a picture, each channel 0 to 255. */
private data class Colour(
    val red: Int,
    val green: Int,
    val blue: Int,
) {
    val isRed: Boolean get() = red >= HIGH && green <= LOW && blue <= LOW
    val isMagenta: Boolean get() = red >= HIGH && green <= LOW && blue >= HIGH
    val isBlue: Boolean get() = red <= LOW && green <= LOW && blue >= HIGH

    companion object {
        private const val HIGH = 180
        private const val LOW = 80
        private const val BYTE = 0xFF
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8

        /** Null when the bytes are no picture ImageIO reads. */
        fun mean(bytes: ByteArray): Colour? {
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            var red = 0L
            var green = 0L
            var blue = 0L
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    red += (rgb shr RED_SHIFT) and BYTE
                    green += (rgb shr GREEN_SHIFT) and BYTE
                    blue += rgb and BYTE
                }
            }
            val pixels = image.width.toLong() * image.height
            return Colour((red / pixels).toInt(), (green / pixels).toInt(), (blue / pixels).toInt())
        }
    }
}
