# Runbook: the media pipeline is stuck

Doc 11:111 asks for this page; doc 11:97 for the alarm that sends you here (P2). The alarm
is `media-check.sh`, a Healthchecks.io heartbeat every quarter-hour (ADR-0050). Its email
says which of four things is wrong. Each section below starts from one of them.

Nothing here needs an id in a chat, an issue or a log: read them in `psql` on the server,
act, and write down the counts. A storage key is never pasted anywhere: with the bucket's
credentials it reaches the bytes.

```
ssh moyi
docker exec -it moyi-postgres psql -U moyi -d moyi
```

## "N photos PROCESSING for more than 30 minutes"

`media.process` marks a photo `PROCESSING` as an attempt begins, in a transaction of its
own, and leaves it there only while the attempt runs or while the outbox is retrying one
that failed for a reason that is not the photo's (the storage, the database).

1. **Is a worker running at all?** Photos are processed only where
   `MOYI_MEDIA_PROCESSING_PROVIDER` is `vips` (the media worker, ADR-0048). With none
   deployed, a photo stays `UPLOADED`, never `PROCESSING`, so this alarm means a worker
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

## "the media reaper has not run for two hours" / "the media demotion has not run for 26 hours"

The jobs run in the app under ShedLock (`media-reaper` hourly at :23, `media-demotion` at 03:40
UTC). Either the app is down (UptimeRobot will have said so), or the job throws on every run:

```
docker logs --since 3h <app container> 2>&1 | grep -i 'media \(reaper\|demotion\)'
```

A lock held by an instance that died is released after `lockAtMostFor` (50 minutes): no
action. The jobs touch only the database, so the storage being down never stops them.

## After any of these

Write in the learning log what it was and what mended it. An alarm that fired and was mended
by waiting is still a line: the next one to read it will want to know it happens.
