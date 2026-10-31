#!/usr/bin/env bash
# test-pre-deploy-hook.sh — runs .kamal/hooks/pre-deploy against throwaway git checkouts, with ssh
# replaced by a stub (MOYI_DEPLOY_SSH), and checks what it allows and refuses (review of #117).
# Needs git, ruby and bash; touches no server and no real repository.
#
#   scripts/test-pre-deploy-hook.sh [hook]   the hook to test (this checkout's by default)
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK="${1:-$here/../.kamal/hooks/pre-deploy}"
DEPLOY="$here/../config/deploy.yml"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASSED=0 FAILED=0
pass() { PASSED=$((PASSED + 1)); echo "  PASS  $1"; }
fail() { FAILED=$((FAILED + 1)); echo "  FAIL  $1${2:+ — $2}"; }

# The stub: logs its arguments, and exits with STUB_EXIT.
cat > "$TMP/ssh" <<'EOF'
#!/bin/bash
printf '%s\n' "$*" >> "$STUB_LOG"
exit "${STUB_EXIT:-0}"
EOF
chmod +x "$TMP/ssh"

# checkout <name> <deploy.yml> — a clone whose HEAD is its origin/main, holding the hook and that config.
checkout() {
  local name="$1" config="$2" dir="$TMP/$1"
  git init -q --bare "$TMP/$name.git"
  git init -q -b main "$dir"
  mkdir -p "$dir/.kamal/hooks" "$dir/config"
  cp "$HOOK" "$dir/.kamal/hooks/pre-deploy"
  cp "$config" "$dir/config/deploy.yml"
  git -C "$dir" add -A
  git -C "$dir" -c user.name=t -c user.email=t@example.com commit -q -m "chore: a test checkout"
  git -C "$dir" remote add origin "$TMP/$name.git"
  git -C "$dir" push -q origin main
  git -C "$dir" fetch -q origin
}
# run <name> <stub exit> — the hook's exit; its output in $TMP/<name>.out, the stub's calls in $TMP/<name>.ssh.
run() {
  local dir="$TMP/$1"
  : > "$TMP/$1.ssh"
  (cd "$dir" && KAMAL_VERSION="$(git rev-parse HEAD)" KAMAL_COMMAND=deploy MOYI_DEPLOY_SSH="$TMP/ssh" \
    STUB_LOG="$TMP/$1.ssh" STUB_EXIT="$2" .kamal/hooks/pre-deploy > "$TMP/$1.out" 2>&1)
}

# The configs: this checkout's (storage on), storage switched off, and no media-worker role.
cp "$DEPLOY" "$TMP/on.yml"
ruby -ryaml -e 'd = YAML.load(File.read(ARGV[0])); d["env"]["clear"]["MOYI_MEDIA_STORAGE_PROVIDER"] = "unavailable"
  File.write(ARGV[1], d.to_yaml)' "$DEPLOY" "$TMP/off.yml"
ruby -ryaml -e 'd = YAML.load(File.read(ARGV[0])); d["servers"].delete("media-worker")
  File.write(ARGV[1], d.to_yaml)' "$DEPLOY" "$TMP/noworker.yml"
ruby -ryaml -e 'exit(YAML.load(File.read(ARGV[0]))["env"]["clear"].key?("MOYI_MEDIA_STORAGE_PROVIDER") ? 1 : 0)' "$TMP/on.yml" ||
  ruby -ryaml -e 'd = YAML.load(File.read(ARGV[0])); d["env"]["clear"].delete("MOYI_MEDIA_STORAGE_PROVIDER")
    File.write(ARGV[0], d.to_yaml)' "$TMP/on.yml" # this checkout has storage off (ops/media-storage-off): turn it on for the test

echo "pre-deploy hook: $HOOK"
checkout on "$TMP/on.yml"
checkout off "$TMP/off.yml"
checkout noworker "$TMP/noworker.yml"

if run on 0; then pass "storage on, the limit reported in place: allowed"; else fail "storage on, the limit in place: allowed" "$(tail -3 "$TMP/on.out")"; fi
HOST="$(ruby -ryaml -e 'puts Array(YAML.load(File.read(ARGV[0]))["servers"]["media-worker"]["hosts"]).first' "$DEPLOY")"
if grep -q -- "-o BatchMode=yes" "$TMP/on.ssh" && grep -q "@$HOST " "$TMP/on.ssh" &&
  grep -q "systemctl is-active --quiet moyi-media-worker-egress && /usr/local/sbin/moyi-media-worker-egress check" "$TMP/on.ssh"; then
  pass "…it asked the worker's host from deploy.yml ($HOST), in batch mode, whether the limit is active and limiting"
else
  fail "…it asked the worker's host, in batch mode, the two questions" "$(cat "$TMP/on.ssh")"
fi

if run on 1; then
  fail "storage on, the limit not in place (or the server not reached): refused" "the hook allowed it"
elif grep -q "network limit is not in place" "$TMP/on.out" && grep -q "step 2" "$TMP/on.out"; then
  pass "storage on, the limit not in place (or the server not reached): refused, naming the runbook's step 2"
else
  fail "storage on, the limit not in place: refused for that reason" "$(grep REFUSED "$TMP/on.out")"
fi

if run off 1 && [ ! -s "$TMP/off.ssh" ]; then pass "storage unavailable: allowed, and the server is not asked"
else fail "storage unavailable: allowed without asking the server" "$(tail -3 "$TMP/off.out")"; fi

if run noworker 1 && [ ! -s "$TMP/noworker.ssh" ]; then pass "no media-worker role: allowed, and the server is not asked"
else fail "no media-worker role: allowed without asking the server" "$(tail -3 "$TMP/noworker.out")"; fi

# The checks before it still come first: a modified tracked file is refused before any ssh.
echo "# a change" >> "$TMP/on/config/deploy.yml"
if ! run on 0 && grep -q "tracked files differ" "$TMP/on.out" && [ ! -s "$TMP/on.ssh" ]; then
  pass "a modified deploy.yml is still refused, before the server is asked"
else
  fail "a modified deploy.yml is still refused first" "$(grep REFUSED "$TMP/on.out")"
fi

echo; echo "test-pre-deploy-hook: $PASSED passed, $FAILED failed"
[ "$FAILED" = 0 ]
