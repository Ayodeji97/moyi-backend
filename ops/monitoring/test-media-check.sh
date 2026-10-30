#!/usr/bin/env bash
# shellcheck disable=SC2015 # pass and fail always succeed: A && pass || fail is an if-then-else here
# test-media-check.sh — runs media-check.sh with docker, systemctl, curl and the egress limiter
# replaced by stubs, and checks its verdict on the worker's network limit (ADR-0052, review of
# #117): with a worker running, a missing limiter, a stopped service or an unlimited container is
# a problem; with no worker running, none of them is. Touches no server.
#
#   ops/monitoring/test-media-check.sh [media-check.sh]   the script to test (this checkout's by default)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECK="${1:-$here/media-check.sh}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASSED=0 FAILED=0
pass() { PASSED=$((PASSED + 1)); echo "  PASS  $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL  $1${2:+ — $2}"; }

mkdir -p "$TMP/bin"
# docker: `ps` lists a worker when WORKER=1; `exec … psql` answers eight zeros (nothing wrong in the database).
cat > "$TMP/bin/docker" <<'EOF'
#!/bin/bash
case "$1" in
  ps) [ "${WORKER:-0}" = 1 ] && echo 0123456789ab ;;
  exec) echo "0 0 0 0 0 0 0 0" ;;
esac
exit 0
EOF
# systemctl: the service is active when SERVICE=active.
cat > "$TMP/bin/systemctl" <<'EOF'
#!/bin/bash
[ "${SERVICE:-active}" = active ]
EOF
# curl: records which heartbeat URL it was sent to.
cat > "$TMP/bin/curl" <<'EOF'
#!/bin/bash
for a in "$@"; do case "$a" in http*) echo "$a" >> "$PINGS" ;; esac; done
exit 0
EOF
# the limiter: `check` passes when LIMITED=1.
cat > "$TMP/egress" <<'EOF'
#!/bin/bash
[ "${LIMITED:-1}" = 1 ]
EOF
chmod +x "$TMP/bin/"* "$TMP/egress"
printf 'MEDIA_PING_URL="https://hc.example.invalid/ping"\n' > "$TMP/env"

# verdict <installed 0|1> — runs the check with the stubs; prints ok, or the /fail reason it gave.
verdict() {
  local bin="$TMP/egress"
  [ "$1" = 1 ] || bin="$TMP/absent"
  : > "$TMP/pings"
  PATH="$TMP/bin:$PATH" PINGS="$TMP/pings" MOYI_MONITORING_ENV="$TMP/env" MOYI_EGRESS_BIN="$bin" \
    bash "$CHECK" > "$TMP/out" 2>&1
  if grep -q '/fail$' "$TMP/pings"; then sed -n 's/^moyi-media-check: //p' "$TMP/out"; else echo ok; fi
}

echo "media-check: $CHECK"
v="$(WORKER=1 SERVICE=active LIMITED=1 verdict 1)"
[ "$v" = ok ] && pass "a worker, the limiter installed, active and limiting: ok" || fail "all in place: ok" "$v"
v="$(WORKER=1 SERVICE=active LIMITED=1 verdict 0)"
[[ "$v" == *"egress limiter is not installed"* ]] && pass "a worker, the limiter not installed: a problem ($v)" || fail "a worker, no limiter: a problem" "$v"
v="$(WORKER=1 SERVICE=inactive LIMITED=1 verdict 1)"
[[ "$v" == *"service is not active"* ]] && pass "a worker, the service stopped: a problem ($v)" || fail "a worker, the service stopped: a problem" "$v"
v="$(WORKER=1 SERVICE=active LIMITED=0 verdict 1)"
[[ "$v" == *"without its egress limit"* ]] && pass "a worker not limited: a problem ($v)" || fail "a worker not limited: a problem" "$v"
v="$(WORKER=0 SERVICE=inactive LIMITED=0 verdict 0)"
[ "$v" = ok ] && pass "no worker running, no limiter: ok" || fail "no worker, no limiter: ok" "$v"

echo; echo "test-media-check: $PASSED passed, $FAILED failed"
[ "$FAILED" = 0 ]
