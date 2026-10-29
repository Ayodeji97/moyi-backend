#!/usr/bin/env bash
# egress-limit.sh — limits where the media worker's container may connect to
# (spec R-E: "network limited to R2 and Postgres"; ADR-0052 decision 5).
# Runs as root on the server, from moyi-media-worker-egress.service; installed
# as /usr/local/sbin/moyi-media-worker-egress.
#
# Why here and not in config/deploy.yml: Kamal 2.12 starts every role's
# container on its `kamal` network (`--network kamal`, written into
# Kamal::Commands::App#run), a bridge with a route to the internet, and Docker
# has no setting that narrows where a container on a bridge may connect. So
# the limit is put inside the container's own network namespace, from the
# host: `nsenter --net` runs the host's iptables in it. The container has no
# capability (cap-drop ALL in deploy.yml), so nothing in it can change the
# rules, and they vanish with the container, so a new container's address can
# never inherit an old one's rules.
#
# What the worker may still reach, and nothing else:
#   - loopback (Docker's DNS resolver lives at 127.0.0.11 inside the namespace);
#   - TCP 5432 anywhere on the kamal network: the Postgres accessory, whose
#     address changes when it is rebooted, so the network rather than an address;
#   - TCP 443 to Cloudflare's published IPv4 ranges, where R2 is
#     (<account>.eu.r2.cloudflarestorage.com resolved into 141.101.64.0/18 on
#     2026-10-29). R2 has no range of its own: this admits every site behind
#     Cloudflare, not R2 alone (ADR-0052 says what that leaves open);
#   - anything in EXTRA_HTTPS_CIDRS (TCP 443), set in the env file: when
#     Rekognition is switched on, AWS's eu-central-1 ranges must be added here
#     or every moderation call fails (and is retried, as an outage).
# Everything else, IPv4 and IPv6, is refused (REJECT, so a client fails at once
# instead of hanging on a timeout).
#
# Usage:
#   moyi-media-worker-egress apply <container>   limit one container now
#   moyi-media-worker-egress watch               limit every worker container, now and as each starts
#   moyi-media-worker-egress check               exit 1 unless every running worker container is limited
#
# /etc/moyi-media-worker/egress.env (optional, root-only) may set EXTRA_HTTPS_CIDRS="a/b c/d".
set -euo pipefail

ENV_FILE=/etc/moyi-media-worker/egress.env
# shellcheck source=/dev/null
[ -r "$ENV_FILE" ] && . "$ENV_FILE"
EXTRA_HTTPS_CIDRS="${EXTRA_HTTPS_CIDRS:-}"
NETWORK="${MOYI_EGRESS_NETWORK:-kamal}"
CHAIN=MOYI-EGRESS
# The labels Kamal puts on the role's containers (Kamal::Configuration::Role#default_labels).
FILTERS=(--filter label=service=moyi --filter label=role=media-worker)

# https://www.cloudflare.com/ips-v4, read 2026-10-29. Cloudflare changes it rarely
# and announces it; re-read it when it does.
CLOUDFLARE_V4="173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22 141.101.64.0/18
108.162.192.0/18 190.93.240.0/20 188.114.96.0/20 197.234.240.0/22 198.41.128.0/17
162.158.0.0/15 104.16.0.0/13 104.24.0.0/14 172.64.0.0/13 131.0.72.0/22"

say() { echo "moyi-media-worker-egress: $*" >&2; }

cidr_ok() { [[ "$1" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$ ]]; }

in_netns() { # pid, then the command to run in that process's network namespace
  local pid=$1; shift
  nsenter --target "$pid" --net -- "$@"
}

