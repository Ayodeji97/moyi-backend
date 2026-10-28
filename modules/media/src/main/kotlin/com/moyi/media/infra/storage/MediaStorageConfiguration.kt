package com.moyi.media.infra.storage

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Chooses the [ObjectStore] from configuration: one `@Bean` with an exhaustive
 * `when`, as email's and push's configurations do and for their reason
 * (ADR-0017 decision 3): a provider added and not wired is a compile error.
 */
@Configuration
@EnableConfigurationProperties(MediaStorageProperties::class)
internal class MediaStorageConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun objectStore(properties: MediaStorageProperties): ObjectStore =
        when (properties.provider) {
            MediaStorageProperties.Provider.R2 -> {
                // The bucket is ours, not a person's: naming it at start-up is how
                // a misconfigured environment is seen in the first lines of its log.
                log.info("Media storage: R2 (bucket: {})", properties.r2.bucket ?: "<unset>")
                R2ObjectStore.create(properties.r2)
            }

            MediaStorageProperties.Provider.UNAVAILABLE -> {
                log.warn("Media storage: UNAVAILABLE. Every upload is refused with 503 until a bucket is configured (ADR-0047).")
                UnavailableObjectStore()
            }
        }
}
