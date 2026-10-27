#!/usr/bin/env bash
# restore-drill.sh — prove a backup restores, and time it (ADR-0043).
#
# Runs on the owner's Mac, never on the server. It takes the newest backup in
# R2 (or the one named), and:
#
#   1. downloads it with a READ-ONLY R2 token;
#   2. decrypts it with the age private key, which lives in Bitwarden only;
#   3. restores it into a fresh postgres:18 of its own;
#   4. boots the production image against it, beside a Valkey of its own:
#      Flyway checks the restored schema against the image's migrations, so a
#      boot is a schema check;
#   5. signs in through the API as an account in the backup and lists its
#      archive: an entry read back the way the person who wrote it would.
#
# Then it prints the time taken, against the RTO of four hours (NFR-024), and
# removes everything it made, the decrypted dump included.
#
# Nothing it starts can reach production: its database, cache and app are
# containers on a network of its own, and the app is given throwaway keys and
# a Resend key that does not exist, so no email can leave it.
#
#   ops/backup/restore-drill.sh --email <an account in the backup> [--object daily/<name>] [--version <commit>]
#
# --version is the commit whose image boots (default: origin/main). Use the
# commit production runs when main has migrations the backup predates, or the
# drill tests a migration as well as a restore.
#
# Needs: docker running (colima start), age, python3, curl, and the Bitwarden
# CLI logged in (`bw login`); the secure note `moyi-prod` holds R2_ENDPOINT,
# R2_DRILL_KEY_ID, R2_DRILL_SECRET and AGE_PRIVATE_KEY.
set -euo pipefail
umask 077

die() { echo "restore-drill: $*" >&2; exit 1; }
say() { printf '\n== %s (%ss)\n' "$1" "$(( $(date +%s) - START ))"; }

EMAIL="" OBJECT="" VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --email)   EMAIL="${2:?}"; shift 2 ;;
    --object)  OBJECT="${2:?}"; shift 2 ;;
    --version) VERSION="${2:?}"; shift 2 ;;
    *) die "usage: $0 --email <address> [--object daily/<name>] [--version <commit>]" ;;
  esac
done
[ -n "$EMAIL" ] || die "--email is required: the drill reads an entry back as that account"
for tool in docker age python3 curl bw openssl; do command -v "$tool" >/dev/null || die "$tool is not installed"; done
docker info >/dev/null 2>&1 || die "docker is not running (colima start)"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[ -n "$VERSION" ] || VERSION="$(git -C "$ROOT" rev-parse origin/main)"
IMAGE="ghcr.io/ayodeji97/moyi-backend:$VERSION"

