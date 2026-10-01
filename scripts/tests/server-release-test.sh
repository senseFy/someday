#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd -P)"
RELEASE_SCRIPT="$ROOT_DIR/scripts/server-release"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/someday-server-release-test.XXXXXX")"
trap 'rm -rf "$TEST_ROOT"' EXIT

VERSION=1.2.3
SHA=0123456789abcdef0123456789abcdef01234567
STUB_BIN="$TEST_ROOT/bin"
CALL_LOG="$TEST_ROOT/calls.log"
PROVIDER_CALL_LOG="$TEST_ROOT/provider-calls.log"
SYSTEM_REPORT="$TEST_ROOT/system-report.json"
MANAGED_REPORT_ROOT="$TEST_ROOT/managed"
REPORT_ROOT="$TEST_ROOT/releases"
mkdir -p "$STUB_BIN"
: >"$CALL_LOG"
: >"$PROVIDER_CALL_LOG"

fail() {
    printf 'server release workflow test error: %s\n' "$*" >&2
    exit 1
}

cat >"$STUB_BIN/git" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'git' >>"$FAKE_CALL_LOG"
printf ' %q' "$@" >>"$FAKE_CALL_LOG"
printf '\n' >>"$FAKE_CALL_LOG"
if [[ "${1:-}" == -C ]]; then
    shift 2
fi
case "${1:-}" in
    rev-parse)
        case "${2:-}" in
            HEAD) printf '%s\n' "$FAKE_SHA" ;;
            --short=12) printf '%.12s\n' "$FAKE_SHA" ;;
            server-v*)
                if [[ -n "${FAKE_LOCAL_TAG_COMMIT:-}" ]]; then
                    printf '%s\n' "$FAKE_LOCAL_TAG_COMMIT"
                else
                    exit 1
                fi
                ;;
            *) exit 1 ;;
        esac
        ;;
    branch)
        [[ "${2:-}" == --show-current ]] || exit 2
        printf '%s\n' "${FAKE_BRANCH:-main}"
        ;;
    status)
        [[ "${FAKE_DIRTY:-false}" != true ]] || printf ' M README.md\n'
        ;;
    merge-base)
        [[ "${2:-}" == --is-ancestor ]] || exit 2
        [[ "${FAKE_TAG_IN_MAIN:-true}" == true ]]
        ;;
    ls-remote)
        case " $* " in
            *' --heads '*)
                printf '%s\trefs/heads/main\n' "${FAKE_REMOTE_MAIN:-$FAKE_SHA}"
                ;;
            *' --tags '*)
                if [[ -n "${FAKE_REMOTE_TAG_COMMIT:-}" ]]; then
                    printf '%s\trefs/tags/server-v1.2.3\n' "$FAKE_REMOTE_TAG_COMMIT"
                else
                    exit 2
                fi
                ;;
            *) exit 2 ;;
        esac
        ;;
    tag|push)
        printf 'forbidden Git mutation\n' >&2
        exit 99
        ;;
    *)
        printf 'unexpected fake git invocation: %s\n' "$*" >&2
        exit 2
        ;;
esac
SH

cat >"$STUB_BIN/gh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'gh' >>"$FAKE_CALL_LOG"
printf ' %q' "$@" >>"$FAKE_CALL_LOG"
printf '\n' >>"$FAKE_CALL_LOG"
if [[ "${1:-}" == repo && "${2:-}" == view ]]; then
    printf '%s\t%s\n' "${FAKE_REPOSITORY:-senseFy/someday}" \
        "${FAKE_VISIBILITY:-PUBLIC}"
    exit 0
fi
if [[ "${1:-}" == api ]]; then
    for argument in "$@"; do
        case "$argument" in
            --method|--method=*|-X|-X*|--input|--input=*|-f|-f*|--raw-field|--raw-field=*|-F|-F*|--field|--field=*)
                printf 'forbidden gh api write option: %s\n' "$argument" >&2
                exit 99
                ;;
        esac
    done
    if [[ "${2:-}" == \
        'repos/senseFy/someday/rulesets?includes_parents=true&per_page=100' ]]; then
        if [[ "${FAKE_TAG_POLICY:-protected}" != missing ]]; then
            printf '7\n'
        fi
        exit 0
    fi
    if [[ "${2:-}" == 'repos/senseFy/someday/rulesets/7' ]]; then
        bypass_mode=always
        if [[ "${FAKE_TAG_POLICY:-protected}" == exempt ]]; then
            bypass_mode=exempt
        fi
        printf '%s\n' \
            "{\"name\":\"Server release tags\",\"target\":\"tag\",\"enforcement\":\"active\",\"bypass_actors\":[{\"actor_type\":\"User\",\"actor_id\":1,\"bypass_mode\":\"$bypass_mode\"}],\"conditions\":{\"ref_name\":{\"include\":[\"refs/tags/server-v*\"],\"exclude\":[]}},\"rules\":[{\"type\":\"creation\"},{\"type\":\"update\"},{\"type\":\"deletion\"}]}"
        exit 0
    fi
