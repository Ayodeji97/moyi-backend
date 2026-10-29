#!/usr/bin/env bash
# test-mirror.sh — proves what mirror.sh does, against two throwaway MinIO
# servers (the image docker-compose.yml pins) on a docker network of their
# own. Runs on the Mac (colima start); publishes no port and touches no
# container it did not start, all of them named moyi-d5-mirror-*.
#
#   ops/media-mirror/test-mirror.sh
#
# The source is given a READ-ONLY user, as production's source token is read
# only, and the target is a bucket with object lock and a 30-day default
# retention (GOVERNANCE, so the test can play the lifecycle rule by deleting
# with --bypass). It checks:
#   a. objects under v/ are copied, and objects under in/ are not;
#   b. a second run copies nothing;
#   c. an object erased from the source, whose mirror copy has expired, is not
#      copied back: the erasure reaches the mirror;
#   d. an object still in the source, whose mirror copy has expired, is copied
#      again;
#   e. a run pings the heartbeat; a failing run (the target unreachable) pings
#      /fail with a reason and exits non-zero;
#   f. nothing it prints names an object key.
# Prints PASS or FAIL for each and exits non-zero if any failed.
set -euo pipefail
umask 077

DIR="$(cd "$(dirname "$0")" && pwd)"
IMAGE=pgsty/minio@sha256:b6bfe7239bfc83fb90d31612d9704d86039dd714f7904b3f1ad68f211e602372
P=moyi-d5-mirror
NET="$P-net"
# Plainly fake: these servers live for the length of the test.
ADMIN_USER=moyi_mirror_test_only ADMIN_PASS=moyi_mirror_test_only_secret
RO_USER=moyi_mirror_ro_test_only RO_PASS=moyi_mirror_ro_test_only_secret
SRC_BUCKET=moyi-media DST_BUCKET=moyi-media-mirror

command -v python3 >/dev/null || { echo "python3 is not installed" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "docker is not running (colima start)" >&2; exit 1; }

WORK="" LISTENER=""
cleanup() {
  [ -z "$LISTENER" ] || kill "$LISTENER" 2>/dev/null || true
  docker ps -aq --filter "name=^$P-" | xargs -r docker rm -f >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  [ -z "$WORK" ] || rm -rf "$WORK"
}
trap cleanup EXIT
cleanup  # what an earlier run killed outright left behind
WORK="$(mktemp -d)"

FAILED=0
pass() { echo "PASS  $*"; }
flunk() { echo "FAIL  $*"; FAILED=1; }

# mc as the admin of both servers, for setting up and inspecting.
n=0
mcx() {
  n=$((n + 1))
  docker run --rm -i --name "$P-mc-$n" --network "$NET" \
    -e MC_HOST_src="http://$ADMIN_USER:$ADMIN_PASS@$P-src:9000" \
    -e MC_HOST_dst="http://$ADMIN_USER:$ADMIN_PASS@$P-dst:9000" \
    --entrypoint mc "$IMAGE" "$@"
}
keys() { mcx ls --recursive "$1" | awk '{print $NF}' | sort; }
put() { printf 'RIFF\0\0\0\0WEBPVP8 test only %s' "$2" | mcx pipe --quiet "$1/$2" >/dev/null; }

echo "== starting two MinIO servers on $NET"
docker network create "$NET" >/dev/null
for s in src dst; do
  docker run -d --name "$P-$s" --network "$NET" \
    -e MINIO_ROOT_USER="$ADMIN_USER" -e MINIO_ROOT_PASSWORD="$ADMIN_PASS" \
    "$IMAGE" server /data >/dev/null
done
for s in src dst; do
  for _ in $(seq 1 60); do
    docker exec "$P-$s" curl -fs http://localhost:9000/minio/health/live >/dev/null 2>&1 && break
    sleep 1
  done
  docker exec "$P-$s" curl -fs http://localhost:9000/minio/health/live >/dev/null || { echo "MinIO $s did not come up" >&2; exit 1; }
done

mcx mb --quiet "src/$SRC_BUCKET" >/dev/null
mcx admin user add src "$RO_USER" "$RO_PASS" >/dev/null
# List and get on the one bucket, as R2's "Object Read only" token allows.
# (MinIO's built-in `readonly` cannot list, so mirror could not see anything.)
mcx admin policy create src mirror-read /dev/stdin >/dev/null <<EOF
{"Version":"2012-10-17","Statement":[
 {"Effect":"Allow","Action":["s3:ListBucket","s3:GetBucketLocation"],"Resource":["arn:aws:s3:::$SRC_BUCKET"]},
 {"Effect":"Allow","Action":["s3:GetObject"],"Resource":["arn:aws:s3:::$SRC_BUCKET/*"]}]}
EOF
mcx admin policy attach src mirror-read --user "$RO_USER" >/dev/null
mcx mb --quiet --with-lock "dst/$DST_BUCKET" >/dev/null
mcx retention set --quiet --default GOVERNANCE 30d "dst/$DST_BUCKET" >/dev/null

# Keys shaped like the app's: random UUIDs under v/ and in/.
K1="v/$(uuidgen | tr '[:upper:]' '[:lower:]')/full.webp"
K2="v/$(uuidgen | tr '[:upper:]' '[:lower:]')/display.webp"
K3="in/$(uuidgen | tr '[:upper:]' '[:lower:]')"
for k in "$K1" "$K2" "$K3"; do put "src/$SRC_BUCKET" "$k"; done

