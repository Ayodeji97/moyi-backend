# Runbook: the media pipeline is stuck

Doc 11:111 asks for this page; doc 11:97 for the alarm that sends you here (P2). The alarm
is `media-check.sh`, a Healthchecks.io heartbeat every quarter-hour (ADR-0050). Its email
says which of the things it checks is wrong. Each section below starts from one of them.

Nothing here needs an id in a chat, an issue or a log: read them in `psql` on the server,
act, and write down the counts. A storage key is never pasted anywhere: with the bucket's
credentials it reaches the bytes.

```
ssh moyi
docker exec -it moyi-postgres psql -U moyi -d moyi
```

## "N uploads waiting UPLOADED for more than 30 minutes"

Nothing took the upload up: the media worker (the `media-worker` role, ADR-0052) is down, was
never deployed, or its outbox poller is stuck. The API never processes (`"off"`).

1. **Is it running and healthy?** `ssh moyi 'docker ps --filter label=role=media-worker'`:
   `Up … (healthy)`. Not there, or `Restarting`: `kamal app logs -r media-worker --since 1h`
   says why (a start-up check of libvips or ffmpeg that failed, the database, or the JVM out of
   memory: `-XX:+ExitOnOutOfMemoryError` ends it and Docker starts it again).
2. **Running but idle?** Its log should say `Event consumer registered: consumer=media.process
   subscribed=[MediaUploaded]` on its first start (later starts say `subscribed=[]`, the row
   already exists). The query in step 2 of the next section shows whether `media.process`'s
   deliveries are being attempted.
3. **Restarting it** is `kamal app boot -r media-worker` (the same version); the uploads wait,
   nothing is lost.

Not a fault: the API logs `Event consumer has subscriptions this build does not declare, and
they are kept: consumer=media.process undeclared=[MediaUploaded]` (and the same for the
moderation consumers once they subscribe) on every start. The worker registered those
subscriptions; the API, with processing off, declares none and leaves them alone
(`ConsumerRegistry`, by design).

## "a media worker container runs without its egress limit"

`moyi-media-worker-egress check` found a worker container whose network namespace lacks the
limit (ADR-0052 decision 5): it was started while the service was down. `systemctl status
moyi-media-worker-egress`; start it (`systemctl restart moyi-media-worker-egress`), which limits
every running worker at once; `check` again. Then find why it was down (`journalctl -u
moyi-media-worker-egress`).

## "N photos PROCESSING for more than 30 minutes"

`media.process` marks a photo `PROCESSING` as an attempt begins, in a transaction of its
own, and leaves it there only while the attempt runs or while the outbox is retrying one
that failed for a reason that is not the photo's (the storage, the database).

1. **Is a worker running at all?** Photos are processed only where
   `MOYI_MEDIA_PROCESSING_PROVIDER` is `native` (the media worker, ADR-0052). With none
   running, a photo stays `UPLOADED`, never `PROCESSING`, so this alarm means a worker
   began and did not finish.
