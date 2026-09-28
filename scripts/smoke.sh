#!/usr/bin/env bash
# Smoke test for the moyi-backend API against a locally running instance.
#
# What it proves: the packaged application boots against the compose Postgres,
# and every identity endpoint that exists so far — registration, verification,
# sign-in, refresh rotation, logout, password reset — answers with the status
# and the error code the API contract (doc 06) promises — on the happy path AND on
# the edges that have bitten before: malformed JSON, wrong method, reused
# token, unknown address, duplicate registration. Since Phase 2 it also proves
# that two accounts can pair by a code and that their bond is invisible to a
# third (M2, T-02, T-06), and since slice B3 that a bond can be ended and that a
# block is indistinguishable from a leave from the other side — the same bytes
# and the same ETag (doc 26 §2.1, T-09). Since slice F it drives
# every rate limit in doc 06 §4 to its 429 (FR-012, ADR-0023) and checks the
# X-RateLimit-* headers and Retry-After on the way, against the compose Valkey.
#
# What it does not prove: anything the unit and integration tests already
# prove. This is the "run it, do not read it" check (docs/learning-log.md,
# 2026-09-19 onward): green tests are a claim about where you ran them.
#
# Usage:
#   scripts/smoke.sh              # builds the jar, boots it, probes, stops it
#   scripts/smoke.sh --no-build   # reuse app/build/libs/*.jar
#   PORT=18080 scripts/smoke.sh   # boot on another port
#   BASE=http://localhost:8080 scripts/smoke.sh --attach  # probe a server you started yourself;
#                                                          # needs MOYI_LOG=<its log file> to read the emailed link
#   MOYI_JAVA=/path/to/jdk-25/bin/java scripts/smoke.sh   # boot with that JDK instead of the one found
#
# The jar is compiled for JDK 25 (build-logic's jvmToolchain), which is not
# necessarily the `java` on PATH: Gradle downloads its own toolchain and
# SDKMAN's `current` is only on PATH in an interactive shell. Booting the jar
# on JDK 21 dies at once with UnsupportedClassVersionError — and the old loop
# below waited its full ninety seconds before saying "timed out". Both found
# on 2026-09-24 by running this script from a non-interactive shell.
set -euo pipefail

cd "$(dirname "$0")/.."
PORT="${PORT:-8080}"
BASE="${BASE:-http://localhost:$PORT}"
API="$BASE/api/v1"
BUILD=1
ATTACH=0
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --attach)   ATTACH=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

PASS=0; FAIL=0
pass() { PASS=$((PASS+1)); printf '  ok   %s\n' "$1"; }
fail() { FAIL=$((FAIL+1)); printf '  FAIL %s\n       %s\n' "$1" "$2"; }

