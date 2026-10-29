# ADR-0052 — The media worker: one image from a Dockerfile, a second Kamal role, no web server, its own consumers, and a network limited from the host

**Status:** Accepted · **Date:** 2026-10-29 · **Deciders:** Daniel

## Context

ADR-0048 and ADR-0049 built the photo and voice pipelines and kept them off in production's API
(`MOYI_MEDIA_PROCESSING_PROVIDER: "off"`), because the owner ruled (Q5) that uploads are decoded in a
worker container of their own: JRE 25, libvips and ffmpeg, the same jar, 768 MB, 1.5 CPUs. Both left
the worker owed, with these conditions:

- **A Kamal role, not an accessory** (the owner's ruling, 2026-10-29): a role runs the application's
  own image with its own command and options and is redeployed with it; an accessory runs a fixed
  image that is not.
- `MOYI_MEDIA_PROCESSING_PROVIDER=native`, the web server and every consumer but its own turned off,
  so that the worker claims only its own deliveries.
- Non-root, read-only root, `--memory=768m --cpus=1.5`, the JVM capped (`-Xmx256m` proposed) and its
  resident memory measured against ADR-0048 §3's arithmetic.
- Its network limited to R2 and Postgres (spec R-E), marked **[inferred feasible], not checked**.
- **Gate (ADR-0049):** not deployed before D5's purge is live in production. It is: production runs
  `ce41f22`, which carries #110.

What had to be decided:

1. **How one image carries libvips and ffmpeg.** A Kamal role runs its service's one image
   (`Kamal::Commands::App#run` passes `config.absolute_image` for every role; there is no per-role
   image). The image was Spring Boot's buildpack image (`bootBuildImage`, Paketo's
   `builder-noble-java-tiny`), whose run image has no shell, no libvips and no ffmpeg, and no
   supported way to add packages.
2. **How the same jar becomes a worker**: no web server, which consumers, which timed jobs.
3. **How anyone knows it is alive**, with no HTTP route for kamal-proxy or an actuator.
4. **The memory**: the JVM's cap, measured.
5. **The network limit**: what Kamal 2.12 and Docker on one host can do.
6. **What `config/deploy.yml` says now**, keeping production safe: storage stays `unavailable`,
   moderation `none`, and the switch-on is a mechanical follow-up.

## Decision

**1. One image, built from `app/Dockerfile`, for both roles.** Ubuntu 24.04 (pinned by the digest of
its multi-arch index), Eclipse Temurin's JRE 25.0.5+8 downloaded from Adoptium's release and checked
against its published SHA-256 per architecture, Ubuntu's `libvips-tools`, `libheif-plugin-libde265`
and `ffmpeg` (`--no-install-recommends`: libvips 8.15.1, ffmpeg 6.1.1 with libopus, exactly what CI's
tests install), the boot jar split into Spring Boot's layers, user `moyi` (uid 10001), and the
`service=moyi` label Kamal checks. CI builds it natively on an amd64 and an arm64 runner, as it built
the buildpack image, and before any push proves on each: the platform, the label, a non-root user,
Java 25, `heifload`, a HEIC from the media module's tests decoded, and ffmpeg's `libopus` encoder.

- **The JRE is pinned in the Dockerfile, not taken from `eclipse-temurin:25-jre-noble`**: on
  2026-10-29 that tag was still 25.0.4.1, one security update behind the 25.0.5 the buildpack image
  ran, and Ubuntu's own `openjdk-25-jre-headless` was 25.0.4.1 too. A newer JRE is a reviewed change
  to four lines.
