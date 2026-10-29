# The media worker

The container that decodes what users upload (the owner's Q5; ADR-0048,
ADR-0049, ADR-0052). It is the `media-worker` role in `config/deploy.yml`: the
application's own image and jar, started with the `media-worker` profile
(`app/src/main/resources/application-media-worker.yml`) and
`MOYI_MEDIA_PROCESSING_PROVIDER=native`. It runs no web server, registers only
`media.process`, `media.moderate-image` and `media.moderate-voice`, and runs no
timed job but the outbox poller. The API (`web`) keeps every other consumer and
job, and processing `off`.

| | |
|---|---|
| Container | `moyi-media-worker-<version>` on `kamal`, no port, no proxy |
| Limits | 1 GB, 1.5 CPUs, JVM `-Xmx192m`, `MALLOC_ARENA_MAX=2` |
| File system | read-only; `/tmp` a 128 MB tmpfs (in memory, counted in the 1 GB) |
| User | `moyi` (uid 10001), no capability, `no-new-privileges` |
| Healthy when | `/tmp/moyi-ready` exists: written once the application has started (after libvips' and ffmpeg's start-up checks), removed as it stops. Kamal waits for `healthy` before calling a deploy done |
| Working when | `ops/monitoring/media-check.sh` finds no upload waiting `UPLOADED`, and nothing `PROCESSING`, for 30 minutes |
| Network | limited to Postgres and Cloudflare by `egress-limit.sh`, once installed (below) |

Useful: `kamal app logs -r media-worker -f`, `kamal app details -r media-worker`,
and on the server `docker inspect --format '{{.State.Health.Status}}' $(docker ps -q --filter label=role=media-worker)`.

## What it does while storage is `unavailable` (today)

It starts, passes its start-up checks, registers `media.process` for
`MediaUploaded`, and has nothing to do: the API refuses every upload intent with
`503 MEDIA_STORAGE_UNAVAILABLE`, so no upload exists. The moderation consumers
subscribe to nothing while moderation is `none`.

## Installing the network limit (once, before switch-on)

Kamal starts the role on its `kamal` network, which reaches the internet, and
neither Kamal nor Docker can narrow that (ADR-0052 decision 5). A systemd
service on the server does it instead, for every worker container as it starts,
from inside the container's network namespace: Postgres (TCP 5432 on the
`kamal` network) and HTTPS to Cloudflare's published ranges, where R2 is;
nothing else.

From the Mac, in `moyi-backend`:

```
scp ops/media-worker/egress-limit.sh moyi:/usr/local/sbin/moyi-media-worker-egress
scp ops/media-worker/moyi-media-worker-egress.service moyi:/etc/systemd/system/
ssh moyi 'chmod 700 /usr/local/sbin/moyi-media-worker-egress && systemctl daemon-reload && systemctl enable --now moyi-media-worker-egress'
ssh moyi 'journalctl -u moyi-media-worker-egress -n 5 --no-pager; /usr/local/sbin/moyi-media-worker-egress check && echo limited'
```

`media-check.sh` runs `check` on every run once the script is installed, and
alarms if a worker container runs without the limit (a worker started while the
service was down). What it was seen to do is `test-egress-limit.sh`'s to show
(Docker-in-Docker on the Mac; ten checks).

