#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_BUILD_FILE="$ROOT_DIR/app/android/build.gradle.kts"
IOS_PROJECT_FILE="$ROOT_DIR/iosApp/Someday.xcodeproj/project.pbxproj"
LOG_ROOT="${SOMEDAY_RELEASE_LOG_ROOT:-$ROOT_DIR/build/release/test-tracks}"
LOCK_DIR="${SOMEDAY_RELEASE_LOCK_DIR:-$ROOT_DIR/build/release/.publish-tracks.lock}"
ANDROID_TRACK="${SOMEDAY_ANDROID_TRACK:-internal}"
MAKE_COMMAND="${SOMEDAY_MAKE_COMMAND:-}"

ASSUME_YES=0
PREPARE_ONLY=0
FORCE_PLAIN=0
USE_TUI=0
TUI_ACTIVE=0
UPLOADS_STARTED=0
LOCK_HELD=0
POLL_INTERVAL="0.2"
TAIL_LINES=4

ANDROID_STATUS="Queued"
IOS_STATUS="Queued"
OVERALL_STATUS="Starting"
ANDROID_PID=""
IOS_PID=""
ANDROID_PRINTED_LINES=0
IOS_PRINTED_LINES=0
LAST_RESULT=""
LAST_STARTED_PID=""

usage() {
  cat <<'EOF'
Prepare and publish Someday to the Google Play internal track and TestFlight.

Usage:
  ./scripts/publish-tracks.sh [options]

Options:
  --yes           Confirm both uploads without prompting
  --no-tui        Use prefixed line-oriented output
  --prepare-only  Build and verify both artifacts without uploading
  --help          Show this help

The command always targets Google Play internal and TestFlight. Full logs are
kept below build/release/test-tracks by default.
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes) ASSUME_YES=1; shift ;;
    --no-tui) FORCE_PLAIN=1; shift ;;
    --prepare-only) PREPARE_ONLY=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ "$ANDROID_TRACK" == "internal" ]] ||
  die "Combined publishing only supports the Google Play internal track."

if [[ -n "$MAKE_COMMAND" ]]; then
  case "$MAKE_COMMAND" in
    /*) ;;
    *) die "SOMEDAY_MAKE_COMMAND must be an absolute executable path." ;;
  esac
  [[ -x "$MAKE_COMMAND" ]] ||
    die "SOMEDAY_MAKE_COMMAND is not executable: $MAKE_COMMAND"
else
  MAKE_COMMAND="$(command -v make || true)"
  [[ -n "$MAKE_COMMAND" ]] || die "make is required."
fi

if [[ -n "${SOMEDAY_RELEASE_LOCK_DIR:-}" ]]; then
  [[ -n "${SOMEDAY_MAKE_COMMAND:-}" ]] ||
    die "SOMEDAY_RELEASE_LOCK_DIR is only available with an injected test Make command."
  case "$LOCK_DIR" in
    /*) ;;
    *) die "SOMEDAY_RELEASE_LOCK_DIR must be an absolute path." ;;
  esac
fi

if [[ "$PREPARE_ONLY" == 0 && "$ASSUME_YES" == 0 && ! -t 0 ]]; then
  printf '%s\n' \
    "ERROR: Publishing from a non-interactive terminal requires --yes." \
    "Use --prepare-only to build and verify without uploading." >&2
  exit 2
fi

if [[ "$FORCE_PLAIN" == 0 && -t 0 && -t 1 &&
  "${TERM:-dumb}" != "dumb" && -z "${NO_COLOR:-}" ]]; then
  USE_TUI=1
fi

case "$LOG_ROOT" in
  /*) ;;
  *) LOG_ROOT="$ROOT_DIR/$LOG_ROOT" ;;
esac
SESSION_ID="$(date '+%Y%m%d-%H%M%S')-$$"
LOG_DIR="$LOG_ROOT/$SESSION_ID"
ANDROID_LOG="$LOG_DIR/android.log"
IOS_LOG="$LOG_DIR/ios.log"
COORDINATOR_LOG="$LOG_DIR/coordinator.log"
ANDROID_RESULT="$LOG_DIR/.android-result"
IOS_RESULT="$LOG_DIR/.ios-result"

mkdir -p "$LOG_DIR"
: > "$ANDROID_LOG"
: > "$IOS_LOG"
: > "$COORDINATOR_LOG"

current_android_version_code() {
  sed -n 's/.*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9_]*\).*/\1/p' "$ANDROID_BUILD_FILE" | head -1
}

