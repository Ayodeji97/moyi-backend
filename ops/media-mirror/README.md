# The media mirror

The nightly backup (`ops/backup`, ADR-0043) is `pg_dump` only. The photos are not in the
database; they are objects in the R2 bucket `moyi-media` (ADR-0047). Without a second copy, a
lost bucket, a deleted token with the wrong scope, or a closed Cloudflare account loses every
photo anyone has shared, and the restored database then points at nothing. That is risk R-D in
the Phase 4 spec; the owner's ruling Q15 is this mirror.

| File | Runs on | Does |
|---|---|---|
| `mirror.sh` | the server, nightly at 04:30 UTC (`moyi-media-mirror.timer`) | copies the objects under `v/` in `moyi-media` that the mirror bucket does not have; pings the heartbeat |
| `test-mirror.sh` | the Mac (`colima start`) | proves what `mirror.sh` does against two throwaway MinIO servers |
| `ops/backup/restore-drill.sh` (last step) | the Mac | fetches one photo from the mirror and checks it is a WebP |

The copying is done by the MinIO client `mc`, run from the MinIO image `docker-compose.yml`
already pins by digest, so nothing is installed on the server beyond Docker.

## What is copied, and what is not

Only `v/`: the variants the app keeps and serves. Never `in/`: a raw upload still carries its
EXIF data, GPS position included, and the app deletes it once the variants are made, so a copy
of it would keep exactly what the processing exists to remove.

Only what is new. `mc mirror` runs without `--overwrite` and without `--remove`: an object the
mirror already has is never written again, and nothing in the mirror is ever deleted by the
mirror. The keys are random (`v/<uuid>/…`) and the app never rewrites one, so "new" is simply
"not in the mirror yet".

## Erasure, and why the mirror never deletes

When someone deletes an entry, withdraws it, or deletes their account, the app deletes its
photos from `moyi-media` (D-9). A mirror that kept them would be a copy of erased content. Two
designs reach the same end; this one is chosen because of the lock:

- **The mirror deletes what the source no longer has** (`mc mirror --remove`). That needs a
  token on the mirror that can delete, and a token that can delete can empty the bucket: the
  one thing a backup must survive is whoever holds the server. And the bucket lock refuses the
  deletes anyway.
- **Every copy expires, and the live ones are copied again (chosen).** The mirror bucket has a
  **bucket lock keeping each object 30 days** and a **lifecycle rule deleting each object 31
  days after it was written**. Each night the mirror copies whatever is in the source and
  missing from the target. So a copy of a live photo expires on day 31 and is copied again that
  night; a copy of an erased photo expires on day 31 and is not, because it is no longer in the
  source.