# The first JDK that can run the jar, in order of how deliberately it was
# chosen: MOYI_JAVA, JAVA_HOME, SDKMAN's current and then any SDKMAN 25+,
# macOS's java_home, and finally whatever `java` is. Prints its path.
MIN_JAVA=25
# The version line, not the first line: a set JAVA_TOOL_OPTIONS or _JAVA_OPTIONS
# makes the JVM print "Picked up …" above it, and every JDK would then look too old.
java_major() { "$1" -version 2>&1 | grep -m1 'version "' | sed -E 's/.*"([0-9]+)[^"]*".*/\1/'; }
find_java() {
  local candidate
  for candidate in "${MOYI_JAVA:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
      "$HOME/.sdkman/candidates/java/current/bin/java" "$HOME"/.sdkman/candidates/java/*/bin/java \
      "$(/usr/libexec/java_home -v "$MIN_JAVA+" 2>/dev/null)/bin/java" "$(command -v java || true)"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    [ "$(java_major "$candidate")" -ge "$MIN_JAVA" ] 2>/dev/null && { echo "$candidate"; return 0; }
  done
  return 1
}

# Empties every rate-limit bucket in the compose Valkey. The buckets are the
# point of slice F, and they are also what makes a second run of this script —
# or the lockout flow below, which signs in more than five times — collide with
# the first. Flushing between sections keeps each probe about one thing.
flush_buckets() { docker compose exec -T redis valkey-cli FLUSHALL >/dev/null 2>&1 || true; }

# expect <label> <expected-status> [expected-substring-in-body] -- <curl args>
# Leaves the body in LAST_BODY and the response headers in LAST_HEADERS, so a
# follow-up `header_is` can check a header of the SAME response rather than
# re-issuing the request — which would spend another rate-limit token.
expect() {
  local label="$1" want="$2" needle="${3:-}"; shift 3; [ "${1:-}" = "--" ] && shift
  local body hdrs status
  body="$(mktemp)"; hdrs="$(mktemp)"
  # JSON by default; a probe that names its own Content-Type gets only that one.
  local -a headers=(-H 'Content-Type: application/json')
  case "$*" in *Content-Type:*) headers=() ;; esac
  status="$(curl -s -o "$body" -D "$hdrs" -w '%{http_code}' "${headers[@]}" "$@")"
  local text; text="$(cat "$body")"; rm -f "$body"
  LAST_HEADERS="$(tr -d '\r' < "$hdrs")"; rm -f "$hdrs"
  if [ "$status" != "$want" ]; then
    fail "$label" "expected HTTP $want, got $status: ${text:0:200}"
  elif [ -n "$needle" ] && [[ "$text" != *"$needle"* ]]; then
    fail "$label" "expected body to contain '$needle': ${text:0:200}"
  else
    pass "$label ($status)"
  fi
  LAST_BODY="$text"
}

# header_is <label> <header-name> <expected-value> — checked against LAST_HEADERS.
header_is() {
  local label="$1" name="$2" want="$3" got
  # `|| true`: under `set -eo pipefail` a header that is simply absent made
  # grep fail the assignment and the whole script exit 1 without a FAIL line.
  got="$(printf '%s\n' "$LAST_HEADERS" | grep -i "^$name:" | head -1 | cut -d' ' -f2- || true)"
  if [ "$got" = "$want" ]; then pass "$label ($name: $got)"; else fail "$label" "expected $name: $want, got '${got:-<absent>}'"; fi
}

if [ "$ATTACH" = 0 ]; then
  command -v docker >/dev/null || { echo "docker is required"; exit 1; }
  docker compose up -d postgres >/dev/null
  if [ "$BUILD" = 1 ]; then
    echo "building the jar…"
    ./gradlew -q :app:bootJar
  fi
  JAR="$(ls app/build/libs/app-*-SNAPSHOT.jar | grep -v plain | head -1)"
  JAVA="$(find_java)" || { echo "no JDK $MIN_JAVA or newer found: set MOYI_JAVA or JAVA_HOME, or install one (README: sdk install java 25.0.4-tem)"; exit 1; }
  MOYI_LOG="$(mktemp -t moyi-smoke.XXXXXX.log)"
  echo "booting $JAR (local profile) on $("$JAVA" -version 2>&1 | head -1), log: $MOYI_LOG"
  "$JAVA" -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" >"$MOYI_LOG" 2>&1 &
  APP_PID=$!
  trap 'kill $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null || true' EXIT
  for _ in $(seq 1 90); do
    grep -q "Started MoyiApplication" "$MOYI_LOG" && break
    grep -q "APPLICATION FAILED" "$MOYI_LOG" && { echo "the application failed to start:"; grep -A3 "APPLICATION FAILED" "$MOYI_LOG"; exit 1; }
    # A JVM that could not even load the main class is gone long before any
    # Spring banner: notice, rather than wait out the timeout.
    kill -0 "$APP_PID" 2>/dev/null || { echo "the application exited before it started:"; head -5 "$MOYI_LOG"; exit 1; }
    sleep 1
  done
  grep -q "Started MoyiApplication" "$MOYI_LOG" || { echo "timed out waiting for startup"; tail -20 "$MOYI_LOG"; exit 1; }
else
  : "${MOYI_LOG:?--attach needs MOYI_LOG=<path to the log file of the running server>}"
fi

EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASSWORD="correct horse battery"
register_body() { printf '{"email":"%s","password":"%s","displayName":"Smoke","locale":"en","acceptedTermsVersion":"2026-09-01","over18":true}' "$1" "$PASSWORD"; }

# A previous run's buckets must not count against this one.
flush_buckets

echo; echo "health"
expect "GET /actuator/health is UP" 200 '"status":"UP"' -- "$BASE/actuator/health"

echo; echo "registration (FR-001, FR-011, ADR-0015)"
# Each probe arrives from its own address. Registration is three an hour per
# IP (FR-012), spent BEFORE validation, so seven probes from one address would
# meet the limit on the fourth; the local profile trusts loopback as a proxy,
# so X-Forwarded-For chooses the client. The rate-limit section below is where
# that limit is the subject.
EMAILS_BEFORE="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "valid registration is 201 with an empty body" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.1' -d "$(register_body "$EMAIL")"
[ -z "$LAST_BODY" ] && pass "…and the body really is empty" || fail "empty body" "got: ${LAST_BODY:0:100}"
expect "same address again (different case) is still 201" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.2' -d "$(register_body "$(echo "$EMAIL" | tr a-z A-Z)")"
expect "7-character password is 422 VALID_PASSWORD" 422 '"code":"VALID_PASSWORD"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.3' -d '{"email":"x@example.com","password":"short12","displayName":"S","acceptedTermsVersion":"1","over18":true}'
expect "breached password is 422 NOT_BREACHED" 422 '"code":"NOT_BREACHED"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.4' -d '{"email":"x@example.com","password":"password","displayName":"S","acceptedTermsVersion":"1","over18":true}'
expect "over18=false is 422 on field over18" 422 '"field":"over18"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.5' -d '{"email":"x@example.com","password":"correct horse battery","displayName":"S","acceptedTermsVersion":"1","over18":false}'
expect "malformed JSON is 400 MALFORMED_REQUEST" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.6' -d '{"email": '
expect "wrong content type is 415" 415 '"code":"UNSUPPORTED_MEDIA_TYPE"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.7' -H 'Content-Type: text/plain' -d 'x'
# Since E1 (ADR-0019) the resource server answers before routing does: anything
# under /api/v1 that is not a permitted public POST needs a bearer token, so an
# unknown route is 401, not 404 — and the 401 still carries a code.
expect "unknown route under /api/v1 is 401 UNAUTHENTICATED" 401 '"code":"UNAUTHENTICATED"' -- "$API/auth/nope"

echo; echo "resource server (ADR-0019)"
expect "GET /me without a token is 401 UNAUTHENTICATED" 401 '"code":"UNAUTHENTICATED"' -- "$API/me"
if curl -s -o /dev/null -D - "$API/me" | grep -qi "^www-authenticate: bearer"; then pass "…with a WWW-Authenticate: Bearer challenge"; else fail "WWW-Authenticate" "header missing on the 401"; fi
expect "GET /me with a garbage token is 401" 401 '"code":"UNAUTHENTICATED"' -- "$API/me" -H 'Authorization: Bearer not-a-jwt'

echo; echo "email verification (FR-002, ADR-0018)"
# The email is sent after the commit, on another thread. Poll for a NEW log
# line rather than sleeping a fixed time: a loaded machine can take longer
# than any constant, and an old line from an earlier run must not count.
for _ in $(seq 1 40); do
  [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$EMAILS_BEFORE" ] && break
  sleep 0.25
done
if [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$EMAILS_BEFORE" ]; then pass "verification email was written to the log (provider=log)"; else fail "email in log" "no new 'Email NOT sent' line in $MOYI_LOG within 10 s"; fi
SECRET="$(grep -oE 'token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
if [ "${#SECRET}" = 43 ]; then pass "link carries a 43-character secret"; else fail "secret in link" "got '${SECRET}'"; fi
if grep -qi "$EMAIL" "$MOYI_LOG"; then fail "address never logged" "the address appears in the log"; else pass "address never appears in the log (masked to the domain)"; fi
expect "garbage token is 422 VERIFICATION_TOKEN_INVALID" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -d '{"token":"not-a-token"}'
expect "blank token is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/verify-email" -d '{"token":" "}'
# A scanner's GET cannot spend a token: only POST is public here, so the filter
# chain refuses it before the controller is ever reached.
expect "GET on verify-email cannot spend a token (401)" 401 '"code":"UNAUTHENTICATED"' -- "$API/auth/verify-email?token=$SECRET"
expect "real token verifies: 200, empty body" 200 "" -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$SECRET\"}"
expect "same token again is 410 VERIFICATION_TOKEN_EXPIRED" 410 '"code":"VERIFICATION_TOKEN_EXPIRED"' -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$SECRET\"}"
expect "resend for a verified address is 202, empty" 202 "" -- -X POST "$API/auth/resend-verification" -d "{\"email\":\"$EMAIL\"}"
expect "resend for an unknown address is 202, identical" 202 "" -- -X POST "$API/auth/resend-verification" -d '{"email":"nobody-here@example.com"}'
expect "resend for a malformed address is 422" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/resend-verification" -d '{"email":"not-an-address"}'

echo; echo "rate limiting (FR-012, ADR-0023)"
# Every bucket in doc 06 §4 that the identity module owes, driven to its 429
# against the real jar and the compose Valkey. Each flow uses an address no
# other section uses, so nothing here leaks into the probes that follow — and
# a 429 writes nothing, so the database checks at the end are unaffected. It
# runs after verification because the fresh accounts it registers send
# verification emails of their own, and that section reads the LAST link in
# the log.
#
# Registration: a fresh account (201) shows the headers on a success; two 422s
# spend tokens too, because the interceptor runs before validation; the fourth
# request is the 429. Fresh addresses rather than the duplicate path, because
# the duplicate path makes Postgres name the address in a constraint message
# that Hibernate logs — see the "address never logged" probe below.
RL_STAMP="$(date +%s)-$RANDOM"
expect "a limited response carries X-RateLimit-* on success (201)" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.20' -d "$(register_body "rl-a-$RL_STAMP@example.com")"
header_is "…limit is three an hour per address" X-RateLimit-Limit 3
header_is "…with two left" X-RateLimit-Remaining 2
if printf '%s\n' "$LAST_HEADERS" | grep -qiE '^X-RateLimit-Reset: [0-9]{10}'; then pass "…and a reset time in Unix seconds"; else fail "X-RateLimit-Reset" "missing or not epoch seconds"; fi
expect "a 422 spends a token too (validation runs after the limiter)" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.20' -d '{"email":"x@example.com","password":"short12","displayName":"S","acceptedTermsVersion":"1","over18":true}'
header_is "…one left" X-RateLimit-Remaining 1
expect "third attempt from the address is still answered" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.20' -d '{"email":"x@example.com","password":"short12","displayName":"S","acceptedTermsVersion":"1","over18":true}'
header_is "…none left" X-RateLimit-Remaining 0
expect "the fourth registration from one address is 429 RATE_LIMITED" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.20' -d "$(register_body "$EMAIL")"
[[ "$LAST_BODY" == *"Please wait 20 minutes before trying again."* ]] && pass "…announcing the wait, not the failure (states.md §1b)" || fail "429 copy" "got: ${LAST_BODY:0:200}"
header_is "…with Retry-After: three an hour, greedy, is one token every twenty minutes" Retry-After 1200
header_is "…and X-RateLimit-Remaining: 0" X-RateLimit-Remaining 0
if printf '%s\n' "$LAST_HEADERS" | grep -qi '^Content-Type: application/problem+json'; then pass "…in the problem shape"; else fail "429 content type" "not application/problem+json"; fi
expect "another address is unaffected" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.24' -d "$(register_body "rl-b-$RL_STAMP@example.com")"
# Sign-in: five attempts per fifteen minutes per address — counted for an
# address that has no account, so the 429 is not an oracle (T-18). No Argon2
# is paid for the sixth: the bucket is consumed before the verify.
LIMITED_EMAIL="limit-$(date +%s)-$RANDOM@example.com"
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -H 'Content-Type: application/json' -H 'X-Forwarded-For: 203.0.113.21' -X POST "$API/auth/login" -d "$(printf '{"email":"%s","password":"wrong %s"}' "$LIMITED_EMAIL" "$i")"
done
expect "the sixth sign-in for one (unknown) address in fifteen minutes is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/login" -H 'X-Forwarded-For: 203.0.113.21' -d "$(printf '{"email":"%s","password":"wrong 6"}' "$LIMITED_EMAIL")"
header_is "…Retry-After: one token back every three minutes" Retry-After 180
header_is "…X-RateLimit-Limit is the per-email five, the bucket that decided" X-RateLimit-Limit 5
[[ "$LAST_BODY" == *"Please wait 3 minutes before trying again."* ]] && pass "…copy names the three minutes" || fail "429 copy" "got: ${LAST_BODY:0:200}"
# Resend: once a minute per address, the cooldown states.md §1 promised.
expect "first resend for an unknown address is 202" 202 "" -- -X POST "$API/auth/resend-verification" -H 'X-Forwarded-For: 203.0.113.22' -d "{\"email\":\"$LIMITED_EMAIL\"}"
expect "a second inside a minute is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/resend-verification" -H 'X-Forwarded-For: 203.0.113.22' -d "{\"email\":\"$LIMITED_EMAIL\"}"
header_is "…Retry-After: 60" Retry-After 60
[[ "$LAST_BODY" == *"Please wait a minute before trying again."* ]] && pass "…copy says a minute" || fail "429 copy" "got: ${LAST_BODY:0:200}"
# Password reset: three an hour per address, identical for an address nobody has.
for i in 1 2 3; do
  expect "forgot-password $i of 3 for an unknown address is 202" 202 "" -- -X POST "$API/auth/forgot-password" -H 'X-Forwarded-For: 203.0.113.23' -d "{\"email\":\"$LIMITED_EMAIL\"}"
done
expect "the fourth is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/forgot-password" -H 'X-Forwarded-For: 203.0.113.23' -d "{\"email\":\"$LIMITED_EMAIL\"}"
header_is "…Retry-After: 1200" Retry-After 1200
flush_buckets

echo; echo "sign-in and sessions (FR-003, FR-004, ADR-0020–0022)"
# The account is verified by now, so this is the ordinary sign-in; the
# unverified sign-in FR-002 allows is covered by the endpoint tests.
jget() { python3 -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
login_body() { printf '{"email":"%s","password":"%s","device":{"platform":"ANDROID","appVersion":"smoke","osVersion":"16"}}' "$1" "$2"; }
expect "login is 200 with a token pair and the profile" 200 '"expiresIn":900' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"; REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
[[ "$LAST_BODY" == *'"emailVerified":true'* ]] && pass "…and the profile says the address is verified" || fail "profile" "emailVerified not true: ${LAST_BODY:0:120}"
expect "GET /me with the access token is 200" 200 "\"email\":\"$EMAIL\"" -- "$API/me" -H "Authorization: Bearer $ACCESS"
header_is "…and every authenticated request is counted against the per-user bucket (doc 06 §4)" X-RateLimit-Limit 120
# Errors Spring raises for itself — no such route, wrong method — arrive in
# the contract's shape too: `type` (required by contracts/openapi.json), our
# prose title, and no implementation detail. Found by this smoke test on
# 2026-09-24: a trailing slash was a 404 with `"type":null` and the detail
# "No static resource api/v1/me." on an API that serves none.
expect "a trailing slash is a 404 in the contract's shape" 404 '"type":"https://api.moyi.app/problems/not-found"' -- "$API/me/" -H "Authorization: Bearer $ACCESS"
[[ "$LAST_BODY" == *'"detail":"No such resource."'* && "$LAST_BODY" != *"static resource"* ]] && pass "…that says only 'No such resource.'" || fail "404 detail" "${LAST_BODY:0:160}"
expect "PUT /me is 405 METHOD_NOT_ALLOWED with a type" 405 '"type":"https://api.moyi.app/problems/method-not-allowed"' -- -X PUT "$API/me" -H "Authorization: Bearer $ACCESS"
expect "wrong password is 401 INVALID_CREDENTIALS" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "not the password")"
WRONG="$LAST_BODY"
expect "unknown address is 401 too" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "nobody-$RANDOM@example.com" "not the password")"
[ "$WRONG" = "$LAST_BODY" ] && pass "…with a byte-identical body (no account oracle)" || fail "identical 401s" "bodies differ"
expect "an oversized password is 422, not work" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$(printf 'x%.0s' $(seq 1 600))")"
# Lockout: four more wrong attempts make five; the right password is then
# refused with the same 401, silently, for a minute. The per-email bucket
# (five per fifteen minutes) fires BEFORE the lockout on an unflushed run —
# that layering is deliberate (ADR-0023) — so the buckets are emptied here to
# let the lockout, the durable control, be observed on its own.
flush_buckets
for i in 2 3 4 5; do curl -s -o /dev/null -H 'Content-Type: application/json' -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "wrong $i")"; done
expect "after five failures the RIGHT password is refused, identically" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
LOCK="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT failed_attempts || '|' || CAST(EXTRACT(EPOCH FROM (locked_until - now())) AS int) FROM credentials c JOIN users u ON u.id=c.user_id WHERE u.email='$EMAIL'" 2>/dev/null || echo "psql-unavailable")"
case "$LOCK" in 5\|5[5-9]|5\|60) pass "locked for a minute after five failures ($LOCK)";; psql-unavailable) echo "  skip lock check (psql unavailable)";; *) fail "lockout row" "expected 5|55..60, got '$LOCK'";; esac
docker compose exec -T postgres psql -U moyi -d moyi -Atc "UPDATE credentials SET locked_until = now() - interval '1 second' FROM users u WHERE u.id = credentials.user_id AND u.email='$EMAIL'" >/dev/null 2>&1 || true
flush_buckets
expect "once the lock expires the right password signs in again" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "refresh rotates: 200 with a new pair" 200 '"refreshToken"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH\"}"
REFRESH2="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
[ "$REFRESH2" != "$REFRESH" ] && pass "…and the new refresh token differs from the old" || fail "rotation" "same refresh token returned"
expect "presenting the rotated token again is 401 TOKEN_REUSE_DETECTED" 401 '"code":"TOKEN_REUSE_DETECTED"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH\"}"
expect "…and the successor died with the family: REFRESH_TOKEN_INVALID" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH2\"}"
expect "a never-issued refresh token is 401 REFRESH_TOKEN_INVALID" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d '{"refreshToken":"never-issued"}'
expect "login again (fresh family)" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
REFRESH3="$(printf '%s' "$LAST_BODY" | jget refreshToken)"; ACCESS3="$(printf '%s' "$LAST_BODY" | jget accessToken)"
expect "logout is 204" 204 "" -- -X POST "$API/auth/logout" -d "{\"refreshToken\":\"$REFRESH3\"}"
expect "…and idempotent" 204 "" -- -X POST "$API/auth/logout" -d "{\"refreshToken\":\"$REFRESH3\"}"
expect "the logged-out token cannot refresh" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH3\"}"
expect "logout-all without a bearer token is 401" 401 '"code":"UNAUTHENTICATED"' -- -X POST "$API/auth/logout-all"
expect "logout-all with one is 204" 204 "" -- -X POST "$API/auth/logout-all" -H "Authorization: Bearer $ACCESS3"

echo; echo "sessions (FR-007, ADR-0025)"
# Two sign-ins, two sessions: the list is the caller's live refresh-token
# families, most recently seen first, and marks the one the bearer belongs to.
expect "sign in on a phone" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
PHONE_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"; PHONE_REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "sign in on a watch" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "$(printf '{"email":"%s","password":"%s","device":{"platform":"WEAR","appVersion":"smoke","osVersion":"5.1"}}' "$EMAIL" "$PASSWORD")"
WATCH_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
expect "GET /auth/sessions lists both, the watch current" 200 '"current":true' -- "$API/auth/sessions" -H "Authorization: Bearer $WATCH_ACCESS"
[[ "$LAST_BODY" == *'"platform":"WEAR"'* && "$LAST_BODY" == *'"platform":"ANDROID"'* ]] && pass "…with both devices" || fail "devices in list" "${LAST_BODY:0:200}"
PHONE_SESSION="$(python3 -c "import json,sys; print(next(s['id'] for s in json.load(sys.stdin)['sessions'] if not s['current']))" <<<"$LAST_BODY")"
expect "GET /auth/sessions without a bearer is 401" 401 '"code":"UNAUTHENTICATED"' -- "$API/auth/sessions"
expect "DELETE somebody else's (a random id) is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/auth/sessions/$(python3 -c 'import uuid; print(uuid.uuid4())')" -H "Authorization: Bearer $WATCH_ACCESS"
expect "DELETE a value that is not an id is 404 too" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/auth/sessions/not-a-session" -H "Authorization: Bearer $WATCH_ACCESS"
expect "DELETE the phone's session from the watch is 204" 204 "" -- -X DELETE "$API/auth/sessions/$PHONE_SESSION" -H "Authorization: Bearer $WATCH_ACCESS"
expect "…and the phone's refresh token is dead" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$PHONE_REFRESH\"}"
expect "…while the phone's access token still lists one session" 200 '"sessions":[{' -- "$API/auth/sessions" -H "Authorization: Bearer $PHONE_ACCESS"
expect "DELETE the same session again is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/auth/sessions/$PHONE_SESSION" -H "Authorization: Bearer $WATCH_ACCESS"
WATCH_SESSION="$(curl -s "$API/auth/sessions" -H "Authorization: Bearer $WATCH_ACCESS" | python3 -c "import json,sys; print(next(s['id'] for s in json.load(sys.stdin)['sessions'] if s['current']))")"
expect "DELETE the current session is a logout: 204" 204 "" -- -X DELETE "$API/auth/sessions/$WATCH_SESSION" -H "Authorization: Bearer $WATCH_ACCESS"
expect "…and the watch now lists no sessions" 200 '"sessions":[]' -- "$API/auth/sessions" -H "Authorization: Bearer $WATCH_ACCESS"
expect "an unknown platform is 422 on device.platform" 422 '"field":"device.platform"' -- -X POST "$API/auth/login" -d "$(printf '{"email":"%s","password":"%s","device":{"platform":"BLACKBERRY","appVersion":"1","osVersion":"7"}}' "$EMAIL" "$PASSWORD")"
flush_buckets

echo; echo "password reset (FR-004, T-17, ADR-0022)"
EMAILS_BEFORE="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "forgot-password for the account is 202, empty" 202 "" -- -X POST "$API/auth/forgot-password" -d "{\"email\":\"$EMAIL\"}"
expect "forgot-password for a stranger is 202, identical" 202 "" -- -X POST "$API/auth/forgot-password" -d '{"email":"nobody-here@example.com"}'
for _ in $(seq 1 40); do [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$EMAILS_BEFORE" ] && break; sleep 0.25; done
RESET="$(grep -oE 'reset-password\?token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
if [ "${#RESET}" = 43 ]; then pass "reset link lands on the reset page with a 43-character secret"; else fail "reset link" "got '${RESET}'"; fi
expect "a breached new password is refused per field, token not spent" 422 '"code":"NOT_BREACHED"' -- -X POST "$API/auth/reset-password" -d "{\"token\":\"$RESET\",\"password\":\"password\"}"
NEW_PASSWORD="a different good password"
expect "reset with a good password is 200" 200 "" -- -X POST "$API/auth/reset-password" -d "{\"token\":\"$RESET\",\"password\":\"$NEW_PASSWORD\"}"
expect "the same link again is 410 PASSWORD_RESET_TOKEN_EXPIRED" 410 '"code":"PASSWORD_RESET_TOKEN_EXPIRED"' -- -X POST "$API/auth/reset-password" -d "{\"token\":\"$RESET\",\"password\":\"$NEW_PASSWORD\"}"
expect "the old password no longer signs in" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
expect "the new one does" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$NEW_PASSWORD")"
sleep 1
for needle in "$PASSWORD" "$NEW_PASSWORD" "$REFRESH" "$REFRESH2" "$ACCESS"; do if grep -qF -- "$needle" "$MOYI_LOG"; then fail "secret in log" "a password or token appears in the log"; SECRET_LEAK=1; fi; done
[ "${SECRET_LEAK:-0}" = 0 ] && pass "no password, refresh token or access token appears in the log"

echo; echo "bonds (FR-020, FR-022, FR-025, T-02, ADR-0026)"
flush_buckets
expect "sign in as the verified account" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$NEW_PASSWORD")"
BOND_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
bond_body() { printf '{"name":"%s","type":"COUPLE","anchorTimezone":"Africa/Lagos"}' "$1"; }

expect "POST /bonds is 201, pending its second member" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d "$(bond_body "Us")"
BOND_ID="$(printf '%s' "$LAST_BODY" | jget id)"
CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
header_is "…with an ETag of the row version" ETag '"0"'
# The alphabet is states.md §2's thirty symbols; 0/O, 1/I/L and U are absent
# because a code is read aloud down a phone line (T-06, ADR-0026).
if [[ "$CODE" =~ ^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{6}$ ]]; then pass "…code $CODE is six characters of the 30-symbol alphabet"; else fail "invite code" "got '$CODE'"; fi
if [[ "$LAST_BODY" == *"\"link\":\"https://moyi.com/i/$CODE\""* ]]; then pass "…and the link carries it"; else fail "invite link" "${LAST_BODY:0:200}"; fi
# states.md §8: never the other member's settings. And a user id is identity's
# to hand out, not this module's to echo.
if [[ "$LAST_BODY" != *userId* && "$LAST_BODY" != *reminderTimezone* ]]; then pass "…and no userId or partner settings in the body"; else fail "response leakage" "${LAST_BODY:0:200}"; fi

expect "GET /bonds lists it" 200 "\"id\":\"$BOND_ID\"" -- "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS"
expect "GET /bonds/{id} is 200 for the owner" 200 '"role":"OWNER"' -- "$API/bonds/$BOND_ID" -H "Authorization: Bearer $BOND_ACCESS"
header_is "…with the ETag" ETag '"0"'

expect "a fixed-offset zone is 422 on anchorTimezone" 422 '"field":"anchorTimezone"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d '{"name":"Us","type":"COUPLE","anchorTimezone":"Etc/GMT+3"}'
expect "an unknown type is 422 on type" 422 '"field":"type"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d '{"name":"Us","type":"THROUPLE","anchorTimezone":"Africa/Lagos"}'
# A non-breaking space passes `@NotBlank` and is blank to Kotlin, so before the
# review of #40 this was a 500 on a well-formed request (ADR-0029 §13).
expect "a name of one non-breaking space is 422, not 500" 422 '"field":"name"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d '{"name":"\u00a0","type":"COUPLE","anchorTimezone":"Africa/Lagos"}'

expect "second bond is 201" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d "$(bond_body "Two")"
expect "third bond is 201" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d "$(bond_body "Three")"
expect "a fourth open bond is 409 BOND_LIMIT_REACHED" 409 '"code":"BOND_LIMIT_REACHED"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d "$(bond_body "Four")"

# A third account: registered, never verified. It may sign in (FR-002) and it
# may not create a bond — and somebody else's bond does not exist for it.
STRANGER="stranger-$(date +%s)-$RANDOM@example.com"
expect "register a stranger" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.30' -d "$(register_body "$STRANGER")"
expect "the stranger signs in, unverified" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$STRANGER" "$PASSWORD")"
STRANGER_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
expect "an unverified account cannot create a bond: 403 EMAIL_NOT_VERIFIED" 403 '"code":"EMAIL_NOT_VERIFIED"' -- -X POST "$API/bonds" -H "Authorization: Bearer $STRANGER_ACCESS" -d "$(bond_body "Mine")"
expect "the stranger's GET of someone else's bond is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$BOND_ID" -H "Authorization: Bearer $STRANGER_ACCESS"
STRANGER_404="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
expect "a random id is 404 too" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$(python3 -c 'import uuid; print(uuid.uuid4())')" -H "Authorization: Bearer $BOND_ACCESS"
RANDOM_404="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
# T-02: a 403 for the stranger would confirm the bond is real. The bodies must
# be indistinguishable apart from the path the caller typed.
[ "$STRANGER_404" = "$RANDOM_404" ] && pass "…with a byte-identical body (no existence oracle)" || fail "identical 404s" "bodies differ"
expect "a value that is not an id is 404 as well" 404 '"code":"NOT_FOUND"' -- "$API/bonds/not-a-bond" -H "Authorization: Bearer $BOND_ACCESS"
expect "the stranger's list is empty" 200 '"bonds":[]' -- "$API/bonds" -H "Authorization: Bearer $STRANGER_ACCESS"
if grep -qF -- "$CODE" "$MOYI_LOG"; then fail "code in log" "the invite code appears in the log"; else pass "the invite code never appears in the log"; fi

echo; echo "invites and pairing — M2 (FR-022, FR-023, FR-024, T-06, ADR-0027)"
flush_buckets
# A second account, verified, so it may join (FR-002).
JOINER="joiner-$(date +%s)-$RANDOM@example.com"
EMAILS_BEFORE="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "register the joiner" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.40' -d "$(register_body "$JOINER")"
for _ in $(seq 1 40); do [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$EMAILS_BEFORE" ] && break; sleep 0.25; done
JOINER_TOKEN="$(grep -oE 'token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
expect "verify the joiner" 200 "" -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$JOINER_TOKEN\"}"
expect "the joiner signs in" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$JOINER" "$PASSWORD")"
JOINER_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"

# BOND_ID and CODE are the bond and code from the previous section.
expect "the joiner resolves the code and sees who invited them" 200 '"inviterDisplayName":"Smoke"' -- "$API/invites/$CODE" -H "Authorization: Bearer $JOINER_ACCESS"
[[ "$LAST_BODY" == *'"bondName":"Us"'* ]] && pass "…and the bond's name" || fail "preview" "${LAST_BODY:0:200}"
expect "a lowercase code resolves too" 200 '"bondName"' -- "$API/invites/$(echo "$CODE" | tr 'A-Z' 'a-z')" -H "Authorization: Bearer $JOINER_ACCESS"
expect "a malformed code is 422 on the field" 422 '"field":"code"' -- "$API/invites/ABC" -H "Authorization: Bearer $JOINER_ACCESS"

expect "the joiner accepts: 200, and the bond is ACTIVE" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$CODE/accept" -H "Authorization: Bearer $JOINER_ACCESS"
MEMBERS="$(python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d['members']))" <<<"$LAST_BODY")"
[ "$MEMBERS" = 2 ] && pass "…with both members in it" || fail "members" "expected 2 members, got $MEMBERS"
[[ "$LAST_BODY" == *'"invite":null'* ]] && pass "…and no live invite left" || fail "invite cleared" "${LAST_BODY:0:250}"
expect "the creator sees the same active bond" 200 '"status":"ACTIVE"' -- "$API/bonds/$BOND_ID" -H "Authorization: Bearer $BOND_ACCESS"

# FR-024: every way a code fails is one answer. A spent code and one that was
# never issued must be indistinguishable.
# A *verified* caller: the stranger above is deliberately unverified, and
# FR-002 is checked before the code is, so they would be refused earlier.
expect "the spent code is 404 INVITE_NOT_USABLE" 404 '"code":"INVITE_NOT_USABLE"' -- -X POST "$API/invites/$CODE/accept" -H "Authorization: Bearer $JOINER_ACCESS"
SPENT_404="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
expect "a code that never existed is 404 too" 404 '"code":"INVITE_NOT_USABLE"' -- -X POST "$API/invites/ZZZZZZ/accept" -H "Authorization: Bearer $JOINER_ACCESS"
NEVER_404="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
[ "$SPENT_404" = "$NEVER_404" ] && pass "…with a byte-identical body (FR-024, one answer)" || fail "one answer" "bodies differ"
expect "a full bond takes no new invite: 409 BOND_FULL" 409 '"code":"BOND_FULL"' -- -X POST "$API/bonds/$BOND_ID/invites" -H "Authorization: Bearer $BOND_ACCESS"
expect "an unverified account is refused before the code is even read: 403" 403 '"code":"EMAIL_NOT_VERIFIED"' -- -X POST "$API/invites/$CODE/accept" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "a non-member cannot invite into it: 404" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$BOND_ID/invites" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…and still cannot read it" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$BOND_ID" -H "Authorization: Bearer $STRANGER_ACCESS"

# Revoking, on a bond that still has room.
expect "a second bond for the joiner" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $JOINER_ACCESS" -d '{"name":"Two","type":"FRIENDS","anchorTimezone":"Europe/London"}'
SECOND_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
SECOND_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
SECOND_INVITE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['id'])" <<<"$LAST_BODY")"
expect "issuing a new invite is 201" 201 '"code"' -- -X POST "$API/bonds/$SECOND_BOND/invites" -H "Authorization: Bearer $JOINER_ACCESS"
THIRD_CODE="$(printf '%s' "$LAST_BODY" | jget code)"
expect "…and the replaced code no longer works" 404 '"code":"INVITE_NOT_USABLE"' -- "$API/invites/$SECOND_CODE" -H "Authorization: Bearer $BOND_ACCESS"
expect "the new one does" 200 '"bondName":"Two"' -- "$API/invites/$THIRD_CODE" -H "Authorization: Bearer $BOND_ACCESS"
expect "revoking another bond's invite id is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$SECOND_BOND/invites/$SECOND_INVITE" -H "Authorization: Bearer $JOINER_ACCESS"
THIRD_INVITE="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT id FROM bond_invites WHERE code='$THIRD_CODE'" 2>/dev/null | tr -d '[:space:]')"
expect "revoking the live one is 204" 204 "" -- -X DELETE "$API/bonds/$SECOND_BOND/invites/$THIRD_INVITE" -H "Authorization: Bearer $JOINER_ACCESS"
expect "…and it stops working at once" 404 '"code":"INVITE_NOT_USABLE"' -- "$API/invites/$THIRD_CODE" -H "Authorization: Bearer $BOND_ACCESS"
expect "revoking it again is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$SECOND_BOND/invites/$THIRD_INVITE" -H "Authorization: Bearer $JOINER_ACCESS"

if grep -qF -- "$CODE" "$MOYI_LOG" || grep -qF -- "$THIRD_CODE" "$MOYI_LOG"; then fail "code in log" "an invite code appears in the log"; else pass "no invite code appears in the log"; fi

echo; echo "ending — leave and block (FR-026, FR-029, T-09, doc 26 §2.1, ADR-0028)"
flush_buckets

# Registers, verifies and signs in one account, leaving its token in
# ACCOUNT_ACCESS. Factored out because this section needs four accounts and the
# sequence was already written twice inline above. Every account has the same
# display name ("Smoke", from register_body), which is what lets two bonds be
# compared byte for byte below. A different X-Forwarded-For each time, so the
# per-IP registration bucket is not what this section ends up testing.
verified_account() {
  local label="$1" ip="$2" email before token
  email="end-$label-$(date +%s)-$RANDOM@example.com"
  before="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
  expect "register $label" 201 "" -- -X POST "$API/auth/register" -H "X-Forwarded-For: $ip" -d "$(register_body "$email")"
  for _ in $(seq 1 40); do [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$before" ] && break; sleep 0.25; done
  token="$(grep -oE 'token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
  expect "verify $label" 200 "" -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$token\"}"
  expect "$label signs in" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$email" "$PASSWORD")"
  ACCOUNT_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
}

etag_of() { printf '%s\n' "$LAST_HEADERS" | grep -i '^etag:' | head -1 | cut -d' ' -f2-; }

# Two pairs, built identically, ended differently. Doc 26 §2.1 is about what the
# OTHER member can see, so anything that differs between the two archived bonds
# is something a blocked person could use to tell they were blocked (T-09).
verified_account "leaver" "203.0.113.50";  LEAVER_ACCESS="$ACCOUNT_ACCESS"
verified_account "stayer" "203.0.113.51";  STAYER_ACCESS="$ACCOUNT_ACCESS"
verified_account "blocker" "203.0.113.52"; BLOCKER_ACCESS="$ACCOUNT_ACCESS"
verified_account "blocked" "203.0.113.53"; BLOCKED_ACCESS="$ACCOUNT_ACCESS"

expect "a bond to leave is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Us")"
LEFT_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
LEFT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
LEFT_INVITE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['id'])" <<<"$LAST_BODY")"
expect "the other member joins it" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$LEFT_CODE/accept" -H "Authorization: Bearer $STAYER_ACCESS"
expect "leaving is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $LEAVER_ACCESS"

expect "a bond to block in is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BLOCKER_ACCESS" -d "$(bond_body "Us")"
BLOCK_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
BLOCK_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the other member joins that one too" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$BLOCK_CODE/accept" -H "Authorization: Bearer $BLOCKED_ACCESS"
expect "blocking is 204 — the same 204" 204 "" -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Authorization: Bearer $BLOCKER_ACCESS"

# The archive stays readable for both, including whoever left (states.md §9).
expect "the member who left still reads the bond" 200 '"status":"ARCHIVED"' -- "$API/bonds/$LEFT_BOND" -H "Authorization: Bearer $LEAVER_ACCESS"
[[ "$LAST_BODY" == *'"invite":null'* ]] && pass "…and its code is gone from the body" || fail "invite cleared" "${LAST_BODY:0:200}"
expect "the one who stayed reads it too" 200 '"status":"ARCHIVED"' -- "$API/bonds/$LEFT_BOND" -H "Authorization: Bearer $STAYER_ACCESS"
LEFT_VIEW="$LAST_BODY"; LEFT_ETAG="$(etag_of)"
expect "the blocked member reads theirs, unaware" 200 '"status":"ARCHIVED"' -- "$API/bonds/$BLOCK_BOND" -H "Authorization: Bearer $BLOCKED_ACCESS"
BLOCK_VIEW="$LAST_BODY"; BLOCK_ETAG="$(etag_of)"

# Doc 26 §2.1 on the wire: normalise the ids and timestamps two different bonds
# cannot share, and what is left must be the same bytes.
if python3 - "$LEFT_VIEW" "$BLOCK_VIEW" <<'PYEOF'
import re, sys

def normalise(body):
    ids = {}
    body = re.sub(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        lambda m: ids.setdefault(m.group(0), "uuid-%d" % len(ids)),
        body,
    )
    return re.sub(r"\d{4}-\d{2}-\d{2}T[0-9:.]+Z", "timestamp", body)

left, blocked = normalise(sys.argv[1]), normalise(sys.argv[2])
if left != blocked:
    print(left, file=sys.stderr)
    print(blocked, file=sys.stderr)
    sys.exit(1)
PYEOF
then pass "a block is indistinguishable from a leave, byte for byte (doc 26 §2.1)"
else fail "discreet exit" "the two archived bonds do not read the same"; fi
# The ETag is the row version: a block that wrote once more than a leave would
# show here and nowhere else.
[ "$LEFT_ETAG" = "$BLOCK_ETAG" ] && pass "…and the ETags match, so the version counts no blocks ($LEFT_ETAG)" || fail "discreet exit etag" "leave $LEFT_ETAG vs block $BLOCK_ETAG"
[[ "${BLOCK_VIEW,,}" != *block* ]] && pass "…and no response anywhere says block" || fail "discreet exit wording" "${BLOCK_VIEW:0:200}"

# An archived bond takes no writes (BR-9, the design's §6.3).
expect "leaving twice is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $STAYER_ACCESS"
expect "inviting into an archived bond is 409" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$LEFT_BOND/invites" -H "Authorization: Bearer $STAYER_ACCESS"
expect "revoking its invite is 409 too" 409 '"code":"BOND_ARCHIVED"' -- -X DELETE "$API/bonds/$LEFT_BOND/invites/$LEFT_INVITE" -H "Authorization: Bearer $LEAVER_ACCESS"
expect "the code it carried is dead" 404 '"code":"INVITE_NOT_USABLE"' -- "$API/invites/$LEFT_CODE" -H "Authorization: Bearer $STAYER_ACCESS"

# Block is the one write an archived bond accepts, and it repeats (FR-029).
expect "blocking an archived bond is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $STAYER_ACCESS"
expect "blocking again is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $STAYER_ACCESS"
BLOCK_ROWS="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT count(*) FROM blocks WHERE bond_id='$LEFT_BOND'" 2>/dev/null | tr -d '[:space:]' || echo psql-unavailable)"
case "$BLOCK_ROWS" in psql-unavailable) echo "  skip block row count";; 1) pass "…and one row, not two";; *) fail "block rows" "expected 1, got '$BLOCK_ROWS'";; esac

# A non-member is refused before the bond's state is even looked at (T-02).
expect "a stranger cannot leave someone else's bond" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…nor block in it" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $STRANGER_ACCESS"

# FR-029: the pair cannot be put back in touch, whichever of them holds the code.
expect "the blocker starts a new bond" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BLOCKER_ACCESS" -d "$(bond_body "Again")"
REPAIR_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the blocked account cannot join it: 404 INVITE_NOT_USABLE" 404 '"code":"INVITE_NOT_USABLE"' -- -X POST "$API/invites/$REPAIR_CODE/accept" -H "Authorization: Bearer $BLOCKED_ACCESS"

# FR-025: leaving frees the slot the limit counts.
# The bond they left is archived and no longer counts, so all three open slots
# are free again — a fourth create is the one that would have been refused.
expect "the leaver opens another bond" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Two")"
expect "and another" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Three")"
expect "a third open one is 201, because the bond they left frees its slot" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Four")"
expect "…and a fourth is 409 BOND_LIMIT_REACHED" 409 '"code":"BOND_LIMIT_REACHED"' -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Five")"

if grep -qiE '\bblock' "$MOYI_LOG"; then fail "block in log" "the log says block"; else pass "the log never says who blocked whom"; fi

echo; echo "settings — the conditional update (FR-027, doc 06 §1, ADR-0029)"
flush_buckets
verified_account "settler" "203.0.113.60"; SETTLER_ACCESS="$ACCOUNT_ACCESS"
expect "a bond to configure is 201" 201 '"name":"Us"' -- -X POST "$API/bonds" -H "Authorization: Bearer $SETTLER_ACCESS" -d "$(bond_body "Us")"
SET_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
header_is "…with an ETag of 0" ETag '"0"'

# The four ways a condition can be wrong, before the one way it can be right.
expect "a PATCH with no If-Match is 428" 428 '"code":"PRECONDITION_REQUIRED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"name":"Us two"}'
expect "a stale If-Match is 412" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "7"' -d '{"name":"Us two"}'
# ADR-0029's two deliberate departures from RFC 9110, probed on the wire so
# they stay decisions rather than drifting into accidents.
expect "If-Match: * is 428, deliberately" 428 '"code":"PRECONDITION_REQUIRED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: *' -d '{"name":"Us two"}'
expect "a weak validator is 412" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: W/"0"' -d '{"name":"Us two"}'
expect "the right If-Match is 200" 200 '"name":"Us two"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "0"' -d '{"name":"Us two","strictMode":true}'
header_is "…and the response carries the NEW ETag" ETag '"1"'
expect "the same If-Match again is 412 — it is spent" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "0"' -d '{"name":"Us three"}'

expect "an empty patch is 422" 422 '"code":"VALIDATION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "1"' -d '{}'
# FR-027 makes the anchor zone two-party and once per 30 days, which is B5's
# endpoint. Ignoring the field here would report success for a change that
# never happened.
expect "the anchor zone is refused here, not ignored" 422 'anchorTimezone' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "1"' -d '{"anchorTimezone":"Europe/London"}'
expect "the type is patchable" 200 '"type":"FRIENDS"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "1"' -d '{"type":"FRIENDS"}'
[[ "$LAST_BODY" == *'"maxMembers":2'* ]] && pass "…and the seats do not move with it" || fail "maxMembers" "${LAST_BODY:0:200}"
expect "a reveal time can be set" 200 '"revealTimeLocal":"21:00"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "2"' -d '{"revealTimeLocal":"21:00"}'
expect "a named null clears it" 200 '"revealTimeLocal":null' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "3"' -d '{"revealTimeLocal":null}'
[[ "$LAST_BODY" == *'"strictMode":true'* ]] && pass "…and leaves the setting it did not name" || fail "patch isolation" "${LAST_BODY:0:250}"
# A patch whose values are already the row's values writes nothing, so the
# version does not move and the other member's ETag stays valid. Found by this
# script: the probe below expected a bump and there was none, because clearing
# an already-null field changes nothing (Hibernate's dirty check).
expect "a patch that changes nothing is 200 and moves no version" 200 '"revealTimeLocal":null' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "4"' -d '{"revealTimeLocal":null}'
header_is "…the same ETag it was given" ETag '"4"' 

# The member's own settings: no condition, and nobody else's to see.
expect "the member reads their own settings" 200 '"reminderTimeLocal":"20:00"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS"
expect "and replaces them" 200 '"quietHoursEnd":"07:00"' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"nicknameForOther":"Ada","reminderTimeLocal":"07:30","reminderTimezone":"Europe/London","quietHoursStart":"22:00","quietHoursEnd":"07:00"}'
expect "a PUT that omits a field clears it" 200 '"nicknameForOther":null' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00"}'
[[ "$LAST_BODY" == *'"reminderTimezone":"Europe/London"'* ]] && pass "…but keeps the zone, which is not the caller's to lose" || fail "zone reset" "${LAST_BODY:0:250}"
expect "one quiet hour without the other is 422" 422 'quietHours' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00","quietHoursStart":"22:00"}'
expect "a missing reminder time is 422 rather than a silent 20:00" 422 'reminderTimeLocal' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{}'
# A settings write is one member's business, so the bond's ETag must not move.
expect "the bond is where the last PATCH left it" 200 '"type":"FRIENDS"' -- "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS"
header_is "…with the version the last PATCH produced, unmoved by the settings write" ETag '"4"'

# T-02: a non-member is refused before the header is even read.
expect "a stranger patching it is 404, headers and all" 404 '"code":"NOT_FOUND"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $STRANGER_ACCESS" -H 'If-Match: "3"' -d '{"name":"Mine"}'
expect "…404 without a condition too, not 428" 404 '"code":"NOT_FOUND"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $STRANGER_ACCESS" -d '{"name":"Mine"}'
expect "…and cannot read its settings either" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $STRANGER_ACCESS"

# BR-9: an archived bond keeps its settings readable and takes no writes.
expect "leaving it is 204" 204 "" -- -X POST "$API/bonds/$SET_BOND/leave" -H "Authorization: Bearer $SETTLER_ACCESS"
expect "…the PATCH is then 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "5"' -d '{"name":"Us four"}'
expect "…the settings PUT is too" 409 '"code":"BOND_ARCHIVED"' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00"}'
expect "…and the settings are still readable" 200 '"reminderTimeLocal":"21:00"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS"
if grep -qE '"(nickname|quietHours)' "$MOYI_LOG"; then fail "settings in log" "a member's settings appear in the log"; else pass "no member setting appears in the log"; fi

echo; echo "two-party consent — the shared zone and closing the box (FR-027, FR-028, BR-6, ADR-0030)"
flush_buckets
verified_account "proposer" "203.0.113.70"; PROPOSER_ACCESS="$ACCOUNT_ACCESS"
verified_account "agreer" "203.0.113.71";   AGREER_ACCESS="$ACCOUNT_ACCESS"
expect "a bond for two is 201" 201 '"anchorTimezone":"Africa/Lagos"' -- -X POST "$API/bonds" -H "Authorization: Bearer $PROPOSER_ACCESS" -d "$(bond_body "Us")"
CONSENT_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
CONSENT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the other member joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$CONSENT_CODE/accept" -H "Authorization: Bearer $AGREER_ACCESS"

# states.md §8's three steps, on the wire.
expect "proposing a zone is 200 and moves nothing yet" 200 '"proposedTimezone":"Europe/London"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"Europe/London"}'
TIMEZONE_PROPOSAL_ID="$(python3 -c "import json,sys; print(json.load(sys.stdin)['pendingTimezoneChange']['id'])" <<<"$LAST_BODY")"
[[ "$LAST_BODY" == *'"anchorTimezone":"Africa/Lagos"'* ]] && pass "…the bond still says Africa/Lagos" || fail "premature move" "${LAST_BODY:0:250}"
expect "the proposer cannot confirm their own: 409" 409 '"code":"PROPOSAL_NEEDS_OTHER_MEMBER"' -- -X POST "$API/bonds/$CONSENT_BOND/timezone/confirm" -H "Authorization: Bearer $PROPOSER_ACCESS" -d "{\"proposalId\":\"$TIMEZONE_PROPOSAL_ID\"}"
expect "a second proposal is 409 PROPOSAL_PENDING" 409 '"code":"PROPOSAL_PENDING"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $AGREER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
expect "the other member confirms: 200 and the zone moves" 200 '"anchorTimezone":"Europe/London"' -- -X POST "$API/bonds/$CONSENT_BOND/timezone/confirm" -H "Authorization: Bearer $AGREER_ACCESS" -d "{\"proposalId\":\"$TIMEZONE_PROPOSAL_ID\"}"
[[ "$LAST_BODY" == *'"pendingTimezoneChange":null'* ]] && pass "…and nothing is pending any more" || fail "pending not cleared" "${LAST_BODY:0:250}"
# FR-027's month, as a 409 with a date rather than a 429 with a retry (ADR-0030).
expect "a change within thirty days is 409 TIMEZONE_CHANGE_TOO_SOON" 409 '"code":"TIMEZONE_CHANGE_TOO_SOON"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
[[ "$LAST_BODY" == *"can change again from"* ]] && pass "…and the detail names the date it becomes allowed" || fail "no date" "${LAST_BODY:0:250}"
expect "a fixed-offset zone is 422 on the field" 422 '"field":"anchorTimezone"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"Etc/GMT+3"}'
expect "a zone of one non-breaking space is 422, not 500" 422 '"field":"anchorTimezone"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"\u00a0"}'

# Cancelling a proposal, on a second bond where the month has not been spent.
expect "a second bond for the pair" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $AGREER_ACCESS" -d "$(bond_body "Two")"
SECOND_CONSENT="$(printf '%s' "$LAST_BODY" | jget id)"
SECOND_CONSENT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$SECOND_CONSENT_CODE/accept" -H "Authorization: Bearer $PROPOSER_ACCESS"
expect "a proposal on it is 200" 200 '"proposedTimezone":"Asia/Tokyo"' -- -X PATCH "$API/bonds/$SECOND_CONSENT/timezone" -H "Authorization: Bearer $AGREER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
expect "either member may cancel it: 204" 204 "" -- -X DELETE "$API/bonds/$SECOND_CONSENT/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS"
expect "cancelling again is 404" 404 "" -- -X DELETE "$API/bonds/$SECOND_CONSENT/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS"
expect "…and a fresh proposal is allowed" 200 '"proposedTimezone":"Asia/Tokyo"' -- -X PATCH "$API/bonds/$SECOND_CONSENT/timezone" -H "Authorization: Bearer $AGREER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'

# Closing the box: one route asks and agrees (FR-028, states.md §9).
expect "the first deletion request is 202, and nothing is deleted" 202 '"requestedByMemberId"' -- -X POST "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $PROPOSER_ACCESS"
[[ "$LAST_BODY" == *'"status":"ACTIVE"'* ]] && pass "…the bond is still ACTIVE" || fail "premature deletion" "${LAST_BODY:0:250}"
expect "repeating it is an idempotent 202" 202 '"status":"ACTIVE"' -- -X POST "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $PROPOSER_ACCESS"
expect "the other member's request confirms it: PENDING_DELETION" 202 '"status":"PENDING_DELETION"' -- -X POST "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $AGREER_ACCESS"
[[ "$LAST_BODY" == *'"deletionScheduledFor":"'* ]] && pass "…with a date thirty days out" || fail "no schedule" "${LAST_BODY:0:250}"

# BR-9 during the cooling-off: readable, and no other write.
CONSENT_ETAG="$(curl -sS -o /dev/null -D - "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $PROPOSER_ACCESS" | tr -d '\r' | awk 'tolower($1)=="etag:"{print $2}')"
expect "a PATCH during the cooling-off is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $PROPOSER_ACCESS" -H "If-Match: $CONSENT_ETAG" -d '{"name":"Us two"}'
expect "…so is a settings write" 409 '"code":"BOND_ARCHIVED"' -- -X PUT "$API/bonds/$CONSENT_BOND/members/me/settings" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"reminderTimeLocal":"07:30"}'
expect "…and a zone proposal" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
expect "both members still read it" 200 '"status":"PENDING_DELETION"' -- "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $AGREER_ACCESS"

# The escape hatch, which is what makes the thirty days a cooling-off.
expect "either member calls it off: 204" 204 "" -- -X DELETE "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $AGREER_ACCESS"
expect "…and the bond is ACTIVE again" 200 '"status":"ACTIVE"' -- "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $PROPOSER_ACCESS"
[[ "$LAST_BODY" == *'"deletionScheduledFor":null'* ]] && pass "…with no date on it" || fail "schedule not cleared" "${LAST_BODY:0:250}"
expect "cancelling nothing is 404" 404 "" -- -X DELETE "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $AGREER_ACCESS"

# T-02 on all five routes.
expect "a stranger cannot propose a zone" 404 '"code":"NOT_FOUND"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $STRANGER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
expect "…nor confirm one" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$CONSENT_BOND/timezone/confirm" -H "Authorization: Bearer $STRANGER_ACCESS" -d "{\"proposalId\":\"$TIMEZONE_PROPOSAL_ID\"}"
expect "…nor cancel one" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…nor ask for a deletion" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…nor cancel one" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $STRANGER_ACCESS"

# ADR-0030's decision: an archived bond refuses a deletion request whichever way
# it ended, so the blocked member cannot tell a block from a leave by trying it.
verified_account "ender" "203.0.113.72";  ENDER_ACCESS="$ACCOUNT_ACCESS"
verified_account "stayer2" "203.0.113.73"; STAYER2_ACCESS="$ACCOUNT_ACCESS"
expect "a bond to leave" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $ENDER_ACCESS" -d "$(bond_body "Us")"
LEFT_CONSENT="$(printf '%s' "$LAST_BODY" | jget id)"
LEFT_CONSENT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$LEFT_CONSENT_CODE/accept" -H "Authorization: Bearer $STAYER2_ACCESS"
expect "…and left" 204 "" -- -X POST "$API/bonds/$LEFT_CONSENT/leave" -H "Authorization: Bearer $ENDER_ACCESS"
expect "a deletion request on it is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$LEFT_CONSENT/deletion-request" -H "Authorization: Bearer $STAYER2_ACCESS"
LEFT_REFUSAL="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
verified_account "blocker2" "203.0.113.74"; BLOCKER2_ACCESS="$ACCOUNT_ACCESS"
verified_account "blocked2" "203.0.113.75"; BLOCKED2_ACCESS="$ACCOUNT_ACCESS"
expect "a bond to block in" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BLOCKER2_ACCESS" -d "$(bond_body "Us")"
BLOCK_CONSENT="$(printf '%s' "$LAST_BODY" | jget id)"
BLOCK_CONSENT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$BLOCK_CONSENT_CODE/accept" -H "Authorization: Bearer $BLOCKED2_ACCESS"
expect "…and blocked" 204 "" -- -X POST "$API/bonds/$BLOCK_CONSENT/block" -H "Authorization: Bearer $BLOCKER2_ACCESS"
expect "the blocked member's deletion request is 409 too" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$BLOCK_CONSENT/deletion-request" -H "Authorization: Bearer $BLOCKED2_ACCESS"
BLOCK_REFUSAL="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
[ "$LEFT_REFUSAL" = "$BLOCK_REFUSAL" ] && pass "…byte-identical to the left bond's refusal (doc 26 §2.1, ADR-0030)" || fail "deletion oracle" "the two refusals differ"

echo; echo "database state"
ROW="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT u.status, (u.email_verified_at IS NOT NULL), count(t.id), count(t.consumed_at) FROM users u LEFT JOIN verification_tokens t ON t.user_id=u.id WHERE u.email='$EMAIL' GROUP BY 1,2" 2>/dev/null || echo "psql-unavailable")"
# Two tokens by now — the verification link and the reset link — both consumed.
if [ "$ROW" = "ACTIVE|t|2|2" ]; then pass "user ACTIVE, verified, two tokens (verification + reset), both consumed"; elif [ "$ROW" = "psql-unavailable" ]; then echo "  skip database check (psql not reachable through docker compose)"; else fail "database row" "expected ACTIVE|t|2|2, got '$ROW'"; fi
SESSIONS="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT count(*) || '|' || count(*) FILTER (WHERE revoked_at IS NULL) FROM refresh_tokens t JOIN users u ON u.id=t.user_id WHERE u.email='$EMAIL'" 2>/dev/null || echo "psql-unavailable")"
# Every family started here was ended: two by reuse detection and logout,
# the phone's and the watch's by DELETE /auth/sessions, the rest by
# logout-all and the reset. Two live tokens remain for THIS account — the
# login after the reset and the one the bond section signed in with; the
# joiner is a different account and is not counted here.
case "$SESSIONS" in psql-unavailable) echo "  skip session check";; *"|2") pass "refresh tokens stored as hashes; exactly two live sessions remain ($SESSIONS)";; *) fail "sessions" "expected exactly two live refresh tokens, got '$SESSIONS'";; esac

echo; printf '%d passed, %d failed\n' "$PASS" "$FAIL"
# The exit status is the failure count, as the README says (capped at what a
# shell can carry), so a caller can tell one failure from several.
exit $(( FAIL > 255 ? 255 : FAIL ))