When Rekognition is switched on (ADR-0051, Owed), the worker must also reach
`rekognition.eu-central-1.amazonaws.com`: put AWS's `eu-central-1` ranges for
the `AMAZON` service (https://ip-ranges.amazonaws.com/ip-ranges.json) in
`/etc/moyi-media-worker/egress.env` as `EXTRA_HTTPS_CIDRS="…"` and
`systemctl restart moyi-media-worker-egress` (which re-limits running
containers). Without it every moderation call is refused, retried as an
outage, and `media-check.sh` alarms on unmoderated assets after 30 minutes.

## Switching storage on (the follow-up pull request)

Prerequisites, all the owner's (ADR-0047, Owed): the `moyi-media` bucket in R2's
EU jurisdiction (done), its lifecycle rule expiring `in/` after 2 days, an API
token scoped to that bucket with Object Read & Write (done), and D5 live in
production (it is: production runs `ce41f22`). The network limit installed and
seen limiting (above).

**1. Bitwarden**, secure note `moyi-prod`, three hidden fields. The two key
fields exist; the endpoint is added because it names the Cloudflare account,
which this public repository does not:

| Field | Value |
|---|---|
| `MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID` | the token's access key id |
| `MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY` | the token's secret access key |
| `MOYI_MEDIA_STORAGE_R2_ENDPOINT` | `https://<account id>.eu.r2.cloudflarestorage.com` |

**2. `.kamal/secrets`**: add the three names to the `kamal secrets fetch` line,
and three lines below it:

```diff
-SECRETS=$(kamal secrets fetch --adapter bitwarden --account $BITWARDEN_ACCOUNT --from moyi-prod POSTGRES_PASSWORD HASHING_SECRET JWT_PRIVATE_KEY_PEM JWT_PUBLIC_KEY_PEM RESEND_API_KEY GHCR_TOKEN)
+SECRETS=$(kamal secrets fetch --adapter bitwarden --account $BITWARDEN_ACCOUNT --from moyi-prod POSTGRES_PASSWORD HASHING_SECRET JWT_PRIVATE_KEY_PEM JWT_PUBLIC_KEY_PEM RESEND_API_KEY GHCR_TOKEN MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY MOYI_MEDIA_STORAGE_R2_ENDPOINT)
+
+# The R2 token scoped to the `moyi-media` bucket, Object Read & Write, and the
+# account's EU endpoint (ADR-0047, ADR-0052). Both roles use them: the API signs
+# the uploads, the worker reads and writes the objects.
+MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID=$(kamal secrets extract MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID $SECRETS)
+MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY=$(kamal secrets extract MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY $SECRETS)
+MOYI_MEDIA_STORAGE_R2_ENDPOINT=$(kamal secrets extract MOYI_MEDIA_STORAGE_R2_ENDPOINT $SECRETS)
```

**3. `config/deploy.yml`**, in the top-level `env` (both roles read it):

```diff
     # No media storage until …
-    MOYI_MEDIA_STORAGE_PROVIDER: unavailable
+    MOYI_MEDIA_STORAGE_R2_BUCKET: moyi-media
   secret:
     - SPRING_DATASOURCE_PASSWORD:POSTGRES_PASSWORD
     - MOYI_SECURITY_JWT_PRIVATE_KEY_PEM
     - MOYI_SECURITY_JWT_PUBLIC_KEY_PEM
     - MOYI_SECURITY_HASHING_SECRET
     - MOYI_NOTIFICATION_EMAIL_RESEND_API_KEY
+    - MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID
+    - MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY
+    - MOYI_MEDIA_STORAGE_R2_ENDPOINT
```

(and the comment above the removed line rewritten to say storage is R2.)
`MOYI_MEDIA_PROCESSING_PROVIDER` stays `"off"` at the top and `native` in the
worker's role; moderation stays `none`. `ProductionStartsWithoutMediaSecretsTest`
accepts exactly this shape (seen: the diff above applied, the test green; the
bucket line removed, red).

**4. Deploy** as always, after the PR merges and its CI run has published the
image: `git switch main && git pull --ff-only && kamal deploy --skip-push --version=$(git rev-parse HEAD)`.

**5. Verify**, in this order:

```
# both roles running, the worker healthy
kamal app details
ssh moyi 'docker ps --filter label=service=moyi --format "{{.Names}} {{.Status}}"'

# the worker started NATIVE, with storage, its three consumers, and nothing else
kamal app logs -r media-worker --since 10m | grep -E 'Media processing: NATIVE|Voice processing: NATIVE|Media storage: R2|limited to|Event consumer registered|Ready'

# the API kept processing off and has storage
kamal app logs -r web --since 10m | grep -E 'Media processing: OFF|Media storage: R2'

# the worker is network-limited
ssh moyi /usr/local/sbin/moyi-media-worker-egress check && echo limited
```

Then, from a verified test account (the app, or `scripts/moyi`): upload one
photo and one voice note and read `GET /api/v1/media/{id}` until `status` is
`READY` (seconds); fetch the photo's `url` once (ADR-0048, Owed: R2 serving a
GET signed up to an hour earlier); PUT altered bytes to a fresh intent's URL
and see R2 refuse them (ADR-0047 decision 1). On the server:

```
ssh moyi "docker exec moyi-postgres psql -U moyi -d moyi -c \"SELECT kind, status, count(*) FROM media_assets GROUP BY 1, 2\""
ssh moyi 'docker stats --no-stream $(docker ps -q --filter label=role=media-worker)'
ssh moyi /usr/local/sbin/moyi-media-check
```

**To switch off again**: put `MOYI_MEDIA_STORAGE_PROVIDER: unavailable` back and
deploy. Uploads are refused again; what was uploaded stays in the bucket, and
`media.purge`'s deliveries wait (ops/monitoring/media-pipeline-stuck.md).

## Memory

Measured 2026-10-29 on the image built from this repository (linux/arm64,
Docker on an Apple Silicon Mac, under the role's limits; ADR-0052 has the
table): the JVM resident at about 430-455 MiB whatever its heap cap (192m or
256m; NMT: heap ~160 MiB committed, metaspace ~110, code ~35, symbols ~30), and
the container's peak 686-751 MiB processing a 24-megapixel JPEG, a
24-megapixel HEIC and a voice note, of which ~100-120 MiB was reclaimable page
cache. A HEIC just under the 100 MiB decoded ceiling segfaulted under the
sandbox's 640 MiB address-space limit until glibc's malloc arenas were capped
(`MALLOC_ARENA_MAX=2`); with the cap, in a 1 GB container, it was `READY` and
the container peaked at 799 MiB. Hence 1 GB, not Q5's 768 MB (ADR-0052
decision 4).
