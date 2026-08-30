#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
CHECK="$ROOT_DIR/scripts/server-release-provider-scope"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/someday-provider-scope-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() {
    printf 'server release provider scope test error: %s\n' "$*" >&2
    exit 1
}

scope_check() {
    SOMEDAY_SERVER_RELEASE_SCOPE_ROOT="$TEST_ROOT" "$CHECK" "$@"
}

git -C "$TEST_ROOT" init -q
git -C "$TEST_ROOT" config user.name Test
git -C "$TEST_ROOT" config user.email test@example.invalid
mkdir -p \
    "$TEST_ROOT/docker" \
    "$TEST_ROOT/server/src/main/kotlin/saien/someday/server" \
    "$TEST_ROOT/server/src/main/kotlin/saien/someday/server/persistence" \
    "$TEST_ROOT/server/src/main/kotlin/saien/someday/server/media" \
    "$TEST_ROOT/gradle"
printf 'baseline\n' >"$TEST_ROOT/README.md"
printf 'baseline\n' >"$TEST_ROOT/server/build.gradle.kts"
printf 'baseline\n' >"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/Application.kt"
printf 'baseline\n' >"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/ServerConfig.kt"
printf 'baseline\n' >"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/ServerContext.kt"
printf 'baseline\n' >"$TEST_ROOT/docker/entrypoint.sh"
printf '[versions]\naws-sdk = "1"\npostgresql = "1"\n' >"$TEST_ROOT/gradle/libs.versions.toml"
printf '<verification-metadata/>\n' >"$TEST_ROOT/gradle/verification-metadata.xml"
git -C "$TEST_ROOT" add .
git -C "$TEST_ROOT" commit -qm baseline
git -C "$TEST_ROOT" tag server-v1.2.3

printf 'documentation only\n' >>"$TEST_ROOT/README.md"
git -C "$TEST_ROOT" commit -qam docs
result="$(scope_check requirement planetscale 1.2.4 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.3$'\t'* ]] ||
    fail "documentation unexpectedly required PlanetScale: $result"
result="$(scope_check requirement r2 1.2.4 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.3$'\t'* ]] ||
    fail "documentation unexpectedly required R2: $result"

printf 'application wiring\n' >>"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/Application.kt"
git -C "$TEST_ROOT" commit -qam application
application_path=server/src/main/kotlin/saien/someday/server/Application.kt
if result="$(scope_check changes planetscale HEAD^ HEAD)"; then
    fail "generic application wiring unexpectedly required PlanetScale: $result"
fi
if result="$(scope_check changes r2 HEAD^ HEAD)"; then
    fail "generic application wiring unexpectedly required R2: $result"
fi

printf 'repository wiring\n' >>"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/ServerContext.kt"
git -C "$TEST_ROOT" commit -qam context
context_path=server/src/main/kotlin/saien/someday/server/ServerContext.kt
result="$(scope_check changes planetscale HEAD^ HEAD)"
[[ "$result" == "$context_path" ]] ||
    fail "server repository wiring did not require PlanetScale: $result"
if result="$(scope_check changes r2 HEAD^ HEAD)"; then
    fail "generic server repository wiring unexpectedly required R2: $result"
fi

printf '%s\n' \
    'import saien.someday.server.routes.workspaceRecoveryEnvelopeRoutes' \
    'workspaceRecoveryEnvelopeRoutes(context)' \
    >>"$TEST_ROOT/$application_path"
printf '%s\n' \
    'import saien.someday.server.persistence.WorkspaceRecoveryEnvelopeRepository' \
    'val workspaceRecoveryEnvelopeRepository: WorkspaceRecoveryEnvelopeRepository,' \
    'workspaceRecoveryEnvelopeRepository = WorkspaceRecoveryEnvelopeRepository(' \
    'config,' \
    'activeDatabaseConnectionPool,' \
    '),' \
    >>"$TEST_ROOT/$context_path"
git -C "$TEST_ROOT" commit -qam recovery-wiring
result="$(scope_check changes planetscale HEAD^ HEAD)"
[[ "$result" == "$context_path" ]] ||
    fail "PostgreSQL recovery wiring did not require PlanetScale: $result"
if result="$(scope_check changes r2 HEAD^ HEAD)"; then
    fail "PostgreSQL recovery wiring unexpectedly required R2: $result"
