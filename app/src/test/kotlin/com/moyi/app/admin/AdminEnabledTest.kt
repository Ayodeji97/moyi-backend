package com.moyi.app.admin

import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles

/**
 * The console switched on, as one context the admin tests share: allow-listed for
 * `10.9.0.0/16` only, with a real server beside `MockMvc` so that cookies are the
 * container's own, and a clock the tests can move.
 *
 * Loopback is a trusted proxy here so that the real-server requests can say which address
 * they come from through `X-Forwarded-For`, as kamal-proxy does in production. `MockMvc`
 * requests set `remoteAddr` to a non-proxy address, so their header is never believed.
 *
 * Media's object store answers a review copy's read with fixed bytes ([ReviewObjectStore]) and
 * refuses everything else, as the test profile's unavailable store does.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "moyi.admin.enabled=true",
        "moyi.admin.allowed-networks=10.9.0.0/16",
        "moyi.admin.totp-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "moyi.security.client-address.trusted-proxies=127.0.0.0/8,::1/128",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AdminEnabledTest.TimeConfiguration::class, ReviewObjectStore::class)
abstract class AdminEnabledTest : IntegrationTest() {
    @TestConfiguration
    class TimeConfiguration {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock()
    }

    companion object {
        const val ALLOWED = "10.9.1.1"
        const val REFUSED = "203.0.113.7"
    }
}
