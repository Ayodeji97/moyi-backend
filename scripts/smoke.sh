#!/usr/bin/env bash
# Smoke test for the moyi-backend API against a locally running instance.
#
# What it proves: the packaged application boots against the compose Postgres,
# and every identity endpoint that exists so far answers with the status and
# the error code the API contract (doc 06) promises — on the happy path AND on
# the edges that have bitten before: malformed JSON, wrong method, reused
# token, unknown address, duplicate registration.
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

# expect <label> <expected-status> [expected-substring-in-body] -- <curl args>
expect() {
  local label="$1" want="$2" needle="${3:-}"; shift 3; [ "${1:-}" = "--" ] && shift
  local body status
  body="$(mktemp)"
  # JSON by default; a probe that names its own Content-Type gets only that one.
  local -a headers=(-H 'Content-Type: application/json')
  case "$*" in *Content-Type:*) headers=() ;; esac
  status="$(curl -s -o "$body" -w '%{http_code}' "${headers[@]}" "$@")"
  local text; text="$(cat "$body")"; rm -f "$body"
  if [ "$status" != "$want" ]; then
    fail "$label" "expected HTTP $want, got $status: ${text:0:200}"
  elif [ -n "$needle" ] && [[ "$text" != *"$needle"* ]]; then
    fail "$label" "expected body to contain '$needle': ${text:0:200}"
  else
    pass "$label ($status)"
  fi
  LAST_BODY="$text"
}

if [ "$ATTACH" = 0 ]; then
  command -v docker >/dev/null || { echo "docker is required"; exit 1; }
  docker compose up -d postgres >/dev/null
  if [ "$BUILD" = 1 ]; then
    echo "building the jar…"
    ./gradlew -q :app:bootJar
  fi
  JAR="$(ls app/build/libs/app-*-SNAPSHOT.jar | grep -v plain | head -1)"
  MOYI_LOG="$(mktemp -t moyi-smoke.XXXXXX.log)"
  echo "booting $JAR (local profile), log: $MOYI_LOG"
  java -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" >"$MOYI_LOG" 2>&1 &
  APP_PID=$!
  trap 'kill $APP_PID 2>/dev/null; wait $APP_PID 2>/dev/null || true' EXIT
  for _ in $(seq 1 90); do
    grep -q "Started MoyiApplication" "$MOYI_LOG" && break
    grep -q "APPLICATION FAILED" "$MOYI_LOG" && { echo "the application failed to start:"; grep -A3 "APPLICATION FAILED" "$MOYI_LOG"; exit 1; }
    sleep 1
  done
  grep -q "Started MoyiApplication" "$MOYI_LOG" || { echo "timed out waiting for startup"; tail -20 "$MOYI_LOG"; exit 1; }
else
  : "${MOYI_LOG:?--attach needs MOYI_LOG=<path to the log file of the running server>}"
fi

EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASSWORD="correct horse battery"
register_body() { printf '{"email":"%s","password":"%s","displayName":"Smoke","locale":"en","acceptedTermsVersion":"2026-09-01","over18":true}' "$1" "$PASSWORD"; }

echo; echo "health"
expect "GET /actuator/health is UP" 200 '"status":"UP"' -- "$BASE/actuator/health"

echo; echo "registration (FR-001, FR-011, ADR-0015)"
EMAILS_BEFORE="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "valid registration is 201 with an empty body" 201 "" -- -X POST "$API/auth/register" -d "$(register_body "$EMAIL")"
[ -z "$LAST_BODY" ] && pass "…and the body really is empty" || fail "empty body" "got: ${LAST_BODY:0:100}"
expect "same address again (different case) is still 201" 201 "" -- -X POST "$API/auth/register" -d "$(register_body "$(echo "$EMAIL" | tr a-z A-Z)")"
expect "7-character password is 422 VALID_PASSWORD" 422 '"code":"VALID_PASSWORD"' -- -X POST "$API/auth/register" -d '{"email":"x@example.com","password":"short12","displayName":"S","acceptedTermsVersion":"1","over18":true}'
expect "breached password is 422 NOT_BREACHED" 422 '"code":"NOT_BREACHED"' -- -X POST "$API/auth/register" -d '{"email":"x@example.com","password":"password","displayName":"S","acceptedTermsVersion":"1","over18":true}'
expect "over18=false is 422 on field over18" 422 '"field":"over18"' -- -X POST "$API/auth/register" -d '{"email":"x@example.com","password":"correct horse battery","displayName":"S","acceptedTermsVersion":"1","over18":false}'
expect "malformed JSON is 400 MALFORMED_REQUEST" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/auth/register" -d '{"email": '
expect "wrong content type is 415" 415 '"code":"UNSUPPORTED_MEDIA_TYPE"' -- -X POST "$API/auth/register" -H 'Content-Type: text/plain' -d 'x'
expect "unknown route is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- "$API/auth/nope"

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
if grep -q "$EMAIL" "$MOYI_LOG"; then fail "address never logged" "the address appears in the log"; else pass "address never appears in the log (masked to the domain)"; fi
expect "garbage token is 422 VERIFICATION_TOKEN_INVALID" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -d '{"token":"not-a-token"}'
expect "blank token is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/verify-email" -d '{"token":" "}'
expect "GET on verify-email is 405 (a scanner cannot spend a token)" 405 '"code":"METHOD_NOT_ALLOWED"' -- "$API/auth/verify-email?token=$SECRET"
expect "real token verifies: 200, empty body" 200 "" -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$SECRET\"}"
expect "same token again is 410 VERIFICATION_TOKEN_EXPIRED" 410 '"code":"VERIFICATION_TOKEN_EXPIRED"' -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$SECRET\"}"
expect "resend for a verified address is 202, empty" 202 "" -- -X POST "$API/auth/resend-verification" -d "{\"email\":\"$EMAIL\"}"
expect "resend for an unknown address is 202, identical" 202 "" -- -X POST "$API/auth/resend-verification" -d '{"email":"nobody-here@example.com"}'
expect "resend for a malformed address is 422" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/resend-verification" -d '{"email":"not-an-address"}'

echo; echo "database state"
ROW="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT u.status, (u.email_verified_at IS NOT NULL), count(t.id), count(t.consumed_at) FROM users u LEFT JOIN verification_tokens t ON t.user_id=u.id WHERE u.email='$EMAIL' GROUP BY 1,2" 2>/dev/null || echo "psql-unavailable")"
if [ "$ROW" = "ACTIVE|t|1|1" ]; then pass "user ACTIVE, verified, one token, consumed"; elif [ "$ROW" = "psql-unavailable" ]; then echo "  skip database check (psql not reachable through docker compose)"; else fail "database row" "expected ACTIVE|t|1|1, got '$ROW'"; fi

echo; printf '%d passed, %d failed\n' "$PASS" "$FAIL"
# The exit status is the failure count, as the README says (capped at what a
# shell can carry), so a caller can tell one failure from several.
exit $(( FAIL > 255 ? 255 : FAIL ))