echo "== a heartbeat listener on 127.0.0.1 (not a container)"
cat >"$WORK/listener.py" <<'PY'
import http.server, sys
log = open(sys.argv[1], "a")
class H(http.server.BaseHTTPRequestHandler):
    def _any(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n).decode() if n else ""
        log.write(f"{self.command} {self.path} {body}\n"); log.flush()
        self.send_response(200); self.end_headers(); self.wfile.write(b"OK")
    do_GET = do_POST = do_HEAD = _any
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
open(sys.argv[2], "w").write(str(s.server_port))
s.serve_forever()
PY
: >"$WORK/pings"
python3 -I "$WORK/listener.py" "$WORK/pings" "$WORK/port" &
LISTENER=$!
for _ in $(seq 1 50); do [ -s "$WORK/port" ] && break; sleep 0.1; done
PING="http://127.0.0.1:$(cat "$WORK/port")/hc-test-only"

env_file() {  # $1: the target host
  cat <<EOF
MC_HOST_src="http://$RO_USER:$RO_PASS@$P-src:9000"
MC_HOST_dst="http://$ADMIN_USER:$ADMIN_PASS@$1:9000"
SOURCE_BUCKET=$SRC_BUCKET
TARGET_BUCKET=$DST_BUCKET
MIRROR_PING_URL="$PING"
EOF
}
env_file "$P-dst" >"$WORK/mirror.env"
env_file "$P-nowhere" >"$WORK/broken.env"

# Runs mirror.sh; its output goes to $WORK/out (and is shown), its status to $rc.
mirror() {
  rc=0
  MIRROR_ENV_FILE="$1" MIRROR_DOCKER_NETWORK="$NET" MIRROR_CONTAINER_NAME="$P-run" \
    bash "$DIR/mirror.sh" >"$WORK/out" 2>&1 || rc=$?
  sed 's/^/      | /' "$WORK/out"
  cat "$WORK/out" >>"$WORK/all-out"
}

echo "== run 1"
mirror "$WORK/mirror.env"
got="$(keys "dst/$DST_BUCKET")"
want="$(printf '%s\n' "$K1" "$K2" | sort)"
if [ "$rc" -eq 0 ] && [ "$got" = "$want" ] && grep -q 'copied 2 new objects' "$WORK/out"; then
  pass "a. v/ objects copied (2), in/ not copied"
else
  flunk "a. run 1: exit $rc; target holds: $(echo "$got" | tr '\n' ' ')"
fi

echo "== run 2"
mirror "$WORK/mirror.env"
if [ "$rc" -eq 0 ] && grep -q 'copied 0 new objects' "$WORK/out" && [ "$(keys "dst/$DST_BUCKET")" = "$want" ]; then
  pass "b. a second run copies nothing"
else
  flunk "b. run 2: exit $rc"
fi

echo "== erase K1 from the source; expire both mirror copies; run 3"
mcx rm --quiet "src/$SRC_BUCKET/$K1" >/dev/null
for k in "$K1" "$K2"; do mcx rm --quiet --versions --force --bypass "dst/$DST_BUCKET/$k" >/dev/null; done
[ -z "$(keys "dst/$DST_BUCKET")" ] || flunk "setup: the mirror copies were not removed"
mirror "$WORK/mirror.env"
got="$(keys "dst/$DST_BUCKET")"
if [ "$rc" -eq 0 ] && ! echo "$got" | grep -qxF "$K1"; then
  pass "c. an erased object whose copy expired is not copied back"
else
  flunk "c. run 3: exit $rc; the erased object is in the mirror again"
fi
if [ "$rc" -eq 0 ] && [ "$got" = "$K2" ] && grep -q 'copied 1 new objects' "$WORK/out"; then
  pass "d. a live object whose copy expired is copied again"
else
  flunk "d. run 3: exit $rc; target holds: $(echo "$got" | tr '\n' ' ')"
fi

echo "== run 4, with the target unreachable"
mirror "$WORK/broken.env"
oks="$(grep -c '^GET /hc-test-only $' "$WORK/pings" || true)"
fails="$(grep -c '^POST /hc-test-only/fail ' "$WORK/pings" || true)"
if [ "$oks" -eq 3 ]; then
  pass "e. each good run pinged the heartbeat (3 pings)"
else
  flunk "e. good runs pinged $oks times, not 3"
fi
if [ "$rc" -ne 0 ] && [ "$fails" -eq 1 ] && grep -q '^POST /hc-test-only/fail mc mirror failed' "$WORK/pings"; then
  pass "e. a failing run exits $rc and pings /fail with a reason"
else
  flunk "e. failing run: exit $rc, $fails /fail pings"
fi
echo "      /fail said: $(grep '/fail' "$WORK/pings" | cut -d' ' -f3- | head -n 1)"

leaked=""
for k in "$K1" "$K2" "$K3"; do
  id="${k#*/}"; id="${id%%/*}"
  ! grep -qF "$id" "$WORK/all-out" "$WORK/pings" || leaked=1
done
if [ -z "$leaked" ]; then pass "f. no object key in anything it printed or pinged"; else flunk "f. an object key was printed"; fi

echo
if [ "$FAILED" -eq 0 ]; then echo "ALL PASSED"; else echo "SOME CHECKS FAILED"; exit 1; fi
