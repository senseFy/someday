#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
default_root="$(cd "$SCRIPT_DIR/.." && pwd)"
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    ROOT_DIR="$default_root"
else
    ROOT_DIR="${ROOT_DIR:-$default_root}"
fi
PROFILE=planetscale
source "$SCRIPT_DIR/lib/managed-storage-common.sh"

require_direct_tls_url() {
    local label="$1"
    local jdbc_url="$2"
    python3 - "$label" "$jdbc_url" <<'PY'
import sys
import urllib.parse
import re

label, raw = sys.argv[1:]
if not raw.startswith("jdbc:postgresql://"):
    raise SystemExit(f"{label} must be a PostgreSQL JDBC URL")
parsed = urllib.parse.urlparse(raw.removeprefix("jdbc:"))
if parsed.port != 5432:
    raise SystemExit(f"{label} must use the direct port 5432")
host = (parsed.hostname or "").lower()
labels = host.split(".")
valid_dns_name = all(
    label_part
    and len(label_part) <= 63
    and re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", label_part)
    for label_part in labels
)
if not valid_dns_name or not host.endswith((".horizon.psdb.cloud", ".pg.psdb.cloud")):
    raise SystemExit(f"{label} must use an official PlanetScale Postgres host")
if parsed.username or parsed.password:
    raise SystemExit(f"{label} must not embed credentials")
if parsed.fragment:
    raise SystemExit(f"{label} must not contain a fragment")
query_items = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
query = {key.lower(): value for key, value in query_items}
if len(query_items) != len(query) or set(query) != {"sslmode"}:
    raise SystemExit(f"{label} may contain only one sslmode query parameter")
if query["sslmode"].lower() != "verify-full":
    raise SystemExit(f"{label} must set sslmode=verify-full")
PY
}

