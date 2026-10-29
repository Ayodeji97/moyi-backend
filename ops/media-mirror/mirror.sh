#!/usr/bin/env bash
# mirror.sh — the nightly media mirror (spec Phase 4 §4 D5, ruling Q15; risk
# R-D). Runs as root on the server, from moyi-media-mirror.timer; installed as
# /usr/local/sbin/moyi-media-mirror.
#
# The database backup is pg_dump only, so without this a lost `moyi-media`
# bucket loses every photo. Each night it copies the objects under `v/` (the
# variants the app keeps) that are in the source bucket and missing from the
# target, a second bucket with a 30-day bucket lock. It never copies `in/`:
# raw uploads still carry EXIF and GPS, and the app deletes them once
# processed.
#
# It copies and never deletes or overwrites (`mc mirror` without --remove or
# --overwrite): the target's lock would refuse both anyway. Erasure reaches the
# mirror through the target's 31-day lifecycle rule instead: every copy expires
# 31 days after it was written, a live object is copied again the next night,
# and an erased one is not, because it is no longer in the source. Why, and
# what it costs: ops/media-mirror/README.md.
#
# The copying is done by the MinIO client `mc`, from the image docker-compose.yml
# already pins, so nothing is installed on the server beyond Docker.
#
# It prints one line on success: counts only, never an object key or a URL (a
# key reaches the bytes for whoever holds the bucket's credentials). On
# success it pings MIRROR_PING_URL; on any failure it pings MIRROR_PING_URL/fail
# with a one-line reason and exits non-zero. If the box is down no ping
# arrives, and the heartbeat's grace period alerts: silence is the alarm.
#
# Configuration, root-only (install steps in ops/media-mirror/README.md):
#   /etc/moyi-media-mirror/mirror.env
#     MC_HOST_src       https://KEY:SECRET@<source endpoint>  (read-only token)
#     MC_HOST_dst       https://KEY:SECRET@<target endpoint>  (write token)
#     SOURCE_BUCKET     moyi-media
#     TARGET_BUCKET     the mirror bucket
#     MIRROR_PING_URL   the Healthchecks.io check's ping URL
# The two MC_HOST_ values reach the container by name (`docker run -e NAME`),
# so the keys are never on a command line, where `ps` would show them.
#
# For the local test (test-mirror.sh) only:
#   MIRROR_ENV_FILE         another env file
#   MIRROR_DOCKER_NETWORK   the docker network mc runs on (default: bridge)
#   MIRROR_CONTAINER_NAME   the name of mc's container (default:
#                           moyi-media-mirror; a fixed name also means a
#                           second run cannot start while one is going)
set -euo pipefail
umask 077

ENV_FILE="${MIRROR_ENV_FILE:-/etc/moyi-media-mirror/mirror.env}"
NETWORK="${MIRROR_DOCKER_NETWORK:-bridge}"
NAME="${MIRROR_CONTAINER_NAME:-moyi-media-mirror}"
# RELEASE.2026-08-04T00-00-00Z, the digest docker-compose.yml pins for MinIO.
IMAGE=pgsty/minio@sha256:b6bfe7239bfc83fb90d31612d9704d86039dd714f7904b3f1ad68f211e602372
PREFIX=v/

die() { echo "moyi-media-mirror: $*" >&2; exit 1; }
# After the env file is read: report the failure to the heartbeat, then stop.
# A ping that cannot be delivered does not hide the failure; the exit does not.
fail() {
  curl --fail --silent --max-time 10 --retry 3 --data-raw "$*" "$MIRROR_PING_URL/fail" >/dev/null \
    || echo "moyi-media-mirror: failure ping not delivered" >&2
  die "$*"
}

[ -r "$ENV_FILE" ] || die "$ENV_FILE is missing"
# shellcheck source=/dev/null
. "$ENV_FILE"
: "${MIRROR_PING_URL:?set in $ENV_FILE}"
[ -n "${MC_HOST_src:-}" ] || fail "MC_HOST_src is not set in $ENV_FILE"
[ -n "${MC_HOST_dst:-}" ] || fail "MC_HOST_dst is not set in $ENV_FILE"
[ -n "${SOURCE_BUCKET:-}" ] || fail "SOURCE_BUCKET is not set in $ENV_FILE"
[ -n "${TARGET_BUCKET:-}" ] || fail "TARGET_BUCKET is not set in $ENV_FILE"
export MC_HOST_src MC_HOST_dst
docker info >/dev/null 2>&1 || fail "docker is not running"

out="$(mktemp)"
trap 'rm -f "$out"' EXIT

# --json: a line per object copied, a line per error, and a last line with
# the totals. Errors are counted here and never printed whole: they name keys
# and endpoints. mc exits 0 when it cannot reach the target at all (it reports
# the error and copies nothing), so an error line fails the run as surely as a
# non-zero exit does.
status=0
docker run --rm --name "$NAME" --network "$NETWORK" -e MC_HOST_src -e MC_HOST_dst \
  --entrypoint mc "$IMAGE" \
  mirror --json --quiet --retry \
  "src/$SOURCE_BUCKET/$PREFIX" "dst/$TARGET_BUCKET/$PREFIX" >"$out" 2>&1 || status=$?

copied="$(grep -c '"status":"success","source"' "$out" || true)"
errors="$(grep -c '"status":"error"' "$out" || true)"

if [ "$status" -ne 0 ] || [ "$errors" -gt 0 ]; then
  # The first error's message and its cause, with every URL, host and object
  # key replaced, so the journal and the alert carry the reason and no more.
  first="$(grep '"status":"error"' "$out" | head -n 1 || true)"
  msg="$(printf '%s' "$first" | sed -nE 's/^.*"error":\{"message":"([^"]*)".*$/\1/p')"
  cause="$(printf '%s' "$first" | sed -nE 's/^.*"cause":\{"message":"(([^"\\]|\\.)*)".*$/\1/p')"
  reason="$(printf '%s %s' "$msg" "$cause" | sed -E \
    -e 's/\\"/"/g' \
    -e 's#[a-z]+://[^ "]*#<url>#g' \
    -e 's#lookup [^ ]+#lookup <host>#g' \
    -e 's#(^|[^[:alnum:]_-])(v|in)/[^ ]*#\1\2/<key>#g' | cut -c1-200)"
  fail "mc mirror failed (exit $status, $errors errors, $copied copied): ${reason:-no message}"
fi

echo "moyi-media-mirror: copied $copied new objects under $PREFIX to the mirror"

curl --fail --silent --max-time 10 --retry 3 "$MIRROR_PING_URL" >/dev/null \
  || echo "moyi-media-mirror: heartbeat not delivered" >&2
