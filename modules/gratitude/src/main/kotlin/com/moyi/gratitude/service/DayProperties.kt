package com.moyi.gratitude.service

import com.moyi.gratitude.domain.LateEntryGrace
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * How Bond-days end (BR-3a as amended 2026-11-08, ADR-0033 decision 15).
 *
 * [lateEntryGrace] is how long a day stays open after it ends: an entry whose `intendedAt` is
 * no older than this lands on the day it was written, and the close job settles the day only
 * once this has passed. Twelve hours by the owner's ruling. **The one place the length is
 * said**: every reader takes the [LateEntryGrace] bean below, never the property, so the
 * assignment rule and the close cannot be set apart ([LateEntryGrace] says why they must not).
 * The smoke run shortens nothing: it backdates rows instead.
 */
@ConfigurationProperties("moyi.gratitude.days")
internal data class DayProperties(
    val lateEntryGrace: Duration = Duration.ofHours(LATE_ENTRY_GRACE_HOURS),
) {
    private companion object {
        /** The owner's ruling, 2026-11-08. */
        const val LATE_ENTRY_GRACE_HOURS = 12L
    }
}

@Configuration
@EnableConfigurationProperties(DayProperties::class)
internal class DayConfiguration {
    @Bean
    fun lateEntryGrace(properties: DayProperties): LateEntryGrace = LateEntryGrace(properties.lateEntryGrace)
}
