#!/usr/bin/env bash
# shellcheck disable=SC2015 # pass and fail always succeed: A && pass || fail is an if-then-else here
# r2-switch-on-check.sh — the media pipeline, once, against the real storage.
#
# Run by the owner right after the deploy that switches media storage on
# (ops/media-worker/README.md, "Switching storage on"), from the Mac, as a
# verified test account. It does, through the API and the URLs it hands out,
# what ADR-0047, ADR-0048 and ADR-0049 left as "R2, not run":
#
#   1. a photo's upload intent; PUTs the storage must refuse: a body one byte
#      longer, a body of the declared length with one byte changed (the
#      signed SHA-256: ADR-0047 decision 1's unsettled item), another
#      Content-Type; then the declared bytes, and the completion;
#   2. the media worker turning it READY, the URL it is shown at fetched, and
#      what comes back a WebP with no EXIF; the URL's signature is dated the
#      start of the hour (ADR-0048 decision 6), so it was signed up to an hour
#      before the GET (MOYI_CHECK_URL_MIN_AGE makes it wait until the URL is
#      at least that many minutes old);
#   3. a voice note (a WAV made here) turned READY and served as audio/ogg,
#      Ogg Opus (ADR-0049);
#   4. each deleted, and each URL handed out before then answered 404 by the
#      storage: the objects are gone (OwnMedia's delete, then media.purge;
#      ADR-0050). The reaper deletes through the same storage call on the same
#      role, and its schedule is media-check.sh's to watch.
#
# It never prints a URL, a signature or an object key: only HTTP statuses,
# the assets' ids, and what was seen. Whatever it made it deletes, on the way
# out too, and it signs out. Moderation is `none` in production, so nothing
# it uploads is ever shown to anybody but the test account.
#
#   MOYI_CHECK_API=https://api.getmoyi.app/api/v1   where the API is (required)
#   MOYI_CHECK_EMAIL=...                            a verified test account (required)
#   MOYI_CHECK_PASSWORD=...                         its password; asked for if unset
#   MOYI_CHECK_TIMEOUT=120                          seconds to wait for READY
#   MOYI_CHECK_URL_MIN_AGE=0                        minutes old the photo's URL must be before it is fetched
#
# Needs curl, python3 and openssl. Exit 0 when every check passed.
set -uo pipefail

die() { echo "r2-switch-on-check: $*" >&2; exit 2; }
for tool in curl python3 openssl; do command -v "$tool" >/dev/null || die "$tool is not on PATH"; done