START="$(date +%s)"
WORK="$(mktemp -d)"
NET=moyi-drill
cleanup() {
  docker rm -f moyi-drill-app moyi-drill-postgres moyi-drill-valkey >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

say "Unlocking Bitwarden"
if [ -z "${BW_SESSION:-}" ]; then BW_SESSION="$(bw unlock --raw)"; export BW_SESSION; fi
bw get item moyi-prod >"$WORK/item.json" || die "no Bitwarden item moyi-prod"
field() {
  python3 - "$WORK/item.json" "$1" <<'PY'
import json, sys
item = json.load(open(sys.argv[1]))
for f in item.get("fields") or []:
    if f.get("name") == sys.argv[2] and f.get("value"):
        sys.stdout.write(f["value"]); sys.exit(0)
sys.exit(f"the field {sys.argv[2]} is missing from moyi-prod")
PY
}
ENDPOINT="$(field R2_ENDPOINT)"
printf 'user = "%s:%s"\n' "$(field R2_DRILL_KEY_ID)" "$(field R2_DRILL_SECRET)" >"$WORK/r2.curl"
field AGE_PRIVATE_KEY >"$WORK/age.key"
rm -f "$WORK/item.json"
r2() { curl --fail --silent --show-error --config "$WORK/r2.curl" --aws-sigv4 "aws:amz:auto:s3" "$@"; }

if [ -z "$OBJECT" ]; then
  say "Finding the newest backup"
  # ListObjectsV2 returns keys in UTF-8 order, and the names sort by time.
  OBJECT="$(r2 "$ENDPOINT/moyi-backups?list-type=2&prefix=daily/" \
    | python3 -c 'import re,sys; keys=re.findall(r"<Key>([^<]+)</Key>", sys.stdin.read()); print(max(keys) if keys else "")')"
  [ -n "$OBJECT" ] || die "the bucket holds no backup under daily/"
fi
echo "backup: $OBJECT"
echo "image:  $IMAGE"

say "Downloading"
r2 --output "$WORK/backup.age" "$ENDPOINT/moyi-backups/$OBJECT"

say "Decrypting"
age --decrypt --identity "$WORK/age.key" --output "$WORK/plain.dump" "$WORK/backup.age"
rm -f "$WORK/age.key" "$WORK/backup.age"

say "Restoring into a fresh postgres:18"
docker network create "$NET" >/dev/null
PGPASS="$(openssl rand -hex 24)"
docker run -d --name moyi-drill-postgres --network "$NET" \
  -e POSTGRES_USER=moyi -e POSTGRES_DB=moyi -e POSTGRES_PASSWORD="$PGPASS" postgres:18 >/dev/null
for _ in $(seq 1 60); do docker exec moyi-drill-postgres pg_isready -U moyi -d moyi >/dev/null 2>&1 && break; sleep 1; done
# pg_isready answers during the image's first-boot restart too; wait for the real one.
sleep 3
docker exec -i moyi-drill-postgres pg_restore --username=moyi --dbname=moyi --exit-on-error <"$WORK/plain.dump"
rm -f "$WORK/plain.dump"
docker exec moyi-drill-postgres psql -U moyi -d moyi -Atc \
  "select 'users ' || count(*) from users union all select 'entries ' || count(*) from entries"

say "Booting $VERSION against it"
docker pull --quiet "$IMAGE" >/dev/null
docker run -d --name moyi-drill-valkey --network "$NET" valkey/valkey:8 >/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/jwt.pem" 2>/dev/null
openssl pkey -in "$WORK/jwt.pem" -pubout -out "$WORK/jwt.pub"
{
  echo "SPRING_DATASOURCE_URL=jdbc:postgresql://moyi-drill-postgres:5432/moyi"
  echo "SPRING_DATASOURCE_USERNAME=moyi"
  echo "SPRING_DATASOURCE_PASSWORD=$PGPASS"
  echo "SPRING_DATA_REDIS_HOST=moyi-drill-valkey"
  echo "SPRING_DATA_REDIS_PORT=6379"
  echo "MOYI_SECURITY_JWT_PRIVATE_KEY_PEM=$(tr -d '\n' <"$WORK/jwt.pem")"
  echo "MOYI_SECURITY_JWT_PUBLIC_KEY_PEM=$(tr -d '\n' <"$WORK/jwt.pub")"
  echo "MOYI_SECURITY_HASHING_SECRET=$(openssl rand -hex 32)"
  echo "MOYI_SECURITY_ARGON2_ITERATIONS=6"
  echo "MOYI_NOTIFICATION_EMAIL_RESEND_API_KEY=re_restore_drill_sends_nothing"
  echo "MOYI_NOTIFICATION_EMAIL_FROM=Moyi drill <drill@localhost>"
  echo "MOYI_IDENTITY_VERIFICATION_LINK_BASE_URL=http://localhost/verify"
  echo "MOYI_IDENTITY_VERIFICATION_RESET_LINK_BASE_URL=http://localhost/reset-password"
  echo "MOYI_BOND_INVITE_LINK_BASE_URL=http://localhost/i"
} >"$WORK/app.env"
rm -f "$WORK/jwt.pem" "$WORK/jwt.pub"
docker run -d --name moyi-drill-app --network "$NET" --env-file "$WORK/app.env" \
  -p 127.0.0.1:18080:8080 "$IMAGE" >/dev/null
rm -f "$WORK/app.env"
for _ in $(seq 1 120); do
  curl --fail --silent http://127.0.0.1:18080/healthz >/dev/null 2>&1 && break
  docker ps --quiet --filter name=moyi-drill-app | grep -q . || { docker logs --tail 40 moyi-drill-app; die "the app exited"; }
  sleep 1
done
curl --fail --silent http://127.0.0.1:18080/healthz >/dev/null || { docker logs --tail 40 moyi-drill-app; die "the app did not come up"; }

say "Reading an entry back through the API, as $EMAIL"
export MOYI_API=http://127.0.0.1:18080/api/v1 MOYI_PROFILE=restore-drill
"$ROOT/scripts/moyi" login "$EMAIL"
BOND="$("$ROOT/scripts/moyi" bond list | python3 -c 'import json,sys; b=json.load(sys.stdin)["bonds"]; print(b[0]["id"] if b else "")')"
[ -n "$BOND" ] || die "$EMAIL has no bond in the backup: pick an account that has one"
"$ROOT/scripts/moyi" bond use "$BOND" >/dev/null
"$ROOT/scripts/moyi" days --limit 1
rm -f "${XDG_CONFIG_HOME:-$HOME/.config}/moyi/restore-drill.json"

ELAPSED="$(( $(date +%s) - START ))"
printf '\nRESTORE DRILL PASSED: %s restored and read back in %dm%02ds (RTO 4 h).\n' \
  "$OBJECT" "$((ELAPSED / 60))" "$((ELAPSED % 60))"
