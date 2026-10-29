#!/usr/bin/env bash
# test-egress-limit.sh — proves what egress-limit.sh does, inside a throwaway
# Docker-in-Docker host (a Linux kernel with iptables and a Docker daemon of its
# own, as the server has). Runs on the Mac (colima start); publishes no port and
# touches nothing outside the one privileged container it starts,
# moyi-egress-test-<random>, which it removes.
#
#   ops/media-worker/test-egress-limit.sh
#
# Inside, as Kamal lays out the server: a `kamal` network, a stand-in
# `moyi-postgres` listening on 5432, a stand-in `moyi-valkey` on 6379, and a
# worker container labelled as Kamal labels the role (service=moyi,
# role=media-worker) with no capability. It checks:
#   a. before the limit, the worker reaches Valkey and the internet;
#   b. `watch` limits a worker that was already running: Postgres on 5432 is
#      reachable; Valkey, an address outside Cloudflare on 443 and Cloudflare on
#      80 are refused; R2's address on 443 is not refused by the limit;
#   c. `watch` limits a worker started after it, by itself;
#   d. inside the worker the rules cannot be removed (no capability);
#   e. `check` passes while every worker is limited, and fails for one that is not;
#   f. an apply that fails part-way (a range iptables refuses) applies nothing,
#      exits non-zero and never says "limited";
#   g. applying twice leaves one jump and one set of rules, not two;
#   h. a worker container on the host's network is refused, and the host's own
#      rules are left as they were.
# Prints PASS or FAIL for each and exits non-zero if any failed.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
DIND=docker:28-dind@sha256:2a232a42256f70d78e3cc5d2b5d6b3276710a0de0596c145f627ecfae90282ac
NAME="moyi-egress-test-$RANDOM"
docker info >/dev/null 2>&1 || { echo "docker is not running (colima start)" >&2; exit 1; }

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

failed=0
pass() { echo "PASS $1"; }
fail() { echo "FAIL $1"; failed=1; }
in_host() { docker exec "$NAME" "$@"; }
# Exit 0 when the worker opens a TCP connection to $2:$3 within 3 s.
reach() { in_host docker exec "$1" nc -z -w 3 "$2" "$3" >/dev/null 2>&1; }
# The answer is a refusal at once (the REJECT), not a time-out: under 2 s and a failure.
refused() {
  local start end
  start=$(date +%s)
  if reach "$@"; then return 1; fi
  end=$(date +%s)
  [ $((end - start)) -le 2 ]
}

docker run -d --privileged --name "$NAME" "$DIND" >/dev/null
for _ in $(seq 1 60); do in_host docker info >/dev/null 2>&1 && break; sleep 1; done
in_host apk add --no-cache bash util-linux >/dev/null
docker cp "$DIR/egress-limit.sh" "$NAME:/usr/local/sbin/moyi-media-worker-egress"

in_host docker network create kamal >/dev/null
in_host docker run -d --name moyi-postgres --network kamal busybox:1.37 nc -lk -p 5432 -e cat >/dev/null
in_host docker run -d --name moyi-valkey --network kamal busybox:1.37 nc -lk -p 6379 -e cat >/dev/null
worker() {
  in_host docker run -d --name "$1" --network kamal --cap-drop ALL --security-opt no-new-privileges \
    --label service=moyi --label role=media-worker busybox:1.37 sleep 3600 >/dev/null
}
worker moyi-media-worker-old

# R2's EU endpoint, as resolved on 2026-10-29 (in Cloudflare's 141.101.64.0/18), and a
# well-known address outside every Cloudflare range (Quad9).
R2=141.101.90.96 OUTSIDE=9.9.9.9

# a.
if reach moyi-media-worker-old moyi-valkey 6379 && reach moyi-media-worker-old "$OUTSIDE" 443; then
  pass "a. unlimited, the worker reaches Valkey and the internet"
else
  fail "a. the unlimited worker could not reach Valkey or $OUTSIDE:443 (no internet in the test host?)"
fi

