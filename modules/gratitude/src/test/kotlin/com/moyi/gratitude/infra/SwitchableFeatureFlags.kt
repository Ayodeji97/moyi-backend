package com.moyi.gratitude.infra

import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags
import java.util.concurrent.ConcurrentHashMap

/**
 * `admin`'s flags, which this context does not boot: every flag on, as a table with no rows is,
 * until a test switches one [off], as an `ADMIN` would. [reset] turns them all on again, for an
 * `@AfterEach`.
 */
internal class SwitchableFeatureFlags : FeatureFlags {
    private val switchedOff = ConcurrentHashMap.newKeySet<FeatureFlag>()

    override fun isOn(flag: FeatureFlag): Boolean = flag !in switchedOff

    fun off(flag: FeatureFlag) {
        switchedOff += flag
    }

    fun reset() {
        switchedOff.clear()
    }
}
