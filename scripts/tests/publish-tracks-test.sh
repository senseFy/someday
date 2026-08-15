#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PUBLISH_SCRIPT="$ROOT_DIR/scripts/publish-tracks.sh"
FIXTURES="$(mktemp -d "${TMPDIR:-/tmp}/someday-publish-tracks-test.XXXXXX")"
trap 'rm -rf "$FIXTURES"' EXIT

unset SOMEDAY_ANDROID_TRACK ANDROID_PLAY_DRY_RUN

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local needle="$1"
  local path="$2"
  grep -F -- "$needle" "$path" >/dev/null ||
    fail "Expected '$needle' in $path."
}

assert_not_contains() {
  local needle="$1"
  local path="$2"
  if grep -F -- "$needle" "$path" >/dev/null; then
    fail "Did not expect '$needle' in $path."
  fi
}

assert_called() {
  local target="$1"
  local state="${2:-start}"
  local calls="$3"
  grep -Fx "$target:$state" "$calls" >/dev/null ||
    fail "Expected $target to reach $state."
}

assert_not_called() {
  local target="$1"
  local calls="$2"
  if grep -E "^${target}:(start|done|failed)$" "$calls" >/dev/null; then
    fail "Did not expect $target to run."
  fi
}

assert_before() {
  local earlier="$1"
  local later="$2"
  local calls="$3"
  local earlier_line
  local later_line

  earlier_line="$(grep -n -F "$earlier" "$calls" | head -1 | cut -d: -f1)"
  later_line="$(grep -n -F "$later" "$calls" | head -1 | cut -d: -f1)"
  [[ -n "$earlier_line" && -n "$later_line" ]] ||
    fail "Could not verify release phase order: $earlier -> $later."
  (( earlier_line < later_line )) ||
    fail "Release phase order was invalid: $earlier must precede $later."
}

assert_call_argument() {
  local target="$1"
  local argument="$2"
  local calls="$3"
  grep -F -- "$target:args:" "$calls" | grep -F -- "$argument" >/dev/null ||
    fail "Expected $target to receive '$argument'."
}

assert_no_uploads() {
  local calls="$1"
  assert_not_called android-upload-play "$calls"
  assert_not_called ios-upload-archive "$calls"
}

create_case() {
  local name="$1"
  local directory="$FIXTURES/$name"
  mkdir -p "$directory/logs"
  : > "$directory/calls"
  printf '%s\n' "$directory"
}

run_publish() {
  local directory="$1"
  local fail_target="$2"
  shift 2

  CALL_LOG="$directory/calls" \
    FAIL_TARGET="$fail_target" \
    SLOW_TARGET="${SLOW_TARGET:-}" \
    SOMEDAY_MAKE_COMMAND="$FIXTURES/make-stub" \
    SOMEDAY_RELEASE_LOG_ROOT="$directory/logs" \
    SOMEDAY_RELEASE_LOCK_DIR="$FIXTURES/release.lock" \
    "$PUBLISH_SCRIPT" "$@" > "$directory/output" 2>&1 </dev/null
}

[[ -x "$PUBLISH_SCRIPT" ]] ||
  fail "Publish coordinator is missing or is not executable: $PUBLISH_SCRIPT"

cat > "$FIXTURES/make-stub" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${MAKEFLAGS:-}${MFLAGS:-}${MAKEOVERRIDES:-}${GNUMAKEFLAGS:-}" ]]; then
  printf 'Unsafe recursive Make flags reached the release target.\n' >&2
  exit 65
fi

target=""
for argument in "$@"; do
  case "$argument" in
    android-upload-check|ios-upload-check|android-release-play|ios-archive|android-upload-play|ios-upload-archive)
      target="$argument"
      ;;
  esac
done

[[ -n "$target" ]] || {
  printf 'Unexpected make invocation:' >&2
  printf ' %q' "$@" >&2
  printf '\n' >&2
  exit 64
}

printf '%s:start\n' "$target" >> "$CALL_LOG"
{
  printf '%s:args:' "$target"
  printf ' %q' "$@"
  printf '\n'
} >> "$CALL_LOG"
printf 'test-log:%s\n' "$target"

if [[ "$target" == "${SLOW_TARGET:-}" ]]; then
  sleep 1