current_android_version_name() {
  sed -n 's/.*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$ANDROID_BUILD_FILE" | head -1
}

current_ios_build_number() {
  sed -n 's/.*CURRENT_PROJECT_VERSION = \([^;]*\);.*/\1/p' "$IOS_PROJECT_FILE" | head -1
}

current_ios_marketing_version() {
  sed -n 's/.*MARKETING_VERSION = \([^;]*\);.*/\1/p' "$IOS_PROJECT_FILE" | head -1
}

PRODUCT_VERSION="$(current_android_version_name)" ||
  die "Could not read the Android versionName."
ANDROID_VERSION_CODE="$(current_android_version_code)" ||
  die "Could not read the Android versionCode."
IOS_VERSION="$(current_ios_marketing_version)" ||
  die "Could not read the iOS marketing version."
IOS_BUILD="$(current_ios_build_number)" ||
  die "Could not read the iOS build number."
[[ -n "$PRODUCT_VERSION" && -n "$ANDROID_VERSION_CODE" ]] ||
  die "Could not read the Android version."
[[ -n "$IOS_VERSION" && -n "$IOS_BUILD" ]] ||
  die "Could not read the iOS version."
ANDROID_VERSION="$PRODUCT_VERSION ($ANDROID_VERSION_CODE)"
IOS_VERSION_LABEL="$IOS_VERSION ($IOS_BUILD)"
SOURCE_REVISION="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || true)"
SOURCE_REVISION="${SOURCE_REVISION:-unknown}"

log_event() {
  printf '%s  %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >> "$COORDINATOR_LOG"
}

plain_status() {
  if [[ "$USE_TUI" == 0 ]]; then
    printf '[%s] %s\n' "$1" "$2"
  fi
  log_event "$1: $2"
}

terminal_width() {
  local width=""

  if command -v tput >/dev/null 2>&1; then
    width="$(tput cols 2>/dev/null || true)"
  fi
  width="${width:-${COLUMNS:-100}}"
  case "$width" in
    ''|*[!0-9]*) width=100 ;;
  esac
  if (( width < 48 )); then
    width=48
  fi
  printf '%s\n' "$width"
}

TERMINAL_WIDTH=""
invalidate_terminal_width() {
  TERMINAL_WIDTH=""
}
trap invalidate_terminal_width WINCH

update_terminal_width() {
  if [[ -z "$TERMINAL_WIDTH" ]]; then
    TERMINAL_WIDTH="$(terminal_width)"
  fi
}

TUI_FRAME=""
frame_line() {
  TUI_FRAME+="$1"$'\033[K\n'
}

append_recent_log() {
  local path="$1"
  local width="$2"
  local line
  local maximum=$((width - 6))

  if [[ ! -s "$path" ]]; then
    frame_line "    Waiting for output..."
    return
  fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//$'\r'/}"
    frame_line "    ${line:0:$maximum}"
  done < <(tail -n "$TAIL_LINES" "$path")
}

render_tui() {
  local width divider
  [[ "$USE_TUI" == 1 ]] || return 0
  update_terminal_width
  width="$TERMINAL_WIDTH"
  if [[ "$TUI_ACTIVE" == 0 ]]; then
    printf '\033[?25l\033[2J'
    TUI_ACTIVE=1
  fi
  divider="$(printf '%*s' "$width" '')"
  divider="${divider// /-}"
  TUI_FRAME=""
  frame_line "Someday test-track release  $PRODUCT_VERSION  ·  $SOURCE_REVISION"
  frame_line "$divider"
  frame_line "Android / Google Play internal  $ANDROID_VERSION  [$ANDROID_STATUS]"
  append_recent_log "$ANDROID_LOG" "$width"
  frame_line ""
  frame_line "iOS / TestFlight                $IOS_VERSION_LABEL  [$IOS_STATUS]"
  append_recent_log "$IOS_LOG" "$width"
  frame_line ""
  frame_line "Overall: $OVERALL_STATUS"
  frame_line "Logs: $LOG_DIR"
  printf '\033[?2026h\033[H%s\033[0J\033[?2026l' "$TUI_FRAME"
}

