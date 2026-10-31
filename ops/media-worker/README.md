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

## What it does

Since storage was switched on (ADR-0052, "Switched on"): the API accepts an
upload intent, signs the PUT straight to R2's `moyi-media`, and on the
completion publishes `MediaUploaded`; the worker claims it, reads the upload
from `in/`, writes what it makes of it under `v/` (a photo's two WebP variants,
a voice note's Ogg Opus) and deletes the upload. The moderation consumers
subscribe to nothing while moderation is `none`, so **no photo or voice note
gets a verdict, and no partner is ever shown one**: an author sees their own
media, their partner sees none of it, until the owner decides moderation
(ADR-0051).

Before that, with storage `unavailable`, it started, passed its start-up
checks and had nothing to do: the API refused every upload intent with
`503 MEDIA_STORAGE_UNAVAILABLE`.

## Switching storage on: the owner's runbook

Every step is the owner's, in this order, and each says what it should show.
None is run by the pull request that wrote it. `ssh moyi` is the server
(`root@91.98.71.136`, key `~/.ssh/moyi_hetzner`); everything else runs on the
Mac, in `moyi-backend`.

Already done: the `moyi-media` bucket in R2's EU jurisdiction, its lifecycle
rule deleting `in/` after 2 days, no public URL, no CORS; the token
`moyi-media-prod` (Object Read & Write, `moyi-media` only); D5 live in
production (since `ce41f22`); the worker deployed and healthy (`e88944b`).

### Step 1. Bitwarden: nothing to add, three fields to check

The deploy reads three fields of the secure note `moyi-prod`
(`.kamal/secrets`):

| Field | Holds | Becomes |
|---|---|---|
| `MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID` | the token's access key id (saved) | `MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID` |
| `MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY` | the token's secret access key (saved) | `MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY` |
| `R2_ENDPOINT` | `https://<account id>.eu.r2.cloudflarestorage.com`, **already there**: the backups read it (`ops/backup/README.md`) | `MOYI_MEDIA_STORAGE_R2_ENDPOINT` |

The endpoint is a secret because it names the Cloudflare account, which this
public repository does not. It is the backups' field rather than a new one:
one account, one jurisdiction, one value, kept in one place. **No new field is
needed.** (Had one been added as `MOYI_MEDIA_STORAGE_R2_ENDPOINT`, as an
earlier draft of this runbook said, nothing would read it.)

Check the three without printing any, and that the token reaches the bucket
(`200`; `403` is a wrong key or secret, `404` a wrong endpoint or jurisdiction):

```
export BW_SESSION="$(bw unlock --raw)"; bw sync >/dev/null
f() { bw get item moyi-prod | python3 -c 'import json,sys; print(next(x["value"] for x in json.load(sys.stdin)["fields"] if x["name"]==sys.argv[1]))' "$1"; }
f R2_ENDPOINT | grep -qE '^https://[0-9a-f]{32}\.eu\.r2\.cloudflarestorage\.com$' && echo "endpoint: EU, well formed" || echo "endpoint: NOT the EU form; stop here"
[ -n "$(f MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID)" ] && [ -n "$(f MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY)" ] && echo "token: both halves" || echo "token: a half is MISSING; stop here"
printf 'user = "%s:%s"\n' "$(f MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID)" "$(f MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY)" |
  curl -s -o /dev/null -w 'bucket: %{http_code}\n' -K - --aws-sigv4 "aws:amz:auto:s3" "$(f R2_ENDPOINT)/moyi-media?list-type=2&max-keys=1"
bw lock; unset BW_SESSION
```

(The `curl` takes the token on its standard input, never its command line.
Seen answering 200, 403 and 404 as described against MinIO; not yet run
against R2.)

### Step 2. The worker's network limit (ADR-0052's gate: before storage is on)

Kamal starts the role on its `kamal` network, which reaches the internet, and
neither Kamal nor Docker can narrow that (ADR-0052 decision 5). A systemd
service on the server does it instead, for every worker container as it
starts, from inside the container's network namespace: Postgres (TCP 5432 on
the `kamal` network) and HTTPS to Cloudflare's published ranges, where R2 is;
nothing else. What it was seen to do is `test-egress-limit.sh`'s to show
(Docker-in-Docker on the Mac, Docker 28 and 29), and `test-egress-watch.sh`'s
on the Mac's own Docker 29 (colima's Ubuntu 24.04): a worker started after the
watch began is limited by it. **Storage must not be switched on before this
step has printed `limited`**: a worker that decodes uploads with the whole
internet in reach is what ADR-0052 decision 5 exists to prevent. The deploy
enforces it: `.kamal/hooks/pre-deploy` asks the worker's host (`ssh -o
BatchMode=yes`, so the key must be unlocked in the agent) whether the
service is active and `check` passes, and refuses the deploy, naming this
step, when it is not or the server cannot be asked.

```
# 2a. what it needs is on the server (Ubuntu 24.04 has all three)
ssh moyi 'command -v nsenter iptables-restore ip6tables-restore'

