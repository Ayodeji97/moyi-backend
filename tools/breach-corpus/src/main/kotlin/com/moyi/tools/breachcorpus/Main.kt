package com.moyi.tools.breachcorpus

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.system.exitProcess

/**
 * Command-line entry point for the corpus build (ADR-0012, ADR-0016).
 *
 * Run from CI by `.github/workflows/breach-corpus.yml`, and locally with a
 * `--ranges` limit for a smoke test:
 *
 * ```
 * ./gradlew :tools:breach-corpus:run --args="--output build/pwned.bloom --ranges 500"
 * ```
 *
 * This module is **not** a dependency of `app`. A build-time tool that pulls
 * ~100 GB over HTTP has no business on the runtime classpath, and keeping it
 * out is what makes that statement checkable rather than a convention.
 *
 * The SHA-256 printed at the end is the value that gets pinned in
 * `gradle/breach-corpus.properties`: the service build downloads the published
 * artefact and refuses it if the digest does not match, so a corpus swapped at
 * the distribution point cannot quietly become the one the filter is built
 * from.
 */
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    val arguments = Arguments(args)
    val options =
        BuildOptions(
            output = Path.of(arguments.require("--output")),
            minCount = arguments.int("--min-count", BuildOptions.DEFAULT_MIN_COUNT),
            falsePositiveRate = arguments.double("--fpr", BuildOptions.DEFAULT_FALSE_POSITIVE_RATE),
            concurrency = arguments.int("--concurrency", BuildOptions.DEFAULT_CONCURRENCY),
            prefixLimit = arguments.int("--ranges", PwnedRange.TOTAL_PREFIXES),
        )

    val report =
        runCatching { BreachCorpusBuilder(options).build() }
            .getOrElse { failure ->
                System.err.println("Corpus build failed: ${failure.message}")
                failure.printStackTrace()
                exitProcess(1)
            }

    println(
        """
        |
        |Corpus built: ${options.output.toAbsolutePath()}
        |  digests         ${report.insertedCount}
        |  entries scanned ${report.entriesScanned}
        |  malformed lines ${report.malformedLines}
        |  bits            ${report.bitCount} (k=${report.hashCount})
        |  file size       ${report.fileSizeBytes} bytes
        |  elapsed         ${report.elapsed}
        |  sha256          ${sha256(options.output)}
        """.trimMargin(),
    )
}

private fun sha256(path: Path): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(path.readBytes())
        .joinToString("") { "%02x".format(it) }
