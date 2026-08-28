#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -r "$TEST_ROOT"' EXIT
ROOT_DIR="$TEST_ROOT"
result_file="$ROOT_DIR/build/managed-storage-profile-gate/planetscale/result.json"
mkdir -p "$(dirname "$result_file")"
printf '{"sentinel":true}\n' >"$result_file"

# The profile script exposes only pure validators when sourced.
source "$REPO_ROOT/scripts/lib/managed-storage-planetscale.sh"
[[ "$(cat "$result_file")" == '{"sentinel":true}' ]]

source_jdbc='jdbc:postgresql://shared.pg.psdb.cloud:5432/postgres?sslmode=verify-full'
restore_jdbc="$source_jdbc"
source_psql='postgresql://someday_app.branch_a@shared.pg.psdb.cloud:5432/postgres?sslmode=verify-full&sslrootcert=system'
restore_psql='postgresql://someday_app.branch_b@shared.pg.psdb.cloud:5432/postgres?sslmode=verify-full&sslrootcert=system'
source_target='branch_a@shared.pg.psdb.cloud:5432/postgres'
restore_target='branch_b@shared.pg.psdb.cloud:5432/postgres'

require_direct_tls_url source "$source_jdbc"
require_psql_tls_url source \
    'postgresql://someday_app.branch_a@source.horizon.psdb.cloud:5432/someday?sslmode=verify-full&sslrootcert=system'
require_direct_tls_url current \
    'jdbc:postgresql://aws-ap-northeast-1-2.pg.psdb.cloud:5432/postgres?sslmode=verify-full'
require_psql_tls_url current \
    'postgresql://someday_app.branch_a@aws-ap-northeast-1-2.pg.psdb.cloud:5432/postgres?sslmode=verify-full&sslrootcert=system&sslnegotiation=direct'

if require_direct_tls_url invalid \
    'jdbc:postgresql://ordinary-postgres.example:5432/someday?sslmode=verify-full' 2>/dev/null; then
    printf 'non-PlanetScale host passed validation\n' >&2
    exit 1
fi
if require_psql_tls_url invalid \
    'postgresql://app.branch:secret@source.horizon.psdb.cloud:5432/someday?sslmode=verify-full&sslrootcert=system' 2>/dev/null; then
    printf 'password-bearing PostgreSQL URL passed validation\n' >&2
    exit 1
fi
if require_psql_tls_url invalid \
    'postgresql://app.branch@source.horizon.psdb.cloud:5432/someday?sslmode=verify-full' 2>/dev/null; then
    printf 'PostgreSQL URL without the system trust store passed validation\n' >&2
    exit 1
fi
if require_direct_tls_url invalid \
    'jdbc:postgresql://evil.example,source.horizon.psdb.cloud:5432/someday?sslmode=verify-full' 2>/dev/null; then
    printf 'multi-host JDBC URL passed validation\n' >&2
    exit 1
fi
if require_psql_tls_url invalid \
    'postgresql://app.branch@evil.example,source.horizon.psdb.cloud:5432/someday?sslmode=verify-full&sslrootcert=system' \
    2>/dev/null; then
    printf 'multi-host PostgreSQL URL passed validation\n' >&2
    exit 1
fi
if require_direct_tls_url invalid \
    'jdbc:postgresql://source.horizon.psdb.cloud:5432/someday?PGHOST=evil.example&sslmode=verify-full' \
    2>/dev/null; then
    printf 'target-overriding JDBC URL passed validation\n' >&2
    exit 1
fi
if require_psql_tls_url invalid \
    'postgresql://app.branch@source.horizon.psdb.cloud:5432/someday?host=evil.example&sslmode=verify-full&sslrootcert=system' \
    2>/dev/null; then
    printf 'target-overriding PostgreSQL URL passed validation\n' >&2
    exit 1
fi
if validate_planetscale_target_confirmation \
    "$source_psql" "$source_psql" "$source_target,$source_target" >/dev/null 2>&1; then
    printf 'identical source and restore targets passed validation\n' >&2
    exit 1
fi
if validate_planetscale_target_confirmation \
    "$source_psql" "$restore_psql" wrong-confirmation >/dev/null 2>&1; then
    printf 'incorrect reset target confirmation passed validation\n' >&2
    exit 1
fi

actual="$(validate_planetscale_target_confirmation \
    "$source_psql" "$restore_psql" "$source_target,$restore_target")"
