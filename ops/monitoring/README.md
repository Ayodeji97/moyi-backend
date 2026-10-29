# Monitoring

Five checks, each of which emails the owner when it fails. None needs anything
installed on the server beyond what is there.

| Check | Where | Alerts when |
|---|---|---|
| `https://api.getmoyi.app/healthz` answers `UP` | UptimeRobot, every 5 min | the API is down, or its TLS certificate is about to expire |
| `https://getmoyi.app/` answers | UptimeRobot, every 5 min | the link pages are down |
| the nightly backup checked in | Healthchecks.io, period 1 day, grace 2 h | a night passes with no successful backup (`backup.sh` pings `PING_URL`) |
| the close job is running | Healthchecks.io, period 1 hour, grace 30 min | `close-check.sh` reports a problem (at once, through `/fail`), or stops reporting at all |
| the media pipeline is moving | Healthchecks.io, period 15 min, grace 30 min | `media-check.sh` finds a photo PROCESSING for 30 minutes (doc 11:97, P2), a deleted asset unpurged after a day, or a media job that stopped running; or stops reporting. What to do: [media-pipeline-stuck.md](media-pipeline-stuck.md) |

The last three are heartbeats: the server reports in, and **silence is the
alarm**. That catches what an outside probe cannot see: a backup that quietly
did not happen, a job that stopped while the API still answers.

## Installing the close-job check

From the Mac, in `moyi-backend`:

```
scp ops/monitoring/close-check.sh moyi:/usr/local/sbin/moyi-close-check
scp ops/monitoring/moyi-close-check.service ops/monitoring/moyi-close-check.timer moyi:/etc/systemd/system/
ssh moyi 'chmod 700 /usr/local/sbin/moyi-close-check && install -d -m 700 /etc/moyi-monitoring && systemctl daemon-reload'
```

The ping URLs come from Healthchecks.io (one per check) and are kept in
Bitwarden (`moyi-prod`: `HC_BACKUP_URL`, `HC_CLOSE_URL`). Whoever has one can
report "all well", so they are root-only on the server (`backup.env` is already
600; the `sed` makes a re-run replace the line rather than add a second one):

```
export BW_SESSION="$(bw unlock --raw)"
# The CLI reads a local copy of the vault: a field added in the web vault since
# the last sync is not there, and f() then writes an empty value.
bw sync >/dev/null
f() { bw get item moyi-prod | python3 -c 'import json,sys; print(next(x["value"] for x in json.load(sys.stdin)["fields"] if x["name"]==sys.argv[1]))' "$1"; }
printf 'CLOSE_PING_URL="%s"\n' "$(f HC_CLOSE_URL)" | ssh moyi 'install -m 600 /dev/stdin /etc/moyi-monitoring/heartbeats.env'
printf 'PING_URL="%s"\n' "$(f HC_BACKUP_URL)" | ssh moyi 'sed -i "/^PING_URL=/d" /etc/moyi-backup/backup.env && cat >> /etc/moyi-backup/backup.env'
bw lock; unset BW_SESSION
ssh moyi 'systemctl start moyi-close-check; journalctl -u moyi-close-check -n 3 --no-pager'
ssh moyi 'systemctl enable --now moyi-close-check.timer'
```

## Proving an alert reaches you

A check nobody has seen fire is not known to work. Once, after installing:
pause the close-check heartbeat's schedule in Healthchecks.io, or stop the
timer for an hour and a half, and confirm the email arrives; then put it back.

## Installing the media check

As the close-job check, with its own Healthchecks.io check (period 15 minutes, grace 30)
and its ping URL in Bitwarden as `HC_MEDIA_URL`:

```
scp ops/monitoring/media-check.sh moyi:/usr/local/sbin/moyi-media-check
scp ops/monitoring/moyi-media-check.service ops/monitoring/moyi-media-check.timer moyi:/etc/systemd/system/
ssh moyi 'chmod 700 /usr/local/sbin/moyi-media-check && systemctl daemon-reload'
export BW_SESSION="$(bw unlock --raw)"; bw sync >/dev/null
printf 'MEDIA_PING_URL="%s"\n' "$(f HC_MEDIA_URL)" | ssh moyi 'sed -i "/^MEDIA_PING_URL=/d" /etc/moyi-monitoring/heartbeats.env && cat >> /etc/moyi-monitoring/heartbeats.env'
bw lock; unset BW_SESSION
ssh moyi 'systemctl start moyi-media-check; journalctl -u moyi-media-check -n 3 --no-pager'
ssh moyi 'systemctl enable --now moyi-media-check.timer'
```

(`f` is the helper defined above.) It needs the media tables (V32, V34) and `job_runs` (V35) on the server, so it
is installed after the deploy that carries them. With the storage `unavailable`, as
production runs today, no asset exists and the check reports ok as long as both jobs run.
