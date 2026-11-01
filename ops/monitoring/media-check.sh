#!/usr/bin/env bash
# media-check.sh — the media pipeline's alarm (doc 11:97, P2: "any asset in
# PROCESSING > 30 min"; spec Phase 4 §4 D5; ADR-0050). Runs every quarter-hour
# as root on the server, from moyi-media-check.timer; installed as
# /usr/local/sbin/moyi-media-check. What to do when it fires:
# ops/monitoring/media-pipeline-stuck.md.
#
# Nine things, the first eight read from the database:
#   - no upload has waited UPLOADED for more than 30 minutes: nothing has
#     taken it up, which since ADR-0052 means the media worker is down or
#     not deployed, or its outbox poller is stuck. Docker's health check
#     only says the worker started; this says it is working. Measured from
#     created_at, as the next check is: the row keeps no time of the
#     completion, and a running worker takes an upload up within seconds;
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
#     not;
#   - nothing is held as evidence (ADR-0051, Q16): child sexual abuse
#     material that moderation blocked waits, served to nobody, kept whoever
#     deleted it, for the operator to report it and then delete it
#     (ops/moderation/takedown.md). A held asset is not "unpurged" above;
#   - while a moderator is configured (MEDIA_MODERATION_ON=1 in the env file:
#     the media worker runs one), nothing has been READY for 30 minutes
#     without a verdict (FR-101: a partner sees nothing until moderation has
#     passed it; doc 11's 30-minute bound on the pipeline). With no moderator
#     every asset waits, by design, and this is not checked;
#   - nothing moderation held back (QUARANTINED, not blocked) has waited for
#     the operator's review for more than QUARANTINE_REVIEW_DAYS (3 unless
#     set): the runbook's section 1 asks for a look once a day.
#     Fails at once, every run, until that is done: it is the one thing in
#     this check that a person must act on the same day;
#   - while a media worker container runs, its egress limit is installed
#     (/usr/local/sbin/moyi-media-worker-egress, ops/media-worker/README.md),
#     its service is active, and every worker container is limited by it
#     (ADR-0052): a worker started while the limiter was missing or down
#     reaches the whole internet, and this is how somebody hears.
#
# All well: it pings the heartbeat. Anything wrong: it pings the heartbeat's
# /fail URL with the reason, which alerts at once. If the box, Docker or
# Postgres is down no ping arrives, and the heartbeat's grace period alerts:
# silence is the alarm.
#
# Counts only, never an id: the ids are in the database for whoever follows
# the runbook, and a heartbeat's body is not the place for them.
#
# /etc/moyi-monitoring/heartbeats.env (root-only) holds MEDIA_PING_URL, and
# may set MEDIA_MODERATION_ON=1 and QUARANTINE_REVIEW_DAYS.
set -euo pipefail

ENV_FILE="${MOYI_MONITORING_ENV:-/etc/moyi-monitoring/heartbeats.env}" # the variable for ops/monitoring/test-media-check.sh only
die() { echo "moyi-media-check: $*" >&2; exit 1; }
[ -r "$ENV_FILE" ] || die "$ENV_FILE is missing"
# shellcheck source=/dev/null
. "$ENV_FILE"
: "${MEDIA_PING_URL:?set in $ENV_FILE}"
MEDIA_MODERATION_ON="${MEDIA_MODERATION_ON:-0}"
QUARANTINE_REVIEW_DAYS="${QUARANTINE_REVIEW_DAYS:-3}"
[[ "$MEDIA_MODERATION_ON" =~ ^[01]$ ]] || die "MEDIA_MODERATION_ON must be 0 or 1"
[[ "$QUARANTINE_REVIEW_DAYS" =~ ^[0-9]+$ ]] || die "QUARANTINE_REVIEW_DAYS must be a whole number of days"

