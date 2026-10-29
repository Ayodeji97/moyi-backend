package com.moyi.media.infra.moderation

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chooses the three moderators from configuration: one `@Bean` each with an
 * exhaustive `when`, as the storage's and the processors' configurations do
 * and for their reason (ADR-0017 decision 3): a provider added and not wired
 * is a compile error.
 */
@Configuration
@EnableConfigurationProperties(MediaModerationProperties::class)
internal class MediaModerationConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun imageModerator(properties: MediaModerationProperties): ImageModerator =
        when (properties.image.provider) {
            MediaModerationProperties.ImageProvider.REKOGNITION -> {
                log.info("Photo moderation: Rekognition ({})", properties.rekognition.region)
                RekognitionImageModerator.create(properties.rekognition)
            }

            MediaModerationProperties.ImageProvider.NONE -> {
                log.warn("Photo moderation: NONE. No photo gets a verdict, so no partner is shown one (ADR-0051).")
                NoImageModeration()
            }

            MediaModerationProperties.ImageProvider.STAND_IN -> {
                log.warn("Photo moderation: STAND-IN, deciding by colour. Never in production (ADR-0051).")
                StandInImageModerator()
            }
        }

    @Bean
    fun knownImageMatcher(properties: MediaModerationProperties): KnownImageMatcher =
        when (properties.knownImages.provider) {
            MediaModerationProperties.KnownImageProvider.NONE -> {
                log.warn("Known-image (CSAM hash) list: NONE consulted. Owed to the owner's ruling Q16 (ADR-0051).")
                NoKnownImageList()
            }

            MediaModerationProperties.KnownImageProvider.STAND_IN -> {
                log.warn("Known-image list: STAND-IN, one made-up picture. Never in production (ADR-0051).")
                StandInKnownImageList()
            }
        }

    @Bean
    fun voiceModerator(properties: MediaModerationProperties): VoiceModerator =
        when (properties.voice.provider) {
            MediaModerationProperties.VoiceProvider.NONE -> {
                log.warn("Voice moderation: NONE. No voice note gets a verdict, so no partner hears one (ADR-0049, ADR-0051).")
                NoVoiceModeration()
            }

            MediaModerationProperties.VoiceProvider.STAND_IN -> {
                log.warn("Voice moderation: STAND-IN, passing every voice note. Never in production (ADR-0051).")
                StandInVoiceModerator()
            }
        }
}
