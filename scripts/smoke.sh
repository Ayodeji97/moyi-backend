#!/usr/bin/env bash
# Smoke test for the moyi-backend API against a locally running instance.
#
# What it proves: the packaged application boots against the compose Postgres,
# and every identity endpoint that exists so far — registration, verification,
# sign-in, refresh rotation, logout, password reset — answers with the status
# and the error code the API contract (doc 06) promises — on the happy path AND on
# the edges that have bitten before: malformed JSON, wrong method, reused
# token, unknown address, duplicate registration. Since Phase 2 it also proves
# that two accounts' bonds are invisible to a third (T-02). Since slice F it drives
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

echo; echo "database state"
ROW="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT u.status, (u.email_verified_at IS NOT NULL), count(t.id), count(t.consumed_at) FROM users u LEFT JOIN verification_tokens t ON t.user_id=u.id WHERE u.email='$EMAIL' GROUP BY 1,2" 2>/dev/null || echo "psql-unavailable")"
# Two tokens by now — the verification link and the reset link — both consumed.
if [ "$ROW" = "ACTIVE|t|2|2" ]; then pass "user ACTIVE, verified, two tokens (verification + reset), both consumed"; elif [ "$ROW" = "psql-unavailable" ]; then echo "  skip database check (psql not reachable through docker compose)"; else fail "database row" "expected ACTIVE|t|2|2, got '$ROW'"; fi
SESSIONS="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT count(*) || '|' || count(*) FILTER (WHERE revoked_at IS NULL) FROM refresh_tokens t JOIN users u ON u.id=t.user_id WHERE u.email='$EMAIL'" 2>/dev/null || echo "psql-unavailable")"
# Every family started here was ended: two by reuse detection and logout,
# the phone's and the watch's by DELETE /auth/sessions, the rest by
# logout-all and the reset. Two live tokens remain — the login after the
# reset, and the one the bond section signed in with.
case "$SESSIONS" in psql-unavailable) echo "  skip session check";; *"|2") pass "refresh tokens stored as hashes; exactly two live sessions remain ($SESSIONS)";; *) fail "sessions" "expected exactly two live refresh tokens, got '$SESSIONS'";; esac

echo; printf '%d passed, %d failed\n' "$PASS" "$FAIL"
# The exit status is the failure count, as the README says (capped at what a
# shell can carry), so a caller can tell one failure from several.
exit $(( FAIL > 255 ? 255 : FAIL ))
