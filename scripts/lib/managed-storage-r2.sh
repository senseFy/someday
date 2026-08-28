#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROFILE=r2
source "$SCRIPT_DIR/lib/managed-storage-common.sh"

run_r2_integrity() {
    local database_url="$1"
    local endpoint="$2"
    local bucket="$3"
    local access_key="$4"
    local secret_key="$5"
    run_managed_integrity \
        "$database_url" someday_app r2-app-only-password private \
        SOMEDAY_MEDIA_BACKEND=s3 \
        SOMEDAY_MEDIA_S3_BUCKET="$bucket" \
        SOMEDAY_MEDIA_S3_REGION=auto \
        SOMEDAY_MEDIA_S3_ENDPOINT="$endpoint" \
        SOMEDAY_MEDIA_S3_PATH_STYLE=true \
        AWS_ACCESS_KEY_ID="$access_key" \
        AWS_SECRET_ACCESS_KEY="$secret_key"
}

r2_wrangler_read() {
    local output_file="$1"
    local wrangler_cwd="$2"
    shift 2
    local attempt status

    for attempt in 1 2 3; do
        if NO_COLOR=1 wrangler --cwd "$wrangler_cwd" "$@" >"$output_file"; then
            return 0
        else
            status=$?
        fi
        rm -f "$output_file"
        if ((attempt == 3)); then
            return "$status"
        fi
        info "Cloudflare control-plane read failed; retrying ($attempt/3)"
        sleep "$attempt"
    done
}

r2_bucket_evidence() {
    local bucket="$1"
    local label="$2"
    local lock_output="$FINAL_DIR/$label-lock.txt"
    local lifecycle_output="$FINAL_DIR/$label-lifecycle.txt"
    local dev_url_output="$FINAL_DIR/$label-dev-url.txt"
    local domain_output="$FINAL_DIR/$label-domains.txt"
    local wrangler_cwd="$RUN_DIR/wrangler"
    mkdir -p "$wrangler_cwd"
    r2_wrangler_read "$FINAL_DIR/$label-info.json" "$wrangler_cwd" \
        r2 bucket info "$bucket" --json
    r2_wrangler_read "$lock_output" "$wrangler_cwd" \
        r2 bucket lock list "$bucket"
    r2_wrangler_read "$lifecycle_output" "$wrangler_cwd" \
        r2 bucket lifecycle list "$bucket"
    r2_wrangler_read "$dev_url_output" "$wrangler_cwd" \
        r2 bucket dev-url get "$bucket"
    r2_wrangler_read "$domain_output" "$wrangler_cwd" \
        r2 bucket domain list "$bucket"
    python3 - "$lock_output" <<'PY' || die "$label bucket has no enabled indefinite media/v1 lock rule"
import pathlib
import sys

for block in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").split("\n\n"):
    fields = {}
    for line in block.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            fields[key.strip().lower()] = value.strip().lower()
    if (
        fields.get("enabled") == "yes"
        and fields.get("prefix", "").rstrip("/") == "media/v1"
        and fields.get("condition") == "indefinitely"
    ):
        break
else:
    raise SystemExit(1)
PY
    if rg -qi 'Expire objects' "$lifecycle_output"; then
        die "$label bucket has an object-expiry lifecycle rule"
    fi
    rg -qi 'disabled' "$dev_url_output" || die "$label bucket r2.dev URL is not disabled"
    if rg -qi 'hostname:|domain:' "$domain_output"; then
        die "$label bucket has a public custom domain"
    fi
}

r2_assert_bucket_empty() {
    local label="$1"
    local endpoint="$2"
    local bucket="$3"
    local access_key="$4"
    local secret_key="$5"
    local listing="$RUN_DIR/$label-initial-objects.txt"
    ENDPOINT="$endpoint" \
    BUCKET="$bucket" \
    ACCESS_KEY="$access_key" \
    SECRET_KEY="$secret_key" \
        docker run --rm \
            --env ENDPOINT \
            --env BUCKET \
            --env ACCESS_KEY \
            --env SECRET_KEY \
            --entrypoint /bin/sh \
            "$MINIO_MC_IMAGE" \
            -c 'mc alias set gate "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY" >/dev/null && mc ls --recursive gate/"$BUCKET"' \
        >"$listing"
    [[ ! -s "$listing" ]] || die "$label bucket is not empty; use a fresh dedicated bucket"
}