finish_tui() {
  if [[ "$TUI_ACTIVE" == 1 ]]; then
    printf '\033[?2026l\033[?25h'
    TUI_ACTIVE=0
  fi
}

acquire_release_lock() {
  local owner=""

  mkdir -p "$(dirname "$LOCK_DIR")"
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    LOCK_HELD=1
    printf '%s\n' "$$" > "$LOCK_DIR/pid"
    return
  fi

  if [[ -f "$LOCK_DIR/pid" ]]; then
    owner="$(sed -n '1p' "$LOCK_DIR/pid")"
  fi
  case "$owner" in
    ''|*[!0-9]*)
      die "Another combined release holds $LOCK_DIR. Confirm it is stale before removing it."
      ;;
    *)
      die "Another combined release is already running (PID $owner)."
      ;;
  esac
}

release_release_lock() {
  local owner=""

  [[ "$LOCK_HELD" == 1 ]] || return 0
  if [[ -f "$LOCK_DIR/pid" ]]; then
    owner="$(sed -n '1p' "$LOCK_DIR/pid")"
  fi
  if [[ "$owner" == "$$" ]]; then
    rm -f "$LOCK_DIR/pid"
    rmdir "$LOCK_DIR" 2>/dev/null || true
  fi
  LOCK_HELD=0
}

cleanup() {
  finish_tui
  release_release_lock
}

line_count() {
  local path="$1"
  local count
  count="$(wc -l < "$path")"
  count="${count//[[:space:]]/}"
  printf '%s\n' "${count:-0}"
}

emit_new_lines() {
  local platform="$1"
  local path="$2"
  local printed="$3"
  local total
  local start
  local line

  [[ "$USE_TUI" == 0 ]] || return 0
  total="$(line_count "$path")"
  if (( total <= printed )); then
    return 0
  fi
  start=$((printed + 1))
  sed -n "${start},${total}p" "$path" |
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line//$'\r'/}"
      printf '[%s] %s\n' "$platform" "$line"
    done
  if [[ "$platform" == "Android" ]]; then
    ANDROID_PRINTED_LINES="$total"
  else
    IOS_PRINTED_LINES="$total"
  fi
}

flush_output() {
  emit_new_lines Android "$ANDROID_LOG" "$ANDROID_PRINTED_LINES"
  emit_new_lines iOS "$IOS_LOG" "$IOS_PRINTED_LINES"
  render_tui
}

run_make_to_log() {
  local result_path="$1"
  local log_path="$2"
  local target="$3"
  local result_temporary="$result_path.tmp"
  shift 3

  rm -f "$result_path" "$result_temporary"
  (
    child_pid=""
    write_result() {
      printf '%s\n' "$1" > "$result_temporary" &&
        mv "$result_temporary" "$result_path"
    }
    forward_signal() {
      local exit_code="$1"
      trap - INT TERM HUP
      [[ -z "$child_pid" ]] || kill "$child_pid" >/dev/null 2>&1 || true
      [[ -z "$child_pid" ]] || wait "$child_pid" >/dev/null 2>&1 || true
      write_result "$exit_code" || true
      exit "$exit_code"
    }
    trap 'forward_signal 130' INT
    trap 'forward_signal 143' TERM
    trap 'forward_signal 129' HUP
    set +e
    TERM=dumb MAKEFLAGS= MFLAGS= MAKEOVERRIDES= GNUMAKEFLAGS= \
      "$MAKE_COMMAND" --no-print-directory -C "$ROOT_DIR" \
      "$@" "$target" >> "$log_path" 2>&1 &
    child_pid=$!
    wait "$child_pid"
    result=$?
    child_pid=""
    write_result "$result"
    exit "$result"
  ) &
  LAST_STARTED_PID=$!
}

wait_for_result() {
  local platform="$1"
  local pid="$2"
  local result_path="$3"

  while ! collect_result_if_ready "$platform" "$pid" "$result_path"; do
    flush_output
    sleep "$POLL_INTERVAL"
  done
  flush_output
}