fi
if [[ "${1:-}" == run && "${2:-}" == list ]]; then
    workflow=''
    previous=''
    requested_commit=''
    for argument in "$@"; do
        if [[ "$previous" == --workflow ]]; then
            workflow="$argument"
        fi
        if [[ "$previous" == --commit ]]; then
            requested_commit="$argument"
        fi
        previous="$argument"
    done
    if [[ "$workflow" == ci.yml ]]; then
        conclusion="${FAKE_CI_CONCLUSION:-success}"
        [[ -n "$conclusion" ]] || conclusion=-
        printf '%s\t%s\tmain\t%s\thttps://example.test/ci\n' \
            "${FAKE_CI_STATUS:-completed}" \
            "$conclusion" \
            "${requested_commit:-$FAKE_SHA}"
        exit 0
    fi
    if [[ "$workflow" == server-release.yml ]] &&
        [[ "${FAKE_PUBLISHED:-false}" == true ]]; then
        if [[ "${FAKE_RELEASE_EVENT:-push}" == workflow_dispatch ]]; then
            printf 'completed\tsuccess\tworkflow_dispatch\t%s\t%s\thttps://example.test/run\n' \
                "${FAKE_RELEASE_BRANCH:-main}" "$FAKE_SHA"
        else
            printf 'completed\tsuccess\tpush\tserver-v1.2.3\t%s\thttps://example.test/run\n' \
                "${FAKE_REMOTE_TAG_COMMIT:-$FAKE_SHA}"
        fi
        exit 0
    fi
fi
if [[ "${FAKE_PUBLISHED:-false}" == true && "${1:-}" == release && "${2:-}" == view ]]; then
    printf 'false\thttps://example.test/release\n'
    exit 0
fi
printf 'forbidden or unexpected fake gh invocation: %s\n' "$*" >&2
exit 99
SH

cat >"$STUB_BIN/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker' >>"$FAKE_CALL_LOG"
printf ' %q' "$@" >>"$FAKE_CALL_LOG"
printf '\n' >>"$FAKE_CALL_LOG"
case "${1:-} ${2:-}" in
    'build --target'|'image rm') exit 0 ;;
esac
printf 'unexpected fake docker invocation: %s\n' "$*" >&2
exit 2
SH

cat >"$STUB_BIN/curl" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'curl' >>"$FAKE_CALL_LOG"
printf ' %q' "$@" >>"$FAKE_CALL_LOG"
printf '\n' >>"$FAKE_CALL_LOG"
printf 'curl is forbidden in the local release controller\n' >&2
exit 99
SH

cat >"$STUB_BIN/pass" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s' "${FAKE_STUB_NAME:-pass}" >>"$FAKE_CALL_LOG"
printf ' %q' "$@" >>"$FAKE_CALL_LOG"
printf '\n' >>"$FAKE_CALL_LOG"
SH

cat >"$STUB_BIN/fail" <<'SH'
#!/usr/bin/env bash
exit 37
SH

cat >"$STUB_BIN/system-gate" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'system-gate\n' >>"$FAKE_CALL_LOG"
mkdir -p "$(dirname "$FAKE_SYSTEM_REPORT")"
printf '{"commit":"%s","dirtyWorktree":%s,"result":"%s"}\n' \
    "${FAKE_SYSTEM_COMMIT:-$FAKE_SHA}" \
    "${FAKE_SYSTEM_DIRTY:-false}" \
    "${FAKE_SYSTEM_RESULT:-passed}" \
    >"$FAKE_SYSTEM_REPORT"
SH

cat >"$STUB_BIN/provider-scope" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'provider-scope' >>"$FAKE_PROVIDER_CALL_LOG"
printf ' %q' "$@" >>"$FAKE_PROVIDER_CALL_LOG"
printf '\n' >>"$FAKE_PROVIDER_CALL_LOG"
command="$1"
profile="$2"
[[ "${FAKE_PROVIDER_ERROR:-}" != "$command" ]] || exit 2
if [[ "$command" == requirement ]]; then
    case "$profile" in
        planetscale) state="${FAKE_PLANETSCALE_SCOPE:-required}" ;;
        r2) state="${FAKE_R2_SCOPE:-required}" ;;
        *) exit 2 ;;
    esac
    printf '%s\tserver-v1.2.2\ttest %s scope\n' "$state" "$profile"
    exit 0
fi
if [[ "$command" == changes ]]; then
    case ",${FAKE_PROVIDER_CHANGES:-}," in
        *,all,*|*,"$profile",*) printf 'server/provider-change.kt\n'; exit 0 ;;
        *) exit 1 ;;
    esac