r2_assert_cross_bucket_denied() {
    local label="$1"
    local endpoint="$2"
    local bucket="$3"
    local access_key="$4"
    local secret_key="$5"
    ENDPOINT="$endpoint" \
    BUCKET="$bucket" \
    ACCESS_KEY="$access_key" \
    SECRET_KEY="$secret_key" \
        docker run --rm \
            --env ENDPOINT \
            --env BUCKET \
            --env ACCESS_KEY \
            --env SECRET_KEY \
            --entrypoint /bin/sh \
            "$MINIO_MC_IMAGE" \
            -c 'mc alias set target "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY" >/dev/null; set +e; mc ls target/"$BUCKET" >/dev/null 2>&1; list_status=$?; mc cat target/"$BUCKET"/media/v1/.someday-system/startup-probe-v1.bin >/dev/null 2>&1; read_status=$?; printf scope-test | mc pipe target/"$BUCKET"/media/v1/.someday-system/cross-bucket-write-must-fail.bin >/dev/null 2>&1; write_status=$?; test "$list_status" -ne 0 && test "$read_status" -ne 0 && test "$write_status" -ne 0' ||
        die "$label credential can list, read, or write the other bucket"
}

r2_assert_delete_capability() {
    local endpoint="$1"
    local bucket="$2"
    local access_key="$3"
    local secret_key="$4"
    ENDPOINT="$endpoint" \
    BUCKET="$bucket" \
    ACCESS_KEY="$access_key" \
    SECRET_KEY="$secret_key" \
        docker run --rm \
            --env ENDPOINT \
            --env BUCKET \
            --env ACCESS_KEY \
            --env SECRET_KEY \
            --entrypoint /bin/sh \
            "$MINIO_MC_IMAGE" \
            -c 'mc alias set target "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY" >/dev/null; canary=".someday-lock-delete-canary-$$.bin"; printf delete-capability | mc pipe target/"$BUCKET"/"$canary" >/dev/null; mc rm --force target/"$BUCKET"/"$canary" >/dev/null' ||
        die "R2 credential could not prove delete access outside the locked prefix"
}

r2_assert_lock_enforced() {
    local endpoint="$1"
    local bucket="$2"
    local access_key="$3"
    local secret_key="$4"
    ENDPOINT="$endpoint" \
    BUCKET="$bucket" \
    ACCESS_KEY="$access_key" \
    SECRET_KEY="$secret_key" \
        docker run --rm \
            --env ENDPOINT \
            --env BUCKET \
            --env ACCESS_KEY \
            --env SECRET_KEY \
            --entrypoint /bin/sh \
            "$MINIO_MC_IMAGE" \
            -c 'mc alias set target "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY" >/dev/null; set +e; mc rm --force target/"$BUCKET"/media/v1/.someday-system/startup-probe-v1.bin >/dev/null 2>&1; delete_status=$?; set -e; test "$delete_status" -ne 0; mc cat target/"$BUCKET"/media/v1/.someday-system/startup-probe-v1.bin >/dev/null' ||
        die "R2 Bucket Lock did not reject deletion while preserving the startup probe"
}

r2_mirror_bucket_to_directory() {
    local endpoint="$1"
    local bucket="$2"
    local access_key="$3"
    local secret_key="$4"
    local host_directory="$5"
    mkdir -p "$host_directory"
    ENDPOINT="$endpoint" \
    BUCKET="$bucket" \
    ACCESS_KEY="$access_key" \
    SECRET_KEY="$secret_key" \
        docker run --rm \
            --env ENDPOINT \
            --env BUCKET \
            --env ACCESS_KEY \
            --env SECRET_KEY \
            --volume "$host_directory:/destination" \
            --entrypoint /bin/sh \
            "$MINIO_MC_IMAGE" \
            -c 'mc alias set remote "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY" >/dev/null && mc mirror remote/"$BUCKET" /destination'
}

