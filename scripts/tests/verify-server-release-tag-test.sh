#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
VERIFY_SCRIPT="$ROOT_DIR/scripts/verify-server-release-tag"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/someday-release-tag-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

TAG=server-v1.2.3
COMMIT=0123456789abcdef0123456789abcdef01234567
TAG_OBJECT=89abcdef0123456789abcdef0123456789abcdef
CALL_LOG="$TEST_ROOT/calls.log"
mkdir -p "$TEST_ROOT/bin"
: >"$CALL_LOG"

fail() {
    printf 'server release tag test error: %s\n' "$*" >&2
    exit 1
}

cat >"$TEST_ROOT/bin/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'gh' >>"$FAKE_CALL_LOG"
printf ' %q' "$@" >>"$FAKE_CALL_LOG"
printf '\n' >>"$FAKE_CALL_LOG"
[[ "${1:-}" == api ]] || exit 99
for argument in "$@"; do
    case "$argument" in
        --method|--method=*|-X|-X*|--input|--input=*|-f|-f*|--raw-field|--raw-field=*|-F|-F*|--field|--field=*) exit 99 ;;
    esac
done
case "${2:-}" in
    'repos/senseFy/someday/rulesets?includes_parents=true&per_page=100')
        if [[ "${FAKE_TAG_MODE:-valid}" != missing-policy ]]; then
            printf '7\n'
        fi
        ;;
    repos/senseFy/someday/rulesets/7)
        if [[ "${FAKE_TAG_MODE:-valid}" == weak-policy ]]; then
            printf '%s\n' \
                '{"target":"tag","enforcement":"active","conditions":{"ref_name":{"include":["refs/tags/server-v*"],"exclude":[]}},"rules":[{"type":"creation"}]}'
        else
            printf '%s\n' \
                '{"target":"tag","enforcement":"active","conditions":{"ref_name":{"include":["refs/tags/server-v*"],"exclude":[]}},"rules":[{"type":"creation"},{"type":"update"},{"type":"deletion"}]}'
        fi
        ;;
    repos/senseFy/someday/git/ref/tags/server-v1.2.3)
        if [[ "${FAKE_TAG_MODE:-valid}" == lightweight ]]; then
            printf 'commit\t%s\n' "$FAKE_COMMIT"
        else
            printf 'tag\t%s\n' "$FAKE_TAG_OBJECT"
        fi
        ;;
    repos/senseFy/someday/git/tags/89abcdef0123456789abcdef0123456789abcdef)
        case "${FAKE_TAG_MODE:-valid}" in
            nested) printf 'server-v1.2.3\ttag\t%s\n' "$FAKE_COMMIT" ;;
            renamed) printf 'server-v9.9.9\tcommit\t%s\n' "$FAKE_COMMIT" ;;
            moved) printf 'server-v1.2.3\tcommit\taaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n' ;;
            *) printf 'server-v1.2.3\tcommit\t%s\n' "$FAKE_COMMIT" ;;
        esac
        ;;
    *) exit 99 ;;
esac
SH
chmod 0755 "$TEST_ROOT/bin/gh"

run_verify() {
    local tag="${1:-$TAG}"
    local commit="${2:-$COMMIT}"
    PATH="$TEST_ROOT/bin:$PATH" \
    FAKE_CALL_LOG="$CALL_LOG" \
    FAKE_COMMIT="$COMMIT" \
    FAKE_TAG_OBJECT="$TAG_OBJECT" \
    FAKE_TAG_MODE="${FAKE_TAG_MODE:-valid}" \
    GITHUB_REPOSITORY=senseFy/someday \
    GITHUB_REF_NAME="$tag" \
    GITHUB_REF_PROTECTED="${GITHUB_REF_PROTECTED:-true}" \
    GH_TOKEN=test-token \
        "$VERIFY_SCRIPT" "$tag" "$commit"
}

expect_failure() {
    local label="$1"
    shift
    if "$@" >"$TEST_ROOT/$label.out" 2>&1; then
        fail "$label unexpectedly succeeded"
    fi
}

[[ -x "$VERIFY_SCRIPT" ]] || fail 'tag verifier is not executable'
run_verify >"$TEST_ROOT/valid.out"
grep -Fq 'protected annotated tag confirmed' "$TEST_ROOT/valid.out" ||
    fail 'valid protected tag was not confirmed'

for mode in lightweight nested renamed moved; do
    FAKE_TAG_MODE="$mode" expect_failure "$mode" run_verify
done
for mode in missing-policy weak-policy; do
    FAKE_TAG_MODE="$mode" expect_failure "$mode" run_verify
    grep -Fq 'no active ruleset prevents creation, update, and deletion' \
        "$TEST_ROOT/$mode.out" || fail "$mode failed for the wrong reason"
done
GITHUB_REF_PROTECTED=false expect_failure unprotected run_verify
expect_failure leading-zero run_verify server-v01.2.3 "$COMMIT"

[[ "$(wc -l <"$CALL_LOG" | tr -d ' ')" -eq 22 ]] ||
    fail 'tag verifier made an unexpected number of GitHub reads'
if grep -Eq -- '--method|-X|--input|--field|--raw-field|-f|-F' "$CALL_LOG"; then
    fail 'tag verifier attempted a GitHub write'
fi

printf 'verify-server-release-tag=passed\n'