# 2b. install, and start it now and with Docker from now on
scp ops/media-worker/egress-limit.sh moyi:/usr/local/sbin/moyi-media-worker-egress
scp ops/media-worker/moyi-media-worker-egress.service moyi:/etc/systemd/system/
ssh moyi 'chmod 700 /usr/local/sbin/moyi-media-worker-egress && systemctl daemon-reload && systemctl enable --now moyi-media-worker-egress'
# If an earlier install crash-looped ("docker events ended" in its journal: the
# script before this fix read the events with a format Docker 29 refuses),
# restart it after copying the fixed script, so the running watch is the new one:
ssh moyi 'systemctl restart moyi-media-worker-egress'

# 2c. it is running, it stays running, and it limited the worker that is running now
ssh moyi 'systemctl is-active moyi-media-worker-egress; journalctl -u moyi-media-worker-egress -n 5 --no-pager'
sleep 30; ssh moyi 'systemctl show -p NRestarts --value moyi-media-worker-egress; systemctl is-active moyi-media-worker-egress'
ssh moyi 'docker ps --filter label=role=media-worker --format "{{.Names}} {{.Status}}"'
ssh moyi '/usr/local/sbin/moyi-media-worker-egress check && echo limited'
```

2c should show `active`, a journal line `limited moyi-media-worker-<version>
(pid …): Postgres on 172.18.0.0/16, HTTPS to Cloudflare` and no `docker
events ended` or `cannot be read with --format` after it, the same restart
count (0 on a fresh install) and still `active` thirty seconds later, one worker
container `Up … (healthy)`, and `limited`. `check` passes when no worker runs
at all, so the `docker ps` line must show one. The worker stays healthy: its
open connection to Postgres is kept, and new ones are allowed.

### Step 3. The media check, as of #116

`ops/monitoring/media-check.sh` gained two checks in #116 (an upload waiting
`UPLOADED` for 30 minutes; a worker running without its limit), so the copy on
the server is older than the one that watches the worker. Copy it, with its
units, again:

```
scp ops/monitoring/media-check.sh moyi:/usr/local/sbin/moyi-media-check
scp ops/monitoring/moyi-media-check.service ops/monitoring/moyi-media-check.timer moyi:/etc/systemd/system/
ssh moyi 'chmod 700 /usr/local/sbin/moyi-media-check && systemctl daemon-reload'
ssh moyi 'grep -q "^MEDIA_PING_URL=" /etc/moyi-monitoring/heartbeats.env && echo "heartbeat: set"'
```

If the last line prints nothing, the check was never installed: do
`ops/monitoring/README.md`, "Installing the media check", from its
`export BW_SESSION` line on (the Healthchecks.io check and `HC_MEDIA_URL`
first). Then:

```
ssh moyi 'systemctl enable --now moyi-media-check.timer && systemctl start moyi-media-check; journalctl -u moyi-media-check -n 3 --no-pager'
```

which should end `moyi-media-check: ok (…)`. Leave `MEDIA_MODERATION_ON`
unset: moderation is `none`.

### Step 4. Merge, wait for the image, deploy

Merge the switch-on pull request, wait for its CI run on `main` to finish
(the `manifest` job publishes the image), then, as always:

```
git switch main && git pull --ff-only
kamal deploy --skip-push --version=$(git rev-parse HEAD)
```

The `pre-deploy` hook asks the server whether step 2's limit is in place, and
refuses before anything boots if not (it says so, naming step 2). Kamal asks Bitwarden for the
three fields of step 1; a field it cannot find
fails the deploy before anything is touched. A container given an empty one
refuses to start, naming it (`missing: moyi.media.storage.r2.endpoint
(MOYI_MEDIA_STORAGE_R2_ENDPOINT)`), and Kamal keeps the old one serving.

### Step 5. The logs and the limit

```
# both roles running, the worker healthy
kamal app details
ssh moyi 'docker ps --filter label=service=moyi --format "{{.Names}} {{.Status}}"'

