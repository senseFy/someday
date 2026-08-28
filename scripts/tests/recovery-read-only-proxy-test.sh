#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
BACKEND_PID=""
PROXY_PID=""
BACKEND_DIR=""

free_port() {
    python3 - <<'PY'
import socket

value = socket.socket()
value.bind(("127.0.0.1", 0))
print(value.getsockname()[1])
value.close()
PY
}

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    for pid in "$PROXY_PID" "$BACKEND_PID"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
            kill "$pid" >/dev/null 2>&1 || true
            wait "$pid" >/dev/null 2>&1 || true
        fi
    done
    if [[ -n "$BACKEND_DIR" ]]; then
        [[ ! -f "$BACKEND_DIR/health" ]] || unlink "$BACKEND_DIR/health"
        [[ ! -f "$BACKEND_DIR/rejections.tsv" ]] || unlink "$BACKEND_DIR/rejections.tsv"
        rmdir "$BACKEND_DIR" >/dev/null 2>&1 || true
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

BACKEND_PORT="$(free_port)"
PROXY_PORT="$(free_port)"
BACKEND_DIR="$(mktemp -d)"
printf 'healthy\n' >"$BACKEND_DIR/health"
python3 -m http.server "$BACKEND_PORT" \
    --bind 127.0.0.1 \
    --directory "$BACKEND_DIR" \
    >/dev/null 2>&1 &
BACKEND_PID=$!
python3 -B "$ROOT_DIR/scripts/fixtures/recovery-read-only-proxy.py" \
    --listen-port "$PROXY_PORT" \
    --backend-port "$BACKEND_PORT" \
    --audit-file "$BACKEND_DIR/rejections.tsv" \
    >/dev/null 2>&1 &
PROXY_PID=$!

proxy_ready=false
for _ in $(seq 1 50); do
    if curl --fail --silent "http://127.0.0.1:$PROXY_PORT/health" >/dev/null 2>&1; then
        proxy_ready=true
        break
    fi
    sleep 0.1
done
[[ "$proxy_ready" == true ]]
[[ "$(curl --fail --silent "http://127.0.0.1:$PROXY_PORT/health")" == "healthy" ]]

workspace="workspace-00000000000000000000000000000000"
base="http://127.0.0.1:$PROXY_PORT/sync/v3/workspaces/$workspace/entities"
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --request POST "$base/pull")" == "501" ]]
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --request POST "$base/push")" == "503" ]]
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --request PUT "http://127.0.0.1:$PROXY_PORT/sync/v3/workspaces/$workspace/media/$(printf '0%.0s' {1..64})")" == "503" ]]
awk -F '\t' '$1 == "POST" && $2 ~ /\/entities\/push$/ { found = 1 } END { exit !found }' \
    "$BACKEND_DIR/rejections.tsv"
awk -F '\t' '$1 == "PUT" && $2 ~ /\/media\/[^\/]+$/ { found = 1 } END { exit !found }' \
    "$BACKEND_DIR/rejections.tsv"
