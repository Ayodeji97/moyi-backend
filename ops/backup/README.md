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

The server can write backups but cannot read them back in plaintext. The bucket
lock keeps every object for 30 days, so it cannot delete them either.

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
f() { bw get item moyi-prod | python3 -c 'import json,sys; print(next(x["value"] for x in json.load(sys.stdin)["fields"] if x["name"]==sys.argv[1]))' "$1"; }
printf 'R2_ENDPOINT=%s\nR2_BUCKET=moyi-backups\nAGE_RECIPIENT=%s\n' "$(f R2_ENDPOINT)" "$(f AGE_PUBLIC_KEY)" \
  | ssh moyi 'install -m 600 /dev/stdin /etc/moyi-backup/backup.env'
printf 'user = "%s:%s"\n' "$(f R2_BACKUP_KEY_ID)" "$(f R2_BACKUP_SECRET)" \
  | ssh moyi 'install -m 600 /dev/stdin /etc/moyi-backup/r2.curl'
```

Then start it once by hand, read what it did, and enable the timer:

```
ssh moyi 'systemctl daemon-reload && systemctl start moyi-backup && journalctl -u moyi-backup -n 5 --no-pager'
ssh moyi 'systemctl enable --now moyi-backup.timer && systemctl list-timers moyi-backup.timer --no-pager'
```

## Restoring

The drill is also the restore procedure, minus the last step. To bring
production back from a backup:

1. Run `restore-drill.sh` until it passes. That proves the backup and gives you
   the decrypted dump's path if you stop it before the cleanup.
2. `kamal app stop`, then into the production Postgres:
   `dropdb` / `createdb` and `pg_restore --exit-on-error` from the decrypted dump
   (`docker exec -i moyi-postgres …`).
3. `kamal app boot`. Flyway validates the restored schema at startup.

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
