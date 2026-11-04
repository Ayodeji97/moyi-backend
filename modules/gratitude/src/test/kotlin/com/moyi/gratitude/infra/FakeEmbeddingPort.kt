package com.moyi.gratitude.infra

import com.moyi.ai.api.EmbeddingPort
import com.moyi.ai.api.EmbeddingSpace
import com.moyi.ai.api.EmbeddingUnavailableException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The one test [EmbeddingPort] in `gratitude` (ADR-0054): no provider is reached from a test.
 *
 * It records every text it was handed, and whether a transaction was open on the calling thread
 * when it was ([calledInTransaction]): the sweep must call a provider with none open. Otherwise it
 * answers a deterministic unit vector seeded from the text's hash, or the exact vector [table]
 * holds for that text (Tasks 7-9). [failNext] makes the next call fail as a provider would,
 * [failOn] makes every call fail that carries one of its texts (an input the provider refuses),
 * [delay] makes each call slow, and [during] runs once inside the next call, after it has
 * recorded: the moment between the sweep's read and its write. [clear] resets it all.
 */
internal class FakeEmbeddingPort : EmbeddingPort {
    @Volatile
    override var space: EmbeddingSpace? = DEFAULT_SPACE

    val texts: MutableList<String> = CopyOnWriteArrayList()

    /** One per call: whether the calling thread had a transaction open. */
    val calledInTransaction: MutableList<Boolean> = CopyOnWriteArrayList()

    @Volatile
    var failNext = false

    @Volatile
    var failOn: Set<String> = emptySet()

    @Volatile
    var delay: Duration = Duration.ZERO

    @Volatile
    var during: (() -> Unit)? = null

    @Volatile
    var table: Map<String, FloatArray> = emptyMap()

    override fun embed(texts: List<String>): List<FloatArray> {
        val space = space ?: throw EmbeddingUnavailableException("no provider")
        this.texts += texts
        calledInTransaction += TransactionSynchronizationManager.isActualTransactionActive()
        during?.also { during = null }?.invoke()
        if (!delay.isZero) Thread.sleep(delay)
        if (failNext || texts.any { it in failOn }) {
            failNext = false
            throw EmbeddingUnavailableException("provider error")
        }
        return texts.map { table[it] ?: unitVector(it, space.dimensions) }
    }

    fun clear() {
        space = DEFAULT_SPACE
        texts.clear()
        calledInTransaction.clear()
        failNext = false
        failOn = emptySet()
        delay = Duration.ZERO
        during = null
        table = emptyMap()
    }

    private fun unitVector(
        text: String,
        dimensions: Int,
    ): FloatArray {
        val random = Random(text.hashCode())
        val raw = FloatArray(dimensions) { random.nextFloat() - 0.5f }
        val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dimensions) { raw[it] / norm }
    }

    companion object {
        val DEFAULT_SPACE = EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL
    }
}
