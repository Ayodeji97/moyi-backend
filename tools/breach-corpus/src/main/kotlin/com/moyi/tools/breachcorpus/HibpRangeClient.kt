package com.moyi.tools.breachcorpus

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Where a range of breached hashes comes from.
 *
 * An interface with one implementation, which is usually a smell — here it is
 * the seam that lets the builder's own logic (the threshold, the spill file,
 * the refusal to finish on a partial corpus) be tested against a fake in
 * milliseconds instead of against a million HTTP requests. Doc 25 D7's rule
 * that every external provider sits behind our own type applies to a
 * build-time provider for the same reason it applies to a runtime one.
 */
internal fun interface RangeSource {
    fun fetch(prefix: String): String
}

/**
 * Fetches one Have I Been Pwned range, with the retry policy a 1,048,576-request
 * job needs.
 *
 * **Why the whole corpus is pulled rather than queried per password.** ADR-0012
 * rejected the k-anonymity range API as a *runtime* dependency — it would put a
 * third party in the signup path, add a US transfer to justify against NFR-050,
 * and force a fail-open/fail-closed choice when it is down. None of that
 * applies to a build-time job whose only failure mode is "the build fails
 * loudly and is re-run". The same endpoint is therefore exactly the right
 * source here and exactly the wrong one at runtime, and that is not a
 * contradiction.
 *
 * Retries are not optional at this scale: a 0.01% transient failure rate over a
 * million requests is a hundred silently-missing ranges, and a missing range is
 * ~10 breached passwords that the filter will wave through forever. So a
 * failure that survives [MAX_ATTEMPTS] aborts the run rather than being counted
 * and skipped — an incomplete corpus must not be able to produce a file that
 * looks finished.
 */
internal class HibpRangeClient(
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
    private val sleep: (Long) -> Unit = Thread::sleep,
) : RangeSource {
    /** @throws IOException when the range could not be fetched after [MAX_ATTEMPTS]. */
    override fun fetch(prefix: String): String {
        var lastFailure: Exception? = null

        for (attempt in 0 until MAX_ATTEMPTS) {
            when (val outcome = attemptOnce(prefix)) {
                is Outcome.Body -> {
                    return outcome.value
                }

                is Outcome.Retryable -> {
                    lastFailure = outcome.cause
                    if (attempt < MAX_ATTEMPTS - 1) sleep(BASE_BACKOFF_MILLIS shl attempt)
                }

                // A 4xx other than 429, or an interrupt, will not fix itself.
                // Stopping now keeps a wrong URL or a rejected User-Agent from
                // taking five backoffs to surface, which over a million ranges
                // is the difference between failing in seconds and failing in
                // an hour.
                is Outcome.Fatal -> {
                    lastFailure = outcome.cause
                    break
                }
            }
        }

        throw IOException("GET $prefix failed after at most $MAX_ATTEMPTS attempts", lastFailure)
    }

    /**
     * One request, classified rather than thrown. Keeping the decision about
     * *whether to keep trying* out of the code that performs the request is
     * what lets [fetch] have a single exit.
     */
    private fun attemptOnce(prefix: String): Outcome =
        try {
            val response = http.send(request(prefix), HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            when {
                status == HTTP_OK -> Outcome.Body(response.body())
                status in RETRYABLE_STATUSES -> Outcome.Retryable(IOException("HTTP $status"))
                else -> Outcome.Fatal(IOException("HTTP $status is not retryable"))
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.Fatal(IOException("interrupted", interrupted))
        } catch (failure: IOException) {
            Outcome.Retryable(failure)
        }

    private sealed interface Outcome {
        data class Body(
            val value: String,
        ) : Outcome

        data class Retryable(
            val cause: Exception,
        ) : Outcome

        data class Fatal(
            val cause: Exception,
        ) : Outcome
    }

    private fun request(prefix: String): HttpRequest =
        HttpRequest
            .newBuilder(URI.create("$BASE_URL/$prefix"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            // HIBP pads responses to a uniform length by default so that an
            // observer of the TLS stream cannot infer which range was asked
            // for. That protects the *caller's* password, and we are not
            // asking about one — we are asking about all of them. Turning it
            // off removes roughly a third of ~100 GB of transfer from a job
            // that has no secret to keep.
            .header("Add-Padding", "false")
            // HIBP asks bulk consumers to identify themselves. A job that pulls
            // the whole corpus should be attributable to something a human can
            // contact, not anonymous traffic.
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

    companion object {
        const val BASE_URL = "https://api.pwnedpasswords.com/range"
        const val USER_AGENT = "moyi-backend-breach-corpus (+https://github.com/Ayodeji97/moyi-backend)"

        private const val HTTP_OK = 200
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val RETRYABLE_STATUSES = setOf(HTTP_TOO_MANY_REQUESTS, 500, 502, 503, 504)

        private const val MAX_ATTEMPTS = 5
        private const val BASE_BACKOFF_MILLIS = 500L
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val REQUEST_TIMEOUT_SECONDS = 30L
    }
}