fi

mkdir -p "$TEST_ROOT/server/src/main/kotlin/saien/someday/server/routes"
route_path=server/src/main/kotlin/saien/someday/server/routes/SystemV3Routes.kt
printf 'media route change\n' >"$TEST_ROOT/$route_path"
git -C "$TEST_ROOT" add .
git -C "$TEST_ROOT" commit -qm explicit-media-route
result="$(scope_check changes r2 HEAD^ HEAD)"
[[ "$result" == "$route_path" ]] ||
    fail "explicit media route did not require R2: $result"
if result="$(scope_check changes planetscale HEAD^ HEAD)"; then
    fail "media route unexpectedly required PlanetScale: $result"
fi
git -C "$TEST_ROOT" tag server-v1.2.4

printf 'database change\n' >"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/persistence/SyncV2Repository.kt"
git -C "$TEST_ROOT" add .
git -C "$TEST_ROOT" commit -qm database
result="$(scope_check requirement planetscale 1.2.5 HEAD)"
[[ "$result" == required$'\t'server-v1.2.4$'\t'* ]] ||
    fail "database change did not require PlanetScale: $result"
result="$(scope_check requirement r2 1.2.5 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.4$'\t'* ]] ||
    fail "database-only change unexpectedly required R2: $result"
git -C "$TEST_ROOT" tag server-v1.2.5

printf 's3 change\n' >"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/media/S3MediaBlobStore.kt"
git -C "$TEST_ROOT" add .
git -C "$TEST_ROOT" commit -qm media
result="$(scope_check requirement r2 1.2.6 HEAD)"
[[ "$result" == required$'\t'server-v1.2.5$'\t'* ]] ||
    fail "S3 change did not require R2: $result"
result="$(scope_check requirement planetscale 1.2.6 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.5$'\t'* ]] ||
    fail "S3-only change unexpectedly required PlanetScale: $result"
git -C "$TEST_ROOT" tag server-v1.2.6

printf 'aws-sdk = "2"\n' >>"$TEST_ROOT/gradle/libs.versions.toml"
git -C "$TEST_ROOT" commit -qam aws-dependency
result="$(scope_check requirement r2 1.2.7 HEAD)"
[[ "$result" == required$'\t'server-v1.2.6$'\t'* ]] ||
    fail "AWS dependency change did not require R2: $result"
result="$(scope_check requirement planetscale 1.2.7 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.6$'\t'* ]] ||
    fail "AWS-only dependency change unexpectedly required PlanetScale: $result"
git -C "$TEST_ROOT" tag server-v1.2.7

printf 'postgresql = "2"\n' >>"$TEST_ROOT/gradle/libs.versions.toml"
git -C "$TEST_ROOT" commit -qam postgres-dependency
result="$(scope_check requirement planetscale 1.2.8 HEAD)"
[[ "$result" == required$'\t'server-v1.2.7$'\t'* ]] ||
    fail "PostgreSQL dependency change did not require PlanetScale: $result"
result="$(scope_check requirement r2 1.2.8 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.7$'\t'* ]] ||
    fail "PostgreSQL-only dependency change unexpectedly required R2: $result"
git -C "$TEST_ROOT" tag server-v1.2.8

for common_path in \
    server/src/main/kotlin/saien/someday/server/ServerConfig.kt \
    server/build.gradle.kts \
    docker/entrypoint.sh; do
    printf 'shared provider contract\n' >>"$TEST_ROOT/$common_path"
    git -C "$TEST_ROOT" commit -qam "common-$common_path"
    result="$(scope_check changes planetscale HEAD^ HEAD)"
    [[ "$result" == "$common_path" ]] ||
        fail "$common_path did not require PlanetScale: $result"
    result="$(scope_check changes r2 HEAD^ HEAD)"
    [[ "$result" == "$common_path" ]] ||
        fail "$common_path did not require R2: $result"
done

result="$(scope_check requirement planetscale 1.3.0 HEAD)"
[[ "$result" == required$'\t-'$'\t'*minor* ]] ||
    fail "minor release did not require a full provider certification: $result"
result="$(scope_check requirement r2 1.3.0 HEAD)"
[[ "$result" == required$'\t-'$'\t'*minor* ]] ||
    fail "minor release did not require full R2 certification: $result"

printf 'server-release-provider-scope=passed\n'