r2_split_restore_manifests() {
    local source_manifest="$1"
    local system_manifest="$2"
    local application_manifest="$3"
    : >"$system_manifest"
    : >"$application_manifest"
    awk -F '\t' \
        -v system_output="$system_manifest" \
        -v application_output="$application_manifest" '
        NF != 3 ||
        length($1) != 64 || $1 ~ /[^0-9a-f]/ ||
        $2 !~ /^(0|[1-9][0-9]*)$/ ||
        $3 !~ /^media\/v1\/[A-Za-z0-9._\/-]+$/ ||
        $3 ~ /\/\// || $3 ~ /\/\.\.?\// || $3 ~ /\/\.\.?$/ {
            exit 1
        }
        $3 ~ /^media\/v1\/\.someday-system\// { print > system_output; next }
        { print > application_output }
    ' "$source_manifest" || die "R2 media manifest contains an invalid entry"
    [[ -s "$system_manifest" ]] || die "R2 media manifest contains no system object"
    [[ -s "$application_manifest" ]] || die "R2 media manifest contains no application object"
}

r2_prepare_restore_manifests() {
    local host_directory="$1"
    local expected_manifest="$2"
    local current_manifest="$3"
    local system_manifest="$4"
    local application_manifest="$5"
    media_manifest "$host_directory" "$current_manifest"
    cmp -s "$expected_manifest" "$current_manifest" ||
        die "off-provider R2 files no longer match their byte manifest"
    r2_split_restore_manifests "$current_manifest" "$system_manifest" "$application_manifest"
}