apply() {
  local container=$1 pid subnet cidr
  pid=$(docker inspect --format '{{.State.Pid}}' "$container")
  [ "${pid:-0}" -gt 0 ] || { say "$container is not running"; return 1; }
  subnet=$(docker network inspect --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' "$NETWORK" | tr ' ' '\n' | grep -m1 -E '^[0-9.]+/[0-9]+$')
  cidr_ok "$subnet" || { say "cannot read the $NETWORK network's IPv4 subnet"; return 1; }

  # Built in a fresh chain, then swapped in, so the container is never without
  # its rules while they are rewritten.
  in_netns "$pid" iptables -w -N "$CHAIN-NEW" 2>/dev/null || in_netns "$pid" iptables -w -F "$CHAIN-NEW"
  in_netns "$pid" iptables -w -A "$CHAIN-NEW" -o lo -j ACCEPT
  in_netns "$pid" iptables -w -A "$CHAIN-NEW" -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
  in_netns "$pid" iptables -w -A "$CHAIN-NEW" -d "$subnet" -p tcp --dport 5432 -j ACCEPT
  for cidr in $CLOUDFLARE_V4 $EXTRA_HTTPS_CIDRS; do
    cidr_ok "$cidr" || { say "not an IPv4 range: $cidr"; return 1; }
    in_netns "$pid" iptables -w -A "$CHAIN-NEW" -d "$cidr" -p tcp --dport 443 -j ACCEPT
  done
  in_netns "$pid" iptables -w -A "$CHAIN-NEW" -p tcp -j REJECT --reject-with tcp-reset
  in_netns "$pid" iptables -w -A "$CHAIN-NEW" -j REJECT

  in_netns "$pid" iptables -w -I OUTPUT 1 -j "$CHAIN-NEW"
  if in_netns "$pid" iptables -w -n -L "$CHAIN" >/dev/null 2>&1; then
    while in_netns "$pid" iptables -w -D OUTPUT -j "$CHAIN" 2>/dev/null; do :; done
    in_netns "$pid" iptables -w -F "$CHAIN"
    in_netns "$pid" iptables -w -X "$CHAIN"
  fi
  in_netns "$pid" iptables -w -E "$CHAIN-NEW" "$CHAIN"

  # IPv6: nothing but loopback. The kamal network has no IPv6 today; this
  # keeps it so should that change.
  if in_netns "$pid" ip6tables -w -n -L OUTPUT >/dev/null 2>&1; then
    in_netns "$pid" ip6tables -w -F OUTPUT
    in_netns "$pid" ip6tables -w -A OUTPUT -o lo -j ACCEPT
    in_netns "$pid" ip6tables -w -A OUTPUT -j REJECT
  fi
  say "limited $container (pid $pid): Postgres on $subnet, HTTPS to Cloudflare${EXTRA_HTTPS_CIDRS:+ and $EXTRA_HTTPS_CIDRS}"
}

limited() { # container -> 0 if its namespace has the chain first in OUTPUT, ending in REJECT
  local pid
  pid=$(docker inspect --format '{{.State.Pid}}' "$1") || return 1
  in_netns "$pid" iptables -w -S OUTPUT | grep -q -- "-A OUTPUT -j $CHAIN" &&
    in_netns "$pid" iptables -w -S "$CHAIN" | tail -n 1 | grep -q -- "-j REJECT"
}

workers() { docker ps --quiet "${FILTERS[@]}"; }

case "${1:-}" in
  apply)
    [ -n "${2:-}" ] || { say "usage: $0 apply <container>"; exit 2; }
    apply "$2"
    ;;
  watch)
    # Subscribe first, then limit what already runs, so a container started in
    # between is in the stream rather than missed.
    exec 3< <(docker events --filter type=container --filter event=start "${FILTERS[@]}" --format '{{.ID}}')
    for c in $(workers); do apply "$c" || say "could not limit $c"; done
    while read -r c <&3; do apply "$c" || say "could not limit $c"; done
    say "docker events ended"; exit 1
    ;;
  check)
    bad=0
    for c in $(workers); do limited "$c" || { say "$c is not limited"; bad=1; }; done
    exit "$bad"
    ;;
  *)
    say "usage: $0 apply <container> | watch | check"; exit 2
    ;;
esac
