#!/usr/bin/env bash
# test-r2-switch-on-check-argv.sh — proves that scripts/r2-switch-on-check.sh puts no secret on a
# command line, where `ps` shows it to anyone on the machine (review of #117).
#
# It runs the check with every program it starts replaced by a shim that writes the program's
# arguments to a log and then runs the real one, and fails if the log holds the password, an
# access token (a JWT: "eyJ…"), "Bearer", a refresh token (43 characters of base64url), a
# presigned URL's signature or an object key. Shell builtins (printf, read, echo) start no program
# and need no shim. Takes the check's own environment (MOYI_CHECK_API, MOYI_CHECK_EMAIL,
# MOYI_CHECK_PASSWORD, which must be set here) against a server you started, e.g. against MinIO.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${MOYI_CHECK_PASSWORD:?set MOYI_CHECK_PASSWORD (the test needs to look for it)}"
SHIMS="$(mktemp -d)"; LOG="$SHIMS/argv.log"; OUT="$SHIMS/check.out"
trap 'rm -rf "$SHIMS"' EXIT
: > "$LOG"
for program in curl python3 openssl mktemp chmod rm cp wc tr sleep kill seq cat date head; do
  real="$(command -v "$program")" || continue
  cat > "$SHIMS/$program" <<EOF
#!/bin/bash
line="$program"; for a in "\$@"; do printf -v q '%q' "\$a"; line+=" \$q"; done
printf '%s\n' "\$line" >> "$LOG" # one write per line, so programs run side by side do not interleave
exec "$real" "\$@"
EOF
  chmod +x "$SHIMS/$program"
done

PATH="$SHIMS:$PATH" "$here/r2-switch-on-check.sh" > "$OUT" 2>&1
status=$?
tail -1 "$OUT"
echo "programs run: $(wc -l < "$LOG" | tr -d ' ') ($(cut -d' ' -f1 "$LOG" | sort | uniq -c | tr -s ' ' | tr '\n' ',' | sed 's/,$//'))"

bad=0
found() { echo "FAIL  $1"; bad=1; }
grep -qF -- "$MOYI_CHECK_PASSWORD" "$LOG" && found "the password is in an argument"
grep -q 'eyJ' "$LOG" && found "an access token (a JWT) is in an argument"
grep -q 'Bearer' "$LOG" && found "an Authorization header is in an argument"
grep -qE '(^|[^A-Za-z0-9_-])[A-Za-z0-9_-]{43}([^A-Za-z0-9_=-]|$)' "$LOG" && found "something shaped like a refresh token is in an argument"
grep -qE 'X-Amz-(Signature|Credential)=' "$LOG" && found "a presigned URL is in an argument"
grep -qE '(^| )(in|v)/[0-9a-f-]{36}' "$LOG" && found "an object key is in an argument"
grep -qF -- "$MOYI_CHECK_PASSWORD" "$OUT" && found "the password is in the output"
grep -qE 'eyJ|Bearer|X-Amz-(Signature|Credential)=' "$OUT" && found "a token or a URL is in the output"
[ "$status" = 0 ] || found "the check itself failed (exit $status)"
[ "$bad" = 0 ] && echo "PASS  no secret in any program's arguments, nor in the output"
exit "$bad"