2. **What does the outbox say?**

   ```sql
   SELECT d.attempts, d.last_error, d.next_attempt_at, d.processed_at
   FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
   WHERE d.consumer_id = 'media.process' AND d.processed_at IS NULL
   ORDER BY d.next_attempt_at;
   ```

   - `last_error` names a class only (ADR-0035): `ObjectStoreUnavailableException` is the
     storage (R2 down, a token revoked or expired: check the R2 dashboard and the token in
     Bitwarden); a SQL exception is the database. Each retry waits at most 15 minutes. Once
     the cause is mended the next attempt makes the photo; nothing else is needed.
   - No undelivered row but photos still `PROCESSING`: an attempt was killed mid-decode (the
     container's memory limit, a deploy). The delivery was rolled back and is due again; if
     `next_attempt_at` is past and nothing moves, the worker is not polling: check its
     container (`docker ps`, `docker logs --since 1h <worker>`; the log names media ids and
     the rule that refused a photo, never anything the photo said).
3. **One photo that kills the worker every time** is given up after five decodes
   (`MediaAsset.MAX_ATTEMPTS`, `processing_attempts`): it becomes `FAILED` on the next
   delivery, the reaper deletes it a day later and `media.purge` its objects. Nothing to do
   but watch `processing_attempts` climb to 5.
4. **Re-driving processing** is never an UPDATE of `status`: set the stuck delivery due now
   and let the outbox take it.

   ```sql
   UPDATE outbox_deliveries SET next_attempt_at = now()
   WHERE consumer_id = 'media.process' AND processed_at IS NULL;
   ```

## "N deleted assets not purged after a day"

An asset is `DELETED` (erased with its entry, reaped, deleted by its owner) and
`media.purge` has not yet deleted its objects. **This is a privacy matter**: an erased
photo's objects are still in the bucket, and a URL handed out before the erasure keeps
working until it expires (at most 25 hours).

```sql
SELECT d.attempts, d.last_error, d.next_attempt_at
FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
WHERE d.consumer_id = 'media.purge' AND d.processed_at IS NULL
ORDER BY d.next_attempt_at;
```

- **Rows with `ObjectStoreUnavailableException`**: the storage refuses. Mend it (R2's status
  page; the token's scope is Object Read & Write on `moyi-media`, ADR-0047). The purge retries
  every 15 minutes for ever and catches up by itself.
- **No rows at all**: `media.purge` is not subscribed in this process. It subscribes only where
  a storage is configured (`MOYI_MEDIA_STORAGE_PROVIDER` is not `unavailable`). With the
  storage configured and the app restarted, it subscribes and is handed every release ever
  published (`StartFrom.BEGINNING`).
- **R2 consistency check**, once the purge has caught up: no `DELETED` asset may keep an
  object. For a handful of assets, check by hand with the read-only drill token that
  `HEAD v/<key part>/full.webp` is a 404 (the key's part is `storage_key` without `in/`);
  do it in a shell on the server, never paste the key elsewhere.

## "the media reaper has not finished a run for two hours" / "the media demotion has not finished a run for 26 hours"

The jobs run in the app under ShedLock (`media-reaper` hourly at :23, `media-demotion` at 03:40
UTC), and each records in `job_runs` when a run **finished** without throwing. The alarm reads
that, not ShedLock's row, which is taken as a run begins. So either the app is down
(UptimeRobot will have said so), or the job starts and throws on every run:

```sql
SELECT s.name, s.locked_at AS last_began, r.last_success_at AS last_finished
FROM shedlock s LEFT JOIN job_runs r ON r.name = s.name
WHERE s.name IN ('media-reaper', 'media-demotion');
```

`last_began` recent and `last_finished` old is a job throwing. Its log line says why:

```
docker logs --since 3h <app container> 2>&1 | grep -i 'media \(reaper\|demotion\)'
```

A lock held by an instance that died is released after `lockAtMostFor` (50 minutes): no
action. The jobs touch only the database, so the storage being down never stops them.

## The storage was configured, and is now `unavailable` again

Someone has put `MOYI_MEDIA_STORAGE_PROVIDER: unavailable` back in `config/deploy.yml` after R2
was configured (to stop uploads in an incident, say). What then happens, and what to do:

- **Uploads answer `503 MEDIA_STORAGE_UNAVAILABLE`**, and every photo, on an entry or on
  `GET /media/{id}`, is shown `PROCESSING` with no URL: none can be signed. Nothing is lost.
- **`media.purge` declares no event type in this build**, so it claims nothing. Its subscription
  row stays (a start-up never removes one, ADR-0035), so every erasure, reaping and demotion goes
  on writing `media.purge` deliveries, and they wait, unclaimed. After a day the alarm says
  "deleted assets not purged after a day": **that is expected in this state, and it is a privacy
  debt**: erased photos' objects stay in the bucket, and URLs handed out before keep working until
  they expire (25 hours at most).
- **The reaper and the demotion go on**, as they touch only the database.
- **To pay the debt**, configure the storage again and deploy: `media.purge` claims its waiting
  deliveries at once, oldest first, and the alarm clears once `purged_at` is set on all of them.
  If the bucket itself is being given up, the objects go with it; then mark them by hand
  (`UPDATE media_assets SET purged_at = now() WHERE status = 'DELETED' AND purged_at IS NULL`) and
  write down why.

## After any of these

Write in the learning log what it was and what mended it. An alarm that fired and was mended
by waiting is still a line: the next one to read it will want to know it happens.