fi
exit 2
SH

chmod 0755 "$STUB_BIN"/*

probe_ruleset="$(
    FAKE_CALL_LOG="$CALL_LOG" FAKE_TAG_POLICY=protected \
        "$STUB_BIN/gh" api \
        'repos/senseFy/someday/rulesets?includes_parents=true&per_page=100' \
        --jq '.id'
)"
[[ "$probe_ruleset" == 7 ]] || fail 'fake GitHub ruleset response is invalid'
probe_ci="$(
    FAKE_CALL_LOG="$CALL_LOG" FAKE_SHA="$SHA" FAKE_CI_STATUS=completed \
        FAKE_CI_CONCLUSION=success FAKE_PUBLISHED=false \
        "$STUB_BIN/gh" run list --workflow ci.yml
)"
[[ "$probe_ci" == $'completed\tsuccess\tmain\t'"$SHA"$'\thttps://example.test/ci' ]] ||
    fail 'fake GitHub CI response is invalid'
: >"$CALL_LOG"

run_release() {
    PATH="$STUB_BIN:$PATH" \
    FAKE_CALL_LOG="$CALL_LOG" \
    FAKE_PROVIDER_CALL_LOG="$PROVIDER_CALL_LOG" \
    FAKE_SHA="${FAKE_SHA:-$SHA}" \
    FAKE_BRANCH="${FAKE_BRANCH:-main}" \
    FAKE_DIRTY="${FAKE_DIRTY:-false}" \
    FAKE_REMOTE_MAIN="${FAKE_REMOTE_MAIN:-$SHA}" \
    FAKE_LOCAL_TAG_COMMIT="${FAKE_LOCAL_TAG_COMMIT:-}" \
    FAKE_REMOTE_TAG_COMMIT="${FAKE_REMOTE_TAG_COMMIT:-}" \
    FAKE_RELEASE_EVENT="${FAKE_RELEASE_EVENT:-push}" \
    FAKE_RELEASE_BRANCH="${FAKE_RELEASE_BRANCH:-main}" \
    FAKE_TAG_IN_MAIN="${FAKE_TAG_IN_MAIN:-true}" \
    FAKE_PUBLISHED="${FAKE_PUBLISHED:-false}" \
    FAKE_REPOSITORY="${FAKE_REPOSITORY:-senseFy/someday}" \
    FAKE_VISIBILITY="${FAKE_VISIBILITY:-PUBLIC}" \
    FAKE_TAG_POLICY="${FAKE_TAG_POLICY:-protected}" \
    FAKE_CI_STATUS="${FAKE_CI_STATUS:-completed}" \
    FAKE_CI_CONCLUSION="${FAKE_CI_CONCLUSION:-success}" \
    GH_TOKEN=test-token \
    GITHUB_TOKEN=test-token \
    FAKE_SYSTEM_REPORT="$SYSTEM_REPORT" \
    FAKE_SYSTEM_COMMIT="${FAKE_SYSTEM_COMMIT:-$SHA}" \
    FAKE_SYSTEM_DIRTY="${FAKE_SYSTEM_DIRTY:-false}" \
    FAKE_SYSTEM_RESULT="${FAKE_SYSTEM_RESULT:-passed}" \
    GRADLEW="$STUB_BIN/pass" \
    SOMEDAY_SERVER_RELEASE_HISTORY_CHECK="${FAKE_HISTORY_CHECK:-$STUB_BIN/pass}" \
    SOMEDAY_SERVER_RELEASE_PRIVATE_CHECK="$STUB_BIN/pass" \
    SOMEDAY_SERVER_RELEASE_CONTRACT_CHECK="$STUB_BIN/pass" \
    SOMEDAY_SERVER_RELEASE_SYSTEM_GATE="$STUB_BIN/system-gate" \
    SOMEDAY_SERVER_RELEASE_COMPOSE_SMOKE="${FAKE_COMPOSE_SMOKE:-$STUB_BIN/pass}" \
    SOMEDAY_SERVER_RELEASE_SYSTEM_REPORT="$SYSTEM_REPORT" \
    SOMEDAY_SERVER_RELEASE_MANAGED_REPORT_ROOT="$MANAGED_REPORT_ROOT" \
    SOMEDAY_SERVER_RELEASE_PROVIDER_SCOPE_CHECK="$STUB_BIN/provider-scope" \
    SOMEDAY_SERVER_RELEASE_REPORT_ROOT="$REPORT_ROOT" \
    FAKE_PLANETSCALE_SCOPE="${FAKE_PLANETSCALE_SCOPE:-required}" \
    FAKE_R2_SCOPE="${FAKE_R2_SCOPE:-required}" \
    FAKE_PROVIDER_CHANGES="${FAKE_PROVIDER_CHANGES:-}" \
    FAKE_PROVIDER_ERROR="${FAKE_PROVIDER_ERROR:-}" \
    SOMEDAY_SERVER_RELEASE_FORCE_MANAGED="${SOMEDAY_SERVER_RELEASE_FORCE_MANAGED:-}" \
    SOMEDAY_SERVER_RELEASE_DOCKER=docker \
        "$RELEASE_SCRIPT" "$@"
}

write_managed_report() {
    local profile="$1"
    local commit="${2:-$SHA}"
    local path="$MANAGED_REPORT_ROOT/$profile/result.json"
    mkdir -p "$(dirname "$path")"
    printf '{"profile":"%s","commit":"%s","completedAt":"2026-08-30T00:00:00Z","treeState":"clean","result":"passed","releaseEligible":true}\n' \
        "$profile" "$commit" >"$path"
}

expect_failure() {
    local label="$1"
    shift
    if "$@" >"$TEST_ROOT/$label.out" 2>&1; then
        fail "$label unexpectedly succeeded"
    fi
}

prepare_rehearsal_guard() {
    : >"$CALL_LOG"
    rm -f "$REPORT_ROOT/$VERSION/rehearsal.json" "$SYSTEM_REPORT"
}

assert_rehearsal_guard() {
    local label="$1"
    [[ ! -f "$REPORT_ROOT/$VERSION/rehearsal.json" ]] ||
        fail "$label wrote passing rehearsal evidence"
    if grep -Fq 'docker build' "$CALL_LOG"; then
        fail "$label reached the image build"
    fi
}

assert_rehearsal_cleanup() {
    local label="$1"
    local prefix="someday-server:release-rehearsal-$VERSION-${SHA:0:12}-"
    local image
    image="$(sed -n 's/^docker image rm --force //p' "$CALL_LOG")"
    [[ "$image" == "$prefix"* && "${image#"$prefix"}" =~ ^[0-9]+$ ]] ||
        fail "$label did not clean exactly one task-owned image"
    if grep -Fq 'unbound variable' "$TEST_ROOT/$label.out"; then
        fail "$label lost its cleanup state"
    fi
    [[ ! -f "$REPORT_ROOT/$VERSION/rehearsal.json" ]] ||
        fail "$label wrote passing rehearsal evidence"
}

assert_ready_without_provider_checks() {
    local label="$1"
    : >"$PROVIDER_CALL_LOG"
    if ! run_release status "$VERSION" >"$TEST_ROOT/$label.out" 2>&1; then
        sed -n '1,160p' "$TEST_ROOT/$label.out" >&2
        fail "$label blocked publication on provider certification"
    fi
    grep -Fq 'READY TO TAG' "$TEST_ROOT/$label.out" ||
        fail "$label did not produce READY TO TAG"
    [[ ! -s "$PROVIDER_CALL_LOG" ]] ||
        fail "$label invoked the provider scope checker"
    if grep -Eq '^(PASS|ACTION|FAIL)[[:space:]]+providers[[:space:]]' "$TEST_ROOT/$label.out"; then
        fail "$label reported certification as a public release check"
    fi
}

assert_provider_failure() {
    local label="$1"
    local status
    if run_release providers "$VERSION" >"$TEST_ROOT/$label.out" 2>&1; then
        fail "$label unexpectedly verified provider certification"
    else
        status=$?
    fi
    [[ "$status" -eq 1 ]] || fail "$label returned $status instead of 1"
    grep -Fq 'PROVIDERS NOT VERIFIED' "$TEST_ROOT/$label.out" ||
        fail "$label omitted the provider certification outcome"
}

[[ -x "$RELEASE_SCRIPT" ]] || fail "release script is not executable: $RELEASE_SCRIPT"
run_release help >"$TEST_ROOT/help.out"
grep -Fq 'never creates or pushes a tag' "$TEST_ROOT/help.out" ||
    fail 'help does not state the remote-write boundary'
grep -Fq 'scripts/server-release providers X.Y.Z' "$TEST_ROOT/help.out" ||
    fail 'help omits the independent provider certification command'

run_release plan "$VERSION" >"$TEST_ROOT/plan.out"
grep -Fq "git tag -a server-v$VERSION" "$TEST_ROOT/plan.out" ||
    fail 'plan omits the explicit annotated-tag command'
grep -Fq "git push origin refs/tags/server-v$VERSION:refs/tags/server-v$VERSION" \
    "$TEST_ROOT/plan.out" ||
    fail 'plan omits the exact tag push'
[[ ! -s "$CALL_LOG" ]] || fail 'plan unexpectedly ran a release check'

expect_failure status-before-evidence run_release status "$VERSION"
grep -Fq 'ACTION  rehearsal' "$TEST_ROOT/status-before-evidence.out" ||
    fail 'status did not identify missing rehearsal evidence'
[[ ! -s "$PROVIDER_CALL_LOG" ]] || fail 'status consulted provider certification'
assert_provider_failure providers-before-evidence
grep -Fq 'planetscale: missing' "$TEST_ROOT/providers-before-evidence.out" ||
    fail 'providers did not identify missing PlanetScale evidence'
grep -Fq 'r2: missing' "$TEST_ROOT/providers-before-evidence.out" ||
    fail 'providers did not identify missing R2 evidence'

FAKE_VISIBILITY=PRIVATE \
    expect_failure private-repository run_release status "$VERSION"
grep -Fq 'visibility=PRIVATE (unchanged)' "$TEST_ROOT/private-repository.out" ||
    fail 'status did not report the private repository boundary'

FAKE_REPOSITORY=another/example \
    expect_failure wrong-repository run_release status "$VERSION"
grep -Fq 'repository=another/example' "$TEST_ROOT/wrong-repository.out" ||
    fail 'status did not report the unexpected repository identity'

prepare_rehearsal_guard
FAKE_DIRTY=true expect_failure dirty-rehearsal run_release rehearse "$VERSION"
grep -Fq 'rehearsal requires a clean worktree' "$TEST_ROOT/dirty-rehearsal.out" ||
    fail 'dirty rehearsal failed for the wrong reason'
assert_rehearsal_guard dirty-rehearsal

prepare_rehearsal_guard
FAKE_BRANCH=feature expect_failure branch-rehearsal run_release rehearse "$VERSION"
grep -Fq 'rehearsal requires main' "$TEST_ROOT/branch-rehearsal.out" ||
    fail 'non-main rehearsal failed for the wrong reason'
assert_rehearsal_guard branch-rehearsal

prepare_rehearsal_guard
FAKE_REMOTE_MAIN=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    expect_failure remote-main-rehearsal run_release rehearse "$VERSION"
grep -Fq 'HEAD must exactly match origin/main' "$TEST_ROOT/remote-main-rehearsal.out" ||
    fail 'remote-main rehearsal failed for the wrong reason'
assert_rehearsal_guard remote-main-rehearsal

prepare_rehearsal_guard
FAKE_LOCAL_TAG_COMMIT="$SHA" \
    expect_failure local-tag-rehearsal run_release rehearse "$VERSION"
grep -Fq 'local tag already exists' "$TEST_ROOT/local-tag-rehearsal.out" ||
    fail 'local-tag rehearsal failed for the wrong reason'
assert_rehearsal_guard local-tag-rehearsal

prepare_rehearsal_guard
FAKE_REMOTE_TAG_COMMIT="$SHA" \
    expect_failure remote-tag-rehearsal run_release rehearse "$VERSION"
grep -Fq 'remote tag already exists' "$TEST_ROOT/remote-tag-rehearsal.out" ||
    fail 'remote-tag rehearsal failed for the wrong reason'
assert_rehearsal_guard remote-tag-rehearsal

for field in commit dirty result; do
    prepare_rehearsal_guard
    case "$field" in
        commit)
            FAKE_SYSTEM_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
                expect_failure system-commit run_release rehearse "$VERSION"
            ;;
        dirty)
            FAKE_SYSTEM_DIRTY=true \
                expect_failure system-dirty run_release rehearse "$VERSION"
            ;;
        result)
            FAKE_SYSTEM_RESULT=failed \
                expect_failure system-result run_release rehearse "$VERSION"
            ;;
    esac
    assert_rehearsal_guard "system-$field"
    assert_rehearsal_cleanup "system-$field"
done

for phase in history compose; do
    prepare_rehearsal_guard
    history_check="$STUB_BIN/pass"
    compose_smoke="$STUB_BIN/pass"
    case "$phase" in
        history) history_check="$STUB_BIN/fail" ;;
        compose) compose_smoke="$STUB_BIN/fail" ;;
    esac
    if FAKE_HISTORY_CHECK="$history_check" FAKE_COMPOSE_SMOKE="$compose_smoke" \
        run_release rehearse "$VERSION" >"$TEST_ROOT/$phase-failure.out" 2>&1; then
        fail "$phase failure unexpectedly succeeded"
    else
        failure_status=$?
    fi
    [[ "$failure_status" -eq 37 ]] ||
        fail "$phase failure lost its original exit status: $failure_status"
    assert_rehearsal_cleanup "$phase-failure"
    if [[ "$phase" == history ]]; then
        assert_rehearsal_guard history-failure
    else
        cleanup_image="$(sed -n 's/^docker image rm --force //p' "$CALL_LOG")"
        grep -Fq -- "--tag $cleanup_image " "$CALL_LOG" ||
            fail 'compose failure did not clean the image it built'
    fi
done

: >"$CALL_LOG"
if ! run_release rehearse "$VERSION" >"$TEST_ROOT/rehearse.out" 2>&1; then
    sed -n '1,160p' "$TEST_ROOT/rehearse.out" >&2
    fail 'rehearsal unexpectedly failed'
fi
[[ -f "$REPORT_ROOT/$VERSION/rehearsal.json" ]] || fail 'rehearsal report was not written'
python3 -B - "$REPORT_ROOT/$VERSION/rehearsal.json" "$SHA" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = {
    "contractId": "someday-server-release-rehearsal",
    "version": "1.2.3",
    "tag": "server-v1.2.3",
    "commit": sys.argv[2],
    "treeState": "clean",
    "result": "passed",
}
for key, wanted in expected.items():
    if value.get(key) != wanted:
        raise SystemExit(f"unexpected rehearsal field {key}: {value.get(key)!r}")
PY

if ! run_release status "$VERSION" >"$TEST_ROOT/status-ready.out" 2>&1; then
    sed -n '1,160p' "$TEST_ROOT/status-ready.out" >&2
    sed -n '1,200p' "$CALL_LOG" >&2
    fail 'passing evidence did not produce a ready status'
fi
grep -Fq 'READY TO TAG' "$TEST_ROOT/status-ready.out" ||
    fail 'passing release evidence without provider evidence did not produce READY TO TAG'

assert_ready_without_provider_checks status-missing-providers
SOMEDAY_SERVER_RELEASE_FORCE_MANAGED=all FAKE_PROVIDER_ERROR=requirement \
    assert_ready_without_provider_checks status-forced-providers
SOMEDAY_SERVER_RELEASE_FORCE_MANAGED=invalid-profile FAKE_PROVIDER_ERROR=requirement \
    assert_ready_without_provider_checks status-invalid-provider-option

write_managed_report planetscale
write_managed_report r2
provider_call_start="$(wc -l <"$CALL_LOG" | tr -d '[:space:]')"
run_release providers "$VERSION" >"$TEST_ROOT/providers-ready.out"
grep -Fq 'PROVIDERS VERIFIED' "$TEST_ROOT/providers-ready.out" ||
    fail 'passing provider evidence did not produce PROVIDERS VERIFIED'
if tail -n "+$((provider_call_start + 1))" "$CALL_LOG" | grep -Eq '^gh |^docker |^curl |^git .*ls-remote'; then
    fail 'providers performed a remote query or started a container'
fi

FAKE_DIRTY=true assert_provider_failure providers-dirty

rm -f "$MANAGED_REPORT_ROOT/r2/result.json"
FAKE_R2_SCOPE=skipped \
    assert_provider_failure providers-r2-skipped-missing
grep -Fq 'r2: missing' "$TEST_ROOT/providers-r2-skipped-missing.out" ||
    fail 'unchanged R2 scope was treated as evidence of certification'

ANCESTOR_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
write_managed_report r2 "$ANCESTOR_SHA"
FAKE_R2_SCOPE=skipped \
    run_release providers "$VERSION" >"$TEST_ROOT/providers-r2-skipped-ancestor.out"
grep -Fq "r2: passed at ${ANCESTOR_SHA:0:12}" "$TEST_ROOT/providers-r2-skipped-ancestor.out" ||
    fail 'unchanged R2 scope did not retain valid ancestor evidence'
grep -Fq 'PROVIDERS VERIFIED' "$TEST_ROOT/providers-r2-skipped-ancestor.out" ||
    fail 'valid unchanged R2 certification was not verified'
write_managed_report r2

write_managed_report planetscale "$ANCESTOR_SHA"
run_release providers "$VERSION" >"$TEST_ROOT/providers-ancestor-evidence.out"
grep -Fq "planetscale: passed at ${ANCESTOR_SHA:0:12}" \
    "$TEST_ROOT/providers-ancestor-evidence.out" ||
    fail 'unchanged provider scope did not accept ancestor evidence'
FAKE_PROVIDER_CHANGES=planetscale \
    assert_provider_failure changed-after-evidence
grep -Fq 'relevant changes after evidence' "$TEST_ROOT/changed-after-evidence.out" ||
    fail 'providers accepted changes made after live evidence'
FAKE_PROVIDER_CHANGES=planetscale SOMEDAY_SERVER_RELEASE_FORCE_MANAGED=all \
    assert_ready_without_provider_checks status-stale-providers

FAKE_PROVIDER_ERROR=changes assert_provider_failure provider-comparison-error
grep -Fq 'scope comparison failed' "$TEST_ROOT/provider-comparison-error.out" ||
    fail 'providers treated a failed scope comparison as unchanged'
FAKE_PROVIDER_ERROR=requirement assert_provider_failure provider-requirement-error
FAKE_TAG_IN_MAIN=false assert_provider_failure provider-outside-history
grep -Fq 'outside release history' "$TEST_ROOT/provider-outside-history.out" ||
    fail 'providers accepted evidence outside the current commit history'

SOMEDAY_SERVER_RELEASE_FORCE_MANAGED=all FAKE_PLANETSCALE_SCOPE=skipped FAKE_R2_SCOPE=skipped \
    assert_provider_failure providers-forced-ancestor
grep -Fq 'full certification evidence must match' "$TEST_ROOT/providers-forced-ancestor.out" ||
    fail 'forced certification reused ancestor evidence'
write_managed_report planetscale
SOMEDAY_SERVER_RELEASE_FORCE_MANAGED=all FAKE_PLANETSCALE_SCOPE=skipped FAKE_R2_SCOPE=skipped \
    run_release providers "$VERSION" >"$TEST_ROOT/providers-forced-current.out"
grep -Fq 'PROVIDERS VERIFIED' "$TEST_ROOT/providers-forced-current.out" ||
    fail 'forced certification rejected current passing evidence'

write_managed_report planetscale "$ANCESTOR_SHA"
write_managed_report r2 "$ANCESTOR_SHA"
: >"$PROVIDER_CALL_LOG"
FAKE_SHA="$ANCESTOR_SHA" FAKE_REMOTE_TAG_COMMIT="$SHA" \
    run_release providers "$VERSION" >"$TEST_ROOT/providers-head.out"
grep -Fqx "provider-scope requirement planetscale $VERSION $ANCESTOR_SHA" "$PROVIDER_CALL_LOG" ||
    fail 'providers did not classify current HEAD independently of a published tag'
grep -Fq 'PROVIDERS VERIFIED' "$TEST_ROOT/providers-head.out" ||
    fail 'providers did not verify current HEAD evidence'
write_managed_report planetscale
write_managed_report r2

for field in profile treeState result releaseEligible commit; do
    python3 - "$MANAGED_REPORT_ROOT/r2/result.json" "$field" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
value = json.loads(path.read_text())
value[sys.argv[2]] = False if sys.argv[2] == "releaseEligible" else "invalid"
path.write_text(json.dumps(value))
PY
    assert_provider_failure "providers-invalid-$field"
    write_managed_report r2
done

FAKE_TAG_POLICY=missing \
    expect_failure missing-tag-policy run_release status "$VERSION"
grep -Fq 'ACTION  policy' "$TEST_ROOT/missing-tag-policy.out" ||
    fail 'status did not block an unprotected release tag'

FAKE_TAG_POLICY=exempt \
    expect_failure exempt-tag-policy run_release status "$VERSION"
grep -Fq 'ACTION  policy' "$TEST_ROOT/exempt-tag-policy.out" ||
    fail 'status did not block an unaudited tag-policy bypass'

FAKE_CI_STATUS=in_progress FAKE_CI_CONCLUSION='' \
    expect_failure pending-ci run_release status "$VERSION"
grep -Fq 'RUNNING ci' "$TEST_ROOT/pending-ci.out" ||
    fail 'status did not wait for main CI'

FAKE_CI_CONCLUSION=failure \
    expect_failure failed-ci run_release status "$VERSION"
grep -Fq 'ACTION  ci' "$TEST_ROOT/failed-ci.out" ||
    fail 'status did not block failed main CI'
grep -Fq 'NOT READY' "$TEST_ROOT/failed-ci.out" ||
    fail 'failed main CI did not produce NOT READY'

FAKE_HISTORY_CHECK="$STUB_BIN/fail" \
    expect_failure failed-history run_release status "$VERSION"
grep -Fq 'FAIL    history' "$TEST_ROOT/failed-history.out" ||
    fail 'status did not block a failed public-history check'
grep -Fq 'NOT READY' "$TEST_ROOT/failed-history.out" ||
    fail 'failed history did not produce NOT READY'

cp "$REPORT_ROOT/$VERSION/rehearsal.json" "$TEST_ROOT/rehearsal-good.json"
for field in commit treeState result; do
    python3 - "$REPORT_ROOT/$VERSION/rehearsal.json" "$field" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
value = json.loads(path.read_text())
value[sys.argv[2]] = "invalid"
path.write_text(json.dumps(value))
PY
    expect_failure "status-invalid-rehearsal-$field" run_release status "$VERSION"
    grep -Fq 'ACTION  rehearsal' "$TEST_ROOT/status-invalid-rehearsal-$field.out" ||
        fail "status accepted invalid rehearsal $field"
    grep -Fq 'NOT READY' "$TEST_ROOT/status-invalid-rehearsal-$field.out" ||
        fail "invalid rehearsal $field did not produce NOT READY"
    cp "$TEST_ROOT/rehearsal-good.json" "$REPORT_ROOT/$VERSION/rehearsal.json"
done

FAKE_REMOTE_TAG_COMMIT="$SHA" FAKE_PUBLISHED=true \
    run_release status "$VERSION" >"$TEST_ROOT/status-published.out"
grep -Fq 'RELEASE COMPLETE' "$TEST_ROOT/status-published.out" ||
    fail 'a passing published workflow did not produce RELEASE COMPLETE'

CONTROL_SHA=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
FAKE_SHA="$CONTROL_SHA" FAKE_REMOTE_MAIN="$CONTROL_SHA" \
    FAKE_REMOTE_TAG_COMMIT="$SHA" FAKE_RELEASE_EVENT=workflow_dispatch \
    FAKE_PUBLISHED=true \
    run_release status "$VERSION" >"$TEST_ROOT/status-recovered.out"
grep -Fq "commit ${SHA:0:12}" "$TEST_ROOT/status-recovered.out" ||
    fail 'recovered status did not use the immutable tag commit'
grep -Fq 'tag commit is in main history' "$TEST_ROOT/status-recovered.out" ||
    fail 'recovered status did not validate tag ancestry'
grep -Fq 'RELEASE COMPLETE' "$TEST_ROOT/status-recovered.out" ||
    fail 'a passing recovered workflow did not produce RELEASE COMPLETE'

FAKE_SHA="$CONTROL_SHA" FAKE_REMOTE_MAIN="$CONTROL_SHA" \
    FAKE_REMOTE_TAG_COMMIT="$SHA" FAKE_RELEASE_EVENT=workflow_dispatch \
    FAKE_RELEASE_BRANCH=feature FAKE_PUBLISHED=true \
    expect_failure status-recovery-branch run_release status "$VERSION"
grep -Fq 'recovery workflow is not in current main history' \
    "$TEST_ROOT/status-recovery-branch.out" ||
    fail 'status accepted recovery from a non-main branch'

FAKE_REMOTE_TAG_COMMIT="$SHA" FAKE_TAG_IN_MAIN=false \
    expect_failure status-tag-outside-main run_release status "$VERSION"
grep -Fq 'tag commit is outside main history' \
    "$TEST_ROOT/status-tag-outside-main.out" ||
    fail 'status accepted a tag outside main history'
grep -Fq 'docker build' "$CALL_LOG" || fail 'rehearsal did not build an image'
grep -Fq 'system-gate' "$CALL_LOG" || fail 'rehearsal did not run System V3'

if grep -Eq '^git( [^ ]+)* (tag|push)( |$)|^gh api .*( --method(=| )| -X([^ ]*)?( |$)| --input(=| )| -f([^ ]*)?( |$)| -F([^ ]*)?( |$)| --field(=| )| --raw-field(=| ))|^docker push( |$)|^curl( |$)' \
    "$CALL_LOG"; then
    fail 'the workflow performed a remote mutation'
fi
while IFS= read -r call; do
    case "$call" in
        'gh repo view '*|'gh run list '*|'gh release view '*|'gh api repos/senseFy/someday/rulesets'*) ;;
        *) fail "unexpected GitHub operation: $call" ;;
    esac
done < <(grep '^gh ' "$CALL_LOG" || true)

expect_failure invalid-version run_release plan v1.2.3
expect_failure leading-zero-version run_release plan 01.2.3
long_component='11111111111111111111111111111111111111111111111111111111111111111'
expect_failure long-version run_release plan "$long_component.1.1"
expect_failure missing-version run_release status
expect_failure publish-command run_release publish "$VERSION"

MAKEFLAGS='-- SERVER_RELEASE_VERSION=9.9.9' \
    MAKEOVERRIDES='SERVER_RELEASE_VERSION=9.9.9' \
    SERVER_RELEASE_VERSION=9.9.9 \
    _SOMEDAY_SERVER_RELEASE_VERSION_ARG=9.9.9 \
    "$SCRIPT_DIR/server-release-interface-test.sh" >/dev/null
"$SCRIPT_DIR/server-release-provider-scope-test.sh" >/dev/null
"$SCRIPT_DIR/build-server-release-bundle-test.sh" >/dev/null
"$SCRIPT_DIR/verify-public-history-test.sh" >/dev/null
"$SCRIPT_DIR/verify-server-release-tag-test.sh" >/dev/null
"$SCRIPT_DIR/verify-server-release-contract-test.sh" >/dev/null
"$ROOT_DIR/scripts/verify-server-release-contract" \
    "server-v$VERSION" \
    "ghcr.io/sensefy/someday-server:$VERSION" \
    sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    >/dev/null

printf 'server-release-workflow=passed\n'
