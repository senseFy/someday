#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/someday-server-release-interface.XXXXXX")"
FIXTURE_ROOT="$TEST_ROOT/project"
CALL_LOG="$TEST_ROOT/controller.log"
MARKER="$TEST_ROOT/injected"
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() {
    printf 'server release interface test error: %s\n' "$*" >&2
    exit 1
}

expect_failure() {
    local label="$1"
    shift
    if "$@" >"$TEST_ROOT/$label.out" 2>&1; then
        fail "$label unexpectedly succeeded"
    fi
}

mkdir -p "$FIXTURE_ROOT/scripts" "$FIXTURE_ROOT/docs"
cp "$ROOT_DIR/Makefile" "$FIXTURE_ROOT/Makefile"
cp "$ROOT_DIR/scripts/server-release-tui" "$FIXTURE_ROOT/scripts/server-release-tui"
printf '# Server release\n' >"$FIXTURE_ROOT/docs/server-release.md"

cat >"$FIXTURE_ROOT/scripts/server-release" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

: "${FAKE_RELEASE_CALL_LOG:?}"
printf 'argc=%d' "$#" >>"$FAKE_RELEASE_CALL_LOG"
argument_index=0
for argument in "$@"; do
    argument_index=$((argument_index + 1))
    printf ' arg%d=%q' "$argument_index" "$argument" >>"$FAKE_RELEASE_CALL_LOG"
done
printf '\n' >>"$FAKE_RELEASE_CALL_LOG"

[[ "$#" -eq 2 ]] || exit 90
case "$1" in
    plan)
        printf 'PLAN %s\n' "$2"
        ;;
    status)
        if [[ "${FAKE_CONTROLLER_STATUS:-0}" -eq 0 ]]; then
            printf 'READY TO TAG\n'
        else
            printf 'NOT READY\n'
        fi
        ;;
    rehearse)
        printf 'REHEARSAL %s\n' "$2"
        ;;
    *)
        printf 'unexpected controller action: %s\n' "$1" >&2
        exit 91
        ;;
esac
exit "${FAKE_CONTROLLER_STATUS:-0}"
SH
chmod 0755 "$FIXTURE_ROOT/scripts/server-release" \
    "$FIXTURE_ROOT/scripts/server-release-tui"
: >"$CALL_LOG"

run_make() {
    (
        cd "$FIXTURE_ROOT"
        FAKE_RELEASE_CALL_LOG="$CALL_LOG" make --no-print-directory -s "$@"
    )
}

run_pty() {
    local input="$1"
    local output="$2"
    local version="$3"
    local controller_status="$4"
    shift 4

    PTY_INPUT="$input" \
        PTY_OUTPUT="$output" \
        PTY_VERSION="$version" \
        PTY_CONTROLLER_STATUS="$controller_status" \
        FAKE_RELEASE_CALL_LOG="$CALL_LOG" \
        python3 -B - "$@" <<'PY'
import os
import pathlib
import pty
import select
import subprocess
import sys
import time

command = sys.argv[1:]
environment = os.environ.copy()
environment["TERM"] = "dumb"
environment["NO_COLOR"] = "1"
environment["FAKE_CONTROLLER_STATUS"] = os.environ["PTY_CONTROLLER_STATUS"]
version = os.environ["PTY_VERSION"]
if version == "<unset>":
    environment.pop("SERVER_RELEASE_VERSION", None)
else:
    environment["SERVER_RELEASE_VERSION"] = version

master, slave = pty.openpty()
process = subprocess.Popen(
    command,
    stdin=slave,
    stdout=slave,
    stderr=slave,
    env=environment,
    close_fds=True,
)
os.close(slave)
os.write(master, os.environ["PTY_INPUT"].encode("utf-8"))

chunks = []
deadline = time.monotonic() + 10
while True:
    ready, _, _ = select.select([master], [], [], 0.1)
    if ready:
        try:
            chunk = os.read(master, 65536)
        except OSError:
            chunk = b""
        if chunk:
            chunks.append(chunk)
    if process.poll() is not None:
        while True:
            ready, _, _ = select.select([master], [], [], 0)
            if not ready:
                break
            try:
                chunk = os.read(master, 65536)
            except OSError:
                break
            if not chunk:
                break
            chunks.append(chunk)
        break
    if time.monotonic() >= deadline:
        process.kill()
        process.wait()
        raise SystemExit("interactive release assistant timed out")

os.close(master)
pathlib.Path(os.environ["PTY_OUTPUT"]).write_bytes(b"".join(chunks))
raise SystemExit(process.returncode)
PY
}

VERSION=1.2.3
TUI="$FIXTURE_ROOT/scripts/server-release-tui"

"$TUI" --help >"$TEST_ROOT/tui-help.out"
grep -Fq 'cannot create or push tags' "$TEST_ROOT/tui-help.out" ||
    fail 'TUI help omits its remote-write boundary'

make --no-print-directory -s -C "$FIXTURE_ROOT" help >"$TEST_ROOT/make-help.out"
for target in server-release server-release-plan server-release-status server-release-rehearse; do
    grep -Eq "^[[:space:]]+$target[[:space:]]" "$TEST_ROOT/make-help.out" ||
        fail "make help omits $target"
