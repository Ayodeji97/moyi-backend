# ADR-0043 — Nightly encrypted backups to R2, and the restore drill that gates real data

**Status:** Accepted · **Date:** 2026-10-27

## Context
Doc 10 says a full restore drill must pass, timed, before any real user's data
exists, the soft launch included; M3's live week is that case. Production
(ADR-0042) keeps everything in one Postgres accessory on one Hetzner server,
and Hetzner's daily disk backups are images of that same server, made by the
same provider, and not stated to be crash-consistent. NFR-050 keeps personal
data at rest in the EU. The NFR's targets are RTO 4 h and RPO 5 min; point-in-time
recovery with WAL archiving is Phase 5 (doc 15).

## Decision
1. **A logical dump every night**: `pg_dump -Fc` inside the Postgres accessory at
   03:00 UTC, from a systemd timer on the server (`ops/backup/`). Before anything
   else happens to it, `pg_restore --list` must read it.
2. **Encrypted with age to a public key.** The private key exists only in
   Bitwarden. The server, and the bucket, hold only ciphertext, so taking either
   gives nobody the entries.
3. **Stored in Cloudflare R2**, bucket `moyi-backups`, EU jurisdiction: off the
   server and off Hetzner. The server's token reaches that bucket only. R2 has
   no write-only scope, so the token could delete; a bucket lock keeps every
   object for 30 days so it cannot, and a lifecycle rule removes them after
   35. Both are set by hand in Cloudflare, not in this repository; the runbook
   says where to check them. The last three backups also stay on the server,
   encrypted, kept before the upload is tried, and a failed night is retried
   three times, fifteen minutes apart.
4. **Uploads use curl's SigV4 signing**, so the server needs nothing beyond
   `age` from Ubuntu's archive. The token reaches curl from a root-only config
   file, never a command-line argument.
5. **The restore drill** (`restore-drill.sh`, on the owner's Mac) is the test of
   all of it: download with a read-only token, decrypt, restore into a fresh
   postgres:18, boot the production image against it (Flyway validates the
   schema), sign in through the API as an account in the backup and read its
   archive. It is timed. It must pass before real data exists, and again after
   anything changes how backups are made.
6. **RPO is up to 24 hours for M3**, accepted: a nightly dump loses at most one
   day of two people's entries, against the NFR's five minutes, until WAL
   archiving arrives in Phase 5.

## Drill results
| Date | Backup | Image | Time | Result |
|---|---|---|---|---|
| | | | | |

## Consequences
**Positive:** a lost or compromised server costs at most a day, and its backups
are unreadable to whoever took it. The drill proves the whole chain, keys
included, not only that a file exists.

**Negative:** RPO is a day, not five minutes. A restore needs the owner: the
private key is in the owner's vault and nowhere else, which is the point, and also a
single point of failure; losing the vault loses the backups. The server holds a
write token, so it can fill the bucket, though not empty it. Nothing yet
alerts on a missed night: the script pings a heartbeat URL when one is set,
and the monitor behind it is session 6's second half.

**Neutral:** Hetzner's disk backups stay on as a second, coarser net.

## Alternatives considered
- **Hetzner backups alone** — rejected: same provider, same account, not
  encrypted against the provider, not crash-consistent by any stated guarantee.
- **WAL-G with point-in-time recovery** — the target (Phase 5); more moving
  parts than a week between two people needs.
- **rclone or the AWS CLI for the upload** — more to install and keep patched,
  for what curl already does.
- **Encrypting with a key on the server** (symmetric, or age with the identity
  beside the dumps) — rejected: the encryption would protect nothing from
  whoever holds the server.

## Revisit when
WAL archiving lands (Phase 5), a third person's data exists, or the database
outgrows a nightly dump's window.
