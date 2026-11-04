package com.moyi.gratitude.service

import java.util.UUID

/** Reciprocal Rank Fusion's constant (spec §3.6): large enough that agreement between rankings outweighs one list's top. */
internal const val RRF_K = 60

/**
 * [rankings] fused by **Reciprocal Rank Fusion** (ADR-0054, spec §3.6): each id scores the sum,
 * over the rankings that hold it, of `1 / (k + rank)`, rank counted from 1, and the ids are
 * returned by score, highest first. A repeat within one ranking is dropped before it is counted.
 *
 * **Ties are ordered by first appearance**, in ranking 0, then in ranking 1, and so on, and not
 * left to a sort's whim: two calls with the same rankings give the same order. One ranking alone
 * keeps its order. Pure: no I/O, nothing logged.
 */
internal fun fuse(
    rankings: List<List<UUID>>,
    k: Int = RRF_K,
): List<UUID> {
    // Insertion order is the tie rule: ranking 0's ids first, then those only a later ranking holds.
    val scores = LinkedHashMap<UUID, Double>()
    for (ranking in rankings) {
        ranking.distinct().forEachIndexed { index, id -> scores.merge(id, 1.0 / (k + index + 1), Double::plus) }
    }
    // A stable sort: equal scores stay in insertion order.
    return scores.entries.sortedByDescending { it.value }.map { it.key }
}
