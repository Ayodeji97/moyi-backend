package com.moyi.common.testing

import com.moyi.common.core.IdGenerator
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * An [IdGenerator] that counts instead of guessing, so a test can name the
 * id it expects rather than match "some UUID".
 *
 * The ids it hands out are legible on sight and still structurally valid —
 * `00000000-0000-7000-8000-000000000001` for the first [timeOrdered] and
 * `00000000-0000-4000-8000-000000000001` for the first [opaque] — so a
 * failure message says which generator produced the id and in what order,
 * which a random UUID never does.
 *
 * Deliberately *not* a mock. A mock would assert that the code called the
 * generator; this asserts what the code did with the result, which is the
 * thing that can actually be wrong.
 */
class DeterministicIdGenerator : IdGenerator {
    private val timeOrderedCount = AtomicLong()
    private val opaqueCount = AtomicLong()

    /** Every id handed out, in order, for tests that assert on what was created. */
    val issued: List<UUID> get() = issuedIds.toList()
    private val issuedIds = java.util.Collections.synchronizedList(mutableListOf<UUID>())

    override fun timeOrdered(): UUID = issue(UUID(VERSION_7_BITS, VARIANT_BITS or timeOrderedCount.incrementAndGet()))

    override fun opaque(): UUID = issue(UUID(VERSION_4_BITS, VARIANT_BITS or opaqueCount.incrementAndGet()))

    private fun issue(id: UUID): UUID = id.also { issuedIds.add(it) }

    private companion object {
        /** Version nibble 0b0111 in bits 48..51, everything else zero. */
        const val VERSION_7_BITS = 0x0000_0000_0000_7000L

        /** Version nibble 0b0100. */
        const val VERSION_4_BITS = 0x0000_0000_0000_4000L

        /** RFC 9562 variant 0b10, leaving the low 62 bits for the counter. */
        const val VARIANT_BITS = Long.MIN_VALUE
    }
}
