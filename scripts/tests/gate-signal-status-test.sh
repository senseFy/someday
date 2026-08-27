#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
scripts=(
    "$ROOT_DIR/scripts/server-container-smoke"
    "$ROOT_DIR/scripts/server-recovery-gate"
    "$ROOT_DIR/scripts/server-release-compose-smoke"
    "$ROOT_DIR/scripts/sync-v3-reliability-gate"
    "$ROOT_DIR/scripts/lib/managed-storage-common.sh"
    "$ROOT_DIR/scripts/tests/recovery-read-only-proxy-test.sh"
)

for script in "${scripts[@]}"; do
    rg -q '^[[:space:]]*trap cleanup EXIT$' "$script"
    rg -q "^[[:space:]]*trap 'exit 130' INT$" "$script"
    rg -q "^[[:space:]]*trap 'exit 143' TERM$" "$script"
    if rg -Fq 'trap cleanup EXIT INT TERM' "$script"; then
        printf 'signal can be masked as success: %s\n' "$script" >&2
        exit 1
    fi
done

set +e
bash -c '
cleanup() {
    status=$?
    trap - EXIT INT TERM
    exit "$status"
}
trap cleanup EXIT
trap "exit 130" INT
trap "exit 143" TERM
kill -TERM $$
'
status=$?
set -e
[[ "$status" -eq 143 ]]
printf 'gate-signal-status=passed\n'
