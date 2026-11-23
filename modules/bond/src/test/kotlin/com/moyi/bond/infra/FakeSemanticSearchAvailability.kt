package com.moyi.bond.infra

import com.moyi.bond.api.SemanticSearchAvailability

/**
 * `gratitude`'s answer to [SemanticSearchAvailability], which this module's context does not boot:
 * a switch a test turns, as an `ADMIN` turns `AI_EMBEDDINGS` or a deploy names a provider. On by
 * default, so a bond with its setting on reads as searched by meaning unless a test says otherwise;
 * [reset] puts it back, for an `@AfterEach`.
 */
internal class FakeSemanticSearchAvailability : SemanticSearchAvailability {
    @Volatile
    var available: Boolean = true

    override fun isAvailable(): Boolean = available

    fun reset() {
        available = true
    }
}
