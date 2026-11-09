package com.moyi.identity.infra.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Duration

internal class RefreshPropertiesTest {
    @Test
    fun `the grace window is thirty seconds unless configured`() {
        RefreshProperties(null).graceWindow shouldBe Duration.ofSeconds(30)
        RefreshProperties(Duration.ofSeconds(10)).graceWindow shouldBe Duration.ofSeconds(10)
        RefreshProperties(Duration.ZERO).graceWindow shouldBe Duration.ZERO
    }

    @Test
    fun `a negative or overlong window is a failed start, naming the property`() {
        shouldThrow<IllegalArgumentException> { RefreshProperties(Duration.ofSeconds(-1)) }.message shouldContain
            "moyi.identity.refresh.grace-window"
        shouldThrow<IllegalArgumentException> { RefreshProperties(Duration.ofMinutes(3)) }.message shouldContain
            "moyi.identity.refresh.grace-window"
    }
}
