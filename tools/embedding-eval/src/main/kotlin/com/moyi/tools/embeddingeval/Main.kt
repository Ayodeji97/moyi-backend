package com.moyi.tools.embeddingeval

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Embeds the golden set with every provider whose key is in the environment, and writes one
 * vectors file per provider beside it (Task 9, ADR-0054). Run by hand, from the repo root:
 *
 * ```
 * MOYI_AI_EMBEDDING_OPENAI_API_KEY=… MOYI_AI_EMBEDDING_VOYAGE_API_KEY=… \
 *   ./gradlew :tools:embedding-eval:run --args="generate"
 * ```
 *
 * It prints per provider only what it alone can measure: p95 latency and (estimated) tokens.
 * Recall, MRR and the threshold are `RetrievalEvalTest`'s printout on the files written here.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() != "generate") {
        System.err.println("usage: generate [golden.json]   (the default is $DEFAULT_GOLDEN)")
        exitProcess(2)
    }
    val golden = Path.of(args.getOrElse(1) { DEFAULT_GOLDEN })
    val texts = GoldenTexts.read(golden.readText())
    Providers.from(System.getenv()).use { providers ->
        if (providers.isEmpty()) {
            System.err.println("No provider key: set ${Providers.OPENAI_KEY} and/or ${Providers.VOYAGE_KEY}.")
            exitProcess(1)
        }
        providers.forEach { provider ->
            val space = checkNotNull(provider.port.space) { "${provider.name} has no space" }
            val run = EmbeddingRun.of(provider.port, texts)
            val written = VectorsFile.write(golden.toAbsolutePath().parent, space, run.vectors, Instant.now())
            val entries = texts.entries.size
            println(
                """
                |
                |${provider.name} ($space): ${run.vectors.size} vectors -> $written
                |  p95 latency, one query        ${"%.0f".format(EmbeddingRun.p95(run.queryMillis))} ms (${run.queryMillis.size} calls)
                |  p95 latency, a sweep batch    ${"%.0f".format(
                    EmbeddingRun.p95(run.batchMillis),
                )} ms (${run.batchMillis.size} calls of up to ${EmbeddingRun.SWEEP_BATCH})
                |  tokens, estimated (chars / 4) ${run.estimatedEntryTokens} for $entries entries (${run.estimatedEntryTokens * PER_THOUSAND / entries} per 1k entries), ${run.estimatedQueryTokens} for ${texts.queries.size} queries
                """.trimMargin(),
            )
        }
    }
}

private const val DEFAULT_GOLDEN = "modules/gratitude/src/test/resources/eval/golden.json"
private const val PER_THOUSAND = 1_000
