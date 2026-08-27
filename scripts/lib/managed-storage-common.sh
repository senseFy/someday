#!/usr/bin/env bash
# Shared lifecycle and verification helpers for the two named managed-storage gates.

GRADLEW="${GRADLEW:-$ROOT_DIR/gradlew}"
GATE_PROPERTY="-Psomeday.systemV3ReliabilityGate=true"
POSTGRES_IMAGE="postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
MINIO_MC_IMAGE="minio/mc@sha256:aead63c77f9db9107f1696fb08ecb0faeda23729cde94b0f663edf4fe09728e3"

RUN_DIR=""
FINAL_DIR=""
RESULT_FILE=""
SOURCE_MEDIA_DIR=""
RESTORED_MEDIA_DIR=""
HANDSHAKE_DIR=""
SERVER_PID=""
TEST_PID=""
PROXY_PID=""
POSTGRES_NAME=""
SERVER_LOG=""
TEST_LOG=""
JWT_SECRET=""
MANAGED_GATE_PGPASSFILE=""

initialize_managed_gate() {
    [[ -z "$RUN_DIR" ]] || die "managed gate is already initialized"
    mkdir -p "$ROOT_DIR/build"
    RUN_DIR="$(mktemp -d "$ROOT_DIR/build/managed-storage-$PROFILE.XXXXXX")"
    FINAL_DIR="$ROOT_DIR/build/managed-storage-profile-gate/$PROFILE"
    RESULT_FILE="$FINAL_DIR/result.json"
    SOURCE_MEDIA_DIR="$RUN_DIR/source-media"
    RESTORED_MEDIA_DIR="$RUN_DIR/restored-media"
    HANDSHAKE_DIR="$RUN_DIR/handshake"
    SERVER_LOG="$RUN_DIR/source-server.log"
    TEST_LOG="$RUN_DIR/recovery-journey.log"
    mkdir -p "$FINAL_DIR" "$SOURCE_MEDIA_DIR" "$RESTORED_MEDIA_DIR" "$HANDSHAKE_DIR"
    rm -f "$RESULT_FILE"
    trap cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
}

die() {
    printf 'managed %s gate error: %s\n' "$PROFILE" "$*" >&2
    exit 1
}

info() {
    printf '[managed %s gate] %s\n' "$PROFILE" "$*" >&2
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

require_env() {
    local name="$1"
    [[ -n "${!name:-}" ]] || die "$name is required for the live gate"
}

require_java_21() {
    local java_bin feature_version
    if [[ -n "${JAVA_HOME:-}" ]]; then
        java_bin="$JAVA_HOME/bin/java"
    else
        java_bin="$(command -v java)"
    fi
    feature_version="$(
        "$java_bin" -XshowSettings:properties -version 2>&1 |
            sed -n 's/^[[:space:]]*java\.specification\.version = \([0-9][0-9]*\).*$/\1/p' |
            head -1
    )"
    [[ "$feature_version" =~ ^[0-9]+$ ]] || die "could not determine the Java feature version"
    ((feature_version >= 21)) || die "JDK 21 or newer is required"
}

require_wrangler_version() {
    local version
    version="$(wrangler --version 2>/dev/null | sed -n 's/.*\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' | head -1)"
    [[ "$version" == "4.78.0" ]] ||
        die "Wrangler 4.78.0 is required for the inspected R2 policy output (found: ${version:-unknown})"
}

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    [[ -z "$MANAGED_GATE_PGPASSFILE" ]] || rm -f "$MANAGED_GATE_PGPASSFILE"
    for pid in "$TEST_PID" "$PROXY_PID" "$SERVER_PID"; do
        if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
            kill "$pid" >/dev/null 2>&1 || true
            wait "$pid" >/dev/null 2>&1 || true
        fi
    done
    if [[ -n "$POSTGRES_NAME" ]]; then
        docker rm -f "$POSTGRES_NAME" >/dev/null 2>&1 || true
    fi
    [[ ! -f "$TEST_LOG" ]] || cp "$TEST_LOG" "$FINAL_DIR/recovery-journey.log"
    [[ ! -f "$SERVER_LOG" ]] || tail -n 300 "$SERVER_LOG" >"$FINAL_DIR/server-tail.log" || true
    exit "$status"
}

stop_server() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" >/dev/null 2>&1; then
        kill "$SERVER_PID" >/dev/null 2>&1 || true
        wait "$SERVER_PID" >/dev/null 2>&1 || true
    fi
    SERVER_PID=""
}

free_port() {
    python3 - <<'PY'
import socket

value = socket.socket()
value.bind(("127.0.0.1", 0))
print(value.getsockname()[1])
value.close()
PY
}