require_psql_tls_url() {
    local label="$1"
    local connection="$2"
    python3 - "$label" "$connection" <<'PY'
import sys
import urllib.parse
import re

label, raw = sys.argv[1:]
parsed = urllib.parse.urlparse(raw)
if parsed.scheme not in {"postgres", "postgresql"} or parsed.port != 5432:
    raise SystemExit(f"{label} must use a direct PostgreSQL URL on port 5432")
host = (parsed.hostname or "").lower()
labels = host.split(".")
valid_dns_name = all(
    label_part
    and len(label_part) <= 63
    and re.fullmatch(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?", label_part)
    for label_part in labels
)
if not valid_dns_name or not host.endswith((".horizon.psdb.cloud", ".pg.psdb.cloud")):
    raise SystemExit(f"{label} must use an official PlanetScale Postgres host")
if not parsed.username or parsed.password is not None:
    raise SystemExit(f"{label} must include a username but no password")
if parsed.fragment:
    raise SystemExit(f"{label} must not contain a fragment")
query_items = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
query = {key.lower(): value for key, value in query_items}
allowed = {"sslmode", "sslrootcert", "sslnegotiation"}
if len(query_items) != len(query) or not set(query).issubset(allowed):
    raise SystemExit(f"{label} contains an unsupported or repeated query parameter")
if query.get("sslmode", "").lower() != "verify-full":
    raise SystemExit(f"{label} must set sslmode=verify-full")
if query.get("sslrootcert", "").lower() != "system":
    raise SystemExit(f"{label} must set sslrootcert=system")
if "sslnegotiation" in query and query["sslnegotiation"].lower() != "direct":
    raise SystemExit(f"{label} sslnegotiation must be direct when set")
PY
}

database_endpoint() {
    python3 - "$1" <<'PY'
import sys
import urllib.parse

raw = sys.argv[1]
if raw.startswith("jdbc:"):
    raw = raw.removeprefix("jdbc:")
parsed = urllib.parse.urlparse(raw)
database = urllib.parse.unquote(parsed.path.lstrip("/"))
if not parsed.hostname or parsed.port != 5432 or not database:
    raise SystemExit("invalid direct PostgreSQL target")
print(f"{parsed.hostname.lower()}:5432/{database}")
PY
}

url_username() {
    python3 - "$1" <<'PY'
import sys
import urllib.parse

raw = sys.argv[1]
if raw.startswith("jdbc:"):
    raw = raw.removeprefix("jdbc:")
parsed = urllib.parse.urlparse(raw)
username = urllib.parse.unquote(parsed.username or "")
if not username:
    raise SystemExit("PostgreSQL URL does not contain a username")
print(username)
PY
}

planetscale_base_username() {
    python3 - "$1" <<'PY'
import sys

username = sys.argv[1]
if "." not in username:
    raise SystemExit("PlanetScale username does not contain a branch id")
print(username.rsplit(".", 1)[0])
PY
}

planetscale_target() {
    python3 - "$1" <<'PY'
import sys
import urllib.parse

raw = sys.argv[1]
if raw.startswith("jdbc:"):
    raw = raw.removeprefix("jdbc:")
parsed = urllib.parse.urlparse(raw)
database = urllib.parse.unquote(parsed.path.lstrip("/"))
username = urllib.parse.unquote(parsed.username or "")
if not parsed.hostname or parsed.port != 5432 or not database or "." not in username:
    raise SystemExit("invalid routed PlanetScale PostgreSQL target")
branch_id = username.rsplit(".", 1)[1].split("|", 1)[0]
if not branch_id:
    raise SystemExit("PlanetScale username does not contain a branch id")
print(f"{branch_id}@{parsed.hostname.lower()}:5432/{database}")
PY
}

validate_planetscale_target_confirmation() {
    local source_target restore_target expected
    source_target="$(planetscale_target "$1")" || return 1
    restore_target="$(planetscale_target "$2")" || return 1
    if [[ "$source_target" == "$restore_target" ]]; then
        printf 'PlanetScale source and isolated restore must be different databases\n' >&2
        return 1
    fi
    expected="$source_target,$restore_target"
    if [[ "$3" != "$expected" ]]; then
        printf 'SOMEDAY_PLANETSCALE_RESET_TARGETS must equal %s\n' "$expected" >&2
        return 1
    fi
    printf '%s\t%s\n' "$source_target" "$restore_target"
}

write_planetscale_pgpass() {
    local output="$1"
    python3 - \
        "$output" \
        "$SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL" \
        "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL" \
        "$SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL" <<'PY'
import os
import pathlib
import sys
import urllib.parse


def escaped(value: str) -> str:
    return value.replace("\\", "\\\\").replace(":", "\\:")


passwords = [
    os.environ["SOMEDAY_PLANETSCALE_SOURCE_APP_PASSWORD"],
    os.environ["SOMEDAY_PLANETSCALE_RESTORE_APP_PASSWORD"],
    os.environ["SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PASSWORD"],
    os.environ["SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PASSWORD"],
]
lines = []
for raw, password in zip(sys.argv[2:], passwords, strict=True):
    parsed = urllib.parse.urlparse(raw)
    database = urllib.parse.unquote(parsed.path.lstrip("/"))
    username = urllib.parse.unquote(parsed.username or "")
    if not parsed.hostname or parsed.port != 5432 or not database or not username:
        raise SystemExit("invalid PlanetScale PostgreSQL URL for PGPASSFILE")
    lines.append(
        ":".join(
            escaped(value)
            for value in (parsed.hostname, str(parsed.port), database, username, password)
        )
    )
path = pathlib.Path(sys.argv[1])
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
path.chmod(0o600)
PY
}

reset_planetscale_schema() {
    local admin_connection="$1"
    local app_connection="$2"
    local app_owner="$3"
    local schema_owner had_create create_status
    schema_owner="$(
        psql -X "$admin_connection" -At --set ON_ERROR_STOP=1 \
            --command "SELECT COALESCE(pg_get_userbyid(nspowner), '') FROM pg_namespace WHERE nspname = 'public'"
    )"
    if [[ "$schema_owner" == "$app_owner" ]]; then
        psql -X "$app_connection" --set ON_ERROR_STOP=1 \
            --command 'DROP SCHEMA public CASCADE' >/dev/null
    elif [[ -n "$schema_owner" ]]; then
        psql -X "$admin_connection" --set ON_ERROR_STOP=1 \
            --command 'DROP SCHEMA public CASCADE' >/dev/null
    fi

    had_create="$(
        psql -X "$admin_connection" -At --set ON_ERROR_STOP=1 --set app_role="$app_owner" <<'SQL'
SELECT has_database_privilege(:'app_role', current_database(), 'CREATE');
SQL
    )"
    if [[ "$had_create" == f ]]; then
        psql -X "$admin_connection" --set ON_ERROR_STOP=1 --set app_role="$app_owner" >/dev/null <<'SQL'
