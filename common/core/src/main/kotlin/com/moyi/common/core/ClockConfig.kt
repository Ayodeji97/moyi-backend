package com.moyi.common.core

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The one place [Clock.systemUTC] is allowed to appear (doc 18 §3: no bare
 * `Instant.now()` outside the injected Clock). Everywhere else, inject
 * [Clock] and call `clock.instant()` — this is what makes time
 * controllable in tests.
 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