wait_for() {
    local label="$1"
    shift
    local attempt
    for attempt in $(seq 1 60); do
        if "$@" >/dev/null 2>&1; then
            return 0
        fi
        if [[ -n "$SERVER_PID" ]] && ! kill -0 "$SERVER_PID" >/dev/null 2>&1; then
            tail -n 120 "$SERVER_LOG" >&2 || true
            die "$label exited before becoming healthy"
        fi
        sleep 1
    done
    die "$label did not become healthy within 60 seconds"
}

write_success_result() {
    local profile="$1"
    local source_resource="$2"
    local restore_resource="$3"
    local commit
    commit="$(git rev-parse HEAD)"
    if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
        rm -f "$RESULT_FILE"
        info "live checks passed on a dirty tree; no release result.json was written"
        return 0
    fi
    python3 - "$RESULT_FILE" "$profile" "$commit" "$source_resource" "$restore_resource" <<'PY'
import datetime
import json
import os
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
payload = {
    "profile": sys.argv[2],
    "commit": sys.argv[3],
    "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "sourceResource": sys.argv[4],
    "restoreResource": sys.argv[5],
    "treeState": "clean",
    "result": "passed",
    "releaseEligible": True,
}
temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
os.replace(temporary, path)
PY
}

start_managed_server() {
    local database_url="$1"
    local database_user="$2"
    local database_password="$3"
    local database_tls_mode="$4"
    local port="$5"
    local registration_enabled="$6"
    local log_file="$7"
    shift 7
    SERVER_LOG="$log_file"
    : >"$SERVER_LOG"
    env \
        SOMEDAY_DEPLOYMENT_MODE=production \
        SOMEDAY_HOST=127.0.0.1 \
        SOMEDAY_PORT="$port" \
        SOMEDAY_PUBLIC_BASE_URL=https://someday-managed-gate.invalid \
        SOMEDAY_DB_URL="$database_url" \
        SOMEDAY_DB_USER="$database_user" \
        SOMEDAY_DB_PASSWORD="$database_password" \
        SOMEDAY_DB_TLS_MODE="$database_tls_mode" \
        SOMEDAY_JWT_SECRET="$JWT_SECRET" \
        SOMEDAY_REGISTRATION_ENABLED="$registration_enabled" \
        "$@" \
        "$ROOT_DIR/server/build/install/server/bin/server" >"$SERVER_LOG" 2>&1 &
    SERVER_PID=$!
    wait_for "managed server" curl -fsS "http://127.0.0.1:$port/health"
}

start_recovery_journey() {
    local endpoint="$1"
    SOMEDAY_E2E_ENDPOINT="$endpoint" \
    SOMEDAY_RECOVERY_GATE_DIR="$HANDSHAKE_DIR" \
    SOMEDAY_RECOVERY_GATE_TIMEOUT_SECONDS=600 \
        "$GRADLEW" \
            :integration-tests:serverRecoveryTest \
            "$GATE_PROPERTY" \
            --dependency-verification=strict \
            --stacktrace >"$TEST_LOG" 2>&1 &
    TEST_PID=$!
    local attempt
    for attempt in $(seq 1 1200); do
        [[ ! -f "$HANDSHAKE_DIR/ready" ]] || return 0
        if ! kill -0 "$TEST_PID" >/dev/null 2>&1; then
            tail -n 160 "$TEST_LOG" >&2 || true
            die "recovery journey exited before its paired client became ready"
        fi
        sleep 0.1
    done
    die "paired recovery client did not become ready"
}

finish_recovery_journey() {
    local public_port="$1"
    local backend_port="$2"
    local endpoint="http://127.0.0.1:$public_port"
    local audit_file="$RUN_DIR/recovery-write-rejections.tsv"
    python3 -B "$ROOT_DIR/scripts/fixtures/recovery-read-only-proxy.py" \
        --listen-port "$public_port" \
        --backend-port "$backend_port" \
        --audit-file "$audit_file" \
        >"$RUN_DIR/recovery-read-only-proxy.log" 2>&1 &
    PROXY_PID=$!
    wait_for "managed recovery read-only proxy" curl -fsS "$endpoint/health"
    : >"$HANDSHAKE_DIR/continue"
    if ! wait "$TEST_PID"; then
        TEST_PID=""
        tail -n 200 "$TEST_LOG" >&2 || true
        die "paired-client recovery journey failed"
    fi
    TEST_PID=""
    awk -F '\t' '$1 == "POST" && $2 ~ /\/entities\/push$/ { found = 1 } END { exit !found }' \
        "$audit_file" || die "paired client did not reach the read-only proxy with an entity push"
    awk -F '\t' '$1 == "PUT" && $2 ~ /\/media\/[^\/]+$/ { found = 1 } END { exit !found }' \
        "$audit_file" || die "paired client did not reach the read-only proxy with a media upload"
    cp "$audit_file" "$FINAL_DIR/recovery-write-rejections.tsv"
}