done

: >"$CALL_LOG"
expect_failure missing-version run_make server-release-plan
grep -Fq 'SERVER_RELEASE_VERSION=X.Y.Z is required' "$TEST_ROOT/missing-version.out" ||
    fail 'missing Make version has no useful error'
[[ ! -s "$CALL_LOG" ]] || fail 'missing Make version reached the controller'

: >"$CALL_LOG"
SERVER_RELEASE_VERSION="$VERSION" run_make server-release-plan \
    >"$TEST_ROOT/make-plan.out"
grep -Fqx 'argc=2 arg1=plan arg2=1.2.3' "$CALL_LOG" ||
    fail 'Make plan did not preserve the controller argv'

: >"$CALL_LOG"
SERVER_RELEASE_VERSION="$VERSION" run_make server-release-rehearse \
    >"$TEST_ROOT/make-rehearse.out"
grep -Fqx 'argc=2 arg1=rehearse arg2=1.2.3' "$CALL_LOG" ||
    fail 'Make rehearsal did not preserve the controller argv'

: >"$CALL_LOG"
FAKE_CONTROLLER_STATUS=7 SERVER_RELEASE_VERSION="$VERSION" \
    expect_failure make-status-failure run_make server-release-status
grep -Fqx 'argc=2 arg1=status arg2=1.2.3' "$CALL_LOG" ||
    fail 'Make status did not preserve the controller argv'

: >"$CALL_LOG"
attack='1.2.3`touch '"$MARKER"'`'
SERVER_RELEASE_VERSION="$attack" run_make server-release-plan \
    >"$TEST_ROOT/make-attack.out"
[[ ! -e "$MARKER" ]] || fail 'Make evaluated the version as shell source'
grep -Fq 'argc=2 arg1=plan ' "$CALL_LOG" ||
    fail 'Make did not pass the hostile version as one argument'

: >"$CALL_LOG"
make_function_payload='$(shell touch '"$MARKER"')'
run_make server-release-plan "SERVER_RELEASE_VERSION=$make_function_payload" \
    >"$TEST_ROOT/make-function-attack.out"
[[ ! -e "$MARKER" ]] || fail 'Make expanded a function in the version value'
grep -Fq 'argc=2 arg1=plan ' "$CALL_LOG" ||
    fail 'Make did not preserve the raw command-line version value'

: >"$CALL_LOG"
expect_failure non-tty env SERVER_RELEASE_VERSION="$VERSION" \
    FAKE_RELEASE_CALL_LOG="$CALL_LOG" "$TUI"
grep -Fq 'an interactive terminal is required' "$TEST_ROOT/non-tty.out" ||
    fail 'non-TTY invocation has no useful error'
[[ ! -s "$CALL_LOG" ]] || fail 'non-TTY invocation reached the controller'

: >"$CALL_LOG"
run_pty $'\n\n5\n' "$TEST_ROOT/tui-status.out" "$VERSION" 1 \
    make --no-print-directory -s -C "$FIXTURE_ROOT" server-release
grep -Fqx 'argc=2 arg1=status arg2=1.2.3' "$CALL_LOG" ||
    fail 'default TUI choice did not run status exactly once'
grep -Fq 'NOT READY' "$TEST_ROOT/tui-status.out" ||
    fail 'TUI hid the controller status output'
grep -Fq 'Command exited with status 1' "$TEST_ROOT/tui-status.out" ||
    fail 'TUI hid the controller failure status'

: >"$CALL_LOG"
run_pty $'3\n\n5\n' "$TEST_ROOT/tui-cancel.out" "$VERSION" 0 "$TUI"
[[ ! -s "$CALL_LOG" ]] || fail 'empty confirmation started the rehearsal'
grep -Fq 'Rehearsal cancelled.' "$TEST_ROOT/tui-cancel.out" ||
    fail 'TUI did not report rehearsal cancellation'

: >"$CALL_LOG"
run_pty $'3\nREHEARSE 1.2.3\n\n5\n' "$TEST_ROOT/tui-rehearse.out" \
    "$VERSION" 0 "$TUI"
grep -Fqx 'argc=2 arg1=rehearse arg2=1.2.3' "$CALL_LOG" ||
    fail 'confirmed TUI rehearsal did not call the controller exactly once'

: >"$CALL_LOG"
run_pty $'1.2.3\n2\n\n5\n' "$TEST_ROOT/tui-plan.out" '<unset>' 0 "$TUI"
grep -Fqx 'argc=2 arg1=plan arg2=1.2.3' "$CALL_LOG" ||
    fail 'TUI version prompt or plan delegation is incorrect'
grep -Fq 'PLAN 1.2.3' "$TEST_ROOT/tui-plan.out" ||
    fail 'TUI hid the controller plan output'

if grep -Eq 'arg1=(tag|push|publish)|git|gh|docker' "$CALL_LOG"; then
    fail 'the interface invoked an operation outside the controller contract'
fi

expect_failure invalid-version env SERVER_RELEASE_VERSION=01.2.3 \
    FAKE_RELEASE_CALL_LOG="$CALL_LOG" "$TUI"
expect_failure unknown-option "$TUI" --publish "$VERSION"

printf 'server-release-interface=passed\n'