r2_restore_manifest_to_bucket() {
    local host_directory="$1"
    local manifest="$2"
    local endpoint="$3"
    local bucket="$4"
    local access_key="$5"
    local secret_key="$6"
    [[ "$host_directory" == /* ]] || die "R2 restore directory must be absolute"
    [[ "$manifest" == /* && -s "$manifest" ]] || die "R2 restore manifest must be a non-empty absolute file"
    ENDPOINT="$endpoint" \
    BUCKET="$bucket" \
    ACCESS_KEY="$access_key" \
    SECRET_KEY="$secret_key" \
        docker run --rm \
            --env ENDPOINT \
            --env BUCKET \
            --env ACCESS_KEY \
            --env SECRET_KEY \
            --volume "$host_directory:/source:ro" \
            --volume "$manifest:/manifest:ro" \
            --entrypoint /bin/sh \
            "$MINIO_MC_IMAGE" \
            -c '
            set -eu
            mc alias set remote "$ENDPOINT" "$ACCESS_KEY" "$SECRET_KEY" >/dev/null
            tab="$(printf "\t")"
            while IFS="$tab" read -r digest bytes key; do
                file="/source/$key"
                actual_bytes="$(wc -c <"$file" | tr -d "[:space:]")"
                actual_digest="$(sha256sum "$file")"
                actual_digest="${actual_digest%% *}"
                [ "$actual_bytes" = "$bytes" ] && [ "$actual_digest" = "$digest" ] || {
                    printf "R2 restore source changed: %s\n" "$key" >&2
                    exit 1
                }
                mc cp \
                    --attr "someday-ciphertext-sha256=sha256:$digest" \
                    "$file" "remote/$BUCKET/$key"
            done </manifest
        '
}

run_r2_gate() {
    local required=(
        CLOUDFLARE_API_TOKEN
        CLOUDFLARE_ACCOUNT_ID
        SOMEDAY_R2_SOURCE_ENDPOINT
        SOMEDAY_R2_SOURCE_BUCKET
        SOMEDAY_R2_SOURCE_ACCESS_KEY_ID
        SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY
        SOMEDAY_R2_RESTORE_ENDPOINT
        SOMEDAY_R2_RESTORE_BUCKET
        SOMEDAY_R2_RESTORE_ACCESS_KEY_ID
        SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY
        SOMEDAY_R2_OFF_PROVIDER_DIR
        SOMEDAY_MANAGED_GATE_ALLOW_RESET
        SOMEDAY_R2_RESET_TARGETS
    )
    local name source_status restore_status attempt missing_status backend_port expected_reset_targets
    local current_media_manifest system_media_manifest application_media_manifest
    local source_database="someday_r2_source"
    local restored_database="someday_r2_restore"
    for name in "${required[@]}"; do require_env "$name"; done
    require_java_21
    [[ "$SOMEDAY_MANAGED_GATE_ALLOW_RESET" == "YES" ]] ||
        die "set SOMEDAY_MANAGED_GATE_ALLOW_RESET=YES for two disposable dedicated buckets"
    [[ "$SOMEDAY_R2_SOURCE_BUCKET" != "$SOMEDAY_R2_RESTORE_BUCKET" ]] ||
        die "source and isolated restore buckets must differ"
    python3 - \
        "$CLOUDFLARE_ACCOUNT_ID" \
        "$SOMEDAY_R2_SOURCE_ENDPOINT" \
        "$SOMEDAY_R2_RESTORE_ENDPOINT" <<'PY'
import re
import sys
import urllib.parse

account_id = sys.argv[1]
if not re.fullmatch(r"[0-9a-f]{32}", account_id):
    raise SystemExit("CLOUDFLARE_ACCOUNT_ID must be 32 lowercase hex characters")
expected_host = f"{account_id}.r2.cloudflarestorage.com"
for value in sys.argv[2:]:
    parsed = urllib.parse.urlparse(value)
    if (
        parsed.scheme != "https"
        or parsed.hostname != expected_host
        or parsed.netloc != expected_host
        or parsed.path not in {"", "/"}
        or parsed.params
        or parsed.query
        or parsed.fragment
    ):
        raise SystemExit(f"R2 endpoints must use exactly https://{expected_host}")
PY
    expected_reset_targets="$CLOUDFLARE_ACCOUNT_ID/$SOMEDAY_R2_SOURCE_BUCKET,$CLOUDFLARE_ACCOUNT_ID/$SOMEDAY_R2_RESTORE_BUCKET"
    [[ "$SOMEDAY_R2_RESET_TARGETS" == "$expected_reset_targets" ]] ||
        die "SOMEDAY_R2_RESET_TARGETS must equal $expected_reset_targets"
    [[ "$SOMEDAY_R2_OFF_PROVIDER_DIR" == /* ]] || die "off-provider directory must be absolute"
    mkdir -p "$SOMEDAY_R2_OFF_PROVIDER_DIR"
    [[ -z "$(find "$SOMEDAY_R2_OFF_PROVIDER_DIR" -mindepth 1 -print -quit)" ]] ||
        die "off-provider directory must be empty"

    info "capturing Cloudflare bucket policy evidence"
    r2_bucket_evidence "$SOMEDAY_R2_SOURCE_BUCKET" source
    r2_bucket_evidence "$SOMEDAY_R2_RESTORE_BUCKET" restore
    source_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
        "$SOMEDAY_R2_SOURCE_ENDPOINT/$SOMEDAY_R2_SOURCE_BUCKET")"
    restore_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
        "$SOMEDAY_R2_RESTORE_ENDPOINT/$SOMEDAY_R2_RESTORE_BUCKET")"
    [[ "$source_status" == "400" || "$source_status" == "401" || "$source_status" == "403" ]] ||
        die "source R2 S3 endpoint did not reject an anonymous request"
    [[ "$restore_status" == "400" || "$restore_status" == "401" || "$restore_status" == "403" ]] ||
        die "restore R2 S3 endpoint did not reject an anonymous request"

    if ! docker image inspect "$POSTGRES_IMAGE" >/dev/null 2>&1; then docker pull "$POSTGRES_IMAGE" >/dev/null; fi
    if ! docker image inspect "$MINIO_MC_IMAGE" >/dev/null 2>&1; then docker pull "$MINIO_MC_IMAGE" >/dev/null; fi
    r2_assert_bucket_empty \
        source "$SOMEDAY_R2_SOURCE_ENDPOINT" "$SOMEDAY_R2_SOURCE_BUCKET" \
        "$SOMEDAY_R2_SOURCE_ACCESS_KEY_ID" "$SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY"
    r2_assert_bucket_empty \
        restore "$SOMEDAY_R2_RESTORE_ENDPOINT" "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    POSTGRES_NAME="someday-r2-gate-postgres-$(date +%s)-$$"
    docker run -d --rm \
        --name "$POSTGRES_NAME" \
        --tmpfs /var/lib/postgresql/data:rw,noexec,nosuid,size=512m \
        -e POSTGRES_DB=postgres \
        -e POSTGRES_USER=someday_admin \
        -e POSTGRES_PASSWORD=r2-admin-only-password \
        -p 127.0.0.1::5432 \
        "$POSTGRES_IMAGE" >/dev/null
    POSTGRES_PORT="$(docker port "$POSTGRES_NAME" 5432/tcp | sed -n '1s/.*://p' | tr -d '[:space:]')"
    for attempt in $(seq 1 60); do
        docker exec "$POSTGRES_NAME" pg_isready -U someday_admin -d postgres >/dev/null 2>&1 && break
        sleep 1
    done
    docker exec "$POSTGRES_NAME" pg_isready -U someday_admin -d postgres >/dev/null 2>&1 || die "PostgreSQL did not start"
    docker exec "$POSTGRES_NAME" psql -U someday_admin -d postgres --set ON_ERROR_STOP=1 \
        -c "CREATE ROLE someday_app LOGIN PASSWORD 'r2-app-only-password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS" >/dev/null
    docker exec "$POSTGRES_NAME" createdb -U someday_admin -O someday_app "$source_database"
    docker exec "$POSTGRES_NAME" createdb -U someday_admin -O someday_app "$restored_database"
    SOURCE_DB_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/$source_database"
    RESTORED_DB_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/$restored_database"

    info "proving the R2 S3 contract with the bucket-scoped token"
    SOMEDAY_S3_TEST_ENDPOINT="$SOMEDAY_R2_SOURCE_ENDPOINT" \
    SOMEDAY_S3_TEST_BUCKET="$SOMEDAY_R2_SOURCE_BUCKET" \
    SOMEDAY_S3_TEST_REGION=auto \
    AWS_ACCESS_KEY_ID="$SOMEDAY_R2_SOURCE_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY" \
        "$GRADLEW" :server:s3IntegrationTest "$GATE_PROPERTY" \
            --dependency-verification=strict --stacktrace
    "$GRADLEW" :server:installDist "$GATE_PROPERTY" --dependency-verification=strict --stacktrace
    JWT_SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
    SERVER_PORT="$(free_port)"
    SERVER_ENDPOINT="http://127.0.0.1:$SERVER_PORT"
    start_managed_server \
        "$SOURCE_DB_URL" someday_app r2-app-only-password private \
        "$SERVER_PORT" true "$RUN_DIR/source-server.log" \
        SOMEDAY_MEDIA_BACKEND=s3 \
        SOMEDAY_MEDIA_S3_BUCKET="$SOMEDAY_R2_SOURCE_BUCKET" \
        SOMEDAY_MEDIA_S3_REGION=auto \
        SOMEDAY_MEDIA_S3_ENDPOINT="$SOMEDAY_R2_SOURCE_ENDPOINT" \
        SOMEDAY_MEDIA_S3_PATH_STYLE=true \
        AWS_ACCESS_KEY_ID="$SOMEDAY_R2_SOURCE_ACCESS_KEY_ID" \
        AWS_SECRET_ACCESS_KEY="$SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY"
    start_recovery_journey "$SERVER_ENDPOINT"
    stop_server

    info "proving bucket scope and live lock enforcement"
    r2_assert_cross_bucket_denied \
        restore "$SOMEDAY_R2_SOURCE_ENDPOINT" "$SOMEDAY_R2_SOURCE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    r2_assert_delete_capability \
        "$SOMEDAY_R2_SOURCE_ENDPOINT" "$SOMEDAY_R2_SOURCE_BUCKET" \
        "$SOMEDAY_R2_SOURCE_ACCESS_KEY_ID" "$SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY"
    r2_assert_lock_enforced \
        "$SOMEDAY_R2_SOURCE_ENDPOINT" "$SOMEDAY_R2_SOURCE_BUCKET" \
        "$SOMEDAY_R2_SOURCE_ACCESS_KEY_ID" "$SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY"

    info "capturing R2 into the explicit off-provider directory"
    docker_database_row_manifest \
        "$POSTGRES_NAME" someday_admin "$source_database" "$FINAL_DIR/source-rows.tsv"
    docker_database_owner_manifest \
        "$POSTGRES_NAME" someday_admin "$source_database" "$FINAL_DIR/source-owners.tsv"
    docker exec "$POSTGRES_NAME" psql -U someday_admin -d "$source_database" \
        --set ON_ERROR_STOP=1 \
        -c "COPY (
            SELECT installed_rank, version, description, type, script, checksum, success
            FROM flyway_schema_history ORDER BY installed_rank
        ) TO STDOUT WITH CSV HEADER" >"$FINAL_DIR/source-flyway.csv"
    docker exec "$POSTGRES_NAME" pg_dump -U someday_admin -d "$source_database" -Fc >"$RUN_DIR/postgresql.dump"
    r2_mirror_bucket_to_directory \
        "$SOMEDAY_R2_SOURCE_ENDPOINT" \
        "$SOMEDAY_R2_SOURCE_BUCKET" \
        "$SOMEDAY_R2_SOURCE_ACCESS_KEY_ID" \
        "$SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY" \
        "$SOMEDAY_R2_OFF_PROVIDER_DIR"
    [[ -n "$(find "$SOMEDAY_R2_OFF_PROVIDER_DIR" -type f -print -quit)" ]] || die "off-provider R2 copy is empty"
    media_manifest "$SOMEDAY_R2_OFF_PROVIDER_DIR" "$FINAL_DIR/off-provider-media.tsv"
    assert_non_empty_recovery_source "$FINAL_DIR/source-rows.tsv" "$FINAL_DIR/off-provider-media.tsv"
    assert_relation_owner "$FINAL_DIR/source-owners.tsv" someday_app

    docker exec -i "$POSTGRES_NAME" pg_restore \
        -U someday_admin -d "$restored_database" --no-acl --exit-on-error <"$RUN_DIR/postgresql.dump"
    docker_database_row_manifest \
        "$POSTGRES_NAME" someday_admin "$restored_database" "$FINAL_DIR/restored-rows.tsv"
    docker_database_owner_manifest \
        "$POSTGRES_NAME" someday_admin "$restored_database" "$FINAL_DIR/restored-owners.tsv"
    cmp -s "$FINAL_DIR/source-rows.tsv" "$FINAL_DIR/restored-rows.tsv" ||
        die "R2 profile database row counts differ after restore"
    cmp -s "$FINAL_DIR/source-owners.tsv" "$FINAL_DIR/restored-owners.tsv" ||
        die "R2 profile database ownership differs after restore"
    assert_relation_owner "$FINAL_DIR/restored-owners.tsv" someday_app
    docker exec "$POSTGRES_NAME" psql -U someday_admin -d "$restored_database" \
        --set ON_ERROR_STOP=1 \
        -c "COPY (
            SELECT installed_rank, version, description, type, script, checksum, success
            FROM flyway_schema_history ORDER BY installed_rank
        ) TO STDOUT WITH CSV HEADER" >"$FINAL_DIR/restored-flyway.csv"
    cmp -s "$FINAL_DIR/source-flyway.csv" "$FINAL_DIR/restored-flyway.csv" ||
        die "R2 profile database Flyway history differs after restore"
    current_media_manifest="$RUN_DIR/current-off-provider-media.tsv"
    system_media_manifest="$RUN_DIR/system-media.tsv"
    application_media_manifest="$RUN_DIR/application-media.tsv"
    r2_prepare_restore_manifests \
        "$SOMEDAY_R2_OFF_PROVIDER_DIR" \
        "$FINAL_DIR/off-provider-media.tsv" \
        "$current_media_manifest" \
        "$system_media_manifest" \
        "$application_media_manifest"
    info "proving an incomplete isolated R2 restore exits 2"
    r2_restore_manifest_to_bucket \
        "$SOMEDAY_R2_OFF_PROVIDER_DIR" \
        "$system_media_manifest" \
        "$SOMEDAY_R2_RESTORE_ENDPOINT" \
        "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" \
        "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    r2_assert_cross_bucket_denied \
        source "$SOMEDAY_R2_RESTORE_ENDPOINT" "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_SOURCE_ACCESS_KEY_ID" "$SOMEDAY_R2_SOURCE_SECRET_ACCESS_KEY"
    set +e
    run_r2_integrity \
        "$RESTORED_DB_URL" "$SOMEDAY_R2_RESTORE_ENDPOINT" "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    missing_status=$?
    set -e
    [[ "$missing_status" -eq 2 ]] || die "incomplete isolated R2 restore returned $missing_status instead of 2"

    info "restoring the complete off-provider copy into isolated R2"
    r2_restore_manifest_to_bucket \
        "$SOMEDAY_R2_OFF_PROVIDER_DIR" \
        "$application_media_manifest" \
        "$SOMEDAY_R2_RESTORE_ENDPOINT" \
        "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" \
        "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    run_r2_integrity \
        "$RESTORED_DB_URL" "$SOMEDAY_R2_RESTORE_ENDPOINT" "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    r2_assert_delete_capability \
        "$SOMEDAY_R2_RESTORE_ENDPOINT" "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    r2_assert_lock_enforced \
        "$SOMEDAY_R2_RESTORE_ENDPOINT" "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    r2_mirror_bucket_to_directory \
        "$SOMEDAY_R2_RESTORE_ENDPOINT" \
        "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" \
        "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY" \
        "$RUN_DIR/isolated-roundtrip"
    media_manifest "$RUN_DIR/isolated-roundtrip" "$FINAL_DIR/isolated-media.tsv"
    cmp -s "$FINAL_DIR/off-provider-media.tsv" "$FINAL_DIR/isolated-media.tsv" ||
        die "isolated R2 media count or byte digests differ from the off-provider copy"

    backend_port="$(free_port)"
    start_managed_server \
        "$RESTORED_DB_URL" someday_app r2-app-only-password private \
        "$backend_port" false "$RUN_DIR/restored-server.log" \
        SOMEDAY_MEDIA_BACKEND=s3 \
        SOMEDAY_MEDIA_S3_BUCKET="$SOMEDAY_R2_RESTORE_BUCKET" \
        SOMEDAY_MEDIA_S3_REGION=auto \
        SOMEDAY_MEDIA_S3_ENDPOINT="$SOMEDAY_R2_RESTORE_ENDPOINT" \
        SOMEDAY_MEDIA_S3_PATH_STYLE=true \
        AWS_ACCESS_KEY_ID="$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" \
        AWS_SECRET_ACCESS_KEY="$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY"
    finish_recovery_journey "$SERVER_PORT" "$backend_port"
    docker_database_row_manifest \
        "$POSTGRES_NAME" someday_admin "$restored_database" "$RUN_DIR/restored-post-client-rows.tsv"
    cmp -s "$FINAL_DIR/source-rows.tsv" "$RUN_DIR/restored-post-client-rows.tsv" ||
        die "R2 profile database row counts changed during read-only client verification"
    r2_mirror_bucket_to_directory \
        "$SOMEDAY_R2_RESTORE_ENDPOINT" \
        "$SOMEDAY_R2_RESTORE_BUCKET" \
        "$SOMEDAY_R2_RESTORE_ACCESS_KEY_ID" \
        "$SOMEDAY_R2_RESTORE_SECRET_ACCESS_KEY" \
        "$RUN_DIR/post-client-roundtrip"
    media_manifest "$RUN_DIR/post-client-roundtrip" "$RUN_DIR/post-client-media.tsv"
    cmp -s "$FINAL_DIR/isolated-media.tsv" "$RUN_DIR/post-client-media.tsv" ||
        die "R2 media changed during read-only client verification"
    write_success_result \
        r2 \
        "$CLOUDFLARE_ACCOUNT_ID/$SOMEDAY_R2_SOURCE_BUCKET" \
        "$CLOUDFLARE_ACCOUNT_ID/$SOMEDAY_R2_RESTORE_BUCKET"
    info "R2 live profile passed"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    initialize_managed_gate
    for command in curl git java python3 docker rg wrangler; do
        require_command "$command"
    done
    [[ -x "$GRADLEW" ]] || die "Gradle wrapper is not executable: $GRADLEW"
    require_wrangler_version
    docker info >/dev/null 2>&1 || die "Docker daemon is required"
    cd "$ROOT_DIR"
    run_r2_gate
fi