psql_manifest() {
    local connection="$1"
    local output="$2"
    psql -X "$connection" \
        --tuples-only \
        --no-align \
        --field-separator $'\t' \
        --set ON_ERROR_STOP=1 \
        --command "
            SELECT n.nspname || '.' || c.relname, pg_get_userbyid(c.relowner)
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')
            ORDER BY 1
        " >"$output"
    psql -X "$connection" \
        --set ON_ERROR_STOP=1 \
        --command "COPY (
            SELECT installed_rank, version, description, type, script, checksum, success
            FROM flyway_schema_history ORDER BY installed_rank
        ) TO STDOUT WITH CSV HEADER" >"$output.flyway"
}

psql_row_manifest() {
    local connection="$1"
    local output="$2"
    local table count
    : >"$output"
    while IFS= read -r table; do
        [[ -n "$table" ]] || continue
        count="$(psql -X "$connection" -At --set ON_ERROR_STOP=1 -c "SELECT count(*) FROM public.\"$table\"")"
        [[ "$count" =~ ^[0-9]+$ ]] || die "invalid PostgreSQL row count for $table"
        printf '%s\t%s\n' "$table" "$count" >>"$output"
    done < <(
        psql -X "$connection" -At --set ON_ERROR_STOP=1 \
            -c "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename"
    )
}

assert_non_empty_recovery_source() {
    local row_manifest="$1"
    local media_manifest_path="$2"
    local media_rows object_count
    media_rows="$(awk -F '\t' '$1 == "someday_media_v3_objects" { print $2 }' "$row_manifest")"
    object_count="$(awk -F '\t' '$3 !~ /(^|\/)\.someday-system\// { count++ } END { print count + 0 }' "$media_manifest_path")"
    [[ "$media_rows" =~ ^[1-9][0-9]*$ ]] || die "recovery source has no PostgreSQL media rows"
    [[ "$object_count" =~ ^[1-9][0-9]*$ ]] || die "recovery source has no published media objects"
}

assert_relation_owner() {
    local ownership_manifest="$1"
    local expected_owner="$2"
    [[ -s "$ownership_manifest" ]] || die "database ownership manifest is empty"
    awk -F '\t' -v owner="$expected_owner" '$2 != owner { exit 1 }' "$ownership_manifest" ||
        die "public relations are not owned by $expected_owner"
}

media_manifest() {
    local root="$1"
    local output="$2"
    python3 - "$root" "$output" <<'PY'
import hashlib
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
lines = []
for path in sorted(root.rglob("*")):
    if path.is_symlink():
        raise SystemExit(f"media tree contains a symlink: {path}")
    if path.is_file():
        lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}\t{path.stat().st_size}\t{path.relative_to(root)}")
pathlib.Path(sys.argv[2]).write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
PY
}

run_managed_integrity() {
    local database_url="$1"
    local database_user="$2"
    local database_password="$3"
    local database_tls_mode="$4"
    shift 4
    env \
        SOMEDAY_DEPLOYMENT_MODE=production \
        SOMEDAY_PUBLIC_BASE_URL=https://someday-managed-gate.invalid \
        SOMEDAY_DB_URL="$database_url" \
        SOMEDAY_DB_USER="$database_user" \
        SOMEDAY_DB_PASSWORD="$database_password" \
        SOMEDAY_DB_TLS_MODE="$database_tls_mode" \
        SOMEDAY_JWT_SECRET="$JWT_SECRET" \
        "$@" \
        "$ROOT_DIR/server/build/install/server/bin/verify-media-integrity"
}

docker_database_row_manifest() {
    local container="$1"
    local administrator="$2"
    local database="$3"
    local output="$4"
    local table count
    : >"$output"
    while IFS= read -r table; do
        [[ -n "$table" ]] || continue
        count="$(docker exec "$container" psql -U "$administrator" -d "$database" -At \
            --set ON_ERROR_STOP=1 -c "SELECT count(*) FROM public.\"$table\"")"
        [[ "$count" =~ ^[0-9]+$ ]] || die "invalid row count for $database.$table"
        printf '%s\t%s\n' "$table" "$count" >>"$output"
    done < <(
        docker exec "$container" psql -U "$administrator" -d "$database" -At \
            --set ON_ERROR_STOP=1 \
            -c "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename"
    )
}

docker_database_owner_manifest() {
    local container="$1"
    local administrator="$2"
    local database="$3"
    local output="$4"
    docker exec "$container" psql -U "$administrator" -d "$database" -At \
        --field-separator $'\t' \
        --set ON_ERROR_STOP=1 \
        -c "
            SELECT n.nspname || '.' || c.relname, pg_get_userbyid(c.relowner)
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')
            ORDER BY 1
        " >"$output"
}
