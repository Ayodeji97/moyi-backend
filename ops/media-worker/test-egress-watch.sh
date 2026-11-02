#!/usr/bin/env bash
# test-egress-watch.sh — proves that egress-limit.sh's `watch` limits a worker container that
# starts AFTER the watch began, on the Docker this machine runs (not Docker-in-Docker). On this
# Mac that is colima's VM: Ubuntu 24.04 and Docker 29, as on moyi-prod-1. Written after the watch
# failed on the server (review of #117): Docker 29 no longer has the event's `.ID` field, so the
# first event after start ended the watch, and systemd restart-looped it.
#
#   ops/media-worker/test-egress-watch.sh [egress-limit.sh]   the script to test (this checkout's by default)
#   MOYI_EGRESS_EVENT_FORMAT='{{.ID}}' ops/media-worker/test-egress-watch.sh   the old format, to see it refused at start
#
# It starts, all named moyi-egress-watch-<random> and all removed at the end: a network of its own;
# a privileged helper container sharing the Docker host's process namespace and socket, which runs
# the watch as the server's systemd unit does (the host's iptables via nsenter); and, once the watch
# runs, a fake worker labelled as Kamal labels the role. It limits nothing but that fake worker's
# own network namespace. Prints PASS or FAIL for each check; exits non-zero if any failed.
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${1:-$DIR/egress-limit.sh}"
CLI=docker:29-cli@sha256:5e6baf67325a79f64ed3bed6863954d5e05d0f3d4e57cbdb14809346814bbfdb # pulled 2026-10-31
ID="moyi-egress-watch-$RANDOM"
docker info >/dev/null 2>&1 || { echo "docker is not running (colima start)" >&2; exit 2; }
[ -z "$(docker ps -q --filter label=service=moyi --filter label=role=media-worker)" ] ||
  { echo "a container labelled as a media worker already runs here; refusing to test beside it" >&2; exit 2; }

cleanup() {
  docker rm -f "$ID-worker" "$ID-helper" >/dev/null 2>&1 || true
  docker network rm "$ID" >/dev/null 2>&1 || true
}
trap cleanup EXIT
failed=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; failed=1; }
echo "Docker $(docker version --format '{{.Server.Version}} (API {{.Server.APIVersion}})') on $(docker info --format '{{.OperatingSystem}}'); script: $SCRIPT"

docker network create "$ID" >/dev/null
docker run -d --name "$ID-helper" --privileged --pid host -v /var/run/docker.sock:/var/run/docker.sock \
  -e MOYI_EGRESS_NETWORK="$ID" -e MOYI_EGRESS_EVENT_FORMAT="${MOYI_EGRESS_EVENT_FORMAT:-}" \
  --entrypoint sleep "$CLI" 3600 >/dev/null
setup() { "$@" >/dev/null || { echo "setup failed: $*" >&2; exit 2; }; }
setup docker exec "$ID-helper" apk add --no-cache bash util-linux iptables ip6tables
setup docker exec "$ID-helper" mkdir -p /usr/local/sbin
setup docker cp "$SCRIPT" "$ID-helper:/usr/local/sbin/moyi-media-worker-egress"
setup docker exec "$ID-helper" chmod 700 /usr/local/sbin/moyi-media-worker-egress

# The watch, as the unit runs it; its output in /tmp/watch.log inside the helper.
docker exec -d "$ID-helper" bash -c 'moyi-media-worker-egress watch >/tmp/watch.log 2>&1; echo "exit $?" >>/tmp/watch.log'
sleep 3 # time for it to probe and subscribe; nothing is limited until a worker starts
if docker exec "$ID-helper" grep -q '^exit ' /tmp/watch.log; then
  fail "the watch is running before the worker starts (it ended: $(docker exec "$ID-helper" cat /tmp/watch.log | tr '\n' ' '))"
else
  pass "the watch is running before the worker starts"
fi

# The worker starts after the watch began.
docker run -d --name "$ID-worker" --network "$ID" --cap-drop ALL --security-opt no-new-privileges \
  --label service=moyi --label role=media-worker busybox:1.37 sleep 3600 >/dev/null
WORKER_ID="$(docker inspect --format '{{.Id}}' "$ID-worker")"
for _ in $(seq 1 20); do
  docker exec "$ID-helper" grep -q "limited $WORKER_ID" /tmp/watch.log && break
  sleep 0.5
done
if docker exec "$ID-helper" grep -q "limited $WORKER_ID" /tmp/watch.log; then
  pass "the worker started after the watch was limited by it"
else
  fail "the worker started after the watch was limited by it (watch log: $(docker exec "$ID-helper" cat /tmp/watch.log | tr '\n' ' '))"
fi
PID="$(docker inspect --format '{{.State.Pid}}' "$ID-worker")"
if docker exec "$ID-helper" nsenter --target "$PID" --net -- iptables -w -S OUTPUT | grep -q -- '-j MOYI-EGRESS'; then
  pass "…its network namespace jumps to MOYI-EGRESS"
else
  fail "…its network namespace jumps to MOYI-EGRESS"
fi
if docker exec "$ID-helper" moyi-media-worker-egress check; then pass "…and check passes"; else fail "…and check passes"; fi
if docker exec "$ID-helper" grep -q '^exit ' /tmp/watch.log; then
  fail "the watch is still running after the event ($(docker exec "$ID-helper" cat /tmp/watch.log | tr '\n' ' '))"
else
  pass "the watch is still running after the event"
fi

[ "$failed" = 0 ] && echo "test-egress-watch: all passed" || echo "test-egress-watch: FAILED"
exit "$failed"