collect_result_if_ready() {
  local platform="$1"
  local pid="$2"
  local result_path="$3"
  local result
  local wait_status

  # A leftover result file from an earlier step is not this worker's outcome.
  # Keep polling while the current pid is alive so a stale 0 cannot look ready.
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    return 1
  fi

  if [[ -n "$pid" ]]; then
    if wait "$pid" >/dev/null 2>&1; then
      result=0
    else
      wait_status=$?
      if [[ "$wait_status" == 127 && -f "$result_path" ]]; then
        result="$(sed -n '1p' "$result_path")"
      else
        result="$wait_status"
      fi
    fi
  elif [[ -f "$result_path" ]]; then
    result="$(sed -n '1p' "$result_path")"
  else
    return 1
  fi

  if [[ "$platform" == "Android" ]]; then
    ANDROID_PID=""
  else
    IOS_PID=""
  fi
  LAST_RESULT="$result"
}

record_upload_result() {
  local platform="$1"
  local result="$2"

  if [[ "$platform" == "Android" ]]; then
    if [[ "$result" == "0" ]]; then
      ANDROID_STATUS="Published"
      plain_status Android "Published to Play internal"
    else
      ANDROID_STATUS="Failed"
      plain_status Android "Upload failed or has unknown remote state"
    fi
  else
    if [[ "$result" == "0" ]]; then
      IOS_STATUS="Published"
      plain_status iOS "Uploaded to TestFlight processing"
    else
      IOS_STATUS="Failed"
      plain_status iOS "Upload failed or has unknown remote state"
    fi
  fi
}

run_android_step() {
  local status="$1"
  local success="$2"
  local target="$3"
  shift 3
  local result

  printf '\n== %s ==\n' "$status" >> "$ANDROID_LOG"
  ANDROID_STATUS="$status"
  plain_status Android "$status"
  run_make_to_log "$ANDROID_RESULT" "$ANDROID_LOG" "$target" "$@"
  ANDROID_PID="$LAST_STARTED_PID"
  wait_for_result Android "$ANDROID_PID" "$ANDROID_RESULT"
  result="$LAST_RESULT"
  if [[ "$result" == "0" ]]; then
    ANDROID_STATUS="$success"
    plain_status Android "$success"
    render_tui
    return 0
  fi
  ANDROID_STATUS="Failed"
  plain_status Android "Failed during $status"
  render_tui
  return 1
}

run_ios_step() {
  local status="$1"
  local success="$2"
  local target="$3"
  shift 3
  local result

  printf '\n== %s ==\n' "$status" >> "$IOS_LOG"
  IOS_STATUS="$status"
  plain_status iOS "$status"
  run_make_to_log "$IOS_RESULT" "$IOS_LOG" "$target" "$@"
  IOS_PID="$LAST_STARTED_PID"
  wait_for_result iOS "$IOS_PID" "$IOS_RESULT"
  result="$LAST_RESULT"
  if [[ "$result" == "0" ]]; then
    IOS_STATUS="$success"
    plain_status iOS "$success"
    render_tui
    return 0
  fi
  IOS_STATUS="Failed"
  plain_status iOS "Failed during $status"
  render_tui
  return 1
}

print_recovery() {
  printf '\nFull logs: %s\n' "$LOG_DIR"
  if [[ "$ANDROID_STATUS" != "Published" ]]; then
    printf 'Retry Android after checking Play Console:\n'
    printf '  make android-upload-play ANDROID_PLAY_TRACK=internal'
    printf ' ANDROID_PLAY_RELEASE_STATUS=completed\n'
  fi
  if [[ "$IOS_STATUS" != "Published" ]]; then
    printf 'Retry iOS after checking App Store Connect:\n'
    printf '  make ios-upload-archive\n'
  fi
}

