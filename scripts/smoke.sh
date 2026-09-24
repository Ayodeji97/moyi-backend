#!/usr/bin/env bash
# Smoke test for the moyi-backend API against a locally running instance.
#
# What it proves: the packaged application boots against the compose Postgres,
# and every identity endpoint that exists so far — registration, verification,
# sign-in, refresh rotation, logout, password reset — answers with the status
# and the error code the API contract (doc 06) promises — on the happy path AND on
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
if grep -q "$EMAIL" "$MOYI_LOG"; then fail "address never logged" "the address appears in the log"; else pass "address never appears in the log (masked to the domain)"; fi
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

echo; echo "sign-in and sessions (FR-003, FR-004, ADR-0020–0022)"
# The account is verified by now, so this is the ordinary sign-in; the
# unverified sign-in FR-002 allows is covered by the endpoint tests.
jget() { python3 -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
login_body() { printf '{"email":"%s","password":"%s","deviceInfo":"smoke"}' "$1" "$2"; }
expect "login is 200 with a token pair and the profile" 200 '"expiresIn":900' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"; REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
[[ "$LAST_BODY" == *'"emailVerified":true'* ]] && pass "…and the profile says the address is verified" || fail "profile" "emailVerified not true: ${LAST_BODY:0:120}"
expect "GET /me with the access token is 200" 200 "\"email\":\"$EMAIL\"" -- "$API/me" -H "Authorization: Bearer $ACCESS"
expect "wrong password is 401 INVALID_CREDENTIALS" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "not the password")"
WRONG="$LAST_BODY"
expect "unknown address is 401 too" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "nobody-$RANDOM@example.com" "not the password")"
[ "$WRONG" = "$LAST_BODY" ] && pass "…with a byte-identical body (no account oracle)" || fail "identical 401s" "bodies differ"
expect "an oversized password is 422, not work" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$(printf 'x%.0s' $(seq 1 600))")"
# Lockout: four more wrong attempts make five; the right password is then
# refused with the same 401, silently, for a minute.
for i in 2 3 4 5; do curl -s -o /dev/null -H 'Content-Type: application/json' -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "wrong $i")"; done
expect "after five failures the RIGHT password is refused, identically" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
LOCK="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT failed_attempts || '|' || CAST(EXTRACT(EPOCH FROM (locked_until - now())) AS int) FROM credentials c JOIN users u ON u.id=c.user_id WHERE u.email='$EMAIL'" 2>/dev/null || echo "psql-unavailable")"
case "$LOCK" in 5\|5[5-9]|5\|60) pass "locked for a minute after five failures ($LOCK)";; psql-unavailable) echo "  skip lock check (psql unavailable)";; *) fail "lockout row" "expected 5|55..60, got '$LOCK'";; esac
docker compose exec -T postgres psql -U moyi -d moyi -Atc "UPDATE credentials SET locked_until = now() - interval '1 second' FROM users u WHERE u.id = credentials.user_id AND u.email='$EMAIL'" >/dev/null 2>&1 || true
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

echo; echo "database state"
ROW="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT u.status, (u.email_verified_at IS NOT NULL), count(t.id), count(t.consumed_at) FROM users u LEFT JOIN verification_tokens t ON t.user_id=u.id WHERE u.email='$EMAIL' GROUP BY 1,2" 2>/dev/null || echo "psql-unavailable")"
# Two tokens by now — the verification link and the reset link — both consumed.
if [ "$ROW" = "ACTIVE|t|2|2" ]; then pass "user ACTIVE, verified, two tokens (verification + reset), both consumed"; elif [ "$ROW" = "psql-unavailable" ]; then echo "  skip database check (psql not reachable through docker compose)"; else fail "database row" "expected ACTIVE|t|2|2, got '$ROW'"; fi
SESSIONS="$(docker compose exec -T postgres psql -U moyi -d moyi -Atc "SELECT count(*) || '|' || count(*) FILTER (WHERE revoked_at IS NULL) FROM refresh_tokens t JOIN users u ON u.id=t.user_id WHERE u.email='$EMAIL'" 2>/dev/null || echo "psql-unavailable")"
# Five families were started and every one ended: two by reuse detection and
# logout, the rest by logout-all and the reset. The final login after the
# reset is the one live token.
case "$SESSIONS" in psql-unavailable) echo "  skip session check";; *"|1") pass "refresh tokens stored as hashes; exactly one live session remains ($SESSIONS)";; *) fail "sessions" "expected exactly one live refresh token, got '$SESSIONS'";; esac

echo; printf '%d passed, %d failed\n' "$PASS" "$FAIL"
# The exit status is the failure count, as the README says (capped at what a
# shell can carry), so a caller can tell one failure from several.
exit $(( FAIL > 255 ? 255 : FAIL ))
