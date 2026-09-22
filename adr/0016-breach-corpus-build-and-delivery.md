# ADR-0016 — How the breached-password corpus is built and delivered

**Status:** Accepted · **Date:** 2026-09-22

## Context

ADR-0012 lowered the password minimum from 12 to 8 and priced the change: the shorter floor is paid for by widening the breached-password check from a top-10k list to "an offline breached-password corpus of the top ~10M hashes, held as a Bloom filter (~17 MB at a 0.1% false-positive rate) **built in CI from a pinned HIBP dump and baked into the container image**". It ended: "the two halves are one decision and must not be unbundled", and estimated the corpus work at "half a session".

Three phrases in that sentence do not survive contact with the source, and ADR-0012 does not decide any of the questions they raise. None of this changes ADR-0012's position — it supplies the mechanism that position assumed existed.

**"The top ~10M hashes."** Have I Been Pwned does not publish a global ranking. It serves 1,048,576 prefix ranges, each sorted only within itself, so "the ten million most prevalent" cannot be taken without pulling all ~2.1 billion entries and sorting them. There is no cheap top-N.

**"A pinned HIBP dump."** There is no dump to pin. The downloadable corpus was retired in favour of the range API, and the official downloader tool simply walks all 1,048,576 prefixes — which is what any "dump" now is.

**"Baked into the container image."** The image is built by `bootBuildImage` from the Gradle output, so anything baked in has to be on a module's classpath at build time — which means an 18 MB binary has to reach the build from somewhere. ADR-0012 does not say where it is stored between the CI job that makes it and the build that packages it, and that is the decision with the most consequences.

## Decision

**1. A prevalence threshold replaces the top-N, and the number is measured.** Keep every SHA-1 appearing at least **600** times. The builder was run against 300 live ranges on 2026-09-22 and the result extrapolated:

| appears at least | ≈ corpus size |
|---|---|
| 550 times | 11.6M |
| **600 times** | **10.5M** |
| 650 times | 9.8M |
| 700 times | 9.0M |

Ten million falls between 600 and 650 and the tie goes **upward**: a larger corpus is more coverage of the attack ADR-0012 identifies as dominant, and the filter is sized from the count it actually collected, so erring high costs a few hundred kilobytes rather than the false-positive rate.

**2. The source is the range API, used in bulk at build time.** ADR-0012 rejected the k-anonymity API as a *runtime* dependency — a third party in the signup path, a US transfer to justify against NFR-050, and a fail-open/fail-closed choice when it is down. None of those apply to a build-time job whose only failure mode is a loud build failure. The same endpoint is the right source here and the wrong one at runtime, and that is not a contradiction.

**3. The filter is written here, with a versioned file format.** Not because Guava's is inadequate, but because the artefact is the *file*: written by CI, read by a service months later, it needs a magic number, a format version and the provenance of the corpus behind it, none of which Guava's `writeTo` carries. The input is also already a 160-bit SHA-1, so Guava's Murmur3 pass over it would be work with no benefit. Doc 25 D5's "no hand-written crypto, ever" is not engaged: the digest arrives already computed, and everything in `BloomFilter` is bit-mixing over it.

**4. The corpus is a pinned, checksummed build input — not a file in the repository and not a runtime fetch.** The CI job publishes it as a GitHub release asset under a dated `corpus/YYYY-MM-DD` tag; `gradle/breach-corpus.properties` pins the tag **and the SHA-256**; the build downloads it, verifies the digest, and fails if it does not match. A corpus swapped at the distribution point cannot quietly become the filter the service ships.

**5. A missing or unreadable corpus stops the application from starting.** Not a warning, not a disabled check. ADR-0012's whole argument for the offline filter is that it *removes* the fail-open/fail-closed question; a service that boots without its corpus reintroduces fail-open through the back door, and does it invisibly — every registration succeeds, the control simply is not there.

**6. It rebuilds quarterly and on demand**, never per push. ADR-0012's "revisit when" triggers at a year stale; quarterly is comfortably inside that. Per-push would pull ~70 GB from a free service for an output that does not change between commits.

## Consequences

**Positive:** the corpus is a dependency like any other — versioned, pinned, digest-verified, cached by Gradle after the first fetch. The build is reproducible: the same properties file produces the same image. And the tooling is out of the deployable, so the HTTP client that pulls 70 GB cannot reach the production classpath.

**Negative:** the build now has a network dependency on GitHub releases for a first fetch on a clean machine. That is a build-time outage path, not a runtime one, and it is the same class of dependency as Maven Central — which the build already cannot proceed without.

**Negative, and worth stating plainly:** ADR-0012's "call it half a session" was wrong by roughly a factor of four. The filter, the format, the builder, the retry policy, the workflow, the pin-and-verify step and the runtime loader are each small; there are just more of them than "download a list" suggests. The estimate is not corrected here to make it look better in hindsight — it is recorded because the same shape of underestimate is likely wherever a document describes an artefact without describing where it comes from.

**Neutral:** ~70 GB pulled from Have I Been Pwned four times a year. This is the access pattern HIBP's own downloader tool uses and Cloudflare fronts it, but the job identifies itself by `User-Agent` and disables response padding, which removes about a third of the transfer we would otherwise cost them for a secret we do not have.

## Alternatives considered

- **Commit the 18 MB filter to the repository.** Simplest by a distance: hermetic builds, no network, no release, no pinning machinery. Rejected because every refresh adds another 18 MB to history permanently, and because a binary blob in a public repository that exists to demonstrate the engineering is the wrong artefact to be demonstrating. The simplicity is real and this is the closest call in the ADR.
- **Publish it as a Maven artifact to GitHub Packages.** Elegant — the corpus becomes a line in the version catalog and Gradle handles the rest. Rejected because GitHub Packages requires a token even for public reads, which puts credentials in front of a public repository's build.
- **A binary fuse or XOR filter.** Smaller and faster than a Bloom filter for a static set that is never added to, which is exactly our case. Not adopted: ADR-0012 specifies a Bloom filter, and doc 25 is explicit that decisions are not renegotiated without an ADR being *worth* writing. This one would be a size optimisation on a 17 MB budget line that `10` §2.1 says is not worth a budget line.
- **Sizing the filter for 10M and accepting whatever the threshold produced.** Rejected: the false-positive rate would then be a consequence of how good the extrapolation was, rather than the number ADR-0012 chose.

## Revisit when

ADR-0012 is revisited; HIBP changes how the corpus is published; the download cost or duration becomes a problem; or the corpus is needed by a module other than `identity`, at which point `BloomFilter` has already been placed in `common:security` for it.