handle_signal() {
  local signal_name="$1"
  local exit_code="$2"
  trap - INT TERM HUP

  if [[ "$UPLOADS_STARTED" == 1 ]]; then
    if [[ -n "$ANDROID_PID" ]] &&
      collect_result_if_ready Android "$ANDROID_PID" "$ANDROID_RESULT"; then
      record_upload_result Android "$LAST_RESULT"
    fi
    if [[ -n "$IOS_PID" ]] &&
      collect_result_if_ready iOS "$IOS_PID" "$IOS_RESULT"; then
      record_upload_result iOS "$LAST_RESULT"
    fi
  fi

  [[ -z "$ANDROID_PID" ]] || kill "$ANDROID_PID" >/dev/null 2>&1 || true
  [[ -z "$IOS_PID" ]] || kill "$IOS_PID" >/dev/null 2>&1 || true
  [[ -z "$ANDROID_PID" ]] || wait "$ANDROID_PID" >/dev/null 2>&1 || true
  [[ -z "$IOS_PID" ]] || wait "$IOS_PID" >/dev/null 2>&1 || true
  ANDROID_PID=""
  IOS_PID=""
  if [[ "$UPLOADS_STARTED" == 1 ]]; then
    case "$ANDROID_STATUS" in
      Published|Failed) ;;
      *) ANDROID_STATUS="Unknown" ;;
    esac
    case "$IOS_STATUS" in
      Published|Failed) ;;
      *) IOS_STATUS="Unknown" ;;
    esac
  else
    ANDROID_STATUS="Interrupted"
    IOS_STATUS="Interrupted"
  fi
  OVERALL_STATUS="Interrupted by $signal_name"
  log_event "Interrupted by $signal_name"
  flush_output
  finish_tui
  if [[ "$UPLOADS_STARTED" == 1 ]]; then
    printf '\nUploads were interrupted; remote state may be unknown.\n'
    printf 'Check both store consoles before retrying an upload.\n'
    print_recovery
  else
    printf '\nRelease preparation was interrupted. No upload was started.\n'
    printf 'Full logs: %s\n' "$LOG_DIR"
  fi
  exit "$exit_code"
}

trap 'handle_signal INT 130' INT
trap 'handle_signal TERM 143' TERM
trap 'handle_signal HUP 129' HUP
trap 'cleanup' EXIT

acquire_release_lock

log_event "Release session started for version $PRODUCT_VERSION"
log_event "Android=$ANDROID_VERSION iOS=$IOS_VERSION_LABEL source=$SOURCE_REVISION"
log_event "Log directory: $LOG_DIR"
plain_status Release "Version $PRODUCT_VERSION"
plain_status Release "Android $ANDROID_VERSION · iOS $IOS_VERSION_LABEL · source $SOURCE_REVISION"
plain_status Release "Logs: $LOG_DIR"
render_tui

OVERALL_STATUS="Checking upload prerequisites"
ANDROID_STATUS="Checking"
IOS_STATUS="Waiting"
android_preflight=0
ios_preflight=0
run_android_step \
  "Checking upload prerequisites" "Ready" android-upload-check \
  ANDROID_PLAY_TRACK=internal \
  ANDROID_PLAY_RELEASE_STATUS=completed \
  ANDROID_PLAY_CONFIRM_PRODUCTION= \
  ANDROID_PLAY_DRY_RUN= || android_preflight=$?

IOS_STATUS="Checking"
run_ios_step \
  "Checking upload prerequisites" "Ready" ios-upload-check || ios_preflight=$?

if [[ "$android_preflight" != 0 || "$ios_preflight" != 0 ]]; then
  OVERALL_STATUS="Preflight failed; no artifacts were built"
  render_tui
  finish_tui
  printf '\nRelease preflight failed. No upload was started.\n'
  printf 'Full logs: %s\n' "$LOG_DIR"
  exit 1
fi

OVERALL_STATUS="Preparing verified artifacts"
IOS_STATUS="Waiting"
if ! run_android_step \
  "Building and verifying AAB" "Prepared" android-release-play \
  ANDROID_PLAY_TRACK=internal \
  ANDROID_PLAY_RELEASE_STATUS=completed \
  ANDROID_PLAY_CONFIRM_PRODUCTION= \
  ANDROID_PLAY_DRY_RUN=; then
  OVERALL_STATUS="Android preparation failed; no upload was started"
  IOS_STATUS="Not started"
  render_tui
  finish_tui
  printf '\nAndroid preparation failed. No upload was started.\n'
  printf 'Full logs: %s\n' "$LOG_DIR"
  exit 1
fi