API="${MOYI_CHECK_API:-}"
EMAIL="${MOYI_CHECK_EMAIL:-}"
TIMEOUT="${MOYI_CHECK_TIMEOUT:-120}"
MIN_AGE="${MOYI_CHECK_URL_MIN_AGE:-0}"
[ -n "$API" ] || die "set MOYI_CHECK_API, e.g. https://api.getmoyi.app/api/v1"
[ -n "$EMAIL" ] || die "set MOYI_CHECK_EMAIL to a verified test account"
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || die "MOYI_CHECK_TIMEOUT must be a whole number of seconds"
[[ "$MIN_AGE" =~ ^[0-9]+$ ]] && [ "$MIN_AGE" -le 59 ] || die "MOYI_CHECK_URL_MIN_AGE must be 0 to 59 minutes"
API="${API%/}"
# A password goes over the wire: https, or this machine.
[[ "$API" =~ ^https:// || "$API" =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?/ ]] ||
  die "MOYI_CHECK_API must be https://… (or http://localhost…)"
if [ -z "${MOYI_CHECK_PASSWORD:-}" ]; then
  read -r -s -p "Password for the test account: " MOYI_CHECK_PASSWORD; echo
fi

WORK="$(mktemp -d)" || die "cannot make a work directory"
chmod 700 "$WORK"
PASSED=0 FAILED=0 ACCESS="" REFRESH="" SLEEPER=""
CREATED=() # media ids made by this run and not yet deleted

pass() { PASSED=$((PASSED + 1)); echo "  PASS  $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL  $1${2:+ — $2}"; }

# json <file> <field> — one top-level field of a JSON body, or empty.
json() { python3 -c 'import json,sys
try: v = json.load(open(sys.argv[1])).get(sys.argv[2])
except Exception: v = None
print("" if v is None else v)' "$1" "$2"; }
# header <file> <name> — one of an intent's requiredHeaders.
header() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["requiredHeaders"][sys.argv[2]])' "$1" "$2"; }
uuid() { python3 -c 'import uuid; print(uuid.uuid4())'; }
# api <out> <method> <path> [curl args…] — the status; the body in <out>, which is never printed whole.
api() {
  local out="$1" method="$2" path="$3"; shift 3
  curl -s -o "$out" -w '%{http_code}' --max-time 30 -X "$method" "$API$path" \
    -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' "$@"
}
# storage <out> <url-file> [curl args…] — a request to a signed URL read from a file, handed to curl in a config
# file of its own, so that it is never on a command line (where ps shows it) nor printed.
storage() {
  local out="$1" url="$2"; shift 2
  printf 'url = "%s"\n' "$(cat "$url")" > "$WORK/url.curl"
  curl -s -o "$out" -w '%{http_code}|%{content_type}' --max-time 60 "$@" -K "$WORK/url.curl"
}
forget() { local id="$1" kept=() m; for m in "${CREATED[@]+"${CREATED[@]}"}"; do [ "$m" = "$id" ] || kept+=("$m"); done; CREATED=("${kept[@]+"${kept[@]}"}"); }

cleanup() {
  local m status
  [ -z "$SLEEPER" ] || kill "$SLEEPER" 2>/dev/null
  for m in "${CREATED[@]+"${CREATED[@]}"}"; do
    status="$(api "$WORK/cleanup" DELETE "/media/$m")"
    echo "  cleanup: DELETE /media/$m → $status"
  done
  if [ -n "$REFRESH" ]; then
    curl -s -o /dev/null --max-time 30 -X POST "$API/auth/logout" -H 'Content-Type: application/json' \
      -d "$(python3 -c 'import json,sys; print(json.dumps({"refreshToken": sys.argv[1]}))' "$REFRESH")" || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

echo "r2-switch-on-check against $API"

# --- sign in ---------------------------------------------------------------
python3 -c 'import json,sys; print(json.dumps({"email": sys.argv[1], "password": sys.argv[2],
  "device": {"platform": "ANDROID", "appVersion": "r2-switch-on-check", "osVersion": "0"}}))' \
  "$EMAIL" "$MOYI_CHECK_PASSWORD" > "$WORK/login.json"
STATUS="$(curl -s -o "$WORK/session" -w '%{http_code}' --max-time 30 -X POST "$API/auth/login" \
  -H 'Content-Type: application/json' --data-binary "@$WORK/login.json")"
rm -f "$WORK/login.json"
[ "$STATUS" = 200 ] || die "sign-in answered $STATUS $(json "$WORK/session" code)"
ACCESS="$(json "$WORK/session" accessToken)"; REFRESH="$(json "$WORK/session" refreshToken)"
rm -f "$WORK/session"
[ -n "$ACCESS" ] || die "sign-in gave no access token"

