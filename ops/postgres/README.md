# Moving the Postgres accessory to pgvector

The application on main needs the `vector` extension for V41. The accessory
uses `pgvector/pgvector:0.8.7-pg18-trixie`; production, local compose,
Testcontainers and the restore drill use the same tag (`PostgresImageTest`).

## Why the Debian release matters

On 2026-11-04 the running production container reported Postgres 18.6,
Debian 13 and glibc 2.41; the database's recorded and actual collation versions
were both 2.41. The locally inspected `-pg18-trixie` image matches these.
The plain `-pg18` image uses Debian 12/glibc 2.36. Moving the same database
to it can change text ordering and invalidate indexes. Do not dismiss a
collation warning or just refresh its recorded version: rebuild affected
indexes before accepting a deliberate collation change.

Claude's retained local rehearsal logs on 2026-11-04 report 1,273 smoke
probes on the old image and 1,327 on the migrated database, both with zero
failures. This is synthetic data, not a restore of production's backup.
The production restore below remains a separate gate.

## Before the maintenance window

1. Merge the image PR and wait for main's CI, including its manifest job.
   Use a clean checkout whose HEAD is current `origin/main`; record that
   commit for the restore drill and deployment. Merging publishes the image
   and configuration; it does not restart production.
2. Finish the media prerequisites in `../media-worker/README.md`, steps 1–3.
   Check the limiter's restart count twice: `is-active` alone can pass between
   crashes. A worker must exist, be healthy, and pass the limiter's `check`.
   The current media monitoring script also queries the reporting tables
   added in V39. On production at V36, stage the new script, but enable its
   timer only after the app deploy has applied V39.
3. Unlock Bitwarden locally, sync, verify the three R2 fields without printing
   them, and check the bucket token as the media runbook describes. Do not
   put the vault password or session key in chat or a command argument.
4. Take a fresh encrypted backup:

   ```sh
   ssh moyi 'systemctl start moyi-backup && systemctl show moyi-backup -p Result -p ExecMainStatus'
   ```

   Require `Result=success` and `ExecMainStatus=0`. Record the new backup
   object from the backup journal without dumping the environment files.
5. Run `ops/backup/restore-drill.sh --email 'restored@example.com' --version "$(git rev-parse HEAD)"`
   on the Mac. CI tags images with the full 40-character commit; a short one
   is answered `not found` at the image pull (2026-11-05). It needs the vault
   and the account password, and must end
   `RESTORE DRILL PASSED`. Use the CI-published image of the exact commit to
   be deployed. Production was at V36 on 2026-11-04, so this checks V37–V41,
   not only V40/V41. The drill disables scheduled work and external
   providers; its email endpoint is loopback and no production provider
   credentials are supplied. Record elapsed time and migration validation. Do not
   substitute a fresh synthetic database for this gate.

## Swap, then deploy

This restarts the only database. Use a quiet window. The existing bind mount
must stay `/root/moyi-postgres/data:/var/lib/postgresql`; never remove it.
Take another fresh backup immediately before stopping the application if the
restore rehearsal took long enough for new writes to have arrived.

```sh
kamal app stop
kamal accessory reboot postgres
ssh moyi 'docker exec moyi-postgres pg_isready -U moyi -d moyi'
ssh moyi 'docker exec moyi-postgres psql -U moyi -d moyi -Atc "SELECT datcollversion, pg_database_collation_actual_version(oid) FROM pg_database WHERE datname=current_database(); SELECT default_version FROM pg_available_extensions WHERE name= '\''vector'\'';"'
kamal deploy --skip-push --version=$(git rev-parse HEAD)
```

Require matching collation versions and available vector 0.8.7 before the
app deploy. Check the Postgres logs for collation warnings. If either check
fails, stop here; do not run migrations on an unvalidated image.

From `kamal app stop` until the deploy's new web container is healthy, the
API is down and the uptime monitor says so. On 2026-11-05 that was about nine
minutes (17:26 to 17:35), because the deploy had not run and a `200` was
reported in its place; Flyway at V36 and an empty `docker ps` showed it. The deploy is done only when `kamal deploy` ends
`Finished all in …` and the checks below have been read.

After deployment, require `/healthz` 200, Flyway success through V41,
`SELECT extversion FROM pg_extension WHERE extname='vector'` returning 0.8.7,
both roles running, a healthy worker, and its limiter's restart count stable.
Complete media runbook steps 5–7, including the photo and voice R2 check with
a dedicated test account. Install/enable the media check after V39 exists;
require its first run to succeed and its heartbeat to arrive. Leave AI's
provider as `none` until its prerequisites are met. Photo and voice moderation
are `allow` while the app is in private testing (ADR-0065): partner media is
shown unchecked until photos go back to Rekognition before launch.

## If something fails

Before the new application applies migrations, keep the app stopped and
investigate the database logs and mount. Reverting the accessory to its
previous image is possible only after checking its Postgres major version
and collation compatibility with the same data directory. Keep the pgvector
image after V41 has installed the extension: the old plain image lacks its
library. Never roll the database back by deleting migrations or the volume.

After migrations, follow the backup restore runbook if recovery needs an
older schema; restoring loses writes after the selected backup. Application
rollback alone does not undo migrations or turn media storage off. Use the
media runbook's storage-off PR when that is the required recovery action.