if ! run_ios_step \
  "Archiving and verifying app" "Prepared" ios-archive; then
  OVERALL_STATUS="iOS preparation failed; no upload was started"
  render_tui
  finish_tui
  printf '\niOS preparation failed. No upload was started.\n'
  printf 'The verified Android AAB remains available for a later release.\n'
  printf 'Full logs: %s\n' "$LOG_DIR"
  exit 1
fi

OVERALL_STATUS="Both artifacts are prepared and verified"
render_tui

if [[ "$PREPARE_ONLY" == 1 ]]; then
  finish_tui
  log_event "Preparation completed; upload skipped by --prepare-only"
  printf '\nBoth artifacts are prepared and verified. No upload was started.\n'
  printf 'Full logs: %s\n' "$LOG_DIR"
  exit 0
fi

if [[ "$ASSUME_YES" == 0 ]]; then
  finish_tui
  printf '\nPublish version %s to Play internal and TestFlight? [y/N] ' \
    "$PRODUCT_VERSION"
  if ! IFS= read -r confirmation; then
    confirmation=""
  fi
  case "$confirmation" in
    y|Y|yes|YES) ;;
    *)
      OVERALL_STATUS="Cancelled before upload"
      log_event "Cancelled before upload"
      printf 'No upload was started. Prepared artifacts were kept.\n'
      printf 'Full logs: %s\n' "$LOG_DIR"
      exit 0
      ;;
  esac
fi

UPLOADS_STARTED=1
OVERALL_STATUS="Uploading both prepared artifacts"
ANDROID_STATUS="Uploading"
IOS_STATUS="Uploading"
plain_status Android "Uploading prepared AAB"
plain_status iOS "Uploading prepared archive"
printf '\n== Uploading prepared AAB ==\n' >> "$ANDROID_LOG"
printf '\n== Uploading prepared archive ==\n' >> "$IOS_LOG"

run_make_to_log \
  "$ANDROID_RESULT" "$ANDROID_LOG" android-upload-play \
  ANDROID_PLAY_TRACK=internal \
  ANDROID_PLAY_RELEASE_STATUS=completed \
  ANDROID_PLAY_CONFIRM_PRODUCTION= \
  ANDROID_PLAY_DRY_RUN=
ANDROID_PID="$LAST_STARTED_PID"
run_make_to_log \
  "$IOS_RESULT" "$IOS_LOG" ios-upload-archive
IOS_PID="$LAST_STARTED_PID"

android_result=""
ios_result=""
while [[ -z "$android_result" || -z "$ios_result" ]]; do
  if [[ -z "$android_result" ]] &&
    collect_result_if_ready Android "$ANDROID_PID" "$ANDROID_RESULT"; then
    android_result="$LAST_RESULT"
    record_upload_result Android "$android_result"
  fi
  if [[ -z "$ios_result" ]] &&
    collect_result_if_ready iOS "$IOS_PID" "$IOS_RESULT"; then
    ios_result="$LAST_RESULT"
    record_upload_result iOS "$ios_result"
  fi
  flush_output
  if [[ -z "$android_result" || -z "$ios_result" ]]; then
    sleep "$POLL_INTERVAL"
  fi
done

if [[ "$ANDROID_STATUS" == "Published" && "$IOS_STATUS" == "Published" ]]; then
  OVERALL_STATUS="Both test-track uploads completed"
  log_event "Release completed successfully"
  render_tui
  finish_tui
  printf '\nVersion %s was submitted to Play internal and TestFlight.\n' \
    "$PRODUCT_VERSION"
  printf 'Full logs: %s\n' "$LOG_DIR"
  exit 0
fi

if [[ "$ANDROID_STATUS" == "Published" || "$IOS_STATUS" == "Published" ]]; then
  OVERALL_STATUS="Release partially completed"
else
  OVERALL_STATUS="Both uploads failed or have unknown remote state"
fi
log_event "Release finished with Android=$ANDROID_STATUS iOS=$IOS_STATUS"
render_tui
finish_tui
printf '\nThe combined release did not complete on both platforms.\n'
printf 'Android: %s\n' "$ANDROID_STATUS"
printf 'iOS: %s\n' "$IOS_STATUS"
printf 'A failed upload may still have reached its store; verify remote state first.\n'
print_recovery
exit 1
