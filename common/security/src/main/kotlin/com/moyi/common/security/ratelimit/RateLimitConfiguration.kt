package com.moyi.common.security.ratelimit

import com.moyi.common.security.PersonalDataHasher
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Metrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import java.time.Clock

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class RateLimitConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The registry is optional because Micrometer's autoconfiguration comes
     * with Actuator, which a module's own test context does not carry; the
     * global registry is Micrometer's own fallback and Boot's registry joins
     * it when there is one.
     */
    @Bean
    fun rateLimiter(
        properties: RateLimitProperties,
        connections: LettuceConnectionFactory,
        hasher: PersonalDataHasher,
        clock: Clock,
        meters: ObjectProvider<MeterRegistry>,
    ): RateLimiter {
        if (!properties.enabled) {
            log.warn("Rate limiting is DISABLED (moyi.security.rate-limit.enabled=false). Every bucket in doc 06 §4 is off.")
            return RateLimiter { _, _ -> RateLimitDecision.Unavailable }
        }
        return RedisRateLimiter(connections, hasher, clock, properties, meters.getIfAvailable { Metrics.globalRegistry })
    }
}