waiting="" stuck="" unpurged="" reaper_late="" demotion_late="" blocked="" unmoderated="" unreviewed=""
read -r waiting stuck unpurged reaper_late demotion_late blocked unmoderated unreviewed < <(docker exec moyi-postgres psql -U moyi -d moyi -AtF ' ' -c "
  SELECT
    (SELECT count(*) FROM media_assets WHERE status = 'UPLOADED' AND created_at < now() - interval '30 minutes'),
    (SELECT count(*) FROM media_assets WHERE status = 'PROCESSING' AND created_at < now() - interval '30 minutes'),
    (SELECT count(*) FROM media_assets WHERE status = 'DELETED' AND purged_at IS NULL AND NOT evidence_held
       AND deleted_at < now() - interval '1 day'),
    (SELECT count(*) FROM shedlock s LEFT JOIN job_runs r ON r.name = s.name
       WHERE s.name = 'media-reaper' AND (r.last_success_at IS NULL OR r.last_success_at < now() - interval '2 hours')),
    (SELECT count(*) FROM shedlock s LEFT JOIN job_runs r ON r.name = s.name
       WHERE s.name = 'media-demotion' AND (r.last_success_at IS NULL OR r.last_success_at < now() - interval '26 hours')),
    (SELECT count(*) FROM media_assets WHERE evidence_held),
    (SELECT count(*) FROM media_assets WHERE $MEDIA_MODERATION_ON = 1 AND status = 'READY' AND moderation_verdict IS NULL
       AND ready_at < now() - interval '30 minutes'),
    (SELECT count(*) FROM media_assets WHERE status = 'QUARANTINED' AND moderation_verdict = 'QUARANTINED'
       AND moderated_at < now() - make_interval(days => $QUARANTINE_REVIEW_DAYS))
") || true
[ -n "$unreviewed" ] || die "could not read the database"

problem=""
[ "$waiting" -eq 0 ] || problem="$waiting uploads waiting UPLOADED for more than 30 minutes (is the media worker running?)"
[ "$stuck" -eq 0 ] || problem="${problem:+$problem; }$stuck photos PROCESSING for more than 30 minutes"
[ "$unpurged" -eq 0 ] || problem="${problem:+$problem; }$unpurged deleted assets not purged after a day"
[ "$reaper_late" -eq 0 ] || problem="${problem:+$problem; }the media reaper has not finished a run for two hours"
[ "$demotion_late" -eq 0 ] || problem="${problem:+$problem; }the media demotion has not finished a run for 26 hours"
[ "$blocked" -eq 0 ] || problem="${problem:+$problem; }$blocked blocked assets await the takedown runbook (ops/moderation/takedown.md)"
[ "$unmoderated" -eq 0 ] || problem="${problem:+$problem; }$unmoderated ready assets without a verdict for more than 30 minutes"
[ "$unreviewed" -eq 0 ] || problem="${problem:+$problem; }$unreviewed held-back assets unreviewed for more than $QUARANTINE_REVIEW_DAYS days"
# A running worker container must be limited (ADR-0052): the limiter
# installed, its service active, and every worker container limited. Missing
# or stopped is as bad as not limiting (review of #117): it was silent before.
EGRESS="${MOYI_EGRESS_BIN:-/usr/local/sbin/moyi-media-worker-egress}"
if [ -n "$(docker ps -q --filter label=service=moyi --filter label=role=media-worker)" ]; then
  if [ ! -x "$EGRESS" ]; then
    problem="${problem:+$problem; }a media worker runs and its egress limiter is not installed"
  elif ! systemctl is-active --quiet moyi-media-worker-egress; then
    problem="${problem:+$problem; }a media worker runs and the moyi-media-worker-egress service is not active"
  elif ! "$EGRESS" check 2>/dev/null; then
    problem="${problem:+$problem; }a media worker container runs without its egress limit"
  fi
fi

if [ -z "$problem" ]; then
  curl --fail --silent --max-time 10 --retry 3 "$MEDIA_PING_URL" >/dev/null
  echo "moyi-media-check: ok (nothing stuck, unpurged, blocked, unmoderated or unreviewed; both jobs running)"
else
  curl --fail --silent --max-time 10 --retry 3 --data-raw "$problem" "$MEDIA_PING_URL/fail" >/dev/null || true
  die "$problem"
fi