An erased photo therefore leaves the mirror at most **31 days** after its last copy (plus the
provider's lifecycle lag: R2 removes expired objects within about a day). That is the same
window as the database: the backups are kept 30 days and a deletion request is re-applied on
restore (doc 07 §7), so an erased photo cannot outlive its erasure longer in the mirror than its
row does in a backup.

For a day or so each month, a live photo is in the source but not yet back in the mirror: the
night between its copy expiring and being copied again. A disaster in that window loses that
day's share of the photos (about one thirtieth), not all of them.

`test-mirror.sh` checks both halves: an erased object whose copy expired is not copied back, and
a live object whose copy expired is.

## What it costs

Each live object is copied again about once a month, and every copy is a write (R2: Class A,
$4.50 per million). At the spec's steady state, about 150 GB of display variants of about 150 KB,
roughly a million objects:

| | per month |
|---|---|
| re-copying each object once: 1 M writes on the mirror | ~$4.50 |
| reading each object once: 1 M reads on `moyi-media` (Class B, $0.36 per million) | ~$0.36 |
| listing both buckets nightly: 1,000 keys per list, 2 × 1,000 lists × 30 nights (Class A) | ~$0.27 |
| storing 150 GB on the mirror ($0.015 per GB-month on R2) | ~$2.25 |
| **total, if the mirror is on R2** | **~$7.40** |

Today there are almost no photos, and all of this is within R2's free tier. Another provider's
prices differ: read its write, list and storage prices before choosing, and note that some
charge a minimum storage duration (often 30 days or more), which this design meets but does not
exceed by much.

## Where the mirror lives: the owner's decision, owed

The spec leaves this open (R-D): "same-account R2 shares the account as a failure domain". The
script works with any S3-compatible endpoint, so the choice changes only the env file.

1. **A second R2 bucket in the same Cloudflare account.** Simplest: the same dashboard, the same
   bucket lock and lifecycle settings the backups already use, no new account. But one account
   is one failure domain: a suspended or compromised Cloudflare account, or a mistake at the
   account level, takes the photos and their mirror together.
2. **A bucket at another provider, S3-compatible, with object lock, in the EU** (the photos are
   personal data, and `moyi-media` is in Cloudflare's EU jurisdiction). A new account and a new
   bill, but a failure at Cloudflare no longer reaches the mirror.

**Recommended: option 2.** A mirror exists for the day the first copy is gone; the likeliest
ways to lose `moyi-media` whole are account-level, and option 1 shares every one of them. No
vendor is picked here; that is the owner's to choose.

Whichever is chosen, the provider must give: object lock or a bucket lock with a 30-day
retention; a lifecycle rule expiring objects 31 days after they are written; and an access key
that can write but, with the lock, cannot delete or overwrite. On a provider whose object lock
needs versioning (S3-style Object Lock), expiring an object leaves a delete marker and the old
version behind, which would keep erased content: the lifecycle rule must also expire noncurrent
versions (after 1 day) and remove expired delete markers. R2's bucket lock has no versions.

## Setting it up

### 1. The mirror bucket (in the chosen provider's console)

- create the bucket, in the EU;
- a **bucket lock / default retention of 30 days** on the whole bucket;
- a **lifecycle rule deleting every object 31 days after it was created** (and, if the bucket is
  versioned, noncurrent versions after 1 day and expired delete markers);

Neither rule is in this repository, so check both are still there after any change to the
bucket, as for `moyi-backups`.

### 2. Three tokens

| Token | Scope | Where |
|---|---|---|
| media read | R2: **Object Read only**, on `moyi-media` only | the server |
| mirror write | the mirror bucket only: read, list and write objects. It can delete, as R2's tokens can, but the lock refuses any delete or overwrite for 30 days: write-only in practice | the server |
| mirror drill | the mirror bucket only: **read only** | never on the server; the restore drill uses it from the Mac |

### 3. Bitwarden

Add to the secure note `moyi-prod`:

| Field | What |
|---|---|
| `R2_MEDIA_READ_KEY_ID`, `R2_MEDIA_READ_SECRET` | the media read token (`moyi-media` is served by `R2_ENDPOINT`, already in the note) |
| `MIRROR_ENDPOINT` | the mirror's S3 endpoint, `https://…` |
| `MIRROR_BUCKET` | the mirror bucket's name |
| `MIRROR_REGION` | only if the provider is not R2: its S3 region (the drill signs with it; R2 takes `auto`) |
| `MIRROR_WRITE_KEY_ID`, `MIRROR_WRITE_SECRET` | the mirror write token |
| `MIRROR_DRILL_KEY_ID`, `MIRROR_DRILL_SECRET` | the mirror drill token |
| `HC_MIRROR_URL` | the heartbeat's ping URL (step 4) |

The keys go into URLs (`https://KEY:SECRET@host`, the form `mc` reads from `MC_HOST_<alias>`),
so a key holding `/`, `@` or `:` must be percent-encoded first. R2's keys are hex and need
nothing.

### 4. The heartbeat

In Healthchecks.io, a new check beside the backup's: **period 1 day, grace 2 hours**. A night
with no ping alerts after the grace; a failing run pings `/fail` with a one-line reason and
alerts at once. A failed night is retried twice, 15 minutes apart, so one that recovers sends a
"down" and then an "up". Keep its ping URL in Bitwarden as `HC_MIRROR_URL`: whoever has it can
report "all well".

### 5. Installing on the server

From the Mac, in `moyi-backend`:

```
scp ops/media-mirror/mirror.sh moyi:/usr/local/sbin/moyi-media-mirror
scp ops/media-mirror/moyi-media-mirror.service ops/media-mirror/moyi-media-mirror.timer moyi:/etc/systemd/system/
ssh moyi 'chmod 700 /usr/local/sbin/moyi-media-mirror && install -d -m 700 /etc/moyi-media-mirror && systemctl daemon-reload'
```

The env file, written from Bitwarden without passing through a file on the Mac or the shell
history (`printf` is a shell builtin, so the keys are not in `ps` either):

```
export BW_SESSION="$(bw unlock --raw)"
# The CLI reads a local copy of the vault: a field added in the web vault since
# the last sync is not there, and f() then writes an empty value.
bw sync >/dev/null
f() { bw get item moyi-prod | python3 -c 'import json,sys; print(next(x["value"] for x in json.load(sys.stdin)["fields"] if x["name"]==sys.argv[1]))' "$1"; }
host() { f "$1" | sed 's#^https://##; s#/*$##'; }
printf 'MC_HOST_src="https://%s:%s@%s"\nMC_HOST_dst="https://%s:%s@%s"\nSOURCE_BUCKET=moyi-media\nTARGET_BUCKET="%s"\nMIRROR_PING_URL="%s"\n' \
  "$(f R2_MEDIA_READ_KEY_ID)" "$(f R2_MEDIA_READ_SECRET)" "$(host R2_ENDPOINT)" \
  "$(f MIRROR_WRITE_KEY_ID)" "$(f MIRROR_WRITE_SECRET)" "$(host MIRROR_ENDPOINT)" \
  "$(f MIRROR_BUCKET)" "$(f HC_MIRROR_URL)" \
  | ssh moyi 'install -m 600 /dev/stdin /etc/moyi-media-mirror/mirror.env'
bw lock; unset BW_SESSION
```

Then run it once by hand, read what it did, and enable the timer:

```
ssh moyi 'systemctl start moyi-media-mirror; journalctl -u moyi-media-mirror -n 5 --no-pager'
ssh moyi 'systemctl enable --now moyi-media-mirror.timer && systemctl list-timers moyi-media-mirror.timer --no-pager'
```

A good run ends with `moyi-media-mirror: copied N new objects under v/ to the mirror`. It prints
counts only: an object key reaches the bytes for whoever holds the bucket's credentials, so no
key or URL is ever logged, and an error's reason is printed with keys, hosts and URLs replaced.

### 6. Proving the alert reaches you

A check nobody has seen fire is not known to work. Once, after installing: break the env file
on purpose (`ssh moyi "sed -i 's/^TARGET_BUCKET=.*/TARGET_BUCKET=no-such-bucket/' /etc/moyi-media-mirror/mirror.env"`),
run `systemctl start moyi-media-mirror`, confirm the email arrives, then write the env file again
from step 5 and run it once more to see the check go back up.

### 7. Once: the lock and the lifecycle, on the real bucket

The test proves the script, against MinIO. It cannot prove how the chosen provider's lock and
lifecycle rule behave together. Once, after the first run: confirm a delete of a mirrored object
with the write token is refused; and about 32 days later, confirm an object's copy expired and
was copied again (its creation date in the mirror is recent), and that a photo deleted in the app
since then is gone from the mirror.

## The test

```
colima start
ops/media-mirror/test-mirror.sh
```

It starts two MinIO servers on a docker network of its own (no host port), gives the source a
read-only user and the target object lock with a 30-day default retention, and checks: `v/` is
copied and `in/` is not; a second run copies nothing; an erased object whose copy expired is not
copied back; a live one whose copy expired is; a run pings the heartbeat and a failing one
(target unreachable) pings `/fail` and exits non-zero; and nothing printed or pinged names a
key. It removes everything it started.

One thing it found: `mc mirror` **exits 0 when the target cannot be reached**, having copied
nothing; it reports the error only as a JSON line. `mirror.sh` therefore fails a run on any error
line as well as on a non-zero exit, and the test fails if that check is removed.

## Not verified

- `mc` against the real R2 (or the chosen provider): the script has run only against MinIO. The
  first run by hand (step 5) is that check.
- The lock and lifecycle interplay on the chosen provider (step 7).
- The restore drill's mirror step: written and syntax-checked, never run, since no mirror exists
  yet. Until `MIRROR_DRILL_KEY_ID` is in `moyi-prod` the drill prints that the step was skipped.