# --- the files -------------------------------------------------------------
# A 64 × 48 black JPEG as libvips writes it, EXIF block included: what is served must carry none.
python3 - "$WORK/photo.jpg" <<'PY'
import base64, sys
open(sys.argv[1], "wb").write(base64.b64decode(
"/9j/4QC8RXhpZgAASUkqAAgAAAAGABIBAwABAAAAAQAAABoBBQABAAAAVgAAABsBBQABAAAAXgAAACgBAwABAAAAAgAAABMCAwABAAAAAQAAAGmH"
"BAABAAAAZgAAAAAAAAA4YwAA6AMAADhjAADoAwAABgAAkAcABAAAADAyMTABkQcABAAAAAECAwAAoAcABAAAADAxMDABoAMAAQAAAP//AAACoAQA"
"AQAAAEAAAAADoAQAAQAAADAAAAAAAAAA/9sAQwAIBgYHBgUIBwcHCQkICgwUDQwLCwwZEhMPFB0aHx4dGhwcICQuJyAiLCMcHCg3KSwwMTQ0NB8n"
"OT04MjwuMzQy/9sAQwEJCQkMCwwYDQ0YMiEcITIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy/8AAEQgA"
"MABAAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQci"
"cRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SV"
"lpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAAB"
"AgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1"
"Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT"
"1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/aAAwDAQACEQMRAD8A+f6KKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiig"
"AooooA//2Q=="))
PY
# Two seconds of a 440 Hz tone, 16-bit mono PCM at 8 kHz, in a RIFF WAVE.
python3 - "$WORK/voice.wav" <<'PY'
import math, struct, sys
rate, samples = 8000, 16000
pcm = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * i / rate))) for i in range(samples))
header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
open(sys.argv[1], "wb").write(header + b"data" + struct.pack("<I", len(pcm)) + pcm)
PY

# intent <name> <kind> <type> <file> — the intent's body in $WORK/<name>.intent, its URL in $WORK/<name>.put, its id in
# MEDIA_ID (empty when refused). Not called in a subshell: it counts a failure.
intent() {
  local name="$1" kind="$2" type="$3" file="$4" size sum status
  size="$(wc -c < "$file" | tr -d '[:space:]')"
  sum="$(openssl dgst -sha256 -binary "$file" | openssl base64 -A)"
  MEDIA_ID=""
  status="$(api "$WORK/$name.intent" POST /media/upload-intent \
    -d "{\"kind\":\"$kind\",\"contentType\":\"$type\",\"sizeBytes\":$size,\"checksum\":\"$sum\"}")"
  if [ "$status" != 201 ]; then
    fail "$name: the upload intent is 201" "$status $(json "$WORK/$name.intent" code)"
    return 1
  fi
  json "$WORK/$name.intent" uploadUrl > "$WORK/$name.put"
  MEDIA_ID="$(json "$WORK/$name.intent" mediaId)"
}
# put <name> <file> <content type> — the storage's status for a PUT of <file> to the intent's URL, with its signed checksum.
put() {
  local answer
  answer="$(storage /dev/null "$WORK/$1.put" -X PUT -H "Content-Type: $3" \
    -H "x-amz-checksum-sha256: $(header "$WORK/$1.intent" x-amz-checksum-sha256)" --data-binary "@$2")"
  printf '%s' "${answer%%|*}"
}
complete() { api "$WORK/$1.complete" POST "/media/$2/complete" -H "Idempotency-Key: $(uuid)"; }
# ready <name> <id> — waits until the asset is past UPLOADED and PROCESSING; its last body in $WORK/<name>.media; the status echoed.
ready() {
  local status="" deadline=$((SECONDS + TIMEOUT))
  while [ "$SECONDS" -lt "$deadline" ]; do
    api "$WORK/$1.media" GET "/media/$2" >/dev/null
    status="$(json "$WORK/$1.media" status)"
    case "$status" in UPLOADED | PROCESSING | PENDING_UPLOAD) sleep 2 ;; *) break ;; esac
  done
  printf '%s' "$status"
}
is_refused() { case "$1" in 400 | 403) return 0 ;; *) return 1 ;; esac; }

# --- 1. a photo: the refusals, the upload, the completion -------------------
echo; echo "1. a photo, uploaded straight to the storage (ADR-0047)"
intent photo IMAGE image/jpeg "$WORK/photo.jpg"; PHOTO="$MEDIA_ID"
if [ -n "$PHOTO" ]; then
  CREATED+=("$PHOTO")
  pass "photo: the upload intent is 201 (media $PHOTO)"
  cp "$WORK/photo.jpg" "$WORK/longer.jpg"; printf 'X' >> "$WORK/longer.jpg"
  python3 - "$WORK/photo.jpg" "$WORK/altered.jpg" <<'PY'