SELECT format('GRANT CREATE ON DATABASE %I TO %I', current_database(), :'app_role') \gexec
SQL
    fi
    set +e
    psql -X "$app_connection" --set ON_ERROR_STOP=1 \
        --command 'CREATE SCHEMA public AUTHORIZATION CURRENT_USER' >/dev/null
    create_status=$?
    set -e
    if [[ "$had_create" == f ]]; then
        psql -X "$admin_connection" --set ON_ERROR_STOP=1 --set app_role="$app_owner" >/dev/null <<'SQL'
SELECT format('REVOKE CREATE ON DATABASE %I FROM %I', current_database(), :'app_role') \gexec
SQL
    fi
    return "$create_status"
}

run_planetscale_gate() {
    local required=(
        SOMEDAY_PLANETSCALE_SOURCE_JDBC_URL
        SOMEDAY_PLANETSCALE_RESTORE_JDBC_URL
        SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL
        SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL
        SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL
        SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL
        SOMEDAY_PLANETSCALE_SOURCE_APP_USER
        SOMEDAY_PLANETSCALE_SOURCE_APP_PASSWORD
        SOMEDAY_PLANETSCALE_RESTORE_APP_USER
        SOMEDAY_PLANETSCALE_RESTORE_APP_PASSWORD
        SOMEDAY_PLANETSCALE_SOURCE_ADMIN_JDBC_URL
        SOMEDAY_PLANETSCALE_SOURCE_ADMIN_USER
        SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PASSWORD
        SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PASSWORD
        SOMEDAY_MANAGED_GATE_ALLOW_RESET
        SOMEDAY_PLANETSCALE_RESET_TARGETS
    )
    local name version restore_version role_flags restore_role_flags
    local source_owners restored_owners source_rows restored_rows
    local source_target restore_target target_pair source_owner restore_owner
    local missing_file missing_status backend_port restore_list schema_entry_count
    for name in "${required[@]}"; do require_env "$name"; done
    require_java_21
    [[ "$SOMEDAY_MANAGED_GATE_ALLOW_RESET" == "YES" ]] ||
        die "set SOMEDAY_MANAGED_GATE_ALLOW_RESET=YES for two disposable dedicated databases"
    require_direct_tls_url "source database" "$SOMEDAY_PLANETSCALE_SOURCE_JDBC_URL"
    require_direct_tls_url "restore database" "$SOMEDAY_PLANETSCALE_RESTORE_JDBC_URL"
    require_direct_tls_url "source administrator database" "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_JDBC_URL"
    require_psql_tls_url "source app database" "$SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL"
    require_psql_tls_url "restore app database" "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL"
    require_psql_tls_url "source admin database" "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL"
    require_psql_tls_url "restore admin database" "$SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL"
    target_pair="$(validate_planetscale_target_confirmation \
        "$SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL" \
        "$SOMEDAY_PLANETSCALE_RESET_TARGETS")" || die "PlanetScale reset target confirmation failed"
    IFS=$'\t' read -r source_target restore_target <<<"$target_pair"
    [[ "$(planetscale_target "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL")" == "$source_target" ]] ||
        die "PlanetScale source admin URL targets a different database"
    [[ "$(planetscale_target "$SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL")" == "$restore_target" ]] ||
        die "PlanetScale restore admin URL targets a different database"
    [[ "$(database_endpoint "$SOMEDAY_PLANETSCALE_SOURCE_JDBC_URL")" == \
        "$(database_endpoint "$SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL")" ]] ||
        die "PlanetScale source JDBC URL targets a different endpoint"
    [[ "$(database_endpoint "$SOMEDAY_PLANETSCALE_RESTORE_JDBC_URL")" == \
        "$(database_endpoint "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL")" ]] ||
        die "PlanetScale restore JDBC URL targets a different endpoint"
    [[ "$(database_endpoint "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_JDBC_URL")" == \
        "$(database_endpoint "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL")" ]] ||
        die "PlanetScale integration admin URL targets a different endpoint"
    [[ "$(url_username "$SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL")" == \
        "$SOMEDAY_PLANETSCALE_SOURCE_APP_USER" ]] ||
        die "PlanetScale source application username does not match its URL"
    [[ "$(url_username "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL")" == \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_USER" ]] ||
        die "PlanetScale restore application username does not match its URL"
    [[ "$(url_username "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL")" == \
        "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_USER" ]] ||
        die "PlanetScale source administrator username does not match its URL"

    MANAGED_GATE_PGPASSFILE="$(mktemp "${TMPDIR:-/tmp}/someday-planetscale-pgpass.XXXXXX")"
    PGPASSFILE="$MANAGED_GATE_PGPASSFILE"
    export PGPASSFILE
    write_planetscale_pgpass "$MANAGED_GATE_PGPASSFILE"
    version="$(psql -X "$SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL" -At --set ON_ERROR_STOP=1 -c 'SHOW server_version_num')"
    [[ "$version" =~ ^17[0-9]{4}$ ]] || die "PlanetScale source is not PostgreSQL 17: $version"
    restore_version="$(psql -X "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL" -At --set ON_ERROR_STOP=1 -c 'SHOW server_version_num')"
    [[ "$restore_version" =~ ^17[0-9]{4}$ ]] ||
        die "PlanetScale restore is not PostgreSQL 17: $restore_version"
    role_flags="$(
        psql -X "$SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL" -At --set ON_ERROR_STOP=1 \
            -c "SELECT current_user || ':' || rolsuper || ':' || rolbypassrls FROM pg_roles WHERE rolname = current_user"
    )"
    [[ "$role_flags" == \
        "$(planetscale_base_username "$SOMEDAY_PLANETSCALE_SOURCE_APP_USER"):false:false" ]] ||
        die "PlanetScale application role must be NOSUPERUSER and NOBYPASSRLS: $role_flags"
    restore_role_flags="$(
        psql -X "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL" -At --set ON_ERROR_STOP=1 \
            -c "SELECT current_user || ':' || rolsuper || ':' || rolbypassrls FROM pg_roles WHERE rolname = current_user"
    )"
    [[ "$restore_role_flags" == \
        "$(planetscale_base_username "$SOMEDAY_PLANETSCALE_RESTORE_APP_USER"):false:false" ]] ||
        die "PlanetScale restore application role must be NOSUPERUSER and NOBYPASSRLS: $restore_role_flags"
    source_owner="$(planetscale_base_username "$SOMEDAY_PLANETSCALE_SOURCE_APP_USER")"
    restore_owner="$(planetscale_base_username "$SOMEDAY_PLANETSCALE_RESTORE_APP_USER")"

    info "resetting the two explicitly disposable PlanetScale schemas"
    reset_planetscale_schema \
        "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL" \
        "$SOMEDAY_PLANETSCALE_SOURCE_PSQL_URL" \
        "$source_owner"
    reset_planetscale_schema \
        "$SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL" \
        "$restore_owner"

    info "running migrations, RLS, and PostgreSQL integration on PlanetScale"
    SOMEDAY_DB_URL="$SOMEDAY_PLANETSCALE_SOURCE_JDBC_URL" \
    SOMEDAY_DB_USER="$SOMEDAY_PLANETSCALE_SOURCE_APP_USER" \
    SOMEDAY_DB_PASSWORD="$SOMEDAY_PLANETSCALE_SOURCE_APP_PASSWORD" \
    SOMEDAY_DB_TLS_MODE=verify-full \
    SOMEDAY_DB_ADMIN_URL="$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_JDBC_URL" \
    SOMEDAY_DB_ADMIN_USER="$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_USER" \
    SOMEDAY_DB_ADMIN_PASSWORD="$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PASSWORD" \
    SOMEDAY_MEDIA_BACKEND=filesystem \
    SOMEDAY_MEDIA_BLOB_DIR="$SOURCE_MEDIA_DIR" \
        "$GRADLEW" :server:integrationTest "$GATE_PROPERTY" \
            --tests 'saien.someday.server.ProductionDatabaseBoundaryIntegrationTest.sharedMigrationBoundaryRejectsANewerSchemaBeforeApplyingAnything' \
            --tests 'saien.someday.server.ProductionDatabaseBoundaryIntegrationTest.rlsCatalogRejectsPolicyExpressionTamperingAndPassesAfterRollback' \
            --tests 'saien.someday.server.DatabaseConnectionPoolRlsIntegrationTest.checkoutClearsSessionWildcardBeforeThePhysicalConnectionIsReused' \
            --dependency-verification=strict --stacktrace

    "$GRADLEW" :server:installDist "$GATE_PROPERTY" --dependency-verification=strict --stacktrace
    JWT_SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
    SERVER_PORT="$(free_port)"
    SERVER_ENDPOINT="http://127.0.0.1:$SERVER_PORT"
    start_managed_server \
        "$SOMEDAY_PLANETSCALE_SOURCE_JDBC_URL" \
        "$SOMEDAY_PLANETSCALE_SOURCE_APP_USER" \
        "$SOMEDAY_PLANETSCALE_SOURCE_APP_PASSWORD" \
        verify-full "$SERVER_PORT" true "$RUN_DIR/source-server.log" \
        SOMEDAY_MEDIA_BACKEND=filesystem \
        SOMEDAY_MEDIA_BLOB_DIR="$SOURCE_MEDIA_DIR"
    start_recovery_journey "$SERVER_ENDPOINT"
    stop_server

    source_owners="$RUN_DIR/source-owners.tsv"
    restored_owners="$RUN_DIR/restored-owners.tsv"
    source_rows="$RUN_DIR/source-rows.tsv"
    restored_rows="$RUN_DIR/restored-rows.tsv"
    psql_manifest "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL" "$source_owners"
    psql_row_manifest "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL" "$source_rows"
    media_manifest "$SOURCE_MEDIA_DIR" "$RUN_DIR/source-media.tsv"
    assert_non_empty_recovery_source "$source_rows" "$RUN_DIR/source-media.tsv"
    assert_relation_owner "$source_owners" "$source_owner"
    pg_dump "$SOMEDAY_PLANETSCALE_SOURCE_ADMIN_PSQL_URL" \
        --schema public \
        --format custom >"$RUN_DIR/postgresql.dump"
    tar -C "$SOURCE_MEDIA_DIR" -cf "$RUN_DIR/media.tar" .
    restore_list="$RUN_DIR/postgresql.restore.list"
    pg_restore --list "$RUN_DIR/postgresql.dump" >"$restore_list.full"
    schema_entry_count="$(
        awk '$4 == "SCHEMA" && $5 == "-" && $6 == "public" { count++ } END { print count + 0 }' \
            "$restore_list.full"
    )"
    [[ "$schema_entry_count" -eq 1 ]] || die "PlanetScale dump must contain exactly one public schema entry"
    awk '$4 == "SCHEMA" && $5 == "-" && $6 == "public" { next } { print }' \
        "$restore_list.full" >"$restore_list"
    pg_restore \
        --dbname "$SOMEDAY_PLANETSCALE_RESTORE_APP_PSQL_URL" \
        --no-owner \
        --no-acl \
        --exit-on-error \
        --use-list "$restore_list" \
        "$RUN_DIR/postgresql.dump"
    tar -C "$RESTORED_MEDIA_DIR" -xf "$RUN_DIR/media.tar"
    psql_manifest "$SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL" "$restored_owners"
    psql_row_manifest "$SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL" "$restored_rows"
    media_manifest "$RESTORED_MEDIA_DIR" "$RUN_DIR/restored-media.tsv"
    cut -f1 "$source_owners" >"$RUN_DIR/source-relations.tsv"
    cut -f1 "$restored_owners" >"$RUN_DIR/restored-relations.tsv"
    cmp -s "$RUN_DIR/source-relations.tsv" "$RUN_DIR/restored-relations.tsv" ||
        die "PlanetScale restored relation set differs"
    cmp -s "$source_owners.flyway" "$restored_owners.flyway" || die "PlanetScale restored Flyway history differs"
    cmp -s "$source_rows" "$restored_rows" || die "PlanetScale restored row counts differ"
    cmp -s "$RUN_DIR/source-media.tsv" "$RUN_DIR/restored-media.tsv" || die "PlanetScale restored media differs"
    assert_relation_owner "$restored_owners" "$restore_owner"

    run_managed_integrity \
        "$SOMEDAY_PLANETSCALE_RESTORE_JDBC_URL" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_USER" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_PASSWORD" \
        verify-full \
        SOMEDAY_MEDIA_BACKEND=filesystem \
        SOMEDAY_MEDIA_BLOB_DIR="$RESTORED_MEDIA_DIR"
    missing_file="$(find "$RESTORED_MEDIA_DIR" -type f -name object.bin -print -quit)"
    [[ -n "$missing_file" ]] || die "PlanetScale recovery fixture has no published image"
    mv "$missing_file" "$RUN_DIR/temporarily-missing-media.bin"
    set +e
    run_managed_integrity \
        "$SOMEDAY_PLANETSCALE_RESTORE_JDBC_URL" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_USER" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_PASSWORD" \
        verify-full \
        SOMEDAY_MEDIA_BACKEND=filesystem \
        SOMEDAY_MEDIA_BLOB_DIR="$RESTORED_MEDIA_DIR"
    missing_status=$?
    set -e
    [[ "$missing_status" -eq 2 ]] || die "missing media verifier returned $missing_status instead of 2"
    mv "$RUN_DIR/temporarily-missing-media.bin" "$missing_file"

    backend_port="$(free_port)"
    start_managed_server \
        "$SOMEDAY_PLANETSCALE_RESTORE_JDBC_URL" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_USER" \
        "$SOMEDAY_PLANETSCALE_RESTORE_APP_PASSWORD" \
        verify-full "$backend_port" false "$RUN_DIR/restored-server.log" \
        SOMEDAY_MEDIA_BACKEND=filesystem \
        SOMEDAY_MEDIA_BLOB_DIR="$RESTORED_MEDIA_DIR"
    finish_recovery_journey "$SERVER_PORT" "$backend_port"
    psql_row_manifest "$SOMEDAY_PLANETSCALE_RESTORE_ADMIN_PSQL_URL" "$RUN_DIR/restored-post-client-rows.tsv"
    media_manifest "$RESTORED_MEDIA_DIR" "$RUN_DIR/restored-post-client-media.tsv"
    cmp -s "$source_rows" "$RUN_DIR/restored-post-client-rows.tsv" ||
        die "PlanetScale row counts changed during read-only client verification"
    cmp -s "$RUN_DIR/source-media.tsv" "$RUN_DIR/restored-post-client-media.tsv" ||
        die "PlanetScale media changed during read-only client verification"
    cp "$source_owners" "$FINAL_DIR/source-owners.tsv"
    cp "$restored_owners" "$FINAL_DIR/restored-owners.tsv"
    cp "$source_owners.flyway" "$FINAL_DIR/source-flyway.csv"
    cp "$restored_owners.flyway" "$FINAL_DIR/restored-flyway.csv"
    cp "$source_rows" "$FINAL_DIR/source-rows.tsv"
    cp "$restored_rows" "$FINAL_DIR/restored-rows.tsv"
    cp "$RUN_DIR/source-media.tsv" "$FINAL_DIR/source-media.tsv"
    cp "$RUN_DIR/restored-media.tsv" "$FINAL_DIR/restored-media.tsv"
    write_success_result planetscale "$source_target" "$restore_target"
    info "PlanetScale live profile passed"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    initialize_managed_gate
    for command in curl git java python3 pg_dump pg_restore psql tar; do
        require_command "$command"
    done
    [[ -x "$GRADLEW" ]] || die "Gradle wrapper is not executable: $GRADLEW"
    cd "$ROOT_DIR"
    run_planetscale_gate
fi