- **The API's JVM is sized by the buildpack calculator's formula, with more metaspace.** The
  calculator gives the heap what is left of the container's memory once the rest is budgeted:
  `Xmx = total − MaxDirectMemorySize − MaxMetaspaceSize − ReservedCodeCacheSize − threads × Xss`,
  with metaspace estimated as `classes × 5,800 bytes + 14,000,000` and no headroom. For
  production's image (`ce41f22`, read from it on 2026-10-29: 29,533 classes, 250 threads) that is
  metaspace 180,948K and `-Xmx1404203K` (2,097,152K − 10,240K − 180,948K − 245,760K − 256,000K).
  **Corrected (review of #116):** the first version of this ADR copied the calculator's numbers
  from an older local image (26,525 classes: `-XX:MaxMetaspaceSize=163911K`, `-Xmx1421240K`) and
  called them production's. Under them the reviewer measured this image's API using 155 MB of a
  168 MB cap and still rising; a JVM at its metaspace cap throws `OutOfMemoryError`, and
  `-XX:+ExitOnOutOfMemoryError` ends it. Now: **`-XX:MaxMetaspaceSize=256m` and `-Xmx1292m`**,
  the heap lowered by exactly what metaspace gained, so the total is the calculator's 2 GB:
  2,097,152K − 10,240K − 262,144K − 245,760K − 256,000K = 1,323,008K = 1,292 MiB. The other flags
  (`-XX:MaxDirectMemorySize=10M -XX:ReservedCodeCacheSize=240M -Xss1M -XX:+ExitOnOutOfMemoryError`)
  are the calculator's. **Measured** (this image as the `web` role, deploy.yml's JVM flags plus NMT,
  driven by the whole of `scripts/smoke.sh` with the 5-second close, reminder and held-push crons,
  then stopped): **33,897 classes loaded; metaspace used 158.7 MiB (metadata 136.9 + class space
  21.8), committed 160.4 MiB, against the cap of 256 MiB (63%)**. Against the old cap of 163,911K
  (160.1 MiB) that is already at the limit; against production's present 180,948K, 91%. Not
  exercised in that run: the hourly reaper, the nightly demotion and the quarter-hourly on-this-day
  job, which did not fall due. `ProductionStartsWithoutMediaSecretsTest` now holds every role to a
  metaspace cap, the API's to at least 256 MiB, and the API's whole JVM budget to its container
  (seen red with 163911K, and with the heap left at 1,404,203K: 2,127 MiB in 2,048). The image's
  own default (`-XX:MaxRAMPercentage=50`) only applies to a `docker run` by hand.
- **The API container now carries libvips and ffmpeg it never runs**, and "the image has neither"
  no longer backs up the `"off"` line: NATIVE, the default, would start in the API and decode on its
  outbox thread. The line therefore moved under one guard more: it is in the top-level `env`, which
  every role inherits, the worker's role alone overrides it, and
  `ProductionStartsWithoutMediaSecretsTest` now checks each role's merged environment (seen red with
  `native` added to the `web` role).