[[ "$actual" == "$source_target"$'\t'"$restore_target" ]]
[[ "$(planetscale_base_username 'someday_app.branch_a')" == someday_app ]]
[[ "$(cat "$result_file")" == '{"sentinel":true}' ]]

caller_pgpass="$TEST_ROOT/caller.pgpass"
gate_pgpass="$TEST_ROOT/gate.pgpass"
printf 'caller-owned\n' >"$caller_pgpass"
printf 'gate-owned\n' >"$gate_pgpass"
(
    PGPASSFILE="$caller_pgpass"
    MANAGED_GATE_PGPASSFILE="$gate_pgpass"
    cleanup
)
[[ -f "$caller_pgpass" ]]
[[ ! -e "$gate_pgpass" ]]

source "$REPO_ROOT/scripts/lib/managed-storage-r2.sh"
if rg -q -- '(--env|-e)[[:space:]]+(ACCESS_KEY|SECRET_KEY)=' \
    "$REPO_ROOT/scripts/lib/managed-storage-r2.sh"; then
    printf 'R2 credential value is exposed in a docker command argument\n' >&2
    exit 1
fi

retry_output="$TEST_ROOT/wrangler-output.txt"
retry_attempts=0
retry_sleeps=0
wrangler() {
    retry_attempts=$((retry_attempts + 1))
    if ((retry_attempts < 3)); then
        printf 'partial output\n'
        return 28
    fi
    printf 'complete output\n'
}
sleep() {
    retry_sleeps=$((retry_sleeps + 1))
}
r2_wrangler_read "$retry_output" "$TEST_ROOT/wrangler" r2 bucket info test --json 2>/dev/null
[[ "$retry_attempts" == 3 ]]
[[ "$retry_sleeps" == 2 ]]
[[ "$(cat "$retry_output")" == 'complete output' ]]

retry_attempts=0
wrangler() {
    retry_attempts=$((retry_attempts + 1))
    printf 'partial output\n'
    return 42
}
if r2_wrangler_read "$retry_output" "$TEST_ROOT/wrangler" \
    r2 bucket info test --json 2>/dev/null; then
    printf 'exhausted Wrangler reads passed validation\n' >&2
    exit 1
else
    retry_status=$?
fi
[[ "$retry_status" == 42 ]]
[[ "$retry_attempts" == 3 ]]
[[ ! -e "$retry_output" ]]
unset -f wrangler sleep

r2_copy="$TEST_ROOT/r2-copy"
r2_expected="$TEST_ROOT/r2-expected.tsv"
r2_current="$TEST_ROOT/r2-current.tsv"
r2_system="$TEST_ROOT/r2-system.tsv"
r2_application="$TEST_ROOT/r2-application.tsv"
mkdir -p \
    "$r2_copy/media/v1/.someday-system" \
    "$r2_copy/media/v1/user/workspace"
printf 'startup-probe' >"$r2_copy/media/v1/.someday-system/startup-probe-v1.bin"
printf 'ciphertext' >"$r2_copy/media/v1/user/workspace/media.bin"
media_manifest "$r2_copy" "$r2_expected"
r2_prepare_restore_manifests \
    "$r2_copy" "$r2_expected" "$r2_current" "$r2_system" "$r2_application"
[[ "$(wc -l <"$r2_system" | tr -d '[:space:]')" == 1 ]]
[[ "$(wc -l <"$r2_application" | tr -d '[:space:]')" == 1 ]]

printf 'changed-ciphertext' >"$r2_copy/media/v1/user/workspace/media.bin"
if (r2_prepare_restore_manifests \
    "$r2_copy" "$r2_expected" "$r2_current" "$r2_system" "$r2_application") 2>/dev/null; then
    printf 'changed R2 bytes matched the retained manifest\n' >&2
    exit 1
fi
printf 'ciphertext' >"$r2_copy/media/v1/user/workspace/media.bin"
printf 'extra' >"$r2_copy/media/v1/user/workspace/extra.bin"
if (r2_prepare_restore_manifests \
    "$r2_copy" "$r2_expected" "$r2_current" "$r2_system" "$r2_application") 2>/dev/null; then
    printf 'extra R2 file matched the retained manifest\n' >&2
    exit 1
fi

printf 'managed-storage-profile-preflight=passed\n'