# the worker: NATIVE, R2, its three consumers, and nothing else
kamal app logs -r media-worker --since 10m | grep -E 'Media processing: NATIVE|Voice processing: NATIVE|Media storage: R2|Event consumer registered|Ready'

# the API: processing off, R2
kamal app logs -r web --since 10m | grep -E 'Media processing: OFF|Media storage: R2'

# the new worker container was limited as it started
ssh moyi 'journalctl -u moyi-media-worker-egress --since "15 min ago" --no-pager | grep limited; /usr/local/sbin/moyi-media-worker-egress check && echo limited'
```

`Media storage: R2 (bucket: moyi-media)` in both roles; no
`Media storage: UNAVAILABLE` anywhere.

### Step 6. One upload of each kind, against R2: `scripts/r2-switch-on-check.sh`

From a verified test account of yours (one that is in no bond with a real
person), on the Mac:

```
MOYI_CHECK_API=https://api.getmoyi.app/api/v1 MOYI_CHECK_EMAIL=<the test account> \
  MOYI_CHECK_URL_MIN_AGE=40 scripts/r2-switch-on-check.sh
```

It asks for the password. It covers what ADR-0047, ADR-0048 and ADR-0049 left
as "R2, not run", in the order the header of the script gives: a photo's
upload, with three PUTs R2 must refuse first (a body one byte longer, a body
of the signed length with one byte changed, another `Content-Type`); the
worker turning it `READY`; a GET of its URL once that URL is at least 40
minutes old (a URL is signed as of the start of the hour,
ADR-0048 decision 6; with `MOYI_CHECK_URL_MIN_AGE=40` it waits until then,
so run it after twenty to the hour to wait least); a voice note made `READY`
and served as `audio/ogg`; each deleted, and its URL then answered `404` by R2.
It prints statuses and media ids, never a URL or a key, deletes what it made
even when it fails, and signs out. It ends `N passed, 0 failed`.

What a failure means:

| Fails | Means | Do |
|---|---|---|
| the upload intent | `503`: storage not configured, or not reached at all | step 5's logs |
| "one byte changed" (then the completion's line says what happened next) | R2 does not check the signed SHA-256 (ADR-0047 decision 1's open item). The length and type are still signed | if the completion then refused the bytes (`409`), the server's own check holds; record it in ADR-0047. If it did not, switch off and tell whoever builds the next slice |
| `READY` | the worker did not take it up | `kamal app logs -r media-worker`; `ops/monitoring/media-pipeline-stuck.md` |
| the GET of the URL | R2 refused a signature dated earlier in the hour | switch off (below): no URL handed out would work |
| `audio/ogg` | R2 served the voice note with another type | record it; a phone may still play it, test before deciding |
| the URL after the delete | the objects outlived the delete | `media.purge` retries; `media-pipeline-stuck.md` |

### Step 7. On the server, afterwards

```
# the test assets are DELETED and purged; nothing waits
ssh moyi "docker exec moyi-postgres psql -U moyi -d moyi -c \"SELECT kind, status, (purged_at IS NOT NULL) AS purged, count(*) FROM media_assets GROUP BY 1, 2, 3\""
# the worker's memory after real decodes (ADR-0052 owes this number from the server)
ssh moyi 'docker stats --no-stream $(docker ps -q --filter label=role=media-worker)'
# the alarm is quiet
ssh moyi 'systemctl start moyi-media-check; journalctl -u moyi-media-check -n 2 --no-pager'
```

and, with step 1's `f` and `BW_SESSION`, that no upload is left under `in/`
(it prints a count, never a key):

```
printf 'user = "%s:%s"\n' "$(f MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID)" "$(f MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY)" |
  curl -s -K - --aws-sigv4 "aws:amz:auto:s3" "$(f R2_ENDPOINT)/moyi-media?list-type=2&prefix=in/" | grep -o '<KeyCount>[0-9]*</KeyCount>'
