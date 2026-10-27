#!/usr/bin/env bash
# close-check.sh — the close job's dead-man switch (doc 11; ADR-0033). Runs
# hourly as root on the server, from moyi-close-check.timer; installed as
# /usr/local/sbin/moyi-close-check.
#
# The close job settles every Bond-day a minute after it ends, every fifteen
# minutes. Two things say it is alive, both read from the database:
#   - ShedLock's row for `close-days` was taken within the last 30 minutes
#     (the job takes it on every run, on whichever instance runs it);
#   - no day that ended more than 30 minutes ago is still unclosed.
# Both true: it pings the heartbeat. Either false: it pings the heartbeat's
# /fail URL with the reason, which alerts at once. If the box, Docker or
# Postgres is down, no ping arrives at all and the heartbeat's grace period
# alerts instead: silence is the alarm.
#
# /etc/moyi-monitoring/heartbeats.env (root-only) holds CLOSE_PING_URL. A ping
# URL is a credential of sorts: whoever has it can report "all well".
set -euo pipefail

ENV_FILE=/etc/moyi-monitoring/heartbeats.env
die() { echo "moyi-close-check: $*" >&2; exit 1; }
[ -r "$ENV_FILE" ] || die "$ENV_FILE is missing"
# shellcheck source=/dev/null
. "$ENV_FILE"
: "${CLOSE_PING_URL:?set in $ENV_FILE}"

lock_age="" unclosed=""
read -r lock_age unclosed < <(docker exec moyi-postgres psql -U moyi -d moyi -AtF ' ' -c "
  SELECT
    coalesce((SELECT extract(epoch FROM now() - locked_at)::int FROM shedlock WHERE name = 'close-days'), 999999),
    (SELECT count(*) FROM bond_days WHERE closed_at IS NULL AND ends_at < now() - interval '30 minutes')
") || true
[ -n "$unclosed" ] || die "could not read the database"

problem=""
[ "$lock_age" -le 1800 ] || problem="the close job last ran ${lock_age}s ago"
[ "$unclosed" -eq 0 ] || problem="${problem:+$problem; }$unclosed ended days are still unclosed"

if [ -z "$problem" ]; then
  curl --fail --silent --max-time 10 --retry 3 "$CLOSE_PING_URL" >/dev/null
  echo "moyi-close-check: ok (last run ${lock_age}s ago, no ended day unclosed)"
else
  curl --fail --silent --max-time 10 --retry 3 --data-raw "$problem" "$CLOSE_PING_URL/fail" >/dev/null || true
  die "$problem"
fi
