package com.moyi.media.service

import com.moyi.media.infra.moderation.ImageModerator
import com.moyi.media.infra.moderation.KnownImageMatcher
import com.moyi.media.infra.moderation.Moderated
import com.moyi.media.infra.moderation.VoiceModerator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Moderators a test steers: each answers what [FakeImageModerator.answer] (and
 * the others' equivalents) says, and remembers what it was given. In place of
 * the ones configuration chose (`none`, in this module's tests), so only the
 * contexts that import this moderate anything.
 */
@TestConfiguration
internal class FakeModerators {
    @Bean
    @Primary
    fun fakeImageModerator(): FakeImageModerator = FakeImageModerator()

    @Bean
    @Primary
    fun fakeKnownImages(): FakeKnownImages = FakeKnownImages()

    @Bean
    @Primary
    fun fakeVoiceModerator(): FakeVoiceModerator = FakeVoiceModerator()
}

internal class FakeImageModerator : ImageModerator {
    override val moderatesHere: Boolean = true
    override val name: String = "fake"

    @Volatile var answer: (ByteArray) -> Moderated = { Moderated.Found(emptyList()) }
    val seen = CopyOnWriteArrayList<ByteArray>()

    override fun moderate(jpeg: ByteArray): Moderated {
        seen += jpeg
        return answer(jpeg)
    }

    fun reset() {
        answer = { Moderated.Found(emptyList()) }
        seen.clear()
    }
}

internal class FakeKnownImages : KnownImageMatcher {
    override val name: String = "fake-list"

    @Volatile var answer: (ByteArray) -> Boolean = { false }

    override fun matches(jpeg: ByteArray): Boolean = answer(jpeg)

    fun reset() {
        answer = { false }
    }
}

internal class FakeVoiceModerator : VoiceModerator {
    override val moderatesHere: Boolean = true
    override val name: String = "fake-voice"

    @Volatile var answer: (ByteArray) -> Moderated = { Moderated.Found(emptyList()) }
    val seen = CopyOnWriteArrayList<ByteArray>()

    override fun moderate(
        ogg: ByteArray,
        durationMs: Int,
    ): Moderated {
        seen += ogg
        return answer(ogg)
    }

    fun reset() {
        answer = { Moderated.Found(emptyList()) }
        seen.clear()
    }
}