# b.
in_host bash -c 'nohup moyi-media-worker-egress watch >/tmp/watch.log 2>&1 &'
for _ in $(seq 1 20); do in_host grep -q "limited" /tmp/watch.log 2>/dev/null && break; sleep 0.5; done
w=moyi-media-worker-old
if reach $w moyi-postgres 5432; then pass "b. limited, the worker reaches Postgres on 5432"; else fail "b. limited, Postgres unreachable"; fi
if refused $w moyi-valkey 6379; then pass "b. limited, Valkey is refused"; else fail "b. limited, Valkey not refused"; fi
if refused $w "$OUTSIDE" 443; then pass "b. limited, $OUTSIDE:443 (outside Cloudflare) is refused"; else fail "b. $OUTSIDE:443 not refused"; fi
if refused $w "$R2" 80; then pass "b. limited, Cloudflare on port 80 is refused"; else fail "b. $R2:80 not refused"; fi
if reach $w "$R2" 443; then pass "b. limited, R2's address on 443 is reached"; else fail "b. $R2:443 not reached"; fi

# c.
worker moyi-media-worker-new
for _ in $(seq 1 20); do [ "$(in_host grep -c "limited" /tmp/watch.log)" -ge 2 ] && break; sleep 0.5; done
if refused moyi-media-worker-new moyi-valkey 6379 && reach moyi-media-worker-new moyi-postgres 5432; then
  pass "c. a worker started after watch is limited by it"
else
  fail "c. the new worker was not limited"
fi

# d.
if in_host docker exec moyi-media-worker-new iptables -F >/dev/null 2>&1 \
  || in_host docker exec moyi-media-worker-new sh -c 'echo > /proc/sys/net/ipv4/ip_forward' >/dev/null 2>&1; then
  fail "d. the worker could change its network settings"
else
  pass "d. the worker cannot change its own network settings"
fi

# e.
if in_host moyi-media-worker-egress check >/dev/null 2>&1; then pass "e. check passes while every worker is limited"; else fail "e. check failed with every worker limited"; fi
in_host pkill -f "moyi-media-worker-egress watch" || true
in_host pkill -f "docker events" || true
worker moyi-media-worker-unlimited
if in_host moyi-media-worker-egress check >/dev/null 2>&1; then fail "e. check passed with a worker unlimited"; else pass "e. check fails for a worker that is not limited"; fi

# f. 300.0.0.0/8 passes the script's own shape check and is refused by iptables.
pid=$(in_host docker inspect --format '{{.State.Pid}}' moyi-media-worker-unlimited)
out=$(in_host env EXTRA_HTTPS_CIDRS=300.0.0.0/8 moyi-media-worker-egress apply moyi-media-worker-unlimited 2>&1) && status=0 || status=$?
rules=$(in_host nsenter --target "$pid" --net -- iptables -S | grep -c MOYI-EGRESS || true)
if [ "$status" != 0 ] && ! grep -q "limited moyi" <<<"$out" && [ "$rules" = 0 ] && reach moyi-media-worker-unlimited moyi-valkey 6379; then
  pass "f. a failed apply applies nothing, exits $status and does not say limited"
else
  fail "f. failed apply: status=$status, MOYI-EGRESS lines=$rules, said: $out"
fi

# g.
in_host moyi-media-worker-egress apply moyi-media-worker-unlimited >/dev/null 2>&1
in_host moyi-media-worker-egress apply moyi-media-worker-unlimited >/dev/null 2>&1
jumps=$(in_host nsenter --target "$pid" --net -- iptables -S OUTPUT | grep -c -- "-j MOYI-EGRESS" || true)
rejects=$(in_host nsenter --target "$pid" --net -- iptables -S MOYI-EGRESS | grep -c -- "-j REJECT" || true)
if [ "$jumps" = 1 ] && [ "$rejects" = 2 ] && refused moyi-media-worker-unlimited moyi-valkey 6379; then
  pass "g. applied twice: one jump, one set of rules, still limited"
else
  fail "g. applied twice: $jumps jumps, $rejects REJECT rules"
fi

# h.
in_host docker run -d --name moyi-media-worker-hostnet --network host --label service=moyi --label role=media-worker \
  busybox:1.37 sleep 3600 >/dev/null
before=$(in_host iptables -S | md5sum)
if in_host moyi-media-worker-egress apply moyi-media-worker-hostnet >/dev/null 2>&1; then
  fail "h. apply acted on a container in the host's network namespace"
elif [ "$(in_host iptables -S | md5sum)" = "$before" ]; then
  pass "h. a container on the host's network is refused, the host's rules untouched"
else
  fail "h. the host's rules changed"
fi

exit "$failed"
