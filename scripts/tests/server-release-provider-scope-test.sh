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
    "$TEST_ROOT/server/src/main/kotlin/saien/someday/server/persistence" \
    "$TEST_ROOT/server/src/main/kotlin/saien/someday/server/media" \
    "$TEST_ROOT/gradle"
printf 'baseline\n' >"$TEST_ROOT/README.md"
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

printf 'database change\n' >"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/persistence/SyncV2Repository.kt"
git -C "$TEST_ROOT" add .
git -C "$TEST_ROOT" commit -qm database
result="$(scope_check requirement planetscale 1.2.4 HEAD)"
[[ "$result" == required$'\t'server-v1.2.3$'\t'* ]] ||
    fail "database change did not require PlanetScale: $result"
result="$(scope_check requirement r2 1.2.4 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.3$'\t'* ]] ||
    fail "database-only change unexpectedly required R2: $result"
git -C "$TEST_ROOT" tag server-v1.2.4

printf 's3 change\n' >"$TEST_ROOT/server/src/main/kotlin/saien/someday/server/media/S3MediaBlobStore.kt"
git -C "$TEST_ROOT" add .
git -C "$TEST_ROOT" commit -qm media
result="$(scope_check requirement r2 1.2.5 HEAD)"
[[ "$result" == required$'\t'server-v1.2.4$'\t'* ]] ||
    fail "S3 change did not require R2: $result"
result="$(scope_check requirement planetscale 1.2.5 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.4$'\t'* ]] ||
    fail "S3-only change unexpectedly required PlanetScale: $result"
git -C "$TEST_ROOT" tag server-v1.2.5

printf 'aws-sdk = "2"\n' >>"$TEST_ROOT/gradle/libs.versions.toml"
git -C "$TEST_ROOT" commit -qam aws-dependency
result="$(scope_check requirement r2 1.2.6 HEAD)"
[[ "$result" == required$'\t'server-v1.2.5$'\t'* ]] ||
    fail "AWS dependency change did not require R2: $result"
result="$(scope_check requirement planetscale 1.2.6 HEAD)"
[[ "$result" == skipped$'\t'server-v1.2.5$'\t'* ]] ||
    fail "AWS-only dependency change unexpectedly required PlanetScale: $result"

result="$(scope_check requirement planetscale 1.3.0 HEAD)"
[[ "$result" == required$'\t-'$'\t'*minor* ]] ||
    fail "minor release did not require a full provider certification: $result"

printf 'server-release-provider-scope=passed\n'