```

### Not covered by the runbook

- **The reaper** (ADR-0050: a pending upload past its expiry, a photo
  unattached for 24 hours). It deletes through the same storage call, on the
  same role, as the delete in step 6, which proves the token may delete; its
  schedule is `media-check.sh`'s to watch (`job_runs`).
- **The ceiling HEIC on the CAX21** (ADR-0052, Owed) and **the iPhone's
  playback of the Ogg Opus** (ADR-0049, Q4): each needs a file the script does
  not carry, or a phone.

### Launch gate: no real users until the media mirror runs

Storage on is for the owner's test accounts only. **No real user is invited
while any of these holds** (ADR-0050 and ADR-0052, Owed):

- **The media mirror (ADR-0050, Q15) is not running.** Every photo and voice
  note has one copy, in `moyi-media`. And the worker holds the bucket's token,
  which can delete as well as write (R2 has no write-only scope): a decoder
  subverted by an upload could delete every object in the bucket, the only
  copy. The mirror's locked bucket is what survives that.
- **The known-image list is `none`** (ADR-0051 decision 3): uploads are stored
  without being matched against any list of known abuse material.
- **Moderation is `none`**: no partner sees any media. That is safe, but it is
  not the product.

### To switch off again

**Not by `kamal rollback`.** Kamal 2.12's rollback boots the older image with
a fresh `docker run` whose environment is read from *this* checkout's
`config/deploy.yml` and `.kamal/secrets` (`Cli::Main#rollback` invokes the
app boot; `Cli::App::Boot#start_new_version` uploads the role's secrets, and
`Commands::App#run` passes the role's env), and the `pre-deploy` hook holds
the checkout to `origin/main`. So a rollback brings back an older image with
storage still configured. Switching off is a pull request, already made: the
branch **`ops/media-storage-off`** puts `MOYI_MEDIA_STORAGE_PROVIDER:
unavailable` back in the top-level `env` and turns
`ProductionStartsWithoutMediaSecretsTest`'s storage-on test round to hold
storage off; its build passed when it was made. To use it:

```
git fetch origin
git switch ops/media-storage-off
git merge --no-edit origin/main     # only if main has moved since; resolve any conflict, then
./gradlew :app:test --tests 'com.moyi.app.ProductionStartsWithoutMediaSecretsTest'
git push                             # only if the merge changed anything
gh pr create --base main --head ops/media-storage-off \
  --title "fix(media): switch media storage off in production" --body "Storage off (ops/media-worker/README.md)."
```

Then wait for CI, merge, and deploy as in step 4. With storage `unavailable`
the hook does not ask about the network limit. Uploads are refused again
with `503 MEDIA_STORAGE_UNAVAILABLE`; what was uploaded stays in the bucket,
and `media.purge`'s deliveries wait until storage is back
(`ops/monitoring/media-pipeline-stuck.md`, "The storage was configured, and
is now `unavailable` again"). Afterwards make the branch again from the new
`main`, for next time.

### When Rekognition is switched on

The worker must also reach `rekognition.eu-central-1.amazonaws.com`: put AWS's
`eu-central-1` ranges for the `AMAZON` service
(https://ip-ranges.amazonaws.com/ip-ranges.json) in
`/etc/moyi-media-worker/egress.env` as `EXTRA_HTTPS_CIDRS="…"` and
`systemctl restart moyi-media-worker-egress` (which re-limits running
containers). Without it every moderation call is refused, retried as an
outage, and `media-check.sh` alarms on unmoderated assets after 30 minutes.

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
