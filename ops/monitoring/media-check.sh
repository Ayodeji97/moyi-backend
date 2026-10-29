#!/usr/bin/env bash
# media-check.sh — the media pipeline's alarm (doc 11:97, P2: "any asset in
# PROCESSING > 30 min"; spec Phase 4 §4 D5; ADR-0050). Runs every quarter-hour
# as root on the server, from moyi-media-check.timer; installed as
# /usr/local/sbin/moyi-media-check. What to do when it fires:
# ops/monitoring/media-pipeline-stuck.md.
#
# Four things, all read from the database:
#   - no photo has been PROCESSING for more than 30 minutes. `media.process`
#     marks a photo PROCESSING in a transaction of its own as an attempt
#     begins, and nothing leaves it there for long but a worker that is dead,
#     stuck, or retrying an outage. Measured from created_at: the row keeps no
#     time of the attempt's start, and a photo uploaded more than 30 minutes
#     ago and still PROCESSING is stuck either way;
#   - no asset has been DELETED for more than a day without `media.purge`
#     having deleted its objects (purged_at). An erasure is a privacy rule: a
#     purge that keeps failing is retried for ever, and this is how somebody
#     hears that it is failing;
#   - the reaper last FINISHED a run within the last two hours (it runs
#     hourly), and the demotion within the last 26 (it runs nightly), as the
#     jobs themselves record in `job_runs` (V35) once a run has ended without
#     throwing. Not ShedLock's `locked_at`: that is taken as a run begins, so a
#     job that throws on every run would look alive. A job that has begun
#     (ShedLock has its row) and never finished counts as late; one that has
#     never begun (a fresh database, the demotion before its first 03:40) does
#     not.
#
# All well: it pings the heartbeat. Anything wrong: it pings the heartbeat's
# /fail URL with the reason, which alerts at once. If the box, Docker or
# Postgres is down no ping arrives, and the heartbeat's grace period alerts:
# silence is the alarm.
#
# Counts only, never an id: the ids are in the database for whoever follows
# the runbook, and a heartbeat's body is not the place for them.
#
# /etc/moyi-monitoring/heartbeats.env (root-only) holds MEDIA_PING_URL.
set -euo pipefail

ENV_FILE=/etc/moyi-monitoring/heartbeats.env
die() { echo "moyi-media-check: $*" >&2; exit 1; }
[ -r "$ENV_FILE" ] || die "$ENV_FILE is missing"
# shellcheck source=/dev/null
. "$ENV_FILE"
: "${MEDIA_PING_URL:?set in $ENV_FILE}"

stuck="" unpurged="" reaper_late="" demotion_late=""
read -r stuck unpurged reaper_late demotion_late < <(docker exec moyi-postgres psql -U moyi -d moyi -AtF ' ' -c "
  SELECT
    (SELECT count(*) FROM media_assets WHERE status = 'PROCESSING' AND created_at < now() - interval '30 minutes'),
    (SELECT count(*) FROM media_assets WHERE status = 'DELETED' AND purged_at IS NULL AND deleted_at < now() - interval '1 day'),
    (SELECT count(*) FROM shedlock s LEFT JOIN job_runs r ON r.name = s.name
       WHERE s.name = 'media-reaper' AND (r.last_success_at IS NULL OR r.last_success_at < now() - interval '2 hours')),
    (SELECT count(*) FROM shedlock s LEFT JOIN job_runs r ON r.name = s.name
       WHERE s.name = 'media-demotion' AND (r.last_success_at IS NULL OR r.last_success_at < now() - interval '26 hours'))
") || true
[ -n "$demotion_late" ] || die "could not read the database"

problem=""
[ "$stuck" -eq 0 ] || problem="$stuck photos PROCESSING for more than 30 minutes"
[ "$unpurged" -eq 0 ] || problem="${problem:+$problem; }$unpurged deleted assets not purged after a day"
[ "$reaper_late" -eq 0 ] || problem="${problem:+$problem; }the media reaper has not finished a run for two hours"
[ "$demotion_late" -eq 0 ] || problem="${problem:+$problem; }the media demotion has not finished a run for 26 hours"

if [ -z "$problem" ]; then
  curl --fail --silent --max-time 10 --retry 3 "$MEDIA_PING_URL" >/dev/null
  echo "moyi-media-check: ok (nothing stuck, nothing unpurged, both jobs running)"
else
  curl --fail --silent --max-time 10 --retry 3 --data-raw "$problem" "$MEDIA_PING_URL/fail" >/dev/null || true
  die "$problem"
fi
