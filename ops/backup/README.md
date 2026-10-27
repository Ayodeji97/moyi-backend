# Backups and the restore drill

Why it is built this way: ADR-0043. What runs where:

| File | Runs on | Does |
|---|---|---|
| `backup.sh` | the server, nightly at 03:00 UTC (`moyi-backup.timer`) | `pg_dump`, checks it, encrypts it with age to a public key, uploads it to R2 |
| `restore-drill.sh` | the owner's Mac | downloads the newest backup, decrypts it, restores it into a fresh Postgres, boots the production image against it, reads an entry back through the API, prints the time |

## Where the keys are

All of them are in the Bitwarden secure note `moyi-prod`:

| Field | What | Also on the server? |
|---|---|---|
| `AGE_PRIVATE_KEY` | decrypts the backups | **never** |
| `AGE_PUBLIC_KEY` | encrypts them | yes, `/etc/moyi-backup/backup.env` (not secret) |
| `R2_ENDPOINT` | `https://<account id>.eu.r2.cloudflarestorage.com` | yes |
| `R2_BACKUP_KEY_ID`, `R2_BACKUP_SECRET` | R2 token: Object Read & Write on `moyi-backups` only | yes, `/etc/moyi-backup/r2.curl` |
| `R2_DRILL_KEY_ID`, `R2_DRILL_SECRET` | R2 token: Object Read only on `moyi-backups` | never |

The server can write backups but never read one in plaintext. Its token CAN
delete objects (R2 has no write-only scope): what stops a stolen server from
emptying the bucket is the **bucket lock**, a 30-day retention rule set by hand
in the Cloudflare dashboard, beside the **35-day lifecycle rule** that removes
old backups. Neither is in this repository, so check both are there (R2 →
`moyi-backups` → Settings) after any change to the bucket.

## Installing on a server (or a rebuilt one)

From the Mac, in `moyi-backend`:

```
scp ops/backup/backup.sh moyi:/usr/local/sbin/moyi-backup
scp ops/backup/moyi-backup.service ops/backup/moyi-backup.timer moyi:/etc/systemd/system/
ssh moyi 'apt-get install -y age && chmod 700 /usr/local/sbin/moyi-backup && install -d -m 700 /etc/moyi-backup'
```

The two config files, written from Bitwarden without passing through a file
on the Mac or the shell history:

```
export BW_SESSION="$(bw unlock --raw)"
# The CLI reads a local copy of the vault: a field added in the web vault since
# the last sync is not there, and f() then writes an empty value.
bw sync >/dev/null
f() { bw get item moyi-prod | python3 -c 'import json,sys; print(next(x["value"] for x in json.load(sys.stdin)["fields"] if x["name"]==sys.argv[1]))' "$1"; }
printf 'R2_ENDPOINT="%s"\nR2_BUCKET=moyi-backups\nAGE_RECIPIENT="%s"\n' "$(f R2_ENDPOINT)" "$(f AGE_PUBLIC_KEY)" \
  | ssh moyi 'install -m 600 /dev/stdin /etc/moyi-backup/backup.env'
printf 'user = "%s:%s"\n' "$(f R2_BACKUP_KEY_ID)" "$(f R2_BACKUP_SECRET)" \
  | ssh moyi 'install -m 600 /dev/stdin /etc/moyi-backup/r2.curl'
```

Then start it once by hand, read what it did, and enable the timer:

```
ssh moyi 'systemctl daemon-reload && systemctl start moyi-backup && journalctl -u moyi-backup -n 5 --no-pager'
ssh moyi 'systemctl enable --now moyi-backup.timer && systemctl list-timers moyi-backup.timer --no-pager'
```

## Restoring production

1. Run `restore-drill.sh` against the backup you mean to restore
   (`--object daily/<name>`), so you know it restores before you drop anything.
2. Download and decrypt it on the Mac, straight into the server's Postgres; the
   plaintext exists only in the pipe:
   ```
   kamal app stop
   ssh moyi 'docker exec moyi-postgres dropdb -U moyi moyi && docker exec moyi-postgres createdb -U moyi moyi'
   # r2 and the key as restore-drill.sh sets them up (R2_DRILL_* and AGE_PRIVATE_KEY from moyi-prod):
   curl --fail --config r2.curl --aws-sigv4 "aws:amz:auto:s3" "$R2_ENDPOINT/moyi-backups/daily/<name>" \
     | age --decrypt --identity age.key \
     | ssh moyi 'docker exec -i moyi-postgres pg_restore -U moyi -d moyi --exit-on-error'
   rm -f r2.curl age.key
   kamal app boot
   ```
   Flyway validates the restored schema when the app starts.
3. If the network is the problem rather than the server, the last three
   backups are on the box too, in `/var/backups/moyi/`: copy one to the Mac and
   decrypt it there, since the key never goes to the server.

A restore loses whatever was written after the backup was taken: up to 24 hours
(the RPO, ADR-0043).

## The drill

```
colima start
ops/backup/restore-drill.sh --email <an account that is in the backup>
```

It asks for the Bitwarden master password if the vault is locked, then for that
account's password when it signs in. It finishes with
`RESTORE DRILL PASSED: … in XmYYs`. Record the time in ADR-0043's table.
Run it before real data exists, and again after anything changes how backups
are made.