fi

if [[ "$target" == "$FAIL_TARGET" ]]; then
  case "$target" in
    ios-archive|ios-upload-archive) sleep 1 ;;
  esac
  printf '%s:failed\n' "$target" >> "$CALL_LOG"
  printf 'simulated failure: %s\n' "$target" >&2
  exit 71
fi

printf '%s:done\n' "$target" >> "$CALL_LOG"
EOF
chmod +x "$FIXTURES/make-stub"

directory="$(create_case help)"
run_publish "$directory" "" --help
assert_contains "--prepare-only" "$directory/output"
assert_contains "--no-tui" "$directory/output"
[[ ! -s "$directory/calls" ]] || fail "Help invoked a release phase."

directory="$(create_case preflight-failure)"
if run_publish "$directory" android-upload-check --yes --no-tui; then
  fail "A failed preflight was reported as successful."
fi
assert_called android-upload-check failed "$directory/calls"
assert_not_called android-release-play "$directory/calls"
assert_not_called ios-archive "$directory/calls"
assert_no_uploads "$directory/calls"

directory="$(create_case preparation-failure)"
if run_publish "$directory" ios-archive --yes --no-tui; then
  fail "A failed preparation was reported as successful."
fi
assert_called android-upload-check done "$directory/calls"
assert_called ios-upload-check done "$directory/calls"
assert_called ios-archive failed "$directory/calls"
assert_before ios-upload-check:done ios-archive:start "$directory/calls"
assert_no_uploads "$directory/calls"

directory="$(create_case immediate-preparation-failure)"
if run_publish "$directory" android-release-play --yes --no-tui; then
  fail "An immediate preparation failure was reported as successful."
fi
assert_called android-release-play failed "$directory/calls"
assert_not_called ios-archive "$directory/calls"
assert_no_uploads "$directory/calls"

directory="$(create_case non-tty-confirmation)"
if run_publish "$directory" "" --no-tui; then
  fail "A non-interactive release without --yes was accepted."
fi
[[ ! -s "$directory/calls" ]] ||
  fail "A non-interactive release started work before requiring --yes."
assert_no_uploads "$directory/calls"
assert_contains "--yes" "$directory/output"

release_lock="$FIXTURES/release.lock"
if [[ -e "$release_lock" ]]; then
  fail "Cannot exercise lock contention while a combined release is active."
fi
mkdir -p "$release_lock"
printf '%s\n' "$$" > "$release_lock/pid"
directory="$(create_case release-lock-contention)"
lock_was_acquired=0
run_publish "$directory" "" --prepare-only --no-tui && lock_was_acquired=1
rm -f "$release_lock/pid"
rmdir "$release_lock"
[[ "$lock_was_acquired" == 0 ]] ||
  fail "A second combined release acquired an active release lock."
[[ ! -s "$directory/calls" ]] ||
  fail "Lock contention was detected after a release phase started."
assert_contains "already running (PID $$)" "$directory/output"

directory="$(create_case prepare-only)"
run_publish "$directory" "" --prepare-only --no-tui
assert_called android-release-play done "$directory/calls"
assert_called ios-archive done "$directory/calls"
assert_no_uploads "$directory/calls"

directory="$(create_case unsafe-make-flags)"
MAKEFLAGS=-i MFLAGS=-i MAKEOVERRIDES=unsafe GNUMAKEFLAGS=-i \
  run_publish "$directory" "" --prepare-only --no-tui
assert_called android-release-play done "$directory/calls"
assert_called ios-archive done "$directory/calls"
assert_no_uploads "$directory/calls"

directory="$(create_case dual-success)"
run_publish "$directory" "" --yes --no-tui
assert_called android-upload-check done "$directory/calls"
assert_called ios-upload-check done "$directory/calls"
assert_called android-release-play done "$directory/calls"
assert_called ios-archive done "$directory/calls"
assert_called android-upload-play done "$directory/calls"
assert_called ios-upload-archive done "$directory/calls"
assert_before android-release-play:done android-upload-play:start "$directory/calls"
assert_before ios-archive:done ios-upload-archive:start "$directory/calls"
assert_call_argument android-upload-check "ANDROID_PLAY_TRACK=internal" "$directory/calls"
assert_call_argument android-upload-play "ANDROID_PLAY_TRACK=internal" "$directory/calls"
assert_call_argument android-upload-play "ANDROID_PLAY_RELEASE_STATUS=completed" "$directory/calls"
assert_call_argument android-upload-play "ANDROID_PLAY_CONFIRM_PRODUCTION=" "$directory/calls"
assert_call_argument android-upload-play "ANDROID_PLAY_DRY_RUN=" "$directory/calls"