import sys
b = bytearray(open(sys.argv[1], "rb").read()); b[len(b) // 2] ^= 0x01
open(sys.argv[2], "wb").write(bytes(b))
PY
  S="$(put photo "$WORK/longer.jpg" image/jpeg)"
  is_refused "$S" && pass "photo: the storage refuses a body one byte longer than signed ($S)" ||
    fail "photo: the storage refuses a body one byte longer than signed" "it answered $S"
  S="$(put photo "$WORK/altered.jpg" image/jpeg)"
  ALTERED_TAKEN=0
  if is_refused "$S"; then
    pass "photo: the storage refuses a body of the signed length with one byte changed ($S): the storage checks the signed SHA-256"
  else
    ALTERED_TAKEN=1
    fail "photo: the storage refuses a body of the signed length with one byte changed" \
      "it answered $S: R2 does not check x-amz-checksum-sha256 (ADR-0047 decision 1's open item)"
    S="$(complete photo "$PHOTO")"
    [ "$S" = 409 ] && pass "photo: …but the completion refuses the altered bytes (409 $(json "$WORK/photo.complete" code))" ||
      fail "photo: …and the completion refuses the altered bytes" "it answered $S $(json "$WORK/photo.complete" status): altered bytes were accepted end to end"
  fi
  S="$(put photo "$WORK/photo.jpg" image/png)"
  is_refused "$S" && pass "photo: the storage refuses another Content-Type than signed ($S)" ||
    fail "photo: the storage refuses another Content-Type than signed" "it answered $S"
  S="$(put photo "$WORK/photo.jpg" image/jpeg)"
  [ "$S" = 200 ] && pass "photo: the storage takes the declared bytes (200)" || fail "photo: the storage takes the declared bytes" "it answered $S"
  S="$(complete photo "$PHOTO")"
  [ "$S" = 200 ] && pass "photo: the completion is 200 ($(json "$WORK/photo.complete" status))" ||
    fail "photo: the completion is 200" "$S $(json "$WORK/photo.complete" code)"
  [ "$ALTERED_TAKEN" = 1 ] && echo "        (the declared bytes were PUT again over the altered ones before this completion)"
fi

# --- 2. the worker makes it READY; its URL --------------------------------
echo; echo "2. the media worker makes it READY; the URL it is shown at (ADR-0048, ADR-0052)"
if [ -n "$PHOTO" ]; then
  S="$(ready photo "$PHOTO")"
  if [ "$S" = READY ]; then
    pass "photo: READY within ${TIMEOUT}s, ${SECONDS}s into the run ($(json "$WORK/photo.media" width)×$(json "$WORK/photo.media" height))"
    json "$WORK/photo.media" url > "$WORK/photo.url"
    # The signature's date, X-Amz-Date, read from the URL without printing it.
    age() { python3 -c 'import sys, datetime as d, urllib.parse as u
q = u.parse_qs(u.urlsplit(open(sys.argv[1]).read().strip()).query)
signed = d.datetime.strptime(q["X-Amz-Date"][0], "%Y%m%dT%H%M%SZ").replace(tzinfo=d.timezone.utc)
print(int((d.datetime.now(d.timezone.utc) - signed).total_seconds() // 60))' "$WORK/photo.url"; }
    AGE="$(age)"
    if [ "$AGE" -lt "$MIN_AGE" ]; then
      echo "        the URL was signed $AGE minutes ago; waiting until it is $MIN_AGE minutes old"
      # In the background, waited for: an interrupt then runs the cleanup at once, not after the sleep.
      sleep $(((MIN_AGE - AGE) * 60 + 5)) &
      SLEEPER=$!; wait "$SLEEPER"; SLEEPER=""; AGE="$(age)"
    fi
    S="$(storage "$WORK/served.webp" "$WORK/photo.url")"
    if [ "${S%%|*}" = 200 ]; then
      pass "photo: the storage serves a GET of its URL, signed $AGE minutes before the GET (200, ${S#*|})"
    else
      fail "photo: the storage serves a GET of its URL, signed $AGE minutes before" "it answered ${S%%|*}"
    fi
    python3 - "$WORK/served.webp" <<'PY' && pass "photo: what is served is a WebP with no EXIF" || fail "photo: what is served is a WebP with no EXIF"
import sys
b = open(sys.argv[1], "rb").read()
sys.exit(0 if b[:4] == b"RIFF" and b[8:12] == b"WEBP" and b"Exif" not in b else 1)
PY
  else
    fail "photo: READY within ${TIMEOUT}s" "its status is ${S:-unknown} (is the media worker running, and limited to Postgres and Cloudflare only?)"
  fi
fi

# --- 3. a voice note --------------------------------------------------------
echo; echo "3. a voice note, transcoded and served as audio/ogg (ADR-0049)"
intent voice VOICE audio/wav "$WORK/voice.wav"; VOICE="$MEDIA_ID"
if [ -n "$VOICE" ]; then
  CREATED+=("$VOICE")
  pass "voice: the upload intent is 201 (media $VOICE)"
  S="$(put voice "$WORK/voice.wav" audio/wav)"
  [ "$S" = 200 ] && pass "voice: the storage takes the declared bytes (200)" || fail "voice: the storage takes the declared bytes" "it answered $S"
  S="$(complete voice "$VOICE")"
  [ "$S" = 200 ] && pass "voice: the completion is 200" || fail "voice: the completion is 200" "$S $(json "$WORK/voice.complete" code)"
  S="$(ready voice "$VOICE")"
  if [ "$S" = READY ]; then
    pass "voice: READY ($(json "$WORK/voice.media" durationMs) ms)"
    json "$WORK/voice.media" url > "$WORK/voice.url"
    S="$(storage "$WORK/served.ogg" "$WORK/voice.url")"
    [ "${S%%|*}" = 200 ] && [ "${S#*|}" = audio/ogg ] && pass "voice: the storage serves it as audio/ogg (200)" ||
      fail "voice: the storage serves it as audio/ogg" "it answered ${S%%|*} with Content-Type ${S#*|}"
    python3 - "$WORK/served.ogg" <<'PY' && pass "voice: …and it is Ogg Opus" || fail "voice: …and it is Ogg Opus"
import sys
b = open(sys.argv[1], "rb").read()
sys.exit(0 if b[:4] == b"OggS" and b"OpusHead" in b else 1)
PY
  else
    fail "voice: READY within ${TIMEOUT}s" "its status is ${S:-unknown}"
  fi
fi

# --- 4. deleted, and gone from the storage ----------------------------------
echo; echo "4. deleted, and gone from the storage (ADR-0050)"
# gone <name> <id> — DELETE, then until its URL handed out before answers 404 (at most 60 s).
gone() {
  local name="$1" id="$2" status answer=""
  status="$(api "$WORK/$name.delete" DELETE "/media/$id")"
  if [ "$status" != 204 ]; then fail "$name: DELETE is 204" "$status $(json "$WORK/$name.delete" code)"; return; fi
  forget "$id"
  pass "$name: DELETE is 204"
  status="$(api "$WORK/$name.after" GET "/media/$id")"
  [ "$status" = 404 ] && pass "$name: …and it is 404 to its owner" || fail "$name: …and it is 404 to its owner" "$status"
  [ -s "$WORK/$name.url" ] || { echo "  SKIP  $name: its URL after the delete (it never became READY, so no URL was handed out)"; return; }
  for _ in $(seq 1 30); do
    answer="$(storage /dev/null "$WORK/$name.url")"
    [ "${answer%%|*}" = 404 ] && break
    sleep 2
  done
  [ "${answer%%|*}" = 404 ] && pass "$name: the URL handed out before is answered 404 by the storage: the object is gone" ||
    fail "$name: the URL handed out before is answered 404 by the storage" "it answered ${answer%%|*} after 60 s (media.purge retries; see ops/monitoring/media-pipeline-stuck.md)"
}
[ -n "$PHOTO" ] && gone photo "$PHOTO"
[ -n "$VOICE" ] && gone voice "$VOICE"

echo
echo "r2-switch-on-check: $PASSED passed, $FAILED failed"
[ "$FAILED" = 0 ]
