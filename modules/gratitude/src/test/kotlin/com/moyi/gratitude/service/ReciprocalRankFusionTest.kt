package com.moyi.gratitude.service

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** [fuse]: Reciprocal Rank Fusion with k = 60 (ADR-0054, spec §3.6), and its explicit tie rule. */
internal class ReciprocalRankFusionTest {
    @Test
    fun `an item high in both lists beats one top in a single list`() {
        val (x, y, z) = List(3) { UUID.randomUUID() }
        // x = 1/61, y = 1/62 + 1/61, z = 1/62
        fuse(listOf(listOf(x, y), listOf(y, z))) shouldBe listOf(y, x, z)
    }

    @Test
    fun `equal scores keep the order of the first list`() {
        val (a, b, c) = List(3) { UUID.randomUUID() }
        // a = 1/61 + 1/62 = b; c = 1/63
        fuse(listOf(listOf(a, b, c), listOf(b, a))) shouldBe listOf(a, b, c)
    }

    @Test
    fun `an item only the second list holds is ordered after the first list's, at an equal score`() {
        val (a, b) = List(2) { UUID.randomUUID() }
        // a = 1/61 = b: a tie, broken by list 0 before list 1.
        fuse(listOf(listOf(a), listOf(b))) shouldBe listOf(a, b)
        fuse(listOf(listOf(b), listOf(a))) shouldBe listOf(b, a)
    }

    @Test
    fun `one list alone keeps its order`() {
        val ids = List(5) { UUID.randomUUID() }
        fuse(listOf(ids)) shouldBe ids
    }

    @Test
    fun `empty lists fuse to nothing`() {
        fuse(listOf(emptyList(), emptyList())) shouldBe emptyList()
    }

    @Test
    fun `k is 60 by default, and it decides the order`() {
        val (a, b, c) = List(3) { UUID.randomUUID() }
        val d = UUID.randomUUID()
        val rankings = listOf(listOf(a, b), listOf(c, d, b))
        // k = 60: b = 1/62 + 1/63, about 0.0320; a = c = 1/61, a first by list 0; d = 1/62.
        fuse(rankings) shouldBe listOf(b, a, c, d)
        // k = 0: a = c = 1; b = 1/2 + 1/3; d = 1/2. Not vacuous: a smaller k ranks one list's top above agreement.
        fuse(rankings, k = 0) shouldBe listOf(a, c, b, d)
    }
}