Measured: **image size**, linux/arm64, 397 MB compressed (the registry's size, what a pull moves) and
1.36 GB unpacked, against 105 MB and 552 MB for the buildpack image production runs. By layer: the
apt packages 524 MB (libvips pulls ImageMagick, Poppler, OpenEXR and more as dependencies, which
`VIPS_BLOCK_UNTRUSTED` keeps from ever reading an upload), the JRE 199 MB, the jar's dependencies
107 MB, the application 21 MB. **Layer reuse needs the build cache** (corrected, review of #116):
the first version said a release that changes only the application's classes moves only the last
layer, but every fresh CI runner ran the apt install again and wrote it with new file times, so a new
digest, and the server would have pulled all ~400 MB at every release. CI now builds with buildx and
GitHub's cache (`type=gha`, one scope per architecture, `mode=max`): an unchanged step is taken from
the cache with its layer and digest. What was verified is in the pull request (the layer digests
printed by two runs of the same branch); main's cache is main's own, so the first release after
merge builds cold once. **Build time**, the `docker build` from the jar, no cache: on CI's native runners (#116's first
run) **29 s for linux/amd64 and 36 s for linux/arm64**, the whole `image` job 2 min 11 s and 2 min 16 s
with `bootJar` (about 80 s) and the checks; 219 s for linux/arm64 on an Apple Silicon Mac through
Docker Desktop's VM.

**2. The `media-worker` profile makes the jar the worker** (`application-media-worker.yml`, held by
`MediaWorkerProfileTest`, each line seen red when removed or changed):

- `spring.main.web-application-type: none`: no Tomcat, no API route, no `/healthz`, no actuator
  port. Nothing in the container listens (seen: the only listening socket in its namespace is
  Docker's DNS resolver at 127.0.0.11).
- `spring.main.keep-alive: true`. **Found by running the image, not by a test:** without a web server
  every thread left is a daemon (the outbox poller's, and with virtual threads the scheduler's), and
  the JVM exited 0 one second after "Started", interrupting the first deliveries it had claimed (they
  were retried; nothing was lost).
- `moyi.events.registration.only: [media.process, media.moderate-image, media.moderate-voice]`, a
  new property of `common.events` (`RegistrationProperties.only`): the worker registers and claims
  those and no other; empty, the default, means every consumer, as the API runs. A name that matches
  no consumer refuses the start, so a typo cannot leave a worker that claims nothing
  (`ConsumerRegistryTest`, both seen red with the filter removed). `media.purge` is not the worker's:
  it needs only the storage, and the API has it.
- Every timed job off with Spring's disabled cron (`"-"`): close, reminders, held pushes, on this
  day, the reaper, the demotion, and the idempotency-key reaper (made configurable for this). The
  outbox poller stays. A job added later runs in the worker too until listed: the test that asserts
  the poller is the one scheduled task fails then.
- `moyi.ready-file: /tmp/moyi-ready` (decision 3).

**3. Alive and working are two questions.** *Alive*: `ReadyFile` writes `/tmp/moyi-ready` on
`ApplicationReadyEvent`, after the native start-up checks in the sandbox and the consumers'
registration, and removes it as the context closes. The role's Docker health check is
`test -f /tmp/moyi-ready` (start period 120 s, polled every 2 s while starting). For a role without
a proxy Kamal 2.12 polls `docker inspect`'s health status and accepts only `healthy` once a health
check exists (`Kamal::Cli::Healthcheck::Poller`), so a worker that would refuse to start fails the
deploy instead of being reported up. *Working*: `ops/monitoring/media-check.sh` now also fails on an
upload `UPLOADED` for 30 minutes (nothing took it up), beside its `PROCESSING` check. Docker never
restarts an unhealthy container by itself; a JVM that dies is restarted by the role's
`unless-stopped` policy, and its delivery retried.

**4. The memory, measured, and the container raised to 1 GB** (linux/arm64, the image, under the
role's limits, on Docker Desktop's VM of 2 CPUs; a 24-megapixel JPEG of 8.1 MiB, a 24-megapixel HEIC
of 8.8 MiB and a 20-second AAC voice note uploaded through the API with processing off, then the
worker started):

| Run | Container | JVM cap | JVM resident (VmHWM) | Container peak (`memory.peak`) | Page cache at the end |
|---|---|---|---|---|---|
| 1 | 768m | `-Xmx256m` | 454 MiB | 686 MiB | 90 MiB |
| 2 | 768m | `-Xmx192m` | 458 MiB | 751 MiB | 114 MiB |
| 3 | 768m | `-Xmx256m`, with NMT | 441 MiB | 696 MiB | 98 MiB |
| 4 | 768m | `-Xmx192m`, + the ceiling HEIC | 437 MiB | 748 MiB | 99 MiB; the ceiling HEIC `FAILED` |
| 5 | 768m | `-Xmx256m`, + the ceiling HEIC | 434 MiB | 768 MiB (at the limit, reclaiming) | 97 MiB; the ceiling HEIC `FAILED` |
| 6 | **1g** | `-Xmx192m`, `MALLOC_ARENA_MAX=2`, + the ceiling HEIC | 439 MiB | **799 MiB** | 94 MiB; **all four `READY`** |

NMT at the end of run 3: heap 155 MiB committed, metaspace 108 MiB, code 34 MiB, symbols 28 MiB,
class space and the rest ~40 MiB: 375 MiB committed, about 430 MiB resident. So **the JVM is ~440
MiB resident, not the ~400 ADR-0048 estimated, and the cap barely moves it**: the heap stays near
160 MiB committed under either cap. `-Xmx192m` is chosen over the proposed 256m: the same resident
memory as measured, and 64 MiB less the heap can ever grow beside a decode. The page cache is the
image's own files, read once, and the kernel reclaims it first.

**The ceiling HEIC, and why it failed.** A HEIC of 5900 × 5900 (99.6 MiB decoded, just under
`MAX_DECODED_BYTES`) of fine texture at a high bitrate was refused `UNREADABLE` in runs 4 and 5:
`vips thumbnail` **segfaulted under the sandbox's 640 MiB address-space limit** (exit 139) with
~300-400 MiB resident. `ulimit -v` counts address space reserved, not memory used, and glibc's
malloc reserves 64 MiB of it for each arena it makes, one per thread that allocates while others
hold the lock: libheif's and libde265's decoding threads, and the arenas' count is bounded only by
eight per CPU. With **`MALLOC_ARENA_MAX=2`** (now in both of `VipsImageProcessor`'s environments,
since the sandbox clears the environment, and in the worker role's own) the same file decodes under
the same 640 MiB limit, at 345 MiB resident. The 640 MiB limit and `MAX_DECODED_BYTES` are
unchanged. `VipsImageProcessorTest` makes such a HEIC and decodes it through the processor under
640 MiB: green with the cap, red without it (`UNREADABLE`), on linux/arm64; a gentler file (fine
noise at sigma 6, quality 60) decoded either way, so the test's file is made harder on purpose. It
runs on Linux only (elsewhere nothing is limited) and needs libheif's x265 plugin to write the
HEIC, which CI installs for the tests and the image does not carry. Run 6 is the end-to-end proof:
all four uploads `READY` in a 1 GB container, peak 799 MiB, `memory.events` `max` 0.

**1 GB, not Q5's 768 MB** (the review of #116's ruling). The arithmetic at 768 MB did not hold: the
JVM's ~440 MiB, a decode at the ceiling of up to ~350 MiB, and the tmpfs's work files (the upload
and the decoded intermediate, ~30 MiB) is ~820 MiB. At 1 GB the measured peak with the ceiling HEIC
was 799 MiB including ~94 MiB of reclaimable page cache. The box has room: 2 GB for the API (4 GB
for the moment of a deploy), 2 GB for Postgres, 384 MB for Valkey and 1 GB (2 GB during a deploy)
for the worker, limits not usage, on 8 GB. **Trimming the worker's JVM is owed** (below): it loads
much it never uses (the breach-corpus filter, ~17 MB of heap; the identity, bond and gratitude
modules' beans).

**5. The network limit is put in from the host, because Kamal and Docker cannot express it.**
What was checked, in Kamal 2.12's source and on Docker 29:

- Kamal starts every role's container with `--network kamal` (written into
  `Kamal::Commands::App#run`); a role's `options` can add flags but cannot remove that one. The
  `kamal` network is a user-defined bridge with NAT to the internet, and Docker has no per-container
  egress rule on a bridge. An `--internal` network would cut the internet entirely, and the worker
  needs R2; it cannot be the worker's only network under Kamal anyway.
- Host firewall rules keyed on the worker's address (`DOCKER-USER`) would need a fixed address:
  `--ip` needs a network created with a subnet, which production's `kamal` network was not, and two
  worker containers overlap during a deploy. Keyed on a dynamic address, a stale rule could later
  apply to whichever container inherits the address.

So `ops/media-worker/egress-limit.sh`, run by `moyi-media-worker-egress.service` on the server,
watches Docker for a container labelled `service=moyi`, `role=media-worker` starting and puts
iptables rules **inside that container's network namespace** (`nsenter --net` with the host's
iptables): loopback (Docker's DNS), TCP 5432 to the `kamal` network's subnet (Postgres; a subnet,
not an address, because the accessory's address changes when it is rebooted), TCP 443 to
Cloudflare's published IPv4 ranges (R2's EU endpoint resolved into 141.101.64.0/18), and
`EXTRA_HTTPS_CIDRS`; everything else, and all IPv6 but loopback, rejected. The container has no
capability, so nothing in it can undo them, and they die with it. The rules go in with one
`iptables-restore --noflush`: the chain is declared, filled and jumped to in one commit, so a rule
that fails applies nothing and the apply exits non-zero instead of saying "limited"; and a container
that shares the host's network namespace is refused, never touched (review of #116). The service is
wanted by `docker.service` as well, so a Docker started later starts it. `media-check.sh` runs its
`check` and alarms on a worker running without the limit. `ops/media-worker/test-egress-limit.sh`
proves it in Docker-in-Docker (a Linux kernel and a Docker daemon of its own): thirteen checks pass;
with the two `REJECT` rules removed five went red; with a failed restore ignored, with the jump added
unconditionally, and with the host-namespace refusal removed, the check for each went red.

**What it is not**: R2 has no range of its own, so the limit admits HTTPS to every site behind
Cloudflare, which a subverted decoder could use to send what it read; it stops the rest of the
internet, Valkey, the API's containers, kamal-proxy and the host's other ports. It fails open where
it is not installed or the service is down, which is why the alarm exists. It is not run on the
server by this pull request; installing it is an owner's step (`ops/media-worker/README.md`).

**6. `config/deploy.yml` deploys the worker now, harmlessly.** The `media-worker` role is present:
1g, 1.5 CPUs, `read-only`, `tmpfs /tmp` (128 MB, `noexec,nosuid,nodev`), `cap-drop ALL`,
`no-new-privileges`, the health check, the profile, `native`, the JVM's cap. With storage
`unavailable` no upload can exist, so it starts, passes its checks and has nothing to do; seen, with
the deploy file's environment as Ruby's YAML reads it and throwaway secrets: healthy in 11 s,
`media.process` registered, nothing listening, the root file system read-only, uid 10001. The `web`
role started from the same image with the deploy file's environment and no media secrets: `/healthz`
UP in 8 s, processing OFF, storage UNAVAILABLE. Deploying it now proves the worker boots on the
production host before storage is switched on, and the gate is met. Storage, moderation and the
switch-on are unchanged here; `ops/media-worker/README.md` has the follow-up as a diff of
`config/deploy.yml` and `.kamal/secrets`, with the verification. The R2 endpoint is to be a secret
there, since it names the Cloudflare account and this repository is public;
`ProductionStartsWithoutMediaSecretsTest` accepts it as one (seen: the follow-up's diff applied, green;
its bucket line removed, red).

**Rolling back across this release** (review of #116). Kamal 2.12's `rollback` first checks that a
container of the target version exists for every role it acts on (`Kamal::Cli::Main#container_available?`
iterates `KAMAL.roles_on(host)`, and `roles` is narrowed by `--roles`, `Kamal::Commander::Specifics`);
no `media-worker` container exists for a version before this one, so `kamal rollback <older sha>`
refuses. The rollback past it is `kamal rollback <sha> --roles=web`: the API goes back, and the worker
stays on the newer image, harmless while storage is `unavailable`. Written in `config/deploy.yml`'s
header and the README.

**`builder.context` in `config/deploy.yml`** is the jar's directory. It is unused (every deploy is
`--skip-push`), and it would break a Kamal build from a clean clone of the commit, which is Kamal's
default without a context and holds no jar; with it Kamal builds from the working tree. Said there.

## Consequences

**Positive.** Uploads are decoded in a container that can neither write its own file system nor
gain a privilege, outside the process that serves the API; the API's outbox thread never waits on a
photo. The worker is redeployed with every release, from the same commit. A worker that cannot start
fails the deploy. Production can switch storage on with a five-line pull request. The image now runs
the same JRE vendor and native programs on the server as CI's tests do on Ubuntu 24.04.

**Negative.** The image is about four times larger, for both roles. The API's container carries
programs it must never start; one line and one test stand between it and decoding in the API. The
worker's memory has ~225 MiB of headroom at the ceiling (decision 4: 799 of 1,024 MiB). The worker has no metrics
endpoint: the pipeline's meters (`gratitude.media.processing.duration`, the outbox gauges for its
consumers) are kept in a process nothing can scrape; its health is the ready file and
`media-check.sh`'s database queries. The worker's container receives the same secrets as the API's
(Kamal's top-level `env.secret` reaches every role, and a role cannot drop one): the JWT key and the
email key it never uses. A subverted decoder runs as the same uid as the JVM and could read its
environment; it already reaches the database, which is worth more. The network limit is outside
Kamal's configuration and fails open. During a deploy, both roles' old and new containers can
overlap: 2 × 2 GB + 2 × 1 GB of limits beside Postgres's 2 GB and Valkey's 384 MB on an 8 GB box,
limits not usage, 8.4 GB of them for the minute both overlap (the API's resident memory is far below its limit). The switch from Liberica (the
buildpack's JRE) to Temurin moves Argon2's timing a little: the reviewer of #116 measured t=6
medians of 43.9 and 45.0 ms on Liberica and 45.9 and 47.5 ms on Temurin, same machine, 2 GB, two
runs each: a few percent, inside the spread the deploy file records, so no retune
(`scripts/argon2-bench` now names this image's paths; re-run here on 2 CPUs: 45.4 ms). The number
that decides is the server's, after the first deploy. The smoke script reads the processing log
lines and meters from the process it is attached to, so against the two roles split (the API from
the image, the worker beside it) five of its probes fail by design (seen: 1197 passed, 5 failed, the
five being those log and scrape probes); it is run against one process with processing on.

**Neutral.** `bootBuildImage` and its pinned Paketo builder are gone from the build. The API logs
"subscriptions this build does not declare" for `media.process` on every start: the worker's, kept
by design (runbook). `common.events`
gains `moyi.events.registration.only` and `OutboxDispatcher.consumerIds()` (this process's
consumers; the backlog reads every consumer the database knows); scheduling gains `moyi.scheduling.idempotency-keys.cron`. No
migration, no API change, no state-map row.

## Alternatives considered

- **Keep the buildpack image and add the packages to it.** The tiny run image has no `/bin/sh`, which
  the sandbox needs for `ulimit`, and Paketo offers no supported apt layer; an image extension
  (Dockerfile extensions to the lifecycle) is experimental and would still need a fuller run image.
  More machinery than a Dockerfile, for less control.
- **The worker as an accessory with an image of its own.** Ruled out by the owner: an accessory is not
  redeployed with the application, so its jar would drift from the API's.
- **Two images, one per role.** Kamal 2.12 has no per-role image.
- **`eclipse-temurin:25-jre-noble` as the base.** A security update behind on the day (decision 1).
  Ubuntu's OpenJDK package the same.
- **A web server in the worker bound to loopback, for `/actuator/health` and metrics.** Every API
  route would be live inside the decoding container, and Tomcat and the MVC beans would cost heap
  the container cannot spare. The ready file answers what Kamal needs.
- **Turning consumers off by a property on each consumer.** Seven properties, and the next consumer
  added would run in the worker by default. One allow-list in the worker's profile names what it runs.
- **The network limit by `DOCKER-USER` rules on the host, by address** (decision 5), **or by an HTTP
  proxy the JVM is told to use**: a proxy setting binds only well-behaved clients, not a decoder.
- **A smaller JVM cap (`-Xmx128m`).** Not measured as needed: resident memory is dominated by
  metaspace and code, and the heap's committed size did not reach 192m.

## Owed

- **The owner, before switching storage on:** install the egress limit and see `check` pass
  (`ops/media-worker/README.md`); then the follow-up pull request as written there.
- **Trimming the worker's JVM** (review of #116, owed, not this pull request): it starts every
  module's beans and loads the breach-corpus filter it never consults; ~440 MiB resident is most of
  its 1 GB. A profile that leaves them out, measured.
- **The ceiling on the server itself**: the at-ceiling HEIC decoded under 640 MiB with the arenas
  capped on this Mac's VM (2 CPUs) and on CI; the CAX21 has 4. Re-run the upload of run 6 there when
  storage is on.
- **After the first deploy of this image:** `scripts/argon2-bench` on the server (JRE vendor change);
  the worker's resident memory read with `docker stats` there.
- **When Rekognition is switched on:** AWS's eu-central-1 ranges in the egress limit's
  `EXTRA_HTTPS_CIDRS` (ADR-0051, Owed).
- **A metrics path for the worker** if Prometheus is ever deployed: a scrape over a unix socket or a
  push, not a port.

## Revisit when

Kamal lets a role choose its network or its image. R2 publishes ranges of its own. The worker is
seen to run short of memory, or the client's downscale ships. Ubuntu's or Adoptium's JRE overtakes
the pinned one.
