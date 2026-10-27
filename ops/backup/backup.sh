#!/usr/bin/env bash
# backup.sh — the nightly backup (ADR-0043). Runs as root on the server, from
# moyi-backup.timer; installed as /usr/local/sbin/moyi-backup.
#
#   1. pg_dump -Fc of the whole database, inside the Postgres accessory;
#   2. pg_restore --list over it: a dump that cannot be listed is not a backup,
#      and this is the last moment the plaintext exists to be checked;
#   3. age-encrypt it to a PUBLIC key. The private key is in Bitwarden only,
#      never on this machine, so whoever takes the server cannot read a backup;
#   4. upload it to the R2 bucket (EU jurisdiction), under daily/;
#   5. keep the last three encrypted copies on the box, for a restore that
#      does not need the network to fetch one.
#
# It prints one line on success and exits non-zero on any failure, so the
# journal (journalctl -u moyi-backup) says which night and which step.
#
# Configuration, both root-only (install steps in ops/backup/README.md):
#   /etc/moyi-backup/backup.env   R2_ENDPOINT, R2_BUCKET, AGE_RECIPIENT, and
#                                 optionally PING_URL (a heartbeat monitor)
#   /etc/moyi-backup/r2.curl      a curl config holding the R2 token's key
#                                 pair (user = "id:secret"); a file and not an
#                                 argument, so it never shows in `ps`
set -euo pipefail
umask 077

ENV_FILE=/etc/moyi-backup/backup.env
AUTH_FILE=/etc/moyi-backup/r2.curl
KEEP_DIR=/var/backups/moyi
KEEP=3

die() { echo "moyi-backup: $*" >&2; exit 1; }
[ -r "$ENV_FILE" ] || die "$ENV_FILE is missing"
[ -r "$AUTH_FILE" ] || die "$AUTH_FILE is missing"
# shellcheck source=/dev/null
. "$ENV_FILE"
: "${R2_ENDPOINT:?set in $ENV_FILE}" "${R2_BUCKET:?set in $ENV_FILE}" "${AGE_RECIPIENT:?set in $ENV_FILE}"
command -v age >/dev/null || die "age is not installed (apt install age)"

mkdir -p "$KEEP_DIR"
work="$(mktemp -d "$KEEP_DIR/.work.XXXXXX")"
trap 'rm -rf "$work"' EXIT

name="moyi-$(date -u +%Y-%m-%dT%H%M%SZ).dump.age"

docker exec moyi-postgres pg_dump --username=moyi --dbname=moyi --format=custom >"$work/plain.dump" \
  || die "pg_dump failed"
docker exec -i moyi-postgres pg_restore --list <"$work/plain.dump" >"$work/toc" \
  || die "pg_restore cannot read the dump"
tables="$(grep -c ' TABLE DATA ' "$work/toc" || true)"
[ "$tables" -gt 0 ] || die "the dump holds no table data"

age --recipient "$AGE_RECIPIENT" --output "$work/$name" "$work/plain.dump" || die "age failed"
rm -f "$work/plain.dump"

curl --fail --silent --show-error --max-time 300 --retry 3 \
  --config "$AUTH_FILE" --aws-sigv4 "aws:amz:auto:s3" \
  --upload-file "$work/$name" "$R2_ENDPOINT/$R2_BUCKET/daily/$name" >/dev/null \
  || die "upload to R2 failed"

size="$(stat -c %s "$work/$name")"
mv "$work/$name" "$KEEP_DIR/"
# The newest $KEEP stay; the names sort by time.
find "$KEEP_DIR" -maxdepth 1 -name 'moyi-*.dump.age' | sort -r | tail -n +"$((KEEP + 1))" | xargs -r rm -f

echo "moyi-backup: uploaded daily/$name ($size bytes, $tables tables)"

# A heartbeat: the monitor alerts when a night passes without one. Failing to
# reach it does not fail a backup that has already landed.
if [ -n "${PING_URL:-}" ]; then
  curl --fail --silent --max-time 10 --retry 3 "$PING_URL" >/dev/null || echo "moyi-backup: heartbeat not delivered" >&2
fi