log_files="$directory/log-files"
find "$directory/logs" -type f -print > "$log_files"
[[ -s "$log_files" ]] || fail "A successful release did not persist logs."
combined_logs="$directory/combined-logs"
while IFS= read -r path; do
  cat "$path"
done < "$log_files" > "$combined_logs"
assert_contains "test-log:android-release-play" "$combined_logs"
assert_contains "test-log:ios-archive" "$combined_logs"
assert_contains "test-log:android-upload-play" "$combined_logs"
assert_contains "test-log:ios-upload-archive" "$combined_logs"

directory="$(create_case partial-upload)"
if run_publish "$directory" ios-upload-archive --yes --no-tui; then
  fail "A partial upload was reported as successful."
fi
assert_called android-upload-play done "$directory/calls"
assert_called ios-upload-archive failed "$directory/calls"
assert_contains "make ios-upload-archive" "$directory/output"

directory="$(create_case independent-upload-status)"
SLOW_TARGET=android-upload-play \
  run_publish "$directory" "" --yes --no-tui
assert_before \
  "[iOS] Uploaded to TestFlight processing" \
  "[Android] Published to Play internal" \
  "$directory/output"

if [[ "$(uname)" == "Darwin" ]] && command -v expect >/dev/null 2>&1; then
  directory="$(create_case tui-render)"
  CALL_LOG="$directory/calls" \
    FAIL_TARGET="" \
    SOMEDAY_MAKE_COMMAND="$FIXTURES/make-stub" \
    SOMEDAY_RELEASE_LOG_ROOT="$directory/logs" \
    SOMEDAY_RELEASE_LOCK_DIR="$FIXTURES/release-tui.lock" \
    TERM=xterm-256color \
    NO_COLOR= \
    PUBLISH_SCRIPT_UNDER_TEST="$PUBLISH_SCRIPT" \
    expect -c '
      set timeout 60
      spawn -noecho $env(PUBLISH_SCRIPT_UNDER_TEST) --prepare-only
      expect eof
      catch wait result
      exit [lindex $result 3]
    ' > "$directory/pty-output" 2>&1 ||
    fail "The TUI prepare-only run did not complete."
  grep -aqF "Someday test-track release" "$directory/pty-output" ||
    fail "The TUI run never rendered its dashboard."
  grep -aqF $'\033[?2026h' "$directory/pty-output" ||
    fail "TUI frames are not wrapped in synchronized updates."
  grep -aqF $'\033[K' "$directory/pty-output" ||
    fail "TUI lines do not erase stale content in place."
  full_clear_count="$(grep -aoF $'\033[2J' "$directory/pty-output" | wc -l | tr -d '[:space:]')"
  [[ "$full_clear_count" == "1" ]] ||
    fail "The TUI cleared the whole screen outside the initial frame (count $full_clear_count)."
  if grep -aqF $'\033[H\033[J' "$directory/pty-output"; then
    fail "The TUI still erases the screen before repainting each frame."
  fi
fi

directory="$(create_case production-rejected)"
if SOMEDAY_ANDROID_TRACK=production \
  run_publish "$directory" "" --yes --no-tui; then
  fail "The combined test-track command accepted a production target."
fi
[[ ! -s "$directory/calls" ]] ||
  fail "Production rejection occurred after a release phase started."
assert_contains "Google Play internal" "$directory/output"

make_dry_run_output="$FIXTURES/make-dry-run-output"
make --no-print-directory -C "$ROOT_DIR" -n publish-tracks \
  MOBILE_RELEASE_ARGS=--help > "$make_dry_run_output"
assert_contains "scripts/publish-tracks.sh --help" "$make_dry_run_output"
assert_not_contains "Prepare and publish Someday to" "$make_dry_run_output"

echo "Someday publish-tracks contract tests passed."
